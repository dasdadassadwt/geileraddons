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
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * One user-created mob highlight: what to match, how to draw it, and how often to look.
 *
 * <p>Owns real {@link geiler.addons.client.module.Setting} objects rather than plain fields so the
 * settings panel can render it with the rows it already has. They are deliberately not in the
 * module's flat setting lists: those are keyed by name for the config file, and every highlight
 * would collide on "Match Text". Highlights are persisted as their own section instead.
 */
public final class MobHighlight {
	static final String DEFAULT_DISPLAY_NAME = "New Highlight";
	private static final int MAX_TEXT_LENGTH = 48;

	private final int id;
	private final BooleanSetting enabled = new BooleanSetting("Enabled", true);
	private final BooleanSetting matchName = new BooleanSetting("Match Name", true);
	private final TextSetting matchText = new TextSetting("Match Text", "", MAX_TEXT_LENGTH);
	private final ColorSetting outlineColor = new ColorSetting("Outline Color", 255, 0, 0, 255);
	private final ColorSetting fillColor = new ColorSetting("Fill Color", 255, 0, 0, 60);
	/**
	 * Off by default, unlike the floor-drop boxes.
	 *
	 * <p>A box wrapping a mob is fighting that mob's own model for the same pixels, and Minecraft
	 * models routinely reach past the hitbox they are given - so occlusion here hides the highlight
	 * behind the very thing it is pointing at. Seeing the mob through terrain is the point.
	 */
	private final BooleanSetting depthCheck = new BooleanSetting("Depth Check", false);
	private final NumberSetting scanInterval = new NumberSetting("Scan Interval", 1, 200, 20, true);
	private final TextSetting displayName = new TextSetting("Display Name", DEFAULT_DISPLAY_NAME, MAX_TEXT_LENGTH);
	private final ModuleAction delete;
	/** One switch per island, in enum order so the sections stay grouped as SkyBlock groups them. */
	private final Map<Island, BooleanSetting> islands = new EnumMap<>(Island.class);

	/** Whatever the last scan matched; rebuilt wholesale rather than diffed. */
	private final List<Entity> matches = new ArrayList<>();
	private int ticksSinceScan;

	MobHighlight(int id) {
		this.id = id;
		this.delete = new ModuleAction("Delete Highlight", () -> MobHighlightModule.INSTANCE.remove(this));
		for (Island island : Island.values()) {
			if (island.selectable()) {
				islands.put(island, new BooleanSetting(island.label(), false));
			}
		}
	}

	public int id() {
		return id;
	}

	public BooleanSetting enabled() {
		return enabled;
	}

	public BooleanSetting matchName() {
		return matchName;
	}

	public TextSetting matchText() {
		return matchText;
	}

	public ColorSetting outlineColor() {
		return outlineColor;
	}

	public ColorSetting fillColor() {
		return fillColor;
	}

	public BooleanSetting depthCheck() {
		return depthCheck;
	}

	public NumberSetting scanInterval() {
		return scanInterval;
	}

	public TextSetting displayName() {
		return displayName;
	}

	public Map<Island, BooleanSetting> islands() {
		return islands;
	}

	/**
	 * Whether this highlight is wanted on the island the player is currently on.
	 *
	 * <p>Islands start switched off, so a new highlight does nothing until it is told where it
	 * belongs. The filter only applies once there is an island to apply it to, though: with no
	 * answer from the Mod API - single player, a server that isn't Hypixel, island detection
	 * switched off in the config, or simply the seconds before the handshake lands - there is
	 * nothing to match against, and refusing to draw would make the module look broken rather
	 * than filtered.
	 */
	boolean appliesOn(Island current) {
		if (!HypixelModApi.hasLocation()) return true;
		BooleanSetting setting = islands.get(current);
		return setting != null && setting.value();
	}

	/**
	 * The panel section for this highlight, titled by its display name.
	 *
	 * <p>The id is in the title because the collapsed-shut sections are remembered by their
	 * heading text, and two highlights left on the default name would otherwise fold as one.
	 */
	SettingGroup group() {
		String name = displayName.value().isBlank() ? DEFAULT_DISPLAY_NAME : displayName.value();
		return SettingGroup.switched("#" + id + " " + name, enabled,
				matchName, matchText, outlineColor, fillColor, depthCheck, scanInterval, displayName, delete)
			.containing(islandGroup());
	}

	/**
	 * The island filter, nested inside the highlight it belongs to.
	 *
	 * <p>Folded by default and titled by id rather than by display name: two dozen rows per
	 * highlight would bury everything under them, and a title that tracked the name would lose the
	 * fold the moment the highlight was renamed.
	 */
	private SettingGroup islandGroup() {
		return SettingGroup.folded("#" + id + " Islands", islands.values().toArray(new Setting[0]));
	}

	List<Entity> matches() {
		return matches;
	}

	/** @return true when the interval has elapsed, resetting the count */
	boolean dueForScan() {
		if (++ticksSinceScan < scanInterval.intValue()) return false;
		ticksSinceScan = 0;
		return true;
	}

	void setMatches(List<Entity> found) {
		matches.clear();
		matches.addAll(found);
	}

	void clearMatches() {
		matches.clear();
	}
}
