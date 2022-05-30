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

import com.hedera.services.ledger.EntityChangeSet;
import com.hedera.services.ledger.properties.NftProperty;
import com.hedera.services.ledger.properties.PropertyChanges;
import com.hedera.services.state.merkle.MerkleUniqueToken;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.store.models.NftId;
import com.hedera.services.utils.EntityNum;
import com.hedera.services.utils.EntityNumPair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class LinkAwareUniqueTokensCommitInterceptorTest {
	@Mock
	private UniqueTokensLinkManager uniqueTokensLinkManager;

	private LinkAwareUniqueTokensCommitInterceptor subject;

	@BeforeEach
	void setUp() {
		subject = new LinkAwareUniqueTokensCommitInterceptor(uniqueTokensLinkManager);
	}

	@Test
	void noChangesAreNoOp() {
		final var changes = new EntityChangeSet<NftId, MerkleUniqueToken, NftProperty>();

		subject.preview(changes);

		verifyNoInteractions(uniqueTokensLinkManager);
	}

	@Test
	void zombieCommitIsNoOp() {
		var changes = (EntityChangeSet<NftId, MerkleUniqueToken, NftProperty>) mock(EntityChangeSet.class);
		given(changes.size()).willReturn(1);
		given(changes.entity(0)).willReturn(null);
		given(changes.changes(0)).willReturn(null);

		subject.preview(changes);

		verifyNoInteractions(uniqueTokensLinkManager);
	}

	@Test
	@SuppressWarnings("unchecked")
	void resultsInNoOpForNoOwnershipChanges() {
		var changes = (EntityChangeSet<NftId, MerkleUniqueToken, NftProperty>) mock(EntityChangeSet.class);
		var nft = mock(MerkleUniqueToken.class);
		var change = (PropertyChanges<NftProperty>) mock(PropertyChanges.class);

		given(changes.size()).willReturn(1);
		given(changes.entity(0)).willReturn(nft);
		given(changes.changes(0)).willReturn(change);
		given(change.includes(NftProperty.OWNER)).willReturn(false);

		subject.preview(changes);

		verifyNoInteractions(uniqueTokensLinkManager);
	}

	@Test
	@SuppressWarnings("unchecked")
	void nonTreasuryExitTriggersUpdateLinksAsExpected() {
		final var changes = (EntityChangeSet<NftId, MerkleUniqueToken, NftProperty>) mock(EntityChangeSet.class);
		final var nft = mock(MerkleUniqueToken.class);
		final var change = (PropertyChanges<NftProperty>) mock(PropertyChanges.class);
		final long ownerNum = 1111L;
		final long newOwnerNum = 1234L;
		final long tokenNum = 2222L;
		final long serialNum = 2L;
		EntityNum owner = EntityNum.fromLong(ownerNum);
		EntityNum newOwner = EntityNum.fromLong(newOwnerNum);
		EntityNumPair nftKey = EntityNumPair.fromLongs(tokenNum, serialNum);

		given(changes.size()).willReturn(1);
		given(changes.entity(0)).willReturn(nft);
		given(changes.changes(0)).willReturn(change);
		given(change.includes(NftProperty.OWNER)).willReturn(true);
		given(change.get(NftProperty.OWNER)).willReturn(newOwner.toEntityId());
		given(nft.getOwner()).willReturn(owner.toEntityId());
		given(nft.getKey()).willReturn(nftKey);

		subject.preview(changes);

		verify(uniqueTokensLinkManager).updateLinks(owner, newOwner, nftKey);
	}

	@Test
	@SuppressWarnings("unchecked")
	void treasuryBurnDoesNotUpdateLinks() {
		final var changes = (EntityChangeSet<NftId, MerkleUniqueToken, NftProperty>) mock(EntityChangeSet.class);
		final var nft = mock(MerkleUniqueToken.class);
		EntityNum owner = EntityNum.MISSING_NUM;

		given(changes.size()).willReturn(1);
		given(changes.entity(0)).willReturn(nft);
		given(changes.changes(0)).willReturn(null);
		given(nft.getOwner()).willReturn(owner.toEntityId());

		subject.preview(changes);

		verifyNoInteractions(uniqueTokensLinkManager);
	}

	@Test
	@SuppressWarnings("unchecked")
	void nonOwnerUpdateDoesNotUpdateLinks() {
		final var changes = (EntityChangeSet<NftId, MerkleUniqueToken, NftProperty>) mock(EntityChangeSet.class);
		final var nft = mock(MerkleUniqueToken.class);
		EntityNum owner = EntityNum.MISSING_NUM;
		final var scopedChanges = new PropertyChanges<>(NftProperty.class);
		scopedChanges.set(NftProperty.SPENDER, new EntityId(0, 0, 123));

		given(changes.size()).willReturn(1);
		given(changes.entity(0)).willReturn(nft);
		given(changes.changes(0)).willReturn(scopedChanges);
		given(nft.getOwner()).willReturn(owner.toEntityId());

		subject.preview(changes);

		verifyNoInteractions(uniqueTokensLinkManager);
	}

	@Test
	@SuppressWarnings("unchecked")
	void triggersUpdateLinksOnWipeAsExpected() {
		final var changes = (EntityChangeSet<NftId, MerkleUniqueToken, NftProperty>) mock(EntityChangeSet.class);
		final var nft = mock(MerkleUniqueToken.class);
		final long ownerNum = 1111L;
		final long tokenNum = 2222L;
		final long serialNum = 2L;
		EntityNum owner = EntityNum.fromLong(ownerNum);
		EntityNumPair nftKey = EntityNumPair.fromLongs(tokenNum, serialNum);


		given(changes.size()).willReturn(1);
		given(changes.entity(0)).willReturn(nft);
		given(changes.changes(0)).willReturn(null);
		given(nft.getOwner()).willReturn(owner.toEntityId());
		given(nft.getKey()).willReturn(nftKey);

		subject.preview(changes);

		verify(uniqueTokensLinkManager).updateLinks(owner, null, nftKey);
	}

	@Test
	@SuppressWarnings("unchecked")
	void triggersUpdateLinksOnMultiStageMintAndTransferAsExpected() {
		final var changes = (EntityChangeSet<NftId, MerkleUniqueToken, NftProperty>) mock(EntityChangeSet.class);
		final long ownerNum = 1111L;
		final long tokenNum = 2222L;
		final long serialNum = 2L;
		final var scopedChanges = new PropertyChanges<>(NftProperty.class);
		EntityNum owner = EntityNum.fromLong(ownerNum);
		EntityNumPair nftKey = EntityNumPair.fromLongs(tokenNum, serialNum);
		final var mintedNft = new MerkleUniqueToken();

		given(changes.size()).willReturn(1);
		given(changes.id(0)).willReturn(nftKey.asNftNumPair().nftId());
		given(changes.entity(0)).willReturn(null);
		given(changes.changes(0)).willReturn(scopedChanges);
		scopedChanges.set(NftProperty.OWNER, owner.toEntityId());
		given(uniqueTokensLinkManager.updateLinks(null, owner, nftKey)).willReturn(mintedNft);

		subject.preview(changes);

		verify(uniqueTokensLinkManager).updateLinks(null, owner, nftKey);
		verify(changes).cacheEntity(0, mintedNft);
	}

	@Test
	@SuppressWarnings("unchecked")
	void doesntTriggerUpdateLinkOnNormalTreasuryMint() {
		final var changes = (EntityChangeSet<NftId, MerkleUniqueToken, NftProperty>) mock(EntityChangeSet.class);
		final var scopedChanges = new PropertyChanges<>(NftProperty.class);

		given(changes.size()).willReturn(1);
		given(changes.entity(0)).willReturn(null);
		given(changes.changes(0)).willReturn(scopedChanges);
		scopedChanges.set(NftProperty.OWNER, EntityId.MISSING_ENTITY_ID);

		subject.preview(changes);

		verifyNoInteractions(uniqueTokensLinkManager);
	}
}
