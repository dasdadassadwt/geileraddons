package geiler.addons.client.dungeon;

import java.util.concurrent.atomic.AtomicBoolean;

/** Bounds outstanding profile work and spaces the start of successive player lookups. */
public final class DungeonRequestLimiter {
	private final int maximumOutstanding;
	private final long minimumStartIntervalNanos;
	private int outstanding;
	private long nextStartNanos = Long.MIN_VALUE;

	public DungeonRequestLimiter(int maximumOutstanding, long minimumStartIntervalNanos) {
		if (maximumOutstanding < 1) throw new IllegalArgumentException("maximumOutstanding must be positive");
		if (minimumStartIntervalNanos < 0) throw new IllegalArgumentException("minimumStartIntervalNanos cannot be negative");
		this.maximumOutstanding = maximumOutstanding;
		this.minimumStartIntervalNanos = minimumStartIntervalNanos;
	}

	public synchronized Ticket tryAcquire() {
		if (outstanding >= maximumOutstanding) return null;
		outstanding++;
		return new Ticket(this);
	}

	/** Reserves a paced start slot and returns how long the caller should wait. */
	public synchronized long reserveStartDelayNanos(long nowNanos) {
		long reservedStart = nextStartNanos == Long.MIN_VALUE ? nowNanos : Math.max(nowNanos, nextStartNanos);
		nextStartNanos = reservedStart + minimumStartIntervalNanos;
		return reservedStart - nowNanos;
	}

	public synchronized int outstanding() {
		return outstanding;
	}

	private synchronized void release() {
		if (outstanding > 0) outstanding--;
	}

	public static final class Ticket implements AutoCloseable {
		private final DungeonRequestLimiter owner;
		private final AtomicBoolean closed = new AtomicBoolean();

		private Ticket(DungeonRequestLimiter owner) {
			this.owner = owner;
		}

		@Override
		public void close() {
			if (closed.compareAndSet(false, true)) owner.release();
		}
	}
}
