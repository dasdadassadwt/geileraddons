package geiler.addons.client.enchanting;

import java.util.function.IntConsumer;

/**
 * One by-slot gate for manual and automated sequence clicks.
 *
 * <p>The Minecraft adapter is responsible for proving that the current screen and menu still
 * belong to the active experiment. This class checks the solver's current expected step, wraps the
 * ordinary vanilla click dispatch, and either confirms the cursor once or verifies that the
 * corresponding server-driven transition already advanced exactly one position.</p>
 */
public final class ExperimentClickGate {
	public enum SequenceDispatchResult {
		REJECTED(false, false),
		CONFIRMED(true, true),
		ALREADY_ADVANCED(true, true),
		UNCERTAIN(true, false);

		private final boolean dispatched;
		private final boolean progressAdvanced;

		SequenceDispatchResult(boolean dispatched, boolean progressAdvanced) {
			this.dispatched = dispatched;
			this.progressAdvanced = progressAdvanced;
		}

		/** Whether the vanilla callback ran and must never be sent again for this request. */
		public boolean dispatched() { return dispatched; }
		/** Whether one solver position was confirmed, locally or by a safe immediate transition. */
		public boolean progressAdvanced() { return progressAdvanced; }
	}

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
		return dispatchSequenceClickResult(engine, expectedSequenceIndex, slotId, contextValid,
			vanillaDispatch).dispatched();
	}

	/**
	 * Same guarded dispatch with an explicit result for the caller's retry policy. If vanilla ran
	 * but neither local confirmation nor one safe solver transition followed, UNCERTAIN still marks
	 * the click as dispatched so the caller can pause instead of resending it.
	 */
	public SequenceDispatchResult dispatchSequenceClickResult(ExperimentSolverEngine engine,
		int expectedSequenceIndex, int slotId, boolean contextValid, IntConsumer vanillaDispatch) {
		if (dispatching || !contextValid || engine == null || vanillaDispatch == null) {
			return SequenceDispatchResult.REJECTED;
		}
		SolverView before = engine.view();
		int beforePosition = engine.acceptedSequencePosition();
		if ((before.type() != ExperimentType.CHRONOMATRON
			&& before.type() != ExperimentType.ULTRASEQUENCER)
			|| before.phase() != ExperimentPhase.SOLVE || before.milestoneReached()
			|| before.current().isEmpty()
			|| before.current().orElseThrow().index() != expectedSequenceIndex
			|| before.visualIndex() != expectedSequenceIndex
			|| beforePosition != expectedSequenceIndex
			|| !before.current().orElseThrow().containsSlot(slotId)) {
			return SequenceDispatchResult.REJECTED;
		}
		if (!engine.onClick(slotId).expected()) return SequenceDispatchResult.REJECTED;

		dispatchVanilla(() -> vanillaDispatch.accept(slotId));
		if (engine.confirmClick(expectedSequenceIndex, slotId).visualStateChanged()) {
			return SequenceDispatchResult.CONFIRMED;
		}
		return advancedExactlyOnce(before, engine.view(), expectedSequenceIndex,
			beforePosition, engine.acceptedSequencePosition())
			? SequenceDispatchResult.ALREADY_ADVANCED : SequenceDispatchResult.UNCERTAIN;
	}

	private static boolean advancedExactlyOnce(SolverView before, SolverView after, int dispatchedIndex,
		int beforePosition, int afterPosition) {
		if (before == null || after == null || dispatchedIndex < 0
			|| after.type() != before.type() || after.tier() != before.tier()
			|| after.sequence().size() != before.sequence().size()
			|| beforePosition != dispatchedIndex || afterPosition != dispatchedIndex + 1) return false;
		for (int index = 0; index < before.sequence().size(); index++) {
			if (!before.sequence().get(index).value().equals(after.sequence().get(index).value())) return false;
		}
		int nextIndex = dispatchedIndex + 1;
		boolean sameRound = after.completedRounds() == before.completedRounds();
		if (nextIndex < before.sequence().size()) {
			return sameRound && after.phase() == ExperimentPhase.SOLVE && after.visualIndex() == nextIndex
				&& after.current().isPresent() && after.current().orElseThrow().index() == nextIndex
				&& before.sequence().get(nextIndex).value().equals(after.current().orElseThrow().value());
		}
		if (before.type() == ExperimentType.CHRONOMATRON) {
			return sameRound && after.phase() == ExperimentPhase.ROUND_COMPLETE && after.visualIndex() == nextIndex
				&& after.current().isEmpty();
		}
		SequenceStep finalStep = before.sequence().get(nextIndex - 1);
		if (sameRound) {
			return (after.phase() == ExperimentPhase.SOLVE || after.phase() == ExperimentPhase.ROUND_COMPLETE)
				&& after.current().isPresent() && after.current().orElseThrow().index() == nextIndex - 1
				&& after.current().orElseThrow().containsSlot(finalStep.slotIds().getFirst())
				&& finalStep.value().equals(after.current().orElseThrow().value());
		}
		// The accepted final Ultra click may be followed immediately by its pane edge. Accept only
		// that single expected round count increment; the unchanged sequence and terminal cursor still
		// prove that this dispatch advanced the current round exactly once.
		return nextIndex == before.sequence().size()
			&& after.completedRounds() == before.completedRounds() + 1
			&& after.phase() == ExperimentPhase.ROUND_COMPLETE && after.visualIndex() == nextIndex - 1
			&& after.current().isPresent() && after.current().orElseThrow().index() == nextIndex - 1
			&& after.current().orElseThrow().containsSlot(finalStep.slotIds().getFirst())
			&& finalStep.value().equals(after.current().orElseThrow().value());
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
