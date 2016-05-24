package org.apache.cassandra.db.commitlog;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Future;

import org.apache.cassandra.db.Mutation;

public interface ICommitLog {

	Object archiver = null;

	ReplayPosition getContext();

	ReplayPosition add(Mutation mutation);

	long getPendingTasks();

	int recover();

	void shutdownBlocking() throws InterruptedException;

	void discardCompletedSegments(UUID cfId, ReplayPosition x);

	void forceRecycleAllSegments(Iterable<UUID> droppedCfs);

	void await();

}
