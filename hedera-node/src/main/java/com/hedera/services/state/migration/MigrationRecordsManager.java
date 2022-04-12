package com.hedera.services.state.migration;

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

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.ByteString;
import com.hedera.services.context.SideEffectsTracker;
import com.hedera.services.ledger.SigImpactHistorian;
import com.hedera.services.records.RecordsHistorian;
import com.hedera.services.state.EntityCreator;
import com.hedera.services.state.merkle.MerkleNetworkContext;
import com.hedera.services.utils.EntityNum;
import com.hederahashgraph.api.proto.java.CryptoCreateTransactionBody;
import com.hederahashgraph.api.proto.java.Duration;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import com.hederahashgraph.api.proto.java.TransactionBody;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Instant;
import java.util.function.Supplier;

import static com.hedera.services.records.TxnAwareRecordsHistorian.DEFAULT_SOURCE_ID;
import static com.hedera.services.state.EntityCreator.EMPTY_MEMO;
import static com.hedera.services.state.EntityCreator.NO_CUSTOM_FEES;
import static com.hedera.services.state.initialization.BackedSystemAccountsCreator.FUNDING_ACCOUNT_EXPIRY;
import static com.hedera.services.state.initialization.BackedSystemAccountsCreator.STAKING_FUND_ACCOUNTS;

/**
 * Responsible for externalizing any state changes that happened during migration via child records,
 * and then marking the work done via {@link MerkleNetworkContext#markMigrationRecordsStreamed()}.
 *
 * For example, in release v0.24.1 we created two new accounts {@code 0.0.800} and {@code 0.0.801} to
 * receive staking reward funds. Without synthetic {@code CryptoCreate} records in the record stream,
 * mirror nodes wouldn't know about these new staking accounts. (Note on a network reset, we will <i>also</i>
 * stream these two synthetic creations for mirror node consumption.)
 */
@Singleton
public class MigrationRecordsManager {
	private static final Logger log = LogManager.getLogger(MigrationRecordsManager.class);

	private static final Key immutableKey = Key.newBuilder().setKeyList(KeyList.getDefaultInstance()).build();
	private static final String MEMO = "Release 0.24.1 migration record";

	private final EntityCreator creator;
	private final SigImpactHistorian sigImpactHistorian;
	private final RecordsHistorian recordsHistorian;
	private final Supplier<MerkleNetworkContext> networkCtx;

	private Supplier<SideEffectsTracker> sideEffectsFactory = SideEffectsTracker::new;

	@Inject
	public MigrationRecordsManager(
			final EntityCreator creator,
			final SigImpactHistorian sigImpactHistorian,
			final RecordsHistorian recordsHistorian,
			final Supplier<MerkleNetworkContext> networkCtx
	) {
		this.sigImpactHistorian = sigImpactHistorian;
		this.recordsHistorian = recordsHistorian;
		this.networkCtx = networkCtx;
		this.creator = creator;
	}

	/**
	 * If appropriate, publish the migration records for this upgrade. Only needs to be called
	 * once per restart, but that call must be made from {@code handleTransaction} inside an
	 * active {@link com.hedera.services.context.TransactionContext} (because the record running
	 * hash is in state).
	 */
	public void publishMigrationRecords(final Instant now) {
		final var curNetworkCtx = networkCtx.get();
		if (curNetworkCtx.areMigrationRecordsStreamed()) {
			return;
		}

		// After release 0.24.1, we publish creation records for 0.0.800 and 0.0.801 _only_ on a network reset
		if (curNetworkCtx.consensusTimeOfLastHandledTxn() == null) {
			final var implicitAutoRenewPeriod = FUNDING_ACCOUNT_EXPIRY - now.getEpochSecond();
			STAKING_FUND_ACCOUNTS.forEach(num -> publishForStakingFund(num, implicitAutoRenewPeriod));
		}

		curNetworkCtx.markMigrationRecordsStreamed();
	}

	private void publishForStakingFund(final EntityNum num, final long autoRenewPeriod) {
		final var tracker = sideEffectsFactory.get();
		tracker.trackAutoCreation(num.toGrpcAccountId(), ByteString.EMPTY);
		final var synthBody = synthStakingFundCreate(autoRenewPeriod);
		final var synthRecord = creator.createSuccessfulSyntheticRecord(NO_CUSTOM_FEES, tracker, MEMO);
		recordsHistorian.trackPrecedingChildRecord(DEFAULT_SOURCE_ID, synthBody, synthRecord);
		sigImpactHistorian.markEntityChanged(num.longValue());
		log.info("Published synthetic CryptoCreate for staking fund account 0.0.{}", num.longValue());
	}

	private TransactionBody.Builder synthStakingFundCreate(final long autoRenewPeriod) {
		final var txnBody = CryptoCreateTransactionBody.newBuilder()
				.setKey(immutableKey)
				.setMemo(EMPTY_MEMO)
				.setInitialBalance(0)
				.setAutoRenewPeriod(Duration.newBuilder().setSeconds(autoRenewPeriod))
				.build();
		return TransactionBody.newBuilder().setCryptoCreateAccount(txnBody);
	}

	@VisibleForTesting
	void setSideEffectsFactory(Supplier<SideEffectsTracker> sideEffectsFactory) {
		this.sideEffectsFactory = sideEffectsFactory;
	}
}
