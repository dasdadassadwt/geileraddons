package geiler.addons.client.macro;

import java.util.List;

/**
 * Which workflows the Cheats gate covers.
 *
 * <p>A workflow that does exactly one thing is the same as pressing a key or clicking a slot, so it
 * stays available with Cheats off. Anything beyond that - a second action, a condition, a loop, a
 * call, a variable - is automation the player did not perform by hand, and that is what the gate is
 * for. Kept free of client state so the classification can be checked without a running game.
 */
public final class MacroCheatRules {
	/** How many action nodes a macro may have while the gate is closed. */
	public static final int FREE_ACTIONS = 1;

	private MacroCheatRules() {
	}

	/** Whether this macro's workflows need the Cheats gate open before any of them may start. */
	public static boolean requiresCheats(MacroDefinition macro) {
		return macro != null && requiresCheats(macro.scripts());
	}

	/**
	 * Counts action nodes across every stack of a macro.
	 *
	 * <p>No recursion into nested lists is needed: a nested list only exists inside a block that is
	 * itself complex, and one complex block already settles the answer.
	 */
	public static boolean requiresCheats(List<MacroScript> scripts) {
		if (scripts == null || scripts.isEmpty()) return false;
		int actions = 0;
		boolean complex = false;
		for (MacroScript script : scripts) {
			if (script == null) continue;
			for (MacroStep step : script.steps()) {
				if (step == null) continue;
				if (!isAction(step) && !isSupport(step)) {
					complex = true;
					continue;
				}
				if (isAction(step)) actions++;
			}
		}
		return complex || actions > FREE_ACTIONS;
	}

	/** Short cause for the chat note and the debug log; never null for a macro that needs Cheats. */
	public static String reason(List<MacroScript> scripts) {
		if (scripts == null || scripts.isEmpty()) return "its workflow needs Cheats";
		int actions = 0;
		boolean complex = false;
		for (MacroScript script : scripts) {
			if (script == null) continue;
			for (MacroStep step : script.steps()) {
				if (step == null) continue;
				if (!isAction(step) && !isSupport(step)) complex = true;
				else if (isAction(step)) actions++;
			}
		}
		if (complex && actions > FREE_ACTIONS) return "it has more than one action and uses conditions, loops or calls";
		if (complex) return "it uses conditions, loops, calls or variables";
		if (actions > FREE_ACTIONS) return "it has more than one action";
		return "its workflow needs Cheats";
	}

	/**
	 * Blocks that automate something on their own. These are what "one action" counts.
	 *
	 * <p>A World Switch is included: travelling somewhere is an action the player would otherwise
	 * have performed, and a macro that only travels is still a single action.
	 */
	public static boolean isAction(MacroStep step) {
		return step instanceof MacroStep.Command || step instanceof MacroStep.Chat
			|| step instanceof MacroStep.Key || step instanceof MacroStep.MouseButton
			|| step instanceof MacroStep.ClickSlot || step instanceof MacroStep.ClickItem
			|| step instanceof MacroStep.SelectHotbarSlot || step instanceof MacroStep.CloseScreen
			|| step instanceof MacroStep.Title || step instanceof MacroStep.Sound
			|| step instanceof MacroStep.WorldSwitch;
	}

	/**
	 * Timing, delays and input blocking: these change how an action happens, not how many there are.
	 *
	 * <p>Blocking the player's own input for a while is not something the mod does on the player's
	 * behalf, so it is scaffolding rather than a second action.
	 */
	public static boolean isSupport(MacroStep step) {
		return step instanceof MacroStep.Wait || step instanceof MacroStep.BlockPlayerInput
			|| step instanceof MacroStep.StartBlockPlayerInput || step instanceof MacroStep.StopBlockPlayerInput;
	}
}
