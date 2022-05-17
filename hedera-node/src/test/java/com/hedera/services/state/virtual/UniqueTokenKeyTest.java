package com.hedera.services.state.virtual;

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

import com.swirlds.common.io.streams.SerializableDataInputStream;
import com.swirlds.common.io.streams.SerializableDataOutputStream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class UniqueTokenKeyTest {

	@Test
	void constructedKey_returnsValue() {
		UniqueTokenKey key = new UniqueTokenKey(123L, 456L);
		assertThat(key.getNum()).isEqualTo(123L);
		assertThat(key.getTokenSerial()).isEqualTo(456L);
	}

	@Test
	void serializing_withDifferentTokenNums_yieldSmallerBufferPositionForLeadingZeros() throws IOException {
		UniqueTokenKey key1 = new UniqueTokenKey(0, 0);       // 1 byte
		UniqueTokenKey key2 = new UniqueTokenKey(0, 0xFF);    // 2 bytes
		UniqueTokenKey key3 = new UniqueTokenKey(0xFFFF, 0);  // 3 bytes
		UniqueTokenKey key4 = new UniqueTokenKey(0xFFFF_FFFF_FFFF_FFFFL, 0xFFFF_FFFF_FFFF_FFFFL); // 17 bytes

		ByteBuffer buffer1 = ByteBuffer.wrap(new byte[UniqueTokenKey.ESTIMATED_SIZE_BYTES]);
		ByteBuffer buffer2 = ByteBuffer.wrap(new byte[UniqueTokenKey.ESTIMATED_SIZE_BYTES]);
		ByteBuffer buffer3 = ByteBuffer.wrap(new byte[UniqueTokenKey.ESTIMATED_SIZE_BYTES]);
		ByteBuffer buffer4 = ByteBuffer.wrap(new byte[UniqueTokenKey.ESTIMATED_SIZE_BYTES]);

		key1.serialize(buffer1);
		key2.serialize(buffer2);
		key3.serialize(buffer3);
		key4.serialize(buffer4);

		assertThat(buffer1.position()).isLessThan(buffer2.position());
		assertThat(buffer2.position()).isLessThan(buffer3.position());
		assertThat(buffer3.position()).isLessThan(buffer4.position());
	}

	private static ByteBuffer serializeToByteBuffer(long num, long serial) throws IOException {
		ByteBuffer buffer = ByteBuffer.wrap(new byte[UniqueTokenKey.ESTIMATED_SIZE_BYTES]);
		new UniqueTokenKey(num, serial).serialize(buffer);
		return buffer.rewind();
	}

	private static UniqueTokenKey checkSerializeAndDeserializeByteBuffer(long num, long serial) throws IOException {
		UniqueTokenKey key = new UniqueTokenKey();
		key.deserialize(serializeToByteBuffer(num, serial), UniqueTokenKey.CURRENT_VERSION);
		assertThat(key.getNum()).isEqualTo(num);
		assertThat(key.getTokenSerial()).isEqualTo(serial);
		return key;
	}

	@Test
	void deserializingByteBuffer_whenCurrentVersion_restoresValueAndRegeneratesHash() throws IOException {
		List<Long> valuesToTest = List.of(0L, 0xFFL, 0xFFFFL, 0xFF_FFFFL, 0xFFFF_FFFFL, 0xFF_FFFF_FFFFL,
				0xFFFF_FFFF_FFFFL, 0xFF_FFFF_FFFF_FFFFL, 0xFFFF_FFFF_FFFF_FFFFL);
		List<Integer> hashCodes = new ArrayList<>();
		for (long num : valuesToTest) {
			for (long serial : valuesToTest) {
				UniqueTokenKey key = checkSerializeAndDeserializeByteBuffer(num, serial);
				hashCodes.add(key.hashCode());
			}
		}

		// Also confirm that the hash codes are mostly unique.
		assertThat(new HashSet<>(hashCodes).size()).isAtLeast((int) (0.7 * hashCodes.size()));
	}

	private static SerializableDataInputStream serializeToStream(long num, long serial) throws IOException {
		ByteArrayOutputStream byteOutputStream = new ByteArrayOutputStream(UniqueTokenKey.ESTIMATED_SIZE_BYTES);
		SerializableDataOutputStream outputStream = new SerializableDataOutputStream(byteOutputStream);
		new UniqueTokenKey(num, serial).serialize(outputStream);

		ByteArrayInputStream inputStream = new ByteArrayInputStream(byteOutputStream.toByteArray());
		return new SerializableDataInputStream(inputStream);
	}

	private static UniqueTokenKey checkSerializeAndDeserializeStream(long num, long serial) throws IOException {
		UniqueTokenKey key = new UniqueTokenKey();
		key.deserialize(serializeToStream(num, serial), UniqueTokenKey.CURRENT_VERSION);
		assertThat(key.getNum()).isEqualTo(num);
		assertThat(key.getTokenSerial()).isEqualTo(serial);
		return key;
	}

	@Test
	void deserializingStream_whenCurrentVersion_restoresValueAndRegeneratesHash() throws IOException {
		List<Long> valuesToTest = List.of(0L, 0xFFL, 0xFFFFL, 0xFF_FFFFL, 0xFFFF_FFFFL, 0xFF_FFFF_FFFFL,
				0xFFFF_FFFF_FFFFL, 0xFF_FFFF_FFFF_FFFFL, 0xFFFF_FFFF_FFFF_FFFFL);
		List<Integer> hashCodes = new ArrayList<>();
		for (long num : valuesToTest) {
			for (long serial : valuesToTest) {
				UniqueTokenKey key = checkSerializeAndDeserializeStream(num, serial);
				hashCodes.add(key.hashCode());
			}
		}

		// Also confirm that the hash codes are mostly unique.
		assertThat(new HashSet<>(hashCodes).size()).isAtLeast((int) (0.7 * hashCodes.size()));
	}

	@Test
	void deserializing_withWrongVersion_throwsException() throws IOException {
		ByteBuffer byteBuffer = serializeToByteBuffer(0xFFL, 0xFFL);
		SerializableDataInputStream inputStream  = serializeToStream(0xFFL, 0xFFL);

		UniqueTokenKey key = new UniqueTokenKey();
		Assertions.assertThrows(AssertionError.class,
				() -> key.deserialize(byteBuffer, UniqueTokenKey.CURRENT_VERSION + 1));

		Assertions.assertThrows(AssertionError.class,
				() -> key.deserialize(inputStream, UniqueTokenKey.CURRENT_VERSION + 1));
	}

	@Test
	void equals_whenNull_isFalse() {
		UniqueTokenKey key = new UniqueTokenKey();
		assertThat(key.equals(null)).isFalse();
	}

	@Test
	void equals_whenDifferentType_isFalse() {
		UniqueTokenKey key = new UniqueTokenKey();
		assertThat(key.equals(123L)).isFalse();
	}

	@Test
	void equals_whenSameType_matchesContentCorrectly() {
		UniqueTokenKey key = new UniqueTokenKey(123L, 456L);
		assertThat(key.equals(new UniqueTokenKey(123L, 456L))).isTrue();
		assertThat(key.equals(new UniqueTokenKey(456L, 123L))).isFalse();
		assertThat(key.equals(new UniqueTokenKey(123L, 333L))).isFalse();
		assertThat(key.equals(new UniqueTokenKey())).isFalse();
	}

	@Test
	void comparing_comparesProperly() {
		UniqueTokenKey key1 = new UniqueTokenKey(123L, 789L);
		UniqueTokenKey key2 = new UniqueTokenKey(456L, 789L);
		UniqueTokenKey key3 = new UniqueTokenKey(123L, 456L);
		UniqueTokenKey key4 = new UniqueTokenKey(123L, 456L);

		// Check equality works
		assertThat(key1).isEqualTo(key1);   // same instance
		assertThat(key3).isEqualTo(key4);   // differing instances

		// Check less-than result is valid
		assertThat(key1).isLessThan(key2);  // due to num field
		assertThat(key3).isLessThan(key1);  // due to serial field

		// Check greater-than result is valid
		assertThat(key2).isGreaterThan(key1);  // due to num field
		assertThat(key1).isGreaterThan(key3);  // due to serial field

		// In case above isEqualTo is a reference comparison, we also do the following to confirm
		assertThat(key1.compareTo(key1)).isEqualTo(0);    // same instance
		assertThat(key3.compareTo(key4)).isEqualTo(0);    // differing instances
	}

	private static ByteBuffer asByteBuffer(int value) {
		return ByteBuffer.wrap(new byte[] { (byte) value});
	}

	@Test
	void deserializeKeySize_withVariousPackedLengths_returnsTheCorrectLengths() {
		assertThat(UniqueTokenKey.deserializeKeySize(asByteBuffer(0))).isEqualTo(1);
		assertThat(UniqueTokenKey.deserializeKeySize(asByteBuffer(0x8))).isEqualTo(9);
		assertThat(UniqueTokenKey.deserializeKeySize(asByteBuffer(0x80))).isEqualTo(9);
		assertThat(UniqueTokenKey.deserializeKeySize(asByteBuffer(0x34))).isEqualTo(8);
		assertThat(UniqueTokenKey.deserializeKeySize(asByteBuffer(0x88))).isEqualTo(17);
	}

	@Test
	void getVersion_isCurrent() {
		UniqueTokenKey key1 = new UniqueTokenKey();
		// This will fail if the version number changes and force user to update the version number here.
		assertThat(key1.getVersion()).isEqualTo(1);

		// Make sure current version is above the minimum supported version.
		assertThat(key1.getVersion()).isAtLeast(key1.getMinimumSupportedVersion());
	}

	@Test
	void getClassId_isExpected() {
		// Make sure the class id isn't accidentally changed.
		UniqueTokenKey key1 = new UniqueTokenKey();
		assertThat(key1.getClassId()).isEqualTo(0x17f77b311f6L);
	}

	@Test
	void toString_shouldContain_tokenValue() {
		assertThat(new UniqueTokenKey(123L, 789L).toString()).contains("123");
		assertThat(new UniqueTokenKey(123L, 789L).toString()).contains("789");
		assertThat(new UniqueTokenKey(456L, 789L).toString()).contains("456");
		assertThat(new UniqueTokenKey(456L, 789L).toString()).contains("789");
	}
}