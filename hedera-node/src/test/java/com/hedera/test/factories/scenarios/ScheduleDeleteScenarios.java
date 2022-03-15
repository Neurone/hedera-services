package com.hedera.test.factories.scenarios;

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

import com.hedera.services.utils.accessors.UserTxnAccessor;

import static com.hedera.test.factories.txns.PlatformTxnFactory.from;
import static com.hedera.test.factories.txns.ScheduleDeleteFactory.newSignedScheduleDelete;

public enum ScheduleDeleteScenarios implements TxnHandlingScenario {
	SCHEDULE_DELETE_WITH_KNOWN_SCHEDULE {
		@Override
		public UserTxnAccessor platformTxn() throws Throwable {
			return UserTxnAccessor.from(from(
					newSignedScheduleDelete()
							.deleting(KNOWN_SCHEDULE_WITH_ADMIN)
							.get()
			), aliasManager());
		}
	},
	SCHEDULE_DELETE_WITH_MISSING_SCHEDULE {
		@Override
		public UserTxnAccessor platformTxn() throws Throwable {
			return UserTxnAccessor.from(from(
					newSignedScheduleDelete()
							.deleting(UNKNOWN_SCHEDULE)
							.get()
			), aliasManager());
		}
	},
	SCHEDULE_DELETE_WITH_MISSING_SCHEDULE_ADMIN_KEY {
		@Override
		public UserTxnAccessor platformTxn() throws Throwable {
			return UserTxnAccessor.from(from(
					newSignedScheduleDelete()
							.deleting(KNOWN_SCHEDULE_IMMUTABLE)
							.get()
			), aliasManager());
		}
	}
}
