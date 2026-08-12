package geiler.addons.client.tree;

/**
 * One tree type's gift counters, for the current session and for every session before it.
 *
 * <p>Active time is not measured by a running clock. It is the sum of the gaps between gifts,
 * with each gap capped at the pause timeout, which means it needs no tick loop, can't drift, and
 * stops by itself when the gifts stop. {@link #activeMillis} extends that with the gap still in
 * progress so the read-out ticks up live between gifts and then simply stops once the timeout is
 * reached - the number never jumps backwards, which a clock that retroactively discounted the
 * idle stretch would do.
 */
public final class TreeStats {
	private int sessionCount;
	private long sessionMillis;
	private int storedCount;
	private long storedMillis;
	/** Timestamp of the last gift this session; meaningless until {@link #started}. Never persisted. */
	private long lastGift;
	/**
	 * Whether {@link #lastGift} holds a real timestamp yet.
	 *
	 * <p>A flag rather than treating 0 as "no gift yet": that sentinel is only safe as long as the
	 * clock never legitimately reads 0, which is true of wall-clock time and false of anything
	 * measuring from its own start.
	 */
	private boolean started;

	public void onGift(long now, long timeoutMillis) {
		if (started) {
			sessionMillis += Math.min(Math.max(0, now - lastGift), timeoutMillis);
		}
		started = true;
		lastGift = now;
		sessionCount++;
	}

	public int sessionCount() {
		return sessionCount;
	}

	public long sessionMillis(long now, long timeoutMillis) {
		return sessionMillis + openGap(now, timeoutMillis);
	}

	/** Session plus every session before it - what the totals view shows. */
	public int totalCount() {
		return storedCount + sessionCount;
	}

	public long totalMillis(long now, long timeoutMillis) {
		return storedMillis + sessionMillis(now, timeoutMillis);
	}

	public boolean paused(long now, long timeoutMillis) {
		return started && now - lastGift >= timeoutMillis;
	}

	public boolean hasData() {
		return sessionCount > 0 || storedCount > 0;
	}

	/** Folds the current session into the stored totals and starts a fresh one. */
	public void rollOver(long now, long timeoutMillis) {
		storedMillis += sessionMillis(now, timeoutMillis);
		storedCount += sessionCount;
		sessionMillis = 0;
		sessionCount = 0;
		started = false;
	}

	/** Gifts per hour over the time given, or -1 when there isn't enough of it to divide by. */
	public static int ratePerHour(int count, long millis) {
		if (millis <= 0) return -1;
		return (int) Math.round(count * 3600000.0 / millis);
	}

	private long openGap(long now, long timeoutMillis) {
		if (!started) return 0;
		return Math.min(Math.max(0, now - lastGift), timeoutMillis);
	}

	// ---- persistence --------------------------------------------------------------------

	public int rawSessionCount() {
		return sessionCount;
	}

	public long rawSessionMillis() {
		return sessionMillis;
	}

	public int rawStoredCount() {
		return storedCount;
	}

	public long rawStoredMillis() {
		return storedMillis;
	}

	public void restore(int sessionCount, long sessionMillis, int storedCount, long storedMillis) {
		this.sessionCount = Math.max(0, sessionCount);
		this.sessionMillis = Math.max(0, sessionMillis);
		this.storedCount = Math.max(0, storedCount);
		this.storedMillis = Math.max(0, storedMillis);
		this.started = false;
	}
}
