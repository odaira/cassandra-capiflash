package org.apache.cassandra.db.commitlog;

import java.util.UUID;
import java.util.concurrent.Future;

import org.apache.cassandra.db.RowMutation;

import com.google.common.util.concurrent.ListenableFuture;

public interface ICommitLog {

	Object archiver = null;

	Future<ReplayPosition> getContext();

	void add(RowMutation mutation);

	long getPendingTasks();

	int recover();

	void shutdownBlocking() throws InterruptedException;

	void discardCompletedSegments(UUID cfId, ReplayPosition x);
		
}
