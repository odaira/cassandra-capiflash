/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.db.capiflash;

import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.cassandra.config.Schema;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.Keyspace;
import org.apache.cassandra.db.Mutation;
import org.apache.cassandra.db.commitlog.CommitLogSegment;
import org.apache.cassandra.db.commitlog.ReplayPosition;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.utils.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.ibm.research.capiblock.CapiBlockDevice;
import com.ibm.research.capiblock.Chunk;

/**
 * @author bsendir
 *
 */
public class FlashCommitLog {
	// TUNABLES
	static int BLOCK_SIZE = 4096;
	static long START_OFFSET = 300000000L; // Bookkeeping starts here
	static long DATA_OFFSET = START_OFFSET + FlashSegmentManager.MAX_SEGMENTS; // Commitlog
																				// starts
																				// here
	static String[] DEVICES = { "/dev/sg7", "/dev/sg9" };
	final int flashThreads = 128; // Number of workers to add on commitlog each
									// worker serialize&writeblock
	final int bufferSizeinMB = 1; // Size of Directly allocated buffer in
									// workers

	static final Logger logger = LoggerFactory.getLogger(FlashCommitLog.class);
	public static final FlashCommitLog instance = new FlashCommitLog();

	final CapiBlockDevice dev = CapiBlockDevice.getInstance();
	// final BlockingQueue<Future<FlashWorker>> queue = new
	// LinkedBlockingQueue<Future<FlashWorker>>(
	// flashThreads);// Queue to reuse callables
	final BlockingQueue<FlashWorker> queue = new LinkedBlockingQueue<FlashWorker>(
			flashThreads);// Queue to reuse callables
	final ThreadPoolExecutor exec = (ThreadPoolExecutor) Executors
			.newFixedThreadPool(flashThreads);
	// final ExecutorCompletionService<FlashWorker> completionService = new
	// ExecutorCompletionService<FlashWorker>(
	// exec, queue);
	protected volatile FlashSegmentManager fsm;

	private FlashCommitLog() {
		try {
			Chunk chunk = dev.openChunk(DEVICES[0]);
			fsm = new FlashSegmentManager(chunk);
			for (int i = 0; i < flashThreads; i++) {// Pre allocate workers and
													// fake them into completion
													// service
				Chunk chunkl = dev.openChunk(DEVICES[i % 2]);
				queue.add(new FlashWorker(chunkl, bufferSizeinMB));// TODO might
																	// want to
																	// replace
																	// this
																	// with
																	// JAVA8
																	// Completedfuture
			}
		} catch (IOException e1) {
			e1.printStackTrace();
		}
	}

	/**
	 * Appends row mutation to CommitLog Called from
	 * org.apache.cassandra.db.Keyspace.java Line 348 adds each mutation to
	 * Commitlog
	 * 
	 * @param
	 */
	public ReplayPosition add(Mutation rm) {
		try {
			FlashWorker r = queue.take();
			r.setMessage(rm);
			long totalSize = Mutation.serializer.serializedSize(rm,
					MessagingService.current_version) + 28;
			long requiredBlocks = getBlockCount(totalSize);
			FlashRecordKeeper adder = fsm.allocate(requiredBlocks, rm);// TODO
																		// Synchronization
																		// bottleneck
			adder.setSize((int) totalSize);
			r.setOffset(adder);
			queue.add((FlashWorker) exec.submit(r).get());
			synchronized (queue) {
				if (queue.size() == flashThreads) {
					queue.notify();
				}
			}
			return adder.x;
		} catch (InterruptedException | ExecutionException e) {
			e.printStackTrace();
		}
		return null;
	}

	/**
	 * Modifies the per-CF dirty cursors of any commit log segments for the
	 * column family according to the position given. Recycles it if it is
	 * unused. Called from org.apache.cassandra.db.ColumnFamilyStore.java at the
	 * end of flush operation
	 * 
	 * @param cfId
	 *            the column family ID that was flushed
	 * @param context
	 *            the replay position of the flush
	 */
	public void discardCompletedSegments(UUID cfId, final ReplayPosition context) {
		// Keyspace has a static ReEntrantLock. It writeLocks globally.
		// So we only need to wait for writetoFlash to finish
		// TODO Find elegant way to achieve this to avoid polling
		// Go thru the active segment files, which are ordered oldest to
		// newest, marking the
		// flushed CF as clean, until we reach the segment file
		// containing the ReplayPosition passed
		// in the arguments. Any segments that become unused after they
		// are marked clean will be
		// recycled or discarded.

		synchronized (queue) {
			if (queue.size() != flashThreads) {
				try {
					long startTime = System.currentTimeMillis();
					queue.wait();
					long estimatedTime = System.currentTimeMillis() - startTime;
					logger.debug("------------------------>"
							+ " Wait miliseconds " + estimatedTime);

				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
		for (Iterator<FlashSegment> iter = fsm.getActiveSegments().iterator(); iter
				.hasNext();) {
			FlashSegment segment = iter.next();
			segment.markClean(cfId, context);
			// If the segment is no longer needed, and we have another
			// spare segment in the hopper
			// (to keep the last segment from getting discarded), pursue
			// either recycling or deleting
			// this segment file.
			if (iter.hasNext()) {
				if (segment.isUnused()) {
					logger.debug("Commit log segment {} is unused ",
							segment.physical_block_address);
					fsm.recycleSegment(segment);
				} else {
					logger.debug(
							"Not safe to delete commit log segment {}; dirty is {} ",
							segment.physical_block_address, "  dirty:",
							segment.dirtyString());
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

	/**
	 * Recover
	 */
	public void recover() {
		long startTime = System.currentTimeMillis();
		FlashBulkReplayer r = new FlashBulkReplayer();
		try {
			r.recover(fsm);
		} catch (IOException e) {
			e.printStackTrace();
		}
		long count = r.blockForWrites();
		fsm.recycleAfterReplay();
		long estimatedTime = System.currentTimeMillis() - startTime;
		logger.debug("------------------------>" + " Replayed " + count
				+ " records in " + estimatedTime);
	}

	/**
	 * Shuts down the threads used by the commit log, blocking until completion.
	 */
	public void shutdownBlocking() {
		exec.shutdown();
		try {
			exec.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	/**
	 * 
	 * @return last position in commitlog //TODO ColumnFamilyStore.java Line 845
	 *         writeLocks before calling this. Not sure if I need sync
	 */
	public ReplayPosition getContext() {
		synchronized (queue) {
			if (queue.size() != flashThreads) {
				try {
					long startTime = System.currentTimeMillis();
					queue.wait();
					long estimatedTime = System.currentTimeMillis() - startTime;
					logger.debug("------------------------>"
							+ " Wait miliseconds " + estimatedTime);

				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
		synchronized (fsm.active) {// TODO return accurate context on memtable
									// not sure if I need this there
			return fsm.active.getContext();
		}
	}

	/**
	 * Utility function to calculate number of flash blocks needed
	 * 
	 * @param size
	 * @return
	 */
	static long getBlockCount(long size) {
		return (long) (Math.ceil((double) size / (FlashCommitLog.BLOCK_SIZE)));
	}

	public int getPendingTasks() {
		return flashThreads - queue.size();
	}

	// whilst we've flushed all the CFs, which will have recycled all completed
	// segments, we want to ensure
	// there are no segments to replay, so we force the recycling of any
	// remaining (should be at most one)
	public void forceRecycleAllSegments() {
		forceRecycleAllSegments(Collections.<UUID> emptyList());
	}

	public void forceRecycleAllSegments(Iterable<UUID> droppedCfs) {
		fsm.forceRecycleAll(droppedCfs);
	}

}
