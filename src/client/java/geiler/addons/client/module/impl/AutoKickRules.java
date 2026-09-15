package geiler.addons.client.module.impl;

/** Pure requirement predicates kept separate so their boundary behavior can be checked offline. */
final class AutoKickRules {
	private AutoKickRules() {
	}

	/** A configured PB is an upper bound; zero disables the check. */
	static boolean personalBestPasses(long fastestSeconds, long limitSeconds) {
		return limitSeconds <= 0 || fastestSeconds > 0 && fastestSeconds <= limitSeconds;
	}
}
