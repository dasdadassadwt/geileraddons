package geiler.addons.client.module.impl;

import geiler.addons.client.dungeon.DungeonRoomTracker;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Isolated no-server checks for Shadow Assassin identity, packet order, and room filtering. */
public final class ShadowAssassinAcceptanceChecks {
	private ShadowAssassinAcceptanceChecks() { }

	public static void main(String[] args) {
		run();
	}

	public static void run() {
		checkClassificationAndAssociation();
		checkBothPacketOrdersAndRejections();
		checkCurrentRoomFiltering();
		checkMinibossDeduplication();
	}

	private static void checkClassificationAndAssociation() {
		check(!DungeonMobEspSupport.isEligibleMinibossName("Shadow Assassin", true)
			&& DungeonMobEspSupport.isEligibleMinibossName("✯ Shadow Assassin", true),
			"Only Starred excludes unstarred Shadow Assassins and keeps starred ones in the Miniboss path");
		check(DungeonMobEspSupport.isShadowAssassin("✯ Shadow Assassin 100,000❤"),
			"formatting, star glyph, and health suffix normalize to the SA label identity");
		check(!DungeonMobEspSupport.isShadowAssassin("Lost Adventurer"),
			"another miniboss label is not classified as a Shadow Assassin");
		check(DungeonMobEspSupport.isNamedShadowAssassinArmorStandAssociation(
			"✯ Shadow Assassin 100,000❤", 42, 41, true, false, 9.0),
			"the SA label associates to its adjacent living non-armor-stand body at the maximum distance");
		check("body-not-found".equals(DungeonMobEspSupport.shadowAssassinAssociationReason(
			"Shadow Assassin", 42, -1, false, false, Double.NaN)),
			"diagnostics identify a label whose adjacent body has not arrived");
		check("body-id-not-label-minus-one".equals(DungeonMobEspSupport.shadowAssassinAssociationReason(
			"Shadow Assassin", 42, 40, true, false, 1.0)),
			"diagnostics identify an entity-ID relationship mismatch");
		check("associated-body-not-living".equals(DungeonMobEspSupport.shadowAssassinAssociationReason(
			"Shadow Assassin", 42, 41, false, false, 1.0)),
			"diagnostics identify an adjacent nonliving entity");
		check("associated-body-is-armor-stand".equals(DungeonMobEspSupport.shadowAssassinAssociationReason(
			"Shadow Assassin", 42, 41, true, true, 1.0)),
			"diagnostics reject an armor stand as the body");
		check("associated-body-too-far".equals(DungeonMobEspSupport.shadowAssassinAssociationReason(
			"Shadow Assassin", 42, 41, true, false, 9.01)),
			"diagnostics identify an adjacent-ID candidate outside the validated distance");
		check("label-name-mismatch".equals(DungeonMobEspSupport.shadowAssassinAssociationReason(
			"Lost Adventurer", 42, 41, true, false, 1.0)),
			"diagnostics reject other miniboss labels before body association");
	}

	private static void checkBothPacketOrdersAndRejections() {
		UUID profileFirst = UUID.randomUUID();
		ShadowAssassinEntityTracker profileFirstTracker = new ShadowAssassinEntityTracker();
		profileFirstTracker.onPlayerInfoUpdate(true, 1);
		profileFirstTracker.onPlayerInfo(profileFirst, "Shadow Assassin");
		profileFirstTracker.onPlayerSpawn(profileFirst, 71, true);
		check(profileFirstTracker.isTrackedEntity(71), "profile info followed by player spawn associates the SA");
		check(profileFirstTracker.packetDiagnostics().contains("matchedAtSpawn=1")
			&& profileFirstTracker.packetDiagnostics().contains("last=spawn-associated-existing-profile"),
			"profile-first diagnostics report the profile match and spawn association stages");

		UUID spawnFirst = UUID.randomUUID();
		ShadowAssassinEntityTracker spawnFirstTracker = new ShadowAssassinEntityTracker();
		spawnFirstTracker.onPlayerSpawn(spawnFirst, 72, true);
		spawnFirstTracker.onPlayerInfoUpdate(true, 1);
		spawnFirstTracker.onPlayerInfo(spawnFirst, "Shadow Assassin");
		check(spawnFirstTracker.isTrackedEntity(72), "player spawn followed by profile info associates the SA");
		check(spawnFirstTracker.packetDiagnostics().contains("matchedAtProfile=1")
			&& spawnFirstTracker.packetDiagnostics().contains("last=profile-match-associated-pending-spawn"),
			"spawn-first diagnostics report the profile match and pending-spawn association stages");

		UUID unrelated = UUID.randomUUID();
		ShadowAssassinEntityTracker rejectedTracker = new ShadowAssassinEntityTracker();
		rejectedTracker.onPlayerSpawn(unrelated, 73, true);
		rejectedTracker.onPlayerInfo(unrelated, "Lost Adventurer");
		check(!rejectedTracker.isTrackedEntity(73)
			&& rejectedTracker.packetDiagnostics().contains("otherProfiles=1")
			&& rejectedTracker.packetDiagnostics().contains("last=profile-name-mismatch"),
			"a non-SA profile clears its pending spawn and leaves an explicit rejection reason");
		rejectedTracker.onPlayerSpawn(UUID.randomUUID(), 74, false);
		check(rejectedTracker.packetDiagnostics().contains("nonPlayerSpawns=1")
			&& rejectedTracker.packetDiagnostics().contains("last=add-entity-not-player"),
			"non-player add-entity packets are counted without creating SA candidates");
	}

	private static void checkCurrentRoomFiltering() {
		BlockPos inside = new BlockPos(-199, 70, -199);
		BlockPos outside = new BlockPos(-168, 70, -199);
		var footprint = new DungeonRoomTracker.MapRoomFootprint(null,
			List.of(new DungeonRoomTracker.CellKey(0, 0)));
		check(!DungeonMobEspSupport.isAllowedByRoomFilter(true, null, inside)
			&& "confirmed-room-footprint-unavailable".equals(
				DungeonMobEspSupport.roomFilterRejection(true, null, inside)),
			"current-room-only rejects packet candidates while confirmed occupancy is unavailable");
		check(DungeonMobEspSupport.isAllowedByRoomFilter(true, footprint, inside)
			&& "inside-confirmed-room".equals(DungeonMobEspSupport.roomFilterRejection(true, footprint, inside)),
			"current-room-only accepts a body position in a confirmed occupied cell");
		check(!DungeonMobEspSupport.isAllowedByRoomFilter(true, footprint, outside)
			&& "outside-confirmed-room".equals(DungeonMobEspSupport.roomFilterRejection(true, footprint, outside)),
			"current-room-only rejects a body position in a different cell");
		check(DungeonMobEspSupport.isAllowedByRoomFilter(false, null, null)
			&& "room-filter-disabled".equals(DungeonMobEspSupport.roomFilterRejection(false, null, null)),
			"disabling current-room-only leaves packet resolution independent of map occupancy");
	}

	private static void checkMinibossDeduplication() {
		List<Object> minibosses = new ArrayList<>();
		List<Object> shadowAssassins = new ArrayList<>();
		Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		Object candidate = new Object();
		check(DungeonMobEspSupport.addShadowAssassinCandidateOnce(minibosses, shadowAssassins, seen, candidate),
			"the first packet-resolved SA is added to both the Miniboss and SA lists");
		check(!DungeonMobEspSupport.addShadowAssassinCandidateOnce(minibosses, shadowAssassins, seen, candidate)
			&& minibosses.size() == 1 && shadowAssassins.size() == 1,
			"the same entity discovered by both scanner paths is added to Miniboss only once");
		check(!DungeonMobEspSupport.isStarredGroupTarget(false, true),
			"an SA assigned to Miniboss is excluded from the generic Starred target group");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException(message);
	}
}
