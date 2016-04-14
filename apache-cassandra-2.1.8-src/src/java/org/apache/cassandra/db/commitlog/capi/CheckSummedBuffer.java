package org.apache.cassandra.db.commitlog.capi;

import java.nio.ByteBuffer;
import java.util.zip.Checksum;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.io.util.DataOutputByteBuffer;
import org.apache.cassandra.utils.PureJavaCrc32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.research.capiblock.CapiBlockDevice;

public class CheckSummedBuffer {
	static final Logger logger = LoggerFactory.getLogger(CheckSummedBuffer.class);
	private final ByteBuffer buffer;
	private final DataOutputByteBuffer bufferStream;
	private final PureJavaCrc32 checksum = new PureJavaCrc32();
	public CheckSummedBuffer(){
		buffer = ByteBuffer.allocateDirect(DatabaseDescriptor.getFlashCommitLogBufferSizeInBlocks() * CapiBlockDevice.BLOCK_SIZE);
		bufferStream = new DataOutputByteBuffer(buffer);
	}
	public void clear() {
		checksum.reset();
		buffer.clear();		
	}
	public DataOutputByteBuffer getStream() {
		return bufferStream;
	}
	public Checksum getChecksum(){
		return checksum;
	}
	public ByteBuffer  getBuffer(){
		return buffer;
	}
	public Checksum calculate(int offset,int length){
		checksum.reset();
		checksum.update(buffer, offset, length);
		return checksum;
	}
}
