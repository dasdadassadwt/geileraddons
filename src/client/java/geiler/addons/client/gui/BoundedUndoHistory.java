package geiler.addons.client.gui;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/** Small state history for screen-local undoable edits. */
final class BoundedUndoHistory<T> {
	private final int capacity;
	private final Deque<T> undo = new ArrayDeque<>();
	private final Deque<T> redo = new ArrayDeque<>();
	private T current;

	BoundedUndoHistory(int capacity, T initial) {
		if (capacity < 1) throw new IllegalArgumentException("History capacity must be positive");
		this.capacity = capacity;
		this.current = Objects.requireNonNull(initial, "initial");
	}

	boolean canUndo() {
		return !undo.isEmpty();
	}

	boolean canRedo() {
		return !redo.isEmpty();
	}

	int undoSize() {
		return undo.size();
	}

	/** Records a committed state, ignoring no-op snapshots and invalidating the redo branch. */
	boolean record(T next) {
		Objects.requireNonNull(next, "next");
		if (current.equals(next)) return false;
		push(undo, current);
		current = next;
		redo.clear();
		return true;
	}

	T undo() {
		if (undo.isEmpty()) return null;
		push(redo, current);
		current = undo.removeLast();
		return current;
	}

	T redo() {
		if (redo.isEmpty()) return null;
		push(undo, current);
		current = redo.removeLast();
		return current;
	}

	/** Reconciles the tracked state after restoring a snapshot without creating a history entry. */
	void resetCurrent(T restored) {
		current = Objects.requireNonNull(restored, "restored");
	}

	private void push(Deque<T> stack, T value) {
		stack.addLast(value);
		while (stack.size() > capacity) stack.removeFirst();
	}
}
