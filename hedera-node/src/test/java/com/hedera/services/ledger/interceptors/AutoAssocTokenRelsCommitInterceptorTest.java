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
import com.hedera.services.ledger.EntityChangeSet;
import com.hedera.services.ledger.properties.PropertyChanges;
import com.hedera.services.ledger.properties.TokenRelProperty;
import com.hedera.services.state.merkle.MerkleTokenRelStatus;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenID;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AutoAssocTokenRelsCommitInterceptorTest {
	@Mock
	protected SideEffectsTracker sideEffectsTracker;

	private AutoAssocTokenRelsCommitInterceptor subject;

	@BeforeEach
	void setUp() {
		subject = AutoAssocTokenRelsCommitInterceptor.forKnownAutoAssociatingOp(sideEffectsTracker);
	}

	@Test
	void recordsOnlyNewAssociations() {
		final var unchanged = new PropertyChanges<>(TokenRelProperty.class);
		final var changes = new EntityChangeSet<Pair<AccountID, TokenID>, MerkleTokenRelStatus, TokenRelProperty>();
		changes.include(Pair.of(aAccountId, alreadyAssocTokenId), extantRel, unchanged);
		changes.include(Pair.of(aAccountId, newAssocTokenId), null, unchanged);

		subject.preview(changes);

		verify(sideEffectsTracker).trackAutoAssociation(newAssocTokenId, aAccountId);
	}

	final AccountID aAccountId = AccountID.newBuilder().setAccountNum(1234).build();
	final TokenID alreadyAssocTokenId = TokenID.newBuilder().setTokenNum(1235).build();
	final TokenID newAssocTokenId = TokenID.newBuilder().setTokenNum(1236).build();
	final MerkleTokenRelStatus extantRel = new MerkleTokenRelStatus();
}
