package geiler.addons.client.dungeon;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

/** Pure parsing for the room colors and grid encoded in Hypixel's dungeon map pixels. */
public final class DungeonMapGeometry {
	public static final int MAP_SIZE = 128;
	public static final int ENTRANCE_COLOR = 30;
	private static final int MAX_CONNECTED_CELLS = 36;
	private static final int MIN_ROOM_PIXELS = 6;
	private static final int MAX_ROOM_PIXELS = MAP_SIZE - 1;
	private static final int MAP_GAP_PIXELS = 4;
	private static final int MAP_SEARCH_STEP = 10;
	private static final int ROOM_BLOCKS = 32;
	private static final int RED_CHECKMARK_COLOR = 18;
	private static final int WHITE_CHECKMARK_COLOR = 34;
	private static final int GREEN_CHECKMARK_COLOR = 30;

	private DungeonMapGeometry() { }

	/** Finds the entrance using the pinned source's 10-pixel cardinal search and inset origin. */
	public static Calibration calibrate(byte[] colors, MapPoint playerMapPosition) {
		if (!validColors(colors)) return Calibration.failed("map color buffer is unavailable or incomplete");
		if (!inside(playerMapPosition)) return Calibration.failed("player map marker is outside the 128x128 map");
		Queue<MapPoint> pending = new ArrayDeque<>();
		Set<MapPoint> visited = new HashSet<>();
		pending.add(playerMapPosition);
		visited.add(playerMapPosition);
		while (!pending.isEmpty()) {
			MapPoint point = pending.remove();
			if (colorAt(colors, point.x(), point.z()) == ENTRANCE_COLOR) {
				Calibration candidate = entranceTileAt(colors, point);
				if (candidate.calibrated()) return candidate;
			}
			for (int[] direction : CARDINAL_DIRECTIONS) {
				MapPoint next = new MapPoint(point.x() + direction[0] * MAP_SEARCH_STEP,
					point.z() + direction[1] * MAP_SEARCH_STEP);
				if (inside(next) && visited.add(next)) pending.add(next);
			}
		}
		return Calibration.failed("the player-anchored map search found no entrance tile");
	}

	/** Resolves the map room type and connected tile offsets for a calibrated map. */
	public static RoomGeometry roomAt(byte[] colors, MapPoint playerMapPosition, Calibration calibration) {
		if (!validColors(colors)) return RoomGeometry.unavailable("map color buffer is unavailable or incomplete");
		if (calibration == null || !calibration.calibrated()) {
			return RoomGeometry.unavailable(calibration == null ? "map calibration was not attempted" : calibration.reason());
		}
		if (!inside(playerMapPosition)) return RoomGeometry.unavailable("player map marker is outside the 128x128 map");

		int stride = calibration.roomPixelSize() + MAP_GAP_PIXELS;
		MapPoint currentMapCell = roomMapCell(playerMapPosition, calibration.entrance(), stride);
		if (!inside(currentMapCell)) return RoomGeometry.unavailable("current map cell is outside the map image");
		byte color = (byte) colorAt(colors, currentMapCell.x(), currentMapCell.z());
		RoomType type = RoomType.fromColor(color);
		if (type == RoomType.UNKNOWN) {
			return new RoomGeometry(true, calibration, currentMapCell, color, type, List.of(new CellOffset(0, 0)),
				"1x1", "current room tile is not drawn with a recognized map color");
		}

		List<CellOffset> cells = type == RoomType.ROOM
			? connectedRoomOffsets(colors, currentMapCell, stride, calibration.roomPixelSize(), color)
			: List.of(new CellOffset(0, 0));
		if (cells.isEmpty()) cells = List.of(new CellOffset(0, 0));
		return new RoomGeometry(true, calibration, currentMapCell, color, type, cells, shape(cells), null);
	}

	/**
	 * Reads the same per-segment checkmark pixels Skyblocker uses for room clear state.
	 * Green means cleared with secrets, white means cleared without all secrets, and red marks a failed
	 * room. The checkmark scan deliberately remains unknown for special map types without room clears.
	 */
	public static RoomClearStatus roomClearStatus(byte[] colors, RoomGeometry room) {
		if (!validColors(colors) || room == null || !room.calibrated()
			|| room.roomType() == RoomType.UNKNOWN || room.roomType() == RoomType.ENTRANCE
			|| room.roomType() == RoomType.BLOOD || room.roomType() == RoomType.FAIRY
			|| room.connectedCells().isEmpty()) return RoomClearStatus.UNKNOWN;
		int roomSize = room.calibration().roomPixelSize();
		int halfRoomSize = roomSize / 2;
		if (halfRoomSize <= 0) return RoomClearStatus.UNKNOWN;
		int stride = roomSize + MAP_GAP_PIXELS;
		MapPoint base = room.currentMapCell();
		for (CellOffset segment : room.connectedCells()) {
			int middleX = base.x() + segment.x() * stride + halfRoomSize;
			int middleZ = base.z() + segment.z() * stride + halfRoomSize;
			for (int offset = 0; offset < halfRoomSize; offset++) {
				int color = colorAt(colors, middleX, middleZ + offset);
				if (color == GREEN_CHECKMARK_COLOR) return RoomClearStatus.CLEARED_GREEN;
				if (color == WHITE_CHECKMARK_COLOR) return RoomClearStatus.CLEARED_WHITE;
				if (color == RED_CHECKMARK_COLOR) return RoomClearStatus.FAILED_RED;
			}
		}
		return RoomClearStatus.UNCLEARED;
	}

	public enum RoomClearStatus {
		UNKNOWN, UNCLEARED, CLEARED_WHITE, CLEARED_GREEN, FAILED_RED;
		public boolean cleared() { return this == CLEARED_WHITE || this == CLEARED_GREEN; }
	}

	/** Calculates the map's 32-block room origin using the same half-open cell convention as the game. */
	public static WorldCell physicalCellAt(double worldX, double worldZ) {
		if (!Double.isFinite(worldX) || !Double.isFinite(worldZ)
			|| worldX < Integer.MIN_VALUE + 16.0 || worldX > Integer.MAX_VALUE - 16.0
			|| worldZ < Integer.MIN_VALUE + 16.0 || worldZ > Integer.MAX_VALUE - 16.0) return null;
		int roundedX = (int) (worldX + 8.5);
		int roundedZ = (int) (worldZ + 8.5);
		int baseX = roundedX - Math.floorMod(roundedX, ROOM_BLOCKS) - 8;
		int baseZ = roundedZ - Math.floorMod(roundedZ, ROOM_BLOCKS) - 8;
		return new WorldCell(baseX, baseZ);
	}

	/** Finds the top-left map pixel for the tile containing the local player's map marker. */
	static MapPoint roomMapCell(MapPoint player, MapPoint entrance, int stride) {
		if (player == null || entrance == null || stride <= 0) return null;
		int offsetX = Math.floorMod(entrance.x(), stride);
		int offsetZ = Math.floorMod(entrance.z(), stride);
		int relativeX = player.x() + 2 - offsetX;
		int relativeZ = player.z() + 2 - offsetZ;
		return new MapPoint(relativeX - Math.floorMod(relativeX, stride) + offsetX,
			relativeZ - Math.floorMod(relativeZ, stride) + offsetZ);
	}

	static List<CellOffset> connectedRoomOffsets(byte[] colors, MapPoint start, int stride, int roomSize, byte roomColor) {
		if (!validColors(colors) || !inside(start) || stride <= 0) return List.of();
		ArrayDeque<MapPoint> pending = new ArrayDeque<>();
		Set<MapPoint> visited = new HashSet<>();
		pending.add(start);
		visited.add(start);
		while (!pending.isEmpty() && visited.size() <= MAX_CONNECTED_CELLS) {
			MapPoint point = pending.removeFirst();
			// Skyblocker's pinned traversal tests the north/west edge of each shared gap; do not
			// substitute a midpoint heuristic, since Hypixel's map segments are asymmetric there.
			addConnectedTile(colors, new MapPoint(point.x() - stride, point.z()),
				point.x() - 1, point.z(), roomColor, visited, pending);
			addConnectedTile(colors, new MapPoint(point.x(), point.z() - stride),
				point.x(), point.z() - 1, roomColor, visited, pending);
			addConnectedTile(colors, new MapPoint(point.x() + stride, point.z()),
				point.x() + roomSize, point.z(), roomColor, visited, pending);
			addConnectedTile(colors, new MapPoint(point.x(), point.z() + stride),
				point.x(), point.z() + roomSize, roomColor, visited, pending);
		}
		if (!pending.isEmpty() || visited.size() > MAX_CONNECTED_CELLS) return List.of();
		return visited.stream()
			.map(point -> new CellOffset((point.x() - start.x()) / stride, (point.z() - start.z()) / stride))
			.sorted(Comparator.comparingInt(CellOffset::x).thenComparingInt(CellOffset::z))
			.toList();
	}

	private static void addConnectedTile(byte[] colors, MapPoint candidate,
		int gapX, int gapZ, byte roomColor, Set<MapPoint> visited, ArrayDeque<MapPoint> pending) {
		if (!inside(candidate) || colorAt(colors, gapX, gapZ) != Byte.toUnsignedInt(roomColor)) return;
		if (visited.add(candidate)) pending.addLast(candidate);
	}

	static String shape(List<CellOffset> cells) {
		if (cells == null || cells.isEmpty()) return "unknown";
		int minX = cells.stream().mapToInt(CellOffset::x).min().orElse(0);
		int maxX = cells.stream().mapToInt(CellOffset::x).max().orElse(0);
		int minZ = cells.stream().mapToInt(CellOffset::z).min().orElse(0);
		int maxZ = cells.stream().mapToInt(CellOffset::z).max().orElse(0);
		int width = maxX - minX + 1;
		int depth = maxZ - minZ + 1;
		if (cells.size() == 1) return "1x1";
		if (width == 1 && depth == cells.size() || depth == 1 && width == cells.size()) return "1x" + cells.size();
		if (width == 2 && depth == 2 && cells.size() == 4) return "2x2";
		if (width == 2 && depth == 2 && cells.size() == 3) return "L";
		return "custom-" + cells.size();
	}

	private static Calibration entranceTileAt(byte[] colors, MapPoint point) {
		int left = point.x();
		while (left > 0 && colorAt(colors, left - 1, point.z()) == ENTRANCE_COLOR) left--;
		int top = point.z();
		while (top > 0 && colorAt(colors, left, top - 1) == ENTRANCE_COLOR) top--;
		int width = runLength(colors, left, top, 1, 0, ENTRANCE_COLOR);
		if (width < MIN_ROOM_PIXELS || width > MAX_ROOM_PIXELS) {
			return Calibration.failed("entrance color run is too short or exceeds the map bounds");
		}
		int insetTop = top + 1;
		// Skyblocker's getMapEntrancePosAndRoomSizeAt returns the room origin at top + 1,
		// but measures mapRoomSize from the pre-inset top row. Do not require a second row:
		// the upstream detector only needs a horizontal run longer than five pixels.
		if (!inside(left, insetTop)) return Calibration.failed("entrance top-left inset is outside the map");
		return new Calibration(true, new MapPoint(left, insetTop), width, null);
	}

	private static int runLength(byte[] colors, int x, int z, int stepX, int stepZ, int color) {
		int length = 0;
		while (inside(x + length * stepX, z + length * stepZ)
			&& colorAt(colors, x + length * stepX, z + length * stepZ) == color) length++;
		return length;
	}

	private static boolean validColors(byte[] colors) {
		return colors != null && colors.length >= MAP_SIZE * MAP_SIZE;
	}

	private static int colorAt(byte[] colors, int x, int z) {
		return inside(x, z) ? Byte.toUnsignedInt(colors[x + (z << 7)]) : -1;
	}

	private static boolean inside(MapPoint point) { return point != null && inside(point.x(), point.z()); }
	private static boolean inside(int x, int z) { return x >= 0 && z >= 0 && x < MAP_SIZE && z < MAP_SIZE; }
	private static final int[][] CARDINAL_DIRECTIONS = {{-1, 0}, {0, -1}, {1, 0}, {0, 1}};

	public enum RoomType {
		ENTRANCE(30, "entrance"), ROOM(63, "room"), PUZZLE(66, "puzzle"), TRAP(62, "trap"),
		MINIBOSS(74, "miniboss"), FAIRY(82, "fairy"), BLOOD(18, "blood"), UNKNOWN(85, "unknown");

		private final int mapColor;
		private final String label;
		RoomType(int mapColor, String label) { this.mapColor = mapColor; this.label = label; }
		public String label() { return label; }
		public static RoomType fromColor(int color) {
			for (RoomType type : values()) if (type != UNKNOWN && type.mapColor == color) return type;
			return UNKNOWN;
		}
	}

	public record MapPoint(int x, int z) { }
	public record WorldCell(int baseX, int baseZ) { }
	public record CellOffset(int x, int z) { }
	public record Calibration(boolean calibrated, MapPoint entrance, int roomPixelSize, String reason) {
		static Calibration failed(String reason) { return new Calibration(false, null, 0, reason); }
	}
	public record RoomGeometry(boolean calibrated, Calibration calibration, MapPoint currentMapCell, byte mapColor,
		RoomType roomType, List<CellOffset> connectedCells, String shape, String fallbackReason) {
		public RoomGeometry {
			connectedCells = connectedCells == null ? List.of() : List.copyOf(connectedCells);
		}
		static RoomGeometry unavailable(String reason) {
			return new RoomGeometry(false, null, null, (byte) -1, RoomType.UNKNOWN, List.of(), "unknown", reason);
		}
	}
}
