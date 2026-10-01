package geiler.addons.client.enchanting;

/**
 * Diagnostic readout of the Ultrasequencer model, carried on the solver view.
 *
 * <p>BUG-004 hinges on one fact the code cannot work out by itself: whether the server's solve timer
 * appears <em>before</em> the round's first accepted click, or only <em>after</em> it. The answer
 * decides whether automation must make that opening click or merely wait for it, and a single live
 * round settles it. This carries exactly the fields that answer it so the debug log can print one
 * line per changed decision instead of needing a second instrumented build.
 *
 * <p>It is diagnostics only: nothing in the automation reads it, and it never affects a click.
 */
public record SequenceProbe(String lastStatus, String modelState, boolean openingClickOwed,
	int nextSlotId, boolean solveWindowOpen) {
	public static final SequenceProbe NONE = new SequenceProbe("", "", false, -1, false);

	public SequenceProbe {
		lastStatus = lastStatus == null ? "" : lastStatus;
		modelState = modelState == null ? "" : modelState;
	}

	/** One line naming what the board said and what the model made of it. */
	public String describe() {
		if (lastStatus.isEmpty() && modelState.isEmpty()) return "none";
		return "status=\"" + lastStatus + "\" model=" + modelState
			+ " openingClick=" + openingClickOwed + " nextSlot=" + nextSlotId
			+ " solveWindow=" + solveWindowOpen;
	}
}
