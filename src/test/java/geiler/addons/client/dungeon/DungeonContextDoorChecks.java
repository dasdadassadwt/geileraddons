package geiler.addons.client.dungeon;

/** Offline checks for dungeon map gating and the immutable Box Doors key-color state. */
public final class DungeonContextDoorChecks {
	private DungeonContextDoorChecks() { }

	public static void run() {
		checkMapScanGate();
		checkClearPhaseEspGate();
		checkMobEspPhaseGate();
		checkEntryProbe();
		checkEntryScanInterval();
		checkKeyOwnershipMerge();
	}

	private static void checkClearPhaseEspGate() {
		assertTrue(DungeonContextTracker.shouldBeginClearPhase(false),
			"a new dungeon context starts in Clear when no later-stage evidence arrived first");
		assertFalse(DungeonContextTracker.shouldBeginClearPhase(true),
			"a blood or boss signal received before the first sidebar tick cannot reopen clear ESP");
		assertTrue(DungeonContextTracker.shouldEnableClearEsp(true, true),
			"the legacy Clear-phase gate stays available to existing context consumers");
		assertFalse(DungeonContextTracker.shouldEnableClearEsp(false, true),
			"the Clear flag cannot activate outside a dungeon");
		assertFalse(DungeonContextTracker.shouldEnableClearEsp(true, false),
			"the legacy Clear-phase gate remains closed after Clear");
	}

	private static void checkMobEspPhaseGate() {
		boolean bossPhase = false;
		assertTrue(DungeonContextTracker.shouldEnableMobEsp(true, bossPhase),
			"Dungeon Mob ESP is available during Entry and Clear");
		bossPhase = DungeonContextTracker.mobEspBossPhaseAfter(bossPhase, "BLOOD_OPEN");
		assertFalse(bossPhase, "Blood Open does not close Dungeon Mob ESP");
		assertTrue(DungeonContextTracker.shouldEnableMobEsp(true, bossPhase),
			"Dungeon Mob ESP remains available during Blood Open");
		bossPhase = DungeonContextTracker.mobEspBossPhaseAfter(bossPhase, "BLOOD_CLEAR");
		assertFalse(bossPhase, "Blood Clear does not close Dungeon Mob ESP");
		assertTrue(DungeonContextTracker.shouldEnableMobEsp(true, bossPhase),
			"Dungeon Mob ESP remains available during Blood Clear");
		bossPhase = DungeonContextTracker.mobEspBossPhaseAfter(bossPhase, "BOSS_ENTRY");
		assertTrue(bossPhase, "recognized boss entry closes the mob ESP phase");
		assertFalse(DungeonContextTracker.shouldEnableMobEsp(true, bossPhase),
			"Dungeon Mob ESP is inactive after boss entry");
		assertTrue(DungeonContextTracker.mobEspBossPhaseAfter(bossPhase, "CLEAR"),
			"a late Clear event cannot reopen the phase after boss entry");
		assertTrue(DungeonContextTracker.mobEspBossPhaseAfter(bossPhase, "BLOOD_CLEAR"),
			"late Blood events cannot reopen the phase after boss entry");
		assertFalse(DungeonContextTracker.shouldEnableMobEsp(false, false),
			"an open pre-boss phase cannot activate outside a dungeon");
		assertFalse(DungeonContextTracker.mobEspBossPhaseAfter(bossPhase, "ENTRY"),
			"a new Entry event resets the prior run's boss phase");
	}

	private static void checkMapScanGate() {
		assertFalse(DungeonContextTracker.shouldRunFullMapScan(false, false),
			"ordinary non-dungeon ticks do not run full map geometry");
		assertTrue(DungeonContextTracker.shouldRunFullMapScan(false, true),
			"map entry evidence keeps the fallback that can establish dungeon context");
		assertTrue(DungeonContextTracker.shouldRunFullMapScan(true, false),
			"confirmed dungeon context continues normal map updates");
	}

	private static void checkEntryProbe() {
		byte[] colors = new byte[DungeonMapGeometry.MAP_SIZE * DungeonMapGeometry.MAP_SIZE];
		DungeonMapGeometry.MapPoint marker = new DungeonMapGeometry.MapPoint(64, 64);
		assertFalse(DungeonMapRoomDetector.hasDungeonMapMarkerColor(colors, marker),
			"an uncolored map neighborhood is not a dungeon-entry candidate");
		colors[65 + (64 << 7)] = (byte) 63;
		assertTrue(DungeonMapRoomDetector.hasDungeonMapMarkerColor(colors, marker),
			"one recognized room-color pixel beside the marker is a cheap entry candidate");
		assertFalse(DungeonMapRoomDetector.hasDungeonMapMarkerColor(new byte[12], marker),
			"an incomplete map buffer cannot establish entry evidence");
		assertFalse(DungeonMapRoomDetector.hasDungeonMapMarkerColor(colors, null),
			"a missing map marker cannot establish entry evidence");
		assertFalse(DungeonMapRoomDetector.hasDungeonMapMarkerColor(colors,
			new DungeonMapGeometry.MapPoint(128, 64)), "an out-of-map marker cannot establish entry evidence");
	}

	private static void checkEntryScanInterval() {
		assertTrue(DungeonMapRoomDetector.entryScanIntervalElapsed(0L, 100L),
			"the first candidate may run a full calibration immediately");
		assertFalse(DungeonMapRoomDetector.entryScanIntervalElapsed(100L, 100L + 999_999_999L),
			"unconfirmed candidates wait before recalibrating");
		assertTrue(DungeonMapRoomDetector.entryScanIntervalElapsed(100L, 100L + 1_000_000_000L),
			"a candidate may retry calibration after the one-second interval");
	}

	private static void checkKeyOwnershipMerge() {
		DungeonKeyRules.KeyOwnership inventory = new DungeonKeyRules.KeyOwnership(false, true);
		DungeonKeyRules.KeyOwnership state = DungeonKeyRules.combineOwnership(true, false,
			inventory.wither(), inventory.blood());
		assertTrue(state.wither(), "the local Wither-key chat fallback is retained");
		assertTrue(state.blood(), "the inventory scan adds Blood-key ownership to the same snapshot");
		DungeonKeyRules.KeyOwnership none = DungeonKeyRules.combineOwnership(false, false, false, false);
		assertFalse(none.wither() || none.blood(), "no observed or carried key leaves both palettes unchanged");
	}

	private static void assertTrue(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}

	private static void assertFalse(boolean value, String message) {
		if (value) throw new AssertionError(message);
	}
}
