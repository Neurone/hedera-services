package com.hedera.services.stream;

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

import com.swirlds.common.stream.StreamType;

public interface HederaStreamType extends StreamType {
	String RECORD_DESCRIPTION = "records";
	String RECORD_EXTENSION = "rcd";
	String RECORD_SIG_EXTENSION = "rcd_sig";
	byte[] RECORD_SIG_FILE_HEADER = new byte[] { 5 };

	/**
	 * {@inheritDoc}
	 */
	@Override
	default String getDescription() {
		return RECORD_DESCRIPTION;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	default String getExtension() {
		return RECORD_EXTENSION;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	default String getSigExtension() {
		return RECORD_SIG_EXTENSION;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	default byte[] getSigFileHeader() {
		return RECORD_SIG_FILE_HEADER;
	}
}