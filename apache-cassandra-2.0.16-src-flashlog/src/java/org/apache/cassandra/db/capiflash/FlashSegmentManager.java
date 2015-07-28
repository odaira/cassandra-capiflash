package org.apache.cassandra.db.capiflash;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.research.capiblock.Chunk;
import com.ibm.research.capiblock.ObjectStreamBuffer;

public class FlashSegmentManager {
	static final Logger logger = LoggerFactory
			.getLogger(FlashSegmentManager.class);
	public static int MAX_SEGMENTS = 128;
	public static int BLOCKS_IN_SEG = 32000;

	private final BlockingQueue<Integer> freelist = new LinkedBlockingQueue<Integer>(
			MAX_SEGMENTS);
	private final ConcurrentLinkedQueue<FlashSegment> activeSegments = new ConcurrentLinkedQueue<FlashSegment>();
	ByteBuffer util = ByteBuffer.allocateDirect(1024 * 4);
	HashMap<Integer, Long> unCommitted;
	Chunk bookkeeper = null;
	FlashSegment active;

	FlashSegmentManager(Chunk chunk) {
		bookkeeper = chunk;
		unCommitted = new HashMap<Integer, Long>();
		try {
			ByteBuffer recoverMe = ByteBuffer.allocateDirect(1024 * 4 * 128);
			bookkeeper.readBlock(FlashCommitLog.START_OFFSET, 128, recoverMe);
			for (int i = 0; i < MAX_SEGMENTS; i++) {
				recoverMe.position(i * FlashCommitLog.BLOCK_SIZE);
				long test = recoverMe.getLong();
				if (test != 0) {
					unCommitted.put(i, test);
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
		print();
		activateNextSegment();
	}

	void print() {
		// TODO Auto-generated method stub
		util.clear();
		for (long i = FlashCommitLog.START_OFFSET; i < FlashCommitLog.START_OFFSET + 128; i++) {
			try {
				bookkeeper.readBlock(i, 1, util);
				// buf.flip();
				System.out.println("Position: " + i + "Unique ID: "
						+ util.getLong());
				util.clear();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	private void activateNextSegment() {
		try {
			// TODO if activate takes longer forceFlush //TODO
			active = new FlashSegment(freelist.take());
			try {
				ByteBuffer buf = ByteBuffer.allocateDirect(1024 * 4);
				logger.debug("Activating " + active.getID() + " with PB:"
						+ active.getPB() + " --> "
						+ FlashCommitLog.START_OFFSET + active.getPB());
				buf.putLong(active.getID());
				bookkeeper.writeBlock(
						FlashCommitLog.START_OFFSET + active.getPB(), 1, buf);
			} catch (IOException e) {
				e.printStackTrace();
			}
			activeSegments.add(active);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	synchronized void recycleSegment(final FlashSegment segment) {
		activeSegments.remove(segment);
		try {
			util.putLong(0);
			bookkeeper.writeBlock(
					FlashCommitLog.START_OFFSET + segment.getPB(), 1, util);
			util.clear();
			freelist.put((int) segment.getPB());
		} catch (InterruptedException | IOException e) {
			e.printStackTrace();
		}
	}

	public Collection<FlashSegment> getActiveSegments() {
		return Collections.unmodifiableCollection(activeSegments);
	}

	synchronized FlashSegment ask(long num_blocks) {
		if (active == null || !active.hasCapacityFor(num_blocks)) {
			util.clear();
			util.putLong(0);
			try {// TODO FIx
				bookkeeper.writeBlock(FlashSegment.calculatePos(
						active.currentBlocks.get(), active.getPB()), 1, util);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			activateNextSegment();
		}
		return active;
	}

	public synchronized void recycleAfterReplay() {
		for (Integer key : unCommitted.keySet()) {
			try {
				util.clear();
				util.putLong(0);
				bookkeeper.writeBlock(FlashCommitLog.START_OFFSET + key, 1,
						util);
				util.clear();
				freelist.put(key);
				logger.debug("activating key " + key);
			} catch (IOException | InterruptedException e) {
				e.printStackTrace();
			}

		}
	}

}
