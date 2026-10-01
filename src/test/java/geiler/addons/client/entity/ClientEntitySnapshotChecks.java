package geiler.addons.client.entity;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Offline checks for same-tick entity snapshot reuse and invalidation. */
public final class ClientEntitySnapshotChecks {
	private ClientEntitySnapshotChecks() {
	}

	public static void run() {
		Object level = new Object();
		Object player = new Object();
		ClientEntitySnapshot.Cache<String> cache = new ClientEntitySnapshot.Cache<>();
		AtomicInteger loads = new AtomicInteger();

		ClientEntitySnapshot.Key firstTick = new ClientEntitySnapshot.Key(level, player, 10, 1, 2, 3, 64);
		assertEquals(List.of("first"), cache.get(firstTick, () -> {
			loads.incrementAndGet();
			return List.of("first");
		}), "the first snapshot is loaded");
		assertEquals(List.of("first"), cache.get(firstTick, () -> {
			loads.incrementAndGet();
			return List.of("unexpected");
		}), "the same client tick reuses its snapshot");
		assertEquals(1, loads.get(), "same-tick callers perform one entity query");

		ClientEntitySnapshot.Key nextTick = new ClientEntitySnapshot.Key(level, player, 11, 1, 2, 3, 64);
		cache.get(nextTick, () -> {
			loads.incrementAndGet();
			return List.of("next");
		});
		assertEquals(2, loads.get(), "advancing the game tick invalidates the snapshot");

		ClientEntitySnapshot.Key differentLevel = new ClientEntitySnapshot.Key(new Object(), player, 11, 1, 2, 3, 64);
		cache.get(differentLevel, () -> {
			loads.incrementAndGet();
			return List.of("new level");
		});
		assertEquals(3, loads.get(), "changing levels invalidates the snapshot");

		checkDistinctRanges();
	}

	/**
	 * Two consumers ask for different scan areas in the same tick - the plot borders want 128 blocks,
	 * the pest highlighter wants the render distance capped at 64. With a single cache slot the second
	 * caller always missed and queried every entity again, which is the one thing this cache exists to
	 * prevent, so both requests have to be able to live side by side.
	 */
	private static void checkDistinctRanges() {
		Object level = new Object();
		Object player = new Object();
		ClientEntitySnapshot.Cache<String> cache = new ClientEntitySnapshot.Cache<>(2);
		AtomicInteger loads = new AtomicInteger();

		ClientEntitySnapshot.Key wide = new ClientEntitySnapshot.Key(level, player, 7, 0, 0, 0, 128);
		ClientEntitySnapshot.Key near = new ClientEntitySnapshot.Key(level, player, 7, 0, 0, 0, 64);
		load(cache, wide, loads, "wide");
		load(cache, near, loads, "near");
		assertEquals(2, loads.get(), "two ranges in one tick each perform their own query");

		load(cache, wide, loads, "unexpected");
		load(cache, near, loads, "unexpected");
		assertEquals(2, loads.get(), "both ranges are still cached later in the same tick");

		ClientEntitySnapshot.Key nextTick = new ClientEntitySnapshot.Key(level, player, 8, 0, 0, 0, 128);
		load(cache, nextTick, loads, "next");
		assertEquals(3, loads.get(), "the next tick re-queries rather than reusing either range");
		ClientEntitySnapshot.Key nextTickNear = new ClientEntitySnapshot.Key(level, player, 8, 0, 0, 0, 64);
		load(cache, nextTickNear, loads, "near next tick");
		assertEquals(4, loads.get(), "the next tick re-queries the smaller range too");
		load(cache, nextTick, loads, "unexpected");
		load(cache, nextTickNear, loads, "unexpected");
		assertEquals(4, loads.get(), "both ranges are cached for the rest of the new tick");

		// A moving player also invalidates: the snapshot holds entity positions, not just counts.
		ClientEntitySnapshot.Key moved = new ClientEntitySnapshot.Key(level, player, 8, 5, 0, 0, 64);
		load(cache, moved, loads, "moved");
		assertEquals(5, loads.get(), "moving the player re-queries the same range");

		// The cache is bounded, so it cannot accumulate over a long session.
		ClientEntitySnapshot.Cache<String> small = new ClientEntitySnapshot.Cache<>(1);
		AtomicInteger smallLoads = new AtomicInteger();
		load(small, wide, smallLoads, "wide");
		load(small, near, smallLoads, "near");
		load(small, wide, smallLoads, "wide again");
		assertEquals(3, smallLoads.get(), "a full cache evicts its oldest query instead of growing");
	}

	private static void load(ClientEntitySnapshot.Cache<String> cache, ClientEntitySnapshot.Key key,
		AtomicInteger loads, String value) {
		cache.get(key, () -> {
			loads.incrementAndGet();
			return List.of(value);
		});
	}

	private static void assertEquals(Object expected, Object actual, String message) {
		if (!expected.equals(actual)) {
			throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
		}
	}

	private static void assertEquals(int expected, int actual, String message) {
		if (expected != actual) {
			throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
		}
	}
}
