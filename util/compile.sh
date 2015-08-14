#!/usr/bin/env bash
CAPIBLOCK_PATH="/home/bsendir/lib/capiblock"
javac -sourcepath $PWD/cassandra-logreader/src -cp $CAPIBLOCK_PATH/capiblock.jar -d $PWD/cassandra-logreader/build $PWD/cassandra-logreader/src/Driver.java
