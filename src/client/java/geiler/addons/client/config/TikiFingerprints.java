package geiler.addons.client.config;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The block each Tiki coordinate's ground position was last confirmed to be.
 *
 * <p>There is no way to know Torrhus Canyon's terrain ahead of time other than a client that has
 * actually stood there and looked - so rather than ship guessed data, each coordinate's fingerprint
 * is learned the first time its chunk is seen loaded, and kept from then on. Backs
 * {@link geiler.addons.client.location.TorrhusPresence}, which is what actually decides presence
 * from these once several agree.
 *
 * <p>Never overwritten once learned: the ground under a tiki spawn is undecorated map terrain, not
 * something a player can break or place on, so a fingerprint that was ever right stays right. Kept
 * outside the module itself, same reasoning as {@link TikiCoords} - the config layer and the
 * detector both need it without going through a module lookup.
 */
public final class TikiFingerprints {
	private static final Map<BlockPos, Block> FINGERPRINTS = new HashMap<>();

	private TikiFingerprints() {
	}

	public static Block get(BlockPos coord) {
		return FINGERPRINTS.get(coord);
	}

	/** Records a coordinate's fingerprint if it doesn't already have one. */
	public static void learn(BlockPos coord, Block block) {
		FINGERPRINTS.putIfAbsent(coord.immutable(), block);
	}

	public static Map<BlockPos, Block> all() {
		return Collections.unmodifiableMap(FINGERPRINTS);
	}

	public static void replaceAll(Map<BlockPos, Block> data) {
		FINGERPRINTS.clear();
		FINGERPRINTS.putAll(data);
	}

	/** Drops any fingerprint whose coordinate is no longer a saved waypoint. */
	public static void pruneTo(Iterable<BlockPos> liveCoords) {
		if (FINGERPRINTS.isEmpty()) return;
		Set<BlockPos> keep = new HashSet<>();
		liveCoords.forEach(keep::add);
		FINGERPRINTS.keySet().retainAll(keep);
	}

	public static String idOf(Block block) {
		return BuiltInRegistries.BLOCK.getKey(block).toString();
	}

	/** DefaultedRegistry falls back to air for an id it doesn't recognise - never null. */
	public static Block byId(String id) {
		Identifier parsed = Identifier.tryParse(id);
		return parsed == null ? null : BuiltInRegistries.BLOCK.getValue(parsed);
	}
}
