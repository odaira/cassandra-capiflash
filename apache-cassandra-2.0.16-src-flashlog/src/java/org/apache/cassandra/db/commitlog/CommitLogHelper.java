package org.apache.cassandra.db.commitlog;

import org.apache.cassandra.config.Config;
import org.apache.cassandra.config.DatabaseDescriptor;

public class CommitLogHelper {
	public static final ICommitLog instance = DatabaseDescriptor
			.getCommitLogType() == Config.CommitLogType.CAPIFlashCommitLog ? FlashCommitLog.instance
			: CommitLog.instance;
}
