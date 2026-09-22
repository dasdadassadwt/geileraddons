package geiler.addons.client.module.impl;

import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.CheatsState;
import geiler.addons.client.module.ModuleAction;
import geiler.addons.client.module.NumberSetting;

import java.util.List;

/**
 * Offline checks for the Cheats master gate.
 *
 * <p>The gate is the one switch that decides whether a see-through-walls style option is allowed to
 * exist at all, so both halves are checked: that a closed gate forces the safe behaviour without
 * erasing the stored choice, and that an open gate hands the stored choice back untouched.
 */
public final class CheatGateChecks {
	private CheatGateChecks() {
	}

	public static void run() {
		boolean original = GeneralModule.INSTANCE.cheats().rawValue();
		GeneralModule.INSTANCE.cheats().setValue(false);
		try {
			checkGateSemantics();
			checkGatedSettings();
			GeneralModule.INSTANCE.cheats().setValue(true);
			checkStoredValuesReturn();
		} finally {
			// The gate is global state; leaving it closed would change how every later check runs.
			GeneralModule.INSTANCE.cheats().setValue(original);
		}
	}

	private static void checkGateSemantics() {
		assertFalse(CheatsState.enabled(), "the Cheats gate is closed while the General switch is off");
		assertFalse(GeneralModule.INSTANCE.cheats().isCheatGated(),
			"the Cheats switch itself is never cheat-gated");
		assertFalse(GeneralModule.INSTANCE.cheats().forcedByCheats(),
			"an ungated toggle is never reported as forced");
		assertFalse(new NumberSetting("test", 0, 10, 5, true).isCheatGated(),
			"numbers are not cheat-gated by default");
		ModuleAction action = new ModuleAction("test", "test", () -> { });
		assertFalse(action.isCheatGated(), "actions are not cheat-gated by default");
		assertFalse(action.forcedByCheats(), "an ungated row is never forced");
	}

	private static void checkGatedSettings() {
		BooleanSetting blockEsp = new BlockEspEntry(91).depthCheck();
		BooleanSetting highlight = new MobHighlight(92).depthCheck();
		BooleanSetting safari = depthSetting(SafariFloorDropsModule.INSTANCE.booleanSettings());
		BooleanSetting pest = depthSetting(PestHighlighterModule.INSTANCE.booleanSettings());

		for (BooleanSetting setting : List.of(blockEsp, highlight, safari, pest)) {
			assertTrue(setting.isCheatGated(), "depth check is cheat-gated");
			assertTrue(setting.forcedByCheats(), "a closed gate marks the depth check as forced");
			assertTrue(setting.value(), "the safe state of a depth check is depth-tested");
		}
		assertFalse(blockEsp.rawValue(), "Block ESP keeps its stored off-state behind the gate");
		assertFalse(highlight.rawValue(), "Mob Highlight keeps its stored off-state behind the gate");
		assertFalse(pest.rawValue(), "Pest Highlighter keeps its stored off-state behind the gate");
		assertTrue(safari.rawValue(), "Safari Floor Drops still stores its depth-tested default");
	}

	private static void checkStoredValuesReturn() {
		assertTrue(CheatsState.enabled(), "the gate follows the General switch");

		BooleanSetting stored = new BlockEspEntry(93).depthCheck();
		stored.setValue(false);
		assertFalse(stored.forcedByCheats(), "an open gate stops forcing the row");
		assertFalse(stored.value(), "an open gate runs the stored value again");

		BooleanSetting safe = BooleanSetting.cheat("Depth Check", "Depth Check", false, true);
		safe.setValue(true);
		assertTrue(safe.value(), "a stored safe value is unchanged while the gate is open");
		safe.setValue(false);
		assertFalse(safe.value(), "the stored value round-trips through the gate");
	}

	private static BooleanSetting depthSetting(List<BooleanSetting> settings) {
		for (BooleanSetting setting : settings) {
			if ("Depth Check".equals(setting.name())) return setting;
		}
		throw new AssertionError("module has no Depth Check setting");
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static void assertFalse(boolean value, String label) {
		if (value) throw new AssertionError(label);
	}
}
