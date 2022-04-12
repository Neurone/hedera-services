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

import com.google.common.annotations.VisibleForTesting;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.submerkle.FcTokenAllowance;
import com.hedera.services.state.submerkle.FcTokenAllowanceId;
import com.hedera.services.state.virtual.VirtualBlobKey;
import com.hedera.services.state.virtual.VirtualBlobValue;
import com.hedera.services.usage.contract.ExtantContractContext;
import com.hedera.services.usage.crypto.CryptoOpsUsage;
import com.hedera.services.usage.crypto.ExtantCryptoContext;
import com.hederahashgraph.api.proto.java.ExchangeRate;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.SubType;
import com.swirlds.virtualmap.VirtualMap;
import org.apache.commons.lang3.tuple.Triple;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

import static com.hedera.services.store.contracts.StaticEntityAccess.explicitCodeFetch;
import static com.hedera.services.txns.crypto.helpers.AllowanceHelpers.getCryptoAllowancesList;
import static com.hedera.services.txns.crypto.helpers.AllowanceHelpers.getFungibleTokenAllowancesList;
import static com.hedera.services.txns.crypto.helpers.AllowanceHelpers.getNftAllowancesList;
import static com.hedera.services.utils.MiscUtils.asKeyUnchecked;
import static com.hederahashgraph.fee.FeeBuilder.FEE_DIVISOR_FACTOR;
import static com.hederahashgraph.fee.FeeBuilder.HRS_DIVISOR;
import static com.hederahashgraph.fee.FeeBuilder.getTinybarsFromTinyCents;

@Singleton
public class AutoRenewCalcs {
	private static final Logger log = LogManager.getLogger(AutoRenewCalcs.class);

	private static final RenewAssessment NO_RENEWAL_POSSIBLE = new RenewAssessment(0L, 0L);

	private final CryptoOpsUsage cryptoOpsUsage;
	private final Supplier<VirtualMap<VirtualBlobKey, VirtualBlobValue>> bytecode;

	private Triple<Map<SubType, FeeData>, Instant, Map<SubType, FeeData>> accountPricesSeq = null;
	private Triple<Map<SubType, FeeData>, Instant, Map<SubType, FeeData>> contractPricesSeq = null;

	private long firstAccountConstantFee = 0L;
	private long secondAccountConstantFee = 0L;
	private long firstAccountRbhPrice = 0L;
	private long secondAccountRbhPrice = 0L;

	private long firstContractConstantFee = 0L;
	private long secondContractConstantFee = 0L;
	private long firstContractRbhPrice = 0L;
	private long secondContractRbhPrice = 0L;
	private long firstContractSbhPrice = 0L;
	private long secondContractSbhPrice = 0L;

	@Inject
	public AutoRenewCalcs(
			final CryptoOpsUsage cryptoOpsUsage,
			final Supplier<VirtualMap<VirtualBlobKey, VirtualBlobValue>> bytecode
	) {
		this.bytecode = bytecode;
		this.cryptoOpsUsage = cryptoOpsUsage;
	}

	public void setAccountRenewalPriceSeq(
			final Triple<Map<SubType, FeeData>, Instant, Map<SubType, FeeData>> accountPricesSeq
	) {
		this.accountPricesSeq = accountPricesSeq;
		if (accountPricesSeq.getLeft() == null) {
			log.warn("No prices known for CryptoAccountAutoRenew, will charge zero fees!");
		} else {
			final var leftPrices = accountPricesSeq.getLeft().get(SubType.DEFAULT);
			final var rightPrices = accountPricesSeq.getLeft().get(SubType.DEFAULT);
			this.firstAccountConstantFee = constantFeeFrom(leftPrices);
			this.secondAccountConstantFee = constantFeeFrom(rightPrices);
			this.firstAccountRbhPrice = leftPrices.getServicedata().getRbh();
			this.secondAccountRbhPrice = rightPrices.getServicedata().getRbh();
		}
	}

	public void setContractRenewalPriceSeq(
			final Triple<Map<SubType, FeeData>, Instant, Map<SubType, FeeData>> contractPricesSeq
	) {
		this.contractPricesSeq = contractPricesSeq;
		if (contractPricesSeq.getLeft() == null) {
			log.warn("No prices known for ContractAutoRenew, will charge zero fees!");
		} else {
			final var leftPrices = contractPricesSeq.getLeft().get(SubType.DEFAULT);
			final var rightPrices = contractPricesSeq.getLeft().get(SubType.DEFAULT);
			this.firstContractConstantFee = constantFeeFrom(leftPrices);
			this.secondContractConstantFee = constantFeeFrom(rightPrices);
			this.firstContractRbhPrice = leftPrices.getServicedata().getRbh();
			this.firstContractSbhPrice = leftPrices.getServicedata().getSbh();
			this.secondContractRbhPrice = rightPrices.getServicedata().getRbh();
			this.secondContractSbhPrice = rightPrices.getServicedata().getSbh();
		}
	}

	public RenewAssessment assessCryptoRenewal(
			final MerkleAccount expiredAccountOrContract,
			final long reqPeriod,
			final Instant at,
			final ExchangeRate rate
	) {
		final long balance = expiredAccountOrContract.getBalance();
		if (balance == 0L) {
			return NO_RENEWAL_POSSIBLE;
		}
		final var renewalFees = renewalFees(at, rate, expiredAccountOrContract);
		return assess(renewalFees, reqPeriod, balance);
	}

	private RenewalFees renewalFees(
			final Instant at,
			final ExchangeRate rate,
			final MerkleAccount expiredAccountOrContract
	) {
		if (expiredAccountOrContract.isSmartContract()) {
			return contractRenewalPrices(at, rate, expiredAccountOrContract);
		} else {
			return accountRenewalPrices(at, rate, expiredAccountOrContract);
		}
	}

	private RenewalFees contractRenewalPrices(
			final Instant at,
			final ExchangeRate rate,
			final MerkleAccount contract
	) {
		if (contractPricesSeq == null) {
			throw new IllegalStateException("No contract usage prices are set!");
		}

		final boolean isBeforeSwitch = at.isBefore(contractPricesSeq.getMiddle());
		final long fixedPrice = isBeforeSwitch ? firstContractConstantFee : secondContractConstantFee;
		final long rbhPrice = isBeforeSwitch ? firstContractRbhPrice : secondContractRbhPrice;
		final long sbhPrice = isBeforeSwitch ? firstContractSbhPrice : secondContractSbhPrice;

		final var contractContext = contractContextFrom(contract);
		final var hourlyPrice = rbhPrice * contractContext.currentRb() + sbhPrice * contractContext.currentSb();
		return new RenewalFees(inTinybars(fixedPrice, rate), inTinybars(hourlyPrice, rate));
	}

	private RenewalFees accountRenewalPrices(
			final Instant at,
			final ExchangeRate rate,
			final MerkleAccount account
	) {
		if (accountPricesSeq == null) {
			throw new IllegalStateException("No account usage prices are set!");
		}
		final boolean isBeforeSwitch = at.isBefore(accountPricesSeq.getMiddle());
		final long nominalFixed = isBeforeSwitch ? firstAccountConstantFee : secondAccountConstantFee;
		final long serviceRbhPrice = isBeforeSwitch ? firstAccountRbhPrice : secondAccountRbhPrice;
		final long fixedFee = inTinybars(nominalFixed, rate);
		final long rbUsage = rbUsedBy(account);
		final long hourlyFee = inTinybars(serviceRbhPrice * rbUsage, rate);
		return new RenewalFees(fixedFee, hourlyFee);
	}

	private RenewAssessment assess(
			final RenewalFees meta,
			final long reqPeriod,
			final long balance
	) {
		final long maxRenewableHours = Math.max(
				1L, maxRenewableHoursGiven(meta.fixedFee(), meta.hourlyFee(), reqPeriod, balance));
		final long maxRenewablePeriod = maxRenewableHours * HRS_DIVISOR;
		final long feeForMaxRenewal = Math.min(meta.fixedFee() + maxRenewableHours * meta.hourlyFee(), balance);
		return new RenewAssessment(feeForMaxRenewal, Math.min(reqPeriod, maxRenewablePeriod));
	}

	private long maxRenewableHoursGiven(
			long fixedTinybarFee,
			long tinybarPerHour,
			long requestedPeriod,
			long balance
	) {
		final long remainingBalance = Math.max(0, balance - fixedTinybarFee);
		final long affordableHours = remainingBalance / tinybarPerHour;
		final long requestedHours = requestedPeriod / HRS_DIVISOR + (requestedPeriod % HRS_DIVISOR > 0 ? 1 : 0);
		return Math.min(affordableHours, requestedHours);
	}

	private ExtantContractContext contractContextFrom(final MerkleAccount contract) {
		final var accountContext = accountContextFrom(contract);
		final var numKvPairs = contract.getNumContractKvPairs();
		final var code = explicitCodeFetch(bytecode.get(), contract.getKey().longValue());
		final var codeSize = code == null ? 0 : code.size();
		return new ExtantContractContext(numKvPairs, codeSize, accountContext);
	}

	private ExtantCryptoContext accountContextFrom(final MerkleAccount account) {
		return ExtantCryptoContext.newBuilder()
				.setCurrentExpiry(0L)
				.setCurrentKey(asKeyUnchecked(account.getAccountKey()))
				.setCurrentlyHasProxy(account.getProxy() != null)
				.setCurrentMemo(account.getMemo())
				.setCurrentNumTokenRels(account.getNumAssociations())
				.setCurrentMaxAutomaticAssociations(account.getMaxAutomaticAssociations())
				.setCurrentCryptoAllowances(getCryptoAllowancesList(account))
				.setCurrentTokenAllowances(getFungibleTokenAllowancesList(account))
				.setCurrentApproveForAllNftAllowances(getNftAllowancesList(account))
				.build();
	}

	private long constantFeeFrom(FeeData prices) {
		return prices.getNodedata().getConstant()
				+ prices.getNetworkdata().getConstant()
				+ prices.getServicedata().getConstant();
	}

	@VisibleForTesting
	long inTinybars(long nominalFee, ExchangeRate rate) {
		return getTinybarsFromTinyCents(rate, nominalFee / FEE_DIVISOR_FACTOR);
	}

	@VisibleForTesting
	long rbUsedBy(final MerkleAccount account) {
		final var accountContext = accountContextFrom(account);
		return cryptoOpsUsage.cryptoAutoRenewRb(accountContext);
	}

	@VisibleForTesting
	static int countSerials(Map<FcTokenAllowanceId, FcTokenAllowance> allowanceMap) {
		int serials = 0;
		for (Map.Entry<FcTokenAllowanceId, FcTokenAllowance> e : allowanceMap.entrySet()) {
			serials += e.getValue().getSerialNumbers().size();
		}
		return serials;
	}
}
