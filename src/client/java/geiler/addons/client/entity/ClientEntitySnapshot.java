package geiler.addons.client.entity;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * One client-thread entity query shared by nearby ESP consumers during a game tick.
 *
 * <p>The snapshot is deliberately short-lived: entity positions and liveness are only reused
 * until the level's game time advances. Callers still apply their own type and match filters.
 */
public final class ClientEntitySnapshot {
	/**
	 * How many distinct queries one client tick may reuse.
	 *
	 * <p>One slot was not enough for the consumers this exists for: the plot-border scan asks for 128
	 * blocks and the pest highlighter for the render distance capped at 64, so in the same tick the
	 * second caller always missed and queried every entity again. A handful of slots covers the
	 * modules that exist without letting the map grow.
	 */
	private static final int MAX_CACHED_QUERIES = 4;
	private static final Cache<Entity> CACHE = new Cache<>(MAX_CACHED_QUERIES);

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
		private final int capacity;
		private final Map<Key, List<T>> entries = new LinkedHashMap<>();
		/** The level and tick the current entries belong to; anything else invalidates all of them. */
		private Object level;
		private long gameTime;
		private boolean primed;

		Cache() {
			this(MAX_CACHED_QUERIES);
		}

		Cache(int capacity) {
			this.capacity = Math.max(1, capacity);
		}

		List<T> get(Key requested, Supplier<List<T>> loader) {
			if (!primed || !Objects.equals(level, requested.level()) || gameTime != requested.gameTime()) {
				// Positions, liveness and even the level itself can all change between ticks, so the
				// whole map is stale rather than just the entry that was asked for.
				entries.clear();
				level = requested.level();
				gameTime = requested.gameTime();
				primed = true;
			}
			List<T> cached = entries.get(requested);
			if (cached != null) return cached;

			List<T> loaded = List.copyOf(loader.get());
			if (entries.size() >= capacity) {
				Iterator<Key> oldest = entries.keySet().iterator();
				if (oldest.hasNext()) {
					oldest.next();
					oldest.remove();
				}
			}
			entries.put(requested, loaded);
			return loaded;
		}
	}

	record Key(Object level, Object player, long gameTime, double x, double y, double z, double range) {
	}
}
