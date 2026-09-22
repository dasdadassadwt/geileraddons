package geiler.addons.client.enchanting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tick-driven number memory and mutation-driven round boundaries, following Skyblocker.
 *
 * <p>A pane colour change only closes a round once that round's clicks are complete; while the
 * solve still owes clicks it is the board's own button animation. The next "Remember the pattern!"
 * is the authoritative round reset either way, and it is also the recovery path when a solve is
 * interrupted before this model observes the boundary.</p>
 */
public final class UltrasequencerModel {
	public enum State { REMEMBER, WAIT, SHOW, END }
	private final Map<Integer, ExperimentCell> remembered = new LinkedHashMap<>();
	private State state = State.REMEMBER;
	private int nextSlot = -1;
	private String lastPaneColor;
	private int completedRounds;
	/**
	 * Accepted clicks of the round in progress. {@link #nextSlot} deliberately keeps pointing at the
	 * last button after the final click, so it cannot tell "still owed clicks" apart from "waiting for
	 * the pane edge"; this counter can.
	 */
	private int clicksThisRound;
	/** Whether the current round has already been added to {@link #completedRounds}. */
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
				if (!instruction.equals("Remember the pattern!")) return;
				capture(cells);
			}
			case WAIT -> {
				if (instruction.startsWith("Timer: ")) state = State.SHOW;
			}
			case END -> {
				if (instruction.startsWith("Timer: ")) return;
				if (instruction.equals("Remember the pattern!")) {
					beginNextRound();
					capture(cells);
				} else reset();
			}
			case SHOW -> {
				// The server can start the next round before this model ever observed the pane
				// boundary. A fresh memory notice is the one authoritative round reset, and it also
				// prevents a finished round from ever being replayed.
				if (instruction.equals("Remember the pattern!")) {
					beginNextRound();
					capture(cells);
				}
			}
		}
	}

	private void capture(List<ExperimentCell> cells) {
		clicksThisRound = 0;
		boolean captured = false;
		for (ExperimentCell cell : cells) {
			if (cell.removed() || !cell.hasValue() || !cell.value().matches("\\d+")) continue;
			remembered.put(cell.slotId(), cell);
			if (cell.value().equals("1")) nextSlot = cell.slotId();
			captured = true;
		}
		// A status tick that carries no numbers yet must not close the capture window, or the round's
		// real board would arrive while the model is already waiting for its timer.
		state = captured ? State.WAIT : State.REMEMBER;
	}

	/**
	 * Inspect panes in slot order on actual menu mutations, including during memory.
	 *
	 * <p>The round counter moves here, exactly once per round, because this is the only boundary
	 * signal that never depends on a later status arriving.</p>
	 */
	public void markDirty(List<String> paneColors) {
		for (String color : paneColors) {
			if (color == null || color.equals("black")) continue;
			if (!color.equals(lastPaneColor)) {
				boolean completed = roundFinished();
				lastPaneColor = color;
				if (completed && !countedThisRound) {
					countedThisRound = true;
					completedRounds++;
				}
				// The round's first colour is not a boundary, and a colour change while the solve
				// still owes clicks is the board's own button animation. Ending the round on either
				// would drop the remaining clicks while the solver keeps rendering them.
				if (!midSolve()) state = State.END;
				return;
			}
		}
	}

	/** True while the solve still owes clicks, so a pane repaint must not close its round. */
	private boolean midSolve() {
		return state == State.SHOW && nextSlot >= 0 && !remembered.isEmpty()
			&& clicksThisRound < remembered.size();
	}

	/** Forgets the round in progress so the next capture starts from an empty board. */
	private void clearRound() {
		remembered.clear();
		nextSlot = -1;
		clicksThisRound = 0;
		countedThisRound = false;
	}

	/** True once the round's whole sequence has been clicked and only the pane edge is left. */
	private boolean roundFinished() {
		return state == State.SHOW && !remembered.isEmpty() && clicksThisRound >= remembered.size();
	}

	/**
	 * Starts the next round. A round is normally counted at its pane boundary; this is the fallback
	 * for a boundary the model never got to see, such as a menu that ends without a colour update.
	 */
	private void beginNextRound() {
		if (state == State.SHOW && roundFinished() && !countedThisRound) {
			countedThisRound = true;
			completedRounds++;
		}
		clearRound();
		state = State.REMEMBER;
	}

	public boolean click(int slotId) {
		// The counter also hard-stops a stale board that the game already replaced: without it a
		// missed pane boundary would keep replaying the previous round forever.
		if (state != State.SHOW || slotId != nextSlot || roundFinished()) return false;
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
