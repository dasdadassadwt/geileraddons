package geiler.addons.client.enchanting;

import java.util.List;
import java.util.Optional;

/** Timed, one-pass dispatch of an immutable solution supplied by the experiment solver. */
public final class UltrasequencerSequenceExecutor {
	public enum Action { WAIT, CLICK, PAUSED, STOPPED, CLOSE_MENU }

	public record Decision(Action action, int sequenceIndex, int slotId, String explanation) {
		public Decision {
			action = action == null ? Action.WAIT : action;
			explanation = explanation == null ? "" : explanation;
		}
	}

	/** Immutable, presentation-only view of the Auto cursor for the exact captured solution. */
	public record ExecutionProgress(Solution solution, int cursor, boolean dispatchPending,
		boolean paused, String pauseReason) {
		public ExecutionProgress {
			if (solution == null) throw new IllegalArgumentException("solution must not be null");
			cursor = Math.max(0, Math.min(solution.sequenceLength(), cursor));
			pauseReason = pauseReason == null ? "none" : pauseReason;
		}

		public int sequenceLength() {
			return solution.sequenceLength();
		}
	}

	/** Read-only health check and adapter over the Solver's immutable render snapshot. */
	public record Solution(ExperimentTier tier, int completedRounds, int sequenceLength,
		int milestoneSequenceLength,
		List<SequenceStep> sequence) {
		public Solution {
			tier = tier == null ? ExperimentTier.UNKNOWN : tier;
			sequence = List.copyOf(sequence == null ? List.of() : sequence);
		}

		public static Optional<Solution> from(SolverView view) {
			if (view == null || view.type() != ExperimentType.ULTRASEQUENCER
				|| view.phase() != ExperimentPhase.SOLVE || view.tier() == null
				|| view.tier() == ExperimentTier.UNKNOWN
				|| view.completedRounds() < 0 || view.currentSequenceLength() <= 0
				|| view.sequence().size() != view.currentSequenceLength() || view.visualIndex() != 0
				|| view.current().isEmpty() || view.sequence().isEmpty()) return Optional.empty();

			for (int index = 0; index < view.sequence().size(); index++) {
				SequenceStep step = view.sequence().get(index);
				if (step == null || step.index() != index || step.value().isBlank()
					|| step.slotIds().size() != 1) {
					return Optional.empty();
				}
			}
			if (!view.current().orElseThrow().equals(view.sequence().getFirst())) return Optional.empty();
			int milestoneSequenceLength = view.milestone()
				.filter(milestone -> milestone.type() == ExperimentType.ULTRASEQUENCER)
				.map(ExperimentMilestone::displayedSequenceLength).orElse(-1);
			return Optional.of(new Solution(view.tier(), view.completedRounds(),
				view.currentSequenceLength(), milestoneSequenceLength, view.sequence()));
		}
	}

	private Object screenIdentity;
	private Object menuIdentity;
	private long sessionGeneration = Long.MIN_VALUE;
	private boolean contextBound;
	private Solution solution;
	private int cursor;
	private long dueAtNanos;
	private boolean delayArmed;
	private boolean dispatchPending;
	private boolean closeOnMilestone;
	private boolean milestoneStopped;
	private boolean milestoneCloseRequested;
	private boolean paused;
	private String pauseReason = "none";
	private String pendingPauseNotice = "";
	private String reason = "waiting for a complete solver solution";

	/**
	 * Binds work to one screen, menu, and controller session. A new context discards any queued
	 * position and gets its own first-click delay before the current solver solution can run.
	 */
	public Decision tick(Object screen, Object menu, long sessionGeneration, boolean sessionValid,
		SolverView view, long nowNanos, long firstClickDelayNanos) {
		if (screen == null || menu == null) {
			reset();
			return waitDecision("waiting for the Ultrasequencer screen");
		}
		if (!contextBound || screen != screenIdentity || menu != menuIdentity
			|| sessionGeneration != this.sessionGeneration) {
			bindContext(screen, menu, sessionGeneration);
		}
		if (milestoneStopped) return decision(Action.STOPPED, -1, -1, reason);
		if (paused) return decision(Action.PAUSED, -1, -1, pauseReason);
		if (dispatchPending) return pause("A previous click attempt did not finish; it will not be resent.");
		if (!sessionValid) {
			if (solution != null && cursor < solution.sequence().size()) {
				return pause("The active experiment session is unavailable; automation paused without another click.");
			}
			reason = "waiting for the active Ultrasequencer session";
			return waitDecision(reason);
		}
		if (view != null && view.milestoneReached()) return reachMilestone();

		Optional<Solution> current = Solution.from(view);
		if (current.isEmpty()) {
			if (solution != null && cursor < solution.sequence().size()) {
				return pause("The solver solution became unavailable, unhealthy, or incomplete before execution finished.");
			}
			if (view != null && view.type() == ExperimentType.ULTRASEQUENCER
				&& view.phase() == ExperimentPhase.SOLVE) {
				return pause("The solver's Ultrasequencer solution is unhealthy or incomplete; no click was sent.");
			}
			reason = cursor >= (solution == null ? 0 : solution.sequence().size())
				? "sequence dispatched; waiting for the solver's next solution"
				: "waiting for a complete solver solution";
			return waitDecision(reason);
		}

		Solution solverSolution = current.orElseThrow();
		if (solution == null) {
			if (isPastMilestone(solverSolution)) return reachMilestone();
			beginSolution(solverSolution, nowNanos, firstClickDelayNanos);
		} else if (!solution.equals(solverSolution)) {
			if (cursor < solution.sequence().size()) {
				return pause("The solver solution changed before the ordered sequence finished.");
			}
			if (isPastMilestone(solverSolution)) return reachMilestone();
			beginSolution(solverSolution, nowNanos, firstClickDelayNanos);
		}

		if (cursor >= solution.sequence().size()) {
			reason = "sequence dispatched; waiting for the solver's next solution";
			return waitDecision(reason);
		}
		if (!delayArmed) {
			dueAtNanos = nowNanos + Math.max(0L, firstClickDelayNanos);
			delayArmed = true;
		}
		if (nowNanos < dueAtNanos) {
			reason = "waiting for configured click delay";
			return waitDecision(reason);
		}

		SequenceStep step = solution.sequence().get(cursor++);
		dispatchPending = true;
		delayArmed = false;
		reason = "dispatching solver sequence position " + cursor + "/" + solution.sequenceLength();
		return decision(Action.CLICK, step.index(), step.slotIds().getFirst(), reason);
	}

	/** Records only whether vanilla dispatch ran; solver cursor or server state is not consulted. */
	public Decision dispatchFinished(boolean vanillaDispatched, long nowNanos, long nextClickDelayNanos) {
		if (!dispatchPending) return waitDecision(reason);
		dispatchPending = false;
		if (!vanillaDispatched) {
			return pause("The screen or session guard rejected the click; it will not be retried.");
		}
		closeOnMilestone = true;
		if (solution != null && cursor >= solution.sequence().size() && isMilestoneSequence(solution)) {
			return reachMilestone();
		}
		if (solution != null && cursor < solution.sequence().size()) {
			dueAtNanos = nowNanos + Math.max(0L, nextClickDelayNanos);
			delayArmed = true;
			reason = "click dispatched; waiting for configured delay before the next solver slot";
		} else {
			dueAtNanos = 0L;
			delayArmed = false;
			reason = "complete solver sequence dispatched; waiting for the solver's next solution";
		}
		return waitDecision(reason);
	}

	public String pauseForManualInput() {
		if (paused || milestoneStopped) return "";
		pause("manual Ultrasequencer input");
		pendingPauseNotice = "";
		return "Paused after manual Ultrasequencer input. Toggle Auto Experiments off and on to resume.";
	}

	public String pauseForManualInput(Object screen, Object menu, long sessionGeneration) {
		if (screen == null || menu == null) return "";
		if (!contextBound || screen != screenIdentity || menu != menuIdentity
			|| sessionGeneration != this.sessionGeneration) {
			bindContext(screen, menu, sessionGeneration);
		}
		return pauseForManualInput();
	}

	public String consumePauseNotice() {
		String notice = pendingPauseNotice;
		pendingPauseNotice = "";
		return notice;
	}

	public String reason() { return reason; }
	public String pauseReason() { return paused ? pauseReason : "none"; }
	public boolean paused() { return paused; }
	public int cursor() { return cursor; }
	public int sequenceLength() { return solution == null ? 0 : solution.sequence().size(); }

	/**
	 * Returns Auto's cursor for display only when every execution identity still matches. This method
	 * never changes the scheduler, cursor, pause state, or captured solution.
	 */
	public Optional<ExecutionProgress> presentationProgress(Object screen, Object menu,
		long sessionGeneration, SolverView view) {
		if (!contextBound || screen == null || menu == null || screen != screenIdentity
			|| menu != menuIdentity || sessionGeneration != this.sessionGeneration || solution == null) {
			return Optional.empty();
		}
		Optional<Solution> current = Solution.from(view);
		if (current.isEmpty() || !solution.equals(current.orElseThrow())) return Optional.empty();
		return Optional.of(new ExecutionProgress(solution, cursor, dispatchPending, paused, pauseReason));
	}

	public String pendingDelay() {
		return !delayArmed || solution == null || cursor >= solution.sequence().size() ? "none"
			: "armed";
	}

	public void reset() {
		screenIdentity = null;
		menuIdentity = null;
		sessionGeneration = Long.MIN_VALUE;
		contextBound = false;
		solution = null;
		cursor = 0;
		dueAtNanos = 0L;
		delayArmed = false;
		dispatchPending = false;
		closeOnMilestone = false;
		milestoneStopped = false;
		milestoneCloseRequested = false;
		paused = false;
		pauseReason = "none";
		pendingPauseNotice = "";
		reason = "waiting for a complete solver solution";
	}

	private void beginSolution(Solution next, long nowNanos, long firstClickDelayNanos) {
		solution = next;
		cursor = 0;
		dueAtNanos = nowNanos + Math.max(0L, firstClickDelayNanos);
		delayArmed = true;
		dispatchPending = false;
		reason = "solver solution captured; waiting for configured first-click delay";
	}

	private static boolean isPastMilestone(Solution solution) {
		return solution != null && solution.milestoneSequenceLength() > 0
			&& solution.sequenceLength() > solution.milestoneSequenceLength();
	}

	private static boolean isMilestoneSequence(Solution solution) {
		return solution != null && solution.milestoneSequenceLength() > 0
			&& solution.sequenceLength() >= solution.milestoneSequenceLength();
	}

	private void bindContext(Object screen, Object menu, long generation) {
		reset();
		screenIdentity = screen;
		menuIdentity = menu;
		sessionGeneration = generation;
		contextBound = true;
	}

	private Decision reachMilestone() {
		if (!milestoneStopped) {
			milestoneStopped = true;
			paused = true;
			pauseReason = "experiment bonus-click milestone reached";
			pendingPauseNotice = "";
			dispatchPending = false;
			delayArmed = false;
			reason = "experiment bonus-click milestone reached; automation stopped";
		}
		if (closeOnMilestone && !milestoneCloseRequested) {
			milestoneCloseRequested = true;
			return decision(Action.CLOSE_MENU, -1, -1, reason);
		}
		return decision(Action.STOPPED, -1, -1, reason);
	}

	private Decision pause(String explanation) {
		if (!paused) {
			paused = true;
			pauseReason = explanation == null || explanation.isBlank()
				? "automation paused" : explanation;
			pendingPauseNotice = pauseReason;
			dispatchPending = false;
			delayArmed = false;
			reason = pauseReason;
		}
		return decision(Action.PAUSED, -1, -1, pauseReason);
	}

	private static Decision waitDecision(String explanation) {
		return decision(Action.WAIT, -1, -1, explanation);
	}

	private static Decision decision(Action action, int index, int slot, String explanation) {
		return new Decision(action, index, slot, explanation);
	}
}
