package geiler.addons.client.module.impl;

import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.ModuleAction;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.module.TextSetting;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;

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

	/** Whatever the last scan matched; rebuilt wholesale rather than diffed. */
	private final List<Entity> matches = new ArrayList<>();
	private int ticksSinceScan;

	MobHighlight(int id) {
		this.id = id;
		this.delete = new ModuleAction("Delete Highlight", () -> MobHighlightModule.INSTANCE.remove(this));
	}

	public int id() {
		return id;
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

	/**
	 * The panel section for this highlight, titled by its display name.
	 *
	 * <p>The id is in the title because the collapsed-shut sections are remembered by their
	 * heading text, and two highlights left on the default name would otherwise fold as one.
	 */
	SettingGroup group() {
		String name = displayName.value().isBlank() ? DEFAULT_DISPLAY_NAME : displayName.value();
		return new SettingGroup("#" + id + " " + name,
			matchName, matchText, outlineColor, fillColor, depthCheck, scanInterval, displayName, delete);
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
