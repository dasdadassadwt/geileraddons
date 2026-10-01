package geiler.addons.client.gui;

/** Focused checks for the editor's bounded undo/redo branch behavior. */
public final class BoundedUndoHistoryChecks {
	private BoundedUndoHistoryChecks() { }

	public static void run() {
		BoundedUndoHistory<String> history = new BoundedUndoHistory<>(50, "0");
		for (int i = 1; i <= 60; i++) history.record(Integer.toString(i));
		check(history.undoSize() == 50, "history is bounded to its newest fifty prior states");
		String restored = null;
		for (int i = 0; i < 50; i++) restored = history.undo();
		check("10".equals(restored) && !history.canUndo(), "oldest states beyond the limit are discarded");
		check(history.canRedo() && "11".equals(history.redo()), "redo returns to the next state");
		check(history.record("branch") && !history.canRedo(), "a new edit clears the redo branch");
		check(!history.record("branch"), "unchanged snapshots do not create history entries");
		check("11".equals(history.undo()), "undo returns to the prior snapshot on the new branch");
	}

	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}
}
