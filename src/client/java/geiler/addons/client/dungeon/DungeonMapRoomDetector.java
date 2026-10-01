package geiler.addons.client.dungeon;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.List;

/** Reads the existing Hypixel map state as optional dungeon-room evidence on client ticks. */
public final class DungeonMapRoomDetector {
	private static final MapId DEFAULT_MAP_ID = new MapId(1024);
	private static final long ENTRY_SCAN_INTERVAL_NANOS = 1_000_000_000L;
	private static volatile Reading current = Reading.unavailable(Status.NOT_SCANNED, null,
		"map detector has not run yet");
	private static volatile DungeonMapGeometry.RoomClearStatus currentRoomClearStatus =
		DungeonMapGeometry.RoomClearStatus.UNKNOWN;
	private static final long GEOMETRY_REFRESH_TICKS = 20L;
	private static MapId cachedMapId;
	private static long lastEntryScanNanos;
	private static long clientTick;
	private static long lastGeometryRefreshTick = Long.MIN_VALUE;
	private static MapItemSavedData geometryMap;
	private static DungeonMapGeometry.Calibration cachedCalibration;
	private static DungeonMapGeometry.RoomGeometry cachedGeometry;

	private DungeonMapRoomDetector() { }

	/** Reads only the local inventory and map saved data; it never requests a world chunk. */
	public static void tick(Minecraft client) {
		if (client == null || client.player == null || client.level == null) {
			if (client == null || client.player == null) {
				cachedMapId = null;
				lastEntryScanNanos = 0L;
				resetGeometryCache();
			}
			current = Reading.unavailable(Status.NO_CLIENT_WORLD, null, "client world or player is unavailable");
			currentRoomClearStatus = DungeonMapGeometry.RoomClearStatus.UNKNOWN;
			return;
		}
		clientTick++;
		MapIdResolution resolution = currentMapId(client);
		MapId mapId = resolution.mapId();
		MapItemSavedData map = MapItem.getSavedData(mapId, client.level);
		if (map == null || map.colors == null) {
			resetGeometryCache();
			current = Reading.unavailable(Status.MAP_DATA_MISSING, mapId, resolution.source(),
				"the current level has no saved data for this map id");
			currentRoomClearStatus = DungeonMapGeometry.RoomClearStatus.UNKNOWN;
			return;
		}

		MapDecoration self = localPlayerFrame(map);
		if (self == null) {
			current = Reading.unavailable(Status.PLAYER_MARKER_MISSING, mapId, resolution.source(),
				"the map has no local-player frame marker");
			currentRoomClearStatus = DungeonMapGeometry.RoomClearStatus.UNKNOWN;
			return;
		}

		DungeonMapGeometry.MapPoint marker = new DungeonMapGeometry.MapPoint((self.x() >> 1) + 64,
			(self.y() >> 1) + 64);
		if (geometryMap != map) {
			resetGeometryCache();
			geometryMap = map;
		}
		if (cachedCalibration == null || !cachedCalibration.calibrated()
			|| !calibrationStillPresent(map.colors, cachedCalibration)) {
			cachedCalibration = DungeonMapGeometry.calibrate(map.colors, marker);
			cachedGeometry = null;
			lastGeometryRefreshTick = Long.MIN_VALUE;
		}
		DungeonMapGeometry.Calibration calibration = cachedCalibration;
		if (!calibration.calibrated()) {
			current = Reading.unavailable(Status.CALIBRATION_UNAVAILABLE, mapId, resolution.source(),
				marker, calibration, calibration.reason());
			currentRoomClearStatus = DungeonMapGeometry.RoomClearStatus.UNKNOWN;
			return;
		}
		DungeonMapGeometry.MapPoint mapCell = DungeonMapGeometry.roomMapCell(marker, calibration.entrance(),
			calibration.roomPixelSize() + 4);
		boolean refreshGeometry = shouldRefreshGeometry(true, cachedGeometry != null,
			cachedGeometry == null ? null : cachedGeometry.currentMapCell(), mapCell,
			clientTick - lastGeometryRefreshTick,
			cachedGeometry != null && cachedGeometry.roomType() == DungeonMapGeometry.RoomType.UNKNOWN);
		if (refreshGeometry) {
			cachedGeometry = DungeonMapGeometry.roomAt(map.colors, marker, calibration);
			lastGeometryRefreshTick = clientTick;
		}
		DungeonMapGeometry.RoomGeometry geometry = cachedGeometry;
		DungeonMapGeometry.WorldCell origin = DungeonMapGeometry.physicalCellAt(
			client.player.getX(), client.player.getZ());
		if (origin == null) {
			current = Reading.unavailable(Status.CURRENT_CELL_UNAVAILABLE, mapId, resolution.source(), marker,
				calibration, geometry, "player world coordinates cannot be represented as a catalog grid cell");
			currentRoomClearStatus = DungeonMapGeometry.RoomClearStatus.UNKNOWN;
			return;
		}
		DungeonGridRules.Cell cell = DungeonGridRules.cellAtOrigin(origin.baseX(), origin.baseZ());
		String reason = geometry.fallbackReason();
		Status status = geometry.roomType() == DungeonMapGeometry.RoomType.UNKNOWN
			? Status.ROOM_TYPE_UNAVAILABLE : Status.AVAILABLE;
		current = new Reading(status, mapId, resolution.source(), marker, calibration, geometry, cell, reason);
		currentRoomClearStatus = DungeonMapGeometry.roomClearStatus(map.colors, geometry);
	}

	private static boolean calibrationStillPresent(byte[] colors, DungeonMapGeometry.Calibration calibration) {
		if (colors == null || calibration == null || !calibration.calibrated() || calibration.entrance() == null) return false;
		int x = calibration.entrance().x();
		int z = calibration.entrance().z() - 1;
		return x >= 0 && z >= 0 && x < DungeonMapGeometry.MAP_SIZE && z < DungeonMapGeometry.MAP_SIZE
			&& Byte.toUnsignedInt(colors[x + (z << 7)]) == DungeonMapGeometry.ENTRANCE_COLOR;
	}

	static boolean shouldRefreshGeometry(boolean sameMap, boolean hasCachedGeometry,
		DungeonMapGeometry.MapPoint cachedCell, DungeonMapGeometry.MapPoint playerCell,
		long ticksSinceRefresh, boolean roomUnknown) {
		return !sameMap || !hasCachedGeometry || cachedCell == null || playerCell == null
			|| !cachedCell.equals(playerCell) || ticksSinceRefresh < 0L
			|| ticksSinceRefresh >= GEOMETRY_REFRESH_TICKS || roomUnknown;
	}

	private static void resetGeometryCache() {
		geometryMap = null;
		cachedCalibration = null;
		cachedGeometry = null;
		lastGeometryRefreshTick = Long.MIN_VALUE;
	}

	/**
	 * Cheap entry probe used outside a confirmed dungeon. It checks only the local map marker and
	 * its immediate pixels; calibration and connected-room traversal run only after this returns true.
	 */
	static boolean hasEntryCandidate(Minecraft client) {
		if (client == null || client.player == null || client.level == null) return false;
		MapId mapId = currentMapId(client).mapId();
		MapItemSavedData map = MapItem.getSavedData(mapId, client.level);
		if (map == null || map.colors == null) return false;
		MapDecoration marker = localPlayerFrame(map);
		if (marker == null) return false;
		DungeonMapGeometry.MapPoint point = new DungeonMapGeometry.MapPoint((marker.x() >> 1) + 64,
			(marker.y() >> 1) + 64);
		return hasDungeonMapMarkerColor(map.colors, point);
	}

	/** Package-visible pure predicate for the bounded map entry probe. */
	static boolean hasDungeonMapMarkerColor(byte[] colors, DungeonMapGeometry.MapPoint marker) {
		if (colors == null || colors.length < DungeonMapGeometry.MAP_SIZE * DungeonMapGeometry.MAP_SIZE
			|| marker == null || marker.x() < 0 || marker.z() < 0
			|| marker.x() >= DungeonMapGeometry.MAP_SIZE || marker.z() >= DungeonMapGeometry.MAP_SIZE) return false;
		for (int dz = -1; dz <= 1; dz++) {
			for (int dx = -1; dx <= 1; dx++) {
				int x = marker.x() + dx;
				int z = marker.z() + dz;
				if (x < 0 || z < 0 || x >= DungeonMapGeometry.MAP_SIZE || z >= DungeonMapGeometry.MAP_SIZE) continue;
				int color = Byte.toUnsignedInt(colors[x + (z << 7)]);
				if (DungeonMapGeometry.RoomType.fromColor(color) != DungeonMapGeometry.RoomType.UNKNOWN) return true;
			}
		}
		return false;
	}

	/** Entry calibrations are bounded to at most one attempt per second while context is unconfirmed. */
	static boolean shouldRunEntryScan(long nowNanos) {
		if (!entryScanIntervalElapsed(lastEntryScanNanos, nowNanos)) return false;
		lastEntryScanNanos = nowNanos;
		return true;
	}

	static boolean entryScanIntervalElapsed(long lastScanNanos, long nowNanos) {
		return lastScanNanos == 0L || nowNanos < lastScanNanos
			|| nowNanos - lastScanNanos >= ENTRY_SCAN_INTERVAL_NANOS;
	}

	static void deferUntilDungeonContext() {
		current = Reading.unavailable(Status.NOT_SCANNED, cachedMapId,
			"full map geometry is gated until dungeon context or map-entry evidence is available");
		currentRoomClearStatus = DungeonMapGeometry.RoomClearStatus.UNKNOWN;
	}

	private static MapIdResolution currentMapId(Minecraft client) {
		Inventory inventory = client.player.getInventory();
		List<ItemStack> hotbar = inventory.getNonEquipmentItems();
		ItemStack stack = hotbar.size() > 8 ? hotbar.get(8) : ItemStack.EMPTY;
		boolean hasHotbarMapId = stack != null && stack.is(Items.FILLED_MAP) && stack.has(DataComponents.MAP_ID);
		MapIdResolution resolution = resolveMapId(hasHotbarMapId,
			hasHotbarMapId ? stack.get(DataComponents.MAP_ID) : null, cachedMapId);
		if (resolution.source() == MapIdSource.HOTBAR) cachedMapId = resolution.mapId();
		return resolution;
	}

	private static MapDecoration localPlayerFrame(MapItemSavedData map) {
		for (MapDecoration decoration : map.getDecorations()) {
			if (decoration.type().value().equals(MapDecorationTypes.FRAME.value())) return decoration;
		}
		return null;
	}

	static MapIdResolution resolveMapId(boolean hasHotbarMapId, MapId hotbarMapId, MapId cachedMapId) {
		if (hasHotbarMapId && hotbarMapId != null) return new MapIdResolution(hotbarMapId, MapIdSource.HOTBAR);
		if (cachedMapId != null) return new MapIdResolution(cachedMapId, MapIdSource.CACHED);
		return new MapIdResolution(DEFAULT_MAP_ID, MapIdSource.DEFAULT);
	}

	public static Reading current() { return current; }
	/** True only when the current dungeon map segment carries a white or green clear checkmark. */
	public static boolean currentRoomCleared() { return currentRoomClearStatus.cleared(); }
	public static DungeonMapGeometry.RoomClearStatus currentRoomClearStatus() { return currentRoomClearStatus; }

	public static String diagnostics() {
		Reading reading = current;
		String calibration = reading.calibration() != null && reading.calibration().calibrated()
			? "entrance=" + reading.calibration().entrance() + ", pixelRoomSize=" + reading.calibration().roomPixelSize()
			: "unavailable(" + reading.fallbackReason() + ")";
		String room = reading.geometry() == null ? "unknown"
			: reading.geometry().roomType().label() + "/" + reading.geometry().shape()
			+ " at " + reading.geometry().currentMapCell();
		return "map=" + reading.status() + ", mapId=" + (reading.mapId() == null ? "unknown" : reading.mapId().id())
			+ " [" + reading.mapIdSource() + "]"
			+ ", calibration=" + calibration + ", mapRoom=" + room
			+ ", roomClear=" + currentRoomClearStatus
			+ ", fallback=" + (reading.fallbackReason() == null ? "none" : reading.fallbackReason());
	}

	public enum Status {
		NOT_SCANNED, NO_CLIENT_WORLD, MAP_ITEM_MISSING, MAP_ID_MISSING, MAP_DATA_MISSING,
		PLAYER_MARKER_MISSING, CALIBRATION_UNAVAILABLE, CURRENT_CELL_UNAVAILABLE,
		ROOM_TYPE_UNAVAILABLE, AVAILABLE
	}

	public enum MapIdSource { UNKNOWN, HOTBAR, CACHED, DEFAULT }

	static record MapIdResolution(MapId mapId, MapIdSource source) { }

	public record Reading(Status status, MapId mapId, MapIdSource mapIdSource, DungeonMapGeometry.MapPoint playerMapPosition,
		DungeonMapGeometry.Calibration calibration, DungeonMapGeometry.RoomGeometry geometry,
		DungeonGridRules.Cell currentCell, String fallbackReason) {
		private static Reading unavailable(Status status, MapId mapId, String reason) {
			return new Reading(status, mapId, MapIdSource.UNKNOWN, null, null, null, null, reason);
		}
		private static Reading unavailable(Status status, MapId mapId, MapIdSource mapIdSource, String reason) {
			return new Reading(status, mapId, mapIdSource, null, null, null, null, reason);
		}
		private static Reading unavailable(Status status, MapId mapId, MapIdSource mapIdSource,
			DungeonMapGeometry.MapPoint marker, DungeonMapGeometry.Calibration calibration, String reason) {
			return new Reading(status, mapId, mapIdSource, marker, calibration, null, null, reason);
		}
		private static Reading unavailable(Status status, MapId mapId, MapIdSource mapIdSource,
			DungeonMapGeometry.MapPoint marker,
			DungeonMapGeometry.Calibration calibration, DungeonMapGeometry.RoomGeometry geometry, String reason) {
			return new Reading(status, mapId, mapIdSource, marker, calibration, geometry, null, reason);
		}
		public boolean calibrated() {
			return calibration != null && calibration.calibrated() && geometry != null && geometry.calibrated();
		}
		public boolean hasCurrentCell() { return calibrated() && currentCell != null; }
		public boolean confirmsDungeonRoom() {
			return hasCurrentCell() && geometry.roomType() != DungeonMapGeometry.RoomType.UNKNOWN;
		}
		public String signature() {
			return status + "|" + (currentCell == null ? "none" : currentCell.componentX() + "," + currentCell.componentZ())
				+ "|" + (geometry == null ? "none" : geometry.currentMapCell() + "/" + geometry.roomType()
					+ "/" + geometry.shape() + "/" + geometry.connectedCells());
		}
	}
}
