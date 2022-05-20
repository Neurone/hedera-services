package com.hedera.services.ledger;

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

import com.hedera.services.ledger.properties.BeanProperty;

/**
 * Defines an observer to change sets being committed to a {@link TransactionalLedger}. Such an observer
 * might collect information for a record or assert validity of the pending changes, for example.
 *
 * @param <K>
 * 		the ledger id type
 * @param <A>
 * 		the account type
 * @param <P>
 * 		the enumerable family of account properties
 */
@FunctionalInterface
public interface CommitInterceptor<K, A, P extends Enum<P> & BeanProperty<A>> {
	/**
	 * Accepts a pending change set to preview before it is committed to the ledger. Builds list of first order
	 * reward situations if staking is enabled.
	 *
	 * @param pendingChanges
	 * 		the pending change set
	 * @throws IllegalStateException
	 * 		if these changes are invalid
	 */
	void preview(EntityChangeSet<K, A, P> pendingChanges);

	/**
	 * Returns whether this interceptor completes the pending removals in the previewed change set.
	 * (If true, the {@link TransactionalLedger} will skip calling remove itself.)
	 *
	 * @return if pending removals are done as a side effect of preview
	 */
	default boolean completesPendingRemovals() {
		return false;
	}
}
