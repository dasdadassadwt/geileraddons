package geiler.addons.client.module.impl;

import geiler.addons.client.dungeon.DungeonRoomTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Correlates player-info and player-spawn packets for the dungeon's exact Shadow Assassin profile. */
final class ShadowAssassinEntityTracker {
	private static final int MAX_RECOGNIZED_PROFILES = 128;
	private static final int MAX_PENDING_SPAWNS = 32;
	private static final int MAX_TRACKED_ENTITIES = 32;
	private static final long MAX_PENDING_PACKET_GAP = 512L;
	private final LinkedHashMap<UUID, Long> recognizedProfiles = new LinkedHashMap<>();
	private final LinkedHashMap<UUID, PendingSpawn> pendingSpawns = new LinkedHashMap<>();
	private final LinkedHashMap<Integer, TrackedEntity> trackedEntities = new LinkedHashMap<>();
	private long packetSequence;
	private ClientLevel level;
	private long playerInfoPackets;
	private long addPlayerInfoPackets;
	private long playerInfoEntries;
	private long addPlayerInfoEntries;
	private long exactProfileMatches;
	private long unrelatedProfiles;
	private long missingProfileNames;
	private long playerSpawnPackets;
	private long nonPlayerSpawnPackets;
	private long matchedAtSpawn;
	private long matchedAtProfile;
	private long pendingSpawnsQueued;
	private long pendingSpawnsExpired;
	private long despawnedEntities;
	private String lastPacketStage = "none";
	private ResolveDiagnostics lastResolveDiagnostics = ResolveDiagnostics.empty("not-resolved");

	void onPlayerInfoUpdate(boolean addPlayerAction, int entries) {
		playerInfoPackets++;
		playerInfoEntries += Math.max(0, entries);
		if (!addPlayerAction) {
			lastPacketStage = "player-info-without-add-player-action";
			return;
		}
		addPlayerInfoPackets++;
		addPlayerInfoEntries += Math.max(0, entries);
		lastPacketStage = entries > 0 ? "add-player-entries-awaiting-profile-match" : "add-player-action-empty";
	}

	void onPlayerInfo(UUID profileId, String profileName) {
		packetSequence++;
		expirePendingSpawns();
		if (profileId == null) {
			missingProfileNames++;
			lastPacketStage = "player-info-entry-missing-profile-id";
			return;
		}
		if (!DungeonMobEspSupport.isShadowAssassinProfileName(profileName)) {
			if (profileName == null || profileName.isBlank()) missingProfileNames++;
			else unrelatedProfiles++;
			recognizedProfiles.remove(profileId);
			pendingSpawns.remove(profileId);
			trackedEntities.entrySet().removeIf(entry -> entry.getValue().profileId().equals(profileId));
			lastPacketStage = profileName == null || profileName.isBlank()
				? "profile-name-missing" : "profile-name-mismatch";
			return;
		}
		exactProfileMatches++;
		recognizedProfiles.put(profileId, packetSequence);
		PendingSpawn pending = pendingSpawns.remove(profileId);
		if (pending != null) {
			rememberEntity(pending.entityId(), profileId);
			matchedAtProfile++;
			lastPacketStage = "profile-match-associated-pending-spawn";
		} else {
			lastPacketStage = "exact-profile-match-awaiting-spawn";
		}
		trimOldest(recognizedProfiles, MAX_RECOGNIZED_PROFILES);
	}

	void onPlayerSpawn(UUID entityUuid, int entityId, boolean playerEntity) {
		packetSequence++;
		expirePendingSpawns();
		if (!playerEntity) {
			nonPlayerSpawnPackets++;
			lastPacketStage = "add-entity-not-player";
			return;
		}
		playerSpawnPackets++;
		if (entityUuid == null || entityId < 0) {
			lastPacketStage = entityUuid == null ? "player-spawn-missing-uuid" : "player-spawn-invalid-entity-id";
			return;
		}
		if (recognizedProfiles.containsKey(entityUuid)) {
			rememberEntity(entityId, entityUuid);
			matchedAtSpawn++;
			lastPacketStage = "spawn-associated-existing-profile";
		}
		else {
			pendingSpawns.put(entityUuid, new PendingSpawn(entityId, packetSequence));
			pendingSpawnsQueued++;
			trimOldest(pendingSpawns, MAX_PENDING_SPAWNS);
			lastPacketStage = "spawn-pending-profile-match";
		}
	}

	void onEntityRemoved(int entityId) {
		despawnedEntities++;
		lastPacketStage = "entity-removed";
		trackedEntities.remove(entityId);
		pendingSpawns.entrySet().removeIf(entry -> entry.getValue().entityId() == entityId);
	}

	List<LivingEntity> resolve(Minecraft client, int range,
		DungeonRoomTracker.MapRoomFootprint footprint, boolean currentRoomOnly) {
		if (client == null || client.level == null || client.player == null) {
			lastResolveDiagnostics = ResolveDiagnostics.empty("client-level-or-player-unavailable");
			return List.of();
		}
		onWorld(client.level);
		ClientLevel currentLevel = client.level;
		List<LivingEntity> result = new ArrayList<>();
		int considered = 0;
		int entityMissing = 0;
		int notPlayer = 0;
		int removed = 0;
		int uuidMismatch = 0;
		int notAlive = 0;
		int outOfRange = 0;
		int footprintUnavailable = 0;
		int outsideRoom = 0;
		Iterator<Map.Entry<Integer, TrackedEntity>> iterator = trackedEntities.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<Integer, TrackedEntity> entry = iterator.next();
			considered++;
			Entity resolved = currentLevel.getEntity(entry.getKey());
			TrackedEntity tracked = entry.getValue();
			if (resolved == null) {
				entityMissing++;
				iterator.remove();
				continue;
			}
			if (!(resolved instanceof Player body)) {
				notPlayer++;
				iterator.remove();
				continue;
			}
			if (body.isRemoved()) {
				removed++;
				iterator.remove();
				continue;
			}
			if (!tracked.profileId().equals(body.getUUID())) {
				uuidMismatch++;
				iterator.remove();
				continue;
			}
			if (!body.isAlive()) {
				notAlive++;
				iterator.remove();
				continue;
			}
			int boundedRange = Math.max(1, Math.min(128, range));
			if (body.distanceToSqr(client.player) > (double) boundedRange * boundedRange) {
				outOfRange++;
				continue;
			}
			if (!DungeonMobEspSupport.isAllowedByRoomFilter(currentRoomOnly, footprint, body.blockPosition())) {
				if ("confirmed-room-footprint-unavailable".equals(
					DungeonMobEspSupport.roomFilterRejection(currentRoomOnly, footprint, body.blockPosition()))) {
					footprintUnavailable++;
				} else {
					outsideRoom++;
				}
				continue;
			}
			result.add(body);
		}
		lastResolveDiagnostics = new ResolveDiagnostics(considered, result.size(), entityMissing, notPlayer,
			removed, uuidMismatch, notAlive, outOfRange, footprintUnavailable, outsideRoom, "");
		return List.copyOf(result);
	}

	boolean isTrackedEntity(int entityId) { return trackedEntities.containsKey(entityId); }
	int trackedCount() { return trackedEntities.size(); }
	String packetDiagnostics() {
		return "playerInfoPackets=" + playerInfoPackets + ", addPlayerPackets=" + addPlayerInfoPackets
			+ ", entries=" + playerInfoEntries + "/add=" + addPlayerInfoEntries
			+ ", exactProfiles=" + exactProfileMatches + ", otherProfiles=" + unrelatedProfiles
			+ ", missingProfile=" + missingProfileNames + ", playerSpawns=" + playerSpawnPackets
			+ ", nonPlayerSpawns=" + nonPlayerSpawnPackets + ", matchedAtSpawn=" + matchedAtSpawn
			+ ", matchedAtProfile=" + matchedAtProfile + ", pendingQueued=" + pendingSpawnsQueued
			+ ", pendingExpired=" + pendingSpawnsExpired + ", despawned=" + despawnedEntities
			+ ", tracked=" + trackedEntities.size() + ", pending=" + pendingSpawns.size()
			+ ", last=" + lastPacketStage;
	}
	String resolveDiagnostics() { return lastResolveDiagnostics.summary(); }

	void onWorld(ClientLevel currentLevel) {
		if (level == currentLevel) return;
		clear();
		level = currentLevel;
	}

	void clear() {
		recognizedProfiles.clear();
		pendingSpawns.clear();
		trackedEntities.clear();
		packetSequence = 0L;
		playerInfoPackets = 0L;
		addPlayerInfoPackets = 0L;
		playerInfoEntries = 0L;
		addPlayerInfoEntries = 0L;
		exactProfileMatches = 0L;
		unrelatedProfiles = 0L;
		missingProfileNames = 0L;
		playerSpawnPackets = 0L;
		nonPlayerSpawnPackets = 0L;
		matchedAtSpawn = 0L;
		matchedAtProfile = 0L;
		pendingSpawnsQueued = 0L;
		pendingSpawnsExpired = 0L;
		despawnedEntities = 0L;
		lastPacketStage = "none";
		lastResolveDiagnostics = ResolveDiagnostics.empty("not-resolved");
		level = null;
	}

	private void rememberEntity(int entityId, UUID profileId) {
		// One profile UUID identifies one live NPC body. Repeated add/spawn events can arrive while
		// the client is reconciling a respawn; replace both stale IDs and duplicate IDs atomically.
		trackedEntities.entrySet().removeIf(entry -> entry.getKey() == entityId
			|| entry.getValue().profileId().equals(profileId));
		trackedEntities.put(entityId, new TrackedEntity(profileId));
		trimOldest(trackedEntities, MAX_TRACKED_ENTITIES);
	}

	private void expirePendingSpawns() {
		Iterator<Map.Entry<UUID, PendingSpawn>> iterator = pendingSpawns.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, PendingSpawn> entry = iterator.next();
			if (packetSequence - entry.getValue().packetSequence() > MAX_PENDING_PACKET_GAP) {
				pendingSpawnsExpired++;
				iterator.remove();
			}
		}
	}

	private static <K, V> void trimOldest(LinkedHashMap<K, V> values, int maximum) {
		while (values.size() > maximum) {
			Iterator<K> iterator = values.keySet().iterator();
			if (!iterator.hasNext()) return;
			iterator.next();
			iterator.remove();
		}
	}

	private record PendingSpawn(int entityId, long packetSequence) { }

	private record ResolveDiagnostics(int trackedCandidates, int accepted, int entityMissing, int notPlayer,
		int removed, int uuidMismatch, int notAlive, int outOfRange, int footprintUnavailable,
		int outsideRoom, String reason) {
		static ResolveDiagnostics empty(String reason) {
			return new ResolveDiagnostics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, reason);
		}

		String summary() {
			return "trackedCandidates=" + trackedCandidates + ", accepted=" + accepted
				+ ", entityMissing=" + entityMissing + ", notPlayer=" + notPlayer
				+ ", removed=" + removed + ", uuidMismatch=" + uuidMismatch
				+ ", notAlive=" + notAlive + ", rangeRejected=" + outOfRange
				+ ", noRoomFootprint=" + footprintUnavailable + ", outsideRoom=" + outsideRoom
				+ (reason == null || reason.isBlank() ? "" : ", resolveExit=" + reason);
		}
	}

	private static final class TrackedEntity {
		private final UUID profileId;

		private TrackedEntity(UUID profileId) { this.profileId = profileId; }
		private UUID profileId() { return profileId; }
	}
}
