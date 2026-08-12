package geiler.addons.client.module;

public enum Category {
	F7("F7"),
	HUNTING("Hunting"),
	FORAGING("Foraging"),
	VISUAL("Visual");

	private final String displayName;

	Category(String displayName) {
		this.displayName = displayName;
	}

	public String displayName() {
		return displayName;
	}
}
