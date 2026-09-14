package geiler.addons.client.dungeon;

import java.util.Locale;

/** Normal and Master floors supported by Party Finder. */
public enum DungeonFloor {
	F1("F1", false, 1), F2("F2", false, 2), F3("F3", false, 3), F4("F4", false, 4),
	F5("F5", false, 5), F6("F6", false, 6), F7("F7", false, 7),
	M1("M1", true, 1), M2("M2", true, 2), M3("M3", true, 3), M4("M4", true, 4),
	M5("M5", true, 5), M6("M6", true, 6), M7("M7", true, 7);

	private final String displayName;
	private final boolean master;
	private final int number;

	DungeonFloor(String displayName, boolean master, int number) {
		this.displayName = displayName;
		this.master = master;
		this.number = number;
	}

	public String displayName() {
		return displayName;
	}

	public boolean master() {
		return master;
	}

	public int number() {
		return number;
	}

	public static DungeonFloor parse(String value) {
		if (value == null) return null;
		String normalized = value.trim().toUpperCase(Locale.ROOT);
		for (DungeonFloor floor : values()) {
			if (floor.displayName.equals(normalized)) return floor;
		}
		return null;
	}
}
