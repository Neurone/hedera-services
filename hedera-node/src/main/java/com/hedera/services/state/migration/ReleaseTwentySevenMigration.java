package com.hedera.services.state.migration;

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

import com.hedera.services.state.merkle.MerkleStakingInfo;
import com.hedera.services.utils.EntityNum;
import com.swirlds.common.system.AddressBook;
import com.swirlds.merkle.map.MerkleMap;

public final class ReleaseTwentySevenMigration {
	private ReleaseTwentySevenMigration() {
		throw new UnsupportedOperationException("Utility class");
	}

	public static MerkleMap<EntityNum, MerkleStakingInfo> buildStakingInfoMap(AddressBook addressBook) {
		MerkleMap<EntityNum, MerkleStakingInfo> stakingInfo = new MerkleMap<>();

		final var numberOfNodes = addressBook.getSize();
		for (int i = 0; i < numberOfNodes; i++) {
			final var nodeNum = EntityNum.fromLong(addressBook.getAddress(i).getId());
			final var info = new MerkleStakingInfo();
			info.setMaxStake(1_000_000_000L);
			stakingInfo.put(nodeNum, info);
		}

		return stakingInfo;
	}
}
