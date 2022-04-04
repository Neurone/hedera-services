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
import com.hedera.services.exceptions.NegativeAccountBalanceException;
import com.hedera.services.legacy.core.jproto.JKey;
import com.hedera.services.state.serdes.DomainSerdes;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.state.submerkle.ExpirableTxnRecord;
import com.hedera.services.state.submerkle.FcTokenAllowanceId;
import com.hedera.services.state.submerkle.TokenAssociationMetadata;
import com.hedera.services.utils.EntityNum;
import com.hedera.services.utils.EntityNumPair;
import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.utility.AbstractNaryMerkleInternal;
import com.swirlds.common.merkle.utility.Keyed;
import com.swirlds.fcqueue.FCQueue;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;

public class MerkleAccount extends AbstractNaryMerkleInternal implements MerkleInternal, Keyed<EntityNum> {
	private static final Logger log = LogManager.getLogger(MerkleAccount.class);

	static Runnable stackDump = Thread::dumpStack;

	static final FCQueue<ExpirableTxnRecord> IMMUTABLE_EMPTY_FCQ = new FCQueue<>();

	static {
		IMMUTABLE_EMPTY_FCQ.copy();
	}

	private static final int RELEASE_0240_VERSION = 4;
	static final int MERKLE_VERSION = RELEASE_0240_VERSION;

	static final long RUNTIME_CONSTRUCTABLE_ID = 0x950bcf7255691908L;

	static DomainSerdes serdes = new DomainSerdes();

	@Override
	public EntityNum getKey() {
		return new EntityNum(state().number());
	}

	@Override
	public void setKey(EntityNum phi) {
		state().setNumber(phi.intValue());
	}

	/* Order of Merkle node children */
	public static final class ChildIndices {
		private static final int STATE = 0;
		private static final int RELEASE_090_RECORDS = 1;
		private static final int RELEASE_090_ASSOCIATED_TOKENS = 2;
		static final int NUM_090_CHILDREN = 3;
		static final int NUM_0240_CHILDREN = 2;

		private ChildIndices() {
			throw new UnsupportedOperationException("Utility Class");
		}
	}

	public MerkleAccount(final List<MerkleNode> children, final MerkleAccount immutableAccount) {
		super(immutableAccount);
		addDeserializedChildren(children, MERKLE_VERSION);
	}

	public MerkleAccount(final List<MerkleNode> children) {
		super(ChildIndices.NUM_0240_CHILDREN);
		addDeserializedChildren(children, MERKLE_VERSION);
	}

	public MerkleAccount() {
		addDeserializedChildren(List.of(new MerkleAccountState(), new FCQueue<ExpirableTxnRecord>()), MERKLE_VERSION);
	}

	/* --- MerkleInternal --- */
	@Override
	public long getClassId() {
		return RUNTIME_CONSTRUCTABLE_ID;
	}

	@Override
	public int getVersion() {
		return MERKLE_VERSION;
	}

	@Override
	public int getMinimumChildCount(final int version) {
		return ChildIndices.NUM_0240_CHILDREN;
	}

	/* --- FastCopyable --- */
	@Override
	public MerkleAccount copy() {
		if (isImmutable()) {
			final var msg = String.format(
					"Copy called on immutable MerkleAccount by thread '%s'! Payer records mutable? %s",
					Thread.currentThread().getName(),
					records().isImmutable() ? "NO" : "YES");
			log.warn(msg);
			/* Ensure we get this stack trace in case a caller incorrectly suppresses the exception. */
			stackDump.run();
			throw new IllegalStateException("Tried to make a copy of an immutable MerkleAccount!");
		}

		setImmutable(true);
		return getNumberOfChildren() == ChildIndices.NUM_090_CHILDREN ?
				new MerkleAccount(List.of(state().copy(), records().copy(), tokens().copy()), this) :
				new MerkleAccount(List.of(state().copy(), records().copy()), this);
	}

	/* ---- Object ---- */
	@Override
	public boolean equals(final Object o) {
		if (o == this) {
			return true;
		}
		if (o == null || MerkleAccount.class != o.getClass()) {
			return false;
		}
		final var that = (MerkleAccount) o;
		return this.state().equals(that.state()) && this.records().equals(that.records());
	}

	@Override
	public int hashCode() {
		return Objects.hash(state(), records());
	}

	@Override
	public String toString() {
		return MoreObjects.toStringHelper(MerkleAccount.class)
				.add("state", state())
				.add("# records", records().size())
				.toString();
	}

	/* ----  Merkle children  ---- */
	public MerkleAccountState state() {
		return getChild(ChildIndices.STATE);
	}

	public FCQueue<ExpirableTxnRecord> records() {
		return getChild(ChildIndices.RELEASE_090_RECORDS);
	}

	public MerkleAccountTokens tokens() {
		return getChild(ChildIndices.RELEASE_090_ASSOCIATED_TOKENS);
	}

	public void setTokens(final MerkleAccountTokens tokens) {
		throwIfImmutable("Cannot change this account's tokens if it's immutable.");
		setChild(ChildIndices.RELEASE_090_ASSOCIATED_TOKENS, tokens);
	}

	public void forgetAssociatedTokens() {
		addDeserializedChildren(List.of(state(), records()), RELEASE_0240_VERSION);
	}

	/* ----  Bean  ---- */

	public long getNftsOwned() {
		return state().nftsOwned();
	}

	public void setNftsOwned(final long nftsOwned) {
		throwIfImmutable("Cannot change this account's owned NFTs if it's immutable.");
		state().setNftsOwned(nftsOwned);
	}

	public String getMemo() {
		return state().memo();
	}

	public void setMemo(final String memo) {
		throwIfImmutable("Cannot change this account's memo if it's immutable.");
		state().setMemo(memo);
	}

	public boolean isSmartContract() {
		return state().isSmartContract();
	}

	public void setSmartContract(final boolean smartContract) {
		throwIfImmutable("Cannot change this account's smart contract if it's immutable.");
		state().setSmartContract(smartContract);
	}

	public ByteString getAlias() {
		return state().getAlias();
	}

	public void setAlias(final ByteString alias) {
		throwIfImmutable("Cannot change this account's alias if it's immutable.");
		Objects.requireNonNull(alias);
		state().setAlias(alias);
	}

	public long getTransactionCounter() {
		return state().transactionCounter();
	}

	public void setTransactionCounter(final long transactionCounter) {
		throwIfImmutable("Cannot change this account's transactionCounter if it's immutable.");
		state().setTransactionCounter(transactionCounter);
	}

	public TokenAssociationMetadata getTokenAssociationMetadata() {
		return state().getTokenAssociationMetadata();
	}

	public void setTokenAssociationMetadata(final TokenAssociationMetadata tokenAssociationMetaData) {
		throwIfImmutable("Cannot change this account's tokenAssociationMetaData if it is immutable");
		state().setTokenAssociationMetadata(tokenAssociationMetaData);
	}

	public int getNumAssociations() {
		return getTokenAssociationMetadata().numAssociations();
	}

	public EntityNumPair getLatestAssociation() {
		return getTokenAssociationMetadata().latestAssociation();
	}

	public long getBalance() {
		return state().balance();
	}

	public void setBalance(final long balance) throws NegativeAccountBalanceException {
		if (balance < 0) {
			throw new NegativeAccountBalanceException(String.format("Illegal balance: %d!", balance));
		}
		throwIfImmutable("Cannot change this account's hbar balance if it's immutable.");
		state().setHbarBalance(balance);
	}

	public void setBalanceUnchecked(final long balance) {
		if (balance < 0) {
			throw new IllegalArgumentException("Cannot set an ℏ balance to " + balance);
		}
		throwIfImmutable("Cannot change this account's hbar balance if it's immutable.");
		state().setHbarBalance(balance);
	}

	public boolean isReceiverSigRequired() {
		return state().isReceiverSigRequired();
	}

	public void setReceiverSigRequired(final boolean receiverSigRequired) {
		throwIfImmutable("Cannot change this account's receiver signature required setting if it's immutable.");
		state().setReceiverSigRequired(receiverSigRequired);
	}

	public JKey getAccountKey() {
		return state().key();
	}

	public void setAccountKey(final JKey key) {
		throwIfImmutable("Cannot change this account's key if it's immutable.");
		state().setAccountKey(key);
	}

	public EntityId getProxy() {
		return state().proxy();
	}

	public void setProxy(final EntityId proxy) {
		throwIfImmutable("Cannot change this account's proxy if it's immutable.");
		state().setProxy(proxy);
	}

	public long getAutoRenewSecs() {
		return state().autoRenewSecs();
	}

	public void setAutoRenewSecs(final long autoRenewSecs) {
		throwIfImmutable("Cannot change this account's auto renewal seconds if it's immutable.");
		state().setAutoRenewSecs(autoRenewSecs);
	}

	public boolean isDeleted() {
		return state().isDeleted();
	}

	public void setDeleted(final boolean deleted) {
		throwIfImmutable("Cannot change this account's deleted status if it's immutable.");
		state().setDeleted(deleted);
	}

	public long getExpiry() {
		return state().expiry();
	}

	public void setExpiry(final long expiry) {
		throwIfImmutable("Cannot change this account's expiry time if it's immutable.");
		state().setExpiry(expiry);
	}

	public int getMaxAutomaticAssociations() {
		return state().getMaxAutomaticAssociations();
	}

	public void setMaxAutomaticAssociations(int maxAutomaticAssociations) {
		state().setMaxAutomaticAssociations(maxAutomaticAssociations);
	}

	public int getAlreadyUsedAutoAssociations() {
		return state().getAlreadyUsedAutomaticAssociations();
	}

	public void setAlreadyUsedAutomaticAssociations(final int alreadyUsedAutoAssociations) {
		if (alreadyUsedAutoAssociations < 0 || alreadyUsedAutoAssociations > getMaxAutomaticAssociations()) {
			throw new IllegalArgumentException(
					"Cannot set alreadyUsedAutoAssociations to " + alreadyUsedAutoAssociations);
		}
		state().setAlreadyUsedAutomaticAssociations(alreadyUsedAutoAssociations);
	}

	public int getNumContractKvPairs() {
		return state().getNumContractKvPairs();
	}

	public void setNumContractKvPairs(final int numContractKvPairs) {
		/* The MerkleAccountState will throw a MutabilityException if this MerkleAccount is immutable */
		state().setNumContractKvPairs(numContractKvPairs);
	}

	public Map<EntityNum, Long> getCryptoAllowances() {
		return state().getCryptoAllowances();
	}

	public void setCryptoAllowances(final SortedMap<EntityNum, Long> cryptoAllowances) {
		throwIfImmutable("Cannot change this account's crypto allowances if it's immutable.");
		state().setCryptoAllowances(cryptoAllowances);
	}

	public Map<EntityNum, Long> getCryptoAllowancesUnsafe() {
		return state().getCryptoAllowancesUnsafe();
	}

	public void setCryptoAllowancesUnsafe(final Map<EntityNum, Long> cryptoAllowances) {
		throwIfImmutable("Cannot change this account's crypto allowances if it's immutable.");
		state().setCryptoAllowancesUnsafe(cryptoAllowances);
	}

	public Set<FcTokenAllowanceId> getApproveForAllNfts() {
		return state().getApproveForAllNfts();
	}

	public void setApproveForAllNfts(final Set<FcTokenAllowanceId> approveForAllNfts) {
		throwIfImmutable("Cannot change this account's approved for all NFTs allowances if it's immutable.");
		state().setApproveForAllNfts(approveForAllNfts);
	}

	public Set<FcTokenAllowanceId> getApproveForAllNftsUnsafe() {
		return state().getApproveForAllNftsUnsafe();
	}

	public Map<FcTokenAllowanceId, Long> getFungibleTokenAllowances() {
		return state().getFungibleTokenAllowances();
	}

	public void setFungibleTokenAllowances(final SortedMap<FcTokenAllowanceId, Long> fungibleTokenAllowances) {
		throwIfImmutable("Cannot change this account's fungible token allowances if it's immutable.");
		state().setFungibleTokenAllowances(fungibleTokenAllowances);
	}

	public Map<FcTokenAllowanceId, Long> getFungibleTokenAllowancesUnsafe() {
		return state().getFungibleTokenAllowancesUnsafe();
	}

	public void setFungibleTokenAllowancesUnsafe(final Map<FcTokenAllowanceId, Long> fungibleTokenAllowances) {
		throwIfImmutable("Cannot change this account's fungible token allowances if it's immutable.");
		state().setFungibleTokenAllowancesUnsafe(fungibleTokenAllowances);
	}

	public Iterator<ExpirableTxnRecord> recordIterator() {
		return records().iterator();
	}

	public int numRecords() {
		return records().size();
	}

	public boolean hasAlias() {
		return !getAlias().isEmpty();
	}
}
