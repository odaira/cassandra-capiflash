#!/usr/bin/env bash
CAPIBLOCK_PATH="/home/bsendir/lib/capiblock"
CASSANDRA_PATH="/home/bsendir/cassandra_recovery2/apache-cassandra-2.0.16-src"
java  -Djava.library.path=$CAPIBLOCK_PATH/build/jni -cp $CAPIBLOCK_PATH/capiblock.jar:$PWD/cassandra-logreader/build Driver $CASSANDRA_PATH $1
