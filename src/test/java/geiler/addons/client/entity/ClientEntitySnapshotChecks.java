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
