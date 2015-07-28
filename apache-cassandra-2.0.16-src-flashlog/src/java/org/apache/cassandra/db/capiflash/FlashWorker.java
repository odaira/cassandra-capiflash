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

public class FlashWorker implements Callable {
	static final Logger logger = LoggerFactory.getLogger(FlashWorker.class);
	private final ByteBuffer buffer;
	private final DataOutputStream bufferStream;
	private Chunk ref = null;
	
	private final Checksum checksum = new PureJavaCrc32();
	private RowMutation rm = null;
	private long startBlock = 0;
	private int totalSize = 0;
	private long requiredBlocks = 0;
	private long segmentID=0;

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
			bufferStream.writeLong(segmentID);
			bufferStream.writeInt(totalSize);
			buffer.putLong(checksum.getValue());
			//enable trace here
			RowMutation.serializer.serialize(rm, bufferStream,
					MessagingService.current_version);
			buffer.putLong(checksum.getValue());
			ref.writeBlock(startBlock, requiredBlocks, buffer);
			buffer.clear();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return this;
	}

	public void setMessage(RowMutation rm) {
		this.rm = rm;
	}

	public void setSize(int totalSize, long requiredBlocks) {
		this.totalSize = totalSize;
		this.requiredBlocks = requiredBlocks;
	}

	public void setStartBlock(long startBlock) {
		this.startBlock = startBlock;
	}

	public void setSegmentID(long id) {
		this.segmentID=id;		
	}

}