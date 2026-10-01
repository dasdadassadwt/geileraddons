package geiler.addons.client.dungeon;

/** Bounded shutdown drain for versioned asynchronous guide snapshots. */
final class DungeonGuideFlush {
	private static final int MAX_SNAPSHOT_WRITES = 2;

	private DungeonGuideFlush() { }

	interface Writer {
		/** Waits for the current write, returning false when it could not be resolved. */
		boolean awaitPending();
		boolean dirty();
		boolean persistenceBlocked();
		boolean hasPending();
		void writeSnapshot();
	}

	static void drain(Writer writer) {
		for (int write = 0; write < MAX_SNAPSHOT_WRITES; write++) {
			if (!writer.awaitPending()) return;
			if (writer.persistenceBlocked() || !writer.dirty()) return;
			if (writer.hasPending()) return;
			writer.writeSnapshot();
		}
		writer.awaitPending();
	}
}
