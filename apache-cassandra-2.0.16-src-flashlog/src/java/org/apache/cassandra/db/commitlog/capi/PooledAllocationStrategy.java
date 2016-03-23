package org.apache.cassandra.db.commitlog.capi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PooledAllocationStrategy implements BufferAllocationStrategy {
	static final Logger logger = LoggerFactory.getLogger(PooledAllocationStrategy.class);
	//Vector<ConcurrentLinkedQueue<CheckSummedBuffer>> bufferFreelist = new Vector<ConcurrentLinkedQueue<CheckSummedBuffer>>();
	@Override
	public CheckSummedBuffer poll(long requiredBlocks) {
		return null;
	}

	@Override
	public void free(CheckSummedBuffer buf) {

	}
}
