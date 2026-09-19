package geiler.addons.client.module.impl;

/** Pure requirement predicates kept separate so their boundary behavior can be checked offline. */
final class AutoKickRules {
	private AutoKickRules() {
	}

	/** A configured PB is an upper bound; zero disables the check. */
	static boolean personalBestPasses(long fastestSeconds, long limitSeconds) {
		return limitSeconds <= 0 || fastestSeconds > 0 && fastestSeconds <= limitSeconds;
	}

	/** Parses a PB limit as {@code m:ss} or as legacy whole seconds. Invalid values disable the check. */
	static int parsePersonalBestLimitSeconds(String value) {
		if (value == null || value.isBlank()) return 0;
		String normalized = value.trim();
		try {
			int colon = normalized.indexOf(':');
			if (colon < 0) return Math.max(0, Integer.parseInt(normalized));
			if (colon != normalized.lastIndexOf(':')) return 0;
			long minutes = Long.parseLong(normalized.substring(0, colon).trim());
			int seconds = Integer.parseInt(normalized.substring(colon + 1).trim());
			if (minutes < 0 || minutes > Integer.MAX_VALUE / 60L || seconds < 0 || seconds >= 60) return 0;
			long total = minutes * 60L + seconds;
			return total > Integer.MAX_VALUE ? 0 : (int) total;
		} catch (NumberFormatException ignored) {
			return 0;
		}
	}
}
