/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.db.capiflash;

/**
 * @author bsendir
 * Utility Class to keep offsets for a record. 
 *
 */
//TODO I created this class for easier debugging. We could get rid of this class 
public class FlashRecordKeeper {
	private long startBlock = 0;
	private int totalSize = 0;
	private long requiredBlocks = 0;
	private long segmentID = 0;

	
	public FlashRecordKeeper(long num_blocks, long pos, long id) {
		requiredBlocks=num_blocks;
		segmentID=id;
		startBlock=pos;
	}

	public long getStartBlock() {
		return startBlock;
	}

	public int getTotalSize() {
		return totalSize;
	}

	public long getRequiredBlocks() {
		return requiredBlocks;
	}

	public long getSegmentID() {
		return segmentID;
	}

	public void setSize(int totalSize2) {
		totalSize = totalSize2;

	}
}
