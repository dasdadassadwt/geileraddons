package geiler.addons.client.module.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/** Bounded, room-scoped targets retained through detection loss only while their entity stays loaded. */
final class DungeonMobTargetMemory {
	static final long LIFETIME_TICKS = 10L * 20L;
	static final int MAX_TARGETS = 512;

	enum Group { STARRED, MINIBOSS, FEL_SKULL, FEL_MOVING }
	enum Presence { LOADED_ALIVE, LOADED_GONE, UNLOADED }

	record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
		Bounds {
			if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ)
				|| !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ)
				|| maxX < minX || maxY < minY || maxZ < minZ) {
				throw new IllegalArgumentException("Invalid dungeon ESP bounds");
			}
		}
		double centerX() { return (minX + maxX) * 0.5; }
		double centerY() { return (minY + maxY) * 0.5; }
		double centerZ() { return (minZ + maxZ) * 0.5; }
	}

	/** Immutable identity and last-seen rendering data; never keeps a live Entity reference. */
	record Target(UUID uuid, int entityId, Group group, String type, String label, Bounds bounds,
		String roomKey, long seenAtTick, boolean fullShadowAssassin) {
		Target(UUID uuid, int entityId, Group group, String type, String label, Bounds bounds,
			String roomKey, long seenAtTick) {
			this(uuid, entityId, group, type, label, bounds, roomKey, seenAtTick, false);
		}

		Target {
			Objects.requireNonNull(uuid, "uuid");
			Objects.requireNonNull(group, "group");
			type = type == null ? "unknown" : type;
			label = label == null ? "" : label;
			Objects.requireNonNull(bounds, "bounds");
			Objects.requireNonNull(roomKey, "roomKey");
		}
	}

	private record Key(UUID uuid, Group group) { }
	private final LinkedHashMap<Key, Target> targets = new LinkedHashMap<>();
	private String roomKey;

	/** Stores matched entities and keeps a snapshot only while the same entity remains loaded and alive. */
	List<Target> update(Collection<Target> currentlyMatched, String currentRoom, long gameTick,
		Function<Target, Presence> presenceOf, Function<Target, Target> refreshLoaded) {
		if (currentRoom == null || currentRoom.isBlank()) {
			clear();
			return List.of();
		}
		if (!currentRoom.equals(roomKey)) {
			targets.clear();
			roomKey = currentRoom;
		}
		if (currentlyMatched != null) for (Target seen : currentlyMatched) {
			if (seen == null || !currentRoom.equals(seen.roomKey())) continue;
			Key key = new Key(seen.uuid(), seen.group());
			targets.remove(key);
			targets.put(key, seen);
		}
		while (targets.size() > MAX_TARGETS) {
			var oldest = targets.keySet().iterator();
			if (!oldest.hasNext()) break;
			oldest.next();
			oldest.remove();
		}

		List<Target> retained = new ArrayList<>();
		var iterator = targets.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<Key, Target> entry = iterator.next();
			Target target = entry.getValue();
			if (!currentRoom.equals(target.roomKey())) {
				iterator.remove();
				continue;
			}
			if (currentlyMatched != null && currentlyMatched.stream().anyMatch(seen -> seen != null
				&& seen.uuid().equals(target.uuid()) && seen.group() == target.group())) continue;
			Presence presence = presenceOf == null ? Presence.LOADED_GONE : presenceOf.apply(target);
			if (presence == Presence.UNLOADED) {
				iterator.remove();
				continue;
			}
			if (presence == Presence.LOADED_ALIVE) {
				Target refreshed = refreshLoaded == null ? null : refreshLoaded.apply(target);
				if (refreshed != null && currentRoom.equals(refreshed.roomKey())
					&& refreshed.uuid().equals(target.uuid()) && refreshed.group() == target.group()) {
					entry.setValue(refreshed);
					retained.add(refreshed);
					continue;
				}
			}
			iterator.remove();
		}
		return List.copyOf(retained);
	}

	void clearGroup(Group group) {
		if (group != null) targets.keySet().removeIf(key -> key.group() == group);
	}

	void clear() {
		targets.clear();
		roomKey = null;
	}

	int size() { return targets.size(); }
}
