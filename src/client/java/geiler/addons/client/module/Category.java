package geiler.addons.client.module;

public enum Category {
	F7("Dungeons"),
	ENCHANTING("Enchanting"),
	HUNTING("Hunting"),
	FARMING("Farming"),
	FORAGING("Foraging"),
	MISCELLANEOUS("Miscellaneous"),
	VISUAL("Visual"),
	DEV("Dev");

	private final String displayName;

	Category(String displayName) {
		this.displayName = displayName;
	}

	public String displayName() {
		return displayName;
	}
}
