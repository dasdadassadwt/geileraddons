package geiler.addons.client.enchanting;

import java.util.Locale;
import java.util.Optional;

/** The three add-on games shown by the Experimentation Table. */
public enum ExperimentType {
	CHRONOMATRON("Chronomatron"),
	ULTRASEQUENCER("Ultrasequencer"),
	SUPERPAIRS("Superpairs");

	private final String displayName;

	ExperimentType(String displayName) {
		this.displayName = displayName;
	}

	public String displayName() {
		return displayName;
	}

	/** Matches a complete experiment title and fails closed for unrelated menus. */
	public static Optional<ExperimentType> fromTitle(String title) {
		String normalized = normalize(title);
		for (ExperimentType type : values()) {
			String prefix = type.displayName.toLowerCase(Locale.ROOT);
			if (normalized.equals(prefix)) return Optional.of(type);
			if (!normalized.startsWith(prefix + " (") || !normalized.endsWith(")")) continue;
			String tier = normalized.substring(prefix.length() + 2, normalized.length() - 1).trim();
			if (!tier.isEmpty() && ExperimentTier.fromName(tier).isPresent()) return Optional.of(type);
		}
		return Optional.empty();
	}

	private static String normalize(String value) {
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
			result.append(character);
		}
		return result.toString().trim().toLowerCase(Locale.ROOT);
	}
}
