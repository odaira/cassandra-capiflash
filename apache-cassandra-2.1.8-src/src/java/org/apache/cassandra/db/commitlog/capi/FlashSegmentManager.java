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
package org.apache.cassandra.db.commitlog.capi;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.cassandra.concurrent.JMXEnabledThreadPoolExecutor;
import org.apache.cassandra.concurrent.NamedThreadFactory;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.config.Schema;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.Keyspace;
import org.apache.cassandra.db.Mutation;
import org.apache.cassandra.db.commitlog.ReplayPosition;
import org.apache.cassandra.metrics.DefaultNameFactory;
import org.apache.cassandra.utils.Pair;
import org.apache.cassandra.utils.concurrent.WaitQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.ibm.research.capiblock.CapiBlockDevice;
import com.ibm.research.capiblock.Chunk;
import com.yammer.metrics.Metrics;

/**
 * @author bsendir
 *
 */
public class FlashSegmentManager {

	static final ReentrantLock allocationLock = new ReentrantLock();
	final WaitQueue hasAvailableSegments = new WaitQueue();
	static final Logger logger = LoggerFactory.getLogger(FlashSegmentManager.class);
	public static int MAX_SEGMENTS = DatabaseDescriptor.getFlashCommitLogNumberOfSegments();
	public static int BLOCKS_IN_SEG = DatabaseDescriptor.getFlashCommitLogSegmentSizeInBlocks();
	public static double flush_threshold = DatabaseDescriptor.getFlashCommitLogFlushThresHold();
	public final BlockingQueue<Integer> freelist = new LinkedBlockingQueue<Integer>(MAX_SEGMENTS);
	private final ConcurrentLinkedQueue<FlashSegment> activeSegments = new ConcurrentLinkedQueue<FlashSegment>();
	ByteBuffer util = ByteBuffer.allocateDirect(1024 * 4);// utility buffer for
															// bookkeping
															// purposes
	HashMap<Integer, Long> unCommitted;
	Chunk bookkeeper = null;
	volatile FlashSegment active;

	static final protected ThreadPoolExecutor flushscheduler = (ThreadPoolExecutor) Executors.newFixedThreadPool(
			DatabaseDescriptor.getFlushWriters(), new NamedThreadFactory("Commitlog Flush", Thread.MAX_PRIORITY));

	FlashSegmentManager(Chunk chunk) {
		bookkeeper = chunk;
		unCommitted = new HashMap<Integer, Long>();
		try {// There is only one instance of FSM
			ByteBuffer recoverMe = ByteBuffer.allocateDirect(1024 * 4 * MAX_SEGMENTS);
			bookkeeper.readBlock(FlashCommitLog.START_OFFSET, MAX_SEGMENTS, recoverMe);
			for (int i = 0; i < MAX_SEGMENTS; i++) {
				recoverMe.position(i * CapiBlockDevice.BLOCK_SIZE);
				long segID = recoverMe.getLong();
				if (segID != 0) {// Committed Segments will be 0 unCommitted
									// Segments will contain the unique id
					logger.debug(i + " is uncommitted with segment id " + segID);
					unCommitted.put(i, segID);
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
		}

		for (int i = 0; i < MAX_SEGMENTS; i++) {
			if (!unCommitted.containsKey(i)) {
				freelist.add(i);
			} else {
				logger.debug(i + " will be replayed");
			}
		}
		activateNextSegment();
		startBackgroundThread();
	}

	private void startBackgroundThread() {
		Timer t = new Timer();
		t.scheduleAtFixedRate(new TimerTask() {
			public void run() {
				if (hasAvailableSegments.hasWaiters() && !freelist.isEmpty()) {
					hasAvailableSegments.signalAll();
				}
				if (((JMXEnabledThreadPoolExecutor) ColumnFamilyStore.flushExecutor)
						.getActiveCount() < DatabaseDescriptor.getFlushWriters()
						&& ((JMXEnabledThreadPoolExecutor) ColumnFamilyStore.postFlushExecutor).getPendingTasks() < 2) {
					if (freelist.isEmpty()) {
						logger.debug("Entering into big emergency state!!!!!!");
						final List<FlashSegment> segmentsToRecycle = new ArrayList<>();
						for (FlashSegment segment : getActiveSegments()) {
							segmentsToRecycle.add(segment);
						}
						flushscheduler.submit(new Runnable() {
							public void run() {
								Future<?> futures = flushDataFrom(segmentsToRecycle, true);
								try {
									futures.get();
								} catch (InterruptedException | ExecutionException e) {
									// TODO Auto-generated catch block
									e.printStackTrace();
								}

							}
						});
					} else if (freelist.size() < (double) MAX_SEGMENTS * flush_threshold) {
						logger.debug("Background flush : " + freelist.size());
						final List<FlashSegment> segmentsToRecycle = new ArrayList<>();
						segmentsToRecycle.add(activeSegments.peek());
						flushscheduler.submit(new Runnable() {
							public void run() {
								Future<?> futures = flushDataFrom(segmentsToRecycle, true);
								try {
									futures.get();
								} catch (InterruptedException | ExecutionException e) {
									// TODO Auto-generated catch block
									e.printStackTrace();
								}
							}
						});
					}
				} else {
					logger.debug("Too many active Flush writer tasks "
							+ ((JMXEnabledThreadPoolExecutor) ColumnFamilyStore.flushExecutor).getActiveCount());
					logger.debug("Pending postflush tasks "
							+ ((JMXEnabledThreadPoolExecutor) ColumnFamilyStore.postFlushExecutor).getPendingTasks());
				}
			}
		}, 0, DatabaseDescriptor.getFlashCommitLogFlushCheckInterval());
	}

	private void activateNextSegment() {
		Integer segid;
		try {
			segid = freelist.take();
			active = new FlashSegment(segid);
			try {
				ByteBuffer buf = ByteBuffer.allocateDirect(1024 * 4);
				logger.debug("Activating " + active.getID() + " with PB:" + active.getPB() + " --> "
						+ (FlashCommitLog.START_OFFSET + active.getPB()));
				buf.putLong(active.getID());
				bookkeeper.writeBlock(FlashCommitLog.START_OFFSET + active.getPB(), 1, buf);
			} catch (IOException e) {
				e.printStackTrace();
			}
			activeSegments.add(active);
		} catch (InterruptedException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
	}

	void recycleSegment(final FlashSegment segment) {
		activeSegments.remove(segment);
		try {
			logger.debug("Recycling " + segment.getID());
			util.putLong(0);
			bookkeeper.writeBlock(FlashCommitLog.START_OFFSET + segment.getPB(), 1, util);
			util.clear();
			freelist.add((int) segment.getPB());
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public Collection<FlashSegment> getActiveSegments() {
		return Collections.unmodifiableCollection(activeSegments);
	}

	FlashRecordAdder allocate(int num_blocks, Mutation rm) {
		allocationLock.lock();
		if (active == null || !active.hasCapacityFor(num_blocks)) {
			while (freelist.isEmpty()) {
				hasAvailableSegments.register(Metrics
						.newTimer(new DefaultNameFactory("CommitLog").createMetricName("WaitingOnSegmentAllocation"),
								TimeUnit.MICROSECONDS, TimeUnit.SECONDS)
						.time()).awaitUninterruptibly();
			}
			activateNextSegment();
		}
		active.markDirty(rm, active.getContext());
		final FlashRecordAdder offset = new FlashRecordAdder(num_blocks, active.getandAddPosition(num_blocks),
				active.getID(),(int) active.currentBlocks.get());
		allocationLock.unlock();
		return offset;
	}

	/**
	 * Zero all bookkeeping segments
	 */
	public void recycleAfterReplay() {
		for (Integer key : unCommitted.keySet()) {
			try {
				util.clear();
				util.putLong(0);
				bookkeeper.writeBlock(FlashCommitLog.START_OFFSET + key, 1, util);
				freelist.add(key);
				hasAvailableSegments.signalAll();
				logger.debug("Recycle after replay activating: " + key);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		unCommitted.clear();
	}

	private Future<?> flushDataFrom(List<FlashSegment> segments, boolean force) {
		if (segments.isEmpty())
			return Futures.immediateFuture(null);
		final ReplayPosition maxReplayPosition = segments.get(segments.size() - 1).getContext();
		// a map of CfId -> forceFlush() to ensure we only queue one flush per
		// cf
		final Map<UUID, ListenableFuture<?>> flushes = new LinkedHashMap<>();
		for (FlashSegment segment : segments) {
			for (UUID dirtyCFId : segment.getDirtyCFIDs()) {
				Pair<String, String> pair = Schema.instance.getCF(dirtyCFId);
				if (pair == null) {
					// even though we remove the schema entry before a final
					// flush when dropping a CF,
					// it's still possible for a writer to race and finish his
					// append after the flush.
					logger.debug("Marking clean CF {} that doesn't exist anymore", dirtyCFId);
					segment.markClean(dirtyCFId, segment.getContext());
				} else if (!flushes.containsKey(dirtyCFId)) {
					String keyspace = pair.left;
					final ColumnFamilyStore cfs = Keyspace.open(keyspace).getColumnFamilyStore(dirtyCFId);
					// can safely call forceFlush here as we will only ever
					// block (briefly) for other attempts to flush,
					// no deadlock possibility since switchLock removal
					logger.debug("Flushing " + dirtyCFId);
					flushes.put(dirtyCFId, force ? cfs.forceFlush() : cfs.forceFlush(maxReplayPosition));
				}
			}
		}

		return Futures.allAsList(flushes.values());
	}

	public void forceRecycleAll(Iterable<UUID> droppedCfs) {
		List<FlashSegment> segmentsToRecycle = new ArrayList<>(activeSegments);
		FlashSegment last = segmentsToRecycle.get(segmentsToRecycle.size() - 1);
		allocationLock.lock();
		activateNextSegment();
		allocationLock.unlock();
		// make sure the writes have materialized inside of the memtables by
		// waiting for all outstanding writes
		// on the relevant keyspaces to complete
		Set<Keyspace> keyspaces = new HashSet<>();
		for (UUID cfId : last.getDirtyCFIDs()) {
			ColumnFamilyStore cfs = Schema.instance.getColumnFamilyStoreInstance(cfId);
			if (cfs != null)
				keyspaces.add(cfs.keyspace);
		}
		for (Keyspace keyspace : keyspaces)
			keyspace.writeOrder.awaitNewBarrier();

		// flush and wait for all CFs that are dirty in segments up-to and
		// including 'last'
		Future<?> future = flushDataFrom(segmentsToRecycle, true);
		try {
			future.get();

			for (FlashSegment segment : activeSegments)
				for (UUID cfId : droppedCfs)
					segment.markClean(cfId, segment.getContext());

			// now recycle segments that are unused, as we may not have
			// triggered a discardCompletedSegments()
			// if the previous active segment was the only one to recycle (since
			// an active segment isn't
			// necessarily dirty, and we only call dCS after a flush).
			for (FlashSegment segment : activeSegments)
				if (segment.isUnused())
					recycleSegment(segment);

			FlashSegment first;
			if ((first = activeSegments.peek()) != null && first.id <= last.id)
				logger.error(
						"Failed to force-recycle all segments; at least one segment is still in use with dirty CFs.");
		} catch (Throwable t) {
			// for now just log the error and return false, indicating that we
			// failed
			logger.error("Failed waiting for a forced recycle of in-use commit log segments", t);
		}

	}

}
