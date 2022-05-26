package com.hedera.services.ledger.accounts.staking;

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
import com.hedera.services.ledger.EntityChangeSet;
import com.hedera.services.ledger.properties.AccountProperty;
import com.hedera.services.state.merkle.MerkleAccount;
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

import java.util.HashMap;
import java.util.Map;

import static com.hedera.services.ledger.accounts.staking.StakeChangeManager.finalBalanceGiven;
import static com.hedera.services.state.migration.ReleaseTwentySevenMigration.buildStakingInfoMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class StakeChangeManagerTest {
	@Mock
	private AddressBook addressBook;
	@Mock
	private Address address1 = mock(Address.class);
	@Mock
	private Address address2 = mock(Address.class);
	@Mock
	private MerkleAccount account;
	@Mock
	private StakeInfoManager stakeInfoManager;
	@Mock
	private MerkleMap<EntityNum, MerkleAccount> accounts;

	private StakeChangeManager subject;
	private MerkleMap<EntityNum, MerkleStakingInfo> stakingInfo;

	private final EntityNum node0Id = EntityNum.fromLong(0L);

	@BeforeEach
	void setUp() {
		stakingInfo = buildsStakingInfoMap();
		subject = new StakeChangeManager(stakeInfoManager, () -> accounts);
	}

	@Test
	void validatesIfAnyStakedFieldChanges() {
		assertTrue(subject.hasStakeFieldChanges(randomStakeFieldChanges(100L)));
		assertFalse(subject.hasStakeFieldChanges(randomNotStakeFieldChanges()));
	}

	@Test
	void updatesBalance() {
		var changes = randomStakeFieldChanges(100L);
		var pendingChanges = new EntityChangeSet<AccountID, MerkleAccount, AccountProperty>();
		pendingChanges.include(counterpartyId, counterparty, changes);
		assertEquals(100L, pendingChanges.changes(0).get(AccountProperty.BALANCE));

		subject.updateBalance(20L, 0, pendingChanges);
		assertEquals(120L, pendingChanges.changes(0).get(AccountProperty.BALANCE));

		changes = randomNotStakeFieldChanges();
		pendingChanges.clear();
		pendingChanges.include(counterpartyId, counterparty, changes);
		assertEquals(null, pendingChanges.changes(0).get(AccountProperty.BALANCE));
		subject.updateBalance(20L, 0, pendingChanges);
		assertEquals(20L, pendingChanges.changes(0).get(AccountProperty.BALANCE));
	}

	@Test
	void updatesStakedToMe() {
		var changes = randomStakeFieldChanges(100L);
		var pendingChanges = new EntityChangeSet<AccountID, MerkleAccount, AccountProperty>();
		pendingChanges.include(counterpartyId, counterparty, changes);
		assertEquals(2000L, pendingChanges.changes(0).get(AccountProperty.STAKED_TO_ME));

		subject.updateStakedToMe(0, 20L, pendingChanges);
		assertEquals(2020L, pendingChanges.changes(0).get(AccountProperty.STAKED_TO_ME));


		changes = randomNotStakeFieldChanges();
		pendingChanges.clear();
		pendingChanges.include(counterpartyId, counterparty, changes);
		assertEquals(null, pendingChanges.changes(0).get(AccountProperty.STAKED_TO_ME));
		subject.updateStakedToMe(0, 20L, pendingChanges);
		assertEquals(20L, pendingChanges.changes(0).get(AccountProperty.STAKED_TO_ME));
	}

	@Test
	void withdrawsStakeCorrectly() {
		assertEquals(1000L, stakingInfo.get(node0Id).getStake());
		assertEquals(300L, stakingInfo.get(node0Id).getStakeToReward());
		assertEquals(400L, stakingInfo.get(node0Id).getStakeToNotReward());
		given(stakeInfoManager.mutableStakeInfoFor(0L)).willReturn(stakingInfo.get(node0Id));
		subject.withdrawStake(0L, 100L, false);

		assertEquals(1000L, stakingInfo.get(node0Id).getStake());
		assertEquals(200L, stakingInfo.get(node0Id).getStakeToReward());
		assertEquals(400L, stakingInfo.get(node0Id).getStakeToNotReward());

		subject.withdrawStake(0L, 100L, true);

		assertEquals(1000L, stakingInfo.get(node0Id).getStake());
		assertEquals(200L, stakingInfo.get(node0Id).getStakeToReward());
		assertEquals(300L, stakingInfo.get(node0Id).getStakeToNotReward());
	}

	@Test
	void awardsStakeCorrectly() {
		assertEquals(1000L, stakingInfo.get(node0Id).getStake());
		assertEquals(300L, stakingInfo.get(node0Id).getStakeToReward());
		assertEquals(400L, stakingInfo.get(node0Id).getStakeToNotReward());
		given(stakeInfoManager.mutableStakeInfoFor(0L)).willReturn(stakingInfo.get(node0Id));
		subject.awardStake(0L, 100L, false);

		assertEquals(1000L, stakingInfo.get(node0Id).getStake());
		assertEquals(400L, stakingInfo.get(node0Id).getStakeToReward());
		assertEquals(400L, stakingInfo.get(node0Id).getStakeToNotReward());

		subject.awardStake(0L, 100L, true);

		assertEquals(1000L, stakingInfo.get(node0Id).getStake());
		assertEquals(400L, stakingInfo.get(node0Id).getStakeToReward());
		assertEquals(500L, stakingInfo.get(node0Id).getStakeToNotReward());
	}

	@Test
	void getsFieldsCorrectlyFromChanges() {
		final var changes = randomStakeFieldChanges(100L);

		assertEquals(0L, subject.getAccountStakeeNum(changes));
		assertEquals(-2L, subject.getNodeStakeeNum(changes));
		assertEquals(100L, finalBalanceGiven(account, changes));
		assertEquals(true, subject.finalDeclineRewardGiven(account, changes));
		assertEquals(2000L, subject.finalStakedToMeGiven(account, changes));
	}

	@Test
	void getsFieldsCorrectlyIfNotFromChanges() {
		final var changes = randomNotStakeFieldChanges();

		given(account.getBalance()).willReturn(1000L);
		given(account.isDeclinedReward()).willReturn(true);
		given(account.getStakedToMe()).willReturn(200L);

		assertEquals(0L, subject.getAccountStakeeNum(changes));
		assertEquals(0L, subject.getNodeStakeeNum(changes));
		assertEquals(1000L, finalBalanceGiven(account, changes));
		assertEquals(true, subject.finalDeclineRewardGiven(account, changes));
		assertEquals(200L, subject.finalStakedToMeGiven(account, changes));
	}

	@Test
	void findsOrAddsAccountAsExpected() {
		final var pendingChanges = buildPendingNodeStakeChanges();
		assertEquals(1, pendingChanges.size());

		var num = subject.findOrAdd(partyId.getAccountNum(), pendingChanges);
		assertEquals(1, num);
		num = subject.findOrAdd(counterpartyId.getAccountNum(), pendingChanges);
		assertEquals(0, num);

		assertEquals(2, pendingChanges.size());
	}

	@Test
	void setsStakePeriodStart() {
		final long todayNum = 123456789L;

		final var accountsMap = new MerkleMap<EntityNum, MerkleAccount>();
		accountsMap.put(EntityNum.fromAccountId(counterpartyId), counterparty);
		accountsMap.put(EntityNum.fromAccountId(partyId), party);

		subject = new StakeChangeManager(stakeInfoManager, () -> accountsMap);
		subject.setStakePeriodStart(todayNum);

		assertEquals(todayNum, counterparty.getStakePeriodStart());
		assertEquals(-1, party.getStakePeriodStart());
	}

	@Test
	void checksIfBalanceIncraesed() {
		Map<AccountProperty, Object> stakingFundChanges = Map.of(AccountProperty.BALANCE, 100L);
		assertTrue(subject.isIncreased(stakingFundChanges, stakingFund));

		stakingFundChanges = Map.of(AccountProperty.BALANCE, -100L);
		assertFalse(subject.isIncreased(stakingFundChanges, stakingFund));

		stakingFundChanges = Map.of(AccountProperty.BALANCE, 100L);
		assertTrue(subject.isIncreased(stakingFundChanges, null));

		stakingFundChanges = Map.of(AccountProperty.ALIAS, ByteString.copyFromUtf8("Testing"));
		assertFalse(subject.isIncreased(stakingFundChanges, stakingFund));
	}


	@Test
	void returnsDefaultsWhenAccountIsNull() {
		final var changes = randomNotStakeFieldChanges();

		assertEquals(0, finalBalanceGiven(null, changes));
		assertEquals(false, subject.finalDeclineRewardGiven(null, changes));
		assertEquals(0, subject.finalStakedToMeGiven(null, changes));
	}

	public EntityChangeSet<AccountID, MerkleAccount, AccountProperty> buildPendingNodeStakeChanges() {
		var changes = randomStakeFieldChanges(100L);
		var pendingChanges = new EntityChangeSet<AccountID, MerkleAccount, AccountProperty>();
		pendingChanges.include(counterpartyId, counterparty, changes);
		return pendingChanges;
	}

	private Map<AccountProperty, Object> randomStakeFieldChanges(final long newBalance) {
		final var map = new HashMap<AccountProperty, Object>();
		map.put(AccountProperty.BALANCE, newBalance);
		map.put(AccountProperty.STAKED_ID, -2L);
		map.put(AccountProperty.DECLINE_REWARD, true);
		map.put(AccountProperty.STAKED_TO_ME, 2000L);
		return map;
	}

	private Map<AccountProperty, Object> randomNotStakeFieldChanges() {
		final var map = new HashMap<AccountProperty, Object>();
		map.put(AccountProperty.ALIAS, ByteString.copyFromUtf8("testing"));
		return map;
	}


	public MerkleMap<EntityNum, MerkleStakingInfo> buildsStakingInfoMap() {
		given(addressBook.getSize()).willReturn(2);
		given(addressBook.getAddress(0)).willReturn(address1);
		given(address1.getId()).willReturn(0L);
		given(addressBook.getAddress(1)).willReturn(address2);
		given(address2.getId()).willReturn(1L);

		final var info = buildStakingInfoMap(addressBook);
		info.forEach((a, b) -> {
			b.setStakeToReward(300L);
			b.setStake(1000L);
			b.setStakeToNotReward(400L);
		});
		return info;
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
