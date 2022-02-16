package com.hedera.services.txns.contract;

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

import com.hedera.services.context.TransactionContext;
import com.hedera.services.ledger.HederaLedger;
import com.hedera.services.ledger.SigImpactHistorian;
import com.hedera.services.ledger.accounts.AliasManager;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.store.models.Id;
import com.hedera.services.txns.TransitionLogic;
import com.hedera.services.txns.contract.helpers.UpdateCustomizerFactory;
import com.hedera.services.txns.validation.OptionValidator;
import com.hedera.services.utils.EntityNum;
import com.hedera.services.utils.accessors.ContractUpdateAccessor;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.swirlds.merkle.map.MerkleMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.hedera.services.exceptions.ValidationUtils.validateTrue;
import static com.hedera.services.utils.EntityIdUtils.unaliased;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AUTORENEW_DURATION_NOT_IN_RANGE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FAIL_INVALID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_RENEWAL_PERIOD;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

public class ContractUpdateTransitionLogic implements TransitionLogic {
	private static final Logger log = LogManager.getLogger(ContractUpdateTransitionLogic.class);

	private final HederaLedger ledger;
	private final AliasManager aliasManager;
	private final OptionValidator validator;
	private final SigImpactHistorian sigImpactHistorian;
	private final TransactionContext txnCtx;
	private final UpdateCustomizerFactory customizerFactory;
	private final Supplier<MerkleMap<EntityNum, MerkleAccount>> contracts;

	public ContractUpdateTransitionLogic(
			final HederaLedger ledger,
			final AliasManager aliasManager,
			final OptionValidator validator,
			final SigImpactHistorian sigImpactHistorian,
			final TransactionContext txnCtx,
			final UpdateCustomizerFactory customizerFactory,
			final Supplier<MerkleMap<EntityNum, MerkleAccount>> contracts
	) {
		this.ledger = ledger;
		this.validator = validator;
		this.aliasManager = aliasManager;
		this.txnCtx = txnCtx;
		this.contracts = contracts;
		this.sigImpactHistorian = sigImpactHistorian;
		this.customizerFactory = customizerFactory;
	}

	@Override
	public void doStateTransition() {
		try {
			final var accessor = (ContractUpdateAccessor) txnCtx.accessor();
			final var id = accessor.targetID();

			validateTrue(!id.equals(Id.DEFAULT), INVALID_CONTRACT_ID);

			final var target = contracts.get().get(id);
			var result = customizerFactory.customizerFor(target, validator, accessor);
			var customizer = result.getLeft();
			if (customizer.isPresent()) {
				ledger.customize(id.asGrpcAccount(), customizer.get());
				sigImpactHistorian.markEntityChanged(id.num());
				if (target.hasAlias()) {
					sigImpactHistorian.markAliasChanged(target.getAlias());
				}
				txnCtx.setStatus(SUCCESS);
				txnCtx.setTargetedContract(id.asGrpcContract());
			} else {
				txnCtx.setStatus(result.getRight());
			}
		} catch (Exception e) {
			log.warn("Avoidable exception!", e);
			txnCtx.setStatus(FAIL_INVALID);
		}
	}

	@Override
	public Predicate<TransactionBody> applicability() {
		return TransactionBody::hasContractUpdateInstance;
	}

	@Override
	public Function<TransactionBody, ResponseCodeEnum> semanticCheck() {
		return this::validate;
	}

	public ResponseCodeEnum validate(TransactionBody contractUpdateTxn) {
		final var op = contractUpdateTxn.getContractUpdateInstance();

		final var id = unaliased(op.getContractID(), aliasManager); // can inject accessor into validate
		var status = validator.queryableContractStatus(id, contracts.get());
		if (status != OK) {
			return status;
		}

		if (op.hasAutoRenewPeriod()) {
			if (op.getAutoRenewPeriod().getSeconds() < 1) {
				return INVALID_RENEWAL_PERIOD;
			}
			if (!validator.isValidAutoRenewPeriod(op.getAutoRenewPeriod())) {
				return AUTORENEW_DURATION_NOT_IN_RANGE;
			}
		}

		final var newMemoIfAny = op.hasMemoWrapper() ? op.getMemoWrapper().getValue() : op.getMemo();
		if ((status = validator.memoCheck(newMemoIfAny)) != OK) {
			return status;
		}

		return OK;
	}
}
