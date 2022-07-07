/*
 * Copyright (C) 2022 Hedera Hashgraph, LLC
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
package com.hedera.services.bdd.suites.crypto.staking;

import static com.hedera.services.bdd.spec.HapiApiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.ContractInfoAsserts.contractWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.suites.records.ContractRecordsSanityCheckSuite.PAYABLE_CONTRACT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_STAKING_ID;

import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.suites.HapiApiSuite;
import java.util.List;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class StakingSuite extends HapiApiSuite {
    private static final Logger log = LogManager.getLogger(StakingSuite.class);
    public static final String END_OF_STAKING_PERIOD_CALCULATIONS_MEMO =
            "End of staking period calculation record";
    private static final long ONE_STAKING_PERIOD = 60_000L;
    private static final long BUFFER = 10_000L;
    private static final long stakingRewardRate = 100_000_000_000L;
    private static final String alice = "alice";
    private static final String bob = "bob";
    private static final String carol = "carol";
    private static final long SLEEP_MS = ONE_STAKING_PERIOD + BUFFER;

    public static void main(String... args) {
        new StakingSuite().runSuiteSync();
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }

    @Override
    public boolean canRunConcurrent() {
        return false;
    }

    @Override
    public List<HapiApiSpec> getSpecsInSuite() {
        return List.of(
                new HapiApiSpec[] {
                    // Need to run each separately
                    rewardsWorkAsExpected(),
                    rewardPaymentsNotRepeatedInSamePeriod(),
                    getInfoQueriesReturnsPendingRewards(),
                    secondOrderRewardSituationsWork()
                    //						enabledRewards(),
                    //						previewnetPlannedTest(),
                    //						sendToCarol(),
                    //						endOfStakingPeriodRecTest(),
                    //						rewardsOfDeletedAreRedirectedToBeneficiary(),
                });
    }

    private HapiApiSpec secondOrderRewardSituationsWork() {
        final long totalStakeStartCase1 = 3 * ONE_HUNDRED_HBARS;
        final long expectedRewardRate =
                Math.max(0, Math.min(10 * ONE_HBAR, stakingRewardRate)); // should be 10 * ONE_HBAR;
        final long rewardSumHistoryCase1 =
                expectedRewardRate
                        / (totalStakeStartCase1 / TINY_PARTS_PER_WHOLE); // should be 333333333
        final long alicePendingRewardsCase1 =
                rewardSumHistoryCase1 * (2 * ONE_HUNDRED_HBARS / TINY_PARTS_PER_WHOLE);
        final long bobPendingRewardsCase1 =
                rewardSumHistoryCase1 * (ONE_HUNDRED_HBARS / TINY_PARTS_PER_WHOLE);

        return defaultHapiSpec("rewardsWorkAsExpected")
                .given(
                        overriding("staking.startThreshold", "" + 10 * ONE_HBAR),
                        overriding("staking.rewardRate", "" + stakingRewardRate),
                        cryptoTransfer(tinyBarsFromTo(GENESIS, STAKING_REWARD, ONE_MILLION_HBARS)))
                .when(
                        cryptoCreate(alice).stakedNodeId(0).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(bob).stakedNodeId(0).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(carol).stakedAccountId(alice).balance(ONE_HUNDRED_HBARS),
                        sleepFor(SLEEP_MS))
                .then(
                        /* --- paid_rewards 0 for first period --- */
                        cryptoTransfer(tinyBarsFromTo(bob, alice, ONE_HBAR)).via("firstTransfer"),
                        getTxnRecord("firstTransfer")
                                .andAllChildRecords()
                                .stakingFeeExempted()
                                .hasChildRecordCount(1)
                                .hasChildRecords(
                                        recordWith().memo(END_OF_STAKING_PERIOD_CALCULATIONS_MEMO))
                                .hasPaidStakingRewards(List.of()),

                        /* --- second period reward eligible --- */
                        sleepFor(SLEEP_MS),
                        cryptoUpdate(carol)
                                .newStakedAccountId(bob)
                                .via("secondOrderRewardSituation"),
                        getTxnRecord("secondOrderRewardSituation")
                                .andAllChildRecords()
                                .hasChildRecordCount(1)
                                .hasStakingFeesPaid()
                                .hasChildRecords(
                                        recordWith().memo(END_OF_STAKING_PERIOD_CALCULATIONS_MEMO))
                                .hasPaidStakingRewards(
                                        List.of(
                                                Pair.of(alice, alicePendingRewardsCase1),
                                                Pair.of(bob, bobPendingRewardsCase1)))
                                .logged());
    }

    private HapiApiSpec getInfoQueriesReturnsPendingRewards() {
        final long expectedTotalStakedRewardStart = ONE_HUNDRED_HBARS + ONE_HUNDRED_HBARS;
        final long accountTotalStake = ONE_HUNDRED_HBARS;
        final long expectedRewardRate =
                Math.max(0, Math.min(10 * ONE_HBAR, stakingRewardRate)); // should be 10 * ONE_HBAR;
        final long expectedRewardSumHistory =
                expectedRewardRate
                        / (expectedTotalStakedRewardStart
                                / TINY_PARTS_PER_WHOLE); // should be 500_000_000L
        final long expectedPendingReward =
                expectedRewardSumHistory * (accountTotalStake / TINY_PARTS_PER_WHOLE); //
        // should be 500_000_000L

        return defaultHapiSpec("getInfoQueriesReturnsPendingRewards")
                .given(
                        overriding("staking.startThreshold", "" + 10 * ONE_HBAR),
                        overriding("staking.rewardRate", "" + stakingRewardRate),
                        cryptoTransfer(tinyBarsFromTo(GENESIS, STAKING_REWARD, 10 * ONE_HBAR)))
                .when(
                        cryptoCreate(alice).stakedNodeId(0).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(bob).balance(ONE_HUNDRED_HBARS),
                        uploadInitCode(PAYABLE_CONTRACT),
                        contractCreate(PAYABLE_CONTRACT)
                                .stakedNodeId(0L)
                                .balance(ONE_HUNDRED_HBARS),
                        sleepFor(SLEEP_MS))
                .then(
                        /* --- staking will be activated, child record is generated at end of staking period --- */
                        cryptoTransfer(tinyBarsFromTo(GENESIS, bob, ONE_HBAR)).via("firstTxn"),
                        getTxnRecord("firstTxn")
                                .andAllChildRecords()
                                .hasChildRecordCount(1)
                                .hasChildRecords(
                                        recordWith().memo(END_OF_STAKING_PERIOD_CALCULATIONS_MEMO))
                                .hasPaidStakingRewards(List.of()),
                        sleepFor(SLEEP_MS),
                        cryptoTransfer(tinyBarsFromTo(GENESIS, bob, ONE_HBAR)),

                        /* --- waited enough and account and contract should be eligible for rewards */
                        getAccountInfo(alice)
                                .has(
                                        accountWith()
                                                .stakedNodeId(0L)
                                                .pendingRewards(expectedPendingReward)),
                        getContractInfo(PAYABLE_CONTRACT)
                                .has(
                                        contractWith()
                                                .stakedNodeId(0L)
                                                .pendingRewards(expectedPendingReward)),

                        /* -- trigger a txn and see if pays expected reward */
                        cryptoTransfer(tinyBarsFromTo(bob, alice, ONE_HBAR))
                                .payingWith(bob)
                                .via("rewardTxn"),
                        getTxnRecord("rewardTxn")
                                .andAllChildRecords()
                                .hasChildRecordCount(0)
                                .hasPaidStakingRewards(
                                        List.of(Pair.of(alice, expectedPendingReward))),
                        contractCall(PAYABLE_CONTRACT, "deposit", 1_000L)
                                .payingWith(bob)
                                .sending(1_000L)
                                .via("contractRewardTxn"),
                        getTxnRecord("contractRewardTxn")
                                .andAllChildRecords()
                                .hasChildRecordCount(0)
                                .hasPaidStakingRewards(
                                        List.of(Pair.of(PAYABLE_CONTRACT, expectedPendingReward))));
    }

    private HapiApiSpec rewardPaymentsNotRepeatedInSamePeriod() {
        return defaultHapiSpec("rewardPaymentsNotRepeatedInSamePeriod")
                .given(
                        overriding("staking.startThreshold", "" + 10 * ONE_HBAR),
                        overriding("staking.rewardRate", "" + stakingRewardRate),
                        cryptoTransfer(tinyBarsFromTo(GENESIS, STAKING_REWARD, 10 * ONE_HBAR)))
                .when(
                        cryptoCreate(alice).stakedNodeId(0).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(bob).balance(ONE_HUNDRED_HBARS),
                        uploadInitCode(PAYABLE_CONTRACT),
                        contractCreate(PAYABLE_CONTRACT)
                                .stakedNodeId(0L)
                                .balance(ONE_HUNDRED_HBARS),
                        sleepFor(SLEEP_MS))
                .then(
                        /* --- staking will be activated in the previous suite, child record is generated at end of
                        staking period. But
                        since rewardsSunHistory will be 0 for the first staking period after rewards are activated ,
                        paid_rewards will be 0 --- */
                        cryptoTransfer(tinyBarsFromTo(bob, alice, ONE_HBAR)).via("firstTxn"),
                        getTxnRecord("firstTxn")
                                .andAllChildRecords()
                                .hasChildRecordCount(1)
                                .hasChildRecords(
                                        recordWith().memo(END_OF_STAKING_PERIOD_CALCULATIONS_MEMO))
                                .hasPaidStakingRewards(List.of()),

                        /* should receive reward */
                        sleepFor(SLEEP_MS),
                        contractUpdate(PAYABLE_CONTRACT)
                                .newDeclinedReward(true)
                                .via("acceptsReward"),
                        getTxnRecord("acceptsReward")
                                .logged()
                                .andAllChildRecords()
                                .hasChildRecordCount(1)
                                .hasChildRecords(
                                        recordWith().memo(END_OF_STAKING_PERIOD_CALCULATIONS_MEMO))
                                .hasPaidStakingRewards(
                                        List.of(Pair.of(PAYABLE_CONTRACT, 500000000L))),
                        contractUpdate(PAYABLE_CONTRACT)
                                .newStakedNodeId(1L)
                                .hasPrecheck(INVALID_STAKING_ID),
                        contractUpdate(PAYABLE_CONTRACT)
                                .newStakedAccountId(bob)
                                .via("samePeriodTxn"),
                        getTxnRecord("samePeriodTxn")
                                .andAllChildRecords()
                                .hasChildRecordCount(0)
                                .hasPaidStakingRewards(List.of()),

                        /* --- next period, so child record is generated at end of staking period.
                        Since rewardsSumHistory is updated during the previous staking period after rewards are
                        activated ,paid_rewards will be non-empty in this record --- */
                        sleepFor(SLEEP_MS),
                        cryptoTransfer(tinyBarsFromTo(bob, alice, ONE_HBAR))
                                .payingWith(bob)
                                .via("firstTransfer"),
                        getTxnRecord("firstTransfer")
                                .andAllChildRecords()
                                .hasChildRecordCount(1)
                                .hasStakingFeesPaid()
                                .hasChildRecords(
                                        recordWith().memo(END_OF_STAKING_PERIOD_CALCULATIONS_MEMO))
                                .hasPaidStakingRewards(List.of(Pair.of(alice, 500000000L)))
                                .logged(),
                        /* Within the same period rewards are not awarded twice */
                        cryptoTransfer(tinyBarsFromTo(bob, alice, ONE_HBAR))
                                .payingWith(bob)
                                .via("samePeriodTransfer"),
                        getTxnRecord("samePeriodTransfer")
                                .andAllChildRecords()
                                .hasChildRecordCount(0)
                                .hasStakingFeesPaid()
                                .hasPaidStakingRewards(List.of())
                                .logged(),
                        cryptoUpdate(alice).newStakedAccountId(bob).via("samePeriodUpdate"),
                        getTxnRecord("samePeriodUpdate")
                                .logged()
                                .andAllChildRecords()
                                .hasChildRecordCount(0)
                                .stakingFeeExempted()
                                .hasPaidStakingRewards(List.of()));
    }

    private HapiApiSpec rewardsWorkAsExpected() {
        final long expectedTotalStakedRewardStart = ONE_HUNDRED_HBARS + ONE_HBAR;
        final long expectedRewardRate =
                Math.max(0, Math.min(10 * ONE_HBAR, stakingRewardRate)); // should be 10 * ONE_HBAR;
        final long expectedRewardSumHistory =
                expectedRewardRate
                        / (expectedTotalStakedRewardStart
                                / TINY_PARTS_PER_WHOLE); // should be 9900990L
        final long expectedPendingRewards =
                expectedRewardSumHistory
                        * (expectedTotalStakedRewardStart / TINY_PARTS_PER_WHOLE); // should be
        // 999999990L

        return defaultHapiSpec("rewardsWorkAsExpected")
                .given(
                        overriding("staking.startThreshold", "" + 10 * ONE_HBAR),
                        overriding("staking.rewardRate", "" + stakingRewardRate),
                        cryptoTransfer(tinyBarsFromTo(GENESIS, STAKING_REWARD, ONE_HBAR)))
                .when(
                        cryptoCreate(alice).stakedNodeId(0).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(bob).balance(ONE_HUNDRED_HBARS),
                        sleepFor(SLEEP_MS))
                .then(
                        /* --- staking not active, so no child record for end of staking period are generated --- */
                        cryptoTransfer(tinyBarsFromTo(bob, alice, ONE_HBAR))
                                .via("noRewardTransfer"),
                        getTxnRecord("noRewardTransfer")
                                .stakingFeeExempted()
                                .andAllChildRecords()
                                .hasChildRecordCount(0),

                        /* --- staking will be activated, so child record is generated at end of staking period. But
                        since rewardsSumHistory will be 0 for the first staking period after rewards are activated ,
                        paid_rewards will be 0 --- */
                        cryptoTransfer(tinyBarsFromTo(GENESIS, STAKING_REWARD, 9 * ONE_HBAR)),
                        sleepFor(SLEEP_MS),
                        cryptoTransfer(tinyBarsFromTo(bob, alice, ONE_HBAR)).via("firstTransfer"),
                        getTxnRecord("firstTransfer")
                                .andAllChildRecords()
                                .stakingFeeExempted()
                                .hasChildRecordCount(1)
                                .hasChildRecords(
                                        recordWith().memo(END_OF_STAKING_PERIOD_CALCULATIONS_MEMO))
                                .hasPaidStakingRewards(List.of()),

                        /* --- staking is activated, so child record is generated at end of staking period.
                        Since rewardsSumHistory is updated during the previous staking period after rewards are
                        activated ,
                        paid_rewards will be non-empty in this record --- */
                        sleepFor(SLEEP_MS),
                        cryptoTransfer(tinyBarsFromTo(bob, alice, ONE_HBAR))
                                .payingWith(bob)
                                .via("secondTransfer"),
                        getTxnRecord("secondTransfer")
                                .andAllChildRecords()
                                .hasChildRecordCount(1)
                                .hasStakingFeesPaid()
                                .hasChildRecords(
                                        recordWith().memo(END_OF_STAKING_PERIOD_CALCULATIONS_MEMO))
                                .hasPaidStakingRewards(
                                        List.of(Pair.of(alice, expectedPendingRewards))),

                        /* Within the same period rewards are not awarded twice */
                        cryptoTransfer(tinyBarsFromTo(bob, alice, ONE_HBAR))
                                .payingWith(bob)
                                .via("expectNoReward"),
                        getTxnRecord("expectNoReward")
                                .andAllChildRecords()
                                .hasChildRecordCount(0)
                                .hasStakingFeesPaid()
                                .hasPaidStakingRewards(List.of()));
    }

    private HapiApiSpec sendToCarol() {
        return defaultHapiSpec("SendToCarol")
                .given(
                        //						getAccountBalance("0.0.1006").logged()
                        //						getAccountInfo("0.0.1006").logged()
                        cryptoTransfer(tinyBarsFromTo(GENESIS, "0.0.1004", 1)),
                        cryptoTransfer(tinyBarsFromTo(GENESIS, "0.0.1005", 1)),
                        cryptoTransfer(tinyBarsFromTo(GENESIS, "0.0.1006", 1)),
                        cryptoTransfer(tinyBarsFromTo(GENESIS, "0.0.1004", 1)),
                        cryptoTransfer(tinyBarsFromTo(GENESIS, "0.0.1005", 1)),
                        cryptoTransfer(tinyBarsFromTo(GENESIS, "0.0.1006", 1)))
                .when()
                .then();
    }

    private HapiApiSpec endOfStakingPeriodRecTest() {
        return defaultHapiSpec("EndOfStakingPeriodRecTest")
                .given(
                        cryptoCreate("a1").balance(ONE_HUNDRED_HBARS).stakedNodeId(0),
                        cryptoCreate("a2").balance(ONE_HUNDRED_HBARS).stakedNodeId(0),
                        cryptoTransfer(
                                tinyBarsFromTo(
                                        GENESIS,
                                        "0.0.800",
                                        ONE_MILLION_HBARS)) // will trigger staking
                        )
                .when(sleepFor(SLEEP_MS))
                .then(
                        cryptoTransfer(tinyBarsFromTo("a1", "a2", ONE_HBAR)).via("trigger"),
                        getTxnRecord("trigger")
                                .logged()
                                .hasChildRecordCount(1)
                                .hasChildRecords(
                                        recordWith().memo(END_OF_STAKING_PERIOD_CALCULATIONS_MEMO)),
                        sleepFor(SLEEP_MS),
                        cryptoTransfer(tinyBarsFromTo("a1", "a2", ONE_HBAR)).via("transfer"),
                        getTxnRecord("transfer")
                                .hasChildRecordCount(1)
                                .hasChildRecords(
                                        recordWith().memo(END_OF_STAKING_PERIOD_CALCULATIONS_MEMO))
                                .logged(),
                        cryptoTransfer(tinyBarsFromTo("a1", "a2", ONE_HBAR))
                                .via("noEndOfStakingPeriodRecord"),
                        getTxnRecord("noEndOfStakingPeriodRecord").hasChildRecordCount(0).logged(),
                        sleepFor(SLEEP_MS),
                        cryptoTransfer(tinyBarsFromTo("a1", "a2", ONE_HBAR)).via("transfer1"),
                        getTxnRecord("transfer1")
                                .hasChildRecordCount(1)
                                .hasChildRecords(
                                        recordWith().memo(END_OF_STAKING_PERIOD_CALCULATIONS_MEMO))
                                .logged());
    }

    private HapiApiSpec rewardsOfDeletedAreRedirectedToBeneficiary() {
        final var alice = "alice";
        final var bob = "bob";
        final var deletion = "deletion";
        return defaultHapiSpec("RewardsOfDeletedAreRedirectedToBeneficiary")
                .given(
                        overriding("staking.startThreshold", "" + 10 * ONE_HBAR),
                        cryptoTransfer(tinyBarsFromTo(GENESIS, STAKING_REWARD, ONE_MILLION_HBARS)))
                .when(
                        cryptoCreate(alice).stakedNodeId(0).balance(33_000 * ONE_MILLION_HBARS),
                        cryptoCreate(bob).balance(0L),
                        sleepFor(150_000))
                .then(
                        cryptoDelete(alice).transfer(bob).via(deletion),
                        getTxnRecord(deletion).andAllChildRecords().logged());
    }

    private HapiApiSpec previewnetPlannedTest() {
        final var alice = "alice";
        final var bob = "bob";
        final var carol = "carol";
        final var debbie = "debbie";
        final var civilian = "civilian";
        final var stakingAccount = "0.0.800";
        final var unrewardedTxn = "unrewardedTxn";
        final var rewardedTxn = "rewardedTxn";
        final var rewardedTxn2 = "rewardedTxn2";
        return defaultHapiSpec("PreviewnetPlannedTest")
                .given(
                        overriding("staking.startThreshold", "" + 10 * ONE_HBAR),
                        cryptoTransfer(tinyBarsFromTo(GENESIS, stakingAccount, ONE_MILLION_HBARS)))
                .when(
                        cryptoCreate(civilian),
                        cryptoCreate(alice).stakedNodeId(0).balance(20_000 * ONE_MILLION_HBARS),
                        cryptoCreate(bob).balance(5_000 * ONE_MILLION_HBARS),
                        cryptoCreate(carol).stakedNodeId(0),
                        cryptoCreate(debbie).balance(5 * ONE_HBAR + 90_000_000L),
                        cryptoUpdate(bob).newStakedNodeId(0),
                        // End of period ONE
                        sleepFor(75_000))
                .then(
                        cryptoTransfer(movingHbar(ONE_HBAR).distributing(carol, alice, bob))
                                .payingWith(civilian)
                                .via(unrewardedTxn),
                        //						cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING,
                        // ONE_HBAR)).via(unrewardedTxn),
                        getTxnRecord(unrewardedTxn).andAllChildRecords().logged(),
                        sleepFor(75_000),
                        // rewardSumHistory now: [3, 0, 0, 0, 0, 0, 0, 0, 0, 0]
                        cryptoTransfer(movingHbar(ONE_HBAR).distributing(carol, alice, bob))
                                .payingWith(civilian)
                                .via(rewardedTxn),
                        //						cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING,
                        // ONE_HBAR)).via(rewardedTxn),
                        getTxnRecord(rewardedTxn).andAllChildRecords().logged(),
                        cryptoUpdate(debbie).newStakedAccountId(carol),
                        //						cryptoUpdate(alice).newStakedAccountId(debbie),
                        //						cryptoUpdate(bob).newStakedAccountId(debbie),
                        //						cryptoUpdate(carol).newStakedAccountId(debbie)
                        getAccountInfo(carol).logged(),
                        cryptoTransfer(movingHbar(ONE_HBAR + 90_000_000L).between(GENESIS, debbie)),
                        getAccountInfo(carol).logged(),
                        sleepFor(75_000),
                        cryptoTransfer(movingHbar(ONE_HBAR).distributing(carol, alice, bob))
                                .payingWith(civilian)
                                .via(rewardedTxn2));
    }

    private HapiApiSpec enableRewards() {
        final var stakingAccount = "0.0.800";
        return defaultHapiSpec("EnableRewards")
                .given(overriding("staking.startThreshold", "" + 10 * ONE_HBAR))
                .when(
                        cryptoCreate("account").stakedNodeId(0L),
                        cryptoTransfer(tinyBarsFromTo(GENESIS, stakingAccount, ONE_HBAR))
                                .via("transferTxn"),
                        cryptoTransfer(tinyBarsFromTo(GENESIS, stakingAccount, 8 * ONE_HBAR))
                                .via("moreTransfers"),
                        cryptoTransfer(tinyBarsFromTo(GENESIS, stakingAccount, ONE_HBAR))
                                .via("shouldTriggerStaking"),
                        //
                        //	freezeOnly().payingWith(GENESIS).startingAt(Instant.now().plusSeconds(10))
                        cryptoTransfer(tinyBarsFromTo(GENESIS, "account", ONE_HBAR))
                                .via("shouldSendRewards")
                        // for now testing with the logs, once RewardCalculator is implemented this
                        // test will be
                        // complete.
                        // tested
                        // 1. Only on the last cryptoTransfer the following log is written `Staking
                        // rewards is
                        // activated and rewardSumHistory is cleared`
                        // 2. that restarting from freeze, shows `Staking Rewards Activated ::true`
                        // from
                        // MerkleNetworkContext log
                        )
                .then();
    }
}
