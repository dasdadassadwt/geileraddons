package geiler.addons.client.macro;

import java.util.List;

/**
 * Offline checks for what the Cheats gate covers.
 *
 * <p>The rule decides whether a player's existing macros stop working, so both directions are
 * pinned: the one-action workflows that stay free, and every shape of automation that does not.
 */
public final class MacroCheatRulesChecks {
	private MacroCheatRulesChecks() {
	}

	public static void run() {
		checkFreeWorkflows();
		checkGatedWorkflows();
		checkClassification();
	}

	private static void checkFreeWorkflows() {
		assertFalse(MacroCheatRules.requiresCheats(macroWith()), "an empty macro needs no Cheats");
		assertFalse(MacroCheatRules.requiresCheats(macroWith(new MacroStep.SelectHotbarSlot(3))),
			"a macro with one action runs without Cheats");
		assertFalse(MacroCheatRules.requiresCheats(macroWith(
			new MacroStep.Command("/is"), new MacroStep.Wait(500, 900),
			new MacroStep.BlockPlayerInput(250), new MacroStep.StopBlockPlayerInput())),
			"waits and input blocking are scaffolding, not extra actions");
		assertFalse(MacroCheatRules.requiresCheats(macroWith(new MacroStep.WorldSwitch(
			geiler.addons.client.location.Island.HUB))),
			"a macro that only travels is a single action");
		assertFalse(MacroCheatRules.requiresCheats((List<MacroScript>) null), "a missing macro needs no Cheats");

		MacroDefinition twoStacks = new MacroDefinition(1);
		twoStacks.primaryKeyScript().steps().add(new MacroStep.Chat("hi"));
		twoStacks.addScript(MacroScript.Trigger.CHAT).steps().add(new MacroStep.CloseScreen());
		assertTrue(MacroCheatRules.requiresCheats(twoStacks),
			"one action in each of two stacks is still two actions");
	}

	private static void checkGatedWorkflows() {
		assertTrue(MacroCheatRules.requiresCheats(macroWith(
			new MacroStep.Command("/is"), new MacroStep.Command("/p warp"))),
			"two actions need Cheats");
		assertTrue(MacroCheatRules.requiresCheats(macroWith(
			new MacroStep.IfElse(new MacroCondition.Always(true)))),
			"a condition needs Cheats");
		assertTrue(MacroCheatRules.requiresCheats(macroWith(new MacroStep.WaitUntil(new MacroCondition.World(true)))),
			"a wait-until needs Cheats");
		assertTrue(MacroCheatRules.requiresCheats(macroWith(new MacroStep.Repeat(false, 3))),
			"a loop needs Cheats");
		assertTrue(MacroCheatRules.requiresCheats(macroWith(new MacroStep.MacroCall(2))),
			"a macro call needs Cheats");
		assertTrue(MacroCheatRules.requiresCheats(macroWith(new MacroStep.FunctionCall("fn"))),
			"a function call needs Cheats");
		assertTrue(MacroCheatRules.requiresCheats(macroWith(new MacroStep.ChangeVariable("count", 1))),
			"a variable needs Cheats");
		assertTrue(MacroCheatRules.requiresCheats(macroWith(new MacroStep.SetVariable("flag",
			MacroValue.Type.BOOLEAN, MacroValue.literal(MacroValue.Type.BOOLEAN, "true")))),
			"setting a variable needs Cheats");
		assertTrue(MacroCheatRules.requiresCheats(macroWith(new MacroStep.RepeatUntil(new MacroCondition.Always(false)))),
			"a repeat-until needs Cheats");

		// A complex block settles it even inside a stack whose only other block is support.
		MacroDefinition nested = new MacroDefinition(2);
		MacroStep.Repeat repeat = new MacroStep.Repeat(false, 2);
		repeat.steps().add(new MacroStep.Wait(10, 20));
		nested.primaryKeyScript().steps().add(repeat);
		assertTrue(MacroCheatRules.requiresCheats(nested),
			"a loop whose body is only waits still needs Cheats");
	}

	private static void checkClassification() {
		assertTrue(MacroCheatRules.isAction(new MacroStep.Key("key.keyboard.space", false, 50)),
			"a key press counts as an action");
		assertTrue(MacroCheatRules.isAction(new MacroStep.Sound("minecraft:ui.button.click")),
			"a sound counts as an action");
		assertTrue(MacroCheatRules.isSupport(new MacroStep.Wait(1, 2)), "a wait is support");
		assertTrue(MacroCheatRules.isSupport(new MacroStep.StartBlockPlayerInput()),
			"starting an input block is support");
		assertFalse(MacroCheatRules.isAction(new MacroStep.SetVariable("x", MacroValue.Type.TEXT,
			MacroValue.literal(MacroValue.Type.TEXT, ""))),
			"a variable assignment is not a single action");
		assertFalse(MacroCheatRules.isSupport(new MacroStep.MacroCall(1)),
			"a macro call is not scaffolding");

		assertEquals("it has more than one action",
			MacroCheatRules.reason(macroWith(new MacroStep.Command("/a"), new MacroStep.Command("/b")).scripts()),
			"the reason names the extra action");
		assertEquals("it uses conditions, loops, calls or variables",
			MacroCheatRules.reason(macroWith(new MacroStep.MacroCall(2)).scripts()),
			"the reason names the control flow");
		assertEquals("it has more than one action and uses conditions, loops or calls",
			MacroCheatRules.reason(macroWith(new MacroStep.Command("/a"), new MacroStep.Command("/b"),
				new MacroStep.MacroCall(2)).scripts()),
			"the reason covers both causes");
	}

	private static MacroDefinition macroWith(MacroStep... steps) {
		MacroDefinition macro = new MacroDefinition(90);
		for (MacroStep step : steps) macro.primaryKeyScript().steps().add(step);
		return macro;
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static void assertFalse(boolean value, String label) {
		if (value) throw new AssertionError(label);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected " + expected + ", got " + actual);
		}
	}
}
