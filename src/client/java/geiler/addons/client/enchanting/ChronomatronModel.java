package geiler.addons.client.enchanting;

import java.util.ArrayList;
import java.util.List;

/**
 * Ordered-item Chronomatron state model, independent of slot positions and rendering.
 *
 * <p>The model deliberately follows Skyblocker's four-state lifecycle: memory reveals append one
 * normalized item identity, replayed reveals only advance a chain counter, the timer opens the
 * solve state, and completing the remembered sequence closes the round. Slot ids are used only to
 * detect the end of a reveal and are never stored as solution truth.</p>
 */
public final class ChronomatronModel {
	public enum State {
		REMEMBER,
		WAIT,
		SHOW,
		END
	}

	private final List<String> items = new ArrayList<>();
	private State state = State.REMEMBER;
	private int chainLengthCount;
	private int currentRevealSlot;
	private int currentOrdinal;
	private int completedRounds;

	/** Applies one container event in the exact order received from the menu listener. */
	public void observe(ChronomatronEvent event) {
		if (event == null) return;
		switch (state) {
			case REMEMBER -> observeRemember(event);
			case WAIT -> observeWait(event);
			case SHOW -> {
				// Solve-phase glints include the player's own clicks and are never memory evidence.
			}
			case END -> observeEnd(event);
		}
	}

	private void observeRemember(ChronomatronEvent event) {
		if (event.kind() != ChronomatronEvent.Kind.BOARD) return;
		if (currentRevealSlot == 0) {
			if (!event.highlighted() || event.value() == null) return;
			if (items.size() <= chainLengthCount) {
				items.add(event.value());
				state = State.WAIT;
			} else {
				chainLengthCount++;
			}
			currentRevealSlot = event.slotId();
		} else if (currentRevealSlot == event.slotId() && !event.highlighted()) {
			currentRevealSlot = 0;
		}
	}

	private void observeWait(ChronomatronEvent event) {
		if (event.kind() == ChronomatronEvent.Kind.STATUS && event.status().startsWith("Timer: ")) {
			state = State.SHOW;
		}
	}

	private void observeEnd(ChronomatronEvent event) {
		if (event.kind() != ChronomatronEvent.Kind.STATUS) return;
		if (event.status().startsWith("Timer: ")) return;
		if (event.status().equals("Remember the pattern!")) startReplay();
		else reset();
	}

	public boolean matches(int ordinal, String value) {
		return state == State.SHOW && ordinal >= 0 && ordinal < items.size()
			&& value != null && items.get(ordinal).equals(value);
	}

	/** Advances the same item ordinal that the live click guard just validated. */
	public boolean click(String value) {
		if (!matches(currentOrdinal, value)) return false;
		currentOrdinal++;
		if (currentOrdinal >= items.size()) state = State.END;
		return true;
	}

	public List<String> items() {
		return List.copyOf(items);
	}

	public State state() {
		return state;
	}

	public int chainLengthCount() {
		return chainLengthCount;
	}

	public int currentRevealSlot() {
		return currentRevealSlot;
	}

	public int currentOrdinal() {
		return currentOrdinal;
	}

	public int completedRounds() {
		return completedRounds;
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
		items.clear();
		chainLengthCount = 0;
		currentRevealSlot = 0;
		currentOrdinal = 0;
		completedRounds = 0;
		state = State.REMEMBER;
	}

	private void startReplay() {
		completedRounds++;
		chainLengthCount = 0;
		currentOrdinal = 0;
		// The prior reveal remains latched until its real non-glint update arrives. Clearing it
		// here mistakes the lingering highlight for the first replay pulse.
		state = State.REMEMBER;
	}
}
