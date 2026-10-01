package geiler.addons.client.dungeon;

import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Small, fail-closed rules for deciding whether a detected dungeon doorway can be traced. */
public final class BloodDoorTraceRules {
	private BloodDoorTraceRules() { }

	public static boolean shouldTrace(boolean enabled, DungeonDoorTracker.Door target, boolean closedDoorPresent,
		boolean mapCalibrated, DungeonMapGeometry.RoomType currentRoomType, String currentShape,
		DungeonGridRules.Cell currentCell, List<DungeonRoomTracker.CellKey> connectedCells,
		boolean playerInsideDetectedShape) {
		return target != null && target.type() == DungeonDoorTracker.Type.BLOOD
			&& eligibleConnectedRoom(enabled, target, closedDoorPresent, mapCalibrated, currentRoomType,
				currentShape, currentCell, connectedCells, playerInsideDetectedShape);
	}

	/** Avoid drawing a downstream Blood target at the same time as the currently traced Wither target. */
	public static boolean bloodSequenceAllowsTrace(DungeonDoorTracker.Door activeWitherTrace) {
		return activeWitherTrace == null || activeWitherTrace.type() != DungeonDoorTracker.Type.WITHER;
	}

	public static boolean eligibleConnectedRoom(boolean enabled, DungeonDoorTracker.Door target,
		boolean closedDoorPresent, boolean mapCalibrated, DungeonMapGeometry.RoomType currentRoomType,
		String currentShape, DungeonGridRules.Cell currentCell, List<DungeonRoomTracker.CellKey> connectedCells,
		boolean playerInsideDetectedShape) {
		return enabled && target != null && (target.type() == DungeonDoorTracker.Type.BLOOD
			|| target.type() == DungeonDoorTracker.Type.WITHER) && closedDoorPresent && mapCalibrated
			&& currentRoomType == DungeonMapGeometry.RoomType.ROOM
			&& playerInsideDetectedShape && validCurrentShape(currentCell, currentShape, connectedCells)
			&& isBoundaryOfConnectedRoom(target, connectedCells);
	}

	/** Requires the live map shape, current map cell, and physical player position to agree. */
	public static boolean validCurrentShape(DungeonGridRules.Cell currentCell, String shape,
		List<DungeonRoomTracker.CellKey> connectedCells) {
		if (currentCell == null || shape == null || connectedCells == null || connectedCells.isEmpty()
			|| connectedCells.size() > 4) return false;
		Set<DungeonRoomTracker.CellKey> cells = new HashSet<>(connectedCells);
		if (cells.size() != connectedCells.size() || cells.stream().anyMatch(cell -> !inGrid(cell))) return false;
		DungeonRoomTracker.CellKey current = new DungeonRoomTracker.CellKey(currentCell.componentX(), currentCell.componentZ());
		if (!cells.contains(current)) return false;
		List<DungeonMapGeometry.CellOffset> offsets = cells.stream()
			.map(cell -> new DungeonMapGeometry.CellOffset(cell.x() - current.x(), cell.z() - current.z())).toList();
		return shape.equals(DungeonMapGeometry.shape(offsets)) && connected(cells);
	}

	/** The tracked Blood door must be the exact edge between this shape and its immediate neighbor. */
	public static boolean isBoundaryOfShape(DungeonDoorTracker.Door target,
		List<DungeonRoomTracker.CellKey> connectedCells) {
		if (target == null || target.type() != DungeonDoorTracker.Type.BLOOD || connectedCells == null
			|| connectedCells.isEmpty()) return false;
		return isBoundaryOfConnectedRoom(target, connectedCells);
	}

	public static boolean isBoundaryOfConnectedRoom(DungeonDoorTracker.Door target,
		List<DungeonRoomTracker.CellKey> connectedCells) {
		if (target == null || connectedCells == null || connectedCells.isEmpty()) return false;
		DungeonRoomTracker.CellKey first = cell(target.firstCell());
		DungeonRoomTracker.CellKey second = cell(target.secondCell());
		if (first == null || second == null || manhattan(first, second) != 1) return false;
		Set<DungeonRoomTracker.CellKey> shape = new HashSet<>(connectedCells);
		boolean firstInside = shape.contains(first);
		boolean secondInside = shape.contains(second);
		return firstInside != secondInside;
	}

	private static boolean connected(Set<DungeonRoomTracker.CellKey> cells) {
		Set<DungeonRoomTracker.CellKey> visited = new HashSet<>();
		List<DungeonRoomTracker.CellKey> pending = new java.util.ArrayList<>();
		pending.add(cells.iterator().next());
		for (int index = 0; index < pending.size(); index++) {
			DungeonRoomTracker.CellKey current = pending.get(index);
			if (!visited.add(current)) continue;
			for (DungeonRoomTracker.CellKey candidate : cells) {
				if (!visited.contains(candidate) && manhattan(current, candidate) == 1) pending.add(candidate);
			}
		}
		return visited.size() == cells.size();
	}

	private static int manhattan(DungeonRoomTracker.CellKey first, DungeonRoomTracker.CellKey second) {
		return Math.abs(first.x() - second.x()) + Math.abs(first.z() - second.z());
	}

	private static boolean inGrid(DungeonRoomTracker.CellKey cell) {
		return cell != null && cell.x() >= 0 && cell.x() < 6 && cell.z() >= 0 && cell.z() < 6;
	}

	private static DungeonRoomTracker.CellKey cell(int index) {
		if (index < 0 || index >= 36) return null;
		return new DungeonRoomTracker.CellKey(index % 6, index / 6);
	}

	/** Checks the tracked door plane rather than accepting a stale snapshot after it has opened. */
	public static boolean hasClosedBloodDoorBlocks(DungeonDoorTracker.Door door, Predicate<BlockPos> isBloodDoorBlock) {
		return hasClosedDoorBlocks(door, DungeonDoorTracker.Type.BLOOD, isBloodDoorBlock);
	}

	public static boolean hasClosedWitherDoorBlocks(DungeonDoorTracker.Door door, Predicate<BlockPos> isWitherDoorBlock) {
		return hasClosedDoorBlocks(door, DungeonDoorTracker.Type.WITHER, isWitherDoorBlock);
	}

	private static boolean hasClosedDoorBlocks(DungeonDoorTracker.Door door, DungeonDoorTracker.Type type,
		Predicate<BlockPos> isDoorBlock) {
		return doorPresence(door, type, ignored -> true, isDoorBlock) == DoorPresence.CLOSED;
	}

	/**
	 * Distinguishes a door known to be open from one whose block samples are currently unloaded.
	 * The latter must not cancel a short grace trace merely because the player moved away.
	 */
	public static DoorPresence doorPresence(DungeonDoorTracker.Door door, DungeonDoorTracker.Type type,
		Predicate<BlockPos> isLoaded, Predicate<BlockPos> isDoorBlock) {
		List<BlockPos> samples = doorSamplePositions(door, type);
		if (samples.isEmpty() || isLoaded == null || isDoorBlock == null) return DoorPresence.UNKNOWN;
		boolean everySampleLoaded = true;
		for (BlockPos sample : samples) {
			if (!isLoaded.test(sample)) {
				everySampleLoaded = false;
				continue;
			}
			if (isDoorBlock.test(sample)) return DoorPresence.CLOSED;
		}
		return everySampleLoaded ? DoorPresence.OPEN : DoorPresence.UNKNOWN;
	}

	private static List<BlockPos> doorSamplePositions(DungeonDoorTracker.Door door, DungeonDoorTracker.Type type) {
		if (door == null || door.type() != type || door.width() != 3 || door.height() != 4 || door.depth() != 3
			|| door.firstCell() < 0 || door.firstCell() >= 36 || door.secondCell() < 0 || door.secondCell() >= 36) {
			return List.of();
		}
		int firstX = door.firstCell() % 6, firstZ = door.firstCell() / 6;
		int secondX = door.secondCell() % 6, secondZ = door.secondCell() / 6;
		boolean alongX = firstZ == secondZ && Math.abs(firstX - secondX) == 1;
		boolean alongZ = firstX == secondX && Math.abs(firstZ - secondZ) == 1;
		if (!alongX && !alongZ) return List.of();
		int planeX = door.x() + 1;
		int planeZ = door.z() + 1;
		List<BlockPos> samples = new ArrayList<>(12);
		for (int side = -1; side <= 1; side++) for (int height = 0; height < door.height(); height++) {
			samples.add(alongX
				? new BlockPos(planeX, door.y() + height, planeZ + side)
				: new BlockPos(planeX + side, door.y() + height, planeZ));
		}
		return List.copyOf(samples);
	}

	public enum DoorPresence { CLOSED, OPEN, UNKNOWN }
}
