package geiler.addons.client.module.impl;

/** Offline checks for later-join callback lifecycle ownership. */
public final class PartyFinderLifecycleChecks {
	private PartyFinderLifecycleChecks() {
	}

	public static void run() {
		assertSame(PartyFinderStatsModule.LaterJoinDisposition.IGNORE,
			PartyFinderStatsModule.laterJoinDisposition(0, 1, false, false),
			"a callback after disable is ignored when no consumer remains");
		assertSame(PartyFinderStatsModule.LaterJoinDisposition.IGNORE,
			PartyFinderStatsModule.laterJoinDisposition(0, 1, false, false),
			"a failed callback after disable is also ignored");
		assertSame(PartyFinderStatsModule.LaterJoinDisposition.IGNORE,
			PartyFinderStatsModule.laterJoinDisposition(0, 1, true, false),
			"re-enabling does not revive a callback from an older display epoch");
		assertSame(PartyFinderStatsModule.LaterJoinDisposition.AUTO_KICK_ONLY,
			PartyFinderStatsModule.laterJoinDisposition(0, 1, false, true),
			"Auto Kick can still consume a stale callback without display output");
		assertSame(PartyFinderStatsModule.LaterJoinDisposition.DISPLAY,
			PartyFinderStatsModule.laterJoinDisposition(1, 1, true, false),
			"a current display callback remains eligible for normal stats output");
	}

	private static void assertSame(Object expected, Object actual, String message) {
		if (expected != actual) {
			throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
		}
	}
}
