This project is derived from Cassandra's commitlog implementation. Please see my posts in Cassandra blog.
http://arlab093.austin.ibm.com/cassandra/?author=6

This link might help importing into eclipse
http://wiki.apache.org/cassandra/RunningCassandraInEclipse

HOW TO COMPILE
---------------
-- I set all of the communication ports default+4000. So, please consider this if you are going make rpc. 
Check "_port" in cassandra.yaml and cassandra-env.sh

-- set paths in conf/cassandra.yaml (search for /home/bsendir/temp)

-- set classpath for capiblock/jni in conf/cassandra-env.sh

JAVA 1.8 if you get cql-grammer check error trying compiling it with "ant build"
https://issues.apache.org/jira/browse/CASSANDRA-7028

compile with "ant"

HOW TO RUN
----------
-- bin/cassandra -f

HOW TO INIT YCSB Test Table (sample file in bin folder)
---------------------------
go to CASSANDRA_DIRECTORY/bin
command below inserts the commands in durable file. which is located in bin directory.
./cassandra-cli -p 13160 -f durable


Changed Files
----------------
/src/java/org/apache/cassandra/service/StorageService.java:                FlashCommitLog.instance.shutdownBlocking();
/src/java/org/apache/cassandra/service/StorageService.java:        FlashCommitLog.instance.shutdownBlocking();
/src/java/org/apache/cassandra/service/CassandraDaemon.java:        FlashCommitLog.instance.recover();
/src/java/org/apache/cassandra/db/capiflash/FlashCommitLog.java:	public static final FlashCommitLog instance = new FlashCommitLog();
/src/java/org/apache/cassandra/db/ColumnFamilyStore.java:            final Future<ReplayPosition> ctx = writeCommitLog ? FlashCommitLog.instance.getContext() : Futures.immediateFuture(ReplayPosition.NONE);
/src/java/org/apache/cassandra/db/ColumnFamilyStore.java:                        FlashCommitLog.instance.discardCompletedSegments(metadata.cfId,x);
/src/java/org/apache/cassandra/db/Keyspace.java:                FlashCommitLog.instance.add(mutation);
/src/java/org/apache/cassandra/utils/StatusLogger.java:                                  "Commitlog", "n/a", FlashCommitLog.instance.getPendingTasks()));

Implementation of Cassandra Flash CommitLog
----------------------------------------
Replaced singleton Commitlog instance with FlashCommitlog
src/java/org/apache/cassandra/db/capiflash/*


Commitlog Parts that are not covered
-----------------------------------
--> Cassandra metrics for commitlog
I will work on this soon

--> Commitlog archiving functionality
http://docs.datastax.com/en/cassandra/2.0/cassandra/configuration/configLogArchive_t.html

--> Commitlog Replay filtering 
Excludes colum families from log replay process


Bedri
