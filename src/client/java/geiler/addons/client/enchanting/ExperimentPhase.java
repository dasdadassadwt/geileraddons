package geiler.addons.client.enchanting;

import java.util.Locale;

/** Server-visible phase of an experimentation add-on. */
public enum ExperimentPhase {
	IDLE,
	MEMORIZE,
	WAITING,
	SOLVE,
	ROUND_COMPLETE,
	COMPLETE;

	/** Exposes the same status normalization to the Minecraft/container adapter. */
	public static ExperimentPhase detect(ExperimentType type, String status) {
		if (type == null) return IDLE;
		String normalized = normalizeStatus(status);
		if (type == ExperimentType.SUPERPAIRS) {
			return isTerminalStatusNormalized(normalized) ? COMPLETE : SOLVE;
		}
		if (normalized.isEmpty()) return WAITING;
		// Check the specific round boundary before the broader "complete" terminal wording.
		if (isRoundCompleteStatusNormalized(normalized)) return ROUND_COMPLETE;
		if (isTerminalStatusNormalized(normalized)) return COMPLETE;
		if (isRememberStatusNormalized(normalized)) return MEMORIZE;
		if (isSolveStatusNormalized(normalized)) return SOLVE;
		return WAITING;
	}

	/** Returns a formatting-free, case-insensitive status suitable for matching. */
	public static String normalizeStatus(String value) {
		if (value == null) return "";
		StringBuilder result = new StringBuilder(value.length());
		boolean formatting = false;
		for (int i = 0; i < value.length(); i++) {
			char character = value.charAt(i);
			if (formatting) {
				formatting = false;
				continue;
			}
			if (character == '\u00A7') {
				formatting = true;
				continue;
			}
			if (!Character.isISOControl(character)) result.append(character);
		}
		return result.toString().trim().toLowerCase(Locale.ROOT);
	}

	/** Whether the status contains enough information to be trusted by the solver. */
	public static boolean isKnownStatus(ExperimentType type, String status) {
		String normalized = normalizeStatus(status);
		if (type == ExperimentType.SUPERPAIRS) return true;
		if (normalized.isEmpty()) return false;
		return isRoundCompleteStatusNormalized(normalized) || isTerminalStatusNormalized(normalized)
			|| isRememberStatusNormalized(normalized) || isSolveStatusNormalized(normalized);
	}

	public static boolean isTimerStatus(String status) {
		return isTimerStatusNormalized(normalizeStatus(status));
	}

	public static boolean isRememberStatus(String status) {
		return isRememberStatusNormalized(normalizeStatus(status));
	}

	public static boolean isRoundCompleteStatus(String status) {
		return isRoundCompleteStatusNormalized(normalizeStatus(status));
	}

	public static boolean isTerminalStatus(String status) {
		return isTerminalStatusNormalized(normalizeStatus(status));
	}

	private static boolean isTimerStatusNormalized(String normalized) {
		return normalized.startsWith("timer:");
	}

	private static boolean isRememberStatusNormalized(String normalized) {
		return normalized.startsWith("remember the pattern") || normalized.contains("watch the pattern")
			|| normalized.contains("memorize");
	}

	private static boolean isRoundCompleteStatusNormalized(String normalized) {
		// Hypixel's Chronomatron state machine exposes the round boundary through its explicit
		// label. Feedback such as "Correct!" can appear after an individual click and must not hide
		// the remaining sequence.
		return normalized.startsWith("round complete");
	}

	private static boolean isTerminalStatusNormalized(String normalized) {
		if (isRoundCompleteStatusNormalized(normalized)) return false;
		return containsAny(normalized, "game over", "max clicks", "complete", "finished", "claim",
			"mistake", "wrong", "failed", "failure");
	}

	private static boolean isSolveStatusNormalized(String normalized) {
		return isTimerStatusNormalized(normalized) || normalized.contains("click a second")
			|| normalized.contains("click the") || normalized.contains("select the")
			|| normalized.contains("next button") || normalized.contains("click a button");
	}

	private static boolean containsAny(String value, String... needles) {
		for (String needle : needles) if (value.contains(needle)) return true;
		return false;
	}
}
