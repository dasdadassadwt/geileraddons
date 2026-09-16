package geiler.addons.client.enchanting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tick-driven number memory and mutation-driven round boundaries, following Skyblocker. */
public final class UltrasequencerModel {
	public enum State { REMEMBER, WAIT, SHOW, END }
	private final Map<Integer, ExperimentCell> remembered = new LinkedHashMap<>();
	private State state = State.REMEMBER;
	private int nextSlot = -1;
	private String lastPaneColor;
	private int completedRounds;

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
				for (ExperimentCell cell : cells) {
					if (cell.removed() || !cell.hasValue() || !cell.value().matches("\\d+")) continue;
					remembered.put(cell.slotId(), cell);
					if (cell.value().equals("1")) nextSlot = cell.slotId();
				}
				state = State.WAIT;
			}
			case WAIT -> {
				if (instruction.startsWith("Timer: ")) state = State.SHOW;
			}
			case END -> {
				if (instruction.startsWith("Timer: ")) return;
				if (instruction.equals("Remember the pattern!")) {
					completedRounds++;
					remembered.clear();
					nextSlot = -1;
					state = State.REMEMBER;
				} else reset();
			}
			case SHOW -> { }
		}
	}

	/** Inspect panes in slot order on actual menu mutations, including during memory. */
	public void markDirty(List<String> paneColors) {
		for (String color : paneColors) {
			if (color == null || color.equals("black")) continue;
			if (!color.equals(lastPaneColor)) {
				if (lastPaneColor != null) state = State.END;
				lastPaneColor = color;
				return;
			}
		}
	}

	public boolean click(int slotId) {
		if (state != State.SHOW || slotId != nextSlot) return false;
		ExperimentCell current = remembered.get(nextSlot);
		if (current == null) return false;
		int wantedCount = current.numericValue() + 1;
		for (ExperimentCell cell : remembered.values()) {
			if (cell.numericValue() == wantedCount) {
				nextSlot = cell.slotId();
				break;
			}
		}
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
		remembered.clear();
		state = State.REMEMBER;
		nextSlot = -1;
		lastPaneColor = null;
		completedRounds = 0;
	}
}
