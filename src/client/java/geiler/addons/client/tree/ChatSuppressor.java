package geiler.addons.client.tree;

import java.util.regex.Pattern;

/**
 * Decides which tree chat lines to hide. Pure logic, so it can be exercised offline.
 *
 * <p>A gift is a block of lines, not one line, and it opens with a separator that arrives before
 * anything identifies it. Rather than let that stray bar sit in chat, a separator is <em>held</em>
 * instead of shown: the next line settles whether it opened a gift block, in which case it is
 * dropped along with the rest, or belonged to something else, in which case it is released
 * unchanged. Nothing is held longer than a tick - see {@link #endOfTick()}.
 */
public final class ChatSuppressor {
	/** What to do with the line just handed in. */
	public enum Action { SHOW, HIDE, HOLD }

	/**
	 * @param emitHeld whether the previously held line should be shown before this one; every call
	 *                 resolves the held line one way or the other, so the caller can always clear it
	 * @param action   what to do with the current line
	 */
	public record Decision(boolean emitHeld, Action action) {
	}

	/** U+25AC, the bar Hypixel rules its message blocks with. Numeric so this file stays ASCII. */
	private static final char BAR = 0x25ac;
	/** Short runs of bars turn up inside ordinary messages; a rule is a line of nothing else. */
	private static final int MIN_RULE_LENGTH = 8;
	private static final Pattern HEADER = Pattern.compile("^TREE\\s+GIFT\\b", Pattern.CASE_INSENSITIVE);
	/**
	 * A block that never gets its closing separator must not swallow the rest of the session's
	 * chat. The real one runs to about seven lines.
	 */
	private static final int MAX_BLOCK_LINES = 12;

	private boolean inBlock;
	private boolean holding;
	private int blockLines;

	/** @return what to do, given which of the two hide settings are on */
	public Decision accept(String message, boolean hideGift, boolean hideFelled) {
		boolean emitHeld = holding;
		holding = false;
		String plain = ChatText.plain(message).trim();

		if (inBlock) {
			// Nothing can be held inside a block, so the closing separator needs no special case.
			if (isRule(plain) || ++blockLines >= MAX_BLOCK_LINES) {
				inBlock = false;
			}
			return new Decision(emitHeld, Action.HIDE);
		}

		if (hideGift && HEADER.matcher(plain).find()) {
			inBlock = true;
			blockLines = 0;
			// Whatever was held is this block's opening bar, so it goes with the rest.
			return new Decision(false, Action.HIDE);
		}
		if (hideFelled && TreeFelled.matches(message)) {
			return new Decision(emitHeld, Action.HIDE);
		}
		if (hideGift && isRule(plain)) {
			holding = true;
			return new Decision(emitHeld, Action.HOLD);
		}
		return new Decision(emitHeld, Action.SHOW);
	}

	/**
	 * Closes out anything still pending at the end of a client tick.
	 *
	 * @return true if a held line is still waiting and must now be shown
	 */
	public boolean endOfTick() {
		boolean pending = holding;
		holding = false;
		// A block always arrives inside one tick, so anything still open never got its closing
		// bar. Ending it here means a missed separator costs a few visible lines, not every line
		// from now on.
		inBlock = false;
		return pending;
	}

	/** True while a line is being withheld, so the caller knows to keep hold of it. */
	public boolean holding() {
		return holding;
	}

	private static boolean isRule(String plain) {
		if (plain.length() < MIN_RULE_LENGTH) return false;
		for (int i = 0; i < plain.length(); i++) {
			if (plain.charAt(i) != BAR) return false;
		}
		return true;
	}
}
