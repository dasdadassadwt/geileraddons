package geiler.addons.client.dungeon;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Physical cell/door geometry used to decide whether a dungeon door trace belongs in this room. */
public final class DungeonDoorAdjacency {
	private static final int GRID_SIZE = 6;

	private DungeonDoorAdjacency() { }

	/**
	 * Tests a door against the room footprint rooted at the player's physical dungeon-grid cell.
	 * If supplied map offsets are malformed, the conservative footprint is the player's cell alone;
	 * room identity, room rotation, and catalog recognition are deliberately not involved.
	 */
	public static boolean bordersCurrentRoom(DungeonDoorTracker.Door door, DungeonGridRules.Cell playerCell,
		List<DungeonMapGeometry.CellOffset> mapOffsets) {
		if (door == null || playerCell == null || !validCell(playerCell.componentX(), playerCell.componentZ())) return false;
		Set<Integer> footprint = footprint(playerCell, mapOffsets);
		int first = door.firstCell();
		int second = door.secondCell();
		if (!validDoorEdge(first, second)) return false;
		boolean firstInside = footprint.contains(first);
		boolean secondInside = footprint.contains(second);
		return firstInside != secondInside;
	}

	/** Returns the valid physical footprint; unavailable/invalid map data falls back to a 1x1 cell. */
	public static Set<Integer> footprint(DungeonGridRules.Cell playerCell,
		List<DungeonMapGeometry.CellOffset> mapOffsets) {
		if (playerCell == null || !validCell(playerCell.componentX(), playerCell.componentZ())) return Set.of();
		int current = index(playerCell.componentX(), playerCell.componentZ());
		Set<Integer> fallback = Set.of(current);
		if (mapOffsets == null || mapOffsets.isEmpty() || mapOffsets.size() > GRID_SIZE * GRID_SIZE) return fallback;
		Set<Integer> cells = new HashSet<>();
		for (DungeonMapGeometry.CellOffset offset : mapOffsets) {
			if (offset == null) return fallback;
			int x = playerCell.componentX() + offset.x();
			int z = playerCell.componentZ() + offset.z();
			if (!validCell(x, z) || !cells.add(index(x, z))) return fallback;
		}
		if (!cells.contains(current) || !connected(cells)) return fallback;
		return Set.copyOf(cells);
	}

	private static boolean validDoorEdge(int first, int second) {
		if (first < 0 || first >= GRID_SIZE * GRID_SIZE || second < 0 || second >= GRID_SIZE * GRID_SIZE) return false;
		int firstX = first % GRID_SIZE, firstZ = first / GRID_SIZE;
		int secondX = second % GRID_SIZE, secondZ = second / GRID_SIZE;
		return Math.abs(firstX - secondX) + Math.abs(firstZ - secondZ) == 1;
	}

	private static boolean connected(Set<Integer> cells) {
		int first = cells.iterator().next();
		Set<Integer> visited = new HashSet<>();
		ArrayDeque<Integer> pending = new ArrayDeque<>();
		pending.add(first);
		while (!pending.isEmpty()) {
			int current = pending.removeFirst();
			if (!visited.add(current)) continue;
			int x = current % GRID_SIZE, z = current / GRID_SIZE;
			for (int candidate : cells) {
				if (!visited.contains(candidate)
					&& Math.abs(x - candidate % GRID_SIZE) + Math.abs(z - candidate / GRID_SIZE) == 1) {
					pending.addLast(candidate);
				}
			}
		}
		return visited.size() == cells.size();
	}

	private static boolean validCell(int x, int z) { return x >= 0 && x < GRID_SIZE && z >= 0 && z < GRID_SIZE; }
	private static int index(int x, int z) { return z * GRID_SIZE + x; }
}
