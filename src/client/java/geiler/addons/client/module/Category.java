package geiler.addons.client.module;

public enum Category {
	F7("Dungeons"),
	ENCHANTING("Enchanting"),
	HUNTING("Hunting"),
	FORAGING("Foraging"),
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
