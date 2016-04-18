package org.apache.cassandra.db.commitlog.capi;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ibm.research.capiblock.Chunk;

public class AsyncChunkManager extends ChunkManager {
	static final Logger logger = LoggerFactory.getLogger(FlashCommitLog.class);

	public AsyncChunkManager(int num_async) {
		logger.debug("[AsyncChunkManager - Devices =  "+DEVICES.length+","+num_async+"]");
		openChunks(num_async);
	}

	public AsyncChunkManager() {
		this(DatabaseDescriptor.getFlashCommitLogNumberOfAsyncCallsPerChunk());
	}

	@Override
	public void write(long startOffset, int num_blocks, CheckSummedBuffer buf) {
		Chunk cur = getNextChunk();
		Future<Long> future;
		try {
			future = cur.writeBlockAsync(startOffset,num_blocks,buf.getBuffer());
			future.get();
		} catch (IOException | InterruptedException | ExecutionException e ) {
			e.printStackTrace();
		} 
	}
}
