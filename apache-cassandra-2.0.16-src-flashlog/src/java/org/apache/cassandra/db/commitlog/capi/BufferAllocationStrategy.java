package org.apache.cassandra.db.commitlog.capi;

import java.nio.ByteBuffer;

public interface BufferAllocationStrategy {
	void free(CheckSummedBuffer buf);
	CheckSummedBuffer poll(long requiredBlocks);
}
