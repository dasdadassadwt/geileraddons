package geiler.addons.client.enchanting;

/**
 * One ordered Chronomatron container event.
 *
 * <p>The puzzle changes too quickly to reconstruct its reveal order by polling a finished board.
 * Keeping status and board updates as explicit events lets the pure solver use the same state
 * transitions as the live container without depending on Minecraft classes.</p>
 */
public record ChronomatronEvent(Kind kind, int slotId, String value, boolean highlighted,
	String status) {
	public enum Kind {
		BOARD,
		STATUS
	}

	public ChronomatronEvent {
		if (kind == null) throw new IllegalArgumentException("kind must not be null");
		if (kind == Kind.BOARD && slotId < 0) {
			throw new IllegalArgumentException("board events require a non-negative slot id");
		}
		value = normalize(value);
		status = status == null ? "" : status.trim();
	}

	public static ChronomatronEvent board(int slotId, String value, boolean highlighted) {
		return new ChronomatronEvent(Kind.BOARD, slotId, value, highlighted, "");
	}

	public static ChronomatronEvent status(String status) {
		return new ChronomatronEvent(Kind.STATUS, -1, null, false, status);
	}

	private static String normalize(String value) {
		if (value == null) return null;
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
