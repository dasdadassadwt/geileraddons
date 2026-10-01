package geiler.addons.client.dungeon;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Incrementally inspects loaded dungeon cell boundaries for doorway geometry. */
public final class DungeonDoorTracker {
	private static final int GRID_CELLS = 6;
	private static final int EDGES_PER_TICK = 4;
	private static final List<Edge> EDGES = edges();
	private static ClientLevel trackedLevel;
	private static DungeonFloor trackedFloor;
	private static int cursor;
	private static final List<Door> staging = new ArrayList<>();
	private static List<Door> snapshot = List.of();

	private DungeonDoorTracker() { }

	public static void tick(Minecraft client) {
		ClientLevel level = client == null ? null : client.level;
		DungeonFloor floor = DungeonContextTracker.inDungeon() ? DungeonContextTracker.currentFloor() : null;
		if (level != trackedLevel || floor != trackedFloor) {
			trackedLevel = level;
			trackedFloor = floor;
			cursor = 0;
			staging.clear();
			snapshot = List.of();
		}
		if (level == null || !DungeonContextTracker.inDungeon()) return;
		int end = Math.min(EDGES.size(), cursor + EDGES_PER_TICK);
		for (; cursor < end; cursor++) {
			Door door = inspect(level, EDGES.get(cursor));
			if (door != null) staging.add(door);
		}
		if (cursor == EDGES.size()) {
			snapshot = List.copyOf(staging);
			staging.clear();
			cursor = 0;
		}
	}

	public static List<Door> doors() { return snapshot; }

	/** Returns Wither doors in route order, falling back to the nearest observed doors if the graph is incomplete. */
	public static List<Door> orderedWitherDoors(BlockPos playerPosition, int maximum) {
		if (playerPosition == null || maximum <= 0 || snapshot.isEmpty()) return List.of();
		int start = nearestCellIndex(playerPosition);
		if (start < 0) return List.of();
		List<Door> route = pathWitherDoors(start);
		if (route.isEmpty()) {
			route = snapshot.stream().filter(door -> door.type == Type.WITHER)
				.sorted(java.util.Comparator.comparingDouble(door -> distanceSquared(door, playerPosition))).toList();
		}
		return route.size() <= maximum ? route : List.copyOf(route.subList(0, maximum));
	}

	/** A target is always available when a loaded Wither doorway is visible, even before the route is known. */
	public static Door nextWitherDoor(BlockPos playerPosition) {
		List<Door> doors = orderedWitherDoors(playerPosition, 1);
		return doors.isEmpty() ? null : doors.getFirst();
	}

	/** The single Blood room entrance, chosen from the loaded snapshot nearest to the player. */
	public static Door nextBloodDoor(BlockPos playerPosition) {
		if (playerPosition == null || snapshot == null) return null;
		return snapshot.stream().filter(door -> door.type == Type.BLOOD)
			.min(java.util.Comparator.comparingDouble(door -> distanceSquared(door, playerPosition)))
			.orElse(null);
	}

	/** True when the door borders the room currently occupied by the player. */
	public static boolean touchesCell(Door door, DungeonGridRules.Cell cell) {
		if (door == null || cell == null) return false;
		int cellIndex = index(cell.componentX(), cell.componentZ());
		return door.firstCell == cellIndex || door.secondCell == cellIndex;
	}

	private static List<Door> pathWitherDoors(int start) {
		int[] previous = new int[GRID_CELLS * GRID_CELLS];
		Door[] previousDoor = new Door[previous.length];
		Arrays.fill(previous, -1);
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		previous[start] = start;
		queue.add(start);
		int bloodAdjacent = -1;
		while (!queue.isEmpty()) {
			int current = queue.removeFirst();
			for (Door door : snapshot) {
				if (door.type == Type.BLOOD && (door.firstCell == current || door.secondCell == current)) {
					bloodAdjacent = current;
					queue.clear();
					break;
				}
				if (door.type == Type.BLOOD) continue;
				int other = door.firstCell == current ? door.secondCell : door.secondCell == current ? door.firstCell : -1;
				if (other < 0 || previous[other] >= 0) continue;
				previous[other] = current;
				previousDoor[other] = door;
				queue.add(other);
			}
		}
		if (bloodAdjacent < 0) return List.of();
		List<Door> path = new ArrayList<>();
		for (int at = bloodAdjacent; at != start; at = previous[at]) {
			if (at < 0 || previousDoor[at] == null) return null;
			path.add(previousDoor[at]);
		}
		List<Door> ordered = new ArrayList<>();
		for (int i = path.size() - 1; i >= 0; i--) if (path.get(i).type == Type.WITHER) ordered.add(path.get(i));
		return List.copyOf(ordered);
	}

	private static int nearestCellIndex(BlockPos position) {
		DungeonGridRules.Cell direct = DungeonGridRules.cellAt(position.getX(), position.getZ());
		if (direct != null) return index(direct.componentX(), direct.componentZ());
		DungeonGridRules.Cell closest = null;
		double closestDistance = Double.POSITIVE_INFINITY;
		for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
			DungeonGridRules.Cell candidate = DungeonGridRules.cellAt(position.getX() + dx, position.getZ() + dz);
			if (candidate == null) continue;
			double centerX = candidate.baseX() + DungeonGridRules.ROOM_SIZE / 2.0;
			double centerZ = candidate.baseZ() + DungeonGridRules.ROOM_SIZE / 2.0;
			double x = position.getX() - centerX, z = position.getZ() - centerZ;
			double distance = x * x + z * z;
			if (distance < closestDistance) { closest = candidate; closestDistance = distance; }
		}
		return closest == null ? -1 : index(closest.componentX(), closest.componentZ());
	}

	private static double distanceSquared(Door door, BlockPos position) {
		double x = door.centerX() - position.getX();
		double y = door.centerY() - position.getY();
		double z = door.centerZ() - position.getZ();
		return x * x + y * y + z * z;
	}

	private static Door inspect(ClientLevel level, Edge edge) {
		int x = DungeonGridRules.GRID_MIN + edge.cellX * DungeonGridRules.CELL_STRIDE;
		int z = DungeonGridRules.GRID_MIN + edge.cellZ * DungeonGridRules.CELL_STRIDE;
		int planeX = edge.alongX ? x + DungeonGridRules.ROOM_SIZE : x + 15;
		int planeZ = edge.alongX ? z + 15 : z + DungeonGridRules.ROOM_SIZE;
		BlockPos center = new BlockPos(planeX, DungeonGridRules.SCAN_MIN_Y, planeZ);
		BlockPos otherChunk = edge.alongX ? center.east() : center.south();
		if (!level.hasChunkAt(center) || !level.hasChunkAt(otherChunk)) return null;
		BlockState block = level.getBlockState(center);
		Type type;
		if (containsDoorPlaneBlock(level, edge, planeX, planeZ, Blocks.COAL_BLOCK)) type = Type.WITHER;
		else if (containsDoorPlaneBlock(level, edge, planeX, planeZ, Blocks.RED_TERRACOTTA)) type = Type.BLOOD;
		else if ((block.isAir() || block.is(Blocks.BARRIER)) && hasNormalFrame(level, edge, planeX, planeZ)) type = Type.NORMAL;
		else return null;
		int firstCell = index(edge.cellX, edge.cellZ);
		int secondCell = edge.alongX ? index(edge.cellX + 1, edge.cellZ) : index(edge.cellX, edge.cellZ + 1);
		return centeredDoor(type, firstCell, secondCell, center);
	}

	/** Samples the complete 3 by 4 doorway opening; a single centre-block read missed open Blood gates. */
	private static boolean containsDoorPlaneBlock(ClientLevel level, Edge edge, int x, int z,
		net.minecraft.world.level.block.Block target) {
		for (int side = -1; side <= 1; side++) for (int height = 0; height <= 3; height++) {
			BlockPos sample = edge.alongX
				? new BlockPos(x, DungeonGridRules.SCAN_MIN_Y + height, z + side)
				: new BlockPos(x + side, DungeonGridRules.SCAN_MIN_Y + height, z);
			if (level.hasChunkAt(sample) && level.getBlockState(sample).is(target)) return true;
		}
		return false;
	}

	/** Keeps the 3×4×3 doorway centered on its detected one-block door plane. */
	static Door centeredDoor(Type type, int firstCell, int secondCell, BlockPos planeCenter) {
		return new Door(type, firstCell, secondCell, planeCenter.getX() - 1,
			planeCenter.getY(), planeCenter.getZ() - 1, 3, 4, 3);
	}

	private static boolean hasNormalFrame(ClientLevel level, Edge edge, int x, int z) {
		int floorY = DungeonGridRules.SCAN_MIN_Y;
		int solidJambs = 0;
		for (int side : new int[]{-2, 2}) for (int height = 1; height <= 3; height++) {
			BlockPos jamb = edge.alongX
				? new BlockPos(x, floorY + height, z + side)
				: new BlockPos(x + side, floorY + height, z);
			if (!level.hasChunkAt(jamb)) return false;
			if (!level.getBlockState(jamb).isAir()) solidJambs++;
		}
		return solidJambs >= 4;
	}

	private static List<Edge> edges() {
		List<Edge> edges = new ArrayList<>(60);
		for (int z = 0; z < GRID_CELLS; z++) for (int x = 0; x < GRID_CELLS; x++) {
			if (x + 1 < GRID_CELLS) edges.add(new Edge(x, z, true));
			if (z + 1 < GRID_CELLS) edges.add(new Edge(x, z, false));
		}
		return List.copyOf(edges);
	}

	private static int index(int x, int z) { return z * GRID_CELLS + x; }

	private record Edge(int cellX, int cellZ, boolean alongX) { }
	public enum Type { NORMAL, WITHER, BLOOD }
	public record Door(Type type, int firstCell, int secondCell, int x, int y, int z,
		int width, int height, int depth) {
		public double centerX() { return x + width / 2.0; }
		public double centerY() { return y + height / 2.0; }
		public double centerZ() { return z + depth / 2.0; }
	}
}
