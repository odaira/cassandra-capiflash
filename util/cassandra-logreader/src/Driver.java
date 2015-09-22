import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.zip.Checksum;

import com.ibm.research.capiblock.CapiBlockDevice;
import com.ibm.research.capiblock.Chunk;

public class Driver {
	static Chunk chunk;
	static String DEVICE;
	static long START_OFFSET;
	static int NUM_BLOCKS;
	final static int BLOCK_SIZE = 4096;
	final static CapiBlockDevice dev = CapiBlockDevice.getInstance();

	public static void main(String[] args) throws IOException {
		try {
			if (args.length < 2) {
				System.err.println("Invalid Argument");
				System.exit(0);
			}
			try (BufferedReader br = new BufferedReader(new FileReader(
					args[0]
					+"/conf/cassandra.yaml"))) {
				String line;
				int set = 0;
				while ((line = br.readLine()) != null) {
					if (line.startsWith("capiflashlog_devices:")) {
						line = br.readLine();
						DEVICE = line.split("-")[1].trim();
						set++;
					} else if (line.startsWith("capiflashlog_number_of_segments:")) {
						NUM_BLOCKS = Integer.valueOf(line.split(":")[1].trim());
						set++;
					} else if (line.startsWith("capiflashlog_start_offset:")) {
						START_OFFSET = Long.valueOf(line.split(":")[1].trim());
						set++;
					}
				}
				if (set != 3) {
					System.err.println("Parameters not set");
					System.exit(0);
				}
			}	
			chunk = dev.openChunk(DEVICE);
			if (args[1].equals("-d")) {
				deleteAll();
			} else if (args[1].equals("-r")) {
				readFreeList();
			} else {
				System.err.println("Invalid Argument");
			}

		} catch (IOException e1) {
			e1.printStackTrace();
		}
	}

	static void readFreeList() throws IOException {
		ByteBuffer util = ByteBuffer.allocateDirect(BLOCK_SIZE);
		long segId;
		int empty = 0;
		for (int i = 0; i < NUM_BLOCKS; i++) {
			chunk.readBlock(START_OFFSET + i, 1, util);
			segId = util.getLong();
			util.clear();
			if (segId == 0) {
				empty++;
			} else {
				System.out.println("Block in Use ! OFFSET:" + i + " SegmentID:"
						+ segId);
			}
		}
		System.out.println(empty + " empty blocks");
	}

	static void deleteAll() throws IOException {
		ByteBuffer util = ByteBuffer.allocateDirect(BLOCK_SIZE);
		util.putLong(0);
		for (int i = 0; i < NUM_BLOCKS; i++) {
			chunk.writeBlock(START_OFFSET + i, 1, util);
		}
		System.out.println("Freelist Blocks at " + START_OFFSET + " cleared");
	}

}
