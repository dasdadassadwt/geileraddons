package geiler.addons.client.dungeon;

import geiler.addons.client.tree.ChatText;

import java.util.Locale;

/** Exact display-name matching for the two different Catacombs key types. */
public final class DungeonKeyRules {
	private DungeonKeyRules() { }

	/** One tick's immutable ownership result, shared by every door color decision in that render frame. */
	public record KeyOwnership(boolean wither, boolean blood) {
		public static final KeyOwnership NONE = new KeyOwnership(false, false);
	}

	public static KeyOwnership combineOwnership(boolean observedWither, boolean observedBlood,
		boolean inventoryWither, boolean inventoryBlood) {
		return new KeyOwnership(observedWither || inventoryWither, observedBlood || inventoryBlood);
	}

	public static boolean isMatchingKey(String displayName, DungeonDoorTracker.Type doorType) {
		if (displayName == null || doorType == null
			|| doorType != DungeonDoorTracker.Type.WITHER && doorType != DungeonDoorTracker.Type.BLOOD) return false;
		String normalized = ChatText.stripForMatch(displayName).toLowerCase(Locale.ROOT)
			.replaceAll("[^a-z0-9]+", " ").trim().replaceAll("\\s+", " ");
		String expected = doorType == DungeonDoorTracker.Type.WITHER ? "wither key" : "blood key";
		return (" " + normalized + " ").contains(" " + expected + " ");
	}

	/** Chat fallback events only belong to the local player when their username is present. */
	public static boolean isPickupForPlayer(String line, String playerName, DungeonDoorTracker.Type doorType) {
		return isLocalPlayerEvent(line, playerName, doorType, " has obtained ", " Key!");
	}

	public static boolean isDoorOpenedByPlayer(String line, String playerName, DungeonDoorTracker.Type doorType) {
		String ending = doorType == DungeonDoorTracker.Type.WITHER ? " opened a wither door!" : " opened a blood door!";
		return isLocalPlayerEvent(line, playerName, doorType, null, ending);
	}

	private static boolean isLocalPlayerEvent(String line, String playerName,
		DungeonDoorTracker.Type doorType, String middle, String ending) {
		if (line == null || playerName == null || playerName.isBlank()
			|| doorType != DungeonDoorTracker.Type.WITHER && doorType != DungeonDoorTracker.Type.BLOOD) return false;
		String cleanLine = ChatText.plain(line).trim();
		String localName = ChatText.stripForMatch(playerName);
		String suffix;
		if (middle != null) {
			String key = doorType == DungeonDoorTracker.Type.WITHER ? "Wither" : "Blood";
			suffix = middle + key + ending;
		} else {
			suffix = ending;
		}
		if (cleanLine.length() <= suffix.length()
			|| !cleanLine.regionMatches(true, cleanLine.length() - suffix.length(), suffix, 0, suffix.length())) return false;
		String actor = cleanLine.substring(0, cleanLine.length() - suffix.length()).trim();
		int lastSpace = actor.lastIndexOf(' ');
		if (lastSpace >= 0) actor = actor.substring(lastSpace + 1);
		return actor.equalsIgnoreCase(localName);
	}
}
