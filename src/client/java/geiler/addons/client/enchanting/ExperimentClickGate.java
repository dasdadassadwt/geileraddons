package geiler.addons.client.enchanting;

import java.util.function.IntConsumer;

/**
 * One by-slot gate for manual and automated sequence clicks.
 *
 * <p>The Minecraft adapter is responsible for proving that the current screen and menu still
 * belong to the active experiment. This class checks the solver's current expected step, wraps the
 * ordinary vanilla click dispatch, and confirms the engine cursor exactly once afterward.</p>
 */
public final class ExperimentClickGate {
	private boolean dispatching;

	public boolean dispatching() {
		return dispatching;
	}

	/** Dispatches only the exact expected Chronomatron or Ultrasequencer slot. */
	public boolean dispatchSequenceClick(ExperimentSolverEngine engine, int slotId,
		boolean contextValid, IntConsumer vanillaDispatch) {
		SolverView before = engine == null ? null : engine.view();
		int expectedSequenceIndex = before == null
			? -1 : before.current().map(SequenceStep::index).orElse(-1);
		return dispatchSequenceClick(engine, expectedSequenceIndex, slotId, contextValid, vanillaDispatch);
	}

	/** Dispatches only the requested sequence index and slot, including repeated slot IDs. */
	public boolean dispatchSequenceClick(ExperimentSolverEngine engine, int expectedSequenceIndex,
		int slotId, boolean contextValid, IntConsumer vanillaDispatch) {
		if (dispatching || !contextValid || engine == null || vanillaDispatch == null) return false;
		SolverView before = engine.view();
		if ((before.type() != ExperimentType.CHRONOMATRON
			&& before.type() != ExperimentType.ULTRASEQUENCER)
			|| before.phase() != ExperimentPhase.SOLVE || before.milestoneReached()
			|| before.current().isEmpty()
			|| before.current().orElseThrow().index() != expectedSequenceIndex
			|| before.visualIndex() != expectedSequenceIndex
			|| !before.current().orElseThrow().containsSlot(slotId)) {
			return false;
		}
		if (!engine.onClick(slotId).expected()) return false;

		dispatchVanilla(() -> vanillaDispatch.accept(slotId));
		return engine.confirmClick(expectedSequenceIndex, slotId).visualStateChanged();
	}

	/**
	 * Wraps a non-sequence vanilla dispatch such as a Superpairs card click. Sequence callers should
	 * use {@link #dispatchSequenceClick} so validation and confirmation cannot be skipped.
	 */
	public void dispatchVanilla(Runnable vanillaDispatch) {
		boolean previous = dispatching;
		dispatching = true;
		try {
			vanillaDispatch.run();
		} finally {
			dispatching = previous;
		}
	}
}
