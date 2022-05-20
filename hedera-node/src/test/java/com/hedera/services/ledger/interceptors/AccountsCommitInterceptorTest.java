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

import com.google.protobuf.ByteString;
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
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Map;

import static com.hedera.services.ledger.accounts.staking.RewardCalculator.zoneUTC;
import static com.hedera.services.state.migration.ReleaseTwentySevenMigration.buildStakingInfoMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willCallRealMethod;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;


@ExtendWith(MockitoExtension.class)
class AccountsCommitInterceptorTest {
	@Mock
	private SideEffectsTracker sideEffectsTracker;
	@Mock
	private MerkleNetworkContext networkCtx;

	private MerkleMap<EntityNum, MerkleStakingInfo> stakingInfo;
	@Mock
	private GlobalDynamicProperties dynamicProperties;
	@Mock
	private AddressBook addressBook;
	@Mock
	private Address address1 = mock(Address.class);
	@Mock
	private Address address2 = mock(Address.class);
	@Mock
	private RewardCalculator rewardCalculator;
	@Mock
	private MerkleMap<EntityNum, MerkleAccount> accounts;

	private AccountsCommitInterceptor subject;

	@BeforeEach
	public void setUp() {
		stakingInfo = buildsStakingInfoMap();
		subject = new AccountsCommitInterceptor(sideEffectsTracker);
	}

	@Test
	void doesntCompleteRemovals() {
		setupLiveInterceptor();

		assertFalse(subject.completesPendingRemovals());
	}

	@Test
	void rejectsNonZeroSumChange() {
		setupLiveInterceptor();

		final var changes = new EntityChangeSet<AccountID, MerkleAccount, AccountProperty>();
		changes.include(partyId, party, randomAndBalanceChanges(partyBalance + amount));
		changes.include(counterpartyId, counterparty, randomAndBalanceChanges(counterpartyBalance - amount - 1));

		assertThrows(IllegalStateException.class, () -> subject.preview(changes));
	}

	@Test
	void tracksAsExpected() {
		setupMockInterceptor();

		final var changes = new EntityChangeSet<AccountID, MerkleAccount, AccountProperty>();
		changes.include(partyId, party, randomAndBalanceChanges(partyBalance + amount));
		changes.include(counterpartyId, counterparty, randomAndBalanceChanges(counterpartyBalance - amount));

		subject.preview(changes);

		verify(sideEffectsTracker).trackHbarChange(partyId.getAccountNum(), +amount);
		verify(sideEffectsTracker).trackHbarChange(counterpartyId.getAccountNum(), -amount);
	}

	@Test
	void noopWithoutBalancesChanges() {
		setupMockInterceptor();

		final var changes = new EntityChangeSet<AccountID, MerkleAccount, AccountProperty>();
		changes.include(partyId, party, Map.of(AccountProperty.ALIAS, ByteString.copyFromUtf8("IGNORE THE VASE")));
		subject.preview(changes);

		verify(sideEffectsTracker).getNetHbarChange();
		verifyNoMoreInteractions(sideEffectsTracker);
	}

	@Test
	void activatesStakingRewardsAndClearsRewardSumHistoryAsExpected() {
		final long stakingFee = 2L;
		final long expectedStakePeriodStart = 12345;
		final var dateMock = mock(LocalDate.class);
		final var mockedStatic = mockStatic(LocalDate.class);
		mockedStatic.when(() -> LocalDate.now(zoneUTC)).thenReturn(dateMock);
		given(dateMock.toEpochDay()).willReturn(expectedStakePeriodStart);
		final var inorder = inOrder(sideEffectsTracker);
		given(dynamicProperties.getStakingStartThreshold()).willReturn(1L);

		final var changes = new EntityChangeSet<AccountID, MerkleAccount, AccountProperty>();
		changes.include(partyId, party, randomAndBalanceChanges(partyBalance + amount));
		changes.include(counterpartyId, counterparty,
				randomAndBalanceChanges(counterpartyBalance - amount - stakingFee));
		changes.include(stakingFundId, stakingFund, randomAndBalanceChanges(stakingFee));
		willCallRealMethod().given(networkCtx).areRewardsActivated();
		willCallRealMethod().given(networkCtx).setStakingRewardsActivated(true);
		willCallRealMethod().given(accounts).forEach(any());
		given(accounts.entrySet()).willReturn(Map.of(
				EntityNum.fromAccountId(counterpartyId), counterparty,
				EntityNum.fromAccountId(partyId), party,
				EntityNum.fromAccountId(stakingFundId), stakingFund).entrySet());

		stakingInfo.forEach((a, b) -> b.setRewardSumHistory(new long[] { 5, 5 }));

		// rewardsSumHistory is not cleared
		assertEquals(5, stakingInfo.get(EntityNum.fromLong(3L)).getRewardSumHistory()[0]);
		assertEquals(5, stakingInfo.get(EntityNum.fromLong(4L)).getRewardSumHistory()[0]);
		assertEquals(-1, counterparty.getStakePeriodStart());
		assertEquals(-1, party.getStakePeriodStart());

		subject.preview(changes);

		inorder.verify(sideEffectsTracker).trackHbarChange(partyId.getAccountNum(), +amount);
		inorder.verify(sideEffectsTracker).trackHbarChange(counterpartyId.getAccountNum(), -amount - stakingFee);
		inorder.verify(sideEffectsTracker).trackHbarChange(stakingFundId.getAccountNum(), 1L);
		verify(networkCtx).setStakingRewardsActivated(true);

		// rewardsSumHistory is cleared
		assertEquals(0, stakingInfo.get(EntityNum.fromLong(3L)).getRewardSumHistory()[0]);
		assertEquals(0, stakingInfo.get(EntityNum.fromLong(4L)).getRewardSumHistory()[0]);
		assertEquals(expectedStakePeriodStart, counterparty.getStakePeriodStart());
		assertEquals(-1, party.getStakePeriodStart());
		mockedStatic.close();
	}


	private MerkleMap<EntityNum, MerkleStakingInfo> buildsStakingInfoMap() {
		given(addressBook.getSize()).willReturn(2);
		given(addressBook.getAddress(0)).willReturn(address1);
		given(address1.getMemo()).willReturn("0.0.3");
		given(addressBook.getAddress(1)).willReturn(address2);
		given(address2.getMemo()).willReturn("0.0.4");

		return buildStakingInfoMap(addressBook);
	}

	private void setupMockInterceptor() {
		subject = new AccountsCommitInterceptor(sideEffectsTracker);
	}

	private void setupLiveInterceptor() {
		subject = new AccountsCommitInterceptor(new SideEffectsTracker());
	}

	private Map<AccountProperty, Object> randomAndBalanceChanges(final long newBalance) {
		return Map.of(
				AccountProperty.BALANCE, newBalance,
				AccountProperty.ALIAS, ByteString.copyFromUtf8("IGNORE THE VASE"));
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
