package com.hedera.services.state.merkle;

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

import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.hedera.services.legacy.core.jproto.JKey;
import com.hedera.services.state.serdes.DomainSerdes;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.state.submerkle.FcTokenAllowanceId;
import com.hedera.services.state.submerkle.TokenAssociationMetadata;
import com.hedera.services.utils.EntityNum;
import com.hedera.services.utils.EntityNumPair;
import com.swirlds.common.MutabilityException;
import com.swirlds.common.io.SerializableDataInputStream;
import com.swirlds.common.io.SerializableDataOutputStream;
import com.swirlds.common.merkle.utility.AbstractMerkleLeaf;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;

import static com.hedera.services.legacy.core.jproto.JKey.equalUpToDecodability;
import static com.hedera.services.state.merkle.internals.BitPackUtils.getAlreadyUsedAutomaticAssociationsFrom;
import static com.hedera.services.state.merkle.internals.BitPackUtils.getMaxAutomaticAssociationsFrom;
import static com.hedera.services.state.merkle.internals.BitPackUtils.setAlreadyUsedAutomaticAssociationsTo;
import static com.hedera.services.state.merkle.internals.BitPackUtils.setMaxAutomaticAssociationsTo;
import static com.hedera.services.state.submerkle.TokenAssociationMetadata.EMPTY_TOKEN_ASSOCIATION_META;
import static com.hedera.services.utils.EntityIdUtils.asIdLiteral;
import static com.hedera.services.utils.EntityIdUtils.asRelationshipLiteral;
import static com.hedera.services.utils.MiscUtils.describe;
import static com.hedera.services.utils.SerializationUtils.deserializeApproveForAllNftsAllowances;
import static com.hedera.services.utils.SerializationUtils.deserializeCryptoAllowances;
import static com.hedera.services.utils.SerializationUtils.deserializeFungibleTokenAllowances;
import static com.hedera.services.utils.SerializationUtils.serializeApproveForAllNftsAllowances;
import static com.hedera.services.utils.SerializationUtils.serializeCryptoAllowances;
import static com.hedera.services.utils.SerializationUtils.serializeTokenAllowances;

public class MerkleAccountState extends AbstractMerkleLeaf {
	private static final int MAX_CONCEIVABLE_MEMO_UTF8_BYTES = 1_024;

	static final int MAX_CONCEIVABLE_TOKEN_BALANCES_SIZE = 4_096;

	static final int RELEASE_090_VERSION = 4;
	static final int RELEASE_0160_VERSION = 5;
	static final int RELEASE_0180_PRE_SDK_VERSION = 6;
	static final int RELEASE_0180_VERSION = 7;
	static final int RELEASE_0210_VERSION = 8;
	static final int RELEASE_0220_VERSION = 9;
	static final int RELEASE_0250_VERSION = 10;
	static final int RELEASE_0260_VERSION = 11;
	private static final int CURRENT_VERSION = RELEASE_0260_VERSION;
	static final long RUNTIME_CONSTRUCTABLE_ID = 0x354cfc55834e7f12L;

	static DomainSerdes serdes = new DomainSerdes();

	public static final String DEFAULT_MEMO = "";
	private static final ByteString DEFAULT_ALIAS = ByteString.EMPTY;

	private JKey key;
	private long expiry;
	private long hbarBalance;
	private long autoRenewSecs;
	private String memo = DEFAULT_MEMO;
	private boolean deleted;
	private boolean smartContract;
	private boolean receiverSigRequired;
	private EntityId proxy;
	private long nftsOwned;
	private int number;
	private ByteString alias = DEFAULT_ALIAS;
	private int autoAssociationMetadata;
	private int numContractKvPairs;
	private TokenAssociationMetadata tokenAssociationMetaData = EMPTY_TOKEN_ASSOCIATION_META;
	private long transactionCounter;

	// As per the issue https://github.com/hashgraph/hedera-services/issues/2842 these maps will
	// be modified to use MapValueLinkedList in the future
	private Map<EntityNum, Long> cryptoAllowances = Collections.emptyMap();
	private Map<FcTokenAllowanceId, Long> fungibleTokenAllowances = Collections.emptyMap();
	private Set<FcTokenAllowanceId> approveForAllNfts = Collections.emptySet();

	public MerkleAccountState() {
		/* RuntimeConstructable */
	}

	public MerkleAccountState(
			final JKey key,
			final long expiry,
			final long hbarBalance,
			final long autoRenewSecs,
			final String memo,
			final boolean deleted,
			final boolean smartContract,
			final boolean receiverSigRequired,
			final EntityId proxy,
			final int number,
			final int autoAssociationMetadata,
			final ByteString alias,
			final int numContractKvPairs,
			final long transactionCounter,
			final Map<EntityNum, Long> cryptoAllowances,
			final Map<FcTokenAllowanceId, Long> fungibleTokenAllowances,
			final Set<FcTokenAllowanceId> approveForAllNfts
	) {
		this.key = key;
		this.expiry = expiry;
		this.hbarBalance = hbarBalance;
		this.autoRenewSecs = autoRenewSecs;
		this.memo = Optional.ofNullable(memo).orElse(DEFAULT_MEMO);
		this.deleted = deleted;
		this.smartContract = smartContract;
		this.receiverSigRequired = receiverSigRequired;
		this.proxy = proxy;
		this.number = number;
		this.autoAssociationMetadata = autoAssociationMetadata;
		this.alias = Optional.ofNullable(alias).orElse(DEFAULT_ALIAS);
		this.numContractKvPairs = numContractKvPairs;
		this.transactionCounter = transactionCounter;
		this.cryptoAllowances = cryptoAllowances;
		this.fungibleTokenAllowances = fungibleTokenAllowances;
		this.approveForAllNfts = approveForAllNfts;
	}

	/* --- MerkleLeaf --- */
	@Override
	public long getClassId() {
		return RUNTIME_CONSTRUCTABLE_ID;
	}

	@Override
	public int getVersion() {
		return CURRENT_VERSION;
	}

	@Override
	public void deserialize(SerializableDataInputStream in, int version) throws IOException {
		key = serdes.readNullable(in, serdes::deserializeKey);
		expiry = in.readLong();
		hbarBalance = in.readLong();
		autoRenewSecs = in.readLong();
		memo = in.readNormalisedString(MAX_CONCEIVABLE_MEMO_UTF8_BYTES);
		deleted = in.readBoolean();
		smartContract = in.readBoolean();
		receiverSigRequired = in.readBoolean();
		proxy = serdes.readNullableSerializable(in);
		if (version >= RELEASE_0160_VERSION) {
			/* The number of nfts owned is being saved in the state after RELEASE_0160_VERSION */
			nftsOwned = in.readLong();
		}
		if (version >= RELEASE_0180_PRE_SDK_VERSION) {
			autoAssociationMetadata = in.readInt();
		}
		if (version >= RELEASE_0180_VERSION) {
			number = in.readInt();
		}
		if (version >= RELEASE_0210_VERSION) {
			alias = ByteString.copyFrom(in.readByteArray(Integer.MAX_VALUE));
		}
		if (version >= RELEASE_0220_VERSION) {
			numContractKvPairs = in.readInt();
		}
		if (version >= RELEASE_0250_VERSION) {
			cryptoAllowances = deserializeCryptoAllowances(in);
			fungibleTokenAllowances = deserializeFungibleTokenAllowances(in);
			approveForAllNfts = deserializeApproveForAllNftsAllowances(in);
			tokenAssociationMetaData =
					new TokenAssociationMetadata(in.readInt(), in.readInt(), new EntityNumPair(in.readLong()));
		}
		if (version >= RELEASE_0260_VERSION) {
			transactionCounter = in.readLong();
		}
	}

	@Override
	public void serialize(SerializableDataOutputStream out) throws IOException {
		serdes.writeNullable(key, out, serdes::serializeKey);
		out.writeLong(expiry);
		out.writeLong(hbarBalance);
		out.writeLong(autoRenewSecs);
		out.writeNormalisedString(memo);
		out.writeBoolean(deleted);
		out.writeBoolean(smartContract);
		out.writeBoolean(receiverSigRequired);
		serdes.writeNullableSerializable(proxy, out);
		out.writeLong(nftsOwned);
		out.writeInt(autoAssociationMetadata);
		out.writeInt(number);
		out.writeByteArray(alias.toByteArray());
		out.writeInt(numContractKvPairs);
		serializeCryptoAllowances(out, cryptoAllowances);
		serializeTokenAllowances(out, fungibleTokenAllowances);
		serializeApproveForAllNftsAllowances(out, approveForAllNfts);
		out.writeLong(transactionCounter);
		out.writeInt(tokenAssociationMetaData.numAssociations());
		out.writeInt(tokenAssociationMetaData.numZeroBalances());
		out.writeLong(tokenAssociationMetaData.latestAssociation().value());
	}

	/* --- Copyable --- */
	public MerkleAccountState copy() {
		setImmutable(true);
		var copied = new MerkleAccountState(
				key,
				expiry,
				hbarBalance,
				autoRenewSecs,
				memo,
				deleted,
				smartContract,
				receiverSigRequired,
				proxy,
				number,
				autoAssociationMetadata,
				alias,
				numContractKvPairs,
				transactionCounter,
				cryptoAllowances,
				fungibleTokenAllowances,
				approveForAllNfts);
		copied.setNftsOwned(nftsOwned);
		copied.setTokenAssociationMetadata(tokenAssociationMetaData);
		return copied;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || MerkleAccountState.class != o.getClass()) {
			return false;
		}

		var that = (MerkleAccountState) o;

		return this.number == that.number &&
				this.expiry == that.expiry &&
				this.hbarBalance == that.hbarBalance &&
				this.autoRenewSecs == that.autoRenewSecs &&
				Objects.equals(this.memo, that.memo) &&
				this.deleted == that.deleted &&
				this.smartContract == that.smartContract &&
				this.receiverSigRequired == that.receiverSigRequired &&
				Objects.equals(this.proxy, that.proxy) &&
				this.nftsOwned == that.nftsOwned &&
				this.numContractKvPairs == that.numContractKvPairs &&
				this.transactionCounter == that.transactionCounter &&
				this.autoAssociationMetadata == that.autoAssociationMetadata &&
				equalUpToDecodability(this.key, that.key) &&
				Objects.equals(this.alias, that.alias) &&
				Objects.equals(this.cryptoAllowances, that.cryptoAllowances) &&
				Objects.equals(this.fungibleTokenAllowances, that.fungibleTokenAllowances) &&
				Objects.equals(this.approveForAllNfts, that.approveForAllNfts) &&
				this.tokenAssociationMetaData.equals(that.tokenAssociationMetaData);
	}

	@Override
	public int hashCode() {
		return Objects.hash(
				key,
				expiry,
				hbarBalance,
				autoRenewSecs,
				memo,
				deleted,
				smartContract,
				receiverSigRequired,
				proxy,
				nftsOwned,
				number,
				autoAssociationMetadata,
				alias,
				transactionCounter,
				cryptoAllowances,
				fungibleTokenAllowances,
				approveForAllNfts,
				tokenAssociationMetaData);
	}

	/* --- Bean --- */
	@Override
	public String toString() {
		return MoreObjects.toStringHelper(this)
				.add("number", number + " <-> " + asIdLiteral(number))
				.add("key", describe(key))
				.add("expiry", expiry)
				.add("balance", hbarBalance)
				.add("autoRenewSecs", autoRenewSecs)
				.add("memo", memo)
				.add("deleted", deleted)
				.add("smartContract", smartContract)
				.add("numContractKvPairs", numContractKvPairs)
				.add("receiverSigRequired", receiverSigRequired)
				.add("proxy", proxy)
				.add("nftsOwned", nftsOwned)
				.add("alreadyUsedAutoAssociations", getAlreadyUsedAutomaticAssociations())
				.add("maxAutoAssociations", getMaxAutomaticAssociations())
				.add("alias", alias.toStringUtf8())
				.add("transactionCounter", transactionCounter)
				.add("cryptoAllowances", cryptoAllowances)
				.add("fungibleTokenAllowances", fungibleTokenAllowances)
				.add("approveForAllNfts", approveForAllNfts)
				.add("numAssociations", tokenAssociationMetaData.numAssociations())
				.add("numZeroBalances", tokenAssociationMetaData.numZeroBalances())
				.add("lastAssociation", tokenAssociationMetaData.latestAssociation().value()
						+ "<->" + asRelationshipLiteral(tokenAssociationMetaData.latestAssociation().value()))
				.toString();
	}

	public int number() {
		return number;
	}

	public void setNumber(int number) {
		this.number = number;
	}

	public void setAlias(ByteString alias) {
		this.alias = alias;
	}

	public JKey key() {
		return key;
	}

	public long expiry() {
		return expiry;
	}

	public long balance() {
		return hbarBalance;
	}

	public long autoRenewSecs() {
		return autoRenewSecs;
	}

	public String memo() {
		return memo;
	}

	public boolean isDeleted() {
		return deleted;
	}

	public boolean isSmartContract() {
		return smartContract;
	}

	public boolean isReceiverSigRequired() {
		return receiverSigRequired;
	}

	public EntityId proxy() {
		return proxy;
	}

	public long transactionCounter() {
		return transactionCounter;
	}

	public long nftsOwned() {
		return nftsOwned;
	}

	public ByteString getAlias() {
		return alias;
	}

	public void setAccountKey(JKey key) {
		assertMutable("key");
		this.key = key;
	}

	public void setExpiry(long expiry) {
		assertMutable("expiry");
		this.expiry = expiry;
	}

	public void setHbarBalance(long hbarBalance) {
		assertMutable("hbarBalance");
		this.hbarBalance = hbarBalance;
	}

	public void setAutoRenewSecs(long autoRenewSecs) {
		assertMutable("autoRenewSecs");
		this.autoRenewSecs = autoRenewSecs;
	}

	public void setMemo(String memo) {
		assertMutable("memo");
		this.memo = memo;
	}

	public void setTransactionCounter(long transactionCounter) {
		assertMutable("transactionCounter");
		this.transactionCounter = transactionCounter;
	}

	public void setDeleted(boolean deleted) {
		assertMutable("isSmartContract");
		this.deleted = deleted;
	}

	public void setSmartContract(boolean smartContract) {
		assertMutable("isSmartContract");
		this.smartContract = smartContract;
	}

	public void setReceiverSigRequired(boolean receiverSigRequired) {
		assertMutable("isReceiverSigRequired");
		this.receiverSigRequired = receiverSigRequired;
	}

	public void setProxy(EntityId proxy) {
		assertMutable("proxy");
		this.proxy = proxy;
	}

	public void setNftsOwned(long nftsOwned) {
		assertMutable("nftsOwned");
		this.nftsOwned = nftsOwned;
	}

	public TokenAssociationMetadata getTokenAssociationMetadata() {
		return tokenAssociationMetaData;
	}

	public void setTokenAssociationMetadata(final TokenAssociationMetadata tokenAssociationMetaData) {
		assertMutable("tokenAssociationMetaData");
		this.tokenAssociationMetaData = tokenAssociationMetaData;
	}

	public int getNumContractKvPairs() {
		return numContractKvPairs;
	}

	public void setNumContractKvPairs(int numContractKvPairs) {
		assertMutable("numContractKvPairs");
		this.numContractKvPairs = numContractKvPairs;
	}

	public int getMaxAutomaticAssociations() {
		return getMaxAutomaticAssociationsFrom(autoAssociationMetadata);
	}

	public int getAlreadyUsedAutomaticAssociations() {
		return getAlreadyUsedAutomaticAssociationsFrom(autoAssociationMetadata);
	}

	public void setMaxAutomaticAssociations(int maxAutomaticAssociations) {
		assertMutable("maxAutomaticAssociations");
		autoAssociationMetadata = setMaxAutomaticAssociationsTo(autoAssociationMetadata, maxAutomaticAssociations);
	}

	public void setAlreadyUsedAutomaticAssociations(int alreadyUsedCount) {
		assertMutable("alreadyUsedAutomaticAssociations");
		autoAssociationMetadata = setAlreadyUsedAutomaticAssociationsTo(autoAssociationMetadata, alreadyUsedCount);
	}

	public Map<EntityNum, Long> getCryptoAllowances() {
		return Collections.unmodifiableMap(cryptoAllowances);
	}

	public void setCryptoAllowances(final SortedMap<EntityNum, Long> cryptoAllowances) {
		assertMutable("cryptoAllowances");
		this.cryptoAllowances = cryptoAllowances;
	}

	public Map<EntityNum, Long> getCryptoAllowancesUnsafe() {
		return cryptoAllowances;
	}

	public void setCryptoAllowancesUnsafe(final Map<EntityNum, Long> cryptoAllowances) {
		assertMutable("cryptoAllowances");
		this.cryptoAllowances = cryptoAllowances;
	}

	public Set<FcTokenAllowanceId> getApproveForAllNfts() {
		return Collections.unmodifiableSet(approveForAllNfts);
	}

	public void setApproveForAllNfts(final Set<FcTokenAllowanceId> approveForAllNfts) {
		assertMutable("ApproveForAllNfts");
		this.approveForAllNfts = approveForAllNfts;
	}

	public Set<FcTokenAllowanceId> getApproveForAllNftsUnsafe() {
		return approveForAllNfts;
	}

	public Map<FcTokenAllowanceId, Long> getFungibleTokenAllowances() {
		return Collections.unmodifiableMap(fungibleTokenAllowances);
	}

	public void setFungibleTokenAllowances(final SortedMap<FcTokenAllowanceId, Long> fungibleTokenAllowances) {
		assertMutable("fungibleTokenAllowances");
		this.fungibleTokenAllowances = fungibleTokenAllowances;
	}

	public Map<FcTokenAllowanceId, Long> getFungibleTokenAllowancesUnsafe() {
		return fungibleTokenAllowances;
	}

	public void setFungibleTokenAllowancesUnsafe(final Map<FcTokenAllowanceId, Long> fungibleTokenAllowances) {
		assertMutable("fungibleTokenAllowances");
		this.fungibleTokenAllowances = fungibleTokenAllowances;
	}

	private void assertMutable(String proximalField) {
		if (isImmutable()) {
			throw new MutabilityException("Cannot set " + proximalField + " on an immutable account state!");
		}
	}
}
