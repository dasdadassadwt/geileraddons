package geiler.addons.client.entity;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.function.Supplier;

/**
 * One client-thread entity query shared by nearby ESP consumers during a game tick.
 *
 * <p>The snapshot is deliberately short-lived: entity positions and liveness are only reused
 * until the level's game time advances. Callers still apply their own type and match filters.
 */
public final class ClientEntitySnapshot {
	private static final Cache<Entity> CACHE = new Cache<>();

	private ClientEntitySnapshot() {
	}

	/**
	 * Returns all live entities in the requested client-side scan area, reusing the query for the
	 * remainder of the same level tick when the player and range are unchanged.
	 */
	public static List<Entity> nearby(ClientLevel level, LocalPlayer player, double range) {
		if (level == null || player == null || !Double.isFinite(range) || range <= 0) return List.of();

		double x = player.getX();
		double y = player.getY();
		double z = player.getZ();
		Key key = new Key(level, player, level.getGameTime(), x, y, z, range);
		return CACHE.get(key, () -> {
			AABB area = AABB.ofSize(player.position(), range * 2, range * 2, range * 2);
			return level.getEntities(EntityTypeTest.forClass(Entity.class), area, Entity::isAlive);
		});
	}

	/** Small generic cache kept package-visible so the offline harness can verify its invalidation. */
	static final class Cache<T> {
		private Key key;
		private List<T> value = List.of();

		List<T> get(Key requested, Supplier<List<T>> loader) {
			if (requested.equals(key)) return value;
			key = requested;
			value = List.copyOf(loader.get());
			return value;
		}
	}

	record Key(Object level, Object player, long gameTime, double x, double y, double z, double range) {
	}
}
