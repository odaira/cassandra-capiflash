package org.apache.cassandra.db.capiflash;

import java.io.IOException;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.cassandra.db.RowMutation;
import org.apache.cassandra.db.commitlog.ReplayPosition;
import org.apache.cassandra.net.MessagingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.Futures;
import com.ibm.research.capiblock.CapiBlockDevice;
import com.ibm.research.capiblock.Chunk;

public class FlashCommitLog {
	// TUNABLES
	static int BLOCK_SIZE = 4096;
	static long START_OFFSET = 300000000L;
	static long DATA_OFFSET = START_OFFSET + FlashSegmentManager.MAX_SEGMENTS;
	static String[] DEVICES = { "/dev/sg7", "/dev/sg9" };

	final int flashThreads = 128;
	final int bufferSizeinMB = 1;

	static final Logger logger = LoggerFactory.getLogger(FlashCommitLog.class);
	public static final FlashCommitLog instance = new FlashCommitLog();

	final CapiBlockDevice dev = CapiBlockDevice.getInstance();
	final BlockingQueue<Future<FlashWorker>> queue = new LinkedBlockingQueue<Future<FlashWorker>>(
			flashThreads);
	final ThreadPoolExecutor exec = (ThreadPoolExecutor) Executors
			.newFixedThreadPool(flashThreads);
	final ExecutorCompletionService<FlashWorker> completionService = new ExecutorCompletionService<FlashWorker>(
			exec, queue);

	// Segments
	public FlashSegmentManager fsm;

	private FlashCommitLog() {
		logger.debug("Commitlog Created");
		try {
			Chunk chunk = dev.openChunk(DEVICES[0]);
			fsm = new FlashSegmentManager(chunk);
			for (int i = 0; i < flashThreads; i++) {
				Chunk chunkl = dev.openChunk(DEVICES[i % 2]);
				queue.add(Futures.immediateFuture(new FlashWorker(chunkl,
						bufferSizeinMB)));
			}
		} catch (IOException e1) {
			e1.printStackTrace();
		}
	}

	/**
	 * Appends row mutation to CommitLog
	 * 
	 * @param
	 */
	public void add(RowMutation rm) {
		try {
			FlashWorker r = completionService.take().get();
			r.setMessage(rm);
			long totalSize = RowMutation.serializer.serializedSize(rm,
					MessagingService.current_version) + 28;// TODO Segment ID ?
			long requiredBlocks = getBlockCount(totalSize);
			r.setSize((int) totalSize, requiredBlocks);
			FlashSegment target = fsm.ask(requiredBlocks);
			synchronized (target) {// TODO this is an obvious bottleneck
				target.markDirty(rm, target.getContext());//TODO
				r.setStartBlock(target.getandAddPosition(requiredBlocks));// TODO
				r.setSegmentID(target.getID());
			}
			completionService.submit(r);
		} catch (InterruptedException | ExecutionException e) {
			e.printStackTrace();
		}
	}

	public void discardCompletedSegments(UUID cfId, final ReplayPosition context) {
		synchronized (instance) {// TODO FIX
			int x;
			while ((x = exec.getActiveCount()) != 0) {// TODO Fix
				logger.debug(x + " Waiting queued threads to finish!");
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			for (Iterator<FlashSegment> iter = fsm.getActiveSegments()
					.iterator(); iter.hasNext();) {
				FlashSegment segment = iter.next();
				segment.markClean(cfId, context);
				if (iter.hasNext()) {
					if (segment.isUnused()) {
						logger.debug("Commit log segment {} is unused "
								+ segment.physical_block_address);
						fsm.recycleSegment(segment);
					} else {
						logger.debug("Not safe to delete commit log segment {}; dirty is {} "
								+ segment.physical_block_address
								+ "  dirty:"
								+ segment.dirtyString());
					}
				} else {
					logger.debug("Not deleting active commitlog segment {} "
							+ segment.physical_block_address);
				}
				if (segment.contains(context)) {
					break;
				}
			}
		}
	}

	public Future<ReplayPosition> getContext() {
		synchronized (fsm.active) {// TODO
			return Futures.immediateFuture(fsm.active.getContext());
		}
	}

	public int getPendingTasks() {
		return flashThreads - queue.size();
	}

	static long getBlockCount(long size) {
		return (long) (Math.ceil((double) size / (FlashCommitLog.BLOCK_SIZE)));
	}

	public void recover() {
		FlashLogReplayer r = new FlashLogReplayer();
		try {
			r.recover(fsm);
		} catch (IOException e) {
			e.printStackTrace();
		}
		fsm.recycleAfterReplay();
		long count = r.blockForWrites();
		logger.debug("---->>>>> Log replay complete recycling segments.Replayed " + count + " records");
	}

	public void shutdownBlocking() {
		exec.shutdown();
		try {
			exec.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

}
