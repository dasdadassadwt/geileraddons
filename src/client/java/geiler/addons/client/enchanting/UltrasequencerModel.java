package geiler.addons.client.enchanting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tick-driven number memory and mutation-driven round boundaries, following Skyblocker.
 *
 * <p>Pane churn during an unfinished solve is the board's button animation, so only the final
 * accepted click lets a pane edge end that round. An edge before the timer can be reopened once
 * for an unplayed remembered round; a completed replay never accepts a stale timer.</p>
 */
public final class UltrasequencerModel {
	public enum State { REMEMBER, WAIT, SHOW, END }
	private final Map<Integer, ExperimentCell> remembered = new LinkedHashMap<>();
	private State state = State.REMEMBER;
	private int nextSlot = -1;
	private String lastPaneColor;
	private int completedRounds;
	/** Accepted clicks in the current replay; pane animations only end it after the final click. */
	private int clicksThisRound;
	/** The pane changed while a remembered round was waiting for its solve timer. */
	private boolean reopenUnplayedRoundOnTimer;
	/** Whether this round has already contributed to {@link #completedRounds}. */
	private boolean countedThisRound;

	/** Called once after a screen tick, never for each slot update or render frame. */
	public void observe(String status, List<ExperimentCell> cells, String ignoredPaneColor,
		boolean ignoredStatusEvent) {
		observe(status, cells, ignoredPaneColor);
	}
	public void observe(String status, List<ExperimentCell> cells, String ignoredPaneColor) {
		String instruction = status == null ? "" : status;
		switch (state) {
			case REMEMBER -> {
				if (isRememberNotice(instruction)) capture(cells);
			}
			case WAIT -> {
				if (isTimerNotice(instruction) && !remembered.isEmpty()) state = State.SHOW;
			}
			case END -> {
				if (isTimerNotice(instruction)) {
					if (canReopenUnplayedRound()) {
						state = State.SHOW;
						reopenUnplayedRoundOnTimer = false;
					}
					return;
				}
				if (isRememberNotice(instruction)) {
					beginNextRound();
					capture(cells);
				} else reset();
			}
			case SHOW -> {
				// A new memory notice can arrive without a pane callback. It is authoritative and
				// starts a fresh capture even if this model missed the previous round edge.
				if (isRememberNotice(instruction)) {
					beginNextRound();
					capture(cells);
				}
			}
		}
	}

	private static boolean isRememberNotice(String instruction) {
		return instruction.equals("Remember the pattern!") || ExperimentPhase.isRememberStatus(instruction);
	}

	private static boolean isTimerNotice(String instruction) {
		return instruction.startsWith("Timer: ") || ExperimentPhase.isTimerStatus(instruction);
	}

	private void capture(List<ExperimentCell> cells) {
		clearRound();
		boolean captured = false;
		for (ExperimentCell cell : cells) {
			if (cell == null || cell.removed() || !cell.hasValue() || !cell.value().matches("\\d+")) continue;
			remembered.put(cell.slotId(), cell);
			if (cell.value().equals("1")) nextSlot = cell.slotId();
			captured = true;
		}
		// A status update can precede the board slots. Keep waiting for a later tick to capture them.
		state = captured ? State.WAIT : State.REMEMBER;
	}

	/** Inspect panes in slot order on actual menu mutations, including during memory. */
	public void markDirty(List<String> paneColors) {
		for (String color : paneColors) {
			if (color == null || color.equals("black")) continue;
			if (!color.equals(lastPaneColor)) {
				boolean completed = roundFinished();
				if (completed && !countedThisRound) {
					countedThisRound = true;
					completedRounds++;
				}
				lastPaneColor = color;
				// A color transition inside the solve is the normal pressed/released animation.
				// Only close an unfinished model state before solve, or after every replay click.
				if (!midSolve() && state != State.END) {
					reopenUnplayedRoundOnTimer = !completed && state == State.WAIT
						&& !remembered.isEmpty() && clicksThisRound == 0;
					state = State.END;
				}
				return;
			}
		}
	}

	public boolean click(int slotId) {
		if (!canClick(slotId)) return false;
		ExperimentCell current = remembered.get(nextSlot);
		if (current == null) return false;
		int wantedCount = current.numericValue() + 1;
		for (ExperimentCell cell : remembered.values()) {
			if (cell.numericValue() == wantedCount) {
				nextSlot = cell.slotId();
				break;
			}
		}
		clicksThisRound++;
		// The final button remains selected until the pane color changes, as in Skyblocker.
		return true;
	}

	/** True only while this exact sequence position is still eligible for a new click. */
	public boolean canClick(int slotId) {
		ExperimentCell current = remembered.get(nextSlot);
		return state == State.SHOW && slotId == nextSlot && current != null
			&& clicksThisRound < remembered.size() && !roundFinished();
	}

	/** True when a live pane edge happened before any click in the remembered solve. */
	private boolean canReopenUnplayedRound() {
		return state == State.END && reopenUnplayedRoundOnTimer && !remembered.isEmpty()
			&& clicksThisRound == 0;
	}

	private boolean midSolve() {
		return state == State.SHOW && !remembered.isEmpty() && clicksThisRound < remembered.size();
	}

	private boolean roundFinished() {
		return state == State.SHOW && !remembered.isEmpty() && clicksThisRound >= remembered.size();
	}

	private void clearRound() {
		remembered.clear();
		nextSlot = -1;
		clicksThisRound = 0;
		countedThisRound = false;
		reopenUnplayedRoundOnTimer = false;
	}

	private void beginNextRound() {
		if (roundFinished() && !countedThisRound) {
			countedThisRound = true;
			completedRounds++;
		}
		clearRound();
		state = State.REMEMBER;
	}
	public List<SequenceStep> sequence() {
		List<SequenceStep> result = new ArrayList<>();
		remembered.values().stream().sorted(java.util.Comparator.comparingInt(ExperimentCell::numericValue))
			.forEach(cell -> result.add(new SequenceStep(result.size(), cell.value(), List.of(cell.slotId()))));
		return List.copyOf(result);
	}
	public Map<Integer, Integer> slotsByNumber() {
		Map<Integer, Integer> result = new LinkedHashMap<>();
		remembered.values().forEach(cell -> result.put(cell.numericValue(), cell.slotId()));
		return Collections.unmodifiableMap(result);
	}
	public State state() { return state; }
	public int completedRounds() { return completedRounds; }
	public int acceptedClicks() { return clicksThisRound; }
	public int currentIndex() {
		List<SequenceStep> steps = sequence();
		for (int i = 0; i < steps.size(); i++) if (steps.get(i).slotIds().contains(nextSlot)) return i;
		return steps.size();
	}
	public ExperimentPhase phase() {
		return switch (state) {
			case REMEMBER -> ExperimentPhase.MEMORIZE;
			case WAIT -> ExperimentPhase.WAITING;
			case SHOW -> ExperimentPhase.SOLVE;
			case END -> ExperimentPhase.ROUND_COMPLETE;
		};
	}
	public void reset() {
		clearRound();
		state = State.REMEMBER;
		lastPaneColor = null;
		completedRounds = 0;
	}
}
