package geiler.addons.client.config;

import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.Module;

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
}
