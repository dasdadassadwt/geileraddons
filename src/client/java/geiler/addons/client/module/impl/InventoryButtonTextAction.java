package geiler.addons.client.module.impl;

/** Parses a user-clicked inventory action without depending on Minecraft connection state. */
public final class InventoryButtonTextAction {
	public enum Kind { COMMAND, CHAT }
	public record Dispatch(Kind kind, String payload) { }

	private InventoryButtonTextAction() { }

	public static Dispatch parse(String raw) {
		if (raw == null) return null;
		String value = raw.trim();
		if (value.isEmpty()) return null;
		if (value.startsWith("/")) {
			String command = value.substring(1).trim();
			return command.isEmpty() ? null : new Dispatch(Kind.COMMAND, command);
		}
		return new Dispatch(Kind.CHAT, value);
	}
}
