package geiler.addons.client.dungeon;

import java.util.List;

/** Pure grid and orientation rules for the client's local dungeon-room fingerprints. */
public final class DungeonGridRules {
	public static final int GRID_MIN = -200;
	public static final int GRID_MAX = -10;
	public static final int CELL_STRIDE = 32;
	public static final int ROOM_SIZE = 31;
	public static final int SCAN_MIN_Y = 69;
	public static final int SCAN_MAX_Y = 101;

	private DungeonGridRules() { }

	public static Cell cellAt(int blockX, int blockZ) {
		if (blockX < GRID_MIN || blockX > GRID_MAX || blockZ < GRID_MIN || blockZ > GRID_MAX) return null;
		int componentX = Math.floorDiv(blockX - GRID_MIN, CELL_STRIDE);
		int componentZ = Math.floorDiv(blockZ - GRID_MIN, CELL_STRIDE);
		int localX = blockX - (GRID_MIN + componentX * CELL_STRIDE);
		int localZ = blockZ - (GRID_MIN + componentZ * CELL_STRIDE);
		if (componentX < 0 || componentX >= 6 || componentZ < 0 || componentZ >= 6
			|| localX >= ROOM_SIZE || localZ >= ROOM_SIZE) return null;
		return cellAtOrigin(GRID_MIN + componentX * CELL_STRIDE, GRID_MIN + componentZ * CELL_STRIDE);
	}

	/** Maps a known 32-block room origin to the catalog's dungeon-grid coordinate system. */
	public static Cell cellAtOrigin(int baseX, int baseZ) {
		int componentX = Math.floorDiv(baseX - GRID_MIN, CELL_STRIDE);
		int componentZ = Math.floorDiv(baseZ - GRID_MIN, CELL_STRIDE);
		if (componentX < 0 || componentX >= 6 || componentZ < 0 || componentZ >= 6
			|| GRID_MIN + componentX * CELL_STRIDE != baseX
			|| GRID_MIN + componentZ * CELL_STRIDE != baseZ) return null;
		return new Cell(componentX, componentZ, baseX, baseZ);
	}

	/** Maps world-cell coordinates into the canonical orientation selected by the fingerprint. */
	public static Position toCanonical(int rotation, int x, int z) {
		return switch (Math.floorMod(rotation, 4)) {
			case 1 -> new Position(ROOM_SIZE - 1 - z, x);
			case 2 -> new Position(ROOM_SIZE - 1 - x, ROOM_SIZE - 1 - z);
			case 3 -> new Position(z, ROOM_SIZE - 1 - x);
			default -> new Position(x, z);
		};
	}

	/** Maps canonical marker coordinates back into the loaded dungeon's world-cell orientation. */
	public static Position toWorld(int rotation, int x, int z) {
		return switch (Math.floorMod(rotation, 4)) {
			case 1 -> new Position(z, ROOM_SIZE - 1 - x);
			case 2 -> new Position(ROOM_SIZE - 1 - x, ROOM_SIZE - 1 - z);
			case 3 -> new Position(ROOM_SIZE - 1 - z, x);
			default -> new Position(x, z);
		};
	}

	/**
	 * Produces an order-independent 64-bit fingerprint and the rotation that maps the input cell
	 * to its canonical orientation. Samples contain only stable block IDs and are local to one cell.
	 */
	public static Fingerprint fingerprint(List<BlockSample> samples) {
		long best = 0L;
		int bestRotation = 0;
		boolean first = true;
		for (int rotation = 0; rotation < 4; rotation++) {
			long sum = 0L;
			long xor = 0L;
			for (BlockSample sample : samples) {
				Position canonical = toCanonical(rotation, sample.x(), sample.z());
				long value = hash(sample.blockId(), canonical.x(), sample.y(), canonical.z());
				sum += value;
				xor ^= Long.rotateLeft(value, (int) (value & 63));
			}
			long candidate = avalanche(sum ^ Long.rotateLeft(xor, 19) ^ ((long) samples.size() * 0x9E3779B97F4A7C15L));
			if (first || Long.compareUnsigned(candidate, best) < 0) {
				first = false;
				best = candidate;
				bestRotation = rotation;
			}
		}
		return new Fingerprint(best, bestRotation, samples.size());
	}

	private static long hash(String blockId, int x, int y, int z) {
		long result = 0xCBF29CE484222325L;
		for (int i = 0; i < blockId.length(); i++) {
			result ^= blockId.charAt(i);
			result *= 0x100000001B3L;
		}
		result ^= ((long) x << 32) ^ ((long) y << 16) ^ z;
		return avalanche(result);
	}

	private static long avalanche(long value) {
		value ^= value >>> 30;
		value *= 0xBF58476D1CE4E5B9L;
		value ^= value >>> 27;
		value *= 0x94D049BB133111EBL;
		return value ^ (value >>> 31);
	}

	public record Cell(int componentX, int componentZ, int baseX, int baseZ) { }
	public record Position(int x, int z) { }
	public record BlockSample(int x, int y, int z, String blockId) { }
	public record Fingerprint(long hash, int rotation, int sampleCount) { }
}
