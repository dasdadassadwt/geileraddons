package geiler.addons.client.config;

import geiler.addons.client.macro.MacroStep;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.SettingGroup;

import java.util.EnumMap;
import java.util.Collections;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * View state shared by GUI screens in one game session. Persisted positions and category selection
 * are copied to ModConfig; fold state intentionally remains in memory only.
 */
public final class ClickGuiState {
	private static Category category = Category.values()[0];
	private static Module openModule;
	private static ColorSetting expandedColor;
	private static int settingsScroll;
	private static int categoryScroll;
	private static final EnumMap<Category, Integer> gridScrollByCategory = new EnumMap<>(Category.class);
	/** Stable category enum names persisted for the Click GUI's favorites. */
	private static final Set<String> favoriteCategoryIds = new LinkedHashSet<>();
	/** Stable module config names persisted for the Click GUI's favorites. */
	private static final Set<String> favoriteModuleIds = new LinkedHashSet<>();
	/** Expanded section keys. This stays in memory so each fresh game session starts folded. */
	private static final Set<String> expandedGroups = new LinkedHashSet<>();
	/** Expanded control blocks, weakly held so removed macro nodes do not accumulate for the session. */
	private static final Set<MacroStep> expandedMacroNodes = Collections.newSetFromMap(new WeakHashMap<>());

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

	public static int categoryScroll() { return categoryScroll; }

	public static void setCategoryScroll(int value) { categoryScroll = Math.max(0, value); }

	public static int gridScroll(Category value) {
		return value == null ? 0 : gridScrollByCategory.getOrDefault(value, 0);
	}

	public static void setGridScroll(Category category, int value) {
		if (category == null) return;
		gridScrollByCategory.put(category, Math.max(0, value));
	}

	public static Map<Category, Integer> gridScrolls() {
		return Map.copyOf(gridScrollByCategory);
	}

	public static void setGridScrolls(Map<String, Integer> values) {
		gridScrollByCategory.clear();
		if (values == null) return;
		for (Category category : Category.values()) {
			Integer saved = values.get(category.name());
			if (saved != null) setGridScroll(category, saved);
		}
	}

	public static boolean isFavorite(Category value) {
		return value != null && favoriteCategoryIds.contains(value.name());
	}

	public static void toggleFavorite(Category value) {
		if (value == null) return;
		if (!favoriteCategoryIds.remove(value.name())) favoriteCategoryIds.add(value.name());
	}

	public static Set<String> favoriteCategoryIds() {
		return Set.copyOf(favoriteCategoryIds);
	}

	public static void setFavoriteCategoryIds(Collection<String> values) {
		favoriteCategoryIds.clear();
		if (values == null) return;
		for (Category value : Category.values()) {
			if (values.contains(value.name())) favoriteCategoryIds.add(value.name());
		}
	}

	public static boolean isFavorite(Module value) {
		return value != null && favoriteModuleIds.contains(value.configName());
	}

	public static void toggleFavorite(Module value) {
		if (value == null) return;
		String stableId = value.configName();
		if (!favoriteModuleIds.remove(stableId)) favoriteModuleIds.add(stableId);
	}

	public static Set<String> favoriteModuleIds() {
		return Set.copyOf(favoriteModuleIds);
	}

	public static void setFavoriteModuleIds(Collection<String> values) {
		favoriteModuleIds.clear();
		if (values == null) return;
		for (String value : values) {
			if (value != null && !value.isBlank()) favoriteModuleIds.add(value);
		}
	}

	/**
	 * Unnamed sections have no header to click, so they can never be folded shut. Named sections
	 * begin folded at process startup; their open state is retained only until the game exits.
	 */
	public static boolean isCollapsed(Module module, SettingGroup group) {
		if (group.name() == null) return false;
		return !expandedGroups.contains(key(module, group));
	}

	public static void toggleCollapsed(Module module, SettingGroup group) {
		if (group.name() == null) return;
		String key = key(module, group);
		if (!expandedGroups.remove(key)) expandedGroups.add(key);
	}

	public static boolean macroNodeExpanded(MacroStep step) {
		return step != null && expandedMacroNodes.contains(step);
	}

	public static void toggleMacroNode(MacroStep step) {
		if (step == null) return;
		if (!expandedMacroNodes.remove(step)) expandedMacroNodes.add(step);
	}

	/** Qualified by module so two modules can name a section the same thing. */
	private static String key(Module module, SettingGroup group) {
		return module.name() + "." + (group.stateKey() == null ? group.name() : group.stateKey());
	}
}
