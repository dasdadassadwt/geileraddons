package geiler.addons.client.config;

import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.SettingGroup;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Where the Click GUI was left: which category was selected, which module's settings were open
 * and which color picker was expanded. Held here rather than on the screen so it survives both
 * closing the screen and restarting the game.
 */
public final class ClickGuiState {
	private static Category category = Category.values()[0];
	private static Module openModule;
	private static ColorSetting expandedColor;
	private static int settingsScroll;
	/**
	 * Folded-shut sections, stored as keys rather than flags on the group so the record stays
	 * immutable and unknown keys from an older config are simply ignored.
	 */
	private static final Set<String> collapsedGroups = new LinkedHashSet<>();

	private ClickGuiState() {
	}

	public static Category category() {
		return category;
	}

	public static void setCategory(Category value) {
		category = value;
	}

	public static Module openModule() {
		return openModule;
	}

	public static void setOpenModule(Module value) {
		openModule = value;
	}

	public static ColorSetting expandedColor() {
		return expandedColor;
	}

	public static void setExpandedColor(ColorSetting value) {
		expandedColor = value;
	}

	public static int settingsScroll() {
		return settingsScroll;
	}

	public static void setSettingsScroll(int value) {
		settingsScroll = value;
	}

	/** Unnamed sections have no header to click, so they can never be folded shut. */
	public static boolean isCollapsed(Module module, SettingGroup group) {
		return group.name() != null && collapsedGroups.contains(key(module, group));
	}

	public static void toggleCollapsed(Module module, SettingGroup group) {
		if (group.name() == null) return;
		String key = key(module, group);
		if (!collapsedGroups.remove(key)) {
			collapsedGroups.add(key);
		}
	}

	public static Collection<String> collapsedGroups() {
		return collapsedGroups;
	}

	public static void setCollapsedGroups(Collection<String> keys) {
		collapsedGroups.clear();
		collapsedGroups.addAll(keys);
	}

	/** Qualified by module so two modules can name a section the same thing. */
	private static String key(Module module, SettingGroup group) {
		return module.name() + "." + group.name();
	}
}
