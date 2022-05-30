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

import com.hedera.services.ledger.properties.AccountProperty;
import com.hedera.services.ledger.properties.ChangeSummaryManager;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.submerkle.EntityId;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractCreateTransactionBody;
import com.hederahashgraph.api.proto.java.Duration;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import static com.hedera.services.ledger.properties.AccountProperty.AUTO_RENEW_ACCOUNT_ID;
import static com.hedera.services.ledger.properties.AccountProperty.AUTO_RENEW_PERIOD;
import static com.hedera.services.ledger.properties.AccountProperty.MAX_AUTOMATIC_ASSOCIATIONS;
import static com.hedera.services.ledger.properties.AccountProperty.MEMO;
import static com.hedera.services.ledger.properties.AccountProperty.PROXY;

public final class HederaAccountCustomizer extends
		AccountCustomizer<AccountID, MerkleAccount, AccountProperty, HederaAccountCustomizer> {
	private static final Map<Option, AccountProperty> OPTION_PROPERTIES;

	static {
		Map<Option, AccountProperty> optionAccountPropertyMap = new EnumMap<>(Option.class);
		optionAccountPropertyMap.put(Option.KEY, AccountProperty.KEY);
		optionAccountPropertyMap.put(Option.MEMO, MEMO);
		optionAccountPropertyMap.put(Option.PROXY, PROXY);
		optionAccountPropertyMap.put(Option.EXPIRY, AccountProperty.EXPIRY);
		optionAccountPropertyMap.put(Option.IS_DELETED, AccountProperty.IS_DELETED);
		optionAccountPropertyMap.put(Option.AUTO_RENEW_PERIOD, AUTO_RENEW_PERIOD);
		optionAccountPropertyMap.put(Option.IS_SMART_CONTRACT, AccountProperty.IS_SMART_CONTRACT);
		optionAccountPropertyMap.put(Option.IS_RECEIVER_SIG_REQUIRED, AccountProperty.IS_RECEIVER_SIG_REQUIRED);
		optionAccountPropertyMap.put(Option.MAX_AUTOMATIC_ASSOCIATIONS, MAX_AUTOMATIC_ASSOCIATIONS);
		optionAccountPropertyMap.put(Option.USED_AUTOMATIC_ASSOCIATIONS, AccountProperty.USED_AUTOMATIC_ASSOCIATIONS);
		optionAccountPropertyMap.put(Option.ALIAS, AccountProperty.ALIAS);
		optionAccountPropertyMap.put(Option.AUTO_RENEW_ACCOUNT_ID, AUTO_RENEW_ACCOUNT_ID);
		OPTION_PROPERTIES = Collections.unmodifiableMap(optionAccountPropertyMap);
	}

	public HederaAccountCustomizer() {
		super(AccountProperty.class, OPTION_PROPERTIES, new ChangeSummaryManager<>());
	}

	public void customizeSynthetic(final ContractCreateTransactionBody.Builder op) {
		final var changes = getChanges();
		if (changes.includes(MEMO)) {
			op.setMemo((String) changes.get(MEMO));
		}
		if (changes.includes(AUTO_RENEW_PERIOD)) {
			op.setAutoRenewPeriod(Duration.newBuilder()
					.setSeconds((long) changes.get(AUTO_RENEW_PERIOD)));
		}
		if (changes.includes(PROXY)) {
			op.setProxyAccountID(((EntityId) changes.get(PROXY)).toGrpcAccountId());
		}
		if (changes.includes(AUTO_RENEW_ACCOUNT_ID)) {
			op.setAutoRenewAccountId(((EntityId) changes.get(AUTO_RENEW_ACCOUNT_ID)).toGrpcAccountId());
		}
		if (changes.includes(MAX_AUTOMATIC_ASSOCIATIONS)) {
			op.setMaxAutomaticTokenAssociations(((int) changes.get(MAX_AUTOMATIC_ASSOCIATIONS)));
		}
	}

	@Override
	protected HederaAccountCustomizer self() {
		return this;
	}
}
