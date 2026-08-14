package geiler.addons.client.location;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/**
 * Which part of the Critter Safari the player is standing in.
 *
 * <p>Read from the biome the server actually reports, not from a map of coordinates. Hypixel ships
 * the Safari's areas as real biomes in its own namespace - {@code hypixel:haunted} and friends - so
 * the client can simply ask. That matters: a coordinate list goes stale the moment Hypixel moves a
 * wall, and this mod has already thrown one of those away once (see REFERENCES.md on the Hideyho
 * hiding spots). There is nothing here to keep up to date.
 *
 * <p>Resolved once a tick rather than on demand, because the answer is read from render as well as
 * from tick and a biome lookup per frame buys nothing.
 */
public enum SafariBiome {
	FOREST("Forest", "forest"),
	CAVERN("Cavern", "cavern"),
	/** Two ids, and both are the same place as far as anything here is concerned. */
	ICY("Icy", "icy", "icy_caves"),
	HAUNTED("Haunted", "haunted");

	/** Hypixel registers the Safari's biomes under its own namespace rather than {@code minecraft}. */
	private static final String NAMESPACE = "hypixel";

	private final String displayName;
	private final Identifier[] ids;

	private static SafariBiome current;

	SafariBiome(String displayName, String... paths) {
		this.displayName = displayName;
		this.ids = new Identifier[paths.length];
		for (int i = 0; i < paths.length; i++) {
			this.ids[i] = Identifier.fromNamespaceAndPath(NAMESPACE, paths[i]);
		}
	}

	/** Reads as the tail of "Not in the ..." in the Click GUI's idle explanation. */
	public String displayName() {
		return displayName;
	}

	/**
	 * The Safari biome under the player's feet, or null if there isn't one to be had.
	 *
	 * <p>Null covers being somewhere else entirely, and also a Safari whose biomes the client
	 * cannot name. Callers are expected to treat those the same way and not go silent on it.
	 */
	public static SafariBiome current() {
		return current;
	}

	/** Call once per client tick, after the island is resolved. */
	public static void tick() {
		current = resolve();
	}

	private static SafariBiome resolve() {
		// Nowhere else has these biomes, so off the Safari there is nothing to look up.
		if (HypixelModApi.currentIsland() != Island.SAFARI) return null;

		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) return null;
		Level level = player.level();
		BlockPos feet = player.blockPosition();
		// Outside build height the lookup is meaningless rather than merely wrong.
		if (!level.isInsideBuildHeight(feet)) return null;

		Holder<Biome> biome = level.getBiome(feet);
		for (SafariBiome candidate : values()) {
			for (Identifier id : candidate.ids) {
				if (biome.is(id)) return candidate;
			}
		}
		return null;
	}
}
