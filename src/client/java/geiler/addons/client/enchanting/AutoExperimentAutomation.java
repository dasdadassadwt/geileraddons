package geiler.addons.client.enchanting;

/**
 * Minecraft-free timing and pause state for Chronomatron and Ultrasequencer automation.
 *
 * <p>Each tick can return at most one click request. The caller must validate the live screen and
 * menu again, dispatch through its guarded vanilla click path, and report the result before this
 * state machine will consider another click.</p>
 */
public final class AutoExperimentAutomation {
	public static final long STAGE_TRANSITION_WATCHDOG_NANOS = 3_000_000_000L;

	public enum Action {
		WAIT,
		CLICK,
		PAUSED,
		STOPPED,
		CLOSE_MENU
	}

	public record Snapshot(boolean enabled, boolean gameEnabled, boolean contextValid,
		Object screenIdentity, Object menuIdentity, ExperimentType type, ExperimentTier tier,
		ExperimentPhase phase, String status, boolean sequenceAvailable, int currentIndex,
		int sequenceLength, int expectedSlotId, int completedRounds, boolean milestoneReached) {
		public Snapshot {
			status = status == null ? "" : status.trim();
			currentIndex = Math.max(0, currentIndex);
			sequenceLength = Math.max(0, sequenceLength);
			completedRounds = Math.max(-1, completedRounds);
		}
	}

	public record Decision(Action action, int sequenceIndex, int slotId, String explanation) {
		public Decision {
			if (action == null) throw new IllegalArgumentException("action must not be null");
			explanation = explanation == null ? "" : explanation;
		}
	}

	private enum Mode {
		IDLE,
		FIRST_CLICK_DELAY,
		CLICK_DELAY,
		CLICK_PENDING,
		AWAITING_STAGE,
		PAUSED,
		STOPPED
	}

	private Mode mode = Mode.IDLE;
	private boolean contextInitialized;
	private Object screenIdentity;
	private Object menuIdentity;
	private ExperimentType type;
	private ExperimentTier tier;
	private boolean paused;
	private String pauseExplanation = "";
	private long dueAtNanos;
	private int delayedSequenceIndex = -1;
	private int delayedSequenceLength = -1;
	private int delayedSlotId = -1;
	private int pendingSequenceIndex = -1;
	private int pendingSlotId = -1;
	private boolean closeOnMilestone;
	private int completedSequenceLength = -1;
	private int completedRounds = -1;
	private String completionStatus = "";
	private long stageWaitStartedAtNanos;
	private boolean stageWatchdogArmed;

	/** Called whenever Auto Experiments is switched off or on to discard stale work and pauses. */
	public void reset() {
		paused = false;
		pauseExplanation = "";
		clearContext();
	}

	/** Cancels a queued click after the player takes over; a module toggle is required to resume. */
	public String pauseForManualInput() {
		if (paused) return "";
		paused = true;
		closeOnMilestone = false;
		pauseExplanation = "Paused after a manual experiment click. Toggle Auto Experiments off and on to resume.";
		mode = Mode.PAUSED;
		pendingSequenceIndex = -1;
		pendingSlotId = -1;
		return pauseExplanation;
	}

	/** Returns a due click once, then waits for the adapter to report its guarded dispatch result. */
	public Decision tick(Snapshot snapshot, long nowNanos, long firstClickDelayNanos) {
		if (snapshot == null || !snapshot.enabled()) {
			reset();
			return waitDecision();
		}
		if (!snapshot.gameEnabled() || !eligibleContext(snapshot)) {
			clearContext();
			return waitDecision();
		}
		if (!matchesContext(snapshot)) beginContext(snapshot);
		if (paused) return waitDecision();

		if (mode == Mode.STOPPED) {
			// A new puzzle may reuse the same chest menu after the previous one finished.
			if (snapshot.phase() == ExperimentPhase.MEMORIZE
				&& ExperimentPhase.isRememberStatus(snapshot.status())) {
				mode = Mode.IDLE;
			} else {
				return decision(Action.STOPPED, "");
			}
		}
		if (snapshot.tier() == null || snapshot.tier() == ExperimentTier.UNKNOWN) {
			return pause("The experiment tier is unknown; automation paused without clicking.");
		}
		if (snapshot.milestoneReached()) {
			mode = Mode.STOPPED;
			boolean shouldClose = closeOnMilestone;
			closeOnMilestone = false;
			pendingSequenceIndex = -1;
			pendingSlotId = -1;
			return decision(shouldClose ? Action.CLOSE_MENU : Action.STOPPED, "");
		}
		if (snapshot.phase() == ExperimentPhase.COMPLETE) {
			mode = Mode.STOPPED;
			closeOnMilestone = false;
			pendingSequenceIndex = -1;
			pendingSlotId = -1;
			return decision(Action.STOPPED, "");
		}

		ExperimentPhase phase = snapshot.phase() == null ? ExperimentPhase.IDLE : snapshot.phase();
		if (phase == ExperimentPhase.MEMORIZE || phase == ExperimentPhase.WAITING) {
			// These are ordinary game phases. They are not a reason to click or to trip the watchdog.
			mode = Mode.IDLE;
			pendingSlotId = -1;
			stageWatchdogArmed = false;
			return waitDecision();
		}
		if (phase == ExperimentPhase.ROUND_COMPLETE) {
			if (mode != Mode.AWAITING_STAGE) beginAwaitingStage(snapshot, nowNanos);
			ExperimentPhase reported = ExperimentPhase.detect(snapshot.type(), snapshot.status());
			if (stageWatchdogArmed
				&& (reported == ExperimentPhase.MEMORIZE || reported == ExperimentPhase.WAITING)
				&& ExperimentPhase.isKnownStatus(snapshot.type(), snapshot.status())
				&& !ExperimentPhase.normalizeStatus(snapshot.status()).equals(
					ExperimentPhase.normalizeStatus(completionStatus))) {
				// Some game variants keep the local model at ROUND_COMPLETE until the next ordered
				// board update. A known memorize/wait status proves the stage has moved on; a timer
				// still waits for a valid next sequence within the watchdog window.
				stageWatchdogArmed = false;
			}
			return watchdogDecision(nowNanos);
		}
		if (phase != ExperimentPhase.SOLVE) {
			return pause("The experiment state is unavailable; automation paused without clicking.");
		}

		if (mode == Mode.AWAITING_STAGE) {
			if (isNextStage(snapshot)) {
				beginFirstClickDelay(snapshot, nowNanos, firstClickDelayNanos);
				return waitDecision();
			}
			ExperimentPhase reported = ExperimentPhase.detect(snapshot.type(), snapshot.status());
			if ((reported == ExperimentPhase.MEMORIZE || reported == ExperimentPhase.WAITING)
				&& ExperimentPhase.isKnownStatus(snapshot.type(), snapshot.status())
				&& !ExperimentPhase.normalizeStatus(snapshot.status()).equals(
					ExperimentPhase.normalizeStatus(completionStatus))) {
				// The server has entered the next memorize/wait phase, even if the local model is still
				// holding the just-finished sequence until its next ordered menu event arrives.
				stageWatchdogArmed = false;
			}
			if (stageWatchdogArmed && elapsedAtLeast(nowNanos, stageWaitStartedAtNanos,
				STAGE_TRANSITION_WATCHDOG_NANOS)) {
				return pause("The next experiment stage did not appear within 3 seconds; no click was retried.");
			}
			if (isCompletedSequence(snapshot)) return waitDecision();
			return waitDecision();
		}

		if (!ExperimentPhase.isKnownStatus(snapshot.type(), snapshot.status())
			&& !isCorrectFeedback(snapshot.status())) {
			return pause("The experiment state is unavailable; automation paused without clicking.");
		}
		if (isCompletedSequence(snapshot)) {
			beginAwaitingStage(snapshot, nowNanos);
			return waitDecision();
		}
		if (!hasExpectedStep(snapshot)) {
			return pause("The expected experiment sequence is unavailable; automation paused without clicking.");
		}

		if (mode == Mode.IDLE) {
			beginFirstClickDelay(snapshot, nowNanos, firstClickDelayNanos);
			return waitDecision();
		}
		if (mode == Mode.CLICK_PENDING) return waitDecision();
		if (mode == Mode.FIRST_CLICK_DELAY || mode == Mode.CLICK_DELAY) {
			if (!matchesDelayedStep(snapshot)) {
				return pause("The expected experiment step changed during its delay; automation paused without clicking.");
			}
			if (!deadlineReached(nowNanos, dueAtNanos)) return waitDecision();
			pendingSequenceIndex = delayedSequenceIndex;
			pendingSlotId = delayedSlotId;
			mode = Mode.CLICK_PENDING;
			return new Decision(Action.CLICK, pendingSequenceIndex, pendingSlotId, "");
		}
		return pause("The experiment automation state is inconsistent; automation paused without clicking.");
	}

	/** Compatibility overload for older callers; inter-click timing is applied after dispatch. */
	public Decision tick(Snapshot snapshot, long nowNanos, long firstClickDelayNanos,
		long ignoredClickDelayNanos) {
		return tick(snapshot, nowNanos, firstClickDelayNanos);
	}

	/** Completes the one outstanding click request. A rejected dispatch is never retried. */
	public Decision clickResult(boolean dispatched, Snapshot afterDispatch, long nowNanos,
		long clickDelayNanos) {
		if (mode != Mode.CLICK_PENDING) return waitDecision();
		int dispatchedSequenceIndex = pendingSequenceIndex;
		boolean dispatchedFinalStep = delayedSequenceLength > 0
			&& pendingSequenceIndex == delayedSequenceLength - 1 && pendingSlotId >= 0;
		pendingSequenceIndex = -1;
		pendingSlotId = -1;
		if (!dispatched) {
			return pause("The experiment changed before the click could be dispatched; automation paused without retrying.");
		}
		if (afterDispatch == null || !afterDispatch.contextValid() || !matchesContext(afterDispatch)
			|| !afterDispatch.enabled() || !afterDispatch.gameEnabled()) {
			return pause("The experiment screen or menu changed during the click; automation paused without retrying.");
		}
		closeOnMilestone = true;
		if (afterDispatch.milestoneReached()) {
			mode = Mode.STOPPED;
			closeOnMilestone = false;
			return decision(Action.CLOSE_MENU, "");
		}
		if (afterDispatch.phase() == ExperimentPhase.COMPLETE) {
			mode = Mode.STOPPED;
			closeOnMilestone = false;
			return decision(Action.STOPPED, "");
		}
		if (dispatchedFinalStep || afterDispatch.phase() == ExperimentPhase.ROUND_COMPLETE
			|| isCompletedSequence(afterDispatch)) {
			beginAwaitingStage(afterDispatch, nowNanos);
			return waitDecision();
		}
		if (afterDispatch.phase() == ExperimentPhase.MEMORIZE
			|| afterDispatch.phase() == ExperimentPhase.WAITING) {
			mode = Mode.IDLE;
			stageWatchdogArmed = false;
			return waitDecision();
		}
		if (afterDispatch.phase() != ExperimentPhase.SOLVE || !hasExpectedStep(afterDispatch)) {
			return pause("The next experiment step is unavailable after the click; automation paused without retrying.");
		}
		if (afterDispatch.currentIndex() != dispatchedSequenceIndex + 1) {
			return pause("The experiment sequence did not advance after the click; automation paused without retrying.");
		}
		captureDelayedStep(afterDispatch);
		dueAtNanos = nowNanos + Math.max(0L, clickDelayNanos);
		mode = Mode.CLICK_DELAY;
		return waitDecision();
	}

	private boolean eligibleContext(Snapshot snapshot) {
		return snapshot.contextValid() && snapshot.screenIdentity() != null && snapshot.menuIdentity() != null
			&& (snapshot.type() == ExperimentType.CHRONOMATRON
				|| snapshot.type() == ExperimentType.ULTRASEQUENCER);
	}

	private boolean matchesContext(Snapshot snapshot) {
		return contextInitialized && screenIdentity == snapshot.screenIdentity()
			&& menuIdentity == snapshot.menuIdentity() && type == snapshot.type() && tier == snapshot.tier();
	}

	private void beginContext(Snapshot snapshot) {
		clearCycle();
		contextInitialized = true;
		screenIdentity = snapshot.screenIdentity();
		menuIdentity = snapshot.menuIdentity();
		type = snapshot.type();
		tier = snapshot.tier();
		mode = paused ? Mode.PAUSED : Mode.IDLE;
	}

	private void clearContext() {
		contextInitialized = false;
		screenIdentity = null;
		menuIdentity = null;
		type = null;
		tier = null;
		clearCycle();
		mode = paused ? Mode.PAUSED : Mode.IDLE;
	}

	private void clearCycle() {
		dueAtNanos = 0L;
		delayedSequenceIndex = -1;
		delayedSequenceLength = -1;
		delayedSlotId = -1;
		pendingSequenceIndex = -1;
		pendingSlotId = -1;
		closeOnMilestone = false;
		completedSequenceLength = -1;
		completedRounds = -1;
		completionStatus = "";
		stageWaitStartedAtNanos = 0L;
		stageWatchdogArmed = false;
		if (!paused) mode = Mode.IDLE;
	}

	private void beginFirstClickDelay(Snapshot snapshot, long nowNanos, long delayNanos) {
		captureDelayedStep(snapshot);
		dueAtNanos = nowNanos + Math.max(0L, delayNanos);
		stageWatchdogArmed = false;
		mode = Mode.FIRST_CLICK_DELAY;
	}

	private void captureDelayedStep(Snapshot snapshot) {
		delayedSequenceIndex = snapshot.currentIndex();
		delayedSequenceLength = snapshot.sequenceLength();
		delayedSlotId = snapshot.expectedSlotId();
	}

	private boolean matchesDelayedStep(Snapshot snapshot) {
		return hasExpectedStep(snapshot) && snapshot.currentIndex() == delayedSequenceIndex
			&& snapshot.sequenceLength() == delayedSequenceLength
			&& snapshot.expectedSlotId() == delayedSlotId;
	}

	private void beginAwaitingStage(Snapshot snapshot, long nowNanos) {
		mode = Mode.AWAITING_STAGE;
		completedSequenceLength = snapshot.sequenceLength();
		completedRounds = snapshot.completedRounds();
		completionStatus = snapshot.status();
		stageWaitStartedAtNanos = nowNanos;
		ExperimentPhase reported = ExperimentPhase.detect(snapshot.type(), snapshot.status());
		boolean knownStatus = ExperimentPhase.isKnownStatus(snapshot.type(), snapshot.status());
		stageWatchdogArmed = !knownStatus
			|| (reported != ExperimentPhase.MEMORIZE && reported != ExperimentPhase.WAITING);
		pendingSlotId = -1;
	}

	private Decision watchdogDecision(long nowNanos) {
		return stageWatchdogArmed && elapsedAtLeast(nowNanos, stageWaitStartedAtNanos,
			STAGE_TRANSITION_WATCHDOG_NANOS)
			? pause("The next experiment stage did not appear within 3 seconds; no click was retried.")
			: waitDecision();
	}

	private boolean isNextStage(Snapshot snapshot) {
		return hasExpectedStep(snapshot) && snapshot.currentIndex() == 0
			&& (snapshot.sequenceLength() > completedSequenceLength
				|| snapshot.completedRounds() > completedRounds);
	}

	private static boolean hasExpectedStep(Snapshot snapshot) {
		return snapshot.sequenceAvailable() && snapshot.sequenceLength() > 0
			&& snapshot.currentIndex() >= 0 && snapshot.currentIndex() < snapshot.sequenceLength()
			&& snapshot.expectedSlotId() >= 0;
	}

	private static boolean isCompletedSequence(Snapshot snapshot) {
		return snapshot.sequenceAvailable() && snapshot.sequenceLength() > 0
			&& snapshot.currentIndex() >= snapshot.sequenceLength();
	}

	private static boolean isCorrectFeedback(String status) {
		String normalized = ExperimentPhase.normalizeStatus(status);
		return normalized.equals("correct") || normalized.equals("correct!");
	}

	private Decision pause(String explanation) {
		if (paused) return waitDecision();
		paused = true;
		closeOnMilestone = false;
		pauseExplanation = explanation == null ? "Automation paused." : explanation;
		mode = Mode.PAUSED;
		pendingSequenceIndex = -1;
		pendingSlotId = -1;
		return decision(Action.PAUSED, pauseExplanation);
	}

	private static Decision waitDecision() {
		return decision(Action.WAIT, "");
	}

	private static Decision decision(Action action, String explanation) {
		return new Decision(action, -1, -1, explanation);
	}

	private static boolean deadlineReached(long nowNanos, long deadlineNanos) {
		return nowNanos - deadlineNanos >= 0L;
	}

	private static boolean elapsedAtLeast(long nowNanos, long startNanos, long durationNanos) {
		return nowNanos - startNanos >= durationNanos;
	}
}
