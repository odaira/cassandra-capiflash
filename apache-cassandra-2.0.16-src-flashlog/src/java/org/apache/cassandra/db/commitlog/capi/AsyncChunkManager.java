package org.apache.cassandra.db.commitlog.capi;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.commitlog.FlashCommitLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.research.capiblock.CapiBlockDevice;
import com.ibm.research.capiblock.Chunk;

public class AsyncChunkManager implements ChunkManagerInterface {
	static final Logger logger = LoggerFactory.getLogger(FlashCommitLog.class);
	static String[] DEVICES = DatabaseDescriptor.getFlashCommitLogDevices();
	final CapiBlockDevice dev = CapiBlockDevice.getInstance();
	final Chunk chunks[];
	final AtomicInteger nextChunk = new AtomicInteger(0);

	public AsyncChunkManager(int num_async) {
		chunks = new Chunk[DatabaseDescriptor.getFlashCommitLogNumberOfChunks()];
		openChunks(num_async);
	}

	public AsyncChunkManager() {
		this(DatabaseDescriptor.getFlashCommitLogNumberOfAsyncCallsPerChunk());
	}

	@Override
	public void openChunks(int num_async) {
		for (int i = 0; i < chunks.length; i++) {
			try {
				if (num_async == 0) {
					// let the device decide max num of requests
					chunks[i] = dev.openChunk(DEVICES[i % DEVICES.length]);
				} else {
					// user defined max requests per chunk
					chunks[i] = dev.openChunk(DEVICES[i % DEVICES.length], num_async);

				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	@Override
	public void write(long startOffset, long num_blocks, CheckSummedBuffer buf) {
		Chunk cur = getNextChunk();
		Future<Long> future;
		try {
			future = cur.writeBlockAsync(startOffset,num_blocks,buf.getBuffer());
			future.get();
		} catch (IOException | InterruptedException | ExecutionException e ) {
			e.printStackTrace();
		} 
	}

	@Override
	public void closeChunks() {
		for (int i = 0; i < chunks.length; i++) {
			try {
				chunks[i].close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	private Chunk getNextChunk() {
		return chunks[Math.abs(nextChunk.getAndIncrement() % chunks.length)];
	}
}
