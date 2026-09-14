package geiler.addons.client.tree;

import java.util.regex.Pattern;

/**
 * The lines announcing a tree coming down, which are not part of a gift.
 *
 * <p>Kept apart from {@link TreeType} because these are a different event, not another way of
 * saying the same one: they fire on the log break that finishes a tree, can arrive before the
 * gift, can happen without one, and can legitimately occur twice for a single tree. Nothing here
 * may be de-duplicated against a gift.
 */
public final class TreeFelled {
	/**
	 * Anchored on the two shout words plus "felled" rather than the whole sentence, so a trailing
	 * chat-compacting counter, stray padding or a reworded middle still registers.
	 */
	private static final Pattern FELLED =
		Pattern.compile("\\b(TIMBER|PETALFALL)\\b.*\\bfelled\\b", Pattern.CASE_INSENSITIVE);

	private TreeFelled() {
	}

	public static boolean matches(String message) {
		return FELLED.matcher(ChatText.plain(message)).find();
	}
}
