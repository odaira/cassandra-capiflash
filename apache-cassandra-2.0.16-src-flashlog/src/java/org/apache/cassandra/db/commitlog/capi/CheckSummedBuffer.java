package org.apache.cassandra.db.commitlog.capi;

import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.util.zip.Checksum;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.io.util.ByteBufferOutputStream;
import org.apache.cassandra.io.util.ChecksummedOutputStream;
import org.apache.cassandra.utils.PureJavaCrc32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CheckSummedBuffer {
	static final Logger logger = LoggerFactory.getLogger(CheckSummedBuffer.class);
	private final ByteBuffer buffer;
	private final DataOutputStream bufferStream;
	private final Checksum checksum = new PureJavaCrc32();
	public CheckSummedBuffer(){
		buffer = ByteBuffer.allocateDirect(DatabaseDescriptor.getFlashCommitLogThreadBufferSizeinMB() * 1024 * 1024);
		bufferStream = new DataOutputStream(new ChecksummedOutputStream(
				new ByteBufferOutputStream(buffer), checksum));
	}
	public void clear() {
		checksum.reset();
		buffer.clear();		
	}
	public DataOutputStream getStream() {
		return bufferStream;
	}
	public Checksum getChecksum(){
		return checksum;
	}
	public ByteBuffer  getBuffer(){
		return buffer;
	}
}
