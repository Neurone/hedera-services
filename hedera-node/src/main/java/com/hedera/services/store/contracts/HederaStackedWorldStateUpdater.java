package com.hedera.services.store.contracts;

/*
 * -
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2021 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 *
 */

import com.hederahashgraph.api.proto.java.ContractID;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.Gas;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.hedera.services.utils.EntityIdUtils.accountIdFromEvmAddress;
import static com.hedera.services.utils.EntityIdUtils.contractIdFromEvmAddress;

public class HederaStackedWorldStateUpdater
		extends AbstractStackedLedgerUpdater<HederaMutableWorldState, HederaWorldState.WorldStateAccount>
		implements HederaWorldUpdater {

	private final Map<Address, Address> sponsorMap = new LinkedHashMap<>();
	private final HederaMutableWorldState worldState;

	private Gas sbhRefund = Gas.ZERO;
	private ContractID lastAllocatedId = null;

	public HederaStackedWorldStateUpdater(
			final AbstractLedgerWorldUpdater<HederaMutableWorldState, HederaWorldState.WorldStateAccount> updater,
			final HederaMutableWorldState worldState,
			final WorldLedgers trackingLedgers
	) {
		super(updater, trackingLedgers);
		this.worldState = worldState;
	}

	public byte[] unaliased(final byte[] evmAddress) {
		return aliases().resolveForEvm(Address.wrap(Bytes.wrap(evmAddress))).toArrayUnsafe();
	}

	/**
	 * Given an address in mirror or alias form, returns its alias form (if it has one). We use this to make
	 * the ADDRESS opcode prioritize CREATE2 addresses over mirror addresses.
	 *
	 * @param addressOrAlias a mirror or alias address
	 * @return the alias form of the address, if it exists
	 */
	public Address priorityAddress(final Address addressOrAlias) {
		return trackingLedgers().canonicalAddress(addressOrAlias);
	}

	public Address newAliasedContractAddress(final Address sponsor, final Address alias) {
		final var mirrorAddress = newContractAddress(sponsor);
		final var curAliases = aliases();
		/* Only link the alias if it's not already in use, or if the target of the alleged link
		 * doesn't actually exist. (In the first case, a CREATE2 that tries to re-use an existing
		 * alias address is going to fail in short order; in the second case, the existing link
		 * must have been created by an inline create2 that failed, but didn't revert us---we are
		 * free to re-use this alias). */
		if (!curAliases.isInUse(alias) || isMissingTarget(alias)) {
			curAliases.link(alias, mirrorAddress);
		}
		return mirrorAddress;
	}

	@Override
	public Address newContractAddress(final Address sponsorAddressOrAlias) {
		final var sponsor = aliases().resolveForEvm(sponsorAddressOrAlias);
		final var newAddress = worldState.newContractAddress(sponsor);
		sponsorMap.put(newAddress, sponsor);
		lastAllocatedId = contractIdFromEvmAddress(newAddress);
		return newAddress;
	}

	/**
	 * Returns the underlying entity id of the last allocated EVM address.
	 *
	 * @return the id of the last allocated address
	 */
	public ContractID idOfLastNewAddress() {
		return lastAllocatedId;
	}

	public Map<Address, Address> getSponsorMap() {
		return sponsorMap;
	}

	@Override
	public Gas getSbhRefund() {
		return sbhRefund;
	}

	@Override
	public void addSbhRefund(Gas refund) {
		sbhRefund = sbhRefund.plus(refund);
	}

	@Override
	public void revert() {
		for (int i = 0; i < sponsorMap.size(); i++) {
			worldState.reclaimContractId();
		}
		sponsorMap.clear();
		sbhRefund = Gas.ZERO;
		super.revert();
	}

	@Override
	public void commit() {
		((HederaWorldUpdater) wrappedWorldView()).getSponsorMap().putAll(sponsorMap);
		((HederaWorldUpdater) wrappedWorldView()).addSbhRefund(sbhRefund);
		sbhRefund = Gas.ZERO;
		super.commit();
	}

	@Override
	public HederaWorldState.WorldStateAccount getHederaAccount(final Address addressOrAlias) {
		final var address = aliases().resolveForEvm(addressOrAlias);
		return parentUpdater().map(u -> ((HederaWorldUpdater) u).getHederaAccount(address)).orElse(null);
	}

	@Override
	@SuppressWarnings({"unchecked", "rawtypes"})
	public WorldUpdater updater() {
		return new HederaStackedWorldStateUpdater(
				(AbstractLedgerWorldUpdater) this,
				worldState,
				trackingLedgers().wrapped());
	}

	/* --- Internal helpers --- */
	private boolean isMissingTarget(final Address alias) {
		final var target = aliases().resolveForEvm(alias);
		return !trackingAccounts().exists(accountIdFromEvmAddress(target));
	}
}
