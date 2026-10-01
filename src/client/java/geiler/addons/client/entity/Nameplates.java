package geiler.addons.client.entity;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Finds the mob a Hypixel name label belongs to.
 *
 * <p>Hypixel hangs a mob's name on a separate marker entity floating above it rather than on the
 * mob, and a marker deliberately has no hitbox at all. Matching on that name therefore lands on the
 * label: a box drawn round it encloses nothing and renders as nothing, while a label drawn at it
 * appears exactly as expected - which is what "the highlight does not work" looked like.
 */
public final class Nameplates {
	/** Under this tall there is no body to draw round. Vanilla's smallest mobs clear it by 3x. */
	private static final double MIN_BOX_SIZE = 0.1;
	/** How far from its label a mob may sit; the name floats a little above the head. */
	private static final double SEARCH_RADIUS = 3.0;

	private Nameplates() {
	}

	/** Whether there is enough of a hitbox here to be worth drawing a box around. */
	public static boolean hasBody(Entity entity) {
		return entity.getBoundingBox().getYsize() >= MIN_BOX_SIZE;
	}

	/**
	 * @return {@code matched} itself when it has a body, otherwise the nearest mob that does, or
	 *         null when the label turns out to belong to no mob at all
	 */
	public static Entity resolveBody(ClientLevel level, Entity matched) {
		return resolveBody(level, matched, false);
	}

	/** Resolves labels to real mob bodies; player bodies require an explicit opt-in. */
	public static Entity resolveBody(ClientLevel level, Entity matched, boolean includePlayers) {
		if (allowedBody(matched, includePlayers)) return matched;
		Vec3 at = matched.position();
		List<LivingEntity> nearby = level.getEntities(EntityTypeTest.forClass(LivingEntity.class),
			AABB.ofSize(at, SEARCH_RADIUS * 2, SEARCH_RADIUS * 2, SEARCH_RADIUS * 2),
			entity -> allowedBody(entity, includePlayers));
		return resolveBody(matched, nearby, includePlayers);
	}

	/**
	 * Resolves against an already-collected nearby list. Keeping selection separate from the world
	 * query makes the player-exclusion rule explicit and gives the offline harness a deterministic
	 * seam for labels, direct fake players, and misleading nearby players.
	 */
	public static Entity resolveBody(Entity matched, List<? extends LivingEntity> nearby, boolean includePlayers) {
		if (allowedBody(matched, includePlayers)) return matched;

		Vec3 at = matched.position();
		LivingEntity best = null;
		double bestDistance = Double.MAX_VALUE;
		for (LivingEntity candidate : nearby) {
			if (!allowedBody(candidate, includePlayers)) continue;
			double distance = candidate.position().distanceToSqr(at);
			// Match the world-query overload above: labels may only bind to a body in the local
			// nameplate area, never to an arbitrary entity elsewhere in the caller's larger scan.
			if (distance > SEARCH_RADIUS * SEARCH_RADIUS) continue;
			if (distance < bestDistance) {
				bestDistance = distance;
				best = candidate;
			}
		}
		return best;
	}

	/**
	 * Resolves a marker only when one living body is clearly closer than every other candidate.
	 * Dungeon mob name stands can overlap in busy rooms; choosing the nearest tied entity paints the
	 * wrong mob, so visual classifiers should prefer a missed marker over a false association.
	 */
	public static Entity resolveUnambiguousBody(Entity matched, List<? extends LivingEntity> nearby,
		boolean includePlayers) {
		if (matched == null) return null;
		if (!(matched instanceof ArmorStand) && allowedBody(matched, includePlayers)) return matched;
		if (nearby == null) return null;
		Vec3 at = matched.position();
		LivingEntity best = null;
		double bestDistance = Double.MAX_VALUE;
		double secondDistance = Double.MAX_VALUE;
		for (LivingEntity candidate : nearby) {
			if (candidate == matched || candidate instanceof ArmorStand || !allowedBody(candidate, includePlayers)) continue;
			double distance = candidate.position().distanceToSqr(at);
			if (distance > SEARCH_RADIUS * SEARCH_RADIUS) continue;
			if (distance < bestDistance) { secondDistance = bestDistance; bestDistance = distance; best = candidate; }
			else if (distance < secondDistance) secondDistance = distance;
		}
		return best != null && (secondDistance == Double.MAX_VALUE || secondDistance - bestDistance >= 0.75)
			? best : null;
	}

	private static boolean allowedBody(Entity entity, boolean includePlayers) {
		return hasBody(entity) && (includePlayers || !(entity instanceof Player));
	}
}
