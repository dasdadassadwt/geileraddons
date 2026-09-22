package geiler.addons.client.module.impl;

import geiler.addons.client.location.HypixelModApi;
import geiler.addons.client.location.Island;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.ModuleAction;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.module.TextSetting;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** One exact registered block type and its independent ESP style and island allowlist. */
public final class BlockEspEntry {
	private final int id;
	private String blockId = "";
	private String folderId;
	private final BooleanSetting enabled = new BooleanSetting("Enabled", true);
	private final BooleanSetting box = new BooleanSetting("Box", true);
	private final BooleanSetting fill = new BooleanSetting("Fill", true);
	private final BooleanSetting outline = new BooleanSetting("Outline", true);
	private final BooleanSetting showLabel = new BooleanSetting("Show Label", true);
	private final BooleanSetting tracer = new BooleanSetting("Tracer", false);
	private final BooleanSetting connectTouching = new BooleanSetting("Connect Touching Blocks", true);
	private final BooleanSetting depthCheck = new BooleanSetting("Depth Check", false);
	private final BooleanSetting useCustomRange = new BooleanSetting("Use Custom Range", false);
	private final NumberSetting customRange = new NumberSetting("Custom Range (blocks)", 1, 512, 32, true);
	private final ColorSetting outlineColor = new ColorSetting("Outline Color", 255, 190, 0, 255);
	private final ColorSetting fillColor = new ColorSetting("Fill Color", 255, 190, 0, 48);
	private final TextSetting displayName = new TextSetting("Display Name", "New Block", 48);
	private final ModuleAction chooseBlock;
	private final ModuleAction delete;
	private final Map<Island, BooleanSetting> islands = new EnumMap<>(Island.class);
	private int matchCount;

	BlockEspEntry(int id) {
		this.id = id;
		chooseBlock = new ModuleAction("Choose Block…", "Search registered block names and identifiers.",
			() -> BlockEspModule.INSTANCE.openPicker(this));
		delete = new ModuleAction("Delete Block ESP Entry", () -> BlockEspModule.INSTANCE.remove(this));
		for (Island island : Island.values()) {
			if (island.selectable()) islands.put(island, new BooleanSetting(island.label(), false));
		}
	}

	public int id() { return id; }
	public String blockId() { return blockId; }
	public void setBlockId(String value) { blockId = value == null ? "" : value.trim().substring(0, Math.min(128, value.trim().length())); }
	public String folderId() { return folderId; }
	public void setFolderId(String value) { folderId = value == null || value.isBlank() ? null : value; }
	public BooleanSetting enabled() { return enabled; }
	public BooleanSetting box() { return box; }
	public BooleanSetting fill() { return fill; }
	public BooleanSetting outline() { return outline; }
	public BooleanSetting showLabel() { return showLabel; }
	public BooleanSetting tracer() { return tracer; }
	public BooleanSetting connectTouching() { return connectTouching; }
	public BooleanSetting depthCheck() { return depthCheck; }
	public BooleanSetting useCustomRange() { return useCustomRange; }
	public NumberSetting customRange() { return customRange; }
	public ColorSetting outlineColor() { return outlineColor; }
	public ColorSetting fillColor() { return fillColor; }
	public TextSetting displayName() { return displayName; }
	public Map<Island, BooleanSetting> islands() { return Collections.unmodifiableMap(islands); }
	public int matchCount() { return matchCount; }
	void setStatus(int count) { matchCount = Math.max(0, count); }

	boolean appliesOn(Island current) {
		if (!HypixelModApi.hasLocation() || current == null || current == Island.NONE || current == Island.OTHER) return false;
		BooleanSetting setting = islands.get(current);
		return setting != null && setting.value();
	}

	SettingGroup group() {
		String title = displayName.value().isBlank() ? "Block ESP #" + id : "#" + id + " " + displayName.value();
		String blockLabel = blockId.isBlank() ? "Block: Not selected" : "Block: " + blockId;
		String status = "Matches: " + matchCount;
		SettingGroup islandGroup = SettingGroup.folded("Islands",
			islands.values().toArray(new Setting[0])).keyed("block-esp-islands:" + id);
		return SettingGroup.switched(title, enabled,
			new ModuleAction(blockLabel, "Select one exact block type from the registry.",
				() -> BlockEspModule.INSTANCE.openPicker(this)),
			useCustomRange, customRange, box, fill, outline, displayName, showLabel, tracer, connectTouching,
			depthCheck, outlineColor, fillColor,
			new ModuleAction(status, "Every matching position in range from the last completed scan is included.", () -> { }), delete)
			.containing(islandGroup).keyed("block-esp-entry:" + id);
	}
}
