package geiler.addons.client.dungeon;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.saveddata.maps.MapId;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure round-trip, bounds, phase and trigger checks for the player-authored dungeon features. */
public final class DungeonFeatureChecks {
	private DungeonFeatureChecks() { }

	public static void run() {
		checkDungeonContextSignals();
		checkDungeonFloorSources();
		checkGridRules();
		checkMapGeometry();
		checkMapIdFallback();
		checkDoorBoundsAndKeys();
		checkBloodTracerTarget();
		checkDoorTracerRoomGateAndGrace();
		checkGuidePackages();
		checkGuideMigration();
		checkGuideFlush();
		checkGuideProgress();
	}

	private static void checkDoorBoundsAndKeys() {
		DungeonDoorTracker.Door door = DungeonDoorTracker.centeredDoor(
			DungeonDoorTracker.Type.WITHER, 1, 2, new BlockPos(40, 70, -9));
		assertEquals(39, door.x(), "the door highlight extends one block before the doorway plane");
		assertEquals(-10, door.z(), "the door highlight extends one block behind the doorway plane");
		assertEquals(3, door.width(), "door highlight spans three blocks across");
		assertEquals(4, door.height(), "door highlight spans four blocks high");
		assertEquals(3, door.depth(), "door highlight spans three blocks deep");
		assertEquals(40.5, door.centerX(), "door bounds remain centered across the doorway plane");
		assertEquals(-8.5, door.centerZ(), "door bounds remain centered along the doorway plane");

		assertTrue(DungeonKeyRules.isMatchingKey("§cWither\uE000 Key", DungeonDoorTracker.Type.WITHER),
			"formatting and invisible private-use name characters do not hide a matching Wither Key");
		assertTrue(DungeonKeyRules.isMatchingKey("Blood Key", DungeonDoorTracker.Type.BLOOD),
			"the Blood Key matches its own door type");
		assertFalse(DungeonKeyRules.isMatchingKey("Blood Key", DungeonDoorTracker.Type.WITHER),
			"a Blood Key never enables the Wither palette");
		assertFalse(DungeonKeyRules.isMatchingKey("Wither Key", DungeonDoorTracker.Type.BLOOD),
			"a Wither Key never enables the Blood palette");
		assertFalse(DungeonKeyRules.isMatchingKey("Wither Keychain", DungeonDoorTracker.Type.WITHER),
			"partial key-name matches do not change a door color");
		assertTrue(DungeonKeyRules.isPickupForPlayer("Alice has obtained Wither Key!", "Alice", DungeonDoorTracker.Type.WITHER),
			"a local Wither Key pickup can immediately update its door palette");
		assertFalse(DungeonKeyRules.isPickupForPlayer("Bob has obtained Wither Key!", "Alice", DungeonDoorTracker.Type.WITHER),
			"another player's key pickup cannot enable the local door palette");
		assertFalse(DungeonKeyRules.isPickupForPlayer("A Wither Key was picked up!", "Alice", DungeonDoorTracker.Type.WITHER),
			"a pickup line without player identity cannot override local inventory state");
		assertTrue(DungeonKeyRules.isDoorOpenedByPlayer("Alice opened a WITHER door!", "Alice", DungeonDoorTracker.Type.WITHER),
			"using a local Wither Key clears the corresponding chat fallback state");
		assertFalse(DungeonKeyRules.isDoorOpenedByPlayer("Bob opened a WITHER door!", "Alice", DungeonDoorTracker.Type.WITHER),
			"another player's door does not consume the local key fallback state");

	}

	private static void checkBloodTracerTarget() {
		DungeonDoorTracker.Door bloodDoor = DungeonDoorTracker.centeredDoor(
			DungeonDoorTracker.Type.BLOOD, 7, 8, new BlockPos(40, 70, -9));
		BlockPos closedPlaneBlock = new BlockPos(40, 70, -9);
		assertTrue(BloodDoorTraceRules.hasClosedBloodDoorBlocks(bloodDoor, closedPlaneBlock::equals),
			"a tracked Blood doorway with a live red door block remains a valid target");
		assertFalse(BloodDoorTraceRules.hasClosedBloodDoorBlocks(bloodDoor, ignored -> false),
			"a tracked Blood doorway stops being valid after its door blocks disappear");
		DungeonGridRules.Cell adjacentRoom = new DungeonGridRules.Cell(1, 1, -168, -168);
		List<DungeonRoomTracker.CellKey> singleRoom = List.of(new DungeonRoomTracker.CellKey(1, 1));
		assertTrue(BloodDoorTraceRules.shouldTrace(true, bloodDoor, true, true,
			DungeonMapGeometry.RoomType.ROOM, "1x1", adjacentRoom, singleRoom, true),
			"the tracer follows a closed Blood doorway only from its directly connected current room");
		assertFalse(BloodDoorTraceRules.shouldTrace(false, bloodDoor, true, true,
			DungeonMapGeometry.RoomType.ROOM, "1x1", adjacentRoom, singleRoom, true),
			"disabling the Blood tracer suppresses its target");
		assertFalse(BloodDoorTraceRules.shouldTrace(true, null, true, true,
			DungeonMapGeometry.RoomType.ROOM, "1x1", adjacentRoom, singleRoom, true),
			"the Blood tracer stays hidden without a tracked target");
		assertFalse(BloodDoorTraceRules.shouldTrace(true, bloodDoor, false, true,
			DungeonMapGeometry.RoomType.ROOM, "1x1", adjacentRoom, singleRoom, true),
			"the Blood tracer stays hidden when the tracked doorway is open or invalid");
		assertFalse(BloodDoorTraceRules.shouldTrace(true, bloodDoor, true, true,
			DungeonMapGeometry.RoomType.ENTRANCE, "1x1", adjacentRoom, singleRoom, true),
			"the Blood tracer never appears at the map Entrance/spawn tile");
		assertFalse(BloodDoorTraceRules.shouldTrace(true, bloodDoor, true, true,
			DungeonMapGeometry.RoomType.ROOM, "1x1", adjacentRoom, singleRoom, false),
			"the tracer stays hidden when the player is outside the detected physical room footprint");
		assertFalse(BloodDoorTraceRules.shouldTrace(true, bloodDoor, true, false,
			DungeonMapGeometry.RoomType.ROOM, "1x1", adjacentRoom, singleRoom, true),
			"uncalibrated map cells cannot authorize the tracer");
		assertFalse(BloodDoorTraceRules.shouldTrace(true, bloodDoor, true, true,
			DungeonMapGeometry.RoomType.ROOM, "1x1", new DungeonGridRules.Cell(4, 4, -72, -72),
			List.of(new DungeonRoomTracker.CellKey(4, 4)), true),
			"an unrelated current room does not trace a distant Blood doorway");
		assertFalse(BloodDoorTraceRules.isBoundaryOfShape(
			DungeonDoorTracker.centeredDoor(DungeonDoorTracker.Type.BLOOD, 7, 14, new BlockPos(40, 70, -9)), singleRoom),
			"diagonal/nonadjacent endpoint pairs do not identify a valid boundary");
		assertFalse(BloodDoorTraceRules.isBoundaryOfShape(
			DungeonDoorTracker.centeredDoor(DungeonDoorTracker.Type.BLOOD, 5, 6, new BlockPos(40, 70, -9)),
			List.of(new DungeonRoomTracker.CellKey(5, 0))),
			"linear index wrap between opposite grid edges is not cell adjacency");
		List<DungeonRoomTracker.CellKey> twoTileShape = List.of(
			new DungeonRoomTracker.CellKey(1, 1), new DungeonRoomTracker.CellKey(2, 1));
		DungeonGridRules.Cell secondTile = new DungeonGridRules.Cell(2, 1, -136, -168);
		DungeonDoorTracker.Door outsideShapeBloodDoor = DungeonDoorTracker.centeredDoor(
			DungeonDoorTracker.Type.BLOOD, 8, 9, new BlockPos(40, 70, -9));
		assertTrue(BloodDoorTraceRules.shouldTrace(true, outsideShapeBloodDoor, true, true,
			DungeonMapGeometry.RoomType.ROOM, "1x2", secondTile, twoTileShape, true),
			"a player in any occupied component of the connected multi-cell room traces its exact outer Blood edge");
		assertFalse(BloodDoorTraceRules.isBoundaryOfShape(bloodDoor, twoTileShape),
			"a tracked Blood doorway internal to a multi-cell room is not an exit boundary");
		DungeonDoorTracker.Door bloodDoorAlongZ = DungeonDoorTracker.centeredDoor(
			DungeonDoorTracker.Type.BLOOD, 7, 13, new BlockPos(40, 70, -9));
		assertTrue(BloodDoorTraceRules.hasClosedBloodDoorBlocks(bloodDoorAlongZ, closedPlaneBlock::equals),
			"the live doorway check handles a Blood entrance along either grid axis");
		DungeonDoorTracker.Door witherDoor = DungeonDoorTracker.centeredDoor(
			DungeonDoorTracker.Type.WITHER, 7, 8, new BlockPos(40, 70, -9));
		assertFalse(BloodDoorTraceRules.shouldTrace(true, witherDoor, true, true,
			DungeonMapGeometry.RoomType.ROOM, "1x1", adjacentRoom, singleRoom, true),
			"a Wither doorway cannot become a Blood tracer target");
		assertFalse(BloodDoorTraceRules.bloodSequenceAllowsTrace(witherDoor),
			"an actually targeted closed Wither suppresses the downstream Blood tracer");
		assertTrue(BloodDoorTraceRules.bloodSequenceAllowsTrace(null),
			"opening the Wither clears its trace so Blood can proceed through the existing adjacency gate");
		DungeonDoorTracker.Door unrelatedWither = DungeonDoorTracker.centeredDoor(
			DungeonDoorTracker.Type.WITHER, 22, 23, new BlockPos(200, 70, -9));
		assertTrue(unrelatedWither.type() == DungeonDoorTracker.Type.WITHER
			&& BloodDoorTraceRules.bloodSequenceAllowsTrace(null)
			&& BloodDoorTraceRules.shouldTrace(true, bloodDoor, true, true,
				DungeonMapGeometry.RoomType.ROOM, "1x1", adjacentRoom, singleRoom, true),
			"an unrelated closed Wither elsewhere is not the active trace and cannot suppress a directly connected Blood door");
	}

	private static void checkDoorTracerRoomGateAndGrace() {
		List<DungeonRoomTracker.CellKey> oneRoom = List.of(new DungeonRoomTracker.CellKey(1, 1));
		DungeonGridRules.Cell current = new DungeonGridRules.Cell(1, 1, -168, -168);
		DungeonDoorTracker.Door wither = DungeonDoorTracker.centeredDoor(
			DungeonDoorTracker.Type.WITHER, 7, 8, new BlockPos(40, 70, -9));
		assertTrue(BloodDoorTraceRules.eligibleConnectedRoom(true, wither, true, true,
			DungeonMapGeometry.RoomType.ROOM, "1x1", current, oneRoom, true),
			"a closed Wither door traces only from its validated connected room");
		assertFalse(BloodDoorTraceRules.eligibleConnectedRoom(true, wither, true, true,
			DungeonMapGeometry.RoomType.ROOM, "1x1", new DungeonGridRules.Cell(4, 4, -72, -72),
			List.of(new DungeonRoomTracker.CellKey(4, 4)), true),
			"an unrelated room cannot authorize a Wither door tracer");
		assertFalse(BloodDoorTraceRules.eligibleConnectedRoom(true, wither, false, true,
			DungeonMapGeometry.RoomType.ROOM, "1x1", current, oneRoom, true),
			"an opened Wither door is not eligible for grace");
		assertTrue(DungeonDoorAdjacency.bordersCurrentRoom(wither, current, null),
			"physical player cell and adjacent door cells suffice when identity, rotation, and map shape are unavailable");
		assertFalse(DungeonDoorAdjacency.bordersCurrentRoom(wither,
			new DungeonGridRules.Cell(4, 4, -72, -72), null),
			"a physically unrelated player cell cannot trace this door");
		assertFalse(DungeonDoorAdjacency.bordersCurrentRoom(
			DungeonDoorTracker.centeredDoor(DungeonDoorTracker.Type.WITHER, 5, 6, new BlockPos(40, 70, -9)),
			current, null), "linear-index wrap is not accepted as geometric door adjacency");
		Set<Integer> twoCellShape = DungeonDoorAdjacency.footprint(current, List.of(
			new DungeonMapGeometry.CellOffset(0, 0), new DungeonMapGeometry.CellOffset(1, 0)));
		assertEquals(Set.of(7, 8), twoCellShape,
			"multi-cell map geometry expands from the player's local cell without a recognized room identity");
		assertFalse(DungeonDoorAdjacency.bordersCurrentRoom(wither, current, List.of(
			new DungeonMapGeometry.CellOffset(0, 0), new DungeonMapGeometry.CellOffset(1, 0))),
			"a doorway internal to the current multi-cell room is not considered its boundary");
		assertEquals(Set.of(7), DungeonDoorAdjacency.footprint(current,
			List.of(new DungeonMapGeometry.CellOffset(40, 0))),
			"malformed map footprint falls back to the reliable local player cell");

		DungeonDoorTraceGrace grace = new DungeonDoorTraceGrace();
		Object world = new Object();
		assertEquals(wither, grace.update(true, world, DungeonFloor.F1, wither, true, true, 100),
			"a currently eligible closed door begins its trace");
		assertEquals(wither, grace.update(true, world, DungeonFloor.F1, wither, true, false, 299),
			"the selected target remains visible during the ten-second room-leave grace");
		assertEquals(null, grace.update(true, world, DungeonFloor.F1, wither, true, false, 300),
			"the target is hidden when ten seconds have elapsed outside the eligible room");
		assertEquals(null, grace.update(true, world, DungeonFloor.F1, wither, false, false, 301),
			"an opened selected door clears its retained target immediately");
		assertEquals(wither, grace.update(true, world, DungeonFloor.F1, wither, true, true, 302),
			"a closed connected target can begin tracing again after returning");
		assertEquals(null, grace.update(true, new Object(), DungeonFloor.F1, wither, true, false, 303),
			"a world change cannot carry a previous door target into the new world");
		assertEquals(null, grace.update(true, world, DungeonFloor.F1, wither, true, false, 304),
			"a newly selected target is not traced before the player enters its connected room");

		DungeonDoorTracker.Door another = DungeonDoorTracker.centeredDoor(
			DungeonDoorTracker.Type.WITHER, 8, 9, new BlockPos(72, 70, -9));
		assertEquals(wither, grace.update(true, world, DungeonFloor.F1, wither, true, true, 305),
			"re-entering the connected room refreshes the eligible trace target");
		assertEquals(wither, grace.update(true, world, DungeonFloor.F1, another, true, false, 306),
			"a newly selected but non-adjacent door does not erase the previous target's ten-second grace");

		assertEquals(BloodDoorTraceRules.DoorPresence.UNKNOWN, BloodDoorTraceRules.doorPresence(wither,
			DungeonDoorTracker.Type.WITHER, ignored -> false, ignored -> false),
			"unloaded door samples are unknown, not treated as proof the doorway opened");
		assertEquals(BloodDoorTraceRules.DoorPresence.OPEN, BloodDoorTraceRules.doorPresence(wither,
			DungeonDoorTracker.Type.WITHER, ignored -> true, ignored -> false),
			"a fully loaded door plane with no Wither block confirms it opened");
	}























	private static void checkDungeonContextSignals() {
		DungeonContextTracker.Signal floor = DungeonContextTracker.readSidebarText("The Catacombs\nF7");
		assertTrue(floor.inDungeon(), "the Catacombs scoreboard establishes dungeon context");
		assertEquals(DungeonFloor.F7, floor.floor(), "the detected floor comes from the scoreboard");
		DungeonContextTracker.Signal master = DungeonContextTracker.readSidebarText("Dungeon: Master Mode F7");
		assertTrue(master.inDungeon(), "an explicit dungeon floor establishes context without a Catacombs title");
		assertEquals(DungeonFloor.M7, master.floor(), "Master Mode upgrades the detected floor");
		DungeonContextTracker.Signal entrance = DungeonContextTracker.readSidebarText("Dungeon: Entrance");
		assertTrue(entrance.inDungeon(), "Entrance is accepted as a dungeon context");
		assertEquals(DungeonFloor.ENTRANCE, entrance.floor(), "Entrance resolves to the shared floor value");
		assertFalse(DungeonContextTracker.readSidebarText("Dungeon Finder\nSearch for a party").inDungeon(),
			"the party finder scoreboard does not activate dungeon context");
	}

	private static void checkDungeonFloorSources() {
		DungeonContextTracker.FloorSelection absent = DungeonContextTracker.floorSelection(false,
			null, null, DungeonFloor.F1);
		assertEquals(null, absent.floor(), "a queue floor cannot invent dungeon presence or silently select F1");
		assertEquals(DungeonContextTracker.FloorSource.UNKNOWN, absent.source(),
			"missing dungeon context has an explicit unknown floor source");
		DungeonContextTracker.FloorSelection queue = DungeonContextTracker.floorSelection(true,
			null, null, DungeonFloor.M7);
		assertEquals(DungeonFloor.M7, queue.floor(), "queue floor is retained as a separate validated fallback source");
		assertEquals(DungeonContextTracker.FloorSource.QUEUE, queue.source(), "queue floor provenance is explicit");
		DungeonContextTracker.FloorSelection sidebar = DungeonContextTracker.floorSelection(true,
			null, DungeonFloor.F6, DungeonFloor.M7);
		assertEquals(DungeonFloor.F6, sidebar.floor(), "scoreboard floor wins over queue fallback");
		assertEquals(DungeonContextTracker.FloorSource.SIDEBAR, sidebar.source(), "scoreboard provenance is explicit");
		DungeonContextTracker.FloorSelection manual = DungeonContextTracker.floorSelection(true,
			DungeonFloor.M5, DungeonFloor.F6, DungeonFloor.M7);
		assertEquals(DungeonFloor.M5, manual.floor(), "explicit manual floor has the highest floor-source priority");
		assertEquals(DungeonContextTracker.FloorSource.MANUAL, manual.source(), "manual provenance is explicit");
	}

	private static void checkMapGeometry() {
		byte[] colors = new byte[DungeonMapGeometry.MAP_SIZE * DungeonMapGeometry.MAP_SIZE];
		fillMapTile(colors, 60, 60, 8, DungeonMapGeometry.ENTRANCE_COLOR);
		fillMapTile(colors, 72, 61, 8, 63);
		fillMapTile(colors, 84, 61, 8, 63);
		// The pinned source tests the first shared-gap pixel along the neighbor's north/west edge.
		colors[(72 + 8) + ((60 + 1) << 7)] = (byte) 63;
		fillMapTile(colors, 108, 61, 8, 63);
		fillMapTile(colors, 72, 73, 8, 66);
		DungeonMapGeometry.MapPoint marker = new DungeonMapGeometry.MapPoint(75, 64);
		DungeonMapGeometry.Calibration calibration = DungeonMapGeometry.calibrate(colors, marker);
		assertTrue(calibration.calibrated(), "the nearest complete entrance tile calibrates map geometry");
		assertEquals(new DungeonMapGeometry.MapPoint(60, 61), calibration.entrance(),
			"calibration retains the pinned source's one-pixel entrance inset");
		assertEquals(8, calibration.roomPixelSize(), "calibration records the measured tile size");
		DungeonMapGeometry.RoomGeometry room = DungeonMapGeometry.roomAt(colors, marker, calibration);
		assertEquals(DungeonMapGeometry.RoomType.ROOM, room.roomType(), "the player marker maps to a normal room color");
		assertEquals("1x2", room.shape(), "connected same-color map tiles form a multi-cell shape");
		assertEquals(List.of(new DungeonMapGeometry.CellOffset(0, 0), new DungeonMapGeometry.CellOffset(1, 0)),
			room.connectedCells(), "only orthogonally connected normal-room cells are included");
		assertEquals(DungeonMapGeometry.RoomClearStatus.UNCLEARED,
			DungeonMapGeometry.roomClearStatus(colors, room),
			"a room without an upstream checkmark remains uncleared");
		byte[] whiteCheckmark = colors.clone();
		whiteCheckmark[76 + (65 << 7)] = 34;
		assertEquals(DungeonMapGeometry.RoomClearStatus.CLEARED_WHITE,
			DungeonMapGeometry.roomClearStatus(whiteCheckmark, room),
			"the upstream white checkmark marks a cleared room with remaining secrets");
		assertEquals(room.connectedCells(), DungeonMapGeometry.roomAt(whiteCheckmark, marker, calibration).connectedCells(),
			"a map checkmark color update does not revise the active room footprint");
		assertEquals(room.roomType(), DungeonMapGeometry.roomAt(whiteCheckmark, marker, calibration).roomType(),
			"a map checkmark color update does not revise the active source room type");
		assertTrue(DungeonMapGeometry.roomClearStatus(whiteCheckmark, room).cleared(),
			"both checkmarked room states signal a room clear to dependent tracking");
		byte[] greenCheckmark = colors.clone();
		greenCheckmark[76 + (65 << 7)] = 30;
		assertEquals(DungeonMapGeometry.RoomClearStatus.CLEARED_GREEN,
			DungeonMapGeometry.roomClearStatus(greenCheckmark, room),
			"the upstream green checkmark marks a room cleared with secrets");
		byte[] redCheckmark = colors.clone();
		redCheckmark[76 + (65 << 7)] = 18;
		assertEquals(DungeonMapGeometry.RoomClearStatus.FAILED_RED,
			DungeonMapGeometry.roomClearStatus(redCheckmark, room),
			"the upstream red failed-room mark is not misreported as cleared");
		DungeonMapGeometry.RoomGeometry puzzle = DungeonMapGeometry.roomAt(colors,
			new DungeonMapGeometry.MapPoint(75, 76), calibration);
		assertEquals(DungeonMapGeometry.RoomType.PUZZLE, puzzle.roomType(), "puzzle map colors remain distinct from normal rooms");
		assertFalse(DungeonMapGeometry.calibrate(new byte[128], marker).calibrated(),
			"a truncated map buffer is rejected rather than guessed");
		assertFalse(DungeonMapGeometry.calibrate(new byte[128 * 128], marker).calibrated(),
			"missing entrance pixels produce an explicit calibration failure");
		assertEquals(new DungeonMapGeometry.WorldCell(-168, -200),
			DungeonMapGeometry.physicalCellAt(-169.0, -200.0), "physical positions map through the calibrated 32-block transform");
		assertEquals(new DungeonMapGeometry.WorldCell(-200, -200),
			DungeonMapGeometry.physicalCellAt(-170.0, -200.0), "the transform preserves negative-coordinate boundary behavior");
		assertEquals(new DungeonMapGeometry.WorldCell(-200, -168),
			DungeonMapGeometry.physicalCellAt(-200.0, -169.0), "the negative-grid south edge follows the same source boundary rounding");
		assertEquals(new DungeonMapGeometry.WorldCell(-200, -200),
			DungeonMapGeometry.physicalCellAt(-200.0, -170.0), "the last block of the south segment stays in that segment");
		assertEquals(new DungeonGridRules.Cell(1, 0, -168, -200), DungeonGridRules.cellAtOrigin(-168, -200),
			"a valid map room origin maps to its corresponding grid cell");
		assertEquals(null, DungeonGridRules.cellAtOrigin(-167, -200),
			"non-aligned map origins do not get rounded into another room");

		byte[] adjacentButSeparate = new byte[DungeonMapGeometry.MAP_SIZE * DungeonMapGeometry.MAP_SIZE];
		fillMapTile(adjacentButSeparate, 60, 60, 8, DungeonMapGeometry.ENTRANCE_COLOR);
		fillMapTile(adjacentButSeparate, 72, 61, 8, 63);
		fillMapTile(adjacentButSeparate, 84, 61, 8, 63);
		DungeonMapGeometry.RoomGeometry separate = DungeonMapGeometry.roomAt(adjacentButSeparate, marker, calibration);
		assertEquals("1x1", separate.shape(),
			"adjacent same-color tile centers remain separate when the map has no same-color connector in their gap");

		byte[] horizontalEntrance = new byte[DungeonMapGeometry.MAP_SIZE * DungeonMapGeometry.MAP_SIZE];
		for (int x = 50; x < 56; x++) horizontalEntrance[x + (50 << 7)] = (byte) DungeonMapGeometry.ENTRANCE_COLOR;
		DungeonMapGeometry.Calibration lineCalibration = DungeonMapGeometry.calibrate(horizontalEntrance,
			new DungeonMapGeometry.MapPoint(45, 50));
		assertTrue(lineCalibration.calibrated(),
			"Skyblocker-compatible entrance sizing uses the horizontal run over six pixels without imposing a square scan");
		assertEquals(6, lineCalibration.roomPixelSize(), "the measured horizontal entrance run is the map tile size");
	}

	private static void checkMapIdFallback() {
		MapId hotbarId = new MapId(2048);
		MapId cachedId = new MapId(3072);
		DungeonMapRoomDetector.MapIdResolution held = DungeonMapRoomDetector.resolveMapId(true, hotbarId, cachedId);
		assertEquals(hotbarId, held.mapId(), "a filled map in hotbar slot nine remains the authoritative map ID");
		assertEquals(DungeonMapRoomDetector.MapIdSource.HOTBAR, held.source(), "the live hotbar map source is explicit");
		DungeonMapRoomDetector.MapIdResolution cached = DungeonMapRoomDetector.resolveMapId(false, null, cachedId);
		assertEquals(cachedId, cached.mapId(), "a missing hotbar map reuses the last observed map ID");
		assertEquals(DungeonMapRoomDetector.MapIdSource.CACHED, cached.source(), "the cached map source is explicit");
		DungeonMapRoomDetector.MapIdResolution fallback = DungeonMapRoomDetector.resolveMapId(false, null, null);
		assertEquals(new MapId(1024), fallback.mapId(), "map ID 1024 is the final Skyblocker-compatible fallback");
		assertEquals(DungeonMapRoomDetector.MapIdSource.DEFAULT, fallback.source(), "the default map source is explicit");
		DungeonMapGeometry.MapPoint roomCell = new DungeonMapGeometry.MapPoint(38, 51);
		assertFalse(DungeonMapRoomDetector.shouldRefreshGeometry(true, true, roomCell, roomCell, 1, false),
			"unchanged map-room geometry is reused between bounded refreshes");
		assertTrue(DungeonMapRoomDetector.shouldRefreshGeometry(false, true, roomCell, roomCell, 1, false),
			"a changed map data object invalidates cached room geometry");
		assertTrue(DungeonMapRoomDetector.shouldRefreshGeometry(true, false, roomCell, roomCell, 1, false),
			"room geometry is calculated when there is no cached result");
		assertTrue(DungeonMapRoomDetector.shouldRefreshGeometry(true, true, roomCell,
			new DungeonMapGeometry.MapPoint(52, 51), 1, false),
			"entering another map cell refreshes the room footprint immediately");
		assertTrue(DungeonMapRoomDetector.shouldRefreshGeometry(true, true, roomCell, roomCell, 20, false),
			"periodic refreshes observe newly revealed adjacent map tiles");
		assertTrue(DungeonMapRoomDetector.shouldRefreshGeometry(true, true, roomCell, roomCell, 1, true),
			"unknown geometry retries without waiting for the periodic refresh");
	}

	private static void fillMapTile(byte[] colors, int left, int top, int size, int color) {
		for (int z = top; z < top + size; z++) for (int x = left; x < left + size; x++) {
			colors[x + (z << 7)] = (byte) color;
		}
	}


	private static void checkGridRules() {
		DungeonGridRules.Cell first = DungeonGridRules.cellAt(-200, -200);
		assertEquals(0, first.componentX(), "the dungeon grid starts at component zero");
		assertEquals(-200, first.baseX(), "the first room starts at the documented grid minimum");
		DungeonGridRules.Cell last = DungeonGridRules.cellAt(-10, -10);
		assertEquals(5, last.componentX(), "the final valid grid coordinate maps to the sixth component");
		assertEquals(-40, last.baseX(), "the final room uses the fixed 32-block stride");
		assertEquals(null, DungeonGridRules.cellAt(-169, -200), "the one-block gap between room cells is not another room");
		assertEquals(null, DungeonGridRules.cellAt(-9, -10), "coordinates outside the dungeon grid are rejected");

		for (int rotation = 0; rotation < 4; rotation++) {
			for (int x : List.of(0, 3, 17, 30)) for (int z : List.of(1, 11, 29)) {
				DungeonGridRules.Position world = DungeonGridRules.toWorld(rotation, x, z);
				assertEquals(new DungeonGridRules.Position(x, z),
					DungeonGridRules.toCanonical(rotation, world.x(), world.z()),
					"room coordinate transforms round trip for every rotation");
			}
		}

		List<DungeonGridRules.BlockSample> room = List.of(
			new DungeonGridRules.BlockSample(1, 0, 4, "minecraft:stone_bricks"),
			new DungeonGridRules.BlockSample(8, 3, 2, "minecraft:gold_block"),
			new DungeonGridRules.BlockSample(29, 9, 24, "minecraft:oak_planks"),
			new DungeonGridRules.BlockSample(12, 17, 6, "minecraft:spawner"));
		long expected = DungeonGridRules.fingerprint(room).hash();
		for (int rotation = 1; rotation < 4; rotation++) {
			int orientation = rotation;
			List<DungeonGridRules.BlockSample> rotated = room.stream().map(sample -> {
				DungeonGridRules.Position position = DungeonGridRules.toCanonical(orientation, sample.x(), sample.z());
				return new DungeonGridRules.BlockSample(position.x(), sample.y(), position.z(), sample.blockId());
			}).toList();
			assertEquals(expected, DungeonGridRules.fingerprint(rotated).hash(),
				"room fingerprints remain stable under cell rotation");
		}
	}

	private static void checkGuidePackages() {
		DungeonGuideNode node = new DungeonGuideNode(DungeonFloor.M7, "BOSS", 4, -12, 70, 33);
		node.id = "route-step";
		node.label = "Enter the dragon arena";
		node.shape = DungeonGuideNode.Shape.BOX;
		node.rotation = 45.0f;
		node.size = 2.5f;
		node.trigger = DungeonGuideNode.Trigger.CHAT_EVENT;
		node.eventText = "The gate has opened";
		node.color = 0xFFABCDEF;
		String encoded = DungeonPackageCodec.encodeGuides(List.of(node));
		var decoded = DungeonPackageCodec.decodeGuides(encoded);
		assertTrue(decoded.success(), "guide package decodes");
		DungeonGuideNode restored = decoded.entries().getFirst();
		assertEquals("M7", restored.floor, "guide packages preserve master floors");
		assertEquals("BOSS", restored.phase, "guide packages preserve stable phase ids");
		assertEquals(45.0f, restored.rotation, "guide packages preserve rotation");
		assertEquals(DungeonGuideNode.Trigger.CHAT_EVENT, restored.trigger, "guide packages preserve event triggers");
		assertEquals("The gate has opened", restored.eventText, "guide packages preserve configured event phrases");
		assertFalse(DungeonPackageCodec.decodeGuides(encoded.replace("\"version\": 1", "\"version\": 7")).success(),
			"unknown guide package versions are rejected");
		List<DungeonGuideNode> tooMany = new ArrayList<>();
		for (int i = 0; i <= ClientJsonFile.MAX_ENTRIES; i++) tooMany.add(new DungeonGuideNode(DungeonFloor.F1, "ENTRY", i, 0, 0, 0));
		assertEquals(null, DungeonPackageCodec.encodeGuides(tooMany), "guide export enforces the entry bound");
		assertEquals(15, DungeonFloor.values().length, "the route model contains Entrance, F1-F7 and M1-M7");
	}

	private static void checkGuideProgress() {
		DungeonGuideProgress progress = new DungeonGuideProgress();
		progress.setFloor(DungeonFloor.F7, 1_000_000_000L);
		DungeonGuideNode entry = new DungeonGuideNode(DungeonFloor.F7, "ENTRY", 0, 0, 0, 0);
		DungeonGuideNode bloodEvent = new DungeonGuideNode(DungeonFloor.F7, "BLOOD", 0, 0, 0, 0);
		bloodEvent.eventText = "Watcher defeated";
		bloodEvent.trigger = DungeonGuideNode.Trigger.CHAT_EVENT;
		DungeonGuideNode bloodNext = new DungeonGuideNode(DungeonFloor.F7, "BLOOD", 1, 1, 0, 0);
		DungeonGuideNode otherFloor = new DungeonGuideNode(DungeonFloor.M7, "BLOOD", 0, 2, 0, 0);
		List<DungeonGuideNode> all = List.of(entry, bloodEvent, bloodNext, otherFloor);
		assertEquals(3, progress.group(all).size(), "one route includes every phase on the confirmed floor");
		assertEquals("ENTRY", progress.group(all).get(0).phase, "built-in phases appear before unknown legacy phases");
		progress.next(all, 2_000_000_000L);
		assertEquals(1, progress.index(), "manual next advances the first ordered step");
		assertTrue(progress.observeConfiguredEvent("Watcher defeated", all, 2_500_000_000L),
			"a unique configured phrase remains identifiable for editor/phase tooling");
		assertEquals(1, progress.index(), "an event on a later step never skips the active step");
		assertEquals(2, progress.remaining(all), "remaining count covers all phases in route order");
		assertEquals("BLOOD", progress.group(all).get(progress.index()).phase,
			"the next waypoint is revealed only after the previous ordered step passes");
		progress.previous(3_000_000_000L);
		assertEquals(0, progress.index(), "previous control returns to the preceding route step");
		progress.next(progress.group(all), 4_000_000_000L);
		assertEquals(1, progress.index(), "next control advances the active route");
		checkGuideRevisionProgress();

		DungeonGuideNode delay = new DungeonGuideNode(DungeonFloor.F7, "BLOOD", 2, 0, 0, 0);
		delay.conditions = EnumSet.of(DungeonGuideNode.Condition.AFTER_SECONDS);
		delay.updateLegacyTrigger();
		delay.triggerSeconds = 3;
		assertFalse(DungeonGuideProgress.triggerReady(delay, 2_999_000_000L, Double.POSITIVE_INFINITY),
			"timer trigger waits until its configured duration");
		assertTrue(DungeonGuideProgress.triggerReady(delay, 3_000_000_000L, Double.POSITIVE_INFINITY),
			"timer trigger opens at its configured duration");
		DungeonGuideNode radius = new DungeonGuideNode(DungeonFloor.F7, "BLOOD", 3, 0, 0, 0);
		radius.conditions = EnumSet.of(DungeonGuideNode.Condition.ENTER_RADIUS);
		radius.updateLegacyTrigger();
		radius.triggerRadius = 2;
		assertTrue(DungeonGuideProgress.triggerReady(radius, 0, 3.9), "enter-radius trigger advances inside its configured radius");
		assertFalse(DungeonGuideProgress.triggerReady(radius, 0, 4.1), "enter-radius trigger stays inactive outside its radius");
		DungeonGuideNode combined = new DungeonGuideNode(DungeonFloor.F7, "CLEAR", 4, 0, 0, 0);
		combined.conditions = EnumSet.of(DungeonGuideNode.Condition.ENTER_RADIUS, DungeonGuideNode.Condition.AFTER_SECONDS);
		combined.conditionRule = DungeonGuideNode.ConditionRule.ALL;
		combined.triggerRadius = 2;
		combined.triggerSeconds = 3;
		assertFalse(DungeonGuideProgress.triggerReady(combined, 3_000_000_000L, 4.1),
			"ALL waits while any enabled condition remains false");
		assertTrue(DungeonGuideProgress.triggerReady(combined, 3_000_000_000L, 3.9),
			"ALL advances after every enabled condition is true");
		combined.conditionRule = DungeonGuideNode.ConditionRule.ANY;
		assertTrue(DungeonGuideProgress.triggerReady(combined, 3_000_000_000L, 4.1),
			"ANY advances when at least one enabled condition is true");

		DungeonGuideNode duplicatePhrase = new DungeonGuideNode(DungeonFloor.F7, "COMPLETE", 0, 0, 0, 0);
		duplicatePhrase.eventText = "Watcher defeated";
		assertFalse(progress.observeConfiguredEvent("Watcher defeated", List.of(bloodEvent, duplicatePhrase), 5_000_000_000L),
			"duplicate configured event phrases are treated as ambiguous");
		assertEquals("ENTRY", progress.phase(), "ambiguous chat does not change the active phase");

		DungeonGuideProgress ambiguousProgress = new DungeonGuideProgress();
		ambiguousProgress.setFloor(DungeonFloor.F7, 5_000_000_000L);
		DungeonGuideNode activeChatStep = new DungeonGuideNode(DungeonFloor.F7, "ENTRY", 0, 0, 0, 0);
		activeChatStep.conditions = EnumSet.of(DungeonGuideNode.Condition.CHAT_EVENT);
		activeChatStep.trigger = DungeonGuideNode.Trigger.CHAT_EVENT;
		activeChatStep.eventText = "Watcher defeated";
		DungeonGuideNode conflictingChatStep = new DungeonGuideNode(DungeonFloor.F7, "COMPLETE", 0, 0, 0, 0);
		conflictingChatStep.conditions = EnumSet.of(DungeonGuideNode.Condition.CHAT_EVENT);
		conflictingChatStep.trigger = DungeonGuideNode.Trigger.CHAT_EVENT;
		conflictingChatStep.eventText = activeChatStep.eventText;
		List<DungeonGuideNode> ambiguousSteps = List.of(activeChatStep, conflictingChatStep);
		assertEquals(0, ambiguousProgress.index(), "the duplicate-event trace starts on its chat-triggered step");
		assertFalse(ambiguousProgress.observeConfiguredEvent("Watcher defeated", ambiguousSteps, 5_000_000_001L),
			"an ambiguous phrase is rejected before it can trigger the active chat step");
		assertEquals(0, ambiguousProgress.index(), "ambiguous configured chat leaves route progress unchanged");
	}

	private static void checkGuideRevisionProgress() {
		DungeonGuideProgress progress = new DungeonGuideProgress();
		progress.setFloor(DungeonFloor.F7, 100);
		DungeonGuideNode before = guideStep("before", 0);
		DungeonGuideNode active = guideStep("active", 1);
		DungeonGuideNode after = guideStep("after", 2);
		List<DungeonGuideNode> initial = List.of(before, active, after);
		progress.snapshot(initial, 1, 100);
		progress.next(initial, 200);
		progress.snapshot(initial, 1, 200);

		// Removing a preceding node must not leave the integer index pointing at the next waypoint.
		active.order = 0;
		after.order = 1;
		DungeonGuideProgress.RouteSnapshot removedBefore = progress.snapshot(List.of(active, after), 2, 250);
		assertEquals(0, removedBefore.stepIndex(), "removing a preceding guide node reanchors the same active id");
		assertEquals("active", removedBefore.current().id(), "removal before the active guide does not retarget it");

		// Reorder the active node behind another entry, then insert a new node before it.
		active.order = 2;
		after.order = 0;
		DungeonGuideProgress.RouteSnapshot reordered = progress.snapshot(List.of(active, after), 3, 300);
		assertEquals(1, reordered.stepIndex(), "reordering reanchors the active guide at its new position");
		assertEquals("active", reordered.current().id(), "reordering preserves the active guide id");
		DungeonGuideNode inserted = guideStep("inserted", 1);
		DungeonGuideProgress.RouteSnapshot insertedBefore = progress.snapshot(
			List.of(active, after, inserted), 4, 350);
		assertEquals(2, insertedBefore.stepIndex(), "inserting before the active guide reanchors its new index");
		assertEquals("active", insertedBefore.current().id(), "insertion does not silently skip to a new guide");
		assertEquals(150L, progress.elapsedNanos(350), "an unchanged active guide keeps its trigger timer");

		DungeonGuideProgress deletedProgress = new DungeonGuideProgress();
		deletedProgress.setFloor(DungeonFloor.F7, 100);
		DungeonGuideNode first = guideStep("first", 0);
		DungeonGuideNode deleted = guideStep("deleted", 1);
		DungeonGuideNode shifted = guideStep("shifted", 2);
		List<DungeonGuideNode> oldRoute = List.of(first, deleted, shifted);
		deletedProgress.snapshot(oldRoute, 1, 100);
		deletedProgress.next(oldRoute, 200);
		deletedProgress.snapshot(oldRoute, 1, 200);
		shifted.order = 0;
		DungeonGuideProgress.RouteSnapshot activeDeleted = deletedProgress.snapshot(List.of(shifted), 2, 250);
		assertEquals("guide-data-reset", activeDeleted.rebuildReason(),
			"deleting the active guide reports the explicit restart policy");
		assertEquals(0, activeDeleted.stepIndex(), "deleting the active guide restarts at the route beginning");
		assertEquals("shifted", activeDeleted.current().id(), "restart selects the new first guide deliberately");
		assertFalse(activeDeleted.complete(), "active deletion does not turn the shifted route into completion");
		assertEquals(0L, deletedProgress.elapsedNanos(250), "restart resets the new first guide timer");

		DungeonGuideProgress emptiedProgress = new DungeonGuideProgress();
		emptiedProgress.setFloor(DungeonFloor.F7, 100);
		List<DungeonGuideNode> oneStep = List.of(guideStep("only", 0));
		emptiedProgress.snapshot(oneStep, 1, 100);
		emptiedProgress.next(oneStep, 200);
		DungeonGuideProgress.RouteSnapshot completed = emptiedProgress.snapshot(oneStep, 1, 200);
		assertTrue(completed.complete(), "a non-empty route reports completion after its last step");
		DungeonGuideProgress.RouteSnapshot emptied = emptiedProgress.snapshot(List.of(), 2, 300);
		assertEquals(0, emptied.stepIndex(), "deleting every guide clears stale progress");
		assertEquals(0, emptied.remaining(), "an empty route has no remaining steps");
		assertFalse(emptied.complete(), "deleting every guide is not reported as route completion");
	}

	private static DungeonGuideNode guideStep(String id, int order) {
		DungeonGuideNode node = new DungeonGuideNode(DungeonFloor.F7, "ENTRY", order, 0, 0, 0);
		node.id = id;
		return node;
	}

	private static void checkGuideMigration() {
		List<DungeonGuideNode> nodes = new ArrayList<>();
		DungeonGuideNode lateName = new DungeonGuideNode(DungeonFloor.F7, "BLOOD", 0, 0, 0, 0);
		lateName.id = "legacy-zulu";
		lateName.routeName = "Zulu route";
		DungeonGuideNode earlyOrder = new DungeonGuideNode(DungeonFloor.F7, "BLOOD", 8, 0, 0, 0);
		earlyOrder.id = "legacy-alpha-first";
		earlyOrder.routeName = "Alpha route";
		DungeonGuideNode secondOrder = new DungeonGuideNode(DungeonFloor.F7, "BLOOD", 9, 0, 0, 0);
		secondOrder.id = "legacy-alpha-second";
		secondOrder.routeName = "Alpha route";
		DungeonGuideNode firstPhase = new DungeonGuideNode(DungeonFloor.F7, "ENTRY", 4, 0, 0, 0);
		firstPhase.id = "legacy-entry";
		firstPhase.routeName = "Zulu route";
		DungeonGuideNode soleRoute = new DungeonGuideNode(DungeonFloor.F6, "ENTRY", 2, 0, 0, 0);
		soleRoute.routeName = "My saved route";
		nodes.addAll(List.of(lateName, earlyOrder, secondOrder, soleRoute, firstPhase));
		Map<String, String> routeNames = new java.util.LinkedHashMap<>();

		DungeonGuideStore.normalizeRouteNamesAndOrder(nodes, routeNames);

		assertEquals("Combined Route", routeNames.get("F7"), "multiple legacy route names combine under one floor route");
		assertEquals("My saved route", routeNames.get("F6"), "a sole legacy route name is preserved");
		assertTrue(nodes.stream().filter(node -> node.floor.equals("F7"))
			.allMatch(node -> node.routeName.equals("Combined Route")), "every migrated phase shares the same route name");
		List<DungeonGuideNode> f7 = nodes.stream().filter(node -> node.floor.equals("F7")).toList();
		assertEquals("ENTRY", f7.get(0).phase, "migration sorts built-in phases before later phases");
		assertEquals("legacy-alpha-first", f7.get(1).id, "same-phase routes sort by old route name then old order");
		assertEquals(0, f7.get(1).order, "migration renumbers the first step in its phase");
		assertEquals(1, f7.get(2).order, "migration retains order inside a legacy route");
		assertEquals("legacy-zulu", f7.get(3).id, "later legacy route follows earlier route in the same phase");
	}

	private static void checkGuideFlush() {
		final class PendingSnapshot implements DungeonGuideFlush.Writer {
			private final long mutationVersion = 2;
			private long pendingVersion = 1;
			private long completedVersion;
			private boolean dirty = true;
			private boolean pending = true;
			private int writes;
			private long lastWrittenVersion;

			@Override public boolean awaitPending() {
				if (pending) {
					completedVersion = pendingVersion;
					pending = false;
					dirty = completedVersion != mutationVersion;
				}
				return true;
			}
			@Override public boolean dirty() { return dirty; }
			@Override public boolean persistenceBlocked() { return false; }
			@Override public boolean hasPending() { return pending; }
			@Override public void writeSnapshot() {
				pendingVersion = mutationVersion;
				lastWrittenVersion = pendingVersion;
				writes++;
				pending = true;
			}
		}
		PendingSnapshot store = new PendingSnapshot();
		DungeonGuideFlush.drain(store);
		assertEquals(1, store.writes, "flush schedules a fresh snapshot after an older pending version completes");
		assertEquals(2L, store.lastWrittenVersion, "flush writes the newest mutation version, not the stale completion");
		assertEquals(2L, store.completedVersion, "flush waits for the replacement snapshot to complete");
		assertFalse(store.dirty, "a successful latest-version completion clears the dirty state");
	}

	private static void assertTrue(boolean value, String message) { if (!value) throw new AssertionError(message); }
	private static void assertFalse(boolean value, String message) { if (value) throw new AssertionError(message); }
	private static void assertEquals(Object expected, Object actual, String message) {
		if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
	}
}
