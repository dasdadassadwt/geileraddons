package geiler.addons.client.dungeon;

import java.util.List;

/** Standalone offline checks for geometry-only room footprints and their lifecycle. */
public final class DungeonRoomFootprintChecks {
	private DungeonRoomFootprintChecks() { }

	public static void run() {
		checksShapeAndPhysicalCells();
		checksRetainedFootprintDuringMapLoss();
		checksTransitionExitAndWorldClear();
		checksActivationConditions();
	}

	private static void checksShapeAndPhysicalCells() {
		List<DungeonMapGeometry.CellOffset> line = List.of(
			new DungeonMapGeometry.CellOffset(0, 0), new DungeonMapGeometry.CellOffset(1, 0),
			new DungeonMapGeometry.CellOffset(2, 0));
		List<DungeonMapGeometry.CellOffset> lShape = List.of(
			new DungeonMapGeometry.CellOffset(0, 0), new DungeonMapGeometry.CellOffset(1, 0),
			new DungeonMapGeometry.CellOffset(0, 1));
		check("1x3".equals(DungeonMapGeometry.shape(line)), "map shape remains a horizontal line of three cells");
		check("L".equals(DungeonMapGeometry.shape(lShape)), "map shape distinguishes an L footprint");

		DungeonRoomTracker.MapRoomFootprint footprint = new DungeonRoomTracker.MapRoomFootprint(null, List.of(
			new DungeonRoomTracker.CellKey(2, 3), new DungeonRoomTracker.CellKey(3, 3),
			new DungeonRoomTracker.CellKey(2, 4)));
		int baseX = DungeonGridRules.GRID_MIN + 2 * DungeonGridRules.CELL_STRIDE;
		int baseZ = DungeonGridRules.GRID_MIN + 3 * DungeonGridRules.CELL_STRIDE;
		check(footprint.contains(baseX + 10, baseZ + 10), "the current physical cell is occupied");
		check(footprint.contains(baseX + DungeonGridRules.CELL_STRIDE + 10, baseZ + 10),
			"a horizontal connected cell is occupied");
		check(footprint.contains(baseX + 10, baseZ + DungeonGridRules.CELL_STRIDE + 10),
			"a vertical connected cell is occupied");
		check(!footprint.contains(baseX + DungeonGridRules.CELL_STRIDE + 10,
			baseZ + DungeonGridRules.CELL_STRIDE + 10), "a diagonal cell outside the L is excluded");
		check(!footprint.contains(baseX + DungeonGridRules.ROOM_SIZE, baseZ + 10),
			"the one-block corridor gap is excluded from room occupancy");
		check(footprint.footprintKey().startsWith("cells:"), "the stable key contains only physical cell coordinates");
	}

	private static void checksRetainedFootprintDuringMapLoss() {
		Object world = new Object();
		DungeonRoomTracker.CellKey playerCell = new DungeonRoomTracker.CellKey(2, 3);
		DungeonRoomTracker.MapRoomFootprint confirmed = footprint(2, 3, 3, 3);
		DungeonRoomTracker.TrackerState previous = new DungeonRoomTracker.TrackerState(world, confirmed);

		DungeonRoomTracker.TrackerState afterLoss = DungeonRoomTracker.observe(previous, true, world,
			playerCell, null);
		check(afterLoss.footprint() != null && afterLoss.footprint().cells().equals(confirmed.cells()),
			"transient map-data loss retains the shape while the player stays in the physical room");

		DungeonRoomTracker.MapRoomFootprint conflictingObservation = footprint(2, 3);
		DungeonRoomTracker.TrackerState afterStaleShape = DungeonRoomTracker.observe(previous, true, world,
			playerCell, conflictingObservation);
		check(afterStaleShape.footprint() != null && afterStaleShape.footprint().cells().equals(confirmed.cells()),
			"a temporary different map shape does not replace the footprint under the player");
	}

	private static void checksTransitionExitAndWorldClear() {
		Object world = new Object();
		Object nextWorld = new Object();
		DungeonRoomTracker.MapRoomFootprint first = footprint(2, 3);
		DungeonRoomTracker.TrackerState previous = new DungeonRoomTracker.TrackerState(world, first);

		DungeonRoomTracker.CellKey nextCell = new DungeonRoomTracker.CellKey(3, 3);
		DungeonRoomTracker.MapRoomFootprint next = footprint(3, 3, 4, 3);
		DungeonRoomTracker.TrackerState transitioned = DungeonRoomTracker.observe(previous, true, world,
			nextCell, next);
		check(transitioned.footprint() != null && transitioned.footprint().cells().equals(next.cells()),
			"a confirmed physical room transition adopts the new calibrated footprint");

		DungeonRoomTracker.TrackerState exited = DungeonRoomTracker.observe(previous, true, world,
			new DungeonRoomTracker.CellKey(4, 3), null);
		check(exited.footprint() == null, "leaving all retained physical room cells clears the footprint");

		DungeonRoomTracker.TrackerState changedWorld = DungeonRoomTracker.observe(previous, true, nextWorld,
			new DungeonRoomTracker.CellKey(2, 3), null);
		check(changedWorld.world() == nextWorld && changedWorld.footprint() == null,
			"a world change never carries the previous room footprint forward");

		DungeonRoomTracker.TrackerState dungeonExit = DungeonRoomTracker.observe(previous, false, world,
			new DungeonRoomTracker.CellKey(2, 3), first);
		check(dungeonExit.world() == null && dungeonExit.footprint() == null,
			"leaving Catacombs context clears the retained footprint");
	}

	private static void checksActivationConditions() {
		check(DungeonRoomTracker.shouldTrack(true, true, true, true),
			"tracker runs with Catacombs, enabled module, world and player");
		check(!DungeonRoomTracker.shouldTrack(false, true, true, true), "tracker stays dormant outside Catacombs");
		check(!DungeonRoomTracker.shouldTrack(true, false, true, true), "tracker stays dormant while Mob ESP is disabled");
		check(!DungeonRoomTracker.shouldTrack(true, true, false, true), "tracker needs a client world");
		check(!DungeonRoomTracker.shouldTrack(true, true, true, false), "tracker needs a local player");
	}

	private static DungeonRoomTracker.MapRoomFootprint footprint(int... coordinates) {
		List<DungeonRoomTracker.CellKey> cells = new java.util.ArrayList<>();
		for (int index = 0; index + 1 < coordinates.length; index += 2) {
			cells.add(new DungeonRoomTracker.CellKey(coordinates[index], coordinates[index + 1]));
		}
		return new DungeonRoomTracker.MapRoomFootprint(null, cells);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
