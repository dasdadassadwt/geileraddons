package geiler.addons.client.location;

/**
 * A SkyBlock island, identified by the {@code mode} string the Hypixel Mod API reports.
 *
 * <p>The mode strings are not the island's readable name and cannot be guessed from it - Torrhus
 * Canyon reports {@code foraging_3}, not {@code torrhus_canyon}. They come from the values
 * SkyHanni maps against; see REFERENCES.md.
 */
public enum Island {
	SAFARI("safari", "the Critter Safari"),
	TORRHUS_CANYON("foraging_3", "Torrhus Canyon"),
	/** The API named an island this mod has no constant for - decisive that we are somewhere else. */
	OTHER(null, "a named island"),
	/** No answer at all: not on Hypixel, the API is switched off, or the handshake hasn't finished. */
	NONE(null, "nowhere known");

	private final String mode;
	private final String displayName;

	Island(String mode, String displayName) {
		this.mode = mode;
		this.displayName = displayName;
	}

	/** Reads as the tail of "Not ..." in the Click GUI's idle explanation. */
	public String displayName() {
		return displayName;
	}

	/**
	 * @param mode the raw mode string, or null if the server sent none
	 * @return the matching island, or {@link #OTHER} for an island this mod doesn't name
	 */
	static Island fromMode(String mode) {
		if (mode == null || mode.isBlank()) return OTHER;
		for (Island island : values()) {
			if (island.mode != null && island.mode.equalsIgnoreCase(mode)) {
				return island;
			}
		}
		return OTHER;
	}
}
