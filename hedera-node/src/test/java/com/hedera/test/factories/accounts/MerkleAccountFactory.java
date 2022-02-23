package com.hedera.test.factories.accounts;

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
import com.hedera.services.legacy.core.jproto.JKey;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.state.submerkle.FcTokenAllowance;
import com.hedera.services.state.submerkle.FcTokenAllowanceId;
import com.hedera.services.store.models.Id;
import com.hedera.services.utils.EntityNum;
import com.hedera.test.factories.keys.KeyFactory;
import com.hedera.test.factories.keys.KeyTree;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.TokenID;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

public class MerkleAccountFactory {
	private boolean useNewStyleTokenIds = false;

	private int numKvPairs = 0;
	private KeyFactory keyFactory = KeyFactory.getDefaultInstance();
	private Optional<Long> balance = Optional.empty();
	private Optional<Long> receiverThreshold = Optional.empty();
	private Optional<Long> senderThreshold = Optional.empty();
	private Optional<Boolean> receiverSigRequired = Optional.empty();
	private Optional<JKey> accountKeys = Optional.empty();
	private Optional<Long> autoRenewPeriod = Optional.empty();
	private Optional<Boolean> deleted = Optional.empty();
	private Optional<Long> expirationTime = Optional.empty();
	private Optional<String> memo = Optional.empty();
	private Optional<Boolean> isSmartContract = Optional.empty();
	private Optional<AccountID> proxy = Optional.empty();
	private Optional<Integer> alreadyUsedAutoAssociations = Optional.empty();
	private Optional<Integer> maxAutoAssociations = Optional.empty();
	private Optional<ByteString> alias = Optional.empty();
	private Set<TokenID> associatedTokens = new HashSet<>();
	private Set<Id> assocTokens = new HashSet<>();
	private TreeMap<EntityNum, Long> cryptoAllowances = new TreeMap<>();
	private TreeMap<FcTokenAllowanceId, Long> fungibleTokenAllowances = new TreeMap<>();
	private TreeMap<FcTokenAllowanceId, FcTokenAllowance> nftAllowances = new TreeMap<>();

	public MerkleAccount get() {
		MerkleAccount value = new MerkleAccount();
		memo.ifPresent(value::setMemo);
		alias.ifPresent(value::setAlias);
		proxy.ifPresent(p -> value.setProxy(EntityId.fromGrpcAccountId(p)));
		balance.ifPresent(b -> {
			try {
				value.setBalance(b);
			} catch (Exception ignore) {
			}
		});
		deleted.ifPresent(value::setDeleted);
		accountKeys.ifPresent(value::setAccountKey);
		expirationTime.ifPresent(value::setExpiry);
		autoRenewPeriod.ifPresent(value::setAutoRenewSecs);
		isSmartContract.ifPresent(value::setSmartContract);
		receiverSigRequired.ifPresent(value::setReceiverSigRequired);
		maxAutoAssociations.ifPresent(value::setMaxAutomaticAssociations);
		alreadyUsedAutoAssociations.ifPresent(value::setAlreadyUsedAutomaticAssociations);
//		var tokens = new MerkleAccountTokens();
//		if (useNewStyleTokenIds) {
//			tokens.associate(assocTokens);
//		} else {
//			tokens.associateAll(associatedTokens);
//		}
//		value.setTokens(tokens);
		value.setNumContractKvPairs(numKvPairs);
		value.setCryptoAllowances(cryptoAllowances);
		value.setFungibleTokenAllowances(fungibleTokenAllowances);
		value.setNftAllowances(nftAllowances);
		return value;
	}

	private MerkleAccountFactory() {
	}

	public static MerkleAccountFactory newAccount() {
		return new MerkleAccountFactory();
	}

	public static MerkleAccountFactory newContract() {
		return new MerkleAccountFactory().isSmartContract(true);
	}

	public MerkleAccountFactory numKvPairs(final int numKvPairs) {
		this.numKvPairs = numKvPairs;
		return this;
	}

	public MerkleAccountFactory proxy(final AccountID id) {
		proxy = Optional.of(id);
		return this;
	}

	public MerkleAccountFactory balance(final long amount) {
		balance = Optional.of(amount);
		return this;
	}

	public MerkleAccountFactory alias(final ByteString bytes) {
		alias = Optional.of(bytes);
		return this;
	}

	public MerkleAccountFactory assocTokens(final Id... tokens) {
		useNewStyleTokenIds = true;
		assocTokens.addAll(List.of(tokens));
		return this;
	}

	public MerkleAccountFactory tokens(final TokenID... tokens) {
		associatedTokens.addAll(List.of(tokens));
		return this;
	}

	public MerkleAccountFactory receiverThreshold(final long v) {
		receiverThreshold = Optional.of(v);
		return this;
	}

	public MerkleAccountFactory senderThreshold(final long v) {
		senderThreshold = Optional.of(v);
		return this;
	}

	public MerkleAccountFactory receiverSigRequired(final boolean b) {
		receiverSigRequired = Optional.of(b);
		return this;
	}

	public MerkleAccountFactory keyFactory(final KeyFactory keyFactory) {
		this.keyFactory = keyFactory;
		return this;
	}

	public MerkleAccountFactory accountKeys(final KeyTree kt) throws Exception {
		return accountKeys(kt.asKey(keyFactory));
	}

	public MerkleAccountFactory accountKeys(final Key k) throws Exception {
		return accountKeys(JKey.mapKey(k));
	}

	public MerkleAccountFactory accountKeys(final JKey k) {
		accountKeys = Optional.of(k);
		return this;
	}

	public MerkleAccountFactory autoRenewPeriod(final long p) {
		autoRenewPeriod = Optional.of(p);
		return this;
	}

	public MerkleAccountFactory deleted(final boolean b) {
		deleted = Optional.of(b);
		return this;
	}

	public MerkleAccountFactory expirationTime(final long l) {
		expirationTime = Optional.of(l);
		return this;
	}

	public MerkleAccountFactory memo(final String s) {
		memo = Optional.of(s);
		return this;
	}

	public MerkleAccountFactory isSmartContract(final boolean b) {
		isSmartContract = Optional.of(b);
		return this;
	}

	public MerkleAccountFactory maxAutomaticAssociations(final int max) {
		maxAutoAssociations = Optional.of(max);
		return this;
	}

	public MerkleAccountFactory alreadyUsedAutomaticAssociations(final int count) {
		alreadyUsedAutoAssociations = Optional.of(count);
		return this;
	}

	public MerkleAccountFactory cryptoAllowances(final TreeMap<EntityNum, Long> allowances) {
		cryptoAllowances = allowances;
		return this;
	}

	public MerkleAccountFactory fungibleTokenAllowances(final TreeMap<FcTokenAllowanceId, Long> allowances) {
		fungibleTokenAllowances = allowances;
		return this;
	}

	public MerkleAccountFactory nftAllowances(final TreeMap<FcTokenAllowanceId, FcTokenAllowance> allowances) {
		nftAllowances = allowances;
		return this;
	}
}
