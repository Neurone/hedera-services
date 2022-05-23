package com.hedera.services.store.contracts.precompile;

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

import com.esaulpaugh.headlong.abi.ABIType;
import com.esaulpaugh.headlong.abi.Function;
import com.esaulpaugh.headlong.abi.Tuple;
import com.esaulpaugh.headlong.abi.TypeFactory;
import com.google.protobuf.ByteString;
import com.hedera.services.legacy.proto.utils.ByteStringUtils;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.store.contracts.WorldLedgers;
import com.hedera.services.store.contracts.precompile.TokenCreateWrapper.FixedFeeWrapper;
import com.hedera.services.store.contracts.precompile.TokenCreateWrapper.FractionalFeeWrapper;
import com.hedera.services.store.contracts.precompile.TokenCreateWrapper.KeyValueWrapper;
import com.hedera.services.store.contracts.precompile.TokenCreateWrapper.RoyaltyFeeWrapper;
import com.hedera.services.store.contracts.precompile.TokenCreateWrapper.TokenKeyWrapper;
import com.hedera.services.store.models.NftId;
import com.hedera.services.utils.EntityIdUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenID;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.UnaryOperator;

import static com.hedera.services.utils.EntityIdUtils.accountIdFromEvmAddress;

@Singleton
public class DecodingFacade {
	private static final int WORD_LENGTH = 32;
	private static final int ADDRESS_BYTES_LENGTH = 20;
	private static final int ADDRESS_SKIP_BYTES_LENGTH = 12;
	private static final int FUNCTION_SELECTOR_BYTES_LENGTH = 4;
	private static final String INT_OUTPUT = "(int)";
	private static final String BOOL_OUTPUT = "(bool)";
	private static final String STRING_OUTPUT = "(string)";
	private static final String ADDRESS_PAIR_RAW_TYPE = "(bytes32,bytes32)";
	public static final String UINT256_RAW_TYPE = "(uint256)";

	private static final List<SyntheticTxnFactory.NftExchange> NO_NFT_EXCHANGES = Collections.emptyList();
	private static final List<SyntheticTxnFactory.FungibleTokenTransfer> NO_FUNGIBLE_TRANSFERS =
			Collections.emptyList();

	private static final Function CRYPTO_TRANSFER_FUNCTION = new Function(
			"cryptoTransfer((address,(address,int64)[],(address,address,int64)[])[])", INT_OUTPUT);
	private static final Bytes CRYPTO_TRANSFER_SELECTOR = Bytes.wrap(CRYPTO_TRANSFER_FUNCTION.selector());
	private static final ABIType<Tuple> CRYPTO_TRANSFER_DECODER = TypeFactory.create(
			"((bytes32,(bytes32,int64)[],(bytes32,bytes32,int64)[])[])");

	private static final Function TRANSFER_TOKENS_FUNCTION =
			new Function("transferTokens(address,address[],int64[])", INT_OUTPUT);
	private static final Bytes TRANSFER_TOKENS_SELECTOR = Bytes.wrap(TRANSFER_TOKENS_FUNCTION.selector());
	private static final ABIType<Tuple> TRANSFER_TOKENS_DECODER = TypeFactory.create("(bytes32,bytes32[],int64[])");

	private static final Function TRANSFER_TOKEN_FUNCTION =
			new Function("transferToken(address,address,address,int64)", INT_OUTPUT);
	private static final Bytes TRANSFER_TOKEN_SELECTOR = Bytes.wrap(TRANSFER_TOKEN_FUNCTION.selector());
	private static final ABIType<Tuple> TRANSFER_TOKEN_DECODER = TypeFactory.create("(bytes32,bytes32,bytes32,int64)");

	private static final Function TRANSFER_NFTS_FUNCTION =
			new Function("transferNFTs(address,address[],address[],int64[])", INT_OUTPUT);
	private static final Bytes TRANSFER_NFTS_SELECTOR = Bytes.wrap(TRANSFER_NFTS_FUNCTION.selector());
	private static final ABIType<Tuple> TRANSFER_NFTS_DECODER = TypeFactory.create(
			"(bytes32,bytes32[],bytes32[],int64[])");

	private static final Function TRANSFER_NFT_FUNCTION =
			new Function("transferNFT(address,address,address,int64)", INT_OUTPUT);
	private static final Bytes TRANSFER_NFT_SELECTOR = Bytes.wrap(TRANSFER_NFT_FUNCTION.selector());
	private static final ABIType<Tuple> TRANSFER_NFT_DECODER = TypeFactory.create("(bytes32,bytes32,bytes32,int64)");

	private static final Function MINT_TOKEN_FUNCTION = new Function("mintToken(address,uint64,bytes[])", INT_OUTPUT);
	private static final Bytes MINT_TOKEN_SELECTOR = Bytes.wrap(MINT_TOKEN_FUNCTION.selector());
	private static final ABIType<Tuple> MINT_TOKEN_DECODER = TypeFactory.create("(bytes32,int64,bytes[])");

	private static final Function BURN_TOKEN_FUNCTION =
			new Function("burnToken(address,uint64,int64[])", INT_OUTPUT);
	private static final Bytes BURN_TOKEN_SELECTOR = Bytes.wrap(BURN_TOKEN_FUNCTION.selector());
	private static final ABIType<Tuple> BURN_TOKEN_DECODER = TypeFactory.create("(bytes32,int64,int64[])");

	private static final Function ASSOCIATE_TOKENS_FUNCTION =
			new Function("associateTokens(address,address[])", INT_OUTPUT);
	private static final Bytes ASSOCIATE_TOKENS_SELECTOR = Bytes.wrap(ASSOCIATE_TOKENS_FUNCTION.selector());
	private static final ABIType<Tuple> ASSOCIATE_TOKENS_DECODER = TypeFactory.create("(bytes32,bytes32[])");

	private static final Function ASSOCIATE_TOKEN_FUNCTION =
			new Function("associateToken(address,address)", INT_OUTPUT);
	private static final Bytes ASSOCIATE_TOKEN_SELECTOR = Bytes.wrap(ASSOCIATE_TOKEN_FUNCTION.selector());
	private static final ABIType<Tuple> ASSOCIATE_TOKEN_DECODER = TypeFactory.create(ADDRESS_PAIR_RAW_TYPE);

	private static final Function DISSOCIATE_TOKENS_FUNCTION =
			new Function("dissociateTokens(address,address[])", INT_OUTPUT);
	private static final Bytes DISSOCIATE_TOKENS_SELECTOR = Bytes.wrap(DISSOCIATE_TOKENS_FUNCTION.selector());
	private static final ABIType<Tuple> DISSOCIATE_TOKENS_DECODER = TypeFactory.create("(bytes32,bytes32[])");

	private static final Function DISSOCIATE_TOKEN_FUNCTION =
			new Function("dissociateToken(address,address)", INT_OUTPUT);
	private static final Bytes DISSOCIATE_TOKEN_SELECTOR = Bytes.wrap(DISSOCIATE_TOKEN_FUNCTION.selector());
	private static final ABIType<Tuple> DISSOCIATE_TOKEN_DECODER = TypeFactory.create(ADDRESS_PAIR_RAW_TYPE);

	private static final Function TOKEN_URI_NFT_FUNCTION =
			new Function("tokenURI(uint256)", STRING_OUTPUT);
	private static final Bytes TOKEN_URI_NFT_SELECTOR = Bytes.wrap(TOKEN_URI_NFT_FUNCTION.selector());
	private static final ABIType<Tuple> TOKEN_URI_NFT_DECODER = TypeFactory.create(UINT256_RAW_TYPE);

	private static final Function BALANCE_OF_TOKEN_FUNCTION =
			new Function("balanceOf(address)", INT_OUTPUT);
	private static final Bytes BALANCE_OF_TOKEN_SELECTOR = Bytes.wrap(BALANCE_OF_TOKEN_FUNCTION.selector());
	private static final ABIType<Tuple> BALANCE_OF_TOKEN_DECODER = TypeFactory.create("(bytes32)");

	private static final Function OWNER_OF_NFT_FUNCTION =
			new Function("ownerOf(uint256)", INT_OUTPUT);
	private static final Bytes OWNER_OF_NFT_SELECTOR = Bytes.wrap(OWNER_OF_NFT_FUNCTION.selector());
	private static final ABIType<Tuple> OWNER_OF_NFT_DECODER = TypeFactory.create(UINT256_RAW_TYPE);

	private static final Function ERC_TRANSFER_FUNCTION =
			new Function("transfer(address,uint256)", BOOL_OUTPUT);
	private static final Bytes ERC_TRANSFER_SELECTOR = Bytes.wrap(ERC_TRANSFER_FUNCTION.selector());
	private static final ABIType<Tuple> ERC_TRANSFER_DECODER = TypeFactory.create("(bytes32,uint256)");

	private static final Function ERC_TRANSFER_FROM_FUNCTION =
			new Function("transferFrom(address,address,uint256)");
	private static final Bytes ERC_TRANSFER_FROM_SELECTOR = Bytes.wrap(ERC_TRANSFER_FROM_FUNCTION.selector());
	private static final ABIType<Tuple> ERC_TRANSFER_FROM_DECODER = TypeFactory.create("(bytes32,bytes32,uint256)");

	/* --- Token Create Structs --- */
	private static final String KEY_VALUE = "(bool,address,bytes,bytes,address)";
	private static final String KEY_VALUE_DECODER = "(bool,bytes32,bytes,bytes,bytes32)";

	private static final String TOKEN_KEY = "(uint256," + KEY_VALUE + ")";
	private static final String TOKEN_KEY_DECODER = "(int32," + KEY_VALUE_DECODER + ")";

	private static final String EXPIRY = "(uint32,address,uint32)";
	private static final String EXPIRY_DECODER = "(int64,bytes32,int64)";

	private static final String FIXED_FEE = "(uint32,address,bool,bool,address)";
	private static final String FIXED_FEE_DECODER = "(int64,bytes32,bool,bool,bytes32)";

	private static final String FRACTIONAL_FEE = "(uint32,uint32,uint32,uint32,bool,address)";
	private static final String FRACTIONAL_FEE_DECODER = "(int64,int64,int64,int64,bool,bytes32)";

	private static final String ROYALTY_FEE = "(uint32,uint32,uint32,address,bool,address)";
	private static final String ROYALTY_FEE_DECODER = "(int64,int64,int64,bytes32,bool,bytes32)";

	private static final String TOKEN_CREATE_STRUCT = "(string,string,address,string,bool,uint32,bool,"
			+ TOKEN_KEY + "[]," + EXPIRY + ")";
	private static final String TOKEN_CREATE_STRUCT_DECODER =
			"(string,string,bytes32,string,bool,int64,bool," + TOKEN_KEY_DECODER + "[]," + EXPIRY_DECODER + ")";

	private static final Function TOKEN_CREATE_FUNGIBLE_FUNCTION =
			new Function("createFungibleToken(" + TOKEN_CREATE_STRUCT + ",uint256,uint256)");
	private static final Bytes TOKEN_CREATE_FUNGIBLE_SELECTOR = Bytes.wrap(TOKEN_CREATE_FUNGIBLE_FUNCTION.selector());
	private static final ABIType<Tuple> TOKEN_CREATE_FUNGIBLE_DECODER =
			TypeFactory.create("(" + TOKEN_CREATE_STRUCT_DECODER + ",uint256,uint256)");

	private static final Function TOKEN_CREATE_NON_FUNGIBLE_FUNCTION =
			new Function("createNonFungibleToken(" + TOKEN_CREATE_STRUCT + ")");
	private static final Bytes TOKEN_CREATE_NON_FUNGIBLE_SELECTOR =
			Bytes.wrap(TOKEN_CREATE_NON_FUNGIBLE_FUNCTION.selector());
	private static final ABIType<Tuple> TOKEN_CREATE_NON_FUNGIBLE_DECODER = TypeFactory.create("(" +
			TOKEN_CREATE_STRUCT_DECODER + ")");

	private static final Function TOKEN_CREATE_FUNGIBLE_WITH_FEES_FUNCTION =
			new Function("createFungibleTokenWithCustomFees(" + TOKEN_CREATE_STRUCT + ",uint256,uint256," +
					FIXED_FEE + "[]," + FRACTIONAL_FEE + "[])");
	private static final Bytes TOKEN_CREATE_FUNGIBLE_WITH_FEES_SELECTOR =
			Bytes.wrap(TOKEN_CREATE_FUNGIBLE_WITH_FEES_FUNCTION.selector());
	private static final ABIType<Tuple> TOKEN_CREATE_FUNGIBLE_WITH_FEES_DECODER =
			TypeFactory.create("(" + TOKEN_CREATE_STRUCT_DECODER + ",uint256,uint256," + FIXED_FEE_DECODER + "[],"
					+ FRACTIONAL_FEE_DECODER + "[])");

	private static final Function TOKEN_CREATE_NON_FUNGIBLE_WITH_FEES_FUNCTION =
			new Function("createNonFungibleTokenWithCustomFees(" + TOKEN_CREATE_STRUCT + "," + FIXED_FEE + "[],"
					+ ROYALTY_FEE + "[])");
	private static final Bytes TOKEN_CREATE_NON_FUNGIBLE_WITH_FEES_SELECTOR =
			Bytes.wrap(TOKEN_CREATE_NON_FUNGIBLE_WITH_FEES_FUNCTION.selector());
	private static final ABIType<Tuple> TOKEN_CREATE_NON_FUNGIBLE_WITH_FEES_DECODER =
			TypeFactory.create("(" + TOKEN_CREATE_STRUCT_DECODER + "," + FIXED_FEE_DECODER + "[]," + ROYALTY_FEE_DECODER
					+ "[])");

	private static final Function TOKEN_ALLOWANCE_FUNCTION =
			new Function("allowance(address,address)", INT_OUTPUT);
	private static final Bytes TOKEN_ALLOWANCE_SELECTOR = Bytes.wrap(TOKEN_ALLOWANCE_FUNCTION.selector());
	private static final ABIType<Tuple> TOKEN_ALLOWANCE_DECODER = TypeFactory.create(ADDRESS_PAIR_RAW_TYPE);

	private static final Function GET_APPROVED_FUNCTION =
			new Function("getApproved(uint256)", INT_OUTPUT);
	private static final Bytes GET_APPROVED_FUNCTION_SELECTOR = Bytes.wrap(GET_APPROVED_FUNCTION.selector());
	private static final ABIType<Tuple> GET_APPROVED_FUNCTION_DECODER = TypeFactory.create(UINT256_RAW_TYPE);

	private static final Function IS_APPROVED_FOR_ALL =
			new Function("isApprovedForAll(address,address)", BOOL_OUTPUT);
	private static final Bytes IS_APPROVED_FOR_ALL_SELECTOR = Bytes.wrap(IS_APPROVED_FOR_ALL.selector());
	private static final ABIType<Tuple> IS_APPROVED_FOR_ALL_DECODER = TypeFactory.create(ADDRESS_PAIR_RAW_TYPE);

	private static final Function SET_APPROVAL_FOR_ALL =
			new Function("setApprovalForAll(address,bool)");
	private static final Bytes SET_APPROVAL_FOR_ALL_SELECTOR = Bytes.wrap(SET_APPROVAL_FOR_ALL.selector());
	private static final ABIType<Tuple> SET_APPROVAL_FOR_ALL_DECODER = TypeFactory.create("(bytes32,bool)");

	private static final Function TOKEN_APPROVE_FUNCTION =
			new Function("approve(address,uint256)", BOOL_OUTPUT);
	private static final Bytes TOKEN_APPROVE_SELECTOR = Bytes.wrap(TOKEN_APPROVE_FUNCTION.selector());
	private static final ABIType<Tuple> TOKEN_APPROVE_DECODER = TypeFactory.create("(bytes32,uint256)");

	@Inject
	public DecodingFacade() {
	}

	public List<TokenTransferWrapper> decodeCryptoTransfer(
			final Bytes input,
			final UnaryOperator<byte[]> aliasResolver
	) {
		final Tuple decodedTuples = decodeFunctionCall(input, CRYPTO_TRANSFER_SELECTOR, CRYPTO_TRANSFER_DECODER);
		final List<TokenTransferWrapper> tokenTransferWrappers = new ArrayList<>();

		for (final var tuple : decodedTuples) {
			for (final var tupleNested : (Tuple[]) tuple) {
				final var tokenType = convertAddressBytesToTokenID(tupleNested.get(0));

				var nftExchanges = NO_NFT_EXCHANGES;
				var fungibleTransfers = NO_FUNGIBLE_TRANSFERS;

				final var abiAdjustments = (Tuple[]) tupleNested.get(1);
				if (abiAdjustments.length > 0) {
					fungibleTransfers = bindFungibleTransfersFrom(tokenType, abiAdjustments, aliasResolver);
				}
				final var abiNftExchanges = (Tuple[]) tupleNested.get(2);
				if (abiNftExchanges.length > 0) {
					nftExchanges = bindNftExchangesFrom(tokenType, abiNftExchanges, aliasResolver);
				}

				tokenTransferWrappers.add(new TokenTransferWrapper(nftExchanges, fungibleTransfers));
			}
		}

		return tokenTransferWrappers;
	}

	public BurnWrapper decodeBurn(final Bytes input) {
		final Tuple decodedArguments = decodeFunctionCall(input, BURN_TOKEN_SELECTOR, BURN_TOKEN_DECODER);

		final var tokenID = convertAddressBytesToTokenID(decodedArguments.get(0));
		final var fungibleAmount = (long) decodedArguments.get(1);
		final var serialNumbers = (long[]) decodedArguments.get(2);

		if (fungibleAmount > 0) {
			return BurnWrapper.forFungible(
					tokenID, fungibleAmount);
		} else {
			return BurnWrapper.forNonFungible(
					tokenID, Arrays.stream(serialNumbers).boxed().toList());
		}
	}

	public BalanceOfWrapper decodeBalanceOf(final Bytes input, final UnaryOperator<byte[]> aliasResolver) {
		final Tuple decodedArguments = decodeFunctionCall(input, BALANCE_OF_TOKEN_SELECTOR, BALANCE_OF_TOKEN_DECODER);

		final var account = convertLeftPaddedAddressToAccountId(decodedArguments.get(0), aliasResolver);

		return new BalanceOfWrapper(account);
	}

	public List<TokenTransferWrapper> decodeERCTransfer(
			final Bytes input,
			final TokenID token,
			final AccountID caller,
			final UnaryOperator<byte[]> aliasResolver
	) {
		final Tuple decodedArguments = decodeFunctionCall(input, ERC_TRANSFER_SELECTOR, ERC_TRANSFER_DECODER);

		final var recipient = convertLeftPaddedAddressToAccountId(decodedArguments.get(0), aliasResolver);
		final var amount = (BigInteger) decodedArguments.get(1);

		final List<SyntheticTxnFactory.FungibleTokenTransfer> fungibleTransfers = new ArrayList<>();
		addSignedAdjustment(fungibleTransfers, token, recipient, amount.longValue());
		addSignedAdjustment(fungibleTransfers, token, caller, -amount.longValue());

		return Collections.singletonList(new TokenTransferWrapper(NO_NFT_EXCHANGES, fungibleTransfers));
	}

	public List<TokenTransferWrapper> decodeERCTransferFrom(
			final Bytes input,
			final TokenID token,
			final boolean isFungible,
			final UnaryOperator<byte[]> aliasResolver,
			final WorldLedgers ledgers,
			final EntityId operatorId
	) {
		final Tuple decodedArguments = decodeFunctionCall(input, ERC_TRANSFER_FROM_SELECTOR, ERC_TRANSFER_FROM_DECODER);

		final var from = convertLeftPaddedAddressToAccountId(decodedArguments.get(0), aliasResolver);
		final var to = convertLeftPaddedAddressToAccountId(decodedArguments.get(1), aliasResolver);
		if (isFungible) {
			final List<SyntheticTxnFactory.FungibleTokenTransfer> fungibleTransfers = new ArrayList<>();
			final var amount = (BigInteger) decodedArguments.get(2);
			addSignedAdjustment(fungibleTransfers, token, to, amount.longValue());
			if (from.equals(operatorId.toGrpcAccountId())) {
				addSignedAdjustment(fungibleTransfers, token, from, -amount.longValue());
			} else {
				addApprovedAdjustment(fungibleTransfers, token, from, -amount.longValue());
			}
			return Collections.singletonList(new TokenTransferWrapper(NO_NFT_EXCHANGES, fungibleTransfers));
		} else {
			final List<SyntheticTxnFactory.NftExchange> nonFungibleTransfers = new ArrayList<>();
			final var serialNo = ((BigInteger) decodedArguments.get(2)).longValue();
			final var ownerId = ledgers.ownerIfPresent(NftId.fromGrpc(token, serialNo));
			if (operatorId.equals(ownerId)) {
				nonFungibleTransfers.add(new SyntheticTxnFactory.NftExchange(serialNo, token, from, to));
			} else {
				nonFungibleTransfers.add(SyntheticTxnFactory.NftExchange.fromApproval(serialNo, token, from, to));
			}
			return Collections.singletonList(new TokenTransferWrapper(nonFungibleTransfers, NO_FUNGIBLE_TRANSFERS));
		}
	}

	public OwnerOfAndTokenURIWrapper decodeOwnerOf(final Bytes input) {
		final Tuple decodedArguments = decodeFunctionCall(input, OWNER_OF_NFT_SELECTOR, OWNER_OF_NFT_DECODER);

		final var tokenId = (BigInteger) decodedArguments.get(0);

		return new OwnerOfAndTokenURIWrapper(tokenId.longValue());
	}

	public OwnerOfAndTokenURIWrapper decodeTokenUriNFT(final Bytes input) {
		final Tuple decodedArguments = decodeFunctionCall(input, TOKEN_URI_NFT_SELECTOR, TOKEN_URI_NFT_DECODER);

		final var tokenId = (BigInteger) decodedArguments.get(0);

		return new OwnerOfAndTokenURIWrapper(tokenId.longValue());
	}

	public GetApprovedWrapper decodeGetApproved(final Bytes input) {
		final Tuple decodedArguments = decodeFunctionCall(
				input, GET_APPROVED_FUNCTION_SELECTOR, GET_APPROVED_FUNCTION_DECODER);
		final var serialNo = (BigInteger) decodedArguments.get(0);
		return new GetApprovedWrapper(serialNo.longValue());
	}

	public TokenAllowanceWrapper decodeTokenAllowance(final Bytes input, final UnaryOperator<byte[]> aliasResolver) {
		final Tuple decodedArguments = decodeFunctionCall(input, TOKEN_ALLOWANCE_SELECTOR, TOKEN_ALLOWANCE_DECODER);

		final var owner = convertLeftPaddedAddressToAccountId(decodedArguments.get(0), aliasResolver);
		final var spender = convertLeftPaddedAddressToAccountId(decodedArguments.get(1), aliasResolver);

		return new TokenAllowanceWrapper(owner, spender);
	}

	public ApproveWrapper decodeTokenApprove(
			final Bytes input,
			final TokenID token,
			final boolean isFungible,
			final UnaryOperator<byte[]> aliasResolver
	) {
		final Tuple decodedArguments = decodeFunctionCall(input, TOKEN_APPROVE_SELECTOR, TOKEN_APPROVE_DECODER);
		final var spender = convertLeftPaddedAddressToAccountId(decodedArguments.get(0), aliasResolver);
		if (isFungible) {
			final var amount = (BigInteger) decodedArguments.get(1);
			return new ApproveWrapper(token, spender, amount, BigInteger.ZERO, isFungible);
		} else {
			final var serialNumber = (BigInteger) decodedArguments.get(1);
			return new ApproveWrapper(token, spender, BigInteger.ZERO, serialNumber, isFungible);
		}
	}

	public SetApprovalForAllWrapper decodeSetApprovalForAll(final Bytes input,
			final UnaryOperator<byte[]> aliasResolver) {
		final Tuple decodedArguments = decodeFunctionCall(input, SET_APPROVAL_FOR_ALL_SELECTOR,
				SET_APPROVAL_FOR_ALL_DECODER);

		final var to = convertLeftPaddedAddressToAccountId(decodedArguments.get(0), aliasResolver);
		final var approved = (boolean) decodedArguments.get(1);

		return new SetApprovalForAllWrapper(to, approved);
	}

	public MintWrapper decodeMint(final Bytes input) {
		final Tuple decodedArguments = decodeFunctionCall(input, MINT_TOKEN_SELECTOR, MINT_TOKEN_DECODER);

		final var tokenID = convertAddressBytesToTokenID(decodedArguments.get(0));
		final var fungibleAmount = (long) decodedArguments.get(1);
		final var metadataList = (byte[][]) decodedArguments.get(2);
		final List<ByteString> wrappedMetadata = new ArrayList<>();
		for (final var meta : metadataList) {
			wrappedMetadata.add(ByteStringUtils.wrapUnsafely(meta));
		}
		if (fungibleAmount > 0) {
			return MintWrapper.forFungible(tokenID, fungibleAmount);
		} else {
			return MintWrapper.forNonFungible(tokenID, wrappedMetadata);
		}
	}

	public List<TokenTransferWrapper> decodeTransferToken(final Bytes input,
			final UnaryOperator<byte[]> aliasResolver) {
		final Tuple decodedArguments = decodeFunctionCall(input, TRANSFER_TOKEN_SELECTOR, TRANSFER_TOKEN_DECODER);

		final var tokenID = convertAddressBytesToTokenID(decodedArguments.get(0));
		final var sender = convertLeftPaddedAddressToAccountId(decodedArguments.get(1), aliasResolver);
		final var receiver = convertLeftPaddedAddressToAccountId(decodedArguments.get(2), aliasResolver);
		final var amount = (long) decodedArguments.get(3);

		return Collections.singletonList(new TokenTransferWrapper(NO_NFT_EXCHANGES,
				List.of(new SyntheticTxnFactory.FungibleTokenTransfer(amount, false, tokenID, sender, receiver))));
	}

	public IsApproveForAllWrapper decodeIsApprovedForAll(final Bytes input, final UnaryOperator<byte[]> aliasResolver) {
		final Tuple decodedArguments = decodeFunctionCall(input, IS_APPROVED_FOR_ALL_SELECTOR,
				IS_APPROVED_FOR_ALL_DECODER);

		final var owner = convertLeftPaddedAddressToAccountId(decodedArguments.get(0), aliasResolver);
		final var operator = convertLeftPaddedAddressToAccountId(decodedArguments.get(1), aliasResolver);

		return new IsApproveForAllWrapper(owner, operator);
	}

	public List<TokenTransferWrapper> decodeTransferTokens(
			final Bytes input,
			final UnaryOperator<byte[]> aliasResolver
	) {
		final Tuple decodedArguments = decodeFunctionCall(input, TRANSFER_TOKENS_SELECTOR, TRANSFER_TOKENS_DECODER);

		final var tokenType = convertAddressBytesToTokenID(decodedArguments.get(0));
		final var accountIDs = decodeAccountIds(decodedArguments.get(1), aliasResolver);
		final var amounts = (long[]) decodedArguments.get(2);

		final List<SyntheticTxnFactory.FungibleTokenTransfer> fungibleTransfers = new ArrayList<>();
		for (int i = 0; i < accountIDs.size(); i++) {
			final var accountID = accountIDs.get(i);
			final var amount = amounts[i];

			addSignedAdjustment(fungibleTransfers, tokenType, accountID, amount);
		}

		return Collections.singletonList(new TokenTransferWrapper(NO_NFT_EXCHANGES, fungibleTransfers));
	}

	public List<TokenTransferWrapper> decodeTransferNFT(final Bytes input, final UnaryOperator<byte[]> aliasResolver) {
		final Tuple decodedArguments = decodeFunctionCall(input, TRANSFER_NFT_SELECTOR, TRANSFER_NFT_DECODER);

		final var tokenID = convertAddressBytesToTokenID(decodedArguments.get(0));
		final var sender = convertLeftPaddedAddressToAccountId(decodedArguments.get(1), aliasResolver);
		final var receiver = convertLeftPaddedAddressToAccountId(decodedArguments.get(2), aliasResolver);
		final var serialNumber = (long) decodedArguments.get(3);

		return Collections.singletonList(
				new TokenTransferWrapper(
						List.of(new SyntheticTxnFactory.NftExchange(serialNumber, tokenID, sender, receiver)),
						NO_FUNGIBLE_TRANSFERS));
	}

	public List<TokenTransferWrapper> decodeTransferNFTs(final Bytes input, final UnaryOperator<byte[]> aliasResolver) {
		final Tuple decodedArguments = decodeFunctionCall(input, TRANSFER_NFTS_SELECTOR, TRANSFER_NFTS_DECODER);

		final var tokenID = convertAddressBytesToTokenID(decodedArguments.get(0));
		final var senders = decodeAccountIds(decodedArguments.get(1), aliasResolver);
		final var receivers = decodeAccountIds(decodedArguments.get(2), aliasResolver);
		final var serialNumbers = ((long[]) decodedArguments.get(3));

		final List<SyntheticTxnFactory.NftExchange> nftExchanges = new ArrayList<>();
		for (var i = 0; i < senders.size(); i++) {
			final var nftExchange = new SyntheticTxnFactory.NftExchange(
					serialNumbers[i], tokenID, senders.get(i), receivers.get(i));
			nftExchanges.add(nftExchange);
		}

		return Collections.singletonList(new TokenTransferWrapper(nftExchanges, NO_FUNGIBLE_TRANSFERS));
	}

	public Association decodeAssociation(final Bytes input, final UnaryOperator<byte[]> aliasResolver) {
		final Tuple decodedArguments = decodeFunctionCall(input, ASSOCIATE_TOKEN_SELECTOR, ASSOCIATE_TOKEN_DECODER);

		final var accountID = convertLeftPaddedAddressToAccountId(decodedArguments.get(0), aliasResolver);
		final var tokenID = convertAddressBytesToTokenID(decodedArguments.get(1));

		return Association.singleAssociation(
				accountID, tokenID);
	}

	public Association decodeMultipleAssociations(final Bytes input, final UnaryOperator<byte[]> aliasResolver) {
		final Tuple decodedArguments = decodeFunctionCall(input, ASSOCIATE_TOKENS_SELECTOR, ASSOCIATE_TOKENS_DECODER);

		final var accountID = convertLeftPaddedAddressToAccountId(decodedArguments.get(0), aliasResolver);
		final var tokenIDs = decodeTokenIDsFromBytesArray(decodedArguments.get(1));

		return Association.multiAssociation(accountID, tokenIDs);
	}

	public Dissociation decodeDissociate(final Bytes input, final UnaryOperator<byte[]> aliasResolver) {
		final Tuple decodedArguments = decodeFunctionCall(input, DISSOCIATE_TOKEN_SELECTOR, DISSOCIATE_TOKEN_DECODER);

		final var accountID = convertLeftPaddedAddressToAccountId(decodedArguments.get(0), aliasResolver);
		final var tokenID = convertAddressBytesToTokenID(decodedArguments.get(1));

		return Dissociation.singleDissociation(accountID, tokenID);
	}

	public Dissociation decodeMultipleDissociations(final Bytes input, final UnaryOperator<byte[]> aliasResolver) {
		final Tuple decodedArguments = decodeFunctionCall(input, DISSOCIATE_TOKENS_SELECTOR, DISSOCIATE_TOKENS_DECODER);

		final var accountID = convertLeftPaddedAddressToAccountId(decodedArguments.get(0), aliasResolver);
		final var tokenIDs = decodeTokenIDsFromBytesArray(decodedArguments.get(1));

		return Dissociation.multiDissociation(accountID, tokenIDs);
	}

	public TokenCreateWrapper decodeFungibleCreate(final Bytes input, final UnaryOperator<byte[]> aliasResolver) {
		final Tuple decodedArguments = decodeFunctionCall(input, TOKEN_CREATE_FUNGIBLE_SELECTOR,
				TOKEN_CREATE_FUNGIBLE_DECODER);

		return decodeTokenCreateWithoutFees(decodedArguments.get(0), true, decodedArguments.get(1),
				decodedArguments.get(2), aliasResolver);
	}

	public TokenCreateWrapper decodeFungibleCreateWithFees(
			final Bytes input,
			final UnaryOperator<byte[]> aliasResolver
	) {
		final Tuple decodedArguments = decodeFunctionCall(input, TOKEN_CREATE_FUNGIBLE_WITH_FEES_SELECTOR,
				TOKEN_CREATE_FUNGIBLE_WITH_FEES_DECODER);

		final var tokenCreateWrapper = decodeTokenCreateWithoutFees(decodedArguments.get(0), true,
				decodedArguments.get(1), decodedArguments.get(2), aliasResolver);
		final var fixedFees = decodeFixedFees(decodedArguments.get(3), aliasResolver);
		final var fractionalFees = decodeFractionalFees(decodedArguments.get(4), aliasResolver);
		tokenCreateWrapper.setFixedFees(fixedFees);
		tokenCreateWrapper.setFractionalFees(fractionalFees);

		return tokenCreateWrapper;
	}

	public TokenCreateWrapper decodeNonFungibleCreate(final Bytes input, final UnaryOperator<byte[]> aliasResolver
	) {
		final Tuple decodedArguments = decodeFunctionCall(input, TOKEN_CREATE_NON_FUNGIBLE_SELECTOR,
				TOKEN_CREATE_NON_FUNGIBLE_DECODER);

		return decodeTokenCreateWithoutFees(decodedArguments.get(0), false, BigInteger.ZERO, BigInteger.ZERO,
				aliasResolver);
	}

	public TokenCreateWrapper decodeNonFungibleCreateWithFees(
			final Bytes input,
			final UnaryOperator<byte[]> aliasResolver
	) {
		final Tuple decodedArguments = decodeFunctionCall(input, TOKEN_CREATE_NON_FUNGIBLE_WITH_FEES_SELECTOR,
				TOKEN_CREATE_NON_FUNGIBLE_WITH_FEES_DECODER);

		final var tokenCreateWrapper = decodeTokenCreateWithoutFees(decodedArguments.get(0), false,
				BigInteger.ZERO, BigInteger.ZERO, aliasResolver);
		final var fixedFees = decodeFixedFees(decodedArguments.get(1), aliasResolver);
		final var royaltyFees = decodeRoyaltyFees(decodedArguments.get(2), aliasResolver);
		tokenCreateWrapper.setFixedFees(fixedFees);
		tokenCreateWrapper.setRoyaltyFees(royaltyFees);

		return tokenCreateWrapper;
	}

	private TokenCreateWrapper decodeTokenCreateWithoutFees(
			final Tuple tokenCreateStruct,
			final boolean isFungible,
			final BigInteger initSupply,
			final BigInteger decimals,
			final UnaryOperator<byte[]> aliasResolver
	) {
		final var tokenName = (String) tokenCreateStruct.get(0);
		final var tokenSymbol = (String) tokenCreateStruct.get(1);
		final var tokenTreasury = convertLeftPaddedAddressToAccountId(tokenCreateStruct.get(2),
				aliasResolver);
		final var memo = (String) tokenCreateStruct.get(3);
		final var isSupplyTypeFinite = (Boolean) tokenCreateStruct.get(4);
		final var maxSupply = (long) tokenCreateStruct.get(5);
		final var isFreezeDefault = (Boolean) tokenCreateStruct.get(6);
		final var tokenKeys = decodeTokenKeys(tokenCreateStruct.get(7), aliasResolver);
		final var tokenExpiry = decodeTokenExpiry(tokenCreateStruct.get(8), aliasResolver);

		return new TokenCreateWrapper(isFungible, tokenName, tokenSymbol, tokenTreasury.getAccountNum() != 0 ?
				tokenTreasury : null, memo, isSupplyTypeFinite, initSupply, decimals, maxSupply, isFreezeDefault,
				tokenKeys, tokenExpiry);
	}

	private List<TokenKeyWrapper> decodeTokenKeys(
			final Tuple[] tokenKeysTuples,
			final UnaryOperator<byte[]> aliasResolver
	) {
		final List<TokenKeyWrapper> tokenKeys = new ArrayList<>(tokenKeysTuples.length);
		for (final var tokenKeyTuple : tokenKeysTuples) {
			final var keyType = (int) tokenKeyTuple.get(0);
			final Tuple keyValueTuple = tokenKeyTuple.get(1);
			final var inheritAccountKey = (Boolean) keyValueTuple.get(0);
			final var contractId = EntityIdUtils.asContract(
					convertLeftPaddedAddressToAccountId(keyValueTuple.get(1), aliasResolver));
			final var ed25519 = (byte[]) keyValueTuple.get(2);
			final var ecdsaSecp256K1 = (byte[]) keyValueTuple.get(3);
			final var delegatableContractId = EntityIdUtils.asContract(
					convertLeftPaddedAddressToAccountId(keyValueTuple.get(4), aliasResolver));
			tokenKeys.add(new TokenKeyWrapper(keyType, new KeyValueWrapper(inheritAccountKey,
					contractId.getContractNum() != 0 ? contractId : null, ed25519,
					ecdsaSecp256K1, delegatableContractId.getContractNum() != 0 ? delegatableContractId : null)));
		}
		return tokenKeys;
	}

	private TokenExpiryWrapper decodeTokenExpiry(final Tuple expiryTuple, final UnaryOperator<byte[]> aliasResolver) {
		final var second = (long) expiryTuple.get(0);
		final var autoRenewAccount = convertLeftPaddedAddressToAccountId(expiryTuple.get(1), aliasResolver);
		final var autoRenewPeriod = (long) expiryTuple.get(2);
		return new TokenExpiryWrapper(second, autoRenewAccount.getAccountNum() == 0 ? null : autoRenewAccount,
				autoRenewPeriod);
	}

	private List<FixedFeeWrapper> decodeFixedFees(
			final Tuple[] fixedFeesTuples,
			final UnaryOperator<byte[]> aliasResolver
	) {
		final List<FixedFeeWrapper> fixedFees = new ArrayList<>(fixedFeesTuples.length);
		for (final var fixedFeeTuple : fixedFeesTuples) {
			final var amount = (long) fixedFeeTuple.get(0);
			final var tokenId = convertAddressBytesToTokenID(fixedFeeTuple.get(1));
			final var useHbarsForPayment = (Boolean) fixedFeeTuple.get(2);
			final var useCurrentTokenForPayment = (Boolean) fixedFeeTuple.get(3);
			final var feeCollector = convertLeftPaddedAddressToAccountId(fixedFeeTuple.get(4), aliasResolver);
			fixedFees.add(new FixedFeeWrapper(amount, tokenId.getTokenNum() != 0 ? tokenId : null, useHbarsForPayment,
					useCurrentTokenForPayment, feeCollector.getAccountNum() != 0 ? feeCollector : null));
		}
		return fixedFees;
	}

	private List<FractionalFeeWrapper> decodeFractionalFees(
			final Tuple[] fractionalFeesTuples,
			final UnaryOperator<byte[]> aliasResolver
	) {
		final List<FractionalFeeWrapper> fractionalFees = new ArrayList<>(fractionalFeesTuples.length);
		for (final var fractionalFeeTuple : fractionalFeesTuples) {
			final var numerator = (long) fractionalFeeTuple.get(0);
			final var denominator = (long) fractionalFeeTuple.get(1);
			final var minimumAmount = (long) fractionalFeeTuple.get(2);
			final var maximumAmount = (long) fractionalFeeTuple.get(3);
			final var netOfTransfers = (Boolean) fractionalFeeTuple.get(4);
			final var feeCollector = convertLeftPaddedAddressToAccountId(
					fractionalFeeTuple.get(5), aliasResolver);
			fractionalFees.add(new FractionalFeeWrapper(numerator, denominator, minimumAmount, maximumAmount,
					netOfTransfers, feeCollector.getAccountNum() != 0 ? feeCollector : null));
		}
		return fractionalFees;
	}

	private List<RoyaltyFeeWrapper> decodeRoyaltyFees(
			final Tuple[] royaltyFeesTuples,
			final UnaryOperator<byte[]> aliasResolver
	) {
		final List<RoyaltyFeeWrapper> decodedRoyaltyFees = new ArrayList<>(royaltyFeesTuples.length);
		for (final var royaltyFeeTuple : royaltyFeesTuples) {
			final var numerator = (long) royaltyFeeTuple.get(0);
			final var denominator = (long) royaltyFeeTuple.get(1);

			// When at least 1 of the following 3 values is different from its default value,
			// we treat it as though the user has tried to specify a fallbackFixedFee
			final var fixedFeeAmount = (long) royaltyFeeTuple.get(2);
			final var fixedFeeTokenId =
					convertAddressBytesToTokenID(royaltyFeeTuple.get(3));
			final var fixedFeeUseHbars = (Boolean) royaltyFeeTuple.get(4);
			FixedFeeWrapper fixedFee = null;
			if (fixedFeeAmount != 0 || fixedFeeTokenId.getTokenNum() != 0 || Boolean.TRUE.equals(fixedFeeUseHbars)) {
				fixedFee = new FixedFeeWrapper(
						fixedFeeAmount,
						fixedFeeTokenId.getTokenNum() != 0 ? fixedFeeTokenId : null,
						fixedFeeUseHbars,
						false,
						null);
			}

			final var feeCollector =
					convertLeftPaddedAddressToAccountId(royaltyFeeTuple.get(5), aliasResolver);
			decodedRoyaltyFees.add(new RoyaltyFeeWrapper(numerator, denominator, fixedFee,
					feeCollector.getAccountNum() != 0 ? feeCollector : null));
		}
		return decodedRoyaltyFees;
	}

	private Tuple decodeFunctionCall(final Bytes input, final Bytes selector, final ABIType<Tuple> decoder) {
		if (!selector.equals(input.slice(0, FUNCTION_SELECTOR_BYTES_LENGTH))) {
			throw new IllegalArgumentException(
					"Selector does not match, expected " + selector
							+ " actual " + input.slice(0, FUNCTION_SELECTOR_BYTES_LENGTH));
		}
		return decoder.decode(input.slice(FUNCTION_SELECTOR_BYTES_LENGTH).toArray());
	}

	private static List<AccountID> decodeAccountIds(
			final byte[][] accountBytesArray,
			final UnaryOperator<byte[]> aliasResolver
	) {
		final List<AccountID> accountIDs = new ArrayList<>();
		for (final var account : accountBytesArray) {
			accountIDs.add(convertLeftPaddedAddressToAccountId(account, aliasResolver));
		}
		return accountIDs;
	}

	private static List<TokenID> decodeTokenIDsFromBytesArray(final byte[][] accountBytesArray) {
		final List<TokenID> accountIDs = new ArrayList<>();
		for (final var account : accountBytesArray) {
			accountIDs.add(convertAddressBytesToTokenID(account));
		}
		return accountIDs;
	}

	private static AccountID convertLeftPaddedAddressToAccountId(
			final byte[] leftPaddedAddress,
			final UnaryOperator<byte[]> aliasResolver
	) {
		final var addressOrAlias = Arrays.copyOfRange(leftPaddedAddress, ADDRESS_SKIP_BYTES_LENGTH, WORD_LENGTH);
		return accountIdFromEvmAddress(aliasResolver.apply(addressOrAlias));
	}

	private static TokenID convertAddressBytesToTokenID(final byte[] addressBytes) {
		final var address = Address.wrap(
				Bytes.wrap(addressBytes).slice(ADDRESS_SKIP_BYTES_LENGTH, ADDRESS_BYTES_LENGTH));
		return EntityIdUtils.tokenIdFromEvmAddress(address.toArray());
	}

	private List<SyntheticTxnFactory.NftExchange> bindNftExchangesFrom(
			final TokenID tokenType,
			final Tuple[] abiExchanges,
			final UnaryOperator<byte[]> aliasResolver
	) {
		final List<SyntheticTxnFactory.NftExchange> nftExchanges = new ArrayList<>();
		for (final var exchange : abiExchanges) {
			final var sender = convertLeftPaddedAddressToAccountId(exchange.get(0), aliasResolver);
			final var receiver = convertLeftPaddedAddressToAccountId(exchange.get(1), aliasResolver);
			final var serialNo = (long) exchange.get(2);
			nftExchanges.add(new SyntheticTxnFactory.NftExchange(serialNo, tokenType, sender, receiver));
		}
		return nftExchanges;
	}

	private List<SyntheticTxnFactory.FungibleTokenTransfer> bindFungibleTransfersFrom(
			final TokenID tokenType,
			final Tuple[] abiTransfers,
			final UnaryOperator<byte[]> aliasResolver
	) {
		final List<SyntheticTxnFactory.FungibleTokenTransfer> fungibleTransfers = new ArrayList<>();
		for (final var transfer : abiTransfers) {
			final AccountID accountID = convertLeftPaddedAddressToAccountId(transfer.get(0), aliasResolver);
			final long amount = transfer.get(1);
			addSignedAdjustment(fungibleTransfers, tokenType, accountID, amount);
		}
		return fungibleTransfers;
	}

	private void addApprovedAdjustment(
			final List<SyntheticTxnFactory.FungibleTokenTransfer> fungibleTransfers,
			final TokenID tokenId,
			final AccountID accountId,
			final long amount
	) {
		fungibleTransfers.add(new SyntheticTxnFactory.FungibleTokenTransfer(
				-amount, true, tokenId, accountId, null));
	}

	private void addSignedAdjustment(
			final List<SyntheticTxnFactory.FungibleTokenTransfer> fungibleTransfers,
			final TokenID tokenType,
			final AccountID accountID,
			final long amount
	) {
		if (amount > 0) {
			fungibleTransfers.add(new SyntheticTxnFactory.FungibleTokenTransfer(
					amount, false, tokenType, null, accountID));
		} else {
			fungibleTransfers.add(new SyntheticTxnFactory.FungibleTokenTransfer(
					-amount, false, tokenType, accountID, null));
		}
	}
}