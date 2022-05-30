package com.hedera.services.ledger.accounts;

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

import com.google.protobuf.ByteString;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.submerkle.EntityId;
import com.hederahashgraph.api.proto.java.ContractCreateTransactionBody;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class HederaAccountCustomizerTest {
	HederaAccountCustomizer subject = new HederaAccountCustomizer();

	@Test
	void canCustomizeAlias() {
		final var target = new MerkleAccount();
		final var alias = ByteString.copyFromUtf8("FAKE");
		subject.alias(alias).customizing(target);
		assertEquals(alias, target.getAlias());
	}

	@Test
	void customizesSyntheticContractCreation() {
		final var memo = "Inherited";
		final var proxy = new EntityId(0, 0, 4);
		final var autoRenew = 7776001L;
		final var expiry = 1_234_567L;
		final var autoRenewAccount = new EntityId(0, 0, 5);

		final var customizer = new HederaAccountCustomizer()
				.memo(memo)
				.proxy(proxy)
				.autoRenewPeriod(autoRenew)
				.expiry(expiry)
				.isSmartContract(true)
				.autoRenewAccount(autoRenewAccount)
				.maxAutomaticAssociations(10);

		final var op = ContractCreateTransactionBody.newBuilder();
		customizer.customizeSynthetic(op);

		assertEquals(memo, op.getMemo());
		assertEquals(proxy.toGrpcAccountId(), op.getProxyAccountID());
		assertEquals(autoRenew, op.getAutoRenewPeriod().getSeconds());
		assertEquals(autoRenewAccount.toGrpcAccountId(), op.getAutoRenewAccountId());
		assertEquals(10, op.getMaxAutomaticTokenAssociations());
	}

	@Test
	void noopCustomizationIsSafe() {
		final var customizer = new HederaAccountCustomizer().isSmartContract(true);
		final var op = ContractCreateTransactionBody.newBuilder();

		assertDoesNotThrow(() -> customizer.customizeSynthetic(op));
	}
}
