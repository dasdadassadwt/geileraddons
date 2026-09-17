package geiler.addons.client.dungeon;

import com.google.gson.JsonParser;

/** Offline fixtures for the profile-evidence boundary used by AutoKick gear checks. */
public final class DungeonStatsChecks {
	private DungeonStatsChecks() {
	}

	public static void run() {
		assertFalse("{\"inventory\":{\"error\":\"unavailable\"}}",
			"an API error object is not complete inventory data");
		assertFalse("{\"inventory\":[]}", "an empty inventory is not complete inventory data");
		assertFalse("{\"inventory\":[\"temporarily unavailable\"]}",
			"an arbitrary status string is not complete inventory data");
		assertTrue("{\"inventory\":[{\"id\":\"TERMINATOR\"}]}",
			"an item identifier makes inventory evidence readable");
		assertTrue("{\"inventory\":[\"minecraft:diamond\"]}",
			"a namespaced item identifier makes inventory evidence readable");
	}

	private static void assertTrue(String json, String message) {
		if (!DungeonStatsService.hasValidatedInventoryData(JsonParser.parseString(json))) {
			throw new AssertionError(message);
		}
	}

	private static void assertFalse(String json, String message) {
		if (DungeonStatsService.hasValidatedInventoryData(JsonParser.parseString(json))) {
			throw new AssertionError(message);
		}
	}
}
