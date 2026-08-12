package geiler.addons.client.tree;

import java.util.regex.Pattern;

/** The three gift-dropping trees, and how each one's gift line is recognised in chat. */
public enum TreeType {
	HELIX("Helix"),
	FIG("Fig"),
	MANGROVE("Mangrove");

	private final String displayName;
	private final Pattern cutLine;
	private final Pattern giftLine;

	TreeType(String displayName) {
		this.displayName = displayName;
		String name = Pattern.quote(displayName);
		// Spacing is deliberately loose throughout: the separator between the name and "Tree" is
		// not reliably an ordinary space, which is also why the summary reads as "FigTree Gift."
		// once it has been through a copy-paste.
		this.cutLine = Pattern.compile("cut\\s.*\\sof\\s+the\\s+" + name + "\\s*Tree", Pattern.CASE_INSENSITIVE);
		this.giftLine = Pattern.compile(name + "\\s*Tree\\s*Gift", Pattern.CASE_INSENSITIVE);
	}

	public String displayName() {
		return displayName;
	}

	/**
	 * The tree this message names as having just given a gift, or null.
	 *
	 * <p>Two shapes are accepted because it isn't knowable from outside the game which of them the
	 * mod actually receives. The "cut ...% of the X Tree" line is the one confirmed to arrive as
	 * chat; the "X Tree Gift." summary was only ever seen in a log, with its colour codes still
	 * raw, which is what hover text looks like rather than a message. Matching both and letting
	 * {@link TreeTracker} discard the repeat is safer than betting on either.
	 *
	 * <p>Two things about the cut line, both confirmed in game and neither guessable from the text:
	 * the share is whatever you personally contributed and is usually not 100, so nothing may key
	 * off the number; and only your own line is ever sent, never another player's, so there is no
	 * need to anchor on "You" - doing so would only add a way for a reworded message to stop
	 * matching. Every gift block carries this line, so it is enough on its own.
	 */
	public static TreeType fromMessage(String message) {
		String plain = normalize(message);
		for (TreeType type : VALUES) {
			if (type.cutLine.matcher(plain).find() || type.giftLine.matcher(plain).find()) return type;
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
