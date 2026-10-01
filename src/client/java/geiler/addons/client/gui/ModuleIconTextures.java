package geiler.addons.client.gui;

import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.Set;

/** Bundled pixel-art icons for each registered module. */
final class ModuleIconTextures {
	private static final Map<String, Identifier> ICONS = Map.ofEntries(
		entry("Debug", "debug"),
		guiEntry("General", "octicon-gear-16"),
		entry("Slot IDs", "slot_ids"),
		entry("i4 helper", "i4_helper"),
		entry("Experimentation Solver", "experiment_solver"),
		entry("Auto Experiments", "auto_experiments"),
		entry("Auto Kick", "auto_kick"),
		entry("Party Finder Stats", "party_finder_stats"),
		entry("Dungeon Guide", "dungeon_guide"),
		entry("Box Doors", "box_doors"),
		entry("Tiki Helper", "tiki_helper"),
		entry("Safari Floor Drops", "safari_floor_drops"),
		entry("Hideyho Finder", "hideyho_finder"),
		entry("Sparkling Critter", "sparkling_critter"),
		entry("Pest Highlighter", "pest_highlighter"),
		entry("Infested plot", "infested_plot"),
		entry("Tree Tracker", "tree_tracker"),
		entry("Tree Broken Notifier", "tree_broken_notifier"),
		entry("Macros", "macros"),
		guiEntry("Theme", "octicon-paintbrush-16"),
		entry("Mob Highlight", "mob_highlight"),
		entry("Dungeon Mob ESP", "dungeon_mob_esp"),
		entry("Block ESP", "block_esp"),
		entry("Inventory Buttons", "inventory_buttons")
	);
	/** Source dimensions for 256x256 module cards and the reused toolbar textures. */
	private static final Map<String, Integer> SOURCE_SIZES = Map.ofEntries(
		Map.entry("Debug", 256),
		Map.entry("General", 256),
		Map.entry("Slot IDs", 256),
		Map.entry("i4 helper", 256),
		Map.entry("Experimentation Solver", 256),
		Map.entry("Auto Experiments", 256),
		Map.entry("Auto Kick", 256),
		Map.entry("Theme", 256),
		Map.entry("Party Finder Stats", 256),
		Map.entry("Dungeon Guide", 256),
		Map.entry("Box Doors", 256),
		Map.entry("Tiki Helper", 256),
		Map.entry("Safari Floor Drops", 256),
		Map.entry("Hideyho Finder", 256),
		Map.entry("Sparkling Critter", 256),
		Map.entry("Pest Highlighter", 256),
		Map.entry("Infested plot", 256),
		Map.entry("Tree Tracker", 256),
		Map.entry("Tree Broken Notifier", 256),
		Map.entry("Macros", 256),
		Map.entry("Mob Highlight", 256),
		Map.entry("Dungeon Mob ESP", 256),
		Map.entry("Block ESP", 256),
		Map.entry("Inventory Buttons", 256)
	);
	/** Tinted only in module-card rendering; toolbar icons use separate drawing calls. */
	private static final Set<String> TINTABLE = Set.of(
		"Auto Experiments", "Auto Kick", "Block ESP", "Box Doors", "Debug", "Dungeon Guide",
		"Dungeon Mob ESP", "Experimentation Solver", "General", "Hideyho Finder", "i4 helper",
		"Infested plot", "Inventory Buttons", "Macros", "Mob Highlight", "Party Finder Stats",
		"Pest Highlighter", "Safari Floor Drops", "Slot IDs", "Sparkling Critter", "Theme",
		"Tiki Helper", "Tree Broken Notifier", "Tree Tracker"
	);

	private ModuleIconTextures() { }

	static Identifier forModule(String moduleName) { return ICONS.get(moduleName); }

	static boolean isTintable(String moduleName) { return TINTABLE.contains(moduleName); }

	static int sourceSize(String moduleName) { return SOURCE_SIZES.getOrDefault(moduleName, 64); }

	private static Map.Entry<String, Identifier> entry(String name, String file) {
		return Map.entry(name, Identifier.fromNamespaceAndPath("geileraddons",
			"textures/module_icons/" + file + ".png"));
	}

	private static Map.Entry<String, Identifier> guiEntry(String name, String file) {
		return Map.entry(name, Identifier.fromNamespaceAndPath("geileraddons",
			"textures/gui/" + file + ".png"));
	}
}
