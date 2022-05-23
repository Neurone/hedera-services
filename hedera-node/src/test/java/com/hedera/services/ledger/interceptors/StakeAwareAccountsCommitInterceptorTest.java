package com.hedera.services.ledger.interceptors;

/*-
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2022 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import com.hedera.services.context.SideEffectsTracker;
import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.ledger.EntityChangeSet;
import com.hedera.services.ledger.accounts.staking.RewardCalculator;
import com.hedera.services.ledger.properties.AccountProperty;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleNetworkContext;
import com.hedera.services.state.merkle.MerkleStakingInfo;
import com.hedera.services.utils.EntityNum;
import com.hedera.test.factories.accounts.MerkleAccountFactory;
import com.hederahashgraph.api.proto.java.AccountID;
import com.swirlds.common.system.Address;
import com.swirlds.common.system.AddressBook;
import com.swirlds.merkle.map.MerkleMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.hedera.services.ledger.accounts.staking.RewardCalculator.zoneUTC;
import static com.hedera.services.state.migration.ReleaseTwentySevenMigration.buildStakingInfoMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willCallRealMethod;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StakeAwareAccountsCommitInterceptorTest {
	@Mock
	private SideEffectsTracker sideEffectsTracker;
	@Mock
	private MerkleMap<EntityNum, MerkleAccount> accounts;
	@Mock
	private MerkleNetworkContext networkCtx;
	@Mock
	private GlobalDynamicProperties dynamicProperties;
	@Mock
	private RewardCalculator rewardCalculator;
	@Mock
	private StakeChangeManager manager;
	@Mock
	private AddressBook addressBook;
	@Mock
	private Address address1 = mock(Address.class);
	@Mock
	private Address address2 = mock(Address.class);

	private MerkleMap<EntityNum, MerkleStakingInfo> stakingInfo;
	private StakeAwareAccountsCommitsInterceptor subject;

	private static final long stakePeriodStart = LocalDate.now(zoneUTC).toEpochDay() - 1;

	@BeforeEach
	void setUp() {
		subject = new StakeAwareAccountsCommitsInterceptor(sideEffectsTracker, () -> networkCtx, () -> stakingInfo,
				dynamicProperties, () -> accounts,
				rewardCalculator, manager);
		stakingInfo = buildsStakingInfoMap();
	}

	@Test
	void noChangesAreNoop() {
		final var changes = new EntityChangeSet<AccountID, MerkleAccount, AccountProperty>();

		subject.preview(changes);

		verifyNoInteractions(sideEffectsTracker);
	}

	@Test
	void calculatesRewardIfNeeded() {
		final var amount = 5L;

		final var changes = new EntityChangeSet<AccountID, MerkleAccount, AccountProperty>();
		changes.include(partyId, party, randomStakedNodeChanges(partyBalance + amount));
		changes.include(counterpartyId, counterparty, randomStakedNodeChanges(counterpartyBalance - amount));
		counterparty.setStakePeriodStart(stakePeriodStart - 2);

		given(networkCtx.areRewardsActivated()).willReturn(true);
		given(rewardCalculator.updateRewardChanges(counterparty, changes.changes(1))).willReturn(1L);
		given(rewardCalculator.latestRewardableStakePeriodStart()).willReturn(stakePeriodStart - 1);

		subject.preview(changes);

		verify(sideEffectsTracker).trackHbarChange(partyId.getAccountNum(), +amount);
		verify(sideEffectsTracker).trackHbarChange(counterpartyId.getAccountNum(), -amount);
		verify(rewardCalculator).updateRewardChanges(counterparty, changes.changes(1));
		verify(sideEffectsTracker).trackRewardPayment(counterpartyId.getAccountNum(), 1L);
	}

	@Test
	void checksIfRewardsToBeActivatedEveryHandle() {
		stakingInfo.forEach((a, b) -> b.setRewardSumHistory(new long[] { 5, 5 }));
		subject.setRewardsActivated(false);
		subject.setRewardBalanceChanged(true);
		subject.setNewRewardBalance(10L);
		assertTrue(subject.isRewardBalanceChanged());
		assertEquals(10L, subject.getNewRewardBalance());
		given(dynamicProperties.getStakingStartThreshold()).willReturn(20L);
		assertFalse(subject.shouldActivateStakingRewards());

		subject.activateRewardsIfValid();
		verify(networkCtx, never()).setStakingRewardsActivated(true);

		subject.setNewRewardBalance(20L);
		assertEquals(20L, subject.getNewRewardBalance());
		assertTrue(subject.shouldActivateStakingRewards());

		assertEquals(5L, stakingInfo.get(EntityNum.fromLong(3L)).getRewardSumHistory()[0]);
		assertEquals(5L, stakingInfo.get(EntityNum.fromLong(4L)).getRewardSumHistory()[0]);
		assertEquals(5L, stakingInfo.get(EntityNum.fromLong(3L)).getRewardSumHistory()[1]);
		assertEquals(5L, stakingInfo.get(EntityNum.fromLong(4L)).getRewardSumHistory()[1]);

		subject.activateRewardsIfValid();
		verify(networkCtx).setStakingRewardsActivated(true);
		verify(accounts).forEach(any());
		assertEquals(0L, stakingInfo.get(EntityNum.fromLong(3L)).getRewardSumHistory()[0]);
		assertEquals(0L, stakingInfo.get(EntityNum.fromLong(4L)).getRewardSumHistory()[0]);
		assertEquals(0L, stakingInfo.get(EntityNum.fromLong(3L)).getRewardSumHistory()[1]);
		assertEquals(0L, stakingInfo.get(EntityNum.fromLong(4L)).getRewardSumHistory()[1]);
	}

	@Test
	void checksIfRewardable() {
		given(networkCtx.areRewardsActivated()).willReturn(true);
		counterparty.setStakePeriodStart(-1);
		assertFalse(subject.isRewardable(counterparty, randomStakedNodeChanges(100L), stakePeriodStart));

		counterparty.setStakePeriodStart(stakePeriodStart - 2);
		assertTrue(subject.isRewardable(counterparty, randomStakedNodeChanges(100L), stakePeriodStart));

		given(networkCtx.areRewardsActivated()).willReturn(false);
		assertFalse(subject.isRewardable(counterparty, randomStakedNodeChanges(100L), stakePeriodStart));

		counterparty.setDeclineReward(true);
		given(networkCtx.areRewardsActivated()).willReturn(true);
		assertTrue(subject.isRewardable(counterparty, randomStakedNodeChanges(100L), stakePeriodStart));
	}

	@Test
	void returnsIfRewardsShouldBeActivated() {
		subject.setRewardsActivated(true);
		assertTrue(subject.isRewardsActivated());
		assertFalse(subject.shouldActivateStakingRewards());

		subject.setNewRewardBalance(10L);
		assertFalse(subject.shouldActivateStakingRewards());

		subject.setRewardsActivated(false);
		assertFalse(subject.isRewardsActivated());
		assertFalse(subject.isRewardBalanceChanged());
		assertFalse(subject.shouldActivateStakingRewards());

		subject.setRewardBalanceChanged(true);
		assertTrue(subject.isRewardBalanceChanged());
		assertEquals(10L, subject.getNewRewardBalance());
		given(dynamicProperties.getStakingStartThreshold()).willReturn(20L);
		assertFalse(subject.shouldActivateStakingRewards());

		subject.setNewRewardBalance(20L);
		assertEquals(20L, subject.getNewRewardBalance());
		assertTrue(subject.shouldActivateStakingRewards());
	}

	@Test
	void activatesStakingRewardsAndClearsRewardSumHistoryAsExpected() {
		final long stakingFee = 2L;
		final var inorder = inOrder(sideEffectsTracker);
		given(dynamicProperties.getStakingStartThreshold()).willReturn(1L);

		final var changes = new EntityChangeSet<AccountID, MerkleAccount, AccountProperty>();
		changes.include(partyId, party, randomStakedNodeChanges(partyBalance + amount));
		changes.include(counterpartyId, counterparty,
				randomStakedNodeChanges(counterpartyBalance - amount - stakingFee));
		changes.include(stakingFundId, stakingFund, randomStakedNodeChanges(stakingFee));
		willCallRealMethod().given(networkCtx).areRewardsActivated();
		willCallRealMethod().given(networkCtx).setStakingRewardsActivated(true);
		willCallRealMethod().given(accounts).forEach(any());
		given(accounts.entrySet()).willReturn(Map.of(
				EntityNum.fromAccountId(counterpartyId), counterparty,
				EntityNum.fromAccountId(partyId), party,
				EntityNum.fromAccountId(stakingFundId), stakingFund).entrySet());
		counterparty.setStakePeriodStart(-1L);

		stakingInfo.forEach((a, b) -> b.setRewardSumHistory(new long[] { 5, 5 }));

		final var mockLocalDate = mock(LocalDate.class);
		final var mockedStatic = mockStatic(LocalDate.class);
		mockedStatic.when(() -> LocalDate.now(zoneUTC)).thenReturn(mockLocalDate);
		when(mockLocalDate.toEpochDay()).thenReturn(19131L);

		// rewardsSumHistory is not cleared
		assertEquals(5, stakingInfo.get(EntityNum.fromLong(3L)).getRewardSumHistory()[0]);
		assertEquals(5, stakingInfo.get(EntityNum.fromLong(4L)).getRewardSumHistory()[0]);
		assertEquals(-1, counterparty.getStakePeriodStart());
		assertEquals(-1, party.getStakePeriodStart());

		subject.preview(changes);
		mockedStatic.close();

		inorder.verify(sideEffectsTracker).trackHbarChange(partyId.getAccountNum(), +amount);
		inorder.verify(sideEffectsTracker).trackHbarChange(counterpartyId.getAccountNum(), -amount - stakingFee);
		inorder.verify(sideEffectsTracker).trackHbarChange(stakingFundId.getAccountNum(), 1L);
		verify(networkCtx).setStakingRewardsActivated(true);

		// rewardsSumHistory is cleared
		assertEquals(0, stakingInfo.get(EntityNum.fromLong(3L)).getRewardSumHistory()[0]);
		assertEquals(0, stakingInfo.get(EntityNum.fromLong(4L)).getRewardSumHistory()[0]);
		assertEquals(19131, counterparty.getStakePeriodStart());
		assertEquals(-1, party.getStakePeriodStart());
	}

	@Test
	void findsOrAddsAccountAsExpected() {
		final var pendingChanges = buildPendingNodeStakeChanges();
		assertEquals(1, pendingChanges.size());

		final var num = subject.findOrAdd(partyId.getAccountNum(), pendingChanges);
		assertEquals(1, num);
		assertEquals(2, pendingChanges.size());
	}

	@Test
	void paysRewardIfRewardable() {
		final var hasBeenRewarded = new HashSet<Long>();

		final var pendingChanges = buildPendingNodeStakeChanges();
		assertEquals(1, pendingChanges.size());

		subject.payRewardIfRewardable(pendingChanges, 0, Set.of(), stakePeriodStart - 2);
		verify(rewardCalculator, never()).updateRewardChanges(counterparty, pendingChanges.changes(0));

		given(networkCtx.areRewardsActivated()).willReturn(true);
		counterparty.setStakePeriodStart(stakePeriodStart - 2);
		subject.payRewardIfRewardable(pendingChanges, 0, hasBeenRewarded, stakePeriodStart - 1);
		verify(rewardCalculator).updateRewardChanges(counterparty, pendingChanges.changes(0));
		verify(sideEffectsTracker).trackRewardPayment(eq(counterpartyId.getAccountNum()), anyLong());
		assertEquals(1, hasBeenRewarded.size());
	}

	@Test
	void stakingEffectsWorkAsExpectedWhenStakingToNode() {
		final var inorderST = inOrder(sideEffectsTracker);
		final var inorderM = inOrder(manager);

		final var pendingChanges = buildPendingNodeStakeChanges();
		final Map<AccountProperty, Object> stakingFundChanges = Map.of(AccountProperty.BALANCE, 100L);
		given(rewardCalculator.latestRewardableStakePeriodStart()).willReturn(stakePeriodStart - 1);
		given(rewardCalculator.updateRewardChanges(counterparty, pendingChanges.changes(0))).willReturn(10l);
		given(networkCtx.areRewardsActivated()).willReturn(true);
		pendingChanges.include(stakingFundId, stakingFund, stakingFundChanges);
		stakingFund.setStakePeriodStart(-1);
		counterparty.setStakePeriodStart(stakePeriodStart - 2);

		willCallRealMethod().given(manager).getNodeStakeeNum(any());
		willCallRealMethod().given(manager).getAccountStakeeNum(any());
		willCallRealMethod().given(manager).finalStakedToMeGiven(any(), any());
		willCallRealMethod().given(manager).finalDeclineRewardGiven(any(), any());

		subject.preview(pendingChanges);

		inorderST.verify(sideEffectsTracker).trackHbarChange(321L, -455L);
		inorderST.verify(sideEffectsTracker).trackHbarChange(800L, 99L);
		inorderST.verify(sideEffectsTracker).trackRewardPayment(321L, 10L);
		inorderM.verify(manager, never()).updateStakedToMe(anyInt(), anyLong(), any());
		inorderM.verify(manager).getAccountStakeeNum(pendingChanges.changes(0));
		inorderM.verify(manager).getAccountStakeeNum(pendingChanges.changes(1));
		inorderM.verify(manager).getNodeStakeeNum(pendingChanges.changes(0));
		inorderM.verify(manager).finalDeclineRewardGiven(counterparty, pendingChanges.changes(0));
		inorderM.verify(manager).withdrawStake(1L, counterpartyBalance + counterparty.getStakedToMe(), false);
		inorderM.verify(manager).finalStakedToMeGiven(counterparty, pendingChanges.changes(0));
		inorderM.verify(manager).awardStake(2L, 2100, false);
	}

	@Test
	void stakingEffectsWorkAsExpectedWhenStakingToAccount() {
		final var inorderST = inOrder(sideEffectsTracker);
		final var inorderM = inOrder(manager);

		final var pendingChanges = buildPendingAccountStakeChanges();
		final Map<AccountProperty, Object> stakingFundChanges = Map.of(AccountProperty.BALANCE, 100L);
		given(rewardCalculator.latestRewardableStakePeriodStart()).willReturn(stakePeriodStart - 1);
		given(rewardCalculator.updateRewardChanges(counterparty, pendingChanges.changes(0))).willReturn(10l);
		given(networkCtx.areRewardsActivated()).willReturn(true);
		pendingChanges.include(stakingFundId, stakingFund, stakingFundChanges);
		stakingFund.setStakePeriodStart(-1);
		counterparty.setStakePeriodStart(stakePeriodStart - 2);

		willCallRealMethod().given(manager).getNodeStakeeNum(any());
		willCallRealMethod().given(manager).getAccountStakeeNum(any());
		willCallRealMethod().given(manager).finalDeclineRewardGiven(any(), any());

		subject.preview(pendingChanges);

		inorderST.verify(sideEffectsTracker).trackHbarChange(321L, -455L);
		inorderST.verify(sideEffectsTracker).trackHbarChange(800L, 99L);
		inorderST.verify(sideEffectsTracker).trackRewardPayment(321L, 10L);

		inorderM.verify(manager).getAccountStakeeNum(pendingChanges.changes(0));
		inorderM.verify(manager).updateStakedToMe(2, 100, pendingChanges);
		inorderM.verify(manager).getAccountStakeeNum(pendingChanges.changes(1));

		inorderM.verify(manager).withdrawStake(1L, counterpartyBalance + counterparty.getStakedToMe(), false);
		inorderM.verify(manager, never()).awardStake(2L, 2100, false);
	}

	public EntityChangeSet<AccountID, MerkleAccount, AccountProperty> buildPendingNodeStakeChanges() {
		var changes = randomStakedNodeChanges(100L);
		var pendingChanges = new EntityChangeSet<AccountID, MerkleAccount, AccountProperty>();
		pendingChanges.include(counterpartyId, counterparty, changes);
		return pendingChanges;
	}

	public EntityChangeSet<AccountID, MerkleAccount, AccountProperty> buildPendingAccountStakeChanges() {
		var changes = randomStakeAccountChanges(100L);
		var pendingChanges = new EntityChangeSet<AccountID, MerkleAccount, AccountProperty>();
		pendingChanges.include(counterpartyId, counterparty, changes);
		return pendingChanges;
	}

	public MerkleMap<EntityNum, MerkleStakingInfo> buildsStakingInfoMap() {
		given(addressBook.getSize()).willReturn(2);
		given(addressBook.getAddress(0)).willReturn(address1);
		given(address1.getMemo()).willReturn("0.0.3");
		given(addressBook.getAddress(1)).willReturn(address2);
		given(address2.getMemo()).willReturn("0.0.4");

		final var info = buildStakingInfoMap(addressBook);
		info.forEach((a, b) -> {
			b.setStakeToReward(300L);
			b.setStake(1000L);
			b.setStakeToNotReward(400L);
		});
		return info;
	}

	private Map<AccountProperty, Object> randomStakedNodeChanges(final long newBalance) {
		return Map.of(
				AccountProperty.BALANCE, newBalance,
				AccountProperty.STAKED_ID, -2L,
				AccountProperty.DECLINE_REWARD, false,
				AccountProperty.STAKED_TO_ME, 2000L);
	}

	private Map<AccountProperty, Object> randomStakeAccountChanges(final long newBalance) {
		return Map.of(
				AccountProperty.BALANCE, newBalance,
				AccountProperty.STAKED_ID, 2L,
				AccountProperty.DECLINE_REWARD, false,
				AccountProperty.STAKED_TO_ME, 2000L);
	}

	private static final long amount = 1L;
	private static final long partyBalance = 111L;
	private static final long counterpartyBalance = 555L;
	private static final AccountID partyId = AccountID.newBuilder().setAccountNum(123).build();
	private static final AccountID counterpartyId = AccountID.newBuilder().setAccountNum(321).build();
	private static final AccountID stakingFundId = AccountID.newBuilder().setAccountNum(800).build();
	private static final MerkleAccount party = MerkleAccountFactory.newAccount()
			.number(EntityNum.fromAccountId(partyId))
			.balance(partyBalance)
			.get();
	private static final MerkleAccount counterparty = MerkleAccountFactory.newAccount()
			.stakedId(-1)
			.number(EntityNum.fromAccountId(counterpartyId))
			.balance(counterpartyBalance)
			.get();
	private static final MerkleAccount stakingFund = MerkleAccountFactory.newAccount()
			.number(EntityNum.fromAccountId(stakingFundId))
			.balance(amount)
			.get();
}
