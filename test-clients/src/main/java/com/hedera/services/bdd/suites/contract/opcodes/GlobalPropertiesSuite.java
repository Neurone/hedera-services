/*
 * Copyright (C) 2021-2022 Hedera Hashgraph, LLC
 *
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
 */
package com.hedera.services.bdd.suites.contract.opcodes;

import static com.hedera.services.bdd.spec.HapiApiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.isLiteralResult;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.contract.Utils.parsedToByteString;

import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.spec.HapiSpecSetup;
import com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts;
import com.hedera.services.bdd.suites.HapiApiSuite;
import java.math.BigInteger;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

public class GlobalPropertiesSuite extends HapiApiSuite {

    private static final Logger log = LogManager.getLogger(GlobalPropertiesSuite.class);
    private static final String CONTRACT = "GlobalProperties";

    public static void main(String... args) {
        new GlobalPropertiesSuite().runSuiteAsync();
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }

    @Override
    public List<HapiApiSpec> getSpecsInSuite() {
        return List.of(chainIdWorks(), baseFeeWorks(), coinbaseWorks(), gasLimitWorks());
    }

    private HapiApiSpec chainIdWorks() {
        final var expectedChainID =
                new BigInteger(HapiSpecSetup.getDefaultNodeProps().get("contracts.chainId"));
        return defaultHapiSpec("chainIdWorks")
                .given(uploadInitCode(CONTRACT), contractCreate(CONTRACT))
                .when(contractCall(CONTRACT, "getChainID").via("chainId"))
                .then(
                        getTxnRecord("chainId")
                                .logged()
                                .hasPriority(
                                        recordWith()
                                                .contractCallResult(
                                                        resultWith()
                                                                .resultThruAbi(
                                                                        getABIFor(
                                                                                FUNCTION,
                                                                                "getChainID",
                                                                                CONTRACT),
                                                                        isLiteralResult(
                                                                                new Object[] {
                                                                                    expectedChainID
                                                                                })))),
                        contractCallLocal(CONTRACT, "getChainID")
                                .nodePayment(1_234_567)
                                .has(
                                        ContractFnResultAsserts.resultWith()
                                                .resultThruAbi(
                                                        getABIFor(FUNCTION, "getChainID", CONTRACT),
                                                        ContractFnResultAsserts.isLiteralResult(
                                                                new Object[] {expectedChainID}))));
    }

    private HapiApiSpec baseFeeWorks() {
        final var expectedBaseFee = BigInteger.valueOf(0);
        return defaultHapiSpec("baseFeeWorks")
                .given(uploadInitCode(CONTRACT), contractCreate(CONTRACT))
                .when(contractCall(CONTRACT, "getBaseFee").via("baseFee"))
                .then(
                        getTxnRecord("baseFee")
                                .logged()
                                .hasPriority(
                                        recordWith()
                                                .contractCallResult(
                                                        resultWith()
                                                                .resultThruAbi(
                                                                        getABIFor(
                                                                                FUNCTION,
                                                                                "getBaseFee",
                                                                                CONTRACT),
                                                                        isLiteralResult(
                                                                                new Object[] {
                                                                                    BigInteger
                                                                                            .valueOf(
                                                                                                    0)
                                                                                })))),
                        contractCallLocal(CONTRACT, "getBaseFee")
                                .nodePayment(1_234_567)
                                .has(
                                        ContractFnResultAsserts.resultWith()
                                                .resultThruAbi(
                                                        getABIFor(FUNCTION, "getBaseFee", CONTRACT),
                                                        ContractFnResultAsserts.isLiteralResult(
                                                                new Object[] {expectedBaseFee}))));
    }

    private HapiApiSpec coinbaseWorks() {
        return defaultHapiSpec("coinbaseWorks")
                .given(uploadInitCode(CONTRACT), contractCreate(CONTRACT))
                .when(contractCall(CONTRACT, "getCoinbase").via("coinbase"))
                .then(
                        withOpContext(
                                (spec, opLog) -> {
                                    final var expectedCoinbase =
                                            parsedToByteString(
                                                    DEFAULT_PROPS.fundingAccount().getAccountNum());

                                    final var callLocal =
                                            contractCallLocal(CONTRACT, "getCoinbase")
                                                    .nodePayment(1_234_567)
                                                    .saveResultTo("callLocalCoinbase");
                                    final var callRecord = getTxnRecord("coinbase");

                                    allRunFor(spec, callRecord, callLocal);

                                    final var recordResult =
                                            callRecord.getResponseRecord().getContractCallResult();
                                    final var callLocalResult =
                                            spec.registry().getBytes("callLocalCoinbase");
                                    Assertions.assertEquals(
                                            recordResult.getContractCallResult(), expectedCoinbase);
                                    Assertions.assertArrayEquals(
                                            callLocalResult, expectedCoinbase.toByteArray());
                                }));
    }

    private HapiApiSpec gasLimitWorks() {
        final var gasLimit =
                Long.parseLong(HapiSpecSetup.getDefaultNodeProps().get("contracts.maxGasPerSec"));
        return defaultHapiSpec("gasLimitWorks")
                .given(uploadInitCode(CONTRACT), contractCreate(CONTRACT))
                .when(contractCall(CONTRACT, "getGasLimit").via("gasLimit").gas(gasLimit))
                .then(
                        getTxnRecord("gasLimit")
                                .logged()
                                .hasPriority(
                                        recordWith()
                                                .contractCallResult(
                                                        resultWith()
                                                                .resultThruAbi(
                                                                        getABIFor(
                                                                                FUNCTION,
                                                                                "getGasLimit",
                                                                                CONTRACT),
                                                                        isLiteralResult(
                                                                                new Object[] {
                                                                                    BigInteger
                                                                                            .valueOf(
                                                                                                    gasLimit)
                                                                                })))),
                        contractCallLocal(CONTRACT, "getGasLimit")
                                .gas(gasLimit)
                                .nodePayment(1_234_567)
                                .has(
                                        ContractFnResultAsserts.resultWith()
                                                .resultThruAbi(
                                                        getABIFor(
                                                                FUNCTION, "getGasLimit", CONTRACT),
                                                        ContractFnResultAsserts.isLiteralResult(
                                                                new Object[] {
                                                                    BigInteger.valueOf(gasLimit)
                                                                }))));
    }
}
