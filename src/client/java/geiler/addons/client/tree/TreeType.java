package geiler.addons.client.tree;

import java.util.regex.Pattern;

/** The three gift-dropping trees, and how each one's gift line is recognised in chat. */
public enum TreeType {
	HELIX("Helix"),
	FIG("Fig"),
	MANGROVE("Mangrove");

	private final String displayName;
	private final Pattern giftLine;

	TreeType(String displayName) {
		this.displayName = displayName;
		// Spacing is deliberately loose. The name and "Tree" are known to be separated by
		// something, but not reliably by an ordinary space - which is also why the line reads as
		// "FigTree Gift." once it has been through a copy-paste.
		this.giftLine = Pattern.compile(Pattern.quote(displayName) + "\\s*Tree\\s*Gift", Pattern.CASE_INSENSITIVE);
	}

	public String displayName() {
		return displayName;
	}

	/** The tree whose gift line this message carries, or null. */
	public static TreeType fromMessage(String message) {
		String plain = normalize(message);
		for (TreeType type : VALUES) {
			if (type.giftLine.matcher(plain).find()) return type;
		}
		return null;
	}

	/**
	 * Drops legacy colour codes and flattens every kind of space to an ordinary one.
	 *
	 * <p>Both halves matter. Hypixel sends these lines with section-sign codes embedded rather
	 * than as component styling, and one of them sits right before the tree name. The padding it
	 * uses to centre the block is not always U+0020 either, and a non-breaking space is invisible
	 * in a log yet fatal to an exact match - {@code \s} in a regex would not have caught it, since
	 * that only covers ASCII whitespace.
	 */
	private static String normalize(String message) {
		StringBuilder out = new StringBuilder(message.length());
		for (int i = 0; i < message.length(); i++) {
			char c = message.charAt(i);
			if (c == SECTION_SIGN) {
				i++;
				continue;
			}
			out.append(Character.isSpaceChar(c) || Character.isWhitespace(c) ? ' ' : c);
		}
		return out.toString();
	}

	/** A number rather than a literal - this file is about characters that break when re-encoded. */
	private static final char SECTION_SIGN = 0x00a7;

	/** values() hands out a fresh copy each call, and this runs on every chat line. */
	private static final TreeType[] VALUES = values();
}
