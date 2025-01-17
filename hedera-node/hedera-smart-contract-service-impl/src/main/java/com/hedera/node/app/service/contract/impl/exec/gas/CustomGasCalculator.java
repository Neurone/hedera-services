/*
 * Copyright (C) 2023 Hedera Hashgraph, LLC
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

package com.hedera.node.app.service.contract.impl.exec.gas;

import com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.gascalculator.LondonGasCalculator;

/**
 * TODO - Refactor as a transaction-scope {@link com.hedera.node.app.service.mono.contracts.gascalculator.GasCalculatorHederaV22}
 * that gets its price and exchange rate from the {@link HederaOperations}.
 */
@Singleton
@SuppressWarnings("java:S110") // suppress the warning that the class inheritance shouldn't be too deep
public class CustomGasCalculator extends LondonGasCalculator {
    @Inject
    public CustomGasCalculator() {
        // Dagger2
    }

    @Override
    public long transactionIntrinsicGasCost(final Bytes payload, final boolean isContractCreate) {
        return 0L;
    }

    @Override
    public long codeDepositGasCost(final int codeSize) {
        return 0L;
    }
}
