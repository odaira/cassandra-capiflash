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
package org.apache.cassandra.db.commitlog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Vector;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.Keyspace;
import org.apache.cassandra.db.RowMutation;
import org.apache.cassandra.db.commitlog.capi.AsyncChunkManager;
import org.apache.cassandra.db.commitlog.capi.AsyncProducerConsumerChunkManager;
import org.apache.cassandra.db.commitlog.capi.BufferAllocationStrategy;
import org.apache.cassandra.db.commitlog.capi.CheckSummedBuffer;
import org.apache.cassandra.db.commitlog.capi.ChunkManagerInterface;
import org.apache.cassandra.db.commitlog.capi.FixedSizeAllocationStrategy;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.utils.FBUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Sets;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.ibm.research.capiblock.CapiBlockDevice;
import com.ibm.research.capiblock.Chunk;

/**
 * @author bsendir
 *
 */
public class FlashCommitLog implements ICommitLog {
	// TUNABLES

	static int BLOCK_SIZE = 4096;
	static long START_OFFSET = DatabaseDescriptor.getFlashCommitLogStartOffset();
	static String[] DEVICES = DatabaseDescriptor.getFlashCommitLogDevices();
	static int bufferSizeinMB = DatabaseDescriptor.getFlashCommitLogThreadBufferSizeinMB();
	static long DATA_OFFSET = START_OFFSET + FlashSegmentManager.MAX_SEGMENTS;
	static final Logger logger = LoggerFactory.getLogger(FlashCommitLog.class);
	public static final FlashCommitLog instance = new FlashCommitLog();

	public static volatile FlashSegmentManager fsm;
	public static ChunkManagerInterface chunkManager;
	public static BufferAllocationStrategy bufferAlloc;

	protected FlashCommitLog() {
		try {
			fsm = new FlashSegmentManager(CapiBlockDevice.getInstance().openChunk(DEVICES[0]));
			//chunkManager = new AsyncChunkManager();// TODO asyncrequests
			chunkManager = new AsyncProducerConsumerChunkManager();
			bufferAlloc = new FixedSizeAllocationStrategy(); //TODO parameterize
			if (DatabaseDescriptor.isCommitlogDebugEnabled()) {
				new Thread(new Runnable() {
					@Override
					public void run() {
						while (true) {
							try {
								Thread.sleep(2000);
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
							logger.debug(
									"--> Commitlog Monitor: Readers Waiting:" + Keyspace.switchLock.getReadLockCount()
											+ " Is Write Locked:" + Keyspace.switchLock.isWriteLocked());
							logger.debug("Commitlog Monitor:Switchlock queue length: "
									+ Keyspace.switchLock.getQueueLength());
							logger.debug(
									"Commitlog Monitor:Waiting for signal or switchlock acquire" + fsm.locked.get());
							logger.debug("Commitlog Monitor:FlashCommitlog internal lock: "
									+ FlashSegmentManager.freelistState.isLocked() + "Threads waiting to acquire lock: "
									+ FlashSegmentManager.freelistState.getQueueLength());
							logger.debug("Commitlog Monitor:Waiting for signal" + fsm.lockedonWait.get());
							logger.debug("Commitlog Monitor:Flush Executor Active Tasks:"
									+ FlashSegmentManager.flushexecutor.getActiveCount() + " Flush Executor Queue Size:"
									+ FlashSegmentManager.flushexecutor.getQueue().size());
						}
					}
				}).start();
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
	public void add(RowMutation rm) {

		long totalSize = RowMutation.serializer.serializedSize(rm, MessagingService.current_version) + 28;
		long requiredBlocks = getBlockCount(totalSize);
		if (requiredBlocks > DatabaseDescriptor.getFlashCommitLogSegmentSizeInBlocks()
				|| requiredBlocks > DatabaseDescriptor.getFlashCommitLogThreadBufferSizeinMB() * (256)) {
			logger.warn("Skipping commitlog append of extremely large mutation Blocks: {}", requiredBlocks);
			return;
		}
		FlashRecordAdder adder = fsm.allocate(requiredBlocks, rm);
		CheckSummedBuffer buf = null;
		buf = bufferAlloc.poll(requiredBlocks);
		// fill the buffer
		try {
			buf.getStream().writeLong(adder.getSegmentID());
			buf.getStream().writeInt((int) totalSize);
			buf.getBuffer().putLong(buf.getChecksum().getValue());
			RowMutation.serializer.serialize(rm, buf.getStream(), MessagingService.current_version);
			buf.getBuffer().putLong(buf.getChecksum().getValue());
		} catch (IOException e) {
			e.printStackTrace();
		}
		// write buffer
		chunkManager.write(adder.getStartBlock(), adder.getRequiredBlocks(), buf);
		bufferAlloc.free(buf);
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
		FlashSegmentManager.freelistState.lock();
		// Go thru the active segment files, which are ordered oldest to
		// newest, marking the
		// flushed CF as clean, until we reach the segment file
		// containing the ReplayPosition passed
		// in the arguments. Any segments that become unused after they
		// are marked clean will be
		// recycled or discarded.
		for (Iterator<FlashSegment> iter = fsm.getActiveSegments().iterator(); iter.hasNext();) {
			FlashSegment segment = iter.next();
			segment.markClean(cfId, context);
			// If the segment is no longer needed, and we have another
			// spare segment in the hopper
			// (to keep the last segment from getting discarded), pursue
			// either recycling or deleting
			// this segment file.
			if (iter.hasNext()) {
				if (segment.isUnused()) {
					logger.debug("Commit log segment {} is unused ", segment.physical_block_address);
					fsm.recycleSegment(segment);
				} else {
					logger.debug("Not safe to delete commit log segment {}; dirty is {} ",
							segment.physical_block_address, segment.dirtyString());
				}
			} else {
				logger.debug("Not deleting active commitlog segment {} ", segment.physical_block_address);
			}
			if (segment.contains(context)) {
				break;
			}
		}
		FlashSegmentManager.freelistState.unlock();
	}

	/**
	 * Recover
	 */
	public int recover() {
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
		logger.debug("Commitlog Recovery: " + " Replayed " + count + " records in " + estimatedTime);
		return (int) count;
	}

	/**
	 * Shuts down the threads used by the commit log, blocking until completion.
	 */
	public void shutdownBlocking() {
		try {
			chunkManager.closeChunks();
			fsm.bookkeeper.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * 
	 * @return last position in commitlog
	 */
	public Future<ReplayPosition> getContext() {
		return Futures.immediateFuture(fsm.active.getContext());
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

	public long getPendingTasks() {
		return 0;// TODO
	}

	public boolean isEmpty() {
		return fsm.freelist.isEmpty();
	}

	public boolean hasWaiters() {
		return fsm.lockedonWait.get() != 0;
	}
}
