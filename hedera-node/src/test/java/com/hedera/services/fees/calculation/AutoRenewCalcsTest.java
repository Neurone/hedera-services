package com.hedera.services.fees.calculation;

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

import com.hedera.services.pricing.BaseOperationUsage;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.submerkle.FcTokenAllowance;
import com.hedera.services.state.submerkle.FcTokenAllowanceId;
import com.hedera.services.state.virtual.VirtualBlobKey;
import com.hedera.services.state.virtual.VirtualBlobValue;
import com.hedera.services.sysfiles.serdes.FeesJsonToProtoSerde;
import com.hedera.services.usage.crypto.CryptoOpsUsage;
import com.hedera.services.usage.crypto.ExtantCryptoContext;
import com.hedera.services.utils.EntityNum;
import com.hedera.services.utils.MiscUtils;
import com.hedera.test.extensions.LogCaptor;
import com.hedera.test.extensions.LogCaptureExtension;
import com.hedera.test.extensions.LoggingSubject;
import com.hedera.test.extensions.LoggingTarget;
import com.hedera.test.factories.accounts.MerkleAccountFactory;
import com.hedera.test.factories.scenarios.TxnHandlingScenario;
import com.hederahashgraph.api.proto.java.ExchangeRate;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.SubType;
import com.swirlds.virtualmap.VirtualMap;
import org.apache.commons.lang3.tuple.Triple;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.hedera.services.fees.calculation.AutoRenewCalcs.countSerials;
import static com.hedera.services.pricing.BaseOperationUsage.CANONICAL_CONTRACT_BYTECODE_SIZE;
import static com.hedera.services.txns.crypto.helpers.AllowanceHelpers.getCryptoAllowancesList;
import static com.hedera.services.txns.crypto.helpers.AllowanceHelpers.getFungibleTokenAllowancesList;
import static com.hedera.services.txns.crypto.helpers.AllowanceHelpers.getNftAllowancesList;
import static com.hedera.test.utils.IdUtils.asAccount;
import static com.hedera.test.utils.IdUtils.asToken;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ContractAutoRenew;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoAccountAutoRenew;
import static com.hederahashgraph.fee.FeeBuilder.FEE_DIVISOR_FACTOR;
import static com.hederahashgraph.fee.FeeBuilder.getTinybarsFromTinyCents;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsIterableContainingInOrder.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;

@ExtendWith({ MockitoExtension.class, LogCaptureExtension.class })
class AutoRenewCalcsTest {
	private static final EntityNum contractNum = EntityNum.fromLong(666);
	private final Instant preCutoff = Instant.ofEpochSecond(1_234_566L);
	private final Instant cutoff = Instant.ofEpochSecond(1_234_567L);
	private final Instant postCutoff = Instant.ofEpochSecond(1_234_568L);

	private Triple<Map<SubType, FeeData>, Instant, Map<SubType, FeeData>> accountPrices;
	private Triple<Map<SubType, FeeData>, Instant, Map<SubType, FeeData>> contractPrices;
	private MerkleAccount expiredEntity;
	private final CryptoOpsUsage cryptoOpsUsage = new CryptoOpsUsage();
	private final ExchangeRate activeRates = ExchangeRate.newBuilder()
			.setHbarEquiv(1)
			.setCentEquiv(10)
			.build();
	private final int associatedTokensCount = 123;

	@LoggingTarget
	private LogCaptor logCaptor;
	@Mock
	private VirtualBlobValue code;
	@Mock
	private VirtualMap<VirtualBlobKey, VirtualBlobValue> bytecode;

	@LoggingSubject
	private AutoRenewCalcs subject;

	@BeforeEach
	void setUp() throws Exception {
		accountPrices = frozenPricesFrom("fees/feeSchedules.json", CryptoAccountAutoRenew);
		contractPrices = frozenPricesFrom("fees/feeSchedules.json", ContractAutoRenew);

		subject = new AutoRenewCalcs(cryptoOpsUsage, () -> bytecode);

		subject.setAccountRenewalPriceSeq(accountPrices);
		subject.setContractRenewalPriceSeq(contractPrices);
	}

	@Test
	void warnsOnMissingAccountFeeData() {
		subject.setAccountRenewalPriceSeq(Triple.of(null, cutoff, null));

		assertThat(
				logCaptor.warnLogs(),
				contains(Matchers.startsWith("No prices known for CryptoAccountAutoRenew, will charge zero fees")));
	}

	@Test
	void warnsOnMissingContractFeeData() {
		subject.setContractRenewalPriceSeq(Triple.of(null, cutoff, null));

		assertThat(
				logCaptor.warnLogs(),
				contains(Matchers.startsWith("No prices known for ContractAutoRenew, will charge zero fees")));
	}

	@Test
	void computesTinybarFromNominal() {
		// given:
		final long nominalFee = 1_234_567_000_000L;
		final long tinybarFee = getTinybarsFromTinyCents(activeRates, nominalFee / FEE_DIVISOR_FACTOR);

		// expect:
		assertEquals(tinybarFee, subject.inTinybars(nominalFee, activeRates));
	}

	@Test
	void computesExpectedUsdPriceForThreeMonthAccountRenewal() {
		long expectedFeeInTinycents = 2200000L;
		long expectedFeeInTinybars = getTinybarsFromTinyCents(activeRates, expectedFeeInTinycents);
		long threeMonthsInSeconds = 7776000L;
		setupSuperStandardAccountWith(Long.MAX_VALUE);

		// when:
		var maxRenewalAndFee = subject.assessCryptoRenewal(
				expiredEntity, threeMonthsInSeconds, preCutoff, activeRates);
		var percentageOfExpected = (1.0 * maxRenewalAndFee.fee()) / expectedFeeInTinybars * 100.0;

		// then:
		assertEquals(threeMonthsInSeconds, maxRenewalAndFee.renewalPeriod());
		assertEquals(100.0, percentageOfExpected, 5.0);
	}

	@Test
	void computesExpectedUsdPriceForThreeMonthContractRenewal() {
		long expectedFeeInTinycents = 260000000L;
		long expectedFeeInTinybars = getTinybarsFromTinyCents(activeRates, expectedFeeInTinycents);
		long threeMonthsInSeconds = 7776000L;
		setupSuperStandardContractWith(Long.MAX_VALUE);

		var preCutoffAssessment = subject.assessCryptoRenewal(
				expiredEntity, threeMonthsInSeconds, preCutoff, activeRates);
		var prePercent = (1.0 * preCutoffAssessment.fee()) / expectedFeeInTinybars * 100.0;
		assertEquals(100.0, prePercent, 5.0);
		assertEquals(threeMonthsInSeconds, preCutoffAssessment.renewalPeriod());

		var postCutoffAssessment = subject.assessCryptoRenewal(
				expiredEntity, threeMonthsInSeconds, postCutoff, activeRates);
		var postPercent = (1.0 * postCutoffAssessment.fee()) / expectedFeeInTinybars * 100.0;
		assertEquals(100.0, postPercent, 5.0);
		assertEquals(threeMonthsInSeconds, postCutoffAssessment.renewalPeriod());
	}

	@Test
	void computesExpectedUsdPriceForSlightlyMoreThanThreeMonthRenewal() {
		// setup:
		long expectedFeeInTinycents = 2200000L;
		long expectedFeeInTinybars = getTinybarsFromTinyCents(activeRates, expectedFeeInTinycents);
		long threeMonthsInSeconds = 7776001L;
		setupSuperStandardAccountWith(Long.MAX_VALUE);

		// when:
		var maxRenewalAndFee = subject.assessCryptoRenewal(
				expiredEntity, threeMonthsInSeconds, preCutoff, activeRates);
		var percentageOfExpected = (1.0 * maxRenewalAndFee.fee()) / expectedFeeInTinybars * 100.0;

		// then:
		assertEquals(threeMonthsInSeconds, maxRenewalAndFee.renewalPeriod());
		assertEquals(100.0, percentageOfExpected, 5.0);
	}

	@Test
	void throwsIseIfUsedForAccountWithoutInitializedPrices() {
		setupAccountWith(1L);

		// given:
		subject = new AutoRenewCalcs(cryptoOpsUsage, () -> bytecode);

		// expect:
		Assertions.assertThrows(IllegalStateException.class, () ->
				subject.assessCryptoRenewal(expiredEntity, 0L, preCutoff, activeRates));
	}

	@Test
	void throwsIseIfUsedForContractWithoutInitializedPrices() {
		expiredEntity = MerkleAccountFactory.newAccount()
				.isSmartContract(true)
				.balance(1)
				.get();

		// given:
		subject = new AutoRenewCalcs(cryptoOpsUsage, () -> bytecode);

		// expect:
		Assertions.assertThrows(IllegalStateException.class, () ->
				subject.assessCryptoRenewal(expiredEntity, 0L, preCutoff, activeRates));
	}

	@Test
	void returnsZeroZeroIfBalanceIsZero() {
		setupAccountWith(0L);

		// when:
		var maxRenewalAndFee = subject.assessCryptoRenewal(
				expiredEntity, 7776000L, preCutoff, activeRates);

		// then:
		assertEquals(0, maxRenewalAndFee.renewalPeriod());
		assertEquals(0, maxRenewalAndFee.fee());
	}

	@Test
	void knowsHowToBuildCtx() {
		setupAccountWith(0L);

		// given:
		var expectedCtx = ExtantCryptoContext.newBuilder()
				.setCurrentExpiry(0L)
				.setCurrentKey(MiscUtils.asKeyUnchecked(expiredEntity.getAccountKey()))
				.setCurrentlyHasProxy(true)
				.setCurrentMemo(expiredEntity.getMemo())
				.setCurrentNumTokenRels(associatedTokensCount)
				.setCurrentMaxAutomaticAssociations(expiredEntity.getMaxAutomaticAssociations())
				.setCurrentCryptoAllowances(getCryptoAllowancesList(expiredEntity))
				.setCurrentTokenAllowances(getFungibleTokenAllowancesList(expiredEntity))
				.setCurrentApproveForAllNftAllowances(getNftAllowancesList(expiredEntity))
				.build();

		// expect:
		assertEquals(cryptoOpsUsage.cryptoAutoRenewRb(expectedCtx), subject.rbUsedBy(expiredEntity));
	}

	@Test
	void countsSerialsCorrectly() {
		final var spender1 = asAccount("0.0.1000");
		final var token1 = asToken("0.0.1000");
		final var token2 = asToken("0.0.1000");
		Map<FcTokenAllowanceId, FcTokenAllowance> nftAllowances = new TreeMap<>();
		final var id = FcTokenAllowanceId.from(EntityNum.fromTokenId(token1),
				EntityNum.fromAccountId(spender1));
		final var Nftid = FcTokenAllowanceId.from(EntityNum.fromTokenId(token2),
				EntityNum.fromAccountId(spender1));
		final var val = FcTokenAllowance.from(false, List.of(1L, 100L));
		nftAllowances.put(Nftid, val);
		assertEquals(2, countSerials(nftAllowances));
	}

	private Triple<Map<SubType, FeeData>, Instant, Map<SubType, FeeData>> frozenPricesFrom(
			String resource,
			HederaFunctionality autoRenewFunction
	) throws Exception {
		var schedules = FeesJsonToProtoSerde.loadFeeScheduleFromJson(resource);
		var prePrices = schedules.getCurrentFeeSchedule().getTransactionFeeScheduleList().stream()
				.filter(transactionFeeSchedule -> transactionFeeSchedule.getHederaFunctionality() == autoRenewFunction)
				.findFirst()
				.get()
				.getFeesList();
		var prePricesMap = toSubTypeMap(prePrices);

		var postPricesMap = toPostPrices(prePricesMap);
		return Triple.of(prePricesMap, cutoff, postPricesMap);
	}

	private Map<SubType, FeeData> toSubTypeMap(List<FeeData> feesList) {
		Map<SubType, FeeData> result = new HashMap<>();
		for (FeeData feeData : feesList) {
			result.put(feeData.getSubType(), feeData);
		}
		return result;
	}

	private Map<SubType, FeeData> toPostPrices(Map<SubType, FeeData> feeDataMap) {
		var changeableMap = new HashMap<>(feeDataMap);
		for (FeeData feeData : feeDataMap.values()) {
			var postPrices = feeData.toBuilder()
					.setServicedata(feeData.getServicedata().toBuilder().setRbh(2 * feeData.getServicedata().getRbh()))
					.build();
			changeableMap.put(postPrices.getSubType(), postPrices);
		}
		return changeableMap;
	}

	private void setupAccountWith(long balance) {
		expiredEntity = MerkleAccountFactory.newAccount()
				.accountKeys(TxnHandlingScenario.COMPLEX_KEY_ACCOUNT_KT.asJKeyUnchecked())
				.balance(balance)
				.tokens(asToken("1.2.3"), asToken("2.3.4"), asToken("3.4.5"))
				.proxy(asAccount("0.0.12345"))
				.memo("SHOCKED, I tell you!")
				.associatedTokensCount(associatedTokensCount)
				.lastAssociatedToken(5)
				.get();
	}

	private void setupSuperStandardAccountWith(long balance) {
		expiredEntity = MerkleAccountFactory.newAccount()
				.accountKeys(TxnHandlingScenario.SIMPLE_NEW_ADMIN_KT.asJKeyUnchecked())
				.balance(balance)
				.get();
	}

	private void setupSuperStandardContractWith(long balance) {
		expiredEntity = MerkleAccountFactory.newAccount()
				.isSmartContract(true)
				.numKvPairs(BaseOperationUsage.CANONICAL_NUM_CONTRACT_KV_PAIRS)
				.accountKeys(TxnHandlingScenario.SIMPLE_NEW_ADMIN_KT.asJKeyUnchecked())
				.balance(balance)
				.get();
		expiredEntity.setKey(contractNum);
		final var codeKey = new VirtualBlobKey(VirtualBlobKey.Type.CONTRACT_BYTECODE, contractNum.intValue());
		final var codeData = new byte[CANONICAL_CONTRACT_BYTECODE_SIZE];
		given(code.getData()).willReturn(codeData);
		given(bytecode.get(codeKey)).willReturn(code);
	}
}
