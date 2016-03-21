package org.apache.cassandra.db.commitlog.capi;

public class PooledAllocationStrategy implements BufferAllocationStrategy {
	//Vector<ConcurrentLinkedQueue<CheckSummedBuffer>> bufferFreelist = new Vector<ConcurrentLinkedQueue<CheckSummedBuffer>>();
	@Override
	public CheckSummedBuffer poll(long requiredBlocks) {
		return null;
	}

	@Override
	public void free(CheckSummedBuffer buf) {

	}
}
