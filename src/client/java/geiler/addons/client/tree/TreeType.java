package geiler.addons.client.tree;

/** The three gift-dropping trees, and the chat line each one announces. */
public enum TreeType {
	HELIX("Helix", "Helix Tree Gift."),
	FIG("Fig", "FigTree Gift."),
	MANGROVE("Mangrove", "MangroveTree Gift.");

	private final String displayName;
	private final String giftMessage;

	TreeType(String displayName, String giftMessage) {
		this.displayName = displayName;
		this.giftMessage = giftMessage;
	}

	public String displayName() {
		return displayName;
	}

	public String giftMessage() {
		return giftMessage;
	}

	/**
	 * The tree whose gift line the message contains, or null.
	 *
	 * <p>Matched with contains() rather than equals() so the line still registers when a
	 * chat-compacting mod prepends a counter or the server adds formatting around it.
	 */
	public static TreeType fromMessage(String message) {
		for (TreeType type : VALUES) {
			if (message.contains(type.giftMessage)) return type;
		}
		return null;
	}

	/** values() hands out a fresh copy each call, and this runs on every chat line. */
	private static final TreeType[] VALUES = values();
}
