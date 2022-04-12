package com.hedera.services.bdd.suites.perf.token;

/*-
 * ‌
 * Hedera Services Test Clients
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

import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.suites.HapiApiSuite;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;

public class TokenAssociationLoadTest  extends HapiApiSuite {
	private static final Logger log = LogManager.getLogger(TokenAssociationLoadTest.class);

	private AtomicInteger maxTokens = new AtomicInteger(500);
	@Override
	protected Logger getResultsLogger() {
		return log;
	}

	@Override
	public List<HapiApiSpec> getSpecsInSuite() {
		return List.of(
				new HapiApiSpec[] {
						runTokenAssociationLoadTest(),
				}
		);
	}

	private HapiApiSpec runTokenAssociationLoadTest() {
		return HapiApiSpec.defaultHapiSpec("RunTokenAssociationLoadTest")
				.given(
						fileUpdate(APP_PROPERTIES)
								.payingWith(ADDRESS_BOOK_CONTROL)
								.overridingProps(Map.of(
										"accounts.limitTokenAssociations", "false",
										"tokens.maxPerAccount", "10",
										"tokens.maxRelsPerInfoQuery", "10"))
				)
				.when()
				.then(
						fileUpdate(APP_PROPERTIES)
								.payingWith(ADDRESS_BOOK_CONTROL)
								.overridingProps(Map.of(
										"accounts.limitTokenAssociations", "true",
										"tokens.maxPerAccount", "1000",
										"tokens.maxRelsPerInfoQuery", "1000"))
				);
	}
}
