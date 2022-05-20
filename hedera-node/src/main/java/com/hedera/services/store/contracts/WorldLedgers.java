package com.hedera.services.store.contracts;

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

import com.google.protobuf.ByteString;
import com.hedera.services.context.SideEffectsTracker;
import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.ethereum.EthTxSigs;
import com.hedera.services.ledger.SigImpactHistorian;
import com.hedera.services.ledger.TransactionalLedger;
import com.hedera.services.ledger.accounts.ContractAliases;
import com.hedera.services.ledger.accounts.StackedContractAliases;
import com.hedera.services.ledger.accounts.staking.RewardCalculator;
import com.hedera.services.ledger.interceptors.StakeAwareAccountsCommitsInterceptor;
import com.hedera.services.ledger.interceptors.StakeChangeManager;
import com.hedera.services.ledger.properties.AccountProperty;
import com.hedera.services.ledger.properties.NftProperty;
import com.hedera.services.ledger.properties.TokenProperty;
import com.hedera.services.ledger.properties.TokenRelProperty;
import com.hedera.services.state.enums.TokenType;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleNetworkContext;
import com.hedera.services.state.merkle.MerkleStakingInfo;
import com.hedera.services.state.merkle.MerkleToken;
import com.hedera.services.state.merkle.MerkleTokenRelStatus;
import com.hedera.services.state.merkle.MerkleUniqueToken;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.state.submerkle.FcTokenAllowanceId;
import com.hedera.services.store.models.NftId;
import com.hedera.services.utils.EntityNum;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenID;
import com.swirlds.merkle.map.MerkleMap;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import static com.hedera.services.exceptions.ValidationUtils.validateTrue;
import static com.hedera.services.ledger.TransactionalLedger.activeLedgerWrapping;
import static com.hedera.services.ledger.interceptors.AutoAssocTokenRelsCommitInterceptor.forKnownAutoAssociatingOp;
import static com.hedera.services.ledger.properties.AccountProperty.ALIAS;
import static com.hedera.services.ledger.properties.AccountProperty.APPROVE_FOR_ALL_NFTS_ALLOWANCES;
import static com.hedera.services.ledger.properties.NftProperty.METADATA;
import static com.hedera.services.ledger.properties.NftProperty.OWNER;
import static com.hedera.services.ledger.properties.TokenProperty.DECIMALS;
import static com.hedera.services.ledger.properties.TokenProperty.NAME;
import static com.hedera.services.ledger.properties.TokenProperty.SYMBOL;
import static com.hedera.services.ledger.properties.TokenProperty.TOKEN_TYPE;
import static com.hedera.services.ledger.properties.TokenProperty.TOTAL_SUPPLY;
import static com.hedera.services.ledger.properties.TokenProperty.TREASURY;
import static com.hedera.services.ledger.properties.TokenRelProperty.TOKEN_BALANCE;
import static com.hedera.services.state.submerkle.EntityId.MISSING_ENTITY_ID;
import static com.hedera.services.store.contracts.precompile.HTSPrecompiledContract.URI_QUERY_NON_EXISTING_TOKEN_ERROR;
import static com.hedera.services.utils.EntityIdUtils.ECDSA_SECP256K1_ALIAS_SIZE;
import static com.hedera.services.utils.EntityIdUtils.EVM_ADDRESS_SIZE;
import static com.hedera.services.utils.EntityIdUtils.accountIdFromEvmAddress;
import static com.hedera.services.utils.EntityIdUtils.tokenIdFromEvmAddress;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;

public class WorldLedgers {
	public static final ByteString ECDSA_KEY_ALIAS_PREFIX = ByteString.copyFrom(new byte[] { 0x3a, 0x21 });

	private final ContractAliases aliases;
	private final StaticEntityAccess staticEntityAccess;
	private final TransactionalLedger<NftId, NftProperty, MerkleUniqueToken> nftsLedger;
	private final TransactionalLedger<TokenID, TokenProperty, MerkleToken> tokensLedger;
	private final TransactionalLedger<AccountID, AccountProperty, MerkleAccount> accountsLedger;
	private final TransactionalLedger<Pair<AccountID, TokenID>, TokenRelProperty, MerkleTokenRelStatus> tokenRelsLedger;
	private final Supplier<MerkleNetworkContext> networkCtx;
	private final Supplier<MerkleMap<EntityNum, MerkleStakingInfo>> stakingInfo;
	private final GlobalDynamicProperties dynamicProperties;
	private final Supplier<MerkleMap<EntityNum, MerkleAccount>> accounts;
	private final RewardCalculator rewardCalculator;

	public static WorldLedgers staticLedgersWith(
			final ContractAliases aliases,
			final StaticEntityAccess staticEntityAccess
	) {
		return new WorldLedgers(aliases, staticEntityAccess);
	}

	public WorldLedgers(
			final ContractAliases aliases,
			final TransactionalLedger<Pair<AccountID, TokenID>, TokenRelProperty, MerkleTokenRelStatus> tokenRelsLedger,
			final TransactionalLedger<AccountID, AccountProperty, MerkleAccount> accountsLedger,
			final TransactionalLedger<NftId, NftProperty, MerkleUniqueToken> nftsLedger,
			final TransactionalLedger<TokenID, TokenProperty, MerkleToken> tokensLedger,
			final Supplier<MerkleNetworkContext> networkCtx,
			final Supplier<MerkleMap<EntityNum, MerkleStakingInfo>> stakingInfo,
			final GlobalDynamicProperties dynamicProperties,
			final Supplier<MerkleMap<EntityNum, MerkleAccount>> accounts,
			final RewardCalculator rewardCalculator
	) {
		this.tokenRelsLedger = tokenRelsLedger;
		this.accountsLedger = accountsLedger;
		this.tokensLedger = tokensLedger;
		this.nftsLedger = nftsLedger;
		this.aliases = aliases;
		this.networkCtx = networkCtx;
		this.stakingInfo = stakingInfo;
		this.dynamicProperties = dynamicProperties;
		this.rewardCalculator = rewardCalculator;
		this.accounts = accounts;

		staticEntityAccess = null;
	}

	private WorldLedgers(final ContractAliases aliases, final StaticEntityAccess staticEntityAccess) {
		tokenRelsLedger = null;
		accountsLedger = null;
		tokensLedger = null;
		nftsLedger = null;
		networkCtx = null;
		stakingInfo = null;
		dynamicProperties = null;
		rewardCalculator = null;
		accounts = null;

		this.aliases = aliases;
		this.staticEntityAccess = staticEntityAccess;
	}

	public boolean isTokenAddress(final Address address) {
		if (staticEntityAccess != null) {
			return staticEntityAccess.isTokenAccount(address);
		} else {
			return tokensLedger.contains(tokenIdFromEvmAddress(address));
		}
	}

	public String nameOf(final TokenID tokenId) {
		return propertyOf(tokenId, NAME, StaticEntityAccess::nameOf);
	}

	public String symbolOf(final TokenID tokenId) {
		return propertyOf(tokenId, SYMBOL, StaticEntityAccess::symbolOf);
	}

	public long totalSupplyOf(final TokenID tokenId) {
		return propertyOf(tokenId, TOTAL_SUPPLY, StaticEntityAccess::supplyOf);
	}

	public int decimalsOf(final TokenID tokenId) {
		return propertyOf(tokenId, DECIMALS, StaticEntityAccess::decimalsOf);
	}

	public TokenType typeOf(final TokenID tokenId) {
		return propertyOf(tokenId, TOKEN_TYPE, StaticEntityAccess::typeOf);
	}

	public long balanceOf(final AccountID accountId, final TokenID tokenId) {
		if (staticEntityAccess != null) {
			return staticEntityAccess.balanceOf(accountId, tokenId);
		} else {
			validateTrue(tokensLedger.exists(tokenId), INVALID_TOKEN_ID);
			validateTrue(accountsLedger.exists(accountId), INVALID_ACCOUNT_ID);
			final var balanceKey = Pair.of(accountId, tokenId);
			return tokenRelsLedger.exists(balanceKey)
					? (long) tokenRelsLedger.get(balanceKey, TOKEN_BALANCE) : 0;
		}
	}

	@Nullable
	public EntityId ownerIfPresent(final NftId nftId) {
		if (!areMutable()) {
			throw new IllegalStateException("Static ledgers cannot be used to get owner if present");
		}
		return nftsLedger.contains(nftId) ? explicitOwnerOfExtant(nftId): null;
	}

	public Address ownerOf(final NftId nftId) {
		if (!areMutable()) {
			return staticEntityAccess.ownerOf(nftId);
		}
		return explicitOwnerOfExtant(nftId).toEvmAddress();
	}

	@SuppressWarnings("unchecked")
	public boolean hasApprovedForAll(final AccountID ownerId, final AccountID operatorId, final TokenID tokenId) {
		if (!areMutable()) {
			throw new IllegalStateException("Static ledgers cannot be used to check approvedForAll");
		}
		final Set<FcTokenAllowanceId> approvedForAll =
				(Set<FcTokenAllowanceId>) accountsLedger.get(ownerId, APPROVE_FOR_ALL_NFTS_ALLOWANCES);
		return approvedForAll.contains(FcTokenAllowanceId.from(tokenId, operatorId));
	}

	public String metadataOf(final NftId nftId) {
		if (!areMutable()) {
			return staticEntityAccess.metadataOf(nftId);
		}
		return nftsLedger.exists(nftId)
				? new String((byte[]) nftsLedger.get(nftId, METADATA))
				: URI_QUERY_NON_EXISTING_TOKEN_ERROR;
	}

	public Address canonicalAddress(final Address addressOrAlias) {
		if (aliases.isInUse(addressOrAlias)) {
			return addressOrAlias;
		}

		return getAddressOrAlias(addressOrAlias);
	}

	public Address getAddressOrAlias(final Address address) {
		final var sourceId = accountIdFromEvmAddress(address);
		final ByteString alias;
		if (accountsLedger != null) {
			if (!accountsLedger.exists(sourceId)) {
				return address;
			}
			alias = (ByteString) accountsLedger.get(sourceId, ALIAS);
		} else {
			Objects.requireNonNull(staticEntityAccess, "Null ledgers must imply non-null static access");
			if (!staticEntityAccess.isExtant(sourceId)) {
				return address;
			}
			alias = staticEntityAccess.alias(sourceId);
		}
		if (!alias.isEmpty()) {
			if (alias.size() == EVM_ADDRESS_SIZE) {
				return Address.wrap(Bytes.wrap(alias.toByteArray()));
			} else if (alias.size() == ECDSA_SECP256K1_ALIAS_SIZE && alias.startsWith(ECDSA_KEY_ALIAS_PREFIX)) {
				byte[] value = EthTxSigs.recoverAddressFromPubKey(alias.substring(2).toByteArray());
				if (value != null) {
					return Address.wrap(Bytes.wrap(value));
				}
			}
		}
		return address;
	}

	public void commit() {
		if (areMutable()) {
			aliases.commit(null);
			commitLedgers();
		}
	}

	public void commit(final SigImpactHistorian sigImpactHistorian) {
		if (areMutable()) {
			aliases.commit(sigImpactHistorian);
			commitLedgers();
		}
	}

	private void commitLedgers() {
		tokenRelsLedger.commit();
		accountsLedger.commit();
		nftsLedger.commit();
		tokensLedger.commit();
	}

	public void revert() {
		if (areMutable()) {
			tokenRelsLedger.rollback();
			accountsLedger.rollback();
			nftsLedger.rollback();
			tokensLedger.rollback();
			aliases.revert();

			/* Since AbstractMessageProcessor.clearAccumulatedStateBesidesGasAndOutput() will make a
			 * second token call to commit() after the initial revert(), we want to keep these ledgers
			 * in an active transaction. */
			tokenRelsLedger.begin();
			accountsLedger.begin();
			nftsLedger.begin();
			tokensLedger.begin();
		}
	}

	public boolean areMutable() {
		return nftsLedger != null &&
				tokensLedger != null &&
				accountsLedger != null &&
				tokenRelsLedger != null;
	}

	public WorldLedgers wrapped() {
		return wrappedInternal(null);
	}

	public WorldLedgers wrapped(final SideEffectsTracker sideEffectsTracker) {
		return wrappedInternal(sideEffectsTracker);
	}

	public void customizeForAutoAssociatingOp(final SideEffectsTracker sideEffectsTracker) {
		if (!areMutable()) {
			throw new IllegalStateException("Static ledgers cannot be customized");
		}
		tokenRelsLedger.setCommitInterceptor(forKnownAutoAssociatingOp(sideEffectsTracker));
	}

	private WorldLedgers wrappedInternal(@Nullable final SideEffectsTracker sideEffectsTracker) {
		if (!areMutable()) {
			return staticLedgersWith(StackedContractAliases.wrapping(aliases), staticEntityAccess);
		}

		final var wrappedNftsLedger = activeLedgerWrapping(nftsLedger);
		final var wrappedTokensLedger = activeLedgerWrapping(tokensLedger);
		final var wrappedAccountsLedger = activeLedgerWrapping(accountsLedger);
		if (sideEffectsTracker != null) {
			final var stakeAwareAccountsCommitInterceptor = new StakeAwareAccountsCommitsInterceptor(sideEffectsTracker, networkCtx,
					stakingInfo, dynamicProperties, accounts, rewardCalculator,
					new StakeChangeManager(accounts, stakingInfo));
			wrappedAccountsLedger.setCommitInterceptor(stakeAwareAccountsCommitInterceptor);
		}
		final var wrappedTokenRelsLedger = activeLedgerWrapping(tokenRelsLedger);

		return new WorldLedgers(
				StackedContractAliases.wrapping(aliases),
				wrappedTokenRelsLedger,
				wrappedAccountsLedger,
				wrappedNftsLedger,
				wrappedTokensLedger,
				networkCtx,
				stakingInfo,
				dynamicProperties,
				accounts,
				rewardCalculator
		);
	}

	public ContractAliases aliases() {
		return aliases;
	}

	public TransactionalLedger<Pair<AccountID, TokenID>, TokenRelProperty, MerkleTokenRelStatus> tokenRels() {
		return tokenRelsLedger;
	}

	public TransactionalLedger<AccountID, AccountProperty, MerkleAccount> accounts() {
		return accountsLedger;
	}

	public TransactionalLedger<NftId, NftProperty, MerkleUniqueToken> nfts() {
		return nftsLedger;
	}

	public TransactionalLedger<TokenID, TokenProperty, MerkleToken> tokens() {
		return tokensLedger;
	}

	// --- Internal helpers
	private <T> T propertyOf(
			final TokenID tokenId,
			final TokenProperty property,
			final BiFunction<StaticEntityAccess, TokenID, T> staticGetter
	) {
		if (staticEntityAccess != null) {
			return staticGetter.apply(staticEntityAccess, tokenId);
		} else {
			return getTokenMeta(tokenId, property);
		}
	}

	private <T> T getTokenMeta(final TokenID tokenId, final TokenProperty property) {
		final var value = (T) tokensLedger.get(tokenId, property);
		validateTrue(value != null, INVALID_TOKEN_ID);
		return value;
	}

	private EntityId explicitOwnerOfExtant(final NftId nftId) {
		var owner = (EntityId) nftsLedger.get(nftId, OWNER);
		if (MISSING_ENTITY_ID.equals(owner)) {
			owner = (EntityId) tokensLedger.get(nftId.tokenId(), TREASURY);
		}
		return owner;
	}
}
