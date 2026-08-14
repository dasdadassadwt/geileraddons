package geiler.addons.client.location;

/**
 * A SkyBlock island, identified by the {@code mode} string the Hypixel Mod API reports.
 *
 * <p>The mode strings are not the island's readable name and cannot be guessed from it - Torrhus
 * Canyon reports {@code foraging_3}, not {@code torrhus_canyon}. They come from the values
 * SkyHanni maps against; see REFERENCES.md.
 *
 * <p>The guest variants of the Private Island and the Garden are deliberately missing. Hypixel
 * reports no mode at all for them, so there is nothing here that could tell them from the island
 * itself - listing them would be offering a filter that can never match.
 */
public enum Island {
	// General
	PRIVATE_ISLAND("dynamic", "Private Island", "your Private Island"),
	HUB("hub", "Hub", "the Hub"),
	DARK_AUCTION("dark_auction", "Dark Auction", "the Dark Auction"),
	WINTER("winter", "Jerry's Workshop"),

	// Farming
	FARMING_ISLANDS("farming_1", "The Farming Islands", "the Farming Islands"),
	GARDEN("garden", "Garden", "the Garden"),

	// Mining
	GOLD_MINE("mining_1", "Gold Mine", "the Gold Mine"),
	DEEP_CAVERNS("mining_2", "Deep Caverns", "the Deep Caverns"),
	DWARVEN_MINES("mining_3", "Dwarven Mines", "the Dwarven Mines"),
	CRYSTAL_HOLLOWS("crystal_hollows", "Crystal Hollows", "the Crystal Hollows"),
	MINESHAFT("mineshaft", "Mineshaft", "a Mineshaft"),

	// Fishing
	BACKWATER_BAYOU("fishing_1", "Backwater Bayou", "the Backwater Bayou"),
	LOTUS_ATOLL("lotus_atoll", "Lotus Atoll", "the Lotus Atoll"),

	// Foraging
	THE_PARK("foraging_1", "The Park", "the Park"),
	GALATEA("foraging_2", "Moonglade Marsh"),
	TORRHUS_CANYON("foraging_3", "Torrhus Canyon"),

	// Combat
	SPIDERS_DEN("combat_1", "Spider's Den", "the Spider's Den"),
	THE_END("combat_3", "The End", "the End"),
	CRIMSON_ISLE("crimson_isle", "Crimson Isle", "the Crimson Isle"),

	// Dungeons
	DUNGEON_HUB("dungeon_hub", "Dungeon Hub", "the Dungeon Hub"),
	CATACOMBS("dungeon", "Catacombs", "the Catacombs"),
	KUUDRA("kuudra", "Kuudra"),

	// Special
	THE_RIFT("rift", "The Rift", "the Rift"),
	SAFARI("safari", "Critter Safari", "the Critter Safari"),

	/** The API named an island this mod has no constant for - decisive that we are somewhere else. */
	OTHER(null, "Other", "a named island"),
	/** No answer at all: not on Hypixel, the API is switched off, or the handshake hasn't finished. */
	NONE(null, "None", "nowhere known");

	private final String mode;
	private final String label;
	private final String displayName;

	Island(String mode, String label) {
		this(mode, label, label);
	}

	Island(String mode, String label, String displayName) {
		this.mode = mode;
		this.label = label;
		this.displayName = displayName;
	}

	/** The plain name, for a list that names islands rather than explaining an absence. */
	public String label() {
		return label;
	}

	/** Reads as the tail of "Not ..." in the Click GUI's idle explanation. */
	public String displayName() {
		return displayName;
	}

	/**
	 * Whether this is a real island the API can report, as opposed to one of the two answers that
	 * mean it did not name one. Only these belong in a per-island filter.
	 */
	public boolean selectable() {
		return mode != null;
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
