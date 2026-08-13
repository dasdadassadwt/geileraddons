package geiler.addons.client.entity;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
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
		if (hasBody(matched)) return matched;

		Vec3 at = matched.position();
		List<LivingEntity> nearby = level.getEntities(EntityTypeTest.forClass(LivingEntity.class),
			AABB.ofSize(at, SEARCH_RADIUS * 2, SEARCH_RADIUS * 2, SEARCH_RADIUS * 2),
			Nameplates::hasBody);

		LivingEntity best = null;
		double bestDistance = Double.MAX_VALUE;
		for (LivingEntity candidate : nearby) {
			double distance = candidate.position().distanceToSqr(at);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = candidate;
			}
		}
		return best;
	}
}
