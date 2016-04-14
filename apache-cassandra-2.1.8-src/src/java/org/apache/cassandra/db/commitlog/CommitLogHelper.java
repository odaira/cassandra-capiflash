package org.apache.cassandra.db.commitlog;

import org.apache.cassandra.config.Config;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.commitlog.capi.FlashCommitLog;

public class CommitLogHelper {
	public static final ICommitLog instance = DatabaseDescriptor
			.getCommitLogType() == Config.CommitLogType.CAPIFlashCommitLog ? FlashCommitLog.instance
			: CommitLog.instance;
}
