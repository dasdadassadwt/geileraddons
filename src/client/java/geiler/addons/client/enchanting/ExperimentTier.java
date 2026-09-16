package geiler.addons.client.enchanting;

import java.util.Locale;
import java.util.Optional;

/** Experiment difficulty and the server's bonus-click thresholds. */
public enum ExperimentTier {
	BEGINNER("Beginner", -1, -1, 0),
	UNKNOWN("Unknown", -1, -1, 0),
	HIGH("High", 9, 7, 2),
	GRAND("Grand", 9, 7, 2),
	SUPREME("Supreme", 9, 7, 2),
	TRANSCENDENT("Transcendent", 9, 7, 2),
	METAPHYSICAL("Metaphysical", 12, 9, 3);

	private final String displayName;
	private final int chronomatronThreshold;
	private final int ultrasequencerThreshold;
	private final int maximumExtraClicks;

	ExperimentTier(String displayName, int chronomatronThreshold, int ultrasequencerThreshold,
		int maximumExtraClicks) {
		this.displayName = displayName;
		this.chronomatronThreshold = chronomatronThreshold;
		this.ultrasequencerThreshold = ultrasequencerThreshold;
		this.maximumExtraClicks = maximumExtraClicks;
	}

	public String displayName() {
		return displayName;
	}

	public int chronomatronThreshold() {
		return chronomatronThreshold;
	}

	public int ultrasequencerThreshold() {
		return ultrasequencerThreshold;
	}

	public int maximumExtraClicks() {
		return maximumExtraClicks;
	}

	public static Optional<ExperimentTier> fromTitle(String title) {
		if (title == null) return Optional.empty();
		title = stripFormatting(title);
		int open = title.indexOf('(');
		int close = title.lastIndexOf(')');
		if (open < 0 || close <= open || close != title.length() - 1
			|| title.indexOf('(', open + 1) >= 0 || title.indexOf(')', open + 1) != close) {
			return Optional.empty();
		}
		return fromName(title.substring(open + 1, close));
	}

	public static Optional<ExperimentTier> fromName(String name) {
		String normalized = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
		for (ExperimentTier tier : values()) {
			if (tier != UNKNOWN && tier.displayName.toLowerCase(Locale.ROOT).equals(normalized)) {
				return Optional.of(tier);
			}
		}
		return Optional.empty();
	}

	private static String stripFormatting(String value) {
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
			result.append(character);
		}
		return result.toString();
	}
}
