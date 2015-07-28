package org.apache.cassandra.db.capiflash;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.Schema;
import org.apache.cassandra.db.ColumnFamily;
import org.apache.cassandra.db.RowMutation;
import org.apache.cassandra.db.commitlog.ReplayPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FlashSegment {
	static final Logger logger = LoggerFactory.getLogger(FlashSegment.class);
	// cache
	public final Map<UUID, Integer> cfLastWrite = new HashMap<>();

	// create a unique id per segment
	private final static long idBase = System.currentTimeMillis();
	private final static AtomicInteger nextId = new AtomicInteger(1);

	// id of this segment
	final long id;
	final int physical_block_address;// bookkeeping address

	//
	public AtomicLong currentBlocks = new AtomicLong(0);

	public static long getNextId() {
		return idBase + nextId.getAndIncrement();
	}

	public FlashSegment(int pb_id) {
		id = getNextId();
		physical_block_address = pb_id;
	}

	// Utility functions
	public synchronized boolean isActive() {
		return !cfLastWrite.isEmpty();
	}

	public boolean hasCapacityFor(long blocks) {
		return blocks <= (FlashSegmentManager.BLOCKS_IN_SEG - currentBlocks
				.get());
	}

	public ReplayPosition getContext() {
		return new ReplayPosition(id, (int) currentBlocks.get());
	}

	public long getID() {
		return id;
	}

	public long getPB() {
		return physical_block_address;
	}

	public long getandAddPosition(long blocks) {
		return (long) FlashCommitLog.DATA_OFFSET
				+ ((long) (physical_block_address) * FlashSegmentManager.BLOCKS_IN_SEG)
				+ (long) currentBlocks.getAndAdd(blocks);
	}

	static long calculatePos(long start, long segmentno) {
		return FlashCommitLog.DATA_OFFSET
				+ (long) ((segmentno) * FlashSegmentManager.BLOCKS_IN_SEG)
				+ start;
	}

	// TODO code markDirty for multiple CF's
	public void markDirty(RowMutation rm, ReplayPosition repPos) {
		for (ColumnFamily columnFamily : rm.getColumnFamilies()) {
			// check for null cfm in case a cl write goes through after the cf
			// is
			// defined but before a new segment is created.
			CFMetaData cfm = Schema.instance.getCFMetaData(columnFamily.id());
			if (cfm == null) {
				logger.debug("Attempted to write commit log entry for unrecognized column family: "
						+ columnFamily.id());
			} else {
				markCFDirty(cfm.cfId, repPos.position);
			}
		}
	}

	private void markCFDirty(UUID cfId, int position) {
		cfLastWrite.put(cfId, position);
	}

	public synchronized Collection<UUID> getDirtyCFIDs() {
		return new ArrayList<>(cfLastWrite.keySet());
	}

	public synchronized void markClean(UUID cfId, ReplayPosition context) {
		Integer lastWritten = cfLastWrite.get(cfId);
		if (lastWritten != null
				&& (!contains(context) || lastWritten < context.position)) {
			cfLastWrite.remove(cfId);
		}
	}

	public synchronized boolean isUnused() {
		return cfLastWrite.isEmpty();
	}

	public boolean contains(ReplayPosition context) {
		return context.segment == id;
	}

	public String dirtyString() {
		StringBuilder sb = new StringBuilder();
		for (UUID cfId : getDirtyCFIDs()) {
			CFMetaData m = Schema.instance.getCFMetaData(cfId);
			sb.append(m == null ? "<deleted>" : m.cfName).append(" (")
					.append(cfId).append("), ");
		}
		return sb.toString();
	}
}
