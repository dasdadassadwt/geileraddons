package geiler.addons.client.config;

import java.util.HashMap;
import java.util.Map;

/** Focused offline checks for the General-to-Theme icon-color migration. */
public final class ThemeIconColorMigrationChecks {
	private static final String THEME_KEY = "Theme.Icon color";
	private static final String LEGACY_KEY = "General.Icon color";

	private ThemeIconColorMigrationChecks() { }

	public static void run() {
		int[] legacy = {12, 34, 56, 78};
		Map<String, int[]> colors = new HashMap<>();
		colors.put(LEGACY_KEY, legacy);
		check(ThemeIconColorMigration.migrate(colors, THEME_KEY, LEGACY_KEY),
			"a saved General icon color migrates when Theme has no value");
		check(java.util.Arrays.equals(legacy, colors.get(THEME_KEY)),
			"migration preserves every legacy RGBA channel");
		check(colors.get(THEME_KEY) != legacy,
			"migration copies the saved channel array");
		check(!ThemeIconColorMigration.migrate(colors, THEME_KEY, LEGACY_KEY),
			"a migrated Theme value makes the migration idempotent");

		int[] theme = {90, 80, 70, 60};
		colors.put(THEME_KEY, theme);
		legacy[0] = 255;
		check(!ThemeIconColorMigration.migrate(colors, THEME_KEY, LEGACY_KEY)
			&& colors.get(THEME_KEY) == theme && theme[0] == 90,
			"an existing Theme value wins over the legacy General value");

		Map<String, int[]> missing = new HashMap<>();
		check(!ThemeIconColorMigration.migrate(missing, THEME_KEY, LEGACY_KEY),
			"missing legacy icon color leaves the declared default in place");
		missing.put(LEGACY_KEY, new int[]{1, 2, 3});
		check(!ThemeIconColorMigration.migrate(missing, THEME_KEY, LEGACY_KEY),
			"malformed legacy channel arrays are ignored");
	}

	private static void check(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}
}
