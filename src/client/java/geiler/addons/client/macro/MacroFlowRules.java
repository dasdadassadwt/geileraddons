package geiler.addons.client.macro;

import java.util.Locale;
import java.util.Arrays;
import java.util.random.RandomGenerator;

/** Small pure rules shared by the client runner and its offline checks. */
final class MacroFlowRules {
	enum UntilDecision { EXIT, RUN_BODY, EMPTY_BODY }

	private MacroFlowRules() {
	}

	static UntilDecision repeatUntil(boolean stopConditionTrue, boolean hasBody) {
		if (stopConditionTrue) return UntilDecision.EXIT;
		return hasBody ? UntilDecision.RUN_BODY : UntilDecision.EMPTY_BODY;
	}

	static boolean missingItemEndsUntil(boolean clickItemStep, boolean stopConditionTrue) {
		return clickItemStep && stopConditionTrue;
	}

	static boolean contextEnded(boolean systemEnabled, boolean hasLevel, boolean hasPlayer,
		boolean waitingForWorldSwitch) {
		return !systemEnabled || !hasLevel || (!hasPlayer && !waitingForWorldSwitch);
	}

	static boolean itemNameMatches(String actual, String wanted, boolean partial) {
		String name = normalize(actual);
		String needle = normalize(wanted);
		if (name.isEmpty() || needle.isEmpty()) return false;
		return partial ? name.contains(needle) : name.equals(needle);
	}

	/** Comma-separated names are alternatives; an item matches when any non-empty name matches. */
	static boolean itemNameMatchesAny(String actual, String wanted, boolean partial) {
		if (wanted == null || wanted.isBlank()) return false;
		return Arrays.stream(wanted.split(",", -1))
			.map(MacroFlowRules::normalize)
			.filter(name -> !name.isEmpty())
			.anyMatch(name -> itemNameMatches(actual, name, partial));
	}

	static int randomDelay(int minMillis, int maxMillis, RandomGenerator random) {
		int min = Math.max(0, minMillis);
		int max = Math.max(min, maxMillis);
		return min == max ? min : random.nextInt(min, max + 1);
	}

	private static String normalize(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}
}
