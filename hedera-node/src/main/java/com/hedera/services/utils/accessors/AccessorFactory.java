package com.hedera.services.utils.accessors;

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

import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.services.ledger.accounts.AliasManager;
import com.hederahashgraph.api.proto.java.Transaction;
import com.swirlds.common.SwirldTransaction;

import javax.inject.Inject;

import static com.hedera.services.legacy.proto.utils.CommonUtils.extractTransactionBody;
import static com.hedera.services.utils.accessors.SignedTxnAccessor.functionExtractor;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ConsensusCreateTopic;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ConsensusUpdateTopic;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenAccountWipe;

public class AccessorFactory {
	final AliasManager aliasManager;

	@Inject
	public AccessorFactory(final AliasManager aliasManager) {
		this.aliasManager = aliasManager;
	}

	public PlatformTxnAccessor constructFrom(SwirldTransaction transaction) throws InvalidProtocolBufferException {
		final var body = extractTransactionBody(Transaction.parseFrom(transaction.getContents()));
		final var function = functionExtractor.apply(body);
		if (function == TokenAccountWipe) {
			return new TokenWipeAccessor(transaction, aliasManager);
		} else if (function == ConsensusCreateTopic || function == ConsensusUpdateTopic) {
			return new TopicCreateAccessor(transaction, aliasManager);
		}
		return new PlatformTxnAccessor(transaction, aliasManager);
	}
}

