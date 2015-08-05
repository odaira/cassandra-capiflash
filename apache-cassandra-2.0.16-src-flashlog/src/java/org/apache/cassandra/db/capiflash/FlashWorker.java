/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.db.capiflash;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Callable;
import java.util.zip.Checksum;

import org.apache.cassandra.db.ColumnFamily;
import org.apache.cassandra.db.RowMutation;
import org.apache.cassandra.io.util.ByteBufferOutputStream;
import org.apache.cassandra.io.util.ChecksummedOutputStream;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.utils.PureJavaCrc32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.research.capiblock.Chunk;

/**
 * @author bsendir
 * Re-Usable Callable used for writing to flash. 
 */
public class FlashWorker implements Callable {
	static final Logger logger = LoggerFactory.getLogger(FlashWorker.class);
	private final ByteBuffer buffer;
	private final DataOutputStream bufferStream;
	private Chunk ref = null;

	private final Checksum checksum = new PureJavaCrc32();
	private RowMutation rm = null;
	private FlashRecordKeeper info;

	public FlashWorker(Chunk chunk, int nsegmentSizeinMB) {
		buffer = ByteBuffer.allocateDirect(nsegmentSizeinMB * 1024 * 1024);
		bufferStream = new DataOutputStream(new ChecksummedOutputStream(
				new ByteBufferOutputStream(buffer), checksum));
		ref = chunk;
	}

	@Override
	public Callable call() {
		try {
			checksum.reset();
			bufferStream.writeLong(info.getSegmentID());
			bufferStream.writeInt(info.getTotalSize());
			buffer.putLong(checksum.getValue());
			RowMutation.serializer.serialize(rm, bufferStream,
					MessagingService.current_version);
			buffer.putLong(checksum.getValue());
			//logger.debug("Writing to "+info.getStartBlock()+"-->"+info.getRequiredBlocks());
			ref.writeBlock(info.getStartBlock(), info.getRequiredBlocks(),
					buffer);
			buffer.clear();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return this;
	}

	public void setMessage(RowMutation rm) {
		this.rm = rm;
	}

	public void setOffset(FlashRecordKeeper adder) {
		info = adder;
	}

}