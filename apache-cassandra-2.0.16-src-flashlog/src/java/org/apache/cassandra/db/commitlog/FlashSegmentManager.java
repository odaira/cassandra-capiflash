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
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.Condition;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.config.Schema;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.Keyspace;
import org.apache.cassandra.db.RowMutation;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.utils.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.research.capiblock.Chunk;

/**
 * @author bsendir
 *
 */
public class FlashSegmentManager {
	Object allocationLock = new Object();
	static final Logger logger = LoggerFactory.getLogger(FlashSegmentManager.class);
	public static int MAX_SEGMENTS = DatabaseDescriptor.getFlashCommitLogNumberOfSegments();
	public static int BLOCKS_IN_SEG = DatabaseDescriptor.getFlashCommitLogSegmentSizeInBlocks();
	public final BlockingQueue<Integer> freelist = new LinkedBlockingQueue<Integer>(MAX_SEGMENTS);
	private final ConcurrentLinkedQueue<FlashSegment> activeSegments = new ConcurrentLinkedQueue<FlashSegment>();
	ByteBuffer util = ByteBuffer.allocateDirect(1024 * 4);// utility buffer for
															// bookkeping
															// purposes
	HashMap<Integer, Long> unCommitted;
	Chunk bookkeeper = null;
	FlashSegment active;

	FlashSegmentManager(Chunk chunk) {
		bookkeeper = chunk;
		unCommitted = new HashMap<Integer, Long>();
		try {// There is only one instance of FSM
			ByteBuffer recoverMe = ByteBuffer.allocateDirect(1024 * 4 * MAX_SEGMENTS);
			bookkeeper.readBlock(FlashCommitLog.START_OFFSET, MAX_SEGMENTS, recoverMe);
			for (int i = 0; i < MAX_SEGMENTS; i++) {
				recoverMe.position(i * FlashCommitLog.BLOCK_SIZE);
				long segID = recoverMe.getLong();
				if (segID != 0) {// Committed Segments will be 0 unCommitted
									// Segments will contain the unique id
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
	}

	private void activateNextSegment() {
		Integer segid = null;
		try {
			segid = freelist.take();
			// this is the last segment
			if (freelist.isEmpty()) {
				// do not accept any more writes
				logger.debug("Entering into emergency state!!!!!!");
				flushOldestKeyspaces();

			}
		} catch (InterruptedException e1) {
			e1.printStackTrace();
		}
		active = new FlashSegment(segid);
		try {
			ByteBuffer buf = ByteBuffer.allocateDirect(1024 * 4);
			logger.debug("Activating " + active.getID() + " with PB:" + active.getPB() + " --> "
					+ FlashCommitLog.START_OFFSET + active.getPB());
			buf.putLong(active.getID());
			bookkeeper.writeBlock(FlashCommitLog.START_OFFSET + active.getPB(), 1, buf);
		} catch (IOException e) {
			e.printStackTrace();
		}
		activeSegments.add(active);
	}

	private void flushOldestKeyspaces() {
		{
			FlashSegment oldestSegment = activeSegments.peek();

			if (oldestSegment != null && oldestSegment != this.active) {
				for (UUID dirtyCFId : oldestSegment.getDirtyCFIDs()) {
					Pair<String, String> pair = Schema.instance.getCF(dirtyCFId);
					if (pair == null) {
						// even though we remove the schema entry before a final
						// flush when dropping a CF,
						// it's still possible for a writer to race and finish
						// his append after the flush.
						logger.debug("Marking clean CF {} that doesn't exist anymore", dirtyCFId);
						oldestSegment.markClean(dirtyCFId, oldestSegment.getContext());
					} else {
						String keypace = pair.left;
						final ColumnFamilyStore cfs = Keyspace.open(keypace).getColumnFamilyStore(dirtyCFId);
						// flush shouldn't run on the commitlog executor, since
						// it acquires Table.switchLock,
						// which may already be held by a thread waiting for the
						// CL executor (via getContext),
						// causing deadlock
						Runnable runnable = new Runnable() {
							public void run() {
								cfs.forceFlush();
							}
						};
						StorageService.optionalTasks.execute(runnable);
					}
				}
			}
		}

	}

	synchronized void recycleSegment(final FlashSegment segment) {
		activeSegments.remove(segment);
		try {
			util.putLong(0);
			bookkeeper.writeBlock(FlashCommitLog.START_OFFSET + segment.getPB(), 1, util);
			util.clear();
			freelist.put((int) segment.getPB());
		} catch (InterruptedException | IOException e) {
			e.printStackTrace();
		}
	}

	public Collection<FlashSegment> getActiveSegments() {
		return Collections.unmodifiableCollection(activeSegments);
	}

	FlashRecordKeeper allocate(long num_blocks, RowMutation rm) {
		if (freelist.isEmpty()) {
			logger.debug("Emergency state " +Thread.currentThread().getName());
			Keyspace.switchLock.readLock().unlock();
			Keyspace.switchLock.writeLock().lock();
			try {
				while (freelist.isEmpty()) {
					try {
						Keyspace.commitLogoutOfSpace.await();
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
			} finally {
				Keyspace.switchLock.writeLock().unlock();
			}
			logger.debug("Emergency state exit" +Thread.currentThread().getName());
			Keyspace.switchLock.readLock().lock();
		}
		//TODO FIXIT.CASE: released number of segments is not enough for the readers blocked.
		//Maybe require at least num_of_writers segments 64 in this case.
		FlashRecordKeeper allocd = null;
		synchronized (allocationLock) {
			if (active == null || !active.hasCapacityFor(num_blocks)) {
				activateNextSegment();
			}
			active.markDirty(rm, active.getContext());
			allocd = new FlashRecordKeeper(num_blocks, active.getandAddPosition(num_blocks), active.getID());
		}
		return allocd; 
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
				freelist.put(key);
				logger.debug("activating key " + key);
			} catch (IOException | InterruptedException e) {
				e.printStackTrace();
			}
		}
		unCommitted.clear();
	}

}
