package geiler.addons.client.dungeon;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import java.util.Comparator;
import java.util.List;

/** Tracks only the calibrated map shape and physical cells occupied by the local room. */
public final class DungeonRoomTracker {
	private static volatile TrackerState state = TrackerState.empty();

	private DungeonRoomTracker() { }

	/**
	 * Call on the client tick after dungeon context has been updated. The tracker is intentionally
	 * dormant outside Catacombs or while Dungeon Mob ESP is disabled.
	 */
	public static void tick(Minecraft client, boolean dungeonMobEspEnabled) {
		boolean hasWorld = client != null && client.level != null;
		boolean hasPlayer = client != null && client.player != null;
		boolean active = shouldTrack(DungeonContextTracker.inDungeon(), dungeonMobEspEnabled, hasWorld, hasPlayer);
		if (!active) {
			clear();
			return;
		}

		ClientLevel level = client.level;
		CellKey playerCell = playerCell(client);
		MapRoomFootprint observed = currentMapFootprint(client);
		state = observe(state, true, level, playerCell, observed);
	}

	/** Pure activation rule used by the offline checks. */
	static boolean shouldTrack(boolean catacombsActive, boolean moduleEnabled,
		boolean hasWorld, boolean hasPlayer) {
		return catacombsActive && moduleEnabled && hasWorld && hasPlayer;
	}

	/** Builds an observation from calibrated map geometry and its corresponding physical grid cells. */
	public static MapRoomFootprint currentMapFootprint(Minecraft client) {
		if (client == null || client.level == null || client.player == null || !DungeonContextTracker.inDungeon()) return null;
		DungeonMapRoomDetector.Reading map = DungeonMapRoomDetector.current();
		if (map == null || !map.hasCurrentCell() || !map.calibrated() || map.geometry() == null
			|| map.geometry().roomType() == DungeonMapGeometry.RoomType.UNKNOWN) return null;

		DungeonGridRules.Cell mapCell = map.currentCell();
		CellKey physicalCell = playerCell(client);
		if (mapCell == null || physicalCell == null
			|| mapCell.componentX() != physicalCell.x() || mapCell.componentZ() != physicalCell.z()) return null;

		List<DungeonMapGeometry.CellOffset> offsets = map.geometry().connectedCells();
		if (offsets == null || offsets.isEmpty()) return null;
		List<CellKey> cells = offsets.stream()
			.map(offset -> new CellKey(mapCell.componentX() + offset.x(), mapCell.componentZ() + offset.z()))
			.filter(DungeonRoomTracker::insideGrid)
			.distinct()
			.sorted(Comparator.comparingInt(CellKey::x).thenComparingInt(CellKey::z))
			.toList();
		// A partial map shape at the edge of the six-by-six dungeon grid is not a confirmed footprint.
		if (cells.size() != offsets.stream().distinct().count() || cells.isEmpty() || !cells.contains(physicalCell)) return null;
		return new MapRoomFootprint(null, cells);
	}

	/** Current confirmed footprint; empty map data may leave the prior footprint retained in-place. */
	public static MapRoomFootprint currentFootprint() {
		return state.footprint();
	}

	public static List<CellKey> confirmedPhysicalRoomCells() {
		MapRoomFootprint footprint = currentFootprint();
		return footprint == null ? List.of() : footprint.cells();
	}

	public static String diagnostics() {
		TrackerState current = state;
		MapRoomFootprint footprint = current.footprint();
		return "tracking=" + (current.world() != null) + ", footprint="
			+ (footprint == null ? "unconfirmed" : footprint.cells().size() + " physical cell(s)");
	}

	/** Drop retained geometry on disable, context exit, world loss, or explicit lifecycle changes. */
	public static void clear() {
		state = TrackerState.empty();
	}

	static TrackerState observe(TrackerState previous, boolean active, Object world,
		CellKey playerCell, MapRoomFootprint observed) {
		if (!active || world == null) return TrackerState.empty();
		MapRoomFootprint validObservation = confirmsPlayerCell(observed, playerCell) ? observed : null;
		if (previous == null || previous.world() != world) {
			return new TrackerState(world, validObservation);
		}

		MapRoomFootprint retained = previous.footprint();
		if (retained == null) return new TrackerState(world, validObservation);
		if (playerCell == null || !retained.containsCell(playerCell)) {
			// The player has left the retained physical cells. A new calibrated observation may
			// establish the next room in this same tick, but the old shape is never carried across.
			return new TrackerState(world, validObservation);
		}
		if (validObservation == null || !sameCells(retained, validObservation)) {
			// Map saved data can disappear or briefly describe another component while loading.
			// Retain the confirmed shape only while the physical player remains inside it.
			return new TrackerState(world, retained);
		}
		return new TrackerState(world, validObservation);
	}

	private static boolean confirmsPlayerCell(MapRoomFootprint footprint, CellKey playerCell) {
		return footprint != null && playerCell != null && footprint.containsCell(playerCell);
	}

	private static boolean sameCells(MapRoomFootprint first, MapRoomFootprint second) {
		return first != null && second != null && first.cells().equals(second.cells());
	}

	private static CellKey playerCell(Minecraft client) {
		if (client == null || client.player == null) return null;
		DungeonGridRules.Cell cell = DungeonGridRules.cellAt(
			client.player.blockPosition().getX(), client.player.blockPosition().getZ());
		return cell == null ? null : new CellKey(cell.componentX(), cell.componentZ());
	}

	private static boolean insideGrid(CellKey cell) {
		return cell != null && cell.x() >= 0 && cell.x() < 6 && cell.z() >= 0 && cell.z() < 6;
	}

	private static boolean containsPhysicalCell(List<CellKey> cells, double worldX, double worldZ) {
		if (cells == null || cells.isEmpty() || !Double.isFinite(worldX) || !Double.isFinite(worldZ)
			|| worldX < Integer.MIN_VALUE || worldX > Integer.MAX_VALUE
			|| worldZ < Integer.MIN_VALUE || worldZ > Integer.MAX_VALUE) return false;
		DungeonGridRules.Cell physical = DungeonGridRules.cellAt((int) Math.floor(worldX), (int) Math.floor(worldZ));
		return physical != null && cells.contains(new CellKey(physical.componentX(), physical.componentZ()));
	}

	static record TrackerState(Object world, MapRoomFootprint footprint) {
		static TrackerState empty() { return new TrackerState(null, null); }
	}

	public record CellKey(int x, int z) { }

	/** The key is derived only from the normalized occupied grid cells; it carries no room identity. */
	public record MapRoomFootprint(String footprintKey, List<CellKey> cells) {
		public MapRoomFootprint {
			cells = cells == null ? List.of() : cells.stream().filter(DungeonRoomTracker::insideGrid)
				.distinct().sorted(Comparator.comparingInt(CellKey::x).thenComparingInt(CellKey::z)).toList();
			footprintKey = cells.isEmpty() ? "" : "cells:" + cells.stream()
				.map(cell -> cell.x() + "," + cell.z()).collect(java.util.stream.Collectors.joining(";"));
		}

		public boolean contains(BlockPos position) {
			return position != null && containsPhysicalCell(cells, position.getX(), position.getZ());
		}

		public boolean contains(double worldX, double worldZ) {
			return containsPhysicalCell(cells, worldX, worldZ);
		}

		boolean containsCell(CellKey cell) { return cell != null && cells.contains(cell); }
	}
}
