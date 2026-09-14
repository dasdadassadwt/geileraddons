package geiler.addons.client.dungeon;

import java.util.Locale;

/** The five Catacombs classes, as written by Party Finder and profile data. */
public enum DungeonClass {
	TANK("Tank"),
	HEALER("Healer"),
	MAGE("Mage"),
	BERSERK("Berserk"),
	ARCHER("Archer");

	private final String displayName;

	DungeonClass(String displayName) {
		this.displayName = displayName;
	}

	public String displayName() {
		return displayName;
	}

	public static DungeonClass parse(String value) {
		if (value == null) return null;
		String normalized = value.trim().toLowerCase(Locale.ROOT);
		for (DungeonClass dungeonClass : values()) {
			if (dungeonClass.name().toLowerCase(Locale.ROOT).equals(normalized)
				|| dungeonClass.displayName.toLowerCase(Locale.ROOT).equals(normalized)) {
				return dungeonClass;
			}
		}
		return null;
	}
}
