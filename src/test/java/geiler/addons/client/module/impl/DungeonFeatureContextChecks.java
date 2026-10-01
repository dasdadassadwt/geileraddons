package geiler.addons.client.module.impl;

import geiler.addons.client.dungeon.DungeonFloor;

/** Offline checks for the Catacombs-only i4 and Garden-only height-capture boundaries. */
public final class DungeonFeatureContextChecks {
	private DungeonFeatureContextChecks() { }

	public static void run() {
		checkI4Context();
		checkGardenHeightContext();
	}

	private static void checkI4Context() {
		assertTrue(I4HelperModule.supportsDungeonContext(true, DungeonFloor.F7),
			"i4 is available on Catacombs F7");
		assertTrue(I4HelperModule.supportsDungeonContext(true, DungeonFloor.M7),
			"i4 is available on Catacombs M7");
		assertFalse(I4HelperModule.supportsDungeonContext(false, DungeonFloor.F7),
			"F7 labels alone do not activate i4 outside a dungeon");
		assertFalse(I4HelperModule.supportsDungeonContext(true, DungeonFloor.F6),
			"i4 is inactive on other Catacombs floors");
		assertFalse(I4HelperModule.supportsDungeonContext(true, null),
			"unknown floor context does not activate i4");
	}

	private static void checkGardenHeightContext() {
		assertTrue(GardenPlotBordersModule.canCaptureHeight(true, true, 70.0, false),
			"a standing player in the Garden may establish the plot border height");
		assertFalse(GardenPlotBordersModule.canCaptureHeight(false, true, 70.0, false),
			"being grounded outside the Garden cannot capture its height");
		assertFalse(GardenPlotBordersModule.canCaptureHeight(true, false, 70.0, false),
			"an airborne Garden player waits before auto-capture");
		assertFalse(GardenPlotBordersModule.canCaptureHeight(true, true, 70.0, true),
			"the automatic height is captured only once per Garden session");
	}

	private static void assertTrue(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}

	private static void assertFalse(boolean value, String message) {
		if (value) throw new AssertionError(message);
	}
}
