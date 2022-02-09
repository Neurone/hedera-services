package com.hedera.services.bdd.suites.contract.precompile;

/*-
 * ‌
 * Hedera Services Test Clients
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

import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.spec.assertions.AccountInfoAsserts;
import com.hedera.services.bdd.spec.assertions.NonFungibleTransfers;
import com.hedera.services.bdd.spec.infrastructure.meta.ContractResources;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hedera.services.bdd.suites.HapiApiSuite;
import com.hedera.services.legacy.core.CommonUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.services.bdd.spec.HapiApiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.HapiPropertySource.asToken;
import static com.hedera.services.bdd.spec.assertions.SomeFungibleTransfers.changingFungibleBalances;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.infrastructure.meta.ContractResources.HBAR_FEE_COLLECTOR_CONSTRUCTOR;
import static com.hedera.services.bdd.spec.infrastructure.meta.ContractResources.HBAR_FEE_COLLECTOR_DISTRIBUTE;
import static com.hedera.services.bdd.spec.infrastructure.meta.ContractResources.TRANSFER_AMOUNT_AND_TOKEN_TRANSFER_TO_ADDRESS;
import static com.hedera.services.bdd.spec.infrastructure.meta.ContractResources.VERSATILE_TRANSFERS_CONSTRUCTOR;
import static com.hedera.services.bdd.spec.infrastructure.meta.ContractResources.VERSATILE_TRANSFERS_DISTRIBUTE;
import static com.hedera.services.bdd.spec.infrastructure.meta.ContractResources.VERSATILE_TRANSFERS_DISTRIBUTE_STATIC_NESTED_CALL;
import static com.hedera.services.bdd.spec.infrastructure.meta.ContractResources.VERSATILE_TRANSFERS_NFT;
import static com.hedera.services.bdd.spec.infrastructure.meta.ContractResources.VERSATILE_TRANSFERS_NFTS;
import static com.hedera.services.bdd.spec.infrastructure.meta.ContractResources.VERSATILE_TRANSFERS_TOKENS;
import static com.hedera.services.bdd.spec.infrastructure.meta.ContractResources.ZENOS_BANK_DEPOSIT_TOKENS;
import static com.hedera.services.bdd.spec.infrastructure.meta.ContractResources.ZENOS_BANK_WITHDRAW_TOKENS;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFee;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.updateLargeFile;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.extractByteCode;
import static com.hedera.services.bdd.suites.utils.MiscEETUtils.metadata;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REVERTED_SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSFERS_NOT_ZERO_SUM_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

public class ContractHTSSuite extends HapiApiSuite {
	private static final Logger log = LogManager.getLogger(ContractHTSSuite.class);

	private static final long GAS_TO_OFFER = 2_000_000L;

	private static final String CONTRACT = "theContract";
	private static final String NESTED = "theNestedContract";

	private static final long TOTAL_SUPPLY = 1_000;
	private static final long AMOUNT_TO_SEND = 10L;
	private static final long CUSTOM_HBAR_FEE_AMOUNT = 100L;
	private static final String TOKEN_TREASURY = "treasury";

	private static final String A_TOKEN = "TokenA";
	private static final String NFT = "nft";

	private static final String ACCOUNT = "sender";
	private static final String FEE_COLLECTOR = "feeCollector";
	private static final String RECEIVER = "receiver";
	private static final String SECOND_RECEIVER = "receiver2";

	private static final String FEE_TOKEN = "feeToken";

	private static final String UNIVERSAL_KEY = "multipurpose";

	public static void main(String... args) {
		new ContractHTSSuite().runSuiteSync();
	}

	@Override
	public boolean canRunAsync() {
		return true;
	}

	@Override
	protected List<HapiApiSpec> getSpecsInSuite() {
		return allOf(
			positiveSpecs(),
			negativeSpecs()
		);
	}

	List<HapiApiSpec> negativeSpecs() {
		return List.of(
				HSCS_PREC_017_rollback_after_insufficient_balance(),
				nonZeroTransfersFail()
		);
	}

	List<HapiApiSpec> positiveSpecs() {
		return List.of(
				distributeMultipleTokens(),
				depositAndWithdrawFungibleTokens(),
				transferNft(),
				transferMultipleNfts(),
				tokenTransferFromFeeCollector(),
				tokenTransferFromFeeCollectorStaticNestedCall(),
				hbarTransferFromFeeCollector()
		);
	}

	private HapiApiSpec HSCS_PREC_017_rollback_after_insufficient_balance() {
		/*-
			Alice has 7 hbars
			TokenWithHbarFee has a custom fee of 4 hbars
			Bob calls contract TransferAmountAndToken.sol trying to transfer both NFTs from Alice's account with 2 calls
			 to the HTS transferNft() precompile
			First transfer is successful
			Second transfer fails because Alice can't afford the custom fee
			The contract reverts because of require(success)
			Verify the fee collector has a 0 hbar balance meaning the first successful transfer was reverted and no
			custom fees were paid.
		 */
		final var alice = "alice";
		final var bob = "bob";
		final var treasuryForToken = "treasuryForToken";
		final var feeCollector = "feeCollector";
		final var supplyKey = "supplyKey";
		final var tokenWithHbarFee = "tokenWithHbarFee";
		final var theContract = "theContract";

		return defaultHapiSpec("HSCS_PREC_017_rollback_after_insufficient_balance")
				.given(
						newKeyNamed(supplyKey),
						cryptoCreate(alice).balance(7 * ONE_HBAR),
						cryptoCreate(bob).balance(ONE_HUNDRED_HBARS),
						cryptoCreate(treasuryForToken).balance(ONE_HUNDRED_HBARS),
						cryptoCreate(feeCollector).balance(0L),
						tokenCreate(tokenWithHbarFee)
								.tokenType(NON_FUNGIBLE_UNIQUE)
								.supplyKey(supplyKey)
								.initialSupply(0L)
								.treasury(treasuryForToken)
								.withCustom(fixedHbarFee(4 * ONE_HBAR, feeCollector)),
						mintToken(tokenWithHbarFee, List.of(copyFromUtf8("First!"))),
						mintToken(tokenWithHbarFee, List.of(copyFromUtf8("Second!"))),
						fileCreate("bytecode").payingWith(bob),
						updateLargeFile(bob, "bytecode",
								extractByteCode(ContractResources.TRANSFER_AMOUNT_AND_TOKEN_CONTRACT)),
						withOpContext(
								(spec, opLog) ->
										allRunFor(
												spec,
												contractCreate(theContract, ContractResources.TRANSFER_AMOUNT_AND_TOKEN_CONSTRUCTOR,
														asAddress(spec.registry().getTokenID(tokenWithHbarFee)))
														.payingWith(bob)
														.bytecode("bytecode")
														.gas(GAS_TO_OFFER))),
						tokenAssociate(alice, tokenWithHbarFee),
						tokenAssociate(bob, tokenWithHbarFee),
						tokenAssociate(theContract, tokenWithHbarFee),
						cryptoTransfer(movingUnique(tokenWithHbarFee, 1L).between(treasuryForToken, alice))
								.payingWith(GENESIS),
						cryptoTransfer(movingUnique(tokenWithHbarFee, 2L).between(treasuryForToken, alice))
								.payingWith(GENESIS),
						getAccountInfo(feeCollector).has(AccountInfoAsserts.accountWith().balance(0L))
				)
				.when(
						withOpContext(
								(spec, opLog) -> allRunFor(
										spec,
										contractCall(theContract, TRANSFER_AMOUNT_AND_TOKEN_TRANSFER_TO_ADDRESS,
												asAddress(spec.registry().getAccountID(alice)),
												asAddress(spec.registry().getAccountID(bob)),
												1L, 2L)
												.payingWith(bob)
												.alsoSigningWithFullPrefix(alice)
												.gas(GAS_TO_OFFER)
												.via("contractCallTxn")
												.hasKnownStatus(CONTRACT_REVERT_EXECUTED)))
				)
				.then(
						childRecordsCheck("contractCallTxn", CONTRACT_REVERT_EXECUTED,
								recordWith()
										.status(REVERTED_SUCCESS),
								recordWith()
										.status(INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE)
						),
						getAccountInfo(feeCollector).has(AccountInfoAsserts.accountWith().balance(0L))
				);
	}

	private HapiApiSpec depositAndWithdrawFungibleTokens() {
		return defaultHapiSpec("depositAndWithdrawFungibleTokens")
				.given(
						newKeyNamed(UNIVERSAL_KEY),
						cryptoCreate(ACCOUNT).balance(10 * ONE_HUNDRED_HBARS),
						cryptoCreate(RECEIVER),
						cryptoCreate(TOKEN_TREASURY),
						tokenCreate(A_TOKEN)
								.tokenType(FUNGIBLE_COMMON)
								.initialSupply(TOTAL_SUPPLY)
								.treasury(TOKEN_TREASURY),
						fileCreate("bytecode").payingWith(ACCOUNT),
						updateLargeFile(ACCOUNT, "bytecode", extractByteCode(ContractResources.ZENOS_BANK_CONTRACT)),
						withOpContext(
								(spec, opLog) ->
										allRunFor(
												spec,
												contractCreate(CONTRACT, ContractResources.ZENOS_BANK_CONSTRUCTOR,
														asAddress(spec.registry().getTokenID(A_TOKEN)))
														.payingWith(ACCOUNT)
														.bytecode("bytecode")
														.via("creationTx")
														.gas(GAS_TO_OFFER))),
						tokenAssociate(ACCOUNT, List.of(A_TOKEN)),
						tokenAssociate(CONTRACT, List.of(A_TOKEN)),
						cryptoTransfer(moving(200, A_TOKEN).between(TOKEN_TREASURY, ACCOUNT))
				).when(
						contractCall(CONTRACT, ZENOS_BANK_DEPOSIT_TOKENS, 50)
								.payingWith(ACCOUNT)
								.gas(GAS_TO_OFFER)
								.via("zeno"),
						contractCall(CONTRACT, ZENOS_BANK_WITHDRAW_TOKENS)
								.payingWith(RECEIVER)
								.alsoSigningWithFullPrefix(CONTRACT)
								.gas(GAS_TO_OFFER)
								.via("receiverTx")
				).then(
						childRecordsCheck("zeno",
								SUCCESS,
								recordWith()
										.status(SUCCESS)
										.tokenTransfers(
												changingFungibleBalances()
														.including(A_TOKEN, ACCOUNT, -50L)
														.including(A_TOKEN, CONTRACT, 50L)
										)),
						childRecordsCheck("receiverTx",
								SUCCESS,
								recordWith()
										.status(SUCCESS),
								recordWith()
										.status(SUCCESS)
										.tokenTransfers(
												changingFungibleBalances()
														.including(A_TOKEN, CONTRACT, -25L)
														.including(A_TOKEN, RECEIVER, 25L)
										))
				);
	}

	private HapiApiSpec distributeMultipleTokens() {
		final var theSecondReceiver = "somebody2";
		return defaultHapiSpec("DistributeMultipleTokens")
				.given(
						newKeyNamed(UNIVERSAL_KEY),
						cryptoCreate(ACCOUNT).balance(10 * ONE_HUNDRED_HBARS),
						cryptoCreate(RECEIVER),
						cryptoCreate(theSecondReceiver),
						cryptoCreate(TOKEN_TREASURY),
						tokenCreate(A_TOKEN)
								.tokenType(TokenType.FUNGIBLE_COMMON)
								.initialSupply(TOTAL_SUPPLY)
								.treasury(TOKEN_TREASURY),
						fileCreate("bytecode").payingWith(ACCOUNT),
						updateLargeFile(ACCOUNT, "bytecode", extractByteCode(ContractResources.VERSATILE_TRANSFERS_CONTRACT)),
						fileCreate("nestedBytecode").payingWith(ACCOUNT),
						updateLargeFile(ACCOUNT, "nestedBytecode",
								extractByteCode(ContractResources.DISTRIBUTOR_CONTRACT)),
						withOpContext(
								(spec, opLog) -> {
									allRunFor(spec, contractCreate(NESTED)
											.payingWith(ACCOUNT)
											.bytecode("nestedBytecode")
											.gas(GAS_TO_OFFER));
									allRunFor(
											spec,
											contractCreate(CONTRACT, VERSATILE_TRANSFERS_CONSTRUCTOR, getNestedContractAddress(
													spec))
													.payingWith(ACCOUNT)
													.bytecode("bytecode")
													.gas(GAS_TO_OFFER));
								}),
						tokenAssociate(ACCOUNT, List.of(A_TOKEN)),
						tokenAssociate(CONTRACT, List.of(A_TOKEN)),
						tokenAssociate(RECEIVER, List.of(A_TOKEN)),
						tokenAssociate(theSecondReceiver, List.of(A_TOKEN)),
						cryptoTransfer(moving(200, A_TOKEN).between(TOKEN_TREASURY, ACCOUNT))
				).when(
						withOpContext(
								(spec, opLog) -> {
									final var sender = asAddress(spec.registry().getAccountID(ACCOUNT));
									final var receiver1 = asAddress(spec.registry().getAccountID(RECEIVER));
									final var receiver2 = asAddress(spec.registry().getAccountID(theSecondReceiver));

									final var accounts = List.of(sender, receiver1, receiver2);
									final var amounts = List.of(-10L, 5L, 5L);

									allRunFor(
											spec,
											contractCall(CONTRACT, VERSATILE_TRANSFERS_TOKENS,
													asAddress(spec.registry().getTokenID(A_TOKEN)),
													accounts.toArray(),
													amounts.toArray()
											)
													.payingWith(ACCOUNT)
													.gas(GAS_TO_OFFER)
													.via("distributeTx"));
								})

				).then(
						childRecordsCheck("distributeTx", SUCCESS, recordWith()
								.status(SUCCESS)
								.tokenTransfers(
										changingFungibleBalances()
												.including(A_TOKEN, ACCOUNT, -10L)
												.including(A_TOKEN, RECEIVER, 5L)
												.including(A_TOKEN, theSecondReceiver, 5L)
								))
				);
	}

	private HapiApiSpec tokenTransferFromFeeCollector() {
		return defaultHapiSpec("TokenTransferFromFeeCollector")
				.given(
						cryptoCreate(ACCOUNT).balance(10 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
						cryptoCreate(FEE_COLLECTOR),
						cryptoCreate(RECEIVER).maxAutomaticTokenAssociations(10),
						cryptoCreate(SECOND_RECEIVER),
						cryptoCreate(TOKEN_TREASURY),

						tokenCreate(FEE_TOKEN)
								.tokenType(FUNGIBLE_COMMON)
								.initialSupply(TOTAL_SUPPLY)
								.treasury(TOKEN_TREASURY),

						tokenAssociate(FEE_COLLECTOR, FEE_TOKEN),

						tokenCreate(A_TOKEN)
								.tokenType(TokenType.FUNGIBLE_COMMON)
								.initialSupply(TOTAL_SUPPLY)
								.treasury(TOKEN_TREASURY)
								.withCustom(fixedHtsFee(100L, FEE_TOKEN, FEE_COLLECTOR)),

						tokenAssociate(ACCOUNT, A_TOKEN),
						tokenAssociate(RECEIVER, A_TOKEN),
						tokenAssociate(SECOND_RECEIVER, A_TOKEN),

						cryptoTransfer(moving(TOTAL_SUPPLY, FEE_TOKEN)
								.between(TOKEN_TREASURY, ACCOUNT)),
						cryptoTransfer(moving(TOTAL_SUPPLY, A_TOKEN)
								.between(TOKEN_TREASURY, ACCOUNT)),

						fileCreate("bytecode").payingWith(ACCOUNT),
						updateLargeFile(ACCOUNT, "bytecode", extractByteCode(ContractResources.VERSATILE_TRANSFERS_CONTRACT)),
						fileCreate("nestedBytecode").payingWith(ACCOUNT),
						updateLargeFile(ACCOUNT, "nestedBytecode",
								extractByteCode(ContractResources.DISTRIBUTOR_CONTRACT)),
						withOpContext(
								(spec, opLog) -> {
									allRunFor(spec, contractCreate(NESTED)
											.payingWith(ACCOUNT)
											.bytecode("nestedBytecode")
											.gas(GAS_TO_OFFER));
									allRunFor(
											spec,
											contractCreate(CONTRACT, VERSATILE_TRANSFERS_CONSTRUCTOR, getNestedContractAddress(
													spec))
													.payingWith(ACCOUNT)
													.bytecode("bytecode")
													.gas(GAS_TO_OFFER));
								})
				).when(
						withOpContext(
								(spec, opLog) -> {
									final var sender = asAddress(spec.registry().getAccountID(ACCOUNT));
									final var receiver1 = asAddress(spec.registry().getAccountID(RECEIVER));
									final var receiver2 = asAddress(spec.registry().getAccountID(SECOND_RECEIVER));

									final var accounts = List.of(sender, receiver1, receiver2);
									final var amounts = List.of(-10L, 5L, 5L);

									/* --- HSCS-PREC-009 --- */
									allRunFor(
											spec,
											contractCall(CONTRACT, VERSATILE_TRANSFERS_DISTRIBUTE,
													asAddress(spec.registry().getTokenID(A_TOKEN)),
													asAddress(spec.registry().getTokenID(FEE_TOKEN)),
													accounts.toArray(),
													amounts.toArray(),
													asAddress(spec.registry().getAccountID(FEE_COLLECTOR))
											)
													.payingWith(ACCOUNT)
													.gas(GAS_TO_OFFER)
													.via("distributeTx")
													.alsoSigningWithFullPrefix(FEE_COLLECTOR)
													.hasKnownStatus(SUCCESS));

									/* --- HSCS-PREC-018 --- */
									allRunFor(
											spec,
											contractCall(CONTRACT, VERSATILE_TRANSFERS_DISTRIBUTE,
													asAddress(spec.registry().getTokenID(A_TOKEN)),
													asAddress(spec.registry().getTokenID(FEE_TOKEN)),
													accounts.toArray(),
													amounts.toArray(),
													asAddress(spec.registry().getAccountID(FEE_COLLECTOR))
											)
													.payingWith(ACCOUNT)
													.gas(GAS_TO_OFFER)
													.via("missingSignatureTx")
													.hasKnownStatus(CONTRACT_REVERT_EXECUTED));

									/* --- HSCS-PREC-023 --- */
									allRunFor(
											spec,
											contractCall(CONTRACT, VERSATILE_TRANSFERS_DISTRIBUTE,
													asAddress(spec.registry().getTokenID(A_TOKEN)),
													asAddress(spec.registry().getTokenID(FEE_TOKEN)),
													accounts.toArray(),
													amounts.toArray(),
													asAddress(spec.registry().getAccountID(RECEIVER))
											)
													.payingWith(ACCOUNT)
													.gas(GAS_TO_OFFER)
													.via("failingChildFrameTx")
													.alsoSigningWithFullPrefix(RECEIVER)
													.hasKnownStatus(CONTRACT_REVERT_EXECUTED));
								})

				).then(
						childRecordsCheck("distributeTx", SUCCESS,
								recordWith()
										.status(SUCCESS)
										.tokenTransfers(
												changingFungibleBalances()
														.including(A_TOKEN, ACCOUNT, -10L)
														.including(A_TOKEN, RECEIVER, 5L)
														.including(A_TOKEN, SECOND_RECEIVER, 5L)
										),
								recordWith()
										.status(SUCCESS)
										.tokenTransfers(
												changingFungibleBalances()
														.including(FEE_TOKEN, FEE_COLLECTOR, -100L)
														.including(FEE_TOKEN, ACCOUNT, 100L)
										)),

						childRecordsCheck("missingSignatureTx", CONTRACT_REVERT_EXECUTED,
								recordWith()
										.status(REVERTED_SUCCESS),
								recordWith()
										.status(INVALID_SIGNATURE)),

						childRecordsCheck("failingChildFrameTx", CONTRACT_REVERT_EXECUTED,
								recordWith()
										.status(REVERTED_SUCCESS),
								recordWith()
										.status(INSUFFICIENT_TOKEN_BALANCE)),

						getAccountBalance(ACCOUNT).hasTokenBalance(FEE_TOKEN, 1000),
						getAccountBalance(FEE_COLLECTOR).hasTokenBalance(FEE_TOKEN, 0)
				);
	}

	private HapiApiSpec tokenTransferFromFeeCollectorStaticNestedCall() {
		return defaultHapiSpec("TokenTransferFromFeeCollectorStaticNestedCall")
				.given(
						cryptoCreate(ACCOUNT).balance(10 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
						cryptoCreate(FEE_COLLECTOR),
						cryptoCreate(RECEIVER).maxAutomaticTokenAssociations(10),
						cryptoCreate(SECOND_RECEIVER),
						cryptoCreate(TOKEN_TREASURY),

						tokenCreate(FEE_TOKEN)
								.tokenType(FUNGIBLE_COMMON)
								.initialSupply(TOTAL_SUPPLY)
								.treasury(TOKEN_TREASURY),

						tokenAssociate(FEE_COLLECTOR, FEE_TOKEN),

						tokenCreate(A_TOKEN)
								.tokenType(TokenType.FUNGIBLE_COMMON)
								.initialSupply(TOTAL_SUPPLY)
								.treasury(TOKEN_TREASURY)
								.withCustom(fixedHtsFee(100L, FEE_TOKEN, FEE_COLLECTOR)),

						tokenAssociate(ACCOUNT, A_TOKEN),
						tokenAssociate(RECEIVER, A_TOKEN),
						tokenAssociate(SECOND_RECEIVER, A_TOKEN),

						cryptoTransfer(moving(TOTAL_SUPPLY, FEE_TOKEN)
								.between(TOKEN_TREASURY, ACCOUNT)),
						cryptoTransfer(moving(TOTAL_SUPPLY, A_TOKEN)
								.between(TOKEN_TREASURY, ACCOUNT)),

						fileCreate("bytecode").payingWith(ACCOUNT),
						updateLargeFile(ACCOUNT, "bytecode", extractByteCode(ContractResources.VERSATILE_TRANSFERS_CONTRACT)),
						fileCreate("nestedBytecode").payingWith(ACCOUNT),
						updateLargeFile(ACCOUNT, "nestedBytecode",
								extractByteCode(ContractResources.DISTRIBUTOR_CONTRACT)),
						withOpContext(
								(spec, opLog) -> {
									allRunFor(spec, contractCreate(NESTED)
											.payingWith(ACCOUNT)
											.bytecode("nestedBytecode")
											.gas(GAS_TO_OFFER));
									allRunFor(
											spec,
											contractCreate(CONTRACT, VERSATILE_TRANSFERS_CONSTRUCTOR, getNestedContractAddress(
													spec))
													.payingWith(ACCOUNT)
													.bytecode("bytecode")
													.gas(GAS_TO_OFFER));
								})
				).when(
						withOpContext(
								(spec, opLog) -> {
									final var sender = asAddress(spec.registry().getAccountID(ACCOUNT));
									final var receiver1 = asAddress(spec.registry().getAccountID(RECEIVER));
									final var receiver2 = asAddress(spec.registry().getAccountID(SECOND_RECEIVER));

									final var accounts = List.of(sender, receiver1, receiver2);
									final var amounts = List.of(-10L, 5L, 5L);

									/* --- HSCS-PREC-009 --- */
									allRunFor(
											spec,
											contractCall(CONTRACT, VERSATILE_TRANSFERS_DISTRIBUTE_STATIC_NESTED_CALL,
													asAddress(spec.registry().getTokenID(A_TOKEN)),
													asAddress(spec.registry().getTokenID(FEE_TOKEN)),
													accounts.toArray(),
													amounts.toArray(),
													asAddress(spec.registry().getAccountID(FEE_COLLECTOR))
											)
													.payingWith(ACCOUNT)
													.gas(GAS_TO_OFFER)
													.via("distributeTx")
													.alsoSigningWithFullPrefix(FEE_COLLECTOR)
													.hasKnownStatus(SUCCESS));

									/* --- HSCS-PREC-018 --- */
									allRunFor(
											spec,
											contractCall(CONTRACT, VERSATILE_TRANSFERS_DISTRIBUTE_STATIC_NESTED_CALL,
													asAddress(spec.registry().getTokenID(A_TOKEN)),
													asAddress(spec.registry().getTokenID(FEE_TOKEN)),
													accounts.toArray(),
													amounts.toArray(),
													asAddress(spec.registry().getAccountID(FEE_COLLECTOR))
											)
													.payingWith(ACCOUNT)
													.gas(GAS_TO_OFFER)
													.via("missingSignatureTx")
													.hasKnownStatus(CONTRACT_REVERT_EXECUTED));

									/* --- HSCS-PREC-023 --- */
									allRunFor(
											spec,
											contractCall(CONTRACT, VERSATILE_TRANSFERS_DISTRIBUTE_STATIC_NESTED_CALL,
													asAddress(spec.registry().getTokenID(A_TOKEN)),
													asAddress(spec.registry().getTokenID(FEE_TOKEN)),
													accounts.toArray(),
													amounts.toArray(),
													asAddress(spec.registry().getAccountID(RECEIVER))
											)
													.payingWith(ACCOUNT)
													.gas(GAS_TO_OFFER)
													.via("failingChildFrameTx")
													.alsoSigningWithFullPrefix(RECEIVER)
													.hasKnownStatus(CONTRACT_REVERT_EXECUTED));
								})

				).then(
						childRecordsCheck("distributeTx", SUCCESS,
								recordWith()
										.status(SUCCESS)
										.tokenTransfers(
												changingFungibleBalances()
														.including(A_TOKEN, ACCOUNT, -10L)
														.including(A_TOKEN, RECEIVER, 5L)
														.including(A_TOKEN, SECOND_RECEIVER, 5L)
										),
								recordWith()
										.status(SUCCESS)
										.tokenTransfers(
												changingFungibleBalances()
														.including(FEE_TOKEN, FEE_COLLECTOR, -100L)
														.including(FEE_TOKEN, ACCOUNT, 100L)
										)),

						childRecordsCheck("missingSignatureTx", CONTRACT_REVERT_EXECUTED,
								recordWith()
										.status(REVERTED_SUCCESS),
								recordWith()
										.status(INVALID_SIGNATURE)),

						childRecordsCheck("failingChildFrameTx", CONTRACT_REVERT_EXECUTED,
								recordWith()
										.status(REVERTED_SUCCESS),
								recordWith()
										.status(INSUFFICIENT_TOKEN_BALANCE)),

						getAccountBalance(ACCOUNT).hasTokenBalance(FEE_TOKEN, 1000),
						getAccountBalance(FEE_COLLECTOR).hasTokenBalance(FEE_TOKEN, 0)
				);
	}

	/* --- HSCS-PREC-009 ---
	* Contract is a custom hbar fee collector
	* Contract that otherwise wouldn't have enough balance for a .transfer of hbars can perform the transfer after
	* collecting the custom hbar fees from a nested token transfer through the HTS precompile
	* */
	private HapiApiSpec hbarTransferFromFeeCollector() {
		final AtomicReference<TokenID> tokenID = new AtomicReference<>();
		final AtomicReference<AccountID> senderAccountID = new AtomicReference<>();
		final AtomicReference<AccountID> tokenReceiverAccountID = new AtomicReference<>();
		final AtomicReference<AccountID> hbarReceiverAccountID = new AtomicReference<>();

		return defaultHapiSpec("HbarTransferFromFeeCollector")
				.given(
						cryptoCreate(ACCOUNT)
								.balance(10 * ONE_HUNDRED_HBARS)
								.exposingCreatedIdTo(senderAccountID::set)
								.maxAutomaticTokenAssociations(10),
						cryptoCreate(RECEIVER)
								.exposingCreatedIdTo(tokenReceiverAccountID::set)
								.maxAutomaticTokenAssociations(10),
						cryptoCreate(SECOND_RECEIVER)
								.exposingCreatedIdTo(hbarReceiverAccountID::set)
								.balance(0L),
						cryptoCreate(TOKEN_TREASURY),
						withOpContext(
								(spec, opLog) -> allRunFor(spec,
										fileCreate("bytecode")
												.payingWith(ACCOUNT),
										updateLargeFile(ACCOUNT, "bytecode",
												extractByteCode(ContractResources.HBAR_FEE_COLLECTOR)),
										fileCreate("nestedBytecode")
												.payingWith(ACCOUNT),
										updateLargeFile(ACCOUNT, "nestedBytecode",
												extractByteCode(ContractResources.NESTED_HTS_TRANSFERRER)),
										contractCreate(NESTED)
												.payingWith(ACCOUNT)
												.bytecode("nestedBytecode")
												.gas(280_000)))
				).when(
						withOpContext(
								(spec, opLog) -> {
									allRunFor(spec,
											contractCreate(CONTRACT,
													HBAR_FEE_COLLECTOR_CONSTRUCTOR, getNestedContractAddress(
															spec))
													.payingWith(ACCOUNT)
													.bytecode("bytecode")
													.gas(280_000),
											tokenCreate(A_TOKEN)
													.tokenType(TokenType.FUNGIBLE_COMMON)
													.initialSupply(TOTAL_SUPPLY)
													.treasury(TOKEN_TREASURY)
													.exposingCreatedIdTo(id -> tokenID.set(asToken(id)))
													.withCustom(fixedHbarFee(CUSTOM_HBAR_FEE_AMOUNT, CONTRACT)),
											cryptoTransfer(moving(TOTAL_SUPPLY, A_TOKEN)
													.between(TOKEN_TREASURY, ACCOUNT)));
									allRunFor(spec, contractCall(CONTRACT, HBAR_FEE_COLLECTOR_DISTRIBUTE,
											asAddress(tokenID.get()),
											asAddress(senderAccountID.get()),
											asAddress(tokenReceiverAccountID.get()),
											asAddress(hbarReceiverAccountID.get()),
											AMOUNT_TO_SEND,
											CUSTOM_HBAR_FEE_AMOUNT)
											.payingWith(ACCOUNT)
											.gas(GAS_TO_OFFER)
											.via("distributeTx"));
								})
				).then(
						childRecordsCheck("distributeTx", SUCCESS,
								recordWith()
										.status(SUCCESS)
										.tokenTransfers(
												changingFungibleBalances()
														.including(A_TOKEN, ACCOUNT, -AMOUNT_TO_SEND)
														.including(A_TOKEN, RECEIVER, AMOUNT_TO_SEND)
										)),
						getAccountBalance(SECOND_RECEIVER).hasTinyBars(CUSTOM_HBAR_FEE_AMOUNT)
				);
	}

	private HapiApiSpec transferNft() {
		return defaultHapiSpec("TransferNft")
				.given(
						newKeyNamed(UNIVERSAL_KEY),
						cryptoCreate(ACCOUNT).balance(10 * ONE_HUNDRED_HBARS),
						cryptoCreate(RECEIVER),
						cryptoCreate(TOKEN_TREASURY),
						tokenCreate(NFT)
								.tokenType(NON_FUNGIBLE_UNIQUE)
								.supplyKey(UNIVERSAL_KEY)
								.supplyType(TokenSupplyType.INFINITE)
								.initialSupply(0)
								.treasury(TOKEN_TREASURY),
						tokenAssociate(ACCOUNT, NFT),
						mintToken(NFT, List.of(metadata("firstMemo"), metadata("secondMemo"))),
						fileCreate("bytecode").payingWith(ACCOUNT),
						updateLargeFile(ACCOUNT, "bytecode", extractByteCode(ContractResources.VERSATILE_TRANSFERS_CONTRACT)),
						fileCreate("nestedBytecode").payingWith(ACCOUNT),
						updateLargeFile(ACCOUNT, "nestedBytecode",
								extractByteCode(ContractResources.DISTRIBUTOR_CONTRACT)),
						withOpContext(
								(spec, opLog) -> {
									allRunFor(spec, contractCreate(NESTED)
											.payingWith(ACCOUNT)
											.bytecode("nestedBytecode")
											.gas(GAS_TO_OFFER));
									allRunFor(
											spec,
											contractCreate(CONTRACT, VERSATILE_TRANSFERS_CONSTRUCTOR, getNestedContractAddress(
													spec))
													.payingWith(ACCOUNT)
													.bytecode("bytecode")
													.gas(GAS_TO_OFFER));
								}),
						tokenAssociate(CONTRACT, List.of(NFT)),
						tokenAssociate(RECEIVER, List.of(NFT)),
						cryptoTransfer(TokenMovement.movingUnique(NFT, 1).between(TOKEN_TREASURY, ACCOUNT))
				).when(
						withOpContext(
								(spec, opLog) -> {
									final var tokenAddress = asAddress(spec.registry().getTokenID(NFT));
									final var sender = asAddress(spec.registry().getAccountID(ACCOUNT));
									final var receiver = asAddress(spec.registry().getAccountID(RECEIVER));

									allRunFor(
											spec,
											contractCall(CONTRACT, VERSATILE_TRANSFERS_NFT,
													tokenAddress,
													sender,
													receiver,
													1L
											)
													.payingWith(ACCOUNT)
													.gas(GAS_TO_OFFER)
													.via("distributeTx"));
								})

				).then(
						getTokenInfo(NFT).hasTotalSupply(2),
						getAccountInfo(RECEIVER).hasOwnedNfts(1),
						getAccountBalance(RECEIVER).hasTokenBalance(NFT, 1),
						getAccountInfo(ACCOUNT).hasOwnedNfts(0),
						getAccountBalance(ACCOUNT).hasTokenBalance(NFT, 0),

						childRecordsCheck("distributeTx", SUCCESS, recordWith()
								.status(SUCCESS)
								.tokenTransfers(
										NonFungibleTransfers.changingNFTBalances()
												.including(NFT, ACCOUNT, RECEIVER, 1L)
								))
				);
	}

	private HapiApiSpec transferMultipleNfts() {
		return defaultHapiSpec("TransferMultipleNfts")
				.given(
						newKeyNamed(UNIVERSAL_KEY),
						cryptoCreate(ACCOUNT).balance(10 * ONE_HUNDRED_HBARS),
						cryptoCreate(RECEIVER),
						cryptoCreate(TOKEN_TREASURY),
						tokenCreate(NFT)
								.tokenType(NON_FUNGIBLE_UNIQUE)
								.supplyKey(UNIVERSAL_KEY)
								.supplyType(TokenSupplyType.INFINITE)
								.initialSupply(0)
								.treasury(TOKEN_TREASURY),
						tokenAssociate(ACCOUNT, NFT),
						mintToken(NFT, List.of(metadata("firstMemo"), metadata("secondMemo"))),
						fileCreate("bytecode").payingWith(ACCOUNT),
						updateLargeFile(ACCOUNT, "bytecode", extractByteCode(ContractResources.VERSATILE_TRANSFERS_CONTRACT)),
						fileCreate("nestedBytecode").payingWith(ACCOUNT),
						updateLargeFile(ACCOUNT, "nestedBytecode",
								extractByteCode(ContractResources.DISTRIBUTOR_CONTRACT)),
						withOpContext(
								(spec, opLog) -> {
									allRunFor(spec, contractCreate(NESTED)
											.payingWith(ACCOUNT)
											.bytecode("nestedBytecode")
											.gas(GAS_TO_OFFER));
									allRunFor(
											spec,
											contractCreate(CONTRACT, VERSATILE_TRANSFERS_CONSTRUCTOR, getNestedContractAddress(
													spec))
													.payingWith(ACCOUNT)
													.bytecode("bytecode")
													.gas(GAS_TO_OFFER));
								}),
						tokenAssociate(CONTRACT, List.of(NFT)),
						tokenAssociate(RECEIVER, List.of(NFT)),
						cryptoTransfer(TokenMovement.movingUnique(NFT, 1, 2).between(TOKEN_TREASURY, ACCOUNT))
				).when(
						withOpContext(
								(spec, opLog) -> {
									final var tokenAddress = asAddress(spec.registry().getTokenID(NFT));
									final var sender = asAddress(spec.registry().getAccountID(ACCOUNT));
									final var receiver = asAddress(spec.registry().getAccountID(RECEIVER));

									final var theSenders = List.of(sender, sender);
									final var theReceivers = List.of(receiver, receiver);
									final var theSerialNumbers = List.of(1L, 2L);

									allRunFor(
											spec,
											contractCall(CONTRACT, VERSATILE_TRANSFERS_NFTS,
													tokenAddress,
													theSenders,
													theReceivers,
													theSerialNumbers
											)
													.payingWith(ACCOUNT)
													.gas(GAS_TO_OFFER)
													.via("distributeTx"));
								})

				).then(
						childRecordsCheck("distributeTx", SUCCESS, recordWith()
								.status(SUCCESS)
								.tokenTransfers(
										NonFungibleTransfers.changingNFTBalances()
												.including(NFT, ACCOUNT, RECEIVER, 1L)
												.including(NFT, ACCOUNT, RECEIVER, 2L)
								)),
						getTokenInfo(NFT).hasTotalSupply(2),
						getAccountInfo(RECEIVER).hasOwnedNfts(2),
						getAccountBalance(RECEIVER).hasTokenBalance(NFT, 2),
						getAccountInfo(ACCOUNT).hasOwnedNfts(0),
						getAccountBalance(ACCOUNT).hasTokenBalance(NFT, 0)
				);
	}

	private HapiApiSpec nonZeroTransfersFail() {
		final var theSecondReceiver = "somebody2";
		return defaultHapiSpec("NonZeroTransfersFail")
				.given(
						newKeyNamed(UNIVERSAL_KEY),
						cryptoCreate(ACCOUNT).balance(10 * ONE_HUNDRED_HBARS),
						cryptoCreate(RECEIVER),
						cryptoCreate(theSecondReceiver),
						cryptoCreate(TOKEN_TREASURY),
						tokenCreate(A_TOKEN)
								.tokenType(TokenType.FUNGIBLE_COMMON)
								.initialSupply(TOTAL_SUPPLY)
								.treasury(TOKEN_TREASURY),
						fileCreate("bytecode").payingWith(ACCOUNT),
						updateLargeFile(ACCOUNT, "bytecode",
								extractByteCode(ContractResources.VERSATILE_TRANSFERS_CONTRACT)),
						fileCreate("nestedBytecode").payingWith(ACCOUNT),
						updateLargeFile(ACCOUNT, "nestedBytecode",
								extractByteCode(ContractResources.DISTRIBUTOR_CONTRACT)),
						withOpContext(
								(spec, opLog) -> {
									allRunFor(spec, contractCreate(NESTED)
											.payingWith(ACCOUNT)
											.bytecode("nestedBytecode")
											.gas(GAS_TO_OFFER));
									allRunFor(
											spec,
											contractCreate(CONTRACT, VERSATILE_TRANSFERS_CONSTRUCTOR,
													getNestedContractAddress(spec))
													.payingWith(ACCOUNT)
													.bytecode("bytecode")
													.gas(GAS_TO_OFFER));
								}),
						tokenAssociate(ACCOUNT, List.of(A_TOKEN)),
						tokenAssociate(CONTRACT, List.of(A_TOKEN)),
						tokenAssociate(RECEIVER, List.of(A_TOKEN)),
						tokenAssociate(theSecondReceiver, List.of(A_TOKEN)),
						cryptoTransfer(moving(200, A_TOKEN).between(TOKEN_TREASURY, ACCOUNT))
				).when(
						withOpContext(
								(spec, opLog) -> {
									final var receiver1 = asAddress(spec.registry().getAccountID(RECEIVER));
									final var receiver2 = asAddress(spec.registry().getAccountID(theSecondReceiver));

									final var accounts = List.of(receiver1, receiver2);
									final var amounts = List.of(5L, 5L);

									allRunFor(
											spec,
											contractCall(CONTRACT, VERSATILE_TRANSFERS_TOKENS,
													asAddress(spec.registry().getTokenID(A_TOKEN)),
													accounts.toArray(),
													amounts.toArray()
											)
													.payingWith(ACCOUNT)
													.gas(GAS_TO_OFFER)
													.hasKnownStatus(CONTRACT_REVERT_EXECUTED)
													.via("distributeTx"));
								})

				).then(
						childRecordsCheck("distributeTx", CONTRACT_REVERT_EXECUTED,
								recordWith().status(TRANSFERS_NOT_ZERO_SUM_FOR_TOKEN))
				);
	}

	private String getNestedContractAddress(HapiApiSpec spec) {
		return CommonUtils.calculateSolidityAddress(
				(int) spec.registry().getContractId(ContractHTSSuite.NESTED).getShardNum(),
				spec.registry().getContractId(ContractHTSSuite.NESTED).getRealmNum(),
				spec.registry().getContractId(ContractHTSSuite.NESTED).getContractNum());
	}

	@Override
	protected Logger getResultsLogger() {
		return log;
	}
}