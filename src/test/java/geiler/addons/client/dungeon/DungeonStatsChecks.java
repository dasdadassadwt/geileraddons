package geiler.addons.client.dungeon;

import com.google.gson.JsonParser;

import java.util.UUID;

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
		assertFalse("{\"inventory\":[{\"tag\":{\"display\":{\"Lore\":[\"Terminator\"]}}}]}",
			"a lore mention is not an item identity");
		assertTrue("{\"inventory\":[{\"id\":\"TERMINATOR\"}]}",
			"an item identifier makes inventory evidence readable");
		assertTrue("{\"inventory\":[\"minecraft:diamond\"]}",
			"a namespaced item identifier makes inventory evidence readable");
		checkCurrentProfileShape();
	}

	private static void checkCurrentProfileShape() {
		String uuidText = "12345678123412341234123456789012";
		UUID uuid = UUID.fromString("12345678-1234-1234-1234-123456789012");
		String memberJson = "{" +
			"\"dungeons\":{" +
				"\"dungeon_types\":{" +
					"\"catacombs\":{\"experience\":569809640,\"tier_completions\":{\"1\":10}," +
						"\"fastest_time_s_plus\":{\"7\":360000}} ," +
					"\"master_catacombs\":{\"tier_completions\":{\"1\":2}}" +
				"}," +
				"\"player_classes\":{" +
					"\"mage\":{\"experience\":569809640},\"archer\":{\"experience\":50}," +
					"\"berserk\":{\"experience\":50},\"healer\":{\"experience\":50},\"tank\":{\"experience\":50}" +
				"},\"selected_dungeon_class\":\"mage\"" +
			"}," +
			"\"accessory_bag_storage\":{\"highest_magical_power\":2030}," +
			"\"pets_data\":{\"pets\":[{\"type\":\"GOLDEN_DRAGON\"}]}," +
			"\"inventory\":{\"inv_contents\":[{\"id\":\"TERMINATOR\"}]}" +
		"}";
		String rootJson = "{\"profiles\":[{\"selected\":true,\"banking\":{\"balance\":123456}," +
			"\"members\":{" + quote(uuidText) + ":" + memberJson + "}}]}";
		DungeonStats stats = DungeonStatsService.parseForChecks("Example", uuid,
			JsonParser.parseString(rootJson).getAsJsonObject(),
			JsonParser.parseString(memberJson).getAsJsonObject(), 9876L);
		if (stats.totalSecrets() != 9876 || !stats.has(DungeonStats.DataField.SECRETS)) {
			throw new AssertionError("dedicated secrets data overrides the profile fallback");
		}
		if (stats.magicalPower() != 2030 || !stats.has(DungeonStats.DataField.MAGICAL_POWER)) {
			throw new AssertionError("nested magical power remains readable");
		}
		if (!stats.hasGearData(DungeonStats.Gear.TERMINATOR)
			|| !stats.hasGearData(DungeonStats.Gear.HYPERION)
			|| !stats.hasGearData(DungeonStats.Gear.GOLDEN_DRAGON)) {
			throw new AssertionError("current inventory and pet sections provide per-gear evidence");
		}
		if (!stats.has(DungeonStats.Gear.TERMINATOR) || stats.has(DungeonStats.Gear.HYPERION)
			|| !stats.has(DungeonStats.Gear.GOLDEN_DRAGON)) {
			throw new AssertionError("current profile gear identities are parsed independently");
		}
		if (!stats.bankKnown() || stats.bank() != 123456) {
			throw new AssertionError("selected profile bank data remains readable");
		}
	}

	private static String quote(String value) {
		return "\"" + value + "\"";
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
