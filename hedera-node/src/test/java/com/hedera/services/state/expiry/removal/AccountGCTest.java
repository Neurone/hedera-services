package com.hedera.services.state.expiry.removal;

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
import com.hedera.services.ledger.SigImpactHistorian;
import com.hedera.services.ledger.accounts.AliasManager;
import com.hedera.services.ledger.backing.BackingStore;
import com.hedera.services.state.expiry.TokenRelsListRemoval;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleTokenRelStatus;
import com.hedera.services.utils.EntityNum;
import com.hedera.services.utils.EntityNumPair;
import com.hedera.test.factories.accounts.MerkleAccountFactory;
import com.hederahashgraph.api.proto.java.AccountID;
import com.swirlds.merkle.map.MerkleMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AccountGCTest {
	@Mock
	private AliasManager aliasManager;
	@Mock
	private SigImpactHistorian sigImpactHistorian;
	@Mock
	private TreasuryReturnHelper treasuryReturnHelper;
	@Mock
	private BackingStore<AccountID, MerkleAccount> backingAccounts;
	@Mock
	private MerkleMap<EntityNumPair, MerkleTokenRelStatus> tokenRels;
	@Mock
	private AccountGC.RemovalFacilitation removalFacilitation;
	@Mock
	private TokenRelsListRemoval listRemoval;

	private AccountGC subject;

	@BeforeEach
	void setUp() {
		subject = new AccountGC(aliasManager, sigImpactHistorian, treasuryReturnHelper, backingAccounts, () -> tokenRels);
		subject.setRemovalFacilitation(removalFacilitation);
	}

	@Test
	void removalWithNoTokensWorks() {
		given(aliasManager.forgetAlias(accountNoTokens.getAlias())).willReturn(true);
		final var expectedReturns = new TreasuryReturns(Collections.emptyList(), Collections.emptyList(), true);

		final var actualReturns = subject.expireBestEffort(num, accountNoTokens);

		assertEquals(expectedReturns, actualReturns);
		assertRemovalStepsTaken(num, accountNoTokens);
	}

	@Test
	void removalWithTokensWorks() {
		final var expectedReturns = new TreasuryReturns(
				List.of(aToken.toEntityId(), bToken.toEntityId()),
				Collections.emptyList(), true);
		final var rootKey = accountWithTokens.getLatestAssociation();
		final var nextKey = EntityNumPair.fromLongs(num.longValue(), bNum);
		final var unusedKey = EntityNumPair.fromLongs(num.longValue(), 888L);

		given(removalFacilitation.removeNext(
				eq(rootKey),
				eq(rootKey),
				any(TokenRelsListRemoval.class))).willReturn(nextKey);
		given(removalFacilitation.removeNext(
				eq(nextKey),
				eq(nextKey),
				any(TokenRelsListRemoval.class))).willReturn(unusedKey);
		given(tokenRels.get(rootKey)).willReturn(new MerkleTokenRelStatus(1, false, false, false));
		given(tokenRels.get(nextKey)).willReturn(new MerkleTokenRelStatus(0, true, true, true));

		final var actualReturns = subject.expireBestEffort(num, accountWithTokens);

		assertEquals(expectedReturns, actualReturns);
		verify(treasuryReturnHelper, never()).updateReturns(any(), eq(bToken), eq(0), any());
		assertRemovalStepsTaken(num, accountWithTokens);
	}

	private void assertRemovalStepsTaken(final EntityNum num, final MerkleAccount account) {
		verify(aliasManager).forgetAlias(account.getAlias());
		verify(backingAccounts).remove(num.toGrpcAccountId());
		verify(sigImpactHistorian).markEntityChanged(num.longValue());
	}

	private final long expiredNum = 2L;
	private final long aNum = 666L;
	private final long bNum = 777L;
	private final EntityNum num = EntityNum.fromLong(expiredNum);
	private final EntityNum aToken = EntityNum.fromLong(aNum);
	private final EntityNum bToken = EntityNum.fromLong(bNum);
	private final ByteString anAlias = ByteString.copyFromUtf8("bbbb");
	private final MerkleAccount accountNoTokens = MerkleAccountFactory.newAccount()
			.balance(0)
			.lastAssociatedToken(0)
			.associatedTokensCount(0)
			.alias(anAlias)
			.get();
	private final MerkleAccount accountWithTokens = MerkleAccountFactory.newAccount()
			.balance(0)
			.lastAssociatedToken(aNum)
			.associatedTokensCount(2)
			.alias(anAlias)
			.get();
}
