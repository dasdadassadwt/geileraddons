package geiler.addons.client.module.impl;

import geiler.addons.client.location.Island;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Small no-server checks for the state boundaries that are easy to regress. */
public final class OfflineChecks {
	private OfflineChecks() {
	}

	public static void main(String[] args) {
		checkIslandModes();
		checkPersonalBestBoundaries();
		checkEveryStatsToggleCombination();
	}

	private static void checkIslandModes() {
		assertSame(Island.PRIVATE_ISLAND, Island.fromMode("dynamic"), "known island mode");
		assertSame(Island.SAFARI, Island.fromMode("SAFARI"), "case-insensitive island mode");
		assertSame(Island.OTHER, Island.fromMode(null), "missing mode");
		assertSame(Island.OTHER, Island.fromMode("unknown_mode"), "unknown mode");
	}

	private static void checkPersonalBestBoundaries() {
		assertTrue(AutoKickRules.personalBestPasses(0, 0), "zero limit disables PB check");
		assertTrue(AutoKickRules.personalBestPasses(60, 60), "PB equal to limit passes");
		assertTrue(AutoKickRules.personalBestPasses(59, 60), "faster PB passes");
		assertFalse(AutoKickRules.personalBestPasses(61, 60), "slower PB fails");
		assertFalse(AutoKickRules.personalBestPasses(0, 60), "missing PB fails a configured check");
	}

	private static void checkEveryStatsToggleCombination() {
		String[] markers = {"Cata 42", "Mage 45", "CA 46.25", "MP 720", "SA 11.14", "PB 6:42",
			"Term ✓", "Hype X", "GDrag ✓", "Bank 125,000,000"};
		for (int mask = 0; mask < 1 << markers.length; mask++) {
			boolean[] enabled = new boolean[markers.length];
			for (int bit = 0; bit < enabled.length; bit++) enabled[bit] = (mask & (1 << bit)) != 0;
			PartyFinderStatsModule.DisplayOptions options = new PartyFinderStatsModule.DisplayOptions(true,
				enabled[0], enabled[1], enabled[2], enabled[3], enabled[4], enabled[5], enabled[6],
				enabled[7], enabled[8], enabled[9]);
			List<Component> lines = PartyFinderStatsModule.CardFormatter.format(null,
				PartyFinderStatsModule.StatsView.preview(), options, 160, List.of());
			String output = lines.get(0).getString();
			for (int bit = 0; bit < markers.length; bit++) {
				if (enabled[bit] != output.contains(markers[bit])) {
					throw new AssertionError("toggle combination " + mask + " rendered marker " + markers[bit]
						+ " incorrectly: " + output);
				}
			}
			if (output.contains("│  │")) throw new AssertionError("empty stat separator in: " + output);
		}
	}

	private static void assertSame(Object expected, Object actual, String label) {
		if (expected != actual) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static void assertFalse(boolean value, String label) {
		if (value) throw new AssertionError(label);
	}
}
