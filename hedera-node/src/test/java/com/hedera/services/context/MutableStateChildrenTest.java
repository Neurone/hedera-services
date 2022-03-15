package com.hedera.services.context;

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
import com.hedera.services.ServicesState;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleNetworkContext;
import com.hedera.services.state.merkle.MerkleSchedule;
import com.hedera.services.state.merkle.MerkleSpecialFiles;
import com.hedera.services.state.merkle.MerkleToken;
import com.hedera.services.state.merkle.MerkleTokenRelStatus;
import com.hedera.services.state.merkle.MerkleTopic;
import com.hedera.services.state.merkle.MerkleUniqueToken;
import com.hedera.services.state.virtual.ContractKey;
import com.hedera.services.state.virtual.ContractValue;
import com.hedera.services.state.virtual.VirtualBlobKey;
import com.hedera.services.state.virtual.VirtualBlobValue;
import com.hedera.services.stream.RecordsRunningHashLeaf;
import com.hedera.services.utils.EntityNum;
import com.hedera.services.utils.EntityNumPair;
import com.swirlds.common.AddressBook;
import com.swirlds.fchashmap.FCHashMap;
import com.swirlds.merkle.map.MerkleMap;
import com.swirlds.virtualmap.VirtualMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class MutableStateChildrenTest {
	@Mock
	private ServicesState state;
	@Mock
	private MerkleMap<EntityNum, MerkleAccount> accounts;
	@Mock
	private VirtualMap<VirtualBlobKey, VirtualBlobValue> storage;
	@Mock
	private VirtualMap<ContractKey, ContractValue> contractStorage;
	@Mock
	private MerkleMap<EntityNum, MerkleTopic> topics;
	@Mock
	private MerkleMap<EntityNum, MerkleToken> tokens;
	@Mock
	private MerkleMap<EntityNumPair, MerkleTokenRelStatus> tokenAssociations;
	@Mock
	private MerkleMap<EntityNum, MerkleSchedule> scheduleTxs;
	@Mock
	private MerkleNetworkContext networkCtx;
	@Mock
	private AddressBook addressBook;
	@Mock
	private MerkleSpecialFiles specialFiles;
	@Mock
	private MerkleMap<EntityNumPair, MerkleUniqueToken> uniqueTokens;
	@Mock
	private RecordsRunningHashLeaf runningHashLeaf;
	@Mock
	private FCHashMap<ByteString, EntityNum> aliases;

	private MutableStateChildren subject = new MutableStateChildren();

	@Test
	void refusesToUpdateFromUninitializedState() {
		assertThrows(IllegalArgumentException.class, () -> subject.updateFromSigned(state, signedAt));
	}

	@Test
	void childrenGetUpdatedAsExpected() {
		givenStateWithMockChildren();
		given(state.isInitialized()).willReturn(true);

		subject.updateFromSigned(state, signedAt);

		assertChildrenAreExpectedMocks();
		assertEquals(signedAt, subject.signedAt());
	}

	private void givenStateWithMockChildren() {
		given(state.accounts()).willReturn(accounts);
		given(state.storage()).willReturn(storage);
		given(state.contractStorage()).willReturn(contractStorage);
		given(state.topics()).willReturn(topics);
		given(state.tokens()).willReturn(tokens);
		given(state.tokenAssociations()).willReturn(tokenAssociations);
		given(state.scheduleTxs()).willReturn(scheduleTxs);
		given(state.networkCtx()).willReturn(networkCtx);
		given(state.addressBook()).willReturn(addressBook);
		given(state.specialFiles()).willReturn(specialFiles);
		given(state.uniqueTokens()).willReturn(uniqueTokens);
		given(state.runningHashLeaf()).willReturn(runningHashLeaf);
		given(state.aliases()).willReturn(aliases);
	}

	private void assertChildrenAreExpectedMocks() {
		assertSame(accounts, subject.accounts());
		assertSame(storage, subject.storage());
		assertSame(contractStorage, subject.contractStorage());
		assertSame(topics, subject.topics());
		assertSame(tokens, subject.tokens());
		assertSame(tokenAssociations, subject.tokenAssociations());
		assertSame(scheduleTxs, subject.schedules());
		assertSame(networkCtx, subject.networkCtx());
		assertSame(addressBook, subject.addressBook());
		assertSame(specialFiles, subject.specialFiles());
		assertSame(uniqueTokens, subject.uniqueTokens());
		assertSame(runningHashLeaf, subject.runningHashLeaf());
		assertSame(aliases, subject.aliases());
	}

	private static final Instant signedAt = Instant.ofEpochSecond(1_234_567, 890);
}
