package geiler.addons.client.module.impl;

import java.util.LinkedHashMap;
import java.util.List;

/** Bounded starred-position evidence retained per physical dungeon room for one run. */
final class DungeonMobEncounterHistory {
	static final int MAX_ROOMS = 36;
	static final int MAX_POSITIONS_PER_ROOM = 1024;

	private record Cell(int x, int y, int z) { }
	private final LinkedHashMap<String, LinkedHashMap<Cell, DungeonMobEspSupport.WorldPosition>> rooms = new LinkedHashMap<>();

	void observe(String roomKey, double x, double y, double z) {
		if (roomKey == null || roomKey.isBlank() || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return;
		LinkedHashMap<Cell, DungeonMobEspSupport.WorldPosition> positions = rooms.computeIfAbsent(roomKey,
			ignored -> new LinkedHashMap<>());
		positions.putIfAbsent(new Cell((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)),
			new DungeonMobEspSupport.WorldPosition(x, y, z));
		while (positions.size() > MAX_POSITIONS_PER_ROOM) {
			var oldest = positions.keySet().iterator();
			if (!oldest.hasNext()) break;
			oldest.next();
			oldest.remove();
		}
		while (rooms.size() > MAX_ROOMS) {
			var oldestRoom = rooms.keySet().iterator();
			if (!oldestRoom.hasNext()) break;
			oldestRoom.next();
			oldestRoom.remove();
		}
	}

	List<DungeonMobEspSupport.WorldPosition> positions(String roomKey) {
		if (roomKey == null) return List.of();
		var positions = rooms.get(roomKey);
		return positions == null ? List.of() : List.copyOf(positions.values());
	}

	void clearRoom(String roomKey) {
		if (roomKey != null) rooms.remove(roomKey);
	}

	void clear() { rooms.clear(); }
	int roomCount() { return rooms.size(); }
}
