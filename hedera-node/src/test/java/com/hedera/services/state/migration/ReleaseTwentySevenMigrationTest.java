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

import com.hedera.services.utils.EntityNum;
import com.swirlds.common.system.Address;
import com.swirlds.common.system.AddressBook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.hedera.services.state.migration.ReleaseTwentySevenMigration.buildStakingInfoMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class ReleaseTwentySevenMigrationTest {
	@Mock
	AddressBook addressBook;

	@Test
	void buildsStakingInfoMapAsExpected() {
		final var address1 = mock(Address.class);
		final var address2 = mock(Address.class);
		final var address3 = mock(Address.class);
		final var address4 = mock(Address.class);
		final var address5 = mock(Address.class);

		given(addressBook.getSize()).willReturn(5);
		given(addressBook.getAddress(0)).willReturn(address1);
		given(address1.getId()).willReturn(0L);
		given(addressBook.getAddress(1)).willReturn(address2);
		given(address2.getId()).willReturn(1L);
		given(addressBook.getAddress(2)).willReturn(address3);
		given(address3.getId()).willReturn(2L);
		given(addressBook.getAddress(3)).willReturn(address4);
		given(address4.getId()).willReturn(3L);
		given(addressBook.getAddress(4)).willReturn(address5);
		given(address5.getId()).willReturn(4L);

		var stakingInfoMap = buildStakingInfoMap(addressBook);

		assertEquals(5, stakingInfoMap.size());
		assertTrue(stakingInfoMap.containsKey(EntityNum.fromInt(0)));
		assertTrue(stakingInfoMap.containsKey(EntityNum.fromInt(1)));
		assertTrue(stakingInfoMap.containsKey(EntityNum.fromInt(2)));
		assertTrue(stakingInfoMap.containsKey(EntityNum.fromInt(3)));
		assertTrue(stakingInfoMap.containsKey(EntityNum.fromInt(4)));
	}
}
