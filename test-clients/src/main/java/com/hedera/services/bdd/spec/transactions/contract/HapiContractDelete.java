package com.hedera.services.bdd.spec.transactions.contract;

/*-
 * ‌
 * Hedera Services Test Clients
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

import com.google.common.base.MoreObjects;
import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.spec.infrastructure.meta.SupportedContract;
import com.hedera.services.bdd.spec.queries.crypto.ReferenceType;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.ContractDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionResponse;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.hedera.services.bdd.spec.transactions.TxnUtils.asId;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asIdForKeyLookUp;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

public class HapiContractDelete extends HapiTxnOp<HapiContractDelete> {
	private boolean shouldPurge = false;
	private final String contract;
	private Optional<String> transferAccount = Optional.empty();
	private Optional<String> transferContract = Optional.empty();
	private ReferenceType transferAccountRefType = ReferenceType.REGISTRY_NAME;

	@Override
	public HederaFunctionality type() {
		return HederaFunctionality.ContractDelete;
	}

	@Override
	protected HapiContractDelete self() {
		return this;
	}

	public HapiContractDelete(String contract) {
		this.contract = contract;
	}

	public HapiContractDelete transferAccount(String to) {
		transferAccount = Optional.of(to);
		return this;
	}

	public HapiContractDelete transferAccountAliased(String to) {
		transferAccountRefType = ReferenceType.ALIAS_KEY_NAME;
		transferAccount = Optional.of(to);
		return this;
	}

	public HapiContractDelete transferContract(String to) {
		transferContract = Optional.of(to);
		return this;
	}

	public HapiContractDelete purging() {
		shouldPurge = true;
		return this;
	}

	@Override
	protected long feeFor(HapiApiSpec spec, Transaction txn, int numPayerSigs) throws Throwable {
		return spec.fees().forActivityBasedOp(
				HederaFunctionality.ContractDelete,
				scFees::getContractDeleteTxFeeMatrices, txn, numPayerSigs);
	}

	@Override
	protected Consumer<TransactionBody.Builder> opBodyDef(HapiApiSpec spec) throws Throwable {
		ContractDeleteTransactionBody opBody = spec
				.txns()
				.<ContractDeleteTransactionBody, ContractDeleteTransactionBody.Builder>body(
						ContractDeleteTransactionBody.class, builder -> {
							builder.setContractID(TxnUtils.asContractId(contract, spec));
							transferContract.ifPresent(c ->
									builder.setTransferContractID(TxnUtils.asContractId(c, spec)));
							transferAccount.ifPresent(a ->
									builder.setTransferAccountID(spec.registry().getAccountID(a)));
							transferAccount.ifPresent(a -> {
										if (transferAccountRefType == ReferenceType.ALIAS_KEY_NAME) {
											builder.setTransferAccountID(asIdForKeyLookUp(a, spec));
										} else {
											builder.setTransferAccountID(asId(a, spec));
										}
									}
							);
						}
				);
		return builder -> builder.setContractDeleteInstance(opBody);
	}

	@Override
	protected void updateStateOf(HapiApiSpec spec) throws Throwable {
		if (actualStatus != SUCCESS) {
			return;
		}
		if (shouldPurge) {
			if (spec.registry().hasAccountId(contract)) {
				spec.registry().removeAccount(contract);
			}
			spec.registry().removeKey(contract);
			spec.registry().removeContractId(contract);
			spec.registry().removeContractInfo(contract);
			if (spec.registry().hasContractChoice(contract)) {
				SupportedContract choice = spec.registry().getContractChoice(contract);
				AtomicInteger tag = new AtomicInteger();
				choice.getCallDetails().forEach(detail -> {
					spec.registry().removeActionableCall(contract + "-" + tag.getAndIncrement());
				});
				choice.getLocalCallDetails().forEach(detail -> {
					spec.registry().removeActionableLocalCall(contract + "-" + tag.getAndIncrement());
				});
				spec.registry().removeContractChoice(contract);
			}
		}
	}

	@Override
	protected Function<Transaction, TransactionResponse> callToUse(HapiApiSpec spec) {
		return spec.clients().getScSvcStub(targetNodeFor(spec), useTls)::deleteContract;
	}

	@Override
	protected List<Function<HapiApiSpec, Key>> defaultSigners() {
		return Arrays.asList(
				spec -> spec.registry().getKey(effectivePayer(spec)),
				spec -> spec.registry().getKey(contract));
	}

	@Override
	protected MoreObjects.ToStringHelper toStringHelper() {
		MoreObjects.ToStringHelper helper = super.toStringHelper()
				.add("contract", contract);
		transferAccount.ifPresent(a -> helper.add("transferAccount", a));
		transferContract.ifPresent(c -> helper.add("transferContract", c));
		Optional.ofNullable(lastReceipt)
				.ifPresent(receipt -> helper.add("deleted", receipt.getContractID().getContractNum()));
		return helper;
	}
}
