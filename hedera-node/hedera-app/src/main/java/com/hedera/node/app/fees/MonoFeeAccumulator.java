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

package com.hedera.node.app.fees;

import static com.hederahashgraph.api.proto.java.HederaFunctionality.ConsensusGetTopicInfo;

import com.hedera.node.app.hapi.utils.fee.FeeObject;
import com.hedera.node.app.service.mono.context.primitives.StateView;
import com.hedera.node.app.service.mono.fees.calculation.UsageBasedFeeCalculator;
import com.hedera.node.app.service.mono.fees.calculation.UsagePricesProvider;
import com.hedera.node.app.workflows.dispatcher.ReadableStoreFactory;
import com.hedera.node.app.workflows.query.QueryWorkflow;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.Timestamp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Adapter for {@link UsageBasedFeeCalculator} to be used in {@link QueryWorkflow}. This class is
 * currently calling mono-service code and will be replaced with a new implementation as per design.
 */
@Singleton
public class MonoFeeAccumulator implements FeeAccumulator {
    private final UsageBasedFeeCalculator feeCalculator;
    private final MonoGetTopicInfoUsage getTopicInfoUsage;
    private final UsagePricesProvider resourceCosts;
    private final Supplier<StateView> stateView;

    @Inject
    public MonoFeeAccumulator(
            final UsageBasedFeeCalculator feeCalculator,
            final MonoGetTopicInfoUsage getTopicInfoUsage,
            final UsagePricesProvider resourceCosts,
            final Supplier<StateView> stateView) {
        this.feeCalculator = feeCalculator;
        this.getTopicInfoUsage = getTopicInfoUsage;
        this.resourceCosts = resourceCosts;
        this.stateView = stateView;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public FeeObject computePayment(
            @NonNull final ReadableStoreFactory readableStoreFactory,
            @NonNull final HederaFunctionality functionality,
            @NonNull final Query query,
            @NonNull final Timestamp now) {
        final var usagePrices = resourceCosts.defaultPricesGiven(functionality, now);
        // Special case here because when running with workflows enabled, the underlying
        // states will have PBJ Topic's as keys, not MerkleTopic's; so the mono-service
        // resource estimator would hit a ClassCastException
        if (functionality == ConsensusGetTopicInfo) {
            final var topicStore = readableStoreFactory.createTopicStore();
            final var usage = getTopicInfoUsage.computeUsage(query, topicStore);
            return feeCalculator.computeFromQueryResourceUsage(usage, usagePrices, now);
        }
        return feeCalculator.computePayment(query, usagePrices, stateView.get(), now, new HashMap<>());
    }
}