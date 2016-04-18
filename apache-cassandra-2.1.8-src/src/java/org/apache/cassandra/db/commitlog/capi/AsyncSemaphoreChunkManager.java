package org.apache.cassandra.db.commitlog.capi;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ibm.research.capiblock.Chunk;

public class AsyncSemaphoreChunkManager extends ChunkManager {
	static final Logger logger = LoggerFactory.getLogger(AsyncSemaphoreChunkManager.class);
	private final Semaphore semaphore = new Semaphore(DatabaseDescriptor.getFlashCommitlogNumberOfAsyncWrite(), true);

	public AsyncSemaphoreChunkManager(int num_async) {
		logger.debug("[AsyncSemaphoreChunkManager - Devices =  " + DEVICES.length + "," + num_async + "]");
		logger.debug("[AsyncSemaphoreChunkManager - Concurrent Chunk Writers =  "
				+ DatabaseDescriptor.getFlashCommitlogNumberOfAsyncWrite() + "Chunks:"
				+ DatabaseDescriptor.getFlashCommitLogNumberOfChunks());
		openChunks(num_async);
	}

	public AsyncSemaphoreChunkManager() {
		this(DatabaseDescriptor.getFlashCommitLogNumberOfAsyncCallsPerChunk());
	}

	public void write(long startOffset, int num_blocks, CheckSummedBuffer buf) {
		Chunk cur = getNextChunk();
		try {
			semaphore.acquireUninterruptibly();
			cur.writeBlockAsync(startOffset, num_blocks, buf.getBuffer()).get();
			semaphore.release();
		} catch (IOException | InterruptedException | ExecutionException e) {
			e.printStackTrace();
		}
	}
}
