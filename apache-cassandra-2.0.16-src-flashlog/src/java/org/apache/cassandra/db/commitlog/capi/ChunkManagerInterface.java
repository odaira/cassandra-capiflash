package org.apache.cassandra.db.commitlog.capi;

public interface ChunkManagerInterface {
	void openChunks(int num_async);
	void write(long l, long m, CheckSummedBuffer buf);
	void closeChunks();
}
