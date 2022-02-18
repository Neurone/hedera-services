package com.hedera.services.config;

/*-
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2021 Hedera Hashgraph, LLC
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

import com.hedera.services.context.properties.PropertySource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class NetworkInfoTest {
	@Mock
	private PropertySource properties;

	private NetworkInfo subject;

	@BeforeEach
	void setUp() {
		subject = new NetworkInfo(properties);
	}

	@Test
	void getsLedgerIdFromProperties() {
		final var bytes = new byte[]{4};
		final var ledgerId = "0x04";

		given(properties.getStringProperty("ledger.id")).willReturn(ledgerId);
		final var actual = subject.ledgerId();

		assertArrayEquals(bytes, actual.toByteArray());
	}

	@Test
	void throwsAsExpected() {
		final var invalidLedgerId1 = "0v04";
		final var invalidLedgerId2 = "0xv04";
		final var invalidLedgerId3 = "0xZZ04";

		given(properties.getStringProperty("ledger.id")).willReturn(invalidLedgerId1);
		assertThrows(IllegalArgumentException.class, () -> subject.ledgerId());

		given(properties.getStringProperty("ledger.id")).willReturn(invalidLedgerId2);
		assertThrows(IllegalArgumentException.class, () -> subject.ledgerId());

		given(properties.getStringProperty("ledger.id")).willReturn(invalidLedgerId3);
		assertThrows(IllegalArgumentException.class, () -> subject.ledgerId());
	}
}
