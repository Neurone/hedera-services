package com.hedera.services.state.submerkle;

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

import com.hedera.test.serde.SelfSerializableDataTest;
import com.hedera.test.utils.SeededPropertySource;
import com.swirlds.common.io.streams.SerializableDataInputStream;
import org.bouncycastle.util.Arrays;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static com.hedera.services.state.submerkle.ExpirableTxnRecord.RELEASE_0230_VERSION;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ExpirableTxnRecordSerdeTest extends SelfSerializableDataTest<ExpirableTxnRecord> {
	public static final int NUM_TEST_CASES = 4 * MIN_TEST_CASES_PER_VERSION;

	@Override
	protected Class<ExpirableTxnRecord> getType() {
		return ExpirableTxnRecord.class;
	}

	@Override
	protected int getNumTestCasesFor(int version) {
		return version == RELEASE_0230_VERSION ? MIN_TEST_CASES_PER_VERSION : NUM_TEST_CASES;
	}

	@Override
	protected ExpirableTxnRecord getExpectedObject(final SeededPropertySource propertySource) {
		return propertySource.nextRecord();
	}
}
