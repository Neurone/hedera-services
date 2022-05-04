package com.hedera.services.state.virtual.schedule;

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

import java.beans.Transient;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.services.exceptions.UnknownHederaFunctionality;
import com.hedera.services.legacy.core.jproto.JKey;
import com.hedera.services.state.merkle.MerkleSchedule;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.state.submerkle.RichInstant;
import com.hedera.services.utils.MiscUtils;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.SchedulableTransactionBody;
import com.hederahashgraph.api.proto.java.SignedTransaction;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.swirlds.common.io.streams.SerializableDataInputStream;
import com.swirlds.common.io.streams.SerializableDataOutputStream;
import com.swirlds.common.utility.CommonUtils;
import com.swirlds.virtualmap.VirtualValue;

import static com.google.protobuf.ByteString.copyFrom;
import static com.hedera.services.utils.MiscUtils.asTimestamp;
import static com.hedera.services.utils.MiscUtils.describe;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.NONE;

public class ScheduleVirtualValue implements VirtualValue {

	static final int CURRENT_VERSION = 4;
	static final long RUNTIME_CONSTRUCTABLE_ID = 0xadfd7f9e613385fcL;


	private boolean immutable;

	@Nullable
	private Key grpcAdminKey = null;
	@Nullable
	private JKey adminKey = null;
	private String memo;
	private boolean deleted = false;
	private boolean executed = false;
	private boolean waitForExpiry = false;
	@Nullable
	private EntityId payer = null;
	private EntityId schedulingAccount;
	private RichInstant schedulingTXValidStart;
	@Nullable
	private RichInstant expirationTimeProvided = null;
	@Nullable
	private RichInstant calculatedExpirationTime = null;
	@Nullable
	private RichInstant resolutionTime = null;

	private byte[] bodyBytes;
	private TransactionBody ordinaryScheduledTxn;
	private SchedulableTransactionBody scheduledTxn;

	private final List<byte[]> signatories = new ArrayList<>();
	private final Set<ByteString> notary = ConcurrentHashMap.newKeySet();

	public ScheduleVirtualValue() {
		/* RuntimeConstructable */
	}

	public ScheduleVirtualValue(ScheduleVirtualValue toCopy) {

		/* These fields are all immutable or effectively immutable, we can share them between copies */
		this.grpcAdminKey = toCopy.grpcAdminKey;
		this.adminKey = toCopy.adminKey;
		this.memo = toCopy.memo;
		this.waitForExpiry = toCopy.waitForExpiry;
		this.deleted = toCopy.deleted;
		this.executed = toCopy.executed;
		this.payer = toCopy.payer;
		this.schedulingAccount = toCopy.schedulingAccount;
		this.schedulingTXValidStart = toCopy.schedulingTXValidStart;
		this.expirationTimeProvided = toCopy.expirationTimeProvided;
		this.calculatedExpirationTime = toCopy.calculatedExpirationTime;
		this.bodyBytes = toCopy.bodyBytes;
		this.scheduledTxn = toCopy.scheduledTxn;
		this.ordinaryScheduledTxn = toCopy.ordinaryScheduledTxn;
		this.resolutionTime = toCopy.resolutionTime;

		/* Signatories are mutable */
		for (byte[] signatory : toCopy.signatories) {
			this.witnessValidSignature(signatory);
		}
	}

	public ScheduleVirtualValue(MerkleSchedule toCopy) {
		bodyBytes = toCopy.bodyBytes();
		calculatedExpirationTime = new RichInstant(toCopy.expiry(), 0);
		executed = toCopy.isExecuted();
		deleted = toCopy.isDeleted();
		resolutionTime = toCopy.getResolutionTime();
		for (var sig : toCopy.signatories()) {
			witnessValidSignature(sig);
		}

		initFromBodyBytes();
	}

	public static ScheduleVirtualValue from(byte[] bodyBytes, long consensusExpiry) {
		var to = new ScheduleVirtualValue();
		to.calculatedExpirationTime = new RichInstant(consensusExpiry, 0);
		to.bodyBytes = bodyBytes;
		to.initFromBodyBytes();

		return to;
	}

	public static ScheduleVirtualValue from(byte[] bodyBytes, RichInstant consensusExpiry) {
		var to = new ScheduleVirtualValue();
		to.calculatedExpirationTime = consensusExpiry;
		to.bodyBytes = bodyBytes;
		to.initFromBodyBytes();

		return to;
	}

	/* Notary functions */
	public boolean witnessValidSignature(byte[] key) {
		final var usableKey = copyFrom(key);
		if (notary.contains(usableKey)) {
			return false;
		} else {
			signatories.add(key);
			notary.add(usableKey);
			return true;
		}
	}

	public Transaction asSignedTxn() {
		return Transaction.newBuilder()
				.setSignedTransactionBytes(
						SignedTransaction.newBuilder()
								.setBodyBytes(
										TransactionBody.newBuilder()
												.mergeFrom(ordinaryScheduledTxn)
												.setTransactionID(scheduledTransactionId())
												.build()
												.toByteString())
								.build()
								.toByteString())
				.build();
	}

	public Transaction parentAsSignedTxn() {
		return Transaction.newBuilder()
				.setSignedTransactionBytes(
						SignedTransaction.newBuilder()
								.setBodyBytes(ByteString.copyFrom(bodyBytes))
								.build()
								.toByteString())
				.build();
	}

	public TransactionID scheduledTransactionId() {
		if (schedulingAccount == null || schedulingTXValidStart == null) {
			throw new IllegalStateException("Cannot invoke scheduledTransactionId on a content-addressable view!");
		}
		return TransactionID.newBuilder()
				.setAccountID(schedulingAccount.toGrpcAccountId())
				.setTransactionValidStart(asTimestamp(schedulingTXValidStart.toJava()))
				.setScheduled(true)
				.build();
	}

	public boolean hasValidSignatureFor(byte[] key) {
		return notary.contains(copyFrom(key));
	}

	/* Object */

	/**
	 * Two {@code ScheduleVirtualValue}s are identical as long as they agree on
	 * the all the fields of the ScheduleCreate other than the payerAccountID.
	 *
	 * @param o
	 * 		the object to check for equality
	 * @return whether {@code this} and {@code o} are identical
	 */
	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || ScheduleVirtualValue.class != o.getClass()) {
			return false;
		}

		var that = (ScheduleVirtualValue) o;
		return Objects.equals(this.memo, that.memo) &&
				Objects.equals(this.scheduledTxn, that.scheduledTxn) &&
				Objects.equals(this.grpcAdminKey, that.grpcAdminKey) &&
				Objects.equals(this.expirationTimeProvided, that.expirationTimeProvided) &&
				Objects.equals(this.waitForExpiry, that.waitForExpiry);
	}

	@Override
	public int hashCode() {
		return Objects.hash(memo, grpcAdminKey, scheduledTxn, expirationTimeProvided, waitForExpiry);
	}

	public long equalityCheckKey() {
		return equalityHash().asLong();
	}

	public String equalityCheckValue() {
		return equalityHash().toString();
	}

	private HashCode equalityHash() {
		// if this changes at all, the equality database for scheduled transactions will need to be re-built
		return buildEqualityHash(
				memo != null ? memo.getBytes(StandardCharsets.UTF_8): new byte[] {},
				grpcAdminKey != null ? grpcAdminKey.toByteArray() : new byte[] {},
				scheduledTxn.toByteArray(),
				expirationTimeProvided != null ? expirationTimeProvided.toGrpc().toByteArray() : new byte[] {},
				waitForExpiry ? new byte[] {1} : new byte[] {0});
	}

	@Override
	public String toString() {
		var helper = MoreObjects.toStringHelper(ScheduleVirtualValue.class)
				.add("scheduledTxn", scheduledTxn)
				.add("expirationTimeProvided", expirationTimeProvided)
				.add("calculatedExpirationTime", calculatedExpirationTime)
				.add("executed", executed)
				.add("waitForExpiry", waitForExpiry)
				.add("deleted", deleted)
				.add("memo", memo)
				.add("payer", readablePayer())
				.add("schedulingAccount", schedulingAccount)
				.add("schedulingTXValidStart", schedulingTXValidStart)
				.add("signatories", signatories.stream().map(CommonUtils::hex).toList())
				.add("adminKey", describe(adminKey))
				.add("resolutionTime", resolutionTime);
		return helper.toString();
	}

	private String readablePayer() {
		return Optional.ofNullable(effectivePayer()).map(EntityId::toAbbrevString).orElse("<N/A>");
	}

	@Override
	public void deserialize(SerializableDataInputStream in, int version) throws IOException {
		int n = in.readInt();
		bodyBytes = new byte[n];
		in.readFully(bodyBytes);
		if (in.readByte() == 1) {
			calculatedExpirationTime = new RichInstant(in.readLong(), in.readInt());
		} else {
			calculatedExpirationTime = null;
		}
		executed = in.readByte() == 1;
		deleted = in.readByte() == 1;
		if (in.readByte() == 1) {
			resolutionTime = new RichInstant(in.readLong(), in.readInt());
		} else {
			resolutionTime = null;
		}
		int k = in.readInt();
		for (int x = 0; x < k; ++x) {
			n = in.readInt();
			byte[] bytes = new byte[n];
			in.readFully(bytes);
			witnessValidSignature(bytes);
		}

		initFromBodyBytes();
	}

	@Override
	public void serialize(SerializableDataOutputStream out) throws IOException {
		out.writeInt(bodyBytes.length);
		out.write(bodyBytes);
		if (calculatedExpirationTime == null) {
			out.writeByte((byte) 0);
		} else {
			out.writeByte((byte) 1);
			out.writeLong(calculatedExpirationTime.getSeconds());
			out.writeInt(calculatedExpirationTime.getNanos());
		}
		out.writeByte((byte) (executed ? 1 : 0));
		out.writeByte((byte) (deleted ? 1 : 0));
		if (resolutionTime == null) {
			out.writeByte((byte) 0);
		} else {
			out.writeByte((byte) 1);
			out.writeLong(resolutionTime.getSeconds());
			out.writeInt(resolutionTime.getNanos());
		}
		out.writeInt(signatories.size());
		for (byte[] key : signatories) {
			out.writeInt(key.length);
			out.write(key);
		}
	}

	@Override
	public void deserialize(ByteBuffer in, int version) throws IOException {
		var n = in.getInt();
		bodyBytes = new byte[n];
		in.get(bodyBytes);
		if (in.get() == 1) {
			calculatedExpirationTime = new RichInstant(in.getLong(), in.getInt());
		} else {
			calculatedExpirationTime = null;
		}
		executed = in.get() == 1;
		deleted = in.get() == 1;
		if (in.get() == 1) {
			resolutionTime = new RichInstant(in.getLong(), in.getInt());
		} else {
			resolutionTime = null;
		}
		int k = in.getInt();
		for (int x = 0; x < k; ++x) {
			n = in.getInt();
			byte[] bytes = new byte[n];
			in.get(bytes);
			witnessValidSignature(bytes);
		}

		initFromBodyBytes();
	}

	@Override
	public void serialize(ByteBuffer out) throws IOException {
		out.putInt(bodyBytes.length);
		out.put(bodyBytes);
		if (calculatedExpirationTime == null) {
			out.put((byte) 0);
		} else {
			out.put((byte) 1);
			out.putLong(calculatedExpirationTime.getSeconds());
			out.putInt(calculatedExpirationTime.getNanos());
		}
		out.put((byte) (executed ? 1 : 0));
		out.put((byte) (deleted ? 1 : 0));
		if (resolutionTime == null) {
			out.put((byte) 0);
		} else {
			out.put((byte) 1);
			out.putLong(resolutionTime.getSeconds());
			out.putInt(resolutionTime.getNanos());
		}
		out.putInt(signatories.size());
		for (byte[] key : signatories) {
			out.putInt(key.length);
			out.put(key);
		}
	}

	@Override
	public long getClassId() {
		return RUNTIME_CONSTRUCTABLE_ID;
	}

	@Override
	public int getVersion() {
		return CURRENT_VERSION;
	}

	public Optional<String> memo() {
		return Optional.ofNullable(this.memo);
	}

	public boolean isWaitForExpiry() {
		return this.waitForExpiry;
	}

	public boolean hasAdminKey() {
		return adminKey != null;
	}

	public Optional<JKey> adminKey() {
		return Optional.ofNullable(adminKey);
	}

	@VisibleForTesting
	public void setAdminKey(JKey adminKey) {
		throwIfImmutable("Cannot change this schedule's adminKey if it's immutable.");
		this.adminKey = adminKey;
	}

	@VisibleForTesting
	public void setPayer(EntityId payer) {
		throwIfImmutable("Cannot change this schedule's payer if it's immutable.");
		this.payer = payer;
	}

	public EntityId payer() {
		return payer;
	}

	public EntityId effectivePayer() {
		return hasExplicitPayer() ? payer : schedulingAccount;
	}

	public boolean hasExplicitPayer() {
		return payer != null;
	}

	public EntityId schedulingAccount() {
		return schedulingAccount;
	}

	@VisibleForTesting
	void setSchedulingAccount(EntityId schedulingAccount) {
		this.schedulingAccount = schedulingAccount;
	}

	public RichInstant schedulingTXValidStart() {
		return this.schedulingTXValidStart;
	}

	public List<byte[]> signatories() {
		return signatories;
	}

	public RichInstant expirationTimeProvided() {
		return expirationTimeProvided;
	}

	public RichInstant calculatedExpirationTime() {
		return calculatedExpirationTime;
	}

	public void setCalculatedExpirationTime(final RichInstant calculatedExpirationTime) {
		throwIfImmutable("Cannot change this schedule's payer if it's immutable.");
		this.calculatedExpirationTime = calculatedExpirationTime;
	}

	public void markDeleted(Instant at) {
		throwIfImmutable("Cannot change this schedule to deleted if it's immutable.");
		resolutionTime = RichInstant.fromJava(at);
		deleted = true;
	}

	public void markExecuted(Instant at) {
		throwIfImmutable("Cannot change this schedule to executed if it's immutable.");
		resolutionTime = RichInstant.fromJava(at);
		executed = true;
	}

	public boolean isExecuted() {
		return executed;
	}

	public boolean isDeleted() {
		return deleted;
	}

	public Timestamp deletionTime() {
		if (!deleted) {
			throw new IllegalStateException("Schedule not deleted, cannot return deletion time!");
		}
		return resolutionTime.toGrpc();
	}

	public Timestamp executionTime() {
		if (!executed) {
			throw new IllegalStateException("Schedule not executed, cannot return execution time!");
		}
		return resolutionTime.toGrpc();
	}

	@VisibleForTesting
	public RichInstant getResolutionTime() {
		return resolutionTime;
	}

	public HederaFunctionality scheduledFunction() {
		try {
			return MiscUtils.functionOf(ordinaryScheduledTxn);
		} catch (UnknownHederaFunctionality ignore) {
			return NONE;
		}
	}

	public TransactionBody ordinaryViewOfScheduledTxn() {
		return ordinaryScheduledTxn;
	}

	public SchedulableTransactionBody scheduledTxn() {
		return scheduledTxn;
	}

	public byte[] bodyBytes() {
		return bodyBytes;
	}

	public Key grpcAdminKey() {
		return grpcAdminKey;
	}

	private void initFromBodyBytes() {
		try {
			var parentTxn = TransactionBody.parseFrom(bodyBytes);
			var creationOp = parentTxn.getScheduleCreate();

			if (!creationOp.getMemo().isEmpty()) {
				memo = creationOp.getMemo();
			}
			expirationTimeProvided = creationOp.hasExpirationTime() ? RichInstant.fromGrpc(creationOp.getExpirationTime()) : null;
			waitForExpiry = creationOp.getWaitForExpiry();
			if (creationOp.hasPayerAccountID()) {
				payer = EntityId.fromGrpcAccountId(creationOp.getPayerAccountID());
			}
			if (creationOp.hasAdminKey()) {
				MiscUtils.asUsableFcKey(creationOp.getAdminKey()).ifPresent(this::setAdminKey);
				if (adminKey != null) {
					grpcAdminKey = creationOp.getAdminKey();
				}
			}
			scheduledTxn = creationOp.getScheduledTransactionBody();
			schedulingAccount = EntityId.fromGrpcAccountId(parentTxn.getTransactionID().getAccountID());
			ordinaryScheduledTxn = MiscUtils.asOrdinary(scheduledTxn);
			schedulingTXValidStart = RichInstant.fromGrpc(parentTxn.getTransactionID().getTransactionValidStart());
		} catch (InvalidProtocolBufferException e) {
			throw new IllegalArgumentException(String.format(
					"Argument bodyBytes=0x%s was not a TransactionBody!", CommonUtils.hex(bodyBytes)));
		}
	}


	/**
	 * {@inheritDoc}
	 */
	@Override
	public final boolean isImmutable() {
		return immutable;
	}

	@Transient
	protected final void setImmutable(final boolean immutable) {
		this.immutable = immutable;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void release() {
		// nothing to release
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public ScheduleVirtualValue asReadOnly() {
		var c = new ScheduleVirtualValue(this);
		c.setImmutable(true);
		return c;
	}

	@Override
	public ScheduleVirtualValue copy() {
		var fc = new ScheduleVirtualValue(this);

		this.setImmutable(true);

		return fc;
	}

	/**
	 * Needed until getForModify works on VirtualMap
	 * @return a copy of this without marking this as immutable
	 */
	public ScheduleVirtualValue asWritable() {
		return new ScheduleVirtualValue(this);
	}

	private static HashCode buildEqualityHash(byte[]... a) {
		var hasher = Hashing.sha256().newHasher();

        for (byte[] bytes : a) {
			hasher.putInt(bytes.length);
			hasher.putBytes(bytes);
		}

        return hasher.hash();
    }
}