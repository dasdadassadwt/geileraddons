package geiler.addons.client.location;

import geiler.addons.client.config.ModConfig;
import geiler.addons.client.config.TikiCoords;
import geiler.addons.client.config.TikiFingerprints;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Whether the player is in Torrhus Canyon, decided by the ground under the saved Tiki
 * coordinates rather than the scoreboard.
 *
 * <p>The scoreboard sidebar turned out to be the wrong signal to chase: Hypixel gives every area
 * its own icon rather than reusing a handful, so a fix that covers the icons seen so far always
 * risks missing the next one - which is exactly what happened. Terrain doesn't have that problem.
 * Every SkyBlock island is its own level with its own map, so the block standing at one of these
 * specific, deep-in-the-map coordinates is Torrhus Canyon's and nothing else's.
 *
 * <p>Since 1.4.2 this is the fallback rather than the whole story: {@link HypixelModApi} names the
 * island outright when Hypixel is talking to it, and only an island it has no name for - or no
 * answer at all - falls through to the terrain below.
 *
 * <p>Each coordinate's block is learned - not shipped - the first time its chunk is seen loaded,
 * since there is no way to know Torrhus Canyon's terrain ahead of time other than a client that
 * has actually stood there. From then on, presence is decided by comparing whatever is currently
 * loaded against what was learned: any disagreement means this is not that island; agreement with
 * nothing disagreeing means it is.
 */
public final class TorrhusPresence {
	/** Twice a second - responsive enough that a warp is never noticeably behind, cheap either way. */
	private static final int CHECK_INTERVAL_TICKS = 10;

	private static final String WAITING = "Waiting for a known spot to load";
	private static final String NO_DATA = "No fingerprint learned yet";
	private static final String ELSEWHERE = "Not Torrhus Canyon";

	private static ClientLevel lastLevel;
	private static boolean present;
	private static String reason = WAITING;
	private static int ticksSinceCheck = CHECK_INTERVAL_TICKS;

	private TorrhusPresence() {
	}

	/** Call once per client tick. Does real work only every {@link #CHECK_INTERVAL_TICKS}. */
	public static void tick() {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;

		if (level != lastLevel) {
			lastLevel = level;
			// A different level is certainly a different island (or no island at all) - nothing
			// carries over, and this forces an immediate re-check below instead of waiting out
			// the rest of whatever interval was mid-count when the level changed.
			present = false;
			reason = WAITING;
			ticksSinceCheck = CHECK_INTERVAL_TICKS;
		}
		if (level == null) return;
		// Nothing to learn or confirm somewhere the API has already named as a different island,
		// and the scan is 25 block reads that would otherwise run everywhere in SkyBlock.
		if (namedElsewhere()) return;
		if (++ticksSinceCheck < CHECK_INTERVAL_TICKS) return;
		ticksSinceCheck = 0;
		check(level);
	}

	/**
	 * The Mod API's answer wins where it has one, and the terrain settles the rest.
	 *
	 * <p>The API is the better signal: it needs nothing to have been learned first, so it works on
	 * a fresh install and the moment a warp lands. What it cannot do is stay right forever - an
	 * island id it predates reads as unnamed rather than wrong, which is exactly the case the
	 * fingerprints still cover.
	 */
	public static boolean isPresent() {
		if (HypixelModApi.currentIsland() == Island.TORRHUS_CANYON) return true;
		if (namedElsewhere()) return false;
		return present;
	}

	/** Why {@link #isPresent()} is currently false; unspecified once it's true. */
	public static String reason() {
		if (namedElsewhere()) return "Not " + Island.TORRHUS_CANYON.displayName();
		return reason;
	}

	/**
	 * Whether the Mod API has named an island that definitely isn't this one.
	 *
	 * <p>An island it has no name for doesn't count: that could be Torrhus Canyon under an id this
	 * build predates, and the terrain check can still settle it.
	 */
	private static boolean namedElsewhere() {
		Island island = HypixelModApi.currentIsland();
		return island != Island.NONE && island != Island.OTHER && island != Island.TORRHUS_CANYON;
	}

	private static void check(ClientLevel level) {
		TikiFingerprints.pruneTo(TikiCoords.all());

		int matches = 0;
		int mismatches = 0;
		int loaded = 0;
		boolean learnedSomething = false;

		for (BlockPos coord : TikiCoords.all()) {
			if (!level.isLoaded(coord)) continue;
			loaded++;

			BlockState state = level.getBlockState(coord);
			if (state.isAir()) continue;
			Block seen = state.getBlock();

			Block known = TikiFingerprints.get(coord);
			if (known == null) {
				TikiFingerprints.learn(coord, seen);
				learnedSomething = true;
			} else if (known == seen) {
				matches++;
			} else {
				mismatches++;
			}
		}

		if (learnedSomething) {
			// Debounced: this runs from the tick loop, and serializing the whole config the moment
			// a chunk loads put a visible hitch right where the player is warping in.
			ModConfig.markDirty();
		}

		// Any disagreement is decisive - the ground at these spots does not change, so a mismatch
		// means this is a different island wearing the same coordinates, not a stale fingerprint.
		if (mismatches > 0) {
			present = false;
			reason = ELSEWHERE;
		} else if (matches > 0) {
			present = true;
		} else {
			present = false;
			reason = loaded == 0 ? WAITING : NO_DATA;
		}
	}
}
