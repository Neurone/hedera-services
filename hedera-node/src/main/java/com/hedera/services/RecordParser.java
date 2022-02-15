package com.hedera.services;

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

import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import org.apache.commons.lang3.ArrayUtils;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

public class RecordParser {
	private static final byte TYPE_PREV_HASH = 1;
	private static final byte TYPE_RECORD = 2;

	private static final MessageDigest metaDigest;
	private static final MessageDigest contentDigest;

	static {
		try {
			metaDigest = MessageDigest.getInstance("SHA-384");
			contentDigest = MessageDigest.getInstance("SHA-384");
		} catch (Exception fatal) {
			throw new IllegalStateException("Cannot initialize digests!", fatal);
		}
	}

	public static RecordFile parseFrom(File file) {
		FileInputStream stream = null;
		List<TxnHistory> histories = new LinkedList<>();
		byte[] prevHash = null;

		if (!file.exists()) {
			throw new IllegalArgumentException("No such file - " + file);
		}

		try {
			stream = new FileInputStream(file);
			DataInputStream dis = new DataInputStream(stream);

			prevHash = new byte[48];
			int record_format_version = dis.readInt();
			int version = dis.readInt();

//			System.out.println("File is: " + file);
//			System.out.println("  -> Record format v" + record_format_version);
//			System.out.println("  -> HAPI protocol v" + version);

			while (dis.available() != 0) {
				try {
					byte typeDelimiter = dis.readByte();

					switch (typeDelimiter) {
						case TYPE_PREV_HASH:
							dis.read(prevHash);
							break;
						case TYPE_RECORD:
							int n = dis.readInt();
							byte[] buffer = new byte[n];
							dis.readFully(buffer);
							Transaction signedTxn = Transaction.parseFrom(buffer);

							n = dis.readInt();
							buffer = new byte[n];
							dis.readFully(buffer);
							TransactionRecord record = TransactionRecord.parseFrom(buffer);

							histories.add(new TxnHistory(signedTxn, record));

							break;
						default:
							System.out.println("Record file '"
									+ file + "' contained unrecognized delimiter |" + typeDelimiter + "|");
					}
				} catch (Exception e) {
					System.out.println("Problem parsing record file '" + file + "'");
					break;
				}
			}

			metaDigest.reset();
			contentDigest.reset();
			byte[] everything = Files.readAllBytes(file.toPath());
			byte[] preface = Arrays.copyOfRange(everything, 0, 57);
			byte[] bodyHash = contentDigest.digest(Arrays.copyOfRange(everything, 57, everything.length));
			metaDigest.update(ArrayUtils.addAll(preface, bodyHash));
		} catch (FileNotFoundException e) {
			throw new IllegalStateException();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		} finally {
			try {
				if (stream != null) {
					stream.close();
				}
			} catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}

		return new RecordFile(prevHash, metaDigest.digest(), histories);
	}

	public static class RecordFile {
		private final byte[] prevHash;
		private final byte[] thisHash;
		private final List<TxnHistory> txnHistories;

		RecordFile(
				byte[] prevHash,
				byte[] thisHash,
				List<TxnHistory> txnHistories
		) {
			this.prevHash = prevHash;
			this.thisHash = thisHash;
			this.txnHistories = txnHistories;
		}

		public byte[] getPrevHash() {
			return prevHash;
		}

		public byte[] getThisHash() {
			return thisHash;
		}

		public List<TxnHistory> getTxnHistories() {
			return txnHistories;
		}
	}

	public static class TxnHistory {
		private final Transaction signedTxn;
		private final TransactionRecord record;

		public TxnHistory(Transaction signedTxn, TransactionRecord record) {
			this.signedTxn = signedTxn;
			this.record = record;
		}

		public Transaction getSignedTxn() {
			return signedTxn;
		}

		public TransactionRecord getRecord() {
			return record;
		}
	}
}