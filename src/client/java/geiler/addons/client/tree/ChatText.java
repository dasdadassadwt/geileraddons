package geiler.addons.client.tree;

/** Chat text cleanup, shared by everything that has to recognise a Hypixel line. */
public final class ChatText {
	/** A number rather than a literal - this file is about characters that break when re-encoded. */
	private static final char SECTION_SIGN = 0x00a7;

	private ChatText() {
	}

	/**
	 * Drops legacy colour codes and flattens every kind of space to an ordinary one.
	 *
	 * <p>Both halves matter. Hypixel sends these lines with section-sign codes embedded rather
	 * than as component styling, and one of them can sit in the middle of a phrase. The padding it
	 * uses to centre a block is not always U+0020 either, and a non-breaking space is invisible in
	 * a log yet fatal to an exact match - {@code \s} in a regex would not have caught it, since
	 * that only covers ASCII whitespace.
	 */
	public static String plain(String message) {
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
}
