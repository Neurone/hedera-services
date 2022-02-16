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
import com.hedera.services.txns.contract.helpers.DeletionLogic;
import com.hedera.services.utils.accessors.ContractDeleteAccessor;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_DELETED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;
import static org.mockito.Mockito.verify;

class ContractDeleteTransitionLogicTest {
	final private AccountID payer = AccountID.newBuilder().setAccountNum(1_234L).build();
	final private ContractID target = ContractID.newBuilder().setContractNum(9_999L).build();
	final private AccountID transfer = AccountID.newBuilder().setAccountNum(4_321L).build();

	private Instant consensusTime;
	private DeletionLogic deletionLogic;
	private TransactionBody contractDeleteTxn;
	private TransactionContext txnCtx;
	private ContractDeleteAccessor accessor;
	ContractDeleteTransitionLogic subject;

	@BeforeEach
	private void setup() {
		consensusTime = Instant.now();

		deletionLogic = mock(DeletionLogic.class);
		txnCtx = mock(TransactionContext.class);
		given(txnCtx.consensusTime()).willReturn(consensusTime);
		accessor = mock(ContractDeleteAccessor.class);

		subject = new ContractDeleteTransitionLogic(deletionLogic, txnCtx);
	}

	@Test
	void happyPathWorksWithDelegate() {
		givenValidTxnCtx();
		final var op = contractDeleteTxn.getContractDeleteInstance();
		given(accessor.txnBody()).willReturn(op);
		final var tbd = op.getContractID();
		given(deletionLogic.performFor(accessor)).willReturn(tbd);

		subject.doStateTransition();

		verify(deletionLogic).performFor(accessor);
		verify(txnCtx).setTargetedContract(tbd);
	}

	@Test
	void hasCorrectApplicability() {
		givenValidTxnCtx();

		// expect:
		assertTrue(subject.applicability().test(contractDeleteTxn));
		assertFalse(subject.applicability().test(TransactionBody.getDefaultInstance()));
	}

	@Test
	void acceptsOkSyntax() {
		givenValidTxnCtx();

		given(deletionLogic.precheckValidity(accessor)).willReturn(CONTRACT_DELETED);

		assertEquals(CONTRACT_DELETED, subject.validateSemantics(accessor));
	}

	private void givenValidTxnCtx() {
		var op = TransactionBody.newBuilder()
				.setTransactionID(ourTxnId())
				.setContractDeleteInstance(
						ContractDeleteTransactionBody.newBuilder()
								.setTransferAccountID(transfer)
								.setContractID(target));
		contractDeleteTxn = op.build();
		given(accessor.getTxn()).willReturn(contractDeleteTxn);
		given(txnCtx.accessor()).willReturn(accessor);
	}

	private TransactionID ourTxnId() {
		return TransactionID.newBuilder()
				.setAccountID(payer)
				.setTransactionValidStart(
						Timestamp.newBuilder().setSeconds(consensusTime.getEpochSecond()))
				.build();
	}
}
