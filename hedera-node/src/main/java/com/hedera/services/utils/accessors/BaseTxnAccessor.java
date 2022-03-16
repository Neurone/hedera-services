package com.hedera.services.utils.accessors;

import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.services.grpc.marshalling.AliasResolver;
import com.hedera.services.ledger.accounts.AliasManager;
import com.hedera.services.sigs.order.LinkedRefs;
import com.hedera.services.sigs.sourcing.PojoSigMapPubKeyToSigBytes;
import com.hedera.services.sigs.sourcing.PubKeyToSigBytes;
import com.hedera.services.txns.span.ExpandHandleSpanMapAccessor;
import com.hedera.services.usage.BaseTransactionMeta;
import com.hedera.services.usage.consensus.SubmitMessageMeta;
import com.hedera.services.usage.crypto.CryptoAdjustAllowanceMeta;
import com.hedera.services.usage.crypto.CryptoApproveAllowanceMeta;
import com.hedera.services.usage.crypto.CryptoCreateMeta;
import com.hedera.services.usage.crypto.CryptoTransferMeta;
import com.hedera.services.usage.crypto.CryptoUpdateMeta;
import com.hedera.services.usage.token.TokenOpsUsage;
import com.hedera.services.usage.token.meta.FeeScheduleUpdateMeta;
import com.hedera.services.utils.EntityIdUtils;
import com.hedera.services.utils.EntityNum;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ScheduleID;
import com.hederahashgraph.api.proto.java.SignatureMap;
import com.hederahashgraph.api.proto.java.SignedTransaction;
import com.hederahashgraph.api.proto.java.SubType;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.swirlds.common.crypto.TransactionSignature;
import org.apache.commons.codec.binary.StringUtils;
import org.bouncycastle.util.Arrays;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static com.hedera.services.legacy.proto.utils.CommonUtils.noThrowSha384HashOf;
import static com.hedera.services.usage.token.TokenOpsUsageUtils.TOKEN_OPS_USAGE_UTILS;
import static com.hedera.services.utils.MiscUtils.functionExtractor;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ConsensusSubmitMessage;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ContractCreate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoAdjustAllowance;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoApproveAllowance;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoCreate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoTransfer;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoUpdate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenAccountWipe;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenBurn;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenCreate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenFeeScheduleUpdate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenFreezeAccount;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenMint;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenPause;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenUnfreezeAccount;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenUnpause;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_NON_FUNGIBLE_UNIQUE;

/**
 * The base implementation of a TxnAccessor; accessor for every transaction type will be extending this class.
 * This will not provide any data related to signatures
 */
public class BaseTxnAccessor implements TxnAccessor {
	private boolean isTriggered;
	private ScheduleID scheduleRef;
	private AliasManager aliasManager;

	private static final int UNKNOWN_NUM_AUTO_CREATIONS = -1;
	private static final String ACCESSOR_LITERAL = " accessor";

	private static final TokenOpsUsage TOKEN_OPS_USAGE = new TokenOpsUsage();
	private static final ExpandHandleSpanMapAccessor SPAN_MAP_ACCESSOR = new ExpandHandleSpanMapAccessor();

	private final Map<String, Object> spanMap = new HashMap<>();

	private int numAutoCreations = UNKNOWN_NUM_AUTO_CREATIONS;
	private byte[] hash;
	private byte[] txnBytes;
	private byte[] utf8MemoBytes;
	private byte[] signedTxnWrapperBytes;
	private String memo;
	private boolean memoHasZeroByte;
	private LinkedRefs linkedRefs;
	private Transaction signedTxnWrapper;
	private TransactionID txnId;
	private TransactionBody txn;
	private HederaFunctionality function;
	private AccountID payer;
	private BaseTransactionMeta txnUsageMeta;
	private int sigMapSize;
	private int numSigPairs;
	private PubKeyToSigBytes pubKeyToSigBytes;
	private ResponseCodeEnum expandedSigStatus;
	private SignatureMap sigMap;

	private SubmitMessageMeta submitMessageMeta;
	private CryptoTransferMeta xferUsageMeta;

	protected BaseTxnAccessor(final byte[] signedTxnWrapperBytes, final AliasManager aliasManager)
			throws InvalidProtocolBufferException {
		this.aliasManager = aliasManager;
		this.signedTxnWrapperBytes = signedTxnWrapperBytes;
		signedTxnWrapper = Transaction.parseFrom(signedTxnWrapperBytes);

		final var signedTxnBytes = signedTxnWrapper.getSignedTransactionBytes();

		if (signedTxnBytes.isEmpty()) {
			txnBytes = signedTxnWrapper.getBodyBytes().toByteArray();
			hash = noThrowSha384HashOf(signedTxnWrapperBytes);
			sigMap = signedTxnWrapper.getSigMap();
		} else {
			final var signedTxn = SignedTransaction.parseFrom(signedTxnBytes);
			txnBytes = signedTxn.getBodyBytes().toByteArray();
			hash = noThrowSha384HashOf(signedTxnBytes.toByteArray());
			sigMap = signedTxn.getSigMap();
		}
		pubKeyToSigBytes = new PojoSigMapPubKeyToSigBytes(sigMap);
		txn = TransactionBody.parseFrom(txnBytes);
		memo = txn.getMemo();
		txnId = txn.getTransactionID();
		payer = txnId.getAccountID();
		utf8MemoBytes = StringUtils.getBytesUtf8(memo);
		memoHasZeroByte = Arrays.contains(utf8MemoBytes, (byte) 0);
		setBaseUsageMeta();
	}

	public static BaseTxnAccessor from(Transaction txn,
			AliasManager aliasManager) throws InvalidProtocolBufferException {
		return new BaseTxnAccessor(txn.getSignedTransactionBytes().toByteArray(), aliasManager);
	}

	public static BaseTxnAccessor from(final byte[] signedTxnWrapperBytes,
			final AliasManager aliasManager) throws InvalidProtocolBufferException {
		return new BaseTxnAccessor(signedTxnWrapperBytes, aliasManager);
	}

	@Override
	public PubKeyToSigBytes getPkToSigsFn() {
		return pubKeyToSigBytes;
	}

	@Override
	public Function<byte[], TransactionSignature> getRationalizedPkToCryptoSigFn() {
		throw new UnsupportedOperationException();
	}

	public boolean isTriggered() {
		return isTriggered;
	}

	public void setTriggered(boolean triggered) {
		isTriggered = triggered;
	}

	public void setScheduleRef(ScheduleID scheduleRef) {
		this.scheduleRef = scheduleRef;
	}

	protected EntityNum unaliased(AccountID grpcId) {
		return aliasManager.unaliased(grpcId);
	}

	protected EntityNum unaliased(ContractID grpcId) {
		return EntityIdUtils.unaliased(grpcId, aliasManager);
	}

	@Override
	public void setPayer(AccountID payer) {
		this.payer = payer;
	}

	@Override
	public boolean isTriggeredTxn() {
		return false;
	}

	@Override
	public ScheduleID getScheduleRef() {
		return scheduleRef;
	}

	@Override
	public boolean canTriggerTxn() {
		return false;
	}

	@Override
	public long getOfferedFee() {
		return txn.getTransactionFee();
	}

	@Override
	public AccountID getPayer() {
		return payer;
	}

	@Override
	public TransactionID getTxnId() {
		return txnId;
	}

	@Override
	public HederaFunctionality getFunction() {
		if (function == null) {
			function = functionExtractor.apply(getTxn());
		}
		return function;
	}

	@Override
	public byte[] getMemoUtf8Bytes() {
		return utf8MemoBytes;
	}

	@Override
	public String getMemo() {
		return memo;
	}

	@Override
	public boolean memoHasZeroByte() {
		return memoHasZeroByte;
	}

	@Override
	public byte[] getHash() {
		return hash;
	}

	@Override
	public byte[] getTxnBytes() {
		return txnBytes;
	}

	@Override
	public TransactionBody getTxn() {
		return txn;
	}

	@Override
	public void setNumAutoCreations(final int numAutoCreations) {
		this.numAutoCreations = numAutoCreations;
	}

	@Override
	public int getNumAutoCreations() {
		return numAutoCreations;
	}

	@Override
	public boolean areAutoCreationsCounted() {
		return numAutoCreations != UNKNOWN_NUM_AUTO_CREATIONS;
	}

	@Override
	public void countAutoCreationsWith(final AliasManager aliasManager) {
		final var resolver = new AliasResolver();
		resolver.resolve(txn.getCryptoTransfer(), aliasManager);
		numAutoCreations = resolver.perceivedAutoCreations();
	}

	@Override
	public void setLinkedRefs(final LinkedRefs linkedRefs) {
		this.linkedRefs = linkedRefs;
	}

	@Override
	public LinkedRefs getLinkedRefs() {
		return linkedRefs;
	}

	@Override
	public long getGasLimitForContractTx() {
		return getFunction() == ContractCreate ? getTxn().getContractCreateInstance().getGas() :
				getTxn().getContractCall().getGas();
	}

	@Override
	public Map<String, Object> getSpanMap() {
		return spanMap;
	}

	@Override
	public ExpandHandleSpanMapAccessor getSpanMapAccessor() {
		return SPAN_MAP_ACCESSOR;
	}

	@Override
	public Transaction getSignedTxnWrapper() {
		return signedTxnWrapper;
	}

	private void setBaseUsageMeta() {
		if (function == CryptoTransfer) {
			txnUsageMeta = new BaseTransactionMeta(
					utf8MemoBytes.length,
					txn.getCryptoTransfer().getTransfers().getAccountAmountsCount());
		} else {
			txnUsageMeta = new BaseTransactionMeta(utf8MemoBytes.length, 0);
		}
	}

	@Override
	public byte[] getSignedTxnWrapperBytes() {
		return signedTxnWrapperBytes;
	}

	@Override
	public BaseTransactionMeta baseUsageMeta() {
		return txnUsageMeta;
	}

	@Override
	public int sigMapSize() {
		return sigMapSize;
	}

	@Override
	public int numSigPairs() {
		return numSigPairs;
	}

	@Override
	public void setSigMapSize(final int sigMapSize) {
		this.sigMapSize = sigMapSize;
	}

	@Override
	public void setNumSigPairs(final int numSigPairs) {
		this.numSigPairs = numSigPairs;
	}

	@Override
	public void setExpandedSigStatus(final ResponseCodeEnum expandedSigStatus) {
		this.expandedSigStatus = expandedSigStatus;
	}

	@Override
	public ResponseCodeEnum getExpandedSigStatus() {
		return expandedSigStatus;
	}

	@Override
	public SignatureMap getSigMap() {
		return sigMap;
	}

	// TODO : All the below functions will be removed and moved to their respective accessor types
	@Override
	public SubType getSubType() {
		if (function == CryptoTransfer) {
			return xferUsageMeta.getSubType();
		} else if (function == TokenCreate) {
			return SPAN_MAP_ACCESSOR.getTokenCreateMeta(this).getSubType();
		} else if (function == TokenMint) {
			final var op = getTxn().getTokenMint();
			return op.getMetadataCount() > 0 ? TOKEN_NON_FUNGIBLE_UNIQUE : TOKEN_FUNGIBLE_COMMON;
		} else if (function == TokenBurn) {
			return SPAN_MAP_ACCESSOR.getTokenBurnMeta(this).getSubType();
		} else if (function == TokenAccountWipe) {
			return SPAN_MAP_ACCESSOR.getTokenWipeMeta(this).getSubType();
		}
		return SubType.DEFAULT;
	}

	private void setOpUsageMeta() {
		if (function == CryptoTransfer) {
			setXferUsageMeta();
		} else if (function == ConsensusSubmitMessage) {
			setSubmitUsageMeta();
		} else if (function == TokenFeeScheduleUpdate) {
			setFeeScheduleUpdateMeta();
		} else if (function == TokenCreate) {
			setTokenCreateUsageMeta();
		} else if (function == TokenBurn) {
			setTokenBurnUsageMeta();
		} else if (function == TokenAccountWipe) {
			setTokenWipeUsageMeta();
		} else if (function == TokenFreezeAccount) {
			setTokenFreezeUsageMeta();
		} else if (function == TokenUnfreezeAccount) {
			setTokenUnfreezeUsageMeta();
		} else if (function == TokenPause) {
			setTokenPauseUsageMeta();
		} else if (function == TokenUnpause) {
			setTokenUnpauseUsageMeta();
		} else if (function == CryptoCreate) {
			setCryptoCreateUsageMeta();
		} else if (function == CryptoUpdate) {
			setCryptoUpdateUsageMeta();
		} else if (function == CryptoApproveAllowance) {
			setCryptoApproveUsageMeta();
		} else if (function == CryptoAdjustAllowance) {
			setCryptoAdjustUsageMeta();
		}
	}

	private void setXferUsageMeta() {
		var totalTokensInvolved = 0;
		var totalTokenTransfers = 0;
		var numNftOwnershipChanges = 0;
		final var op = txn.getCryptoTransfer();
		for (var tokenTransfers : op.getTokenTransfersList()) {
			totalTokensInvolved++;
			totalTokenTransfers += tokenTransfers.getTransfersCount();
			numNftOwnershipChanges += tokenTransfers.getNftTransfersCount();
		}
		xferUsageMeta = new CryptoTransferMeta(1, totalTokensInvolved, totalTokenTransfers, numNftOwnershipChanges);
	}

	private void setSubmitUsageMeta() {
		submitMessageMeta = new SubmitMessageMeta(txn.getConsensusSubmitMessage().getMessage().size());
	}

	private void setFeeScheduleUpdateMeta() {
		final var effConsTime = getTxnId().getTransactionValidStart().getSeconds();
		final var op = getTxn().getTokenFeeScheduleUpdate();
		final var reprBytes = TOKEN_OPS_USAGE.bytesNeededToRepr(op.getCustomFeesList());

		final var meta = new FeeScheduleUpdateMeta(effConsTime, reprBytes);
		SPAN_MAP_ACCESSOR.setFeeScheduleUpdateMeta(this, meta);
	}

	private void setTokenCreateUsageMeta() {
		final var tokenCreateMeta = TOKEN_OPS_USAGE_UTILS.tokenCreateUsageFrom(txn);
		SPAN_MAP_ACCESSOR.setTokenCreateMeta(this, tokenCreateMeta);
	}

	private void setTokenBurnUsageMeta() {
		final var tokenBurnMeta = TOKEN_OPS_USAGE_UTILS.tokenBurnUsageFrom(txn);
		SPAN_MAP_ACCESSOR.setTokenBurnMeta(this, tokenBurnMeta);
	}

	private void setTokenWipeUsageMeta() {
		final var tokenWipeMeta = TOKEN_OPS_USAGE_UTILS.tokenWipeUsageFrom(txn);
		SPAN_MAP_ACCESSOR.setTokenWipeMeta(this, tokenWipeMeta);
	}

	private void setTokenFreezeUsageMeta() {
		final var tokenFreezeMeta = TOKEN_OPS_USAGE_UTILS.tokenFreezeUsageFrom();
		SPAN_MAP_ACCESSOR.setTokenFreezeMeta(this, tokenFreezeMeta);
	}

	private void setTokenUnfreezeUsageMeta() {
		final var tokenUnfreezeMeta = TOKEN_OPS_USAGE_UTILS.tokenUnfreezeUsageFrom();
		SPAN_MAP_ACCESSOR.setTokenUnfreezeMeta(this, tokenUnfreezeMeta);
	}

	private void setTokenPauseUsageMeta() {
		final var tokenPauseMeta = TOKEN_OPS_USAGE_UTILS.tokenPauseUsageFrom();
		SPAN_MAP_ACCESSOR.setTokenPauseMeta(this, tokenPauseMeta);
	}

	private void setTokenUnpauseUsageMeta() {
		final var tokenUnpauseMeta = TOKEN_OPS_USAGE_UTILS.tokenUnpauseUsageFrom();
		SPAN_MAP_ACCESSOR.setTokenUnpauseMeta(this, tokenUnpauseMeta);
	}

	private void setCryptoCreateUsageMeta() {
		final var cryptoCreateMeta = new CryptoCreateMeta(txn.getCryptoCreateAccount());
		SPAN_MAP_ACCESSOR.setCryptoCreateMeta(this, cryptoCreateMeta);
	}

	private void setCryptoUpdateUsageMeta() {
		final var cryptoUpdateMeta = new CryptoUpdateMeta(txn.getCryptoUpdateAccount(),
				txn.getTransactionID().getTransactionValidStart().getSeconds());
		SPAN_MAP_ACCESSOR.setCryptoUpdate(this, cryptoUpdateMeta);
	}

	private void setCryptoApproveUsageMeta() {
		final var cryptoApproveMeta = new CryptoApproveAllowanceMeta(txn.getCryptoApproveAllowance(),
				txn.getTransactionID().getTransactionValidStart().getSeconds());
		SPAN_MAP_ACCESSOR.setCryptoApproveMeta(this, cryptoApproveMeta);
	}

	private void setCryptoAdjustUsageMeta() {
		final var cryptoAdjustMeta = new CryptoAdjustAllowanceMeta(txn.getCryptoAdjustAllowance(),
				txn.getTransactionID().getTransactionValidStart().getSeconds());
		SPAN_MAP_ACCESSOR.setCryptoAdjustMeta(this, cryptoAdjustMeta);
	}
}