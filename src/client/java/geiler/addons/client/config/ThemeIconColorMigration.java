package geiler.addons.client.config;

import java.util.Map;

/** Moves the module icon tint from General into the Theme module without overwriting a Theme value. */
public final class ThemeIconColorMigration {
	private ThemeIconColorMigration() { }

	/**
	 * Copies the legacy RGBA entry only when the Theme key has never been saved.
	 *
	 * <p>Copy the array so subsequent edits to the parsed legacy entry cannot change the new value.
	 * A present Theme key always wins, even if its value is malformed and the setting falls back to
	 * its declared default when loaded.
	 */
	public static boolean migrate(Map<String, int[]> colors, String themeKey, String legacyKey) {
		if (colors == null || themeKey == null || legacyKey == null || colors.containsKey(themeKey)) {
			return false;
		}
		int[] legacy = colors.get(legacyKey);
		if (legacy == null || legacy.length != 4) return false;
		colors.put(themeKey, legacy.clone());
		return true;
	}
}
