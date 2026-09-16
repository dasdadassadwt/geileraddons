package geiler.addons.client.enchanting;

/** Result of a local click observation; no network operation occurs in the pure layer. */
public record ClickDecision(int slotId, boolean recognized, boolean expected, boolean predicted,
	boolean visualStateChanged, String reason) {
	public ClickDecision {
		reason = reason == null ? "" : reason;
	}
}
