package org.apache.cassandra.db.capiflash;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Checksum;

import org.apache.cassandra.concurrent.Stage;
import org.apache.cassandra.concurrent.StageManager;
import org.apache.cassandra.config.Schema;
import org.apache.cassandra.db.Column;
import org.apache.cassandra.db.ColumnFamily;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.ColumnSerializer;
import org.apache.cassandra.db.Keyspace;
import org.apache.cassandra.db.RowMutation;
import org.apache.cassandra.db.SystemKeyspace;
import org.apache.cassandra.db.commitlog.CommitLogAllocator;
import org.apache.cassandra.db.commitlog.CommitLogDescriptor;
import org.apache.cassandra.db.commitlog.ReplayPosition;
import org.apache.cassandra.io.util.FastByteArrayInputStream;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.PureJavaCrc32;
import org.apache.cassandra.utils.WrappedRunnable;
import org.cliffc.high_scale_lib.NonBlockingHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Ordering;
import com.ibm.research.capiblock.Chunk;

public class FlashLogReplayer {
	static final Logger logger = LoggerFactory
			.getLogger(FlashLogReplayer.class);
	private static final int MAX_OUTSTANDING_REPLAY_COUNT = Integer.getInteger(
			"cassandra.commitlog_max_outstanding_replay_count", 1024);

	private final Set<Keyspace> keyspacesRecovered;
	private final List<Future<?>> futures;
	private final Map<UUID, AtomicInteger> invalidMutations;
	private final AtomicInteger replayedCount;
	private final Map<UUID, ReplayPosition> cfPositions;
	private final ReplayPosition globalPosition;
	private final Checksum checksum;
	private ByteBuffer buffer;
	

	public FlashLogReplayer() {
		this.keyspacesRecovered = new NonBlockingHashSet<Keyspace>();
		this.futures = new ArrayList<Future<?>>();
		buffer = ByteBuffer.allocateDirect(4096);
		this.invalidMutations = new HashMap<UUID, AtomicInteger>();
		this.replayedCount = new AtomicInteger();
		this.checksum = new PureJavaCrc32();
		cfPositions = new HashMap<UUID, ReplayPosition>();
		Ordering<ReplayPosition> replayPositionOrdering = Ordering
				.from(ReplayPosition.comparator);
		for (ColumnFamilyStore cfs : ColumnFamilyStore.all()) {
			ReplayPosition rp = ReplayPosition.getReplayPosition(cfs
					.getSSTables());
			ReplayPosition truncatedAt = SystemKeyspace
					.getTruncatedPosition(cfs.metadata.cfId);
			if (truncatedAt != null)
				rp = replayPositionOrdering.max(Arrays.asList(rp, truncatedAt));

			cfPositions.put(cfs.metadata.cfId, rp);
		}
		globalPosition = replayPositionOrdering.min(cfPositions.values());
		logger.debug("Global replay position is {} from columnfamilies {}"
				+ globalPosition + "--- " + FBUtilities.toString(cfPositions));
	}

	public void recover(FlashSegmentManager fsm) throws IOException {
		for (Integer key : fsm.unCommitted.keySet()) {
			final long segmentId = fsm.unCommitted.get(key);
			int replayPosition;

			if (globalPosition.segment < segmentId) {
				replayPosition = 0;
			} else if (globalPosition.segment == segmentId) {
				replayPosition = globalPosition.position;
			} else {
				logger.debug("skipping replay of fully-flushed {}" + key);
				return;
			}

			logger.debug(segmentId + " Replaying " + key + " starting at "
					+ replayPosition);
			// get the start position
			long startPosition = replayPosition;
			long claimedCRC32;
			int serializedSize;
			Chunk ch = fsm.bookkeeper;
			while (true) {
				buffer.clear();
				checksum.reset();
				if (startPosition >= FlashSegmentManager.BLOCKS_IN_SEG) {
					logger.debug("--> End of Segment!!! " + segmentId);
					break;
				}
				logger.debug("-->"
						+ FlashSegment.calculatePos(startPosition, key));
				ch.readBlock(FlashSegment.calculatePos(startPosition, key), 1,
						buffer);// startposition
				long recordSegmentId = buffer.getLong();
				if (recordSegmentId != segmentId) {
					logger.debug("Unidentified segment!");
					break;
				}

				serializedSize = buffer.getInt();
				if (serializedSize < 38) {// 28 record bookeeping and checking
											// 10 minumum rm
					logger.debug("Error!! Serialized Size is:" + serializedSize);
					break;
				}
				
				buffer.rewind();
				byte []chsum=new byte[12];
				buffer.get(chsum);
			
				int blocksToRead = (int) (FlashCommitLog
						.getBlockCount(serializedSize));
				if (blocksToRead != 1) {
					buffer = ByteBuffer.allocateDirect(blocksToRead * 4096);
					ch.readBlock(FlashSegment.calculatePos(startPosition, key),
							blocksToRead, buffer);// startposition
					if (buffer.getLong() != segmentId) {
						logger.debug("Read ERROR!!!");
						break;
					}

					if (buffer.getInt() != serializedSize) {
						logger.debug("Read ERROR!!!");
						break;
					}

				}

				
				long claimedSizeChecksum = buffer.getLong();
				

				
				//FBUtilities.updateChecksumInt(checksum, serializedSize);(FIX)//TODO
				checksum.update(chsum, 0, 12);
				if (checksum.getValue() != claimedSizeChecksum) {
					logger.debug("Error!! First Checksum Doesnot Match !! "+ " Read:"+claimedSizeChecksum);
					break;
				}

				byte[] dest = new byte[(serializedSize - 28)];

				buffer.get(dest, 0, (serializedSize - 28));
				claimedCRC32 = buffer.getLong();

				checksum.update(dest, 0, serializedSize - 28);

				if (claimedCRC32 != checksum.getValue()) {
					logger.debug("Error!! Second Checksum Doesnot Match !!");
					break;
				}

				// now we are sure that out data is safe
				FastByteArrayInputStream bufIn = new FastByteArrayInputStream(
						dest, 0, serializedSize - 28);
				final RowMutation rm;
				rm = RowMutation.serializer.deserialize(new DataInputStream(
						bufIn), MessagingService.current_version,
						ColumnSerializer.Flag.LOCAL);
				logger.debug("!!!SUCCESS: " + rm.getKeyspaceName());
				for (ColumnFamily cf : rm.getColumnFamilies()) {
					for (Column cell : cf) {
						cf.getComparator().validate(cell.name());
					}
				}
				startPosition += blocksToRead;
				final long entryLocation = startPosition;
				Runnable runnable = new WrappedRunnable() {
					@Override
					protected void runMayThrow() throws Exception {
						if (Schema.instance.getKSMetaData(rm.getKeyspaceName()) == null)
							return;
						final Keyspace keyspace = Keyspace.open(rm
								.getKeyspaceName());
						RowMutation newRm = null;
						for (ColumnFamily columnFamily : rm.getColumnFamilies()) {
							if (Schema.instance.getCF(columnFamily.id()) == null)
								continue; // dropped

							ReplayPosition rp = cfPositions.get(columnFamily
									.id());
							if (segmentId > rp.segment
									|| (segmentId == rp.segment && entryLocation > rp.position)) {
								if (newRm == null)
									newRm = new RowMutation(
											rm.getKeyspaceName(), rm.key());
								newRm.add(columnFamily);
								replayedCount.incrementAndGet();
							}
						}
						if (newRm != null) {
							assert !newRm.isEmpty();
							Keyspace.open(newRm.getKeyspaceName()).apply(newRm,
									false);// donot write back to commitlog
							keyspacesRecovered.add(keyspace);
						}
					}
				};
				logger.debug("Finished reading: " + key);
				futures.add(StageManager.getStage(Stage.MUTATION).submit(
						runnable));
				if (futures.size() > MAX_OUTSTANDING_REPLAY_COUNT) {
					FBUtilities.waitOnFutures(futures);
					futures.clear();
				}
			}
		}

	}

	public int blockForWrites() {
		for (Map.Entry<UUID, AtomicInteger> entry : invalidMutations.entrySet()) {
			logger.debug(String
					.format("Skipped %d mutations from unknown (probably removed) CF with id %s",
							entry.getValue().intValue(), entry.getKey()));
		}
		FBUtilities.waitOnFutures(futures);
		logger.debug("Finished waiting on mutations from recovery");
		futures.clear();
		for (Keyspace keyspace : keyspacesRecovered) {
			futures.addAll(keyspace.flush());
		}
		FBUtilities.waitOnFutures(futures);
		return replayedCount.get();
	}
}
