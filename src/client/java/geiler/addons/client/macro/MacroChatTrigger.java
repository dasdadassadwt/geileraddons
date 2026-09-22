package geiler.addons.client.macro;

import geiler.addons.client.tree.ChatText;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Matching and throttling rules for chat-triggered event stacks.
 *
 * <p>Kept free of client state so the odd parts are checkable without a running game: the same
 * normalization the Chat condition uses, a repeat delay that is measured from the previous start,
 * and the echo window that stops a macro's own chat from retriggering itself when Hypixel sends it
 * back.
 */
public final class MacroChatTrigger {
	/** A stored pattern is bounded so a hand-edited config cannot carry a paragraph into the UI. */
	public static final int MAX_PATTERN_LENGTH = 128;
	/** How long a macro-sent chat line stays recognisable as our own echo. */
	public static final long ECHO_WINDOW_NANOS = 5_000_000_000L;
	/** Newest kept lines; only the tail of a burst can plausibly still be in flight. */
	public static final int MAX_SENT_LINES = 16;

	private MacroChatTrigger() {
	}

	/**
	 * Whether a received line satisfies a pattern.
	 *
	 * <p>Both sides go through the same legacy-format flattening the rest of the mod uses, and the
	 * comparison is case-insensitive. A substring match is the default because Hypixel prefixes a
	 * line with the channel and the speaker: matching the message the player typed has to tolerate
	 * everything in front of it. A blank pattern never matches, so a freshly added stack is inert
	 * rather than firing on every line.
	 */
	public static boolean matches(String pattern, boolean contains, String line) {
		String needle = normalise(pattern);
		if (needle.isEmpty()) return false;
		String haystack = normalise(line);
		if (haystack.isEmpty()) return false;
		return contains ? haystack.contains(needle) : haystack.equals(needle);
	}

	/** Case-folded, format-free text; the single normalization every chat comparison in here shares. */
	public static String normalise(String line) {
		return line == null ? "" : ChatText.stripForMatch(line).toLowerCase(Locale.ROOT);
	}

	/** Whether a stack's minimum repeat delay has elapsed since its previous start. */
	public static boolean cooldownElapsed(long lastStartNanos, long nowNanos, int cooldownMillis) {
		if (lastStartNanos <= 0L) return true;
		return nowNanos - lastStartNanos >= Math.max(0, cooldownMillis) * 1_000_000L;
	}

	/**
	 * Whether a received line is a macro's own chat coming back from the server.
	 *
	 * <p>Party, guild and message chat is echoed to the sender, so a stack triggered by a phrase
	 * that its own workflow sends would otherwise restart forever. The echo arrives with channel
	 * and name prefixes, so a contained match is what identifies it.
	 */
	public static boolean isOwnEcho(String line, List<SentLine> sent, long nowNanos) {
		String haystack = normalise(line);
		if (haystack.isEmpty() || sent == null || sent.isEmpty()) return false;
		for (SentLine entry : sent) {
			if (entry == null || nowNanos - entry.nanos() > ECHO_WINDOW_NANOS) continue;
			String needle = normalise(entry.text());
			if (!needle.isEmpty() && haystack.contains(needle)) return true;
		}
		return false;
	}

	/** Drops entries that are too old to matter and keeps only the newest {@link #MAX_SENT_LINES}. */
	public static List<SentLine> pruneSent(List<SentLine> sent, long nowNanos) {
		if (sent == null || sent.isEmpty()) return List.of();
		List<SentLine> kept = new ArrayList<>(Math.min(sent.size(), MAX_SENT_LINES));
		for (SentLine entry : sent) {
			if (entry == null || nowNanos - entry.nanos() > ECHO_WINDOW_NANOS) continue;
			kept.add(entry);
		}
		if (kept.size() > MAX_SENT_LINES) {
			return List.copyOf(kept.subList(kept.size() - MAX_SENT_LINES, kept.size()));
		}
		return List.copyOf(kept);
	}

	/** One chat line this client sent from a macro, kept until its echo window closes. */
	public record SentLine(String text, long nanos) {
	}

	/**
	 * A chat-triggered stack that matched a line but did not start.
	 *
	 * <p>Remembered rather than dropped so the player can start exactly that stack from a key once
	 * the reason is gone - the usual case being that chat was open while the message arrived.
	 */
	public record Blocked(int macroId, String scriptId, String reason, long nanos) {
	}
}
