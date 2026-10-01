package geiler.addons.client.module.impl;

import geiler.addons.client.dungeon.DungeonRoomTracker;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.UUID;

/** Isolated no-server checks for the confirmed-room, Fel marker, and Shadow Assassin fixes. */
public final class DungeonMobEspAcceptanceChecks {
	private DungeonMobEspAcceptanceChecks() { }

	public static void main(String[] args) {
		run();
	}

	public static void run() {
		checkConfirmedRoomFootprints();
		checkFelMarkerAnchor();
		checkShadowAssassinIdentityAndRendering();
		checkLoadedEntityMemory();
	}

	private static void checkConfirmedRoomFootprints() {
		var cell = new DungeonRoomTracker.CellKey(2, 3);
		check(DungeonMobEspSupport.confirmedRoomFootprint(false, List.of(cell)) == null,
			"uncalibrated map geometry cannot enable current-room-only ESP");
		check(DungeonMobEspSupport.confirmedRoomFootprint(true, List.of()) == null,
			"an empty physical footprint cannot enable current-room-only ESP");
		var footprint = DungeonMobEspSupport.confirmedRoomFootprint(true, List.of(cell));
		check(footprint != null && footprint.cells().equals(List.of(cell))
			&& "cells:2,3".equals(footprint.footprintKey()),
			"calibrated geometry keeps the confirmed physical cells in a geometry-derived key");
		int roomX = geiler.addons.client.dungeon.DungeonGridRules.GRID_MIN
			+ cell.x() * geiler.addons.client.dungeon.DungeonGridRules.CELL_STRIDE;
		int roomZ = geiler.addons.client.dungeon.DungeonGridRules.GRID_MIN
			+ cell.z() * geiler.addons.client.dungeon.DungeonGridRules.CELL_STRIDE;
		check(DungeonMobEspSupport.isAllowedByRoomFilter(true, footprint,
			new BlockPos(roomX + 8, 70, roomZ + 8)), "a target in the local physical room passes the strict Fel room filter");
		check(!DungeonMobEspSupport.isAllowedByRoomFilter(true, footprint,
			new BlockPos(roomX + geiler.addons.client.dungeon.DungeonGridRules.CELL_STRIDE + 8, 70, roomZ + 8)),
			"a target in the adjacent physical cell is excluded when Fels are limited to this room");
	}

	private static void checkFelMarkerAnchor() {
		var bounds = FelSkullMarkerGeometry.bounds(4.0, 70.0, -3.0);
		check(Math.abs(bounds.centerY() - 71.27) < 0.0001,
			"the Fel skull marker is rendered one block above the armor-stand entity anchor");
		check(Math.abs(bounds.centerX() - 4.0) < 0.0001 && Math.abs(bounds.centerZ() + 3.0) < 0.0001,
			"raising the marker preserves its horizontal position");
		check(Math.abs((bounds.maxY() - bounds.minY()) - 0.6) < 0.0001,
			"raising the marker preserves the source 0.6-block geometry");
	}

	private static void checkShadowAssassinIdentityAndRendering() {
		check(DungeonMobEspSupport.isShadowAssassinProfileName("Shadow Assassin"),
			"the verified profile name identifies the hidden Shadow Assassin body");
		check(!DungeonMobEspSupport.isShadowAssassinProfileName("shadow assassin"),
			"a similarly named player profile does not match the exact NPC name");
		check(DungeonMobEspSupport.isNamedShadowAssassinArmorStandAssociation(
			"✯ Shadow Assassin 100,000❤", 42, 41, true, false, 9.0),
			"the named armor stand resolves to its adjacent living body");
		check(!DungeonMobEspSupport.isNamedShadowAssassinArmorStandAssociation(
			"Lost Adventurer", 42, 41, true, false, 1.0),
			"another miniboss name cannot claim the Shadow Assassin body association");
		check(!DungeonMobEspSupport.isEligibleMinibossName("Shadow Assassin", true)
			&& DungeonMobEspSupport.isEligibleMinibossName("✯ Shadow Assassin", true),
			"Only Starred requires star evidence for a Shadow Assassin as well as other minibosses");
		check(!DungeonMobEspSupport.isEligibleMinibossName("Lost Adventurer", true)
			&& DungeonMobEspSupport.isEligibleMinibossName("✯ Lost Adventurer", true),
			"Only Starred continues to filter other minibosses by their star label");

		UUID profile = UUID.randomUUID();
		ShadowAssassinEntityTracker tracker = new ShadowAssassinEntityTracker();
		tracker.onPlayerInfo(profile, "Shadow Assassin");
		tracker.onPlayerSpawn(profile, 71, true);
		check(tracker.isTrackedEntity(71), "player info followed by spawn associates the NPC");
		tracker.clear();
		tracker.onPlayerSpawn(profile, 72, true);
		tracker.onPlayerInfo(profile, "Shadow Assassin");
		check(tracker.isTrackedEntity(72), "spawn followed by player info associates the same NPC");
		tracker.onEntityRemoved(72);
		check(!tracker.isTrackedEntity(72), "entity removal clears the packet association");

		double previousX = 0, previousY = 70, previousZ = 0;
		double currentX = 4, currentY = 72, currentZ = -2;
		float partialTick = 0.5f;
		var interpolatedBounds = DungeonMobEspSupport.fullShadowAssassinBounds(
			previousX, previousY, previousZ, currentX, currentY, currentZ, partialTick);
		check(Math.abs(interpolatedBounds.getCenter().x - 2.0) < 0.0001
			&& Math.abs(interpolatedBounds.minY - 71.0) < 0.0001
			&& Math.abs(interpolatedBounds.getCenter().z + 1.0) < 0.0001,
			"the expanded Shadow Assassin body box uses the supplied interpolated scalar position");
	}

	private static void checkLoadedEntityMemory() {
		DungeonMobTargetMemory memory = new DungeonMobTargetMemory();
		var first = target(UUID.randomUUID(), "F7:source-room", 100);
		memory.update(List.of(first), "F7:source-room", 100,
			ignored -> DungeonMobTargetMemory.Presence.UNLOADED, ignored -> null);
		long laterTick = 100 + DungeonMobTargetMemory.LIFETIME_TICKS + 50;
		var refreshed = target(first.uuid(), "F7:source-room", laterTick);
		check(memory.update(List.of(), "F7:source-room", laterTick,
			ignored -> DungeonMobTargetMemory.Presence.LOADED_ALIVE, ignored -> refreshed).equals(List.of(refreshed)),
			"a living loaded entity remains associated through long detection gaps and refreshes its bounds");
		check(refreshed.fullShadowAssassin(),
			"memory refresh preserves the reference-confirmed Shadow Assassin render identity");
		check(memory.update(List.of(), "F7:source-room", laterTick + 1,
			ignored -> DungeonMobTargetMemory.Presence.UNLOADED, ignored -> null).isEmpty()
			&& memory.size() == 0,
			"an unloaded entity is removed immediately instead of leaving a stale room marker");

		var next = target(UUID.randomUUID(), "F7:source-room", laterTick + 2);
		memory.update(List.of(next), "F7:source-room", laterTick + 2,
			ignored -> DungeonMobTargetMemory.Presence.LOADED_ALIVE, ignored -> next);
		check(memory.update(List.of(), "F7:other-room", laterTick + 3,
			ignored -> DungeonMobTargetMemory.Presence.LOADED_ALIVE, ignored -> next).isEmpty()
			&& memory.size() == 0,
			"a confirmed room transition clears the old entity-to-room association");
	}

	private static DungeonMobTargetMemory.Target target(UUID uuid, String roomKey, long tick) {
		return new DungeonMobTargetMemory.Target(uuid, 7, DungeonMobTargetMemory.Group.MINIBOSS,
			"minecraft:player", "Shadow Assassin",
			new DungeonMobTargetMemory.Bounds(1, 2, 3, 2, 4, 5), roomKey, tick, true);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException(message);
	}
}
