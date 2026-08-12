package geiler.addons.client.tree;

import java.util.EnumMap;
import java.util.Map;

/**
 * Gift counts and rates for all three tree types. No Minecraft dependency, so it can be exercised
 * offline.
 *
 * <p>A session is not tied to the game running. It is saved as it goes and folded into the totals
 * when it ends, which happens either because the user reset it or because the game was restarted -
 * so quitting mid-session still credits those gifts to the all-time numbers instead of losing them.
 */
public final class TreeTracker {
	/** How long after a counted gift further mentions of the same tree are treated as the same event. */
	private static final long DUPLICATE_WINDOW_MILLIS = 1000;

	private final Map<TreeType, TreeStats> stats = new EnumMap<>(TreeType.class);
	/** Whichever tree gifted last; the HUD follows it rather than asking the user to pick. */
	private TreeType lastGifted;

	public TreeTracker() {
		for (TreeType type : TreeType.values()) {
			stats.put(type, new TreeStats());
		}
	}

	public TreeStats stats(TreeType type) {
		return stats.get(type);
	}

	public TreeType lastGifted() {
		return lastGifted;
	}

	/**
	 * @return the type that was credited, or null if the message wasn't a gift or was a repeat
	 */
	public TreeType onChatMessage(String message, long now, long timeoutMillis) {
		TreeType type = TreeType.fromMessage(message);
		if (type == null) return null;
		TreeStats stats = this.stats.get(type);
		// One gift announces itself over several lines, all in the same tick, and more than one of
		// them names the tree. Anything this soon after the last gift is that same block still
		// arriving - a second real gift cannot land within a second of the first, since a tree has
		// to be cut down in between.
		if (stats.countedWithin(now, DUPLICATE_WINDOW_MILLIS)) return null;
		stats.onGift(now, timeoutMillis);
		lastGifted = type;
		return type;
	}

	/** Ends the current session for every type at once and starts a new one. */
	public void resetSession(long now, long timeoutMillis) {
		for (TreeStats value : stats.values()) {
			value.rollOver(now, timeoutMillis);
		}
	}

	/**
	 * Closes out whatever session was loaded from disk.
	 *
	 * <p>Called once after restoring, so the previous run's gifts land in the totals and this run
	 * starts from zero. Passing 0 for the elapsed gap is deliberate - the time between quitting and
	 * launching again was never farming time.
	 */
	public void rollOverLoadedSession() {
		for (TreeStats value : stats.values()) {
			value.rollOver(0, 0);
		}
	}

	public boolean hasAnyData() {
		for (TreeStats value : stats.values()) {
			if (value.hasData()) return true;
		}
		return false;
	}
}
