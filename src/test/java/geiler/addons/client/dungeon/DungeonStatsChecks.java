package geiler.addons.client.dungeon;

import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

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
		String odinPayload = compressedItemNbt("TERMINATOR", "HYPERION");
		assertTrue("{\"inventory\":{\"inv_contents\":{\"type\":\"inventory\",\"data\":\""
			+ odinPayload + "\"}}}", "Odin's wrapped base64 NBT inventory data is accepted");
		checkCurrentProfileShape();
		checkProfileDetails();
		checkUnavailableDetails();
		checkCatacombsProgress();
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
			"\"inventory\":{\"inv_contents\":{\"type\":\"inventory\",\"data\":\"" +
				compressedItemNbt("TERMINATOR", "HYPERION") + "\"}}" +
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
		if (!stats.has(DungeonStats.Gear.TERMINATOR) || !stats.has(DungeonStats.Gear.HYPERION)
			|| !stats.has(DungeonStats.Gear.GOLDEN_DRAGON)) {
			throw new AssertionError("current profile gear identities are parsed independently");
		}
		if (stats.itemDetails(DungeonStats.Gear.TERMINATOR).size() != 1
			|| !stats.itemDetails(DungeonStats.Gear.TERMINATOR).getFirst().displayName().equals("Terminator")
			|| !stats.itemDetails(DungeonStats.Gear.HYPERION).getFirst().lore().contains("Profile-provided lore")) {
			throw new AssertionError("compressed Odin inventory keeps weapon tooltip names and lore");
		}
		if (!stats.bankKnown() || stats.bank() != 123456) {
			throw new AssertionError("selected profile bank data remains readable");
		}
		if (!stats.has(DungeonStats.DataField.PERSONAL_BESTS)) {
			throw new AssertionError("personal best section availability is preserved");
		}
	}

	private static void checkUnavailableDetails() {
		UUID uuid = UUID.fromString("12345678-1234-1234-1234-123456789012");
		DungeonStats stats = DungeonStatsService.parseForChecks("Unavailable", uuid,
			new com.google.gson.JsonObject(), new com.google.gson.JsonObject(), null);
		if (stats.catacombsExperienceKnown() || stats.has(DungeonStats.DataField.PERSONAL_BESTS)
			|| !stats.allItemDetails().isEmpty()
			|| !stats.goldenDragonPets().isEmpty() || stats.hasGearData(DungeonStats.Gear.TERMINATOR)
			|| stats.hasGearData(DungeonStats.Gear.GOLDEN_DRAGON)) {
			throw new AssertionError("missing profile inventory, pet, and Catacombs data stays unavailable");
		}
	}

	private static void checkProfileDetails() {
		UUID uuid = UUID.fromString("12345678-1234-1234-1234-123456789012");
		com.google.gson.JsonObject catacombs = new com.google.gson.JsonObject();
		catacombs.addProperty("experience", 569_821_985L);
		com.google.gson.JsonObject dungeonTypes = new com.google.gson.JsonObject();
		dungeonTypes.add("catacombs", catacombs);
		com.google.gson.JsonObject dungeons = new com.google.gson.JsonObject();
		dungeons.add("dungeon_types", dungeonTypes);

		com.google.gson.JsonArray items = new com.google.gson.JsonArray();
		items.add(profileItem("TERMINATOR", "Terminator", "Shortbow: Instantly shoots!"));
		items.add(profileItem("TERMINATOR", "Terminator (Duplicate)", "Extra terminator detail"));
		items.add(profileItem("HYPERION", "Hyperion", "Wither Impact"));
		com.google.gson.JsonObject inventory = new com.google.gson.JsonObject();
		inventory.add("inv_contents", items);

		com.google.gson.JsonObject pet = new com.google.gson.JsonObject();
		pet.addProperty("type", "GOLDEN_DRAGON");
		pet.addProperty("tier", "LEGENDARY");
		pet.addProperty("level", 200);
		pet.addProperty("exp", 1_000_000_000L);
		pet.addProperty("heldItem", "PET_ITEM_TIER_BOOST");
		pet.addProperty("skin", "GOLDEN_DRAGON");
		pet.addProperty("active", true);
		com.google.gson.JsonArray pets = new com.google.gson.JsonArray();
		pets.add(pet);
		com.google.gson.JsonObject petsData = new com.google.gson.JsonObject();
		petsData.add("pets", pets);

		com.google.gson.JsonObject member = new com.google.gson.JsonObject();
		member.add("dungeons", dungeons);
		member.add("inventory", inventory);
		member.add("pets_data", petsData);
		DungeonStats stats = DungeonStatsService.parseForChecks("Detailed", uuid,
			new com.google.gson.JsonObject(), member, null);
		if (stats.itemDetails(DungeonStats.Gear.TERMINATOR).size() != 2
			|| stats.itemDetails(DungeonStats.Gear.HYPERION).size() != 1) {
			throw new AssertionError("all matching weapons are retained in the profile snapshot");
		}
		DungeonStats.ItemDetails terminator = stats.itemDetails(DungeonStats.Gear.TERMINATOR).getFirst();
		if (!terminator.displayName().contains("Terminator") || terminator.lore().isEmpty()
			|| !terminator.lore().getFirst().contains("Shortbow")
			|| !terminator.source().equals("Inventory")) {
			throw new AssertionError("weapon name, source, and lore survive profile parsing: "
				+ terminator.displayName() + " / " + terminator.source() + " / " + terminator.lore());
		}
		try {
			stats.allItemDetails().clear();
			throw new AssertionError("profile detail snapshots must be immutable");
		} catch (UnsupportedOperationException expected) {
			// Expected: consumers only receive the detached immutable snapshot.
		}
		if (stats.goldenDragonPets().size() != 1) {
			throw new AssertionError("Golden Dragon pet details are retained");
		}
		DungeonStats.GoldenDragonPet parsedPet = stats.goldenDragonPets().getFirst();
		if (!parsedPet.rarity().equals("LEGENDARY") || !parsedPet.level().equals("200")
			|| !parsedPet.experience().equals("1000000000")
			|| !parsedPet.heldItem().equals("PET_ITEM_TIER_BOOST")
			|| !parsedPet.skin().equals("GOLDEN_DRAGON") || !Boolean.TRUE.equals(parsedPet.active())) {
			throw new AssertionError("Golden Dragon tooltip fields survive profile parsing");
		}
	}

	private static com.google.gson.JsonObject profileItem(String id, String name, String lore) {
		com.google.gson.JsonObject display = new com.google.gson.JsonObject();
		display.addProperty("Name", name);
		com.google.gson.JsonArray loreLines = new com.google.gson.JsonArray();
		loreLines.add(lore);
		display.add("Lore", loreLines);
		com.google.gson.JsonObject tag = new com.google.gson.JsonObject();
		tag.add("display", display);
		com.google.gson.JsonObject item = new com.google.gson.JsonObject();
		item.addProperty("id", id);
		item.add("tag", tag);
		return item;
	}

	private static void checkCatacombsProgress() {
		DungeonStatsService.CataProgress level49 = DungeonStatsService.catacombsProgress(453_559_640L);
		if (level49.level() != 49 || level49.capped() || level49.experienceIntoLevel() != 0
			|| level49.experienceForLevel() != 116_250_000L) {
			throw new AssertionError("Catacombs hover reports progress from the exact level threshold");
		}
		DungeonStatsService.CataProgress capped = DungeonStatsService.catacombsProgress(569_821_985L);
		if (capped.level() != 50 || !capped.capped() || capped.overflowExperience() != 12_345L) {
			throw new AssertionError("Catacombs hover reports XP beyond the level-50 threshold");
		}
	}

	private static String quote(String value) {
		return "\"" + value + "\"";
	}

	private static String compressedItemNbt(String... identifiers) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (DataOutputStream nbt = new DataOutputStream(new GZIPOutputStream(bytes))) {
				nbt.writeByte(10); // Root compound.
				writeNbtString(nbt, "");
				nbt.writeByte(9); // A list of item compounds.
				writeNbtString(nbt, "Items");
				nbt.writeByte(10);
				nbt.writeInt(identifiers.length);
				for (String identifier : identifiers) {
					nbt.writeByte(8); // String tag named id.
					writeNbtString(nbt, "id");
					writeNbtString(nbt, identifier);
					nbt.writeByte(10); // Compound tag named tag.
					writeNbtString(nbt, "tag");
					nbt.writeByte(10); // display compound.
					writeNbtString(nbt, "display");
					nbt.writeByte(8); // Display name string.
					writeNbtString(nbt, "Name");
					writeNbtString(nbt, identifier.equals("HYPERION") ? "Hyperion" : "Terminator");
					nbt.writeByte(9); // List tag named Lore.
					writeNbtString(nbt, "Lore");
					nbt.writeByte(8); // List entries are strings.
					nbt.writeInt(1);
					writeNbtString(nbt, "Profile-provided lore");
					nbt.writeByte(0); // End display compound.
					nbt.writeByte(0); // End tag compound.
					nbt.writeByte(0); // End of item compound.
				}
				nbt.writeByte(0); // End of compound.
			}
			return Base64.getEncoder().encodeToString(bytes.toByteArray());
		} catch (IOException exception) {
			throw new AssertionError("could not create compressed NBT test fixture", exception);
		}
	}

	private static void writeNbtString(DataOutputStream output, String value) throws IOException {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		output.writeShort(bytes.length);
		output.write(bytes);
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
