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
import com.hedera.services.bdd.spec.infrastructure.meta.ContractResources;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hedera.services.bdd.suites.HapiApiSuite;
import com.hedera.services.legacy.core.CommonUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.services.bdd.spec.HapiApiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.HapiPropertySource.asDotDelimitedLongArray;
import static com.hedera.services.bdd.spec.HapiPropertySource.asToken;
import static com.hedera.services.bdd.spec.assertions.SomeFungibleTransfers.changingFungibleBalances;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.infrastructure.meta.ContractResources.BURN_AFTER_NESTED_MINT_ABI;
import static com.hedera.services.bdd.spec.infrastructure.meta.ContractResources.DELEGATE_TRANSFER_CALL_ABI;
import static com.hedera.services.bdd.spec.infrastructure.meta.ContractResources.MINT_TOKEN_ORDINARY_CALL;
import static com.hedera.services.bdd.spec.infrastructure.meta.ContractResources.TRANSFER_NFT_ORDINARY_CALL;
import static com.hedera.services.bdd.spec.keys.KeyShape.DELEGATE_CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.updateLargeFile;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.extractByteCode;
import static com.hedera.services.bdd.suites.token.TokenAssociationSpecs.VANILLA_TOKEN;
import static com.hedera.services.bdd.suites.utils.MiscEETUtils.metadata;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;


public class ContractKeysHTSSuite extends HapiApiSuite {
	private static final Logger log = LogManager.getLogger(ContractKeysHTSSuite.class);
	private static final String TOKEN_TREASURY = "treasury";

	private static final String NFT = "nft";
	private static final String CONTRACT = "theContract";

	private static final String ACCOUNT = "sender";
	private static final String RECEIVER = "receiver";

	private static final String UNIVERSAL_KEY = "multipurpose";

	private static final String DELEGATE_KEY = "Delegate key";

	private static final String OUTER_CONTRACT = "Outer Contract";
	private static final String INNER_CONTRACT = "Inner Contract";

	private static final String TOKEN = "Token";

	private static final KeyShape CONTRACT_KEY_SHAPE = KeyShape.threshOf(1, SIMPLE, KeyShape.CONTRACT);

	public static void main(String... args) {
		new ContractKeysHTSSuite().runSuiteAsync();
	}

	@Override
	public boolean canRunAsync() {
		return true;
	}

	@Override
	protected List<HapiApiSpec> getSpecsInSuite() {
		return allOf(
//				HSCS_KEY_1(),
//				HSCS_KEY_2(),
				HSCS_KEY_6()
//				HSCS_KEY_7()
		);
	}

	List<HapiApiSpec> HSCS_KEY_1() {
		return List.of(
				HSCS_KEY_TRANSFER_NFT(),
				HSCS_KEY_MINT_TOKEN()
		);
	}

	List<HapiApiSpec> HSCS_KEY_2() {
		return List.of();
	}

	List<HapiApiSpec> HSCS_KEY_6() {
		return List.of(
				delegateCallForTransfer()
		);
	}

	List<HapiApiSpec> HSCS_KEY_7() {
		return List.of(
				burn_after_nested_mint()
		);
	}


	private HapiApiSpec delegateCallForTransfer() {
		final AtomicReference<AccountID> accountID = new AtomicReference<>();
		final AtomicReference<TokenID> vanillaTokenTokenID = new AtomicReference<>();
		final AtomicReference<AccountID> receiverID = new AtomicReference<>();
		final var supplyKey = "supplyKey";
		final var DELEGATE_CONTRACT_KEY_SHAPE = KeyShape.threshOf(1, SIMPLE, DELEGATE_CONTRACT, DELEGATE_CONTRACT);


		return defaultHapiSpec("delegateCallForTransfer")
				.given(
						newKeyNamed(supplyKey),
						fileCreate(INNER_CONTRACT).payingWith(GENESIS),
						updateLargeFile(GENESIS, INNER_CONTRACT, extractByteCode(ContractResources.SERVICE_CONTRACT)),
						fileCreate(OUTER_CONTRACT).payingWith(GENESIS),
						updateLargeFile(GENESIS, OUTER_CONTRACT, extractByteCode(ContractResources.DELEGATE_CONTRACT)),
						contractCreate(INNER_CONTRACT)
								.bytecode(INNER_CONTRACT)
								.gas(100_000),
						cryptoCreate(TOKEN_TREASURY),
						tokenCreate(VANILLA_TOKEN)
								.tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
								.supplyKey(supplyKey)
								.treasury(TOKEN_TREASURY)
								.initialSupply(0)
								.exposingCreatedIdTo(id -> vanillaTokenTokenID.set(asToken(id))),
						mintToken(VANILLA_TOKEN, List.of(copyFromUtf8("First!"))),
						cryptoCreate(ACCOUNT)
								.exposingCreatedIdTo(accountID::set),
						cryptoCreate(RECEIVER)
								.exposingCreatedIdTo(receiverID::set),
						tokenAssociate(INNER_CONTRACT, VANILLA_TOKEN),
						tokenAssociate(ACCOUNT, VANILLA_TOKEN),
						tokenAssociate(RECEIVER, VANILLA_TOKEN),
						cryptoTransfer(movingUnique(VANILLA_TOKEN, 1L).between(TOKEN_TREASURY, ACCOUNT))
								.payingWith(GENESIS)
				).when(
						withOpContext(
								(spec, opLog) ->
										allRunFor(
												spec,
												contractCreate(OUTER_CONTRACT, ContractResources.DELEGATE_CONTRACT_CONSTRUCTOR,
														getNestedContractAddress(INNER_CONTRACT, spec))
														.bytecode(OUTER_CONTRACT)
														.gas(100_000),
												tokenAssociate(OUTER_CONTRACT, VANILLA_TOKEN),

												newKeyNamed(DELEGATE_KEY).shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, INNER_CONTRACT, OUTER_CONTRACT))),
												cryptoUpdate(ACCOUNT).key(DELEGATE_KEY),

												contractCall(OUTER_CONTRACT, DELEGATE_TRANSFER_CALL_ABI,
														asAddress(vanillaTokenTokenID.get()), asAddress(accountID.get()),
														asAddress(receiverID.get()), 1L)
														.payingWith(GENESIS)
														.via("delegateTransferCallWithDelegateContractKeyTxn")
														.gas(5_000_000)
										)
						)

				).then(
						childRecordsCheck("delegateTransferCallWithDelegateContractKeyTxn", SUCCESS, recordWith()
								.status(SUCCESS)),
						getAccountBalance(ACCOUNT).hasTokenBalance(VANILLA_TOKEN, 0),
						getAccountBalance(RECEIVER).hasTokenBalance(VANILLA_TOKEN, 1)

				);
	}

	private HapiApiSpec burn_after_nested_mint() {
		final var innerContract = "BurnTokenContract";
		final var outerContract = "NestedBurnContract";
		final var multiKey = "purpose";
		final var revisedKey = KeyShape.threshOf(1, SIMPLE, DELEGATE_CONTRACT, DELEGATE_CONTRACT);
		final String ALICE = "Alice";

		return defaultHapiSpec("burn_after_nested_mint")
				.given(
						newKeyNamed(multiKey),
						cryptoCreate(ALICE).balance(10 * ONE_HUNDRED_HBARS),
						cryptoCreate(TOKEN_TREASURY),
						tokenCreate(TOKEN)
								.tokenType(TokenType.FUNGIBLE_COMMON)
								.initialSupply(50L)
								.supplyKey(multiKey)
								.adminKey(multiKey)
								.treasury(TOKEN_TREASURY),
						fileCreate(innerContract).path(ContractResources.MINT_TOKEN_CONTRACT),
						fileCreate(outerContract).path(ContractResources.NESTED_BURN),
						contractCreate(innerContract)
								.bytecode(innerContract)
								.gas(100_000),
						withOpContext(
								(spec, opLog) ->
										allRunFor(
												spec,
												contractCreate(outerContract, ContractResources.NESTED_BURN_CONSTRUCTOR_ABI,
														getNestedContractAddress(innerContract, spec))
														.payingWith(ALICE)
														.bytecode(outerContract)
														.via("creationTx")
														.gas(28_000))),
						getTxnRecord("creationTx").logged()

				)
				.when(
						withOpContext(
								(spec, opLog) ->
										allRunFor(
												spec,
												newKeyNamed("contractKey").shape(revisedKey.signedWith(sigs(ON, outerContract, innerContract))),
												tokenUpdate(TOKEN)
														.supplyKey("contractKey"),
												contractCall(outerContract, BURN_AFTER_NESTED_MINT_ABI,
														1, asAddress(spec.registry().getTokenID(TOKEN)), new ArrayList<>())
														.payingWith(ALICE)
														.via("burnAfterNestedMint"))),

						childRecordsCheck("burnAfterNestedMint", SUCCESS, recordWith()
										.status(SUCCESS)
										.tokenTransfers(
												changingFungibleBalances()
														.including(TOKEN, TOKEN_TREASURY, 1)
										)
										.newTotalSupply(51),
								recordWith()
										.status(SUCCESS)
										.tokenTransfers(
												changingFungibleBalances()
														.including(TOKEN, TOKEN_TREASURY, -1)
										)
										.newTotalSupply(50)
						)

				)
				.then(
						getAccountBalance(TOKEN_TREASURY).hasTokenBalance(TOKEN, 50)
				);
	}

	private HapiApiSpec HSCS_KEY_MINT_TOKEN() {
		final var theAccount = "anybody";
		final var mintContractByteCode = "mintContractByteCode";
		final var amount = 10L;
		final var fungibleToken = "fungibleToken";
		final var multiKey = "purpose";
		final var theContract = "mintContract";
		final var firstMintTxn = "firstMintTxn";

		final AtomicLong fungibleNum = new AtomicLong();

		return defaultHapiSpec("HSCS_KEY_MINT_TOKEN")
				.given(
						newKeyNamed(multiKey),
						cryptoCreate(theAccount).balance(10 * ONE_HUNDRED_HBARS),
						cryptoCreate(TOKEN_TREASURY),
						fileCreate(mintContractByteCode).payingWith(theAccount),
						updateLargeFile(theAccount, mintContractByteCode, extractByteCode(ContractResources.ORDINARY_CALLS_CONTRACT)),
						tokenCreate(fungibleToken)
								.tokenType(TokenType.FUNGIBLE_COMMON)
								.initialSupply(0)
								.treasury(TOKEN_TREASURY)
								.adminKey(multiKey)
								.supplyKey(multiKey)
								.exposingCreatedIdTo(idLit -> fungibleNum.set(asDotDelimitedLongArray(idLit)[2]))
				).when(
						sourcing(() -> contractCreate(theContract)
								.bytecode(mintContractByteCode).payingWith(theAccount)
								.gas(300_000L))
				).then(
						withOpContext(
								(spec, opLog) ->
										allRunFor(
												spec,
												contractCall(theContract, MINT_TOKEN_ORDINARY_CALL,
														asAddress(spec.registry().getTokenID(fungibleToken)), amount,
														new byte[]{})
														.via(firstMintTxn).payingWith(theAccount)
														.alsoSigningWithFullPrefix(multiKey))),
						getTxnRecord(firstMintTxn).andAllChildRecords().logged(),
						getTokenInfo(fungibleToken).hasTotalSupply(amount),
						getAccountBalance(TOKEN_TREASURY).hasTokenBalance(fungibleToken, amount)
				);
	}

	private HapiApiSpec HSCS_KEY_BURN_TOKEN() {
		final var theAccount = "anybody";
		final var mintContractByteCode = "mintContractByteCode";
		final var amount = 10L;
		final var fungibleToken = "fungibleToken";
		final var multiKey = "purpose";
		final var theContract = "mintContract";
		final var firstMintTxn = "firstMintTxn";

		final AtomicLong fungibleNum = new AtomicLong();

		return defaultHapiSpec("HSCS_KEY_BURN_TOKEN")
				.given(
						newKeyNamed(multiKey),
						cryptoCreate(theAccount).balance(10 * ONE_HUNDRED_HBARS),
						cryptoCreate(TOKEN_TREASURY),
						fileCreate(mintContractByteCode)
								.path(ContractResources.ORDINARY_CALLS_CONTRACT).payingWith(theAccount),
						tokenCreate(fungibleToken)
								.tokenType(TokenType.FUNGIBLE_COMMON)
								.initialSupply(0)
								.treasury(TOKEN_TREASURY)
								.adminKey(multiKey)
								.supplyKey(multiKey)
								.exposingCreatedIdTo(idLit -> fungibleNum.set(asDotDelimitedLongArray(idLit)[2]))
				).when(
						sourcing(() -> contractCreate(theContract)
								.bytecode(mintContractByteCode).payingWith(theAccount)
								.gas(300_000L))
				).then(
						withOpContext(
								(spec, opLog) ->
										allRunFor(
												spec,
												contractCall(theContract, MINT_TOKEN_ORDINARY_CALL,
														asAddress(spec.registry().getTokenID(fungibleToken)), amount,
														new byte[]{})
														.via(firstMintTxn).payingWith(theAccount)
														.alsoSigningWithFullPrefix(multiKey))),
						getTxnRecord(firstMintTxn).andAllChildRecords().logged(),
						getTokenInfo(fungibleToken).hasTotalSupply(amount),
						getAccountBalance(TOKEN_TREASURY).hasTokenBalance(fungibleToken, amount)
				);
	}

	private HapiApiSpec HSCS_KEY_TRANSFER_NFT() {
		return defaultHapiSpec("HSCS_KEY_TRANSFER_NFT")
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
						updateLargeFile(ACCOUNT, "bytecode", extractByteCode(ContractResources.ORDINARY_CALLS_CONTRACT))
				).when(
						withOpContext(
								(spec, opLog) ->
										allRunFor(
												spec,
												contractCreate(CONTRACT)
														.payingWith(ACCOUNT)
														.bytecode("bytecode")
														.via("creationTx")
														.gas(28_000),
												getTxnRecord("creationTx").logged(),
												newKeyNamed("contractKey").shape(CONTRACT_KEY_SHAPE.signedWith(sigs(ON,
														CONTRACT))),
												cryptoUpdate(ACCOUNT).key("contractKey"),
												tokenAssociate(CONTRACT, List.of(NFT)),
												tokenAssociate(RECEIVER, List.of(NFT)),
												cryptoTransfer(TokenMovement.movingUnique(NFT, 1).between(TOKEN_TREASURY, ACCOUNT)),
												contractCall(CONTRACT, TRANSFER_NFT_ORDINARY_CALL,
														asAddress(spec.registry().getTokenID(NFT)),
														asAddress(spec.registry().getAccountID(ACCOUNT)),
														asAddress(spec.registry().getAccountID(RECEIVER)),
														1L
												)
														.fee(ONE_HBAR)
														.hasKnownStatus(SUCCESS)
														.payingWith(GENESIS)
														.gas(48_000)
														.via("distributeTx"),
												getTxnRecord("distributeTx").andAllChildRecords().logged()))
				).then(
						getTokenInfo(NFT).hasTotalSupply(2),
						getAccountInfo(RECEIVER).hasOwnedNfts(1),
						getAccountBalance(RECEIVER).hasTokenBalance(NFT, 1),
						getAccountInfo(ACCOUNT).hasOwnedNfts(0),
						getAccountBalance(ACCOUNT).hasTokenBalance(NFT, 0)

//						childRecordsCheck("distributeTx", SUCCESS, recordWith()
//								.status(SUCCESS)
//								.tokenTransfers(
//										NonFungibleTransfers.changingNFTBalances()
//												.including(NFT, ACCOUNT, RECEIVER, 1L)
//								))
				);
	}

	private HapiApiSpec HSCS_KEY_2_TRANSFER() {
		return defaultHapiSpec("HSCS_KEY_2_TRANSFER")
				.given(
				).when(
				).then(
				);
	}

	@NotNull
	private String getNestedContractAddress(String outerContract, HapiApiSpec spec) {
		return CommonUtils.calculateSolidityAddress(
				(int) spec.registry().getContractId(outerContract).getShardNum(),
				spec.registry().getContractId(outerContract).getRealmNum(),
				spec.registry().getContractId(outerContract).getContractNum());
	}

	@Override
	protected Logger getResultsLogger() {
		return log;
	}
}
