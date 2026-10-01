package geiler.addons.client.module.impl;

import com.mojang.blaze3d.vertex.PoseStack;
import geiler.addons.client.config.GeilerAddonsLog;
import geiler.addons.client.dungeon.DungeonContextTracker;
import geiler.addons.client.dungeon.DungeonMapRoomDetector;
import geiler.addons.client.dungeon.DungeonRoomTracker;
import geiler.addons.client.entity.ClientEntitySnapshot;
import geiler.addons.client.entity.Nameplates;
import geiler.addons.client.farming.PestAnimation;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ChoiceSetting;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.render.EspRenderer;
import geiler.addons.client.render.GeilerAddonsRenderTypes;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import org.joml.Vector3fc;

import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** Independent starred-mob and miniboss highlights collected under one dungeon module. */
public final class DungeonMobEspModule extends Module {
	public static final DungeonMobEspModule INSTANCE = new DungeonMobEspModule(new Settings());
	private static final float STARRED_DEFAULT_LINE_WIDTH = 2.0f;
	private static final float MINIBOSS_DEFAULT_LINE_WIDTH = 2.0f;
	private final Settings settings;
	private final BooleanSetting debug;
	private volatile List<LivingEntity> starredMatches = List.of();
	private volatile List<LivingEntity> minibossMatches = List.of();
	private volatile List<LivingEntity> shadowAssassinMatches = List.of();
	private volatile List<LivingEntity> stationaryFelSkulls = List.of();
	private volatile List<LivingEntity> movingFelMatches = List.of();
	private volatile List<DungeonMobTargetMemory.Target> rememberedTargets = List.of();
	private final Map<UUID, KnownFelBody> knownFelBodies = new HashMap<>();
	private final DungeonMobTargetMemory targetMemory = new DungeonMobTargetMemory();
	private final ShadowAssassinEntityTracker shadowAssassinTracker = new ShadowAssassinEntityTracker();
	private DungeonRoomTracker.MapRoomFootprint activeRoomFootprint;
	private ClientLevel lastLevel;
	private String lastRoomKey;
	private String lastClearedRoomKey;
	private boolean packetDungeonWasActive;
	private String lastDiagnostic = "";
	private long lastDiagnosticTick = Long.MIN_VALUE;
	private String lastTracerDiagnostic = "";
	private long lastTracerDiagnosticTick = Long.MIN_VALUE;

	private DungeonMobEspModule(Settings settings) {
		super("Dungeon Mob ESP", "Highlights starred dungeon mobs and named minibosses.", Category.F7,
			settings.starredEnabled, settings.starredOnlyRoom, settings.starredRange, settings.depthCheck, settings.starredStyle,
			settings.starredOutline, settings.starredFill, settings.starredLineWidth,
			settings.starredRingCount, settings.starredRingHeight, settings.starredRingRadius,
			settings.starredRingSpeed, settings.starredRingLineWidth,
			settings.minibossEnabled, settings.minibossRange, settings.minibossOnlyRoom,
			settings.minibossStyle, settings.minibossOnlyStarred, settings.minibossOutline,
			settings.minibossFill, settings.minibossLineWidth, settings.minibossTracer,
			settings.minibossTracerColor, settings.minibossTracerWidth, settings.minibossRingCount,
			settings.minibossRingHeight, settings.minibossRingRadius, settings.minibossRingSpeed,
			settings.minibossRingLineWidth, settings.felsEnabled, settings.felOnlyRoom,
			settings.highlightFels, settings.highlightFelSkulls, settings.felStyle, settings.felOutline,
			settings.felFill, settings.felLineWidth, settings.felRingCount, settings.felRingHeight,
			settings.felRingRadius, settings.felRingSpeed, settings.felRingLineWidth,
			settings.felSkullTracer, settings.felTracerColor, settings.felTracerWidth, settings.debug);
		this.settings = settings;
		this.debug = settings.debug;
		group(
			new SettingGroup(null, settings.depthCheck, settings.debug),
			SettingGroup.switched("Fels", settings.felsEnabled, settings.felOnlyRoom,
				settings.highlightFels, settings.highlightFelSkulls, settings.felStyle,
				settings.felOutline, settings.felFill, settings.felLineWidth, settings.felRingCount,
				settings.felRingHeight, settings.felRingRadius, settings.felRingSpeed,
				settings.felRingLineWidth, settings.felSkullTracer, settings.felTracerColor,
				settings.felTracerWidth),
			SettingGroup.switched("Starred Mobs", settings.starredEnabled, settings.starredOnlyRoom,
				settings.starredRange, settings.starredStyle,
				settings.starredOutline, settings.starredFill, settings.starredLineWidth,
				settings.starredRingCount, settings.starredRingHeight, settings.starredRingRadius,
				settings.starredRingSpeed, settings.starredRingLineWidth),
			SettingGroup.switched("Minibosses", settings.minibossEnabled, settings.minibossRange, settings.minibossOnlyRoom,
				settings.minibossOnlyStarred, settings.minibossStyle, settings.minibossOutline,
				settings.minibossFill, settings.minibossLineWidth, settings.minibossTracer,
				settings.minibossTracerColor, settings.minibossTracerWidth, settings.minibossRingCount,
				settings.minibossRingHeight, settings.minibossRingRadius, settings.minibossRingSpeed,
				settings.minibossRingLineWidth));
	}

	@Override
	public boolean isSettingVisible(geiler.addons.client.module.Setting setting) {
		if (setting == settings.starredRingCount || setting == settings.starredRingHeight
			|| setting == settings.starredRingRadius || setting == settings.starredRingSpeed
			|| setting == settings.starredRingLineWidth) return "Ring".equals(settings.starredStyle.value());
		if (setting == settings.minibossRingCount || setting == settings.minibossRingHeight
			|| setting == settings.minibossRingRadius || setting == settings.minibossRingSpeed
			|| setting == settings.minibossRingLineWidth) return "Ring".equals(settings.minibossStyle.value());
		if (setting == settings.felRingCount || setting == settings.felRingHeight
			|| setting == settings.felRingRadius || setting == settings.felRingSpeed
			|| setting == settings.felRingLineWidth) return "Ring".equals(settings.felStyle.value());
		if (setting == settings.starredLineWidth) return !"Ring".equals(settings.starredStyle.value());
		if (setting == settings.minibossLineWidth) return !"Ring".equals(settings.minibossStyle.value());
		if (setting == settings.felLineWidth) return !"Ring".equals(settings.felStyle.value());
		if (setting == settings.minibossTracerColor || setting == settings.minibossTracerWidth) {
			return settings.minibossTracer.value();
		}
		if (setting == settings.felSkullTracer) return settings.highlightFelSkulls.value();
		if (setting == settings.felTracerColor || setting == settings.felTracerWidth) {
			return settings.highlightFelSkulls.value() && settings.felSkullTracer.value();
		}
		return true;
	}

	@Override public boolean isActive() { return isEnabled() && DungeonContextTracker.isMobEspPhase(); }
	@Override public String inactiveReason() {
		return isEnabled() && !DungeonContextTracker.isMobEspPhase()
			? DungeonContextTracker.inDungeon() ? "ESP is unavailable after boss entry" : "Waiting for a dungeon entry"
			: null;
	}

	public void tick() {
		GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.F7, name(), settings.debug.value());
		Minecraft client = Minecraft.getInstance();
		boolean dungeonPresent = DungeonContextTracker.inDungeon();
		if (packetDungeonWasActive && !dungeonPresent) shadowAssassinTracker.clear();
		packetDungeonWasActive = dungeonPresent;
		if (!isActive() || client.level == null || client.player == null) {
			starredMatches = List.of();
			minibossMatches = List.of();
			shadowAssassinMatches = List.of();
			stationaryFelSkulls = List.of();
			movingFelMatches = List.of();
			rememberedTargets = List.of();
			knownFelBodies.clear();
			targetMemory.clear();
			lastRoomKey = null;
			lastClearedRoomKey = null;
			activeRoomFootprint = null;
			if (client.level == null) shadowAssassinTracker.clear();
			lastLevel = client.level;
			reportDebug("inactive; enabled=" + isEnabled() + ", dungeon=" + DungeonContextTracker.inDungeon()
				+ ", mobEspPhase=" + DungeonContextTracker.isMobEspPhase());
			return;
		}
		if (lastLevel != client.level) {
			lastLevel = client.level;
			shadowAssassinTracker.onWorld(client.level);
			starredMatches = List.of();
			minibossMatches = List.of();
			shadowAssassinMatches = List.of();
			stationaryFelSkulls = List.of();
			movingFelMatches = List.of();
			knownFelBodies.clear();
			targetMemory.clear();
			rememberedTargets = List.of();
			lastRoomKey = null;
			lastClearedRoomKey = null;
			activeRoomFootprint = null;
			lastTracerDiagnostic = "";
			lastTracerDiagnosticTick = Long.MIN_VALUE;
		}
		long gameTick = client.level.getGameTime();
		activeRoomFootprint = updateRoomFootprint(client);
		String roomKey = currentRoomKey(client);
		if (roomKey == null || !roomKey.equals(lastRoomKey)) {
			knownFelBodies.clear();
			targetMemory.clear();
			rememberedTargets = List.of();
			lastRoomKey = roomKey;
		}
		boolean roomCleared = DungeonMapRoomDetector.currentRoomCleared();
		if (roomCleared) {
			if (roomKey != null && !roomKey.equals(lastClearedRoomKey)) {
				knownFelBodies.clear();
				targetMemory.clear();
				rememberedTargets = List.of();
				lastClearedRoomKey = roomKey;
			}
		} else {
			lastClearedRoomKey = null;
		}
		boolean scanForStarEvidence = DungeonMobEspSupport.shouldClassifyFelsForStarFilter(
			settings.starredEnabled.value(), settings.felsEnabled.value());
		List<LivingEntity> felSkullMarkers = !roomCleared && settings.felsEnabled.value() && settings.highlightFelSkulls.value()
			? DungeonMobEspSupport.findFelSkullMarkers(client, settings.starredRange.intValue(),
				settings.felOnlyRoom.value(), activeRoomFootprint).stream()
				.filter(marker -> !settings.felOnlyRoom.value() || isInCurrentRoom(client, marker))
				.map(marker -> (LivingEntity) marker).toList()
			: List.of();
		DungeonMobEspSupport.ScanResult starredScan = scanForStarEvidence
			? DungeonMobEspSupport.scan(client, settings.starredRange.intValue(), settings.starredOnlyRoom.value(),
				DungeonMobEspSupport::isStarred, true, debug.value(), activeRoomFootprint)
			: DungeonMobEspSupport.ScanResult.empty("starred group disabled");
		// Identify Fel bodies even when the Fel group is off if Starred ESP is on, so starred
		// Fels cannot fall back into the generic Starred group. Only create Fel targets/tracks
		// when the user has enabled the Fel group.
		List<DungeonMobEspSupport.FelCandidate> classifiedFels = scanForStarEvidence
			? DungeonMobEspSupport.findFels(client, settings.starredRange.intValue(),
				settings.felsEnabled.value() && settings.felOnlyRoom.value(), activeRoomFootprint)
			: List.of();
		Set<LivingEntity> felBodies = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		classifiedFels.forEach(candidate -> felBodies.add(candidate.entity()));
		List<DungeonMobEspSupport.FelCandidate> fels = settings.felsEnabled.value() && !roomCleared
			? new java.util.ArrayList<>(classifiedFels) : new java.util.ArrayList<>();
		Set<UUID> identifiedFels = new HashSet<>();
		for (DungeonMobEspSupport.FelCandidate candidate : fels) {
			LivingEntity entity = candidate.entity();
			identifiedFels.add(entity.getUUID());
			if (!roomCleared && roomKey != null && isInCurrentRoom(client, entity)) {
				KnownFelBody known = knownFelBodies.computeIfAbsent(entity.getUUID(), ignored -> new KnownFelBody(entity));
				known.observe(entity, candidate.starred());
			}
		}
		if (!roomCleared && settings.felsEnabled.value()) {
			var knownIterator = knownFelBodies.entrySet().iterator();
			while (knownIterator.hasNext()) {
				Map.Entry<UUID, KnownFelBody> entry = knownIterator.next();
				KnownFelBody known = entry.getValue();
				Entity loaded = client.level.getEntity(known.entityId);
				if (!(loaded instanceof LivingEntity body) || !known.uuid.equals(loaded.getUUID()) || !body.isAlive()) {
					knownIterator.remove();
					continue;
				}
				known.observe(body, known.starred);
				if (identifiedFels.contains(known.uuid)
					|| body.distanceToSqr(client.player) > (double) settings.starredRange.intValue() * settings.starredRange.intValue()
					|| settings.felOnlyRoom.value() && !isInCurrentRoom(client, body)) continue;
				fels.add(new DungeonMobEspSupport.FelCandidate(body, known.starred));
				identifiedFels.add(known.uuid);
			}
		}
		while (knownFelBodies.size() > 512) {
			var oldest = knownFelBodies.keySet().iterator();
			if (!oldest.hasNext()) break;
			oldest.next();
			oldest.remove();
		}
		List<LivingEntity> starredFelBodies = new java.util.ArrayList<>();
		for (DungeonMobEspSupport.FelCandidate candidate : fels) {
			LivingEntity entity = candidate.entity();
			felBodies.add(entity);
			if (DungeonMobEspSupport.shouldHighlightFel(
				settings.highlightFels.value(), candidate.starred()))
				starredFelBodies.add(entity);
		}
		stationaryFelSkulls = List.copyOf(felSkullMarkers);
		DungeonMobEspSupport.ScanResult minibossScan = settings.minibossEnabled.value()
			? DungeonMobEspSupport.scan(client, settings.minibossRange.intValue(), settings.minibossOnlyRoom.value(),
				name -> DungeonMobEspSupport.isEligibleMinibossName(name, settings.minibossOnlyStarred.value()),
				false, debug.value(), activeRoomFootprint)
			: DungeonMobEspSupport.ScanResult.empty("miniboss group disabled");
		List<LivingEntity> shadowAssassins = new java.util.ArrayList<>(minibossScan.shadowAssassins());
		List<LivingEntity> minibosses = new java.util.ArrayList<>(minibossScan.matches());
		Set<LivingEntity> shadowAssassinSet = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		shadowAssassinSet.addAll(shadowAssassins);
		int packetShadowAssassins = 0;
		if (settings.minibossEnabled.value()) {
			for (LivingEntity candidate : shadowAssassinTracker.resolve(client, settings.minibossRange.intValue(),
				activeRoomFootprint, settings.minibossOnlyRoom.value())) {
				// Packet identity can recover an unlabelled Shadow Assassin, but it cannot prove the
				// star marker. Under Only Starred, accept packet recovery only when the ordinary scan
				// already established that evidence.
				if (settings.minibossOnlyStarred.value() && !shadowAssassinSet.contains(candidate)) continue;
				if (DungeonMobEspSupport.addShadowAssassinCandidateOnce(
					minibosses, shadowAssassins, shadowAssassinSet, candidate)) packetShadowAssassins++;
			}
		}
		List<LivingEntity> starred = settings.starredEnabled.value()
			? new java.util.ArrayList<>(starredScan.matches().stream()
				.filter(entity -> DungeonMobEspSupport.isStarredGroupTarget(
					felBodies.contains(entity), shadowAssassinSet.contains(entity))).toList())
			: new java.util.ArrayList<>();
		starredMatches = List.copyOf(starred);
		minibossMatches = List.copyOf(minibosses);
		shadowAssassinMatches = List.copyOf(shadowAssassins);
		movingFelMatches = !roomCleared && settings.felsEnabled.value() && settings.highlightFels.value()
			? starredFelBodies.stream().filter(entity -> !settings.felOnlyRoom.value() || isInCurrentRoom(client, entity)).toList()
			: List.of();
		List<DungeonMobTargetMemory.Target> liveTargets = new java.util.ArrayList<>();
		if (roomKey != null && !roomCleared) {
			for (LivingEntity entity : starredMatches) if (isInCurrentRoom(client, entity)) liveTargets.add(memoryTarget(entity, DungeonMobTargetMemory.Group.STARRED,
				roomKey, gameTick, false));
			for (LivingEntity entity : minibossMatches) if (isInCurrentRoom(client, entity)) liveTargets.add(memoryTarget(entity, DungeonMobTargetMemory.Group.MINIBOSS,
				roomKey, gameTick, shadowAssassinSet.contains(entity)));
			for (LivingEntity entity : stationaryFelSkulls) if (isInCurrentRoom(client, entity)) liveTargets.add(memoryTarget(entity, DungeonMobTargetMemory.Group.FEL_SKULL,
				roomKey, gameTick, false));
			for (LivingEntity entity : movingFelMatches) if (isInCurrentRoom(client, entity)) liveTargets.add(memoryTarget(entity,
				DungeonMobTargetMemory.Group.FEL_MOVING, roomKey, gameTick, false));
		}
		if (!settings.starredEnabled.value()) targetMemory.clearGroup(DungeonMobTargetMemory.Group.STARRED);
		if (!settings.minibossEnabled.value()) targetMemory.clearGroup(DungeonMobTargetMemory.Group.MINIBOSS);
		if (!settings.felsEnabled.value() || !settings.highlightFelSkulls.value()) targetMemory.clearGroup(DungeonMobTargetMemory.Group.FEL_SKULL);
		if (!settings.felsEnabled.value() || !settings.highlightFels.value()) targetMemory.clearGroup(DungeonMobTargetMemory.Group.FEL_MOVING);
		if (roomCleared) {
			targetMemory.clearGroup(DungeonMobTargetMemory.Group.FEL_SKULL);
			targetMemory.clearGroup(DungeonMobTargetMemory.Group.FEL_MOVING);
		}
		rememberedTargets = roomKey == null || roomCleared ? List.of() : targetMemory.update(liveTargets, roomKey, gameTick,
			target -> rememberedPresence(client.level, target), target -> refreshRememberedTarget(client, target, roomKey, gameTick));
		reportDebug("starred{" + starredScan.summary() + "}; miniboss{" + minibossScan.summary()
			+ "; SA scan{" + minibossScan.shadowAssassinDiagnostics().summary() + "}"
			+ "}; fullShadowAssassins=" + shadowAssassinMatches.size()
			+ " (packet-associated=" + packetShadowAssassins + ")"
			+ "; SA packets{" + shadowAssassinTracker.packetDiagnostics() + "}"
			+ "; SA resolve{" + shadowAssassinTracker.resolveDiagnostics() + "}"
			+ "; starredFels=" + starredFelBodies.size() + ", felHeadMarkers=" + stationaryFelSkulls.size()
			+ "; depth=" + settings.depthCheck.value() + ", minibossTracer="
			+ settings.minibossTracer.value() + ", minibossStyle=" + settings.minibossStyle.value());
	}

	/** Called by the client packet mixin after Vanilla handles each player-info update. */
	public void onPlayerInfoUpdate(ClientboundPlayerInfoUpdatePacket packet) {
		if (packet == null) return;
		shadowAssassinTracker.onWorld(Minecraft.getInstance().level);
		boolean addPlayerAction = packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER);
		shadowAssassinTracker.onPlayerInfoUpdate(addPlayerAction, packet.entries().size());
		if (!addPlayerAction) return;
		for (ClientboundPlayerInfoUpdatePacket.Entry entry : packet.entries()) {
			var profile = entry.profile();
			shadowAssassinTracker.onPlayerInfo(entry.profileId(), profile == null ? null : profile.name());
		}
	}

	/** Called after Vanilla inserts the entity so resolution can be retried if it is not available yet. */
	public void onEntitySpawn(ClientboundAddEntityPacket packet) {
		if (packet == null) return;
		shadowAssassinTracker.onWorld(Minecraft.getInstance().level);
		shadowAssassinTracker.onPlayerSpawn(packet.getUUID(), packet.getId(),
			net.minecraft.world.entity.EntityType.PLAYER.equals(packet.getType()));
	}

	/** Despawn packets are authoritative; entity IDs may be reused later in the same level. */
	public void onEntitiesRemoved(ClientboundRemoveEntitiesPacket packet) {
		if (packet == null) return;
		shadowAssassinTracker.onWorld(Minecraft.getInstance().level);
		for (int entityId : packet.getEntityIds()) shadowAssassinTracker.onEntityRemoved(entityId);
	}

	public void clearShadowAssassinPackets() {
		shadowAssassinTracker.clear();
	}

	private void reportDebug(String state) {
		if (!debug.value() || !isEnabled()) {
			lastDiagnostic = "";
			lastDiagnosticTick = Long.MIN_VALUE;
			return;
		}
		Minecraft client = Minecraft.getInstance();
		long tick = client.level == null ? 0 : client.level.getGameTime();
		// Packet-stage counters can change during entity-spawn bursts. Keep diagnostics useful without
		// writing a new log line for every packet in the same short interval.
		if (lastDiagnosticTick != Long.MIN_VALUE && tick - lastDiagnosticTick < 20) return;
		if (state.equals(lastDiagnostic) && tick - lastDiagnosticTick < 100) return;
		lastDiagnostic = state;
		lastDiagnosticTick = tick;
		GeilerAddonsLog.write(Category.F7, name(), tick, "mob ESP: " + state);
	}

	private DungeonRoomTracker.MapRoomFootprint updateRoomFootprint(Minecraft client) {
		return client == null || client.level == null || client.player == null
			? null : DungeonMobEspSupport.currentConfirmedRoomFootprint();
	}

	private String currentRoomKey(Minecraft client) {
		if (client == null || client.player == null) return null;
		if (activeRoomFootprint != null && !activeRoomFootprint.footprintKey().isBlank()) {
			return activeRoomFootprint.footprintKey();
		}
		return null;
	}

	private boolean isInCurrentRoom(Minecraft client, LivingEntity entity) {
		if (client == null || client.player == null || entity == null) return false;
		return activeRoomFootprint != null && activeRoomFootprint.contains(entity.blockPosition());
	}

	/** Re-checks the live room at render time so a published Fel snapshot cannot leak across rooms. */
	private List<LivingEntity> currentRoomFels(Minecraft client) {
		if (!settings.felOnlyRoom.value()) return movingFelMatches;
		return movingFelMatches.stream().filter(entity -> isInCurrentRoom(client, entity)).toList();
	}

	private static DungeonMobTargetMemory.Target memoryTarget(LivingEntity entity, DungeonMobTargetMemory.Group group,
		String roomKey, long gameTick, boolean fullShadowAssassin) {
		if (group == DungeonMobTargetMemory.Group.FEL_SKULL) {
			return new DungeonMobTargetMemory.Target(entity.getUUID(), entity.getId(), group,
				String.valueOf(entity.getType()), DungeonMobEspSupport.plainName(entity),
				FelSkullMarkerGeometry.bounds(entity), roomKey, gameTick, false);
		}
		AABB box = fullShadowAssassin ? DungeonMobEspSupport.fullShadowAssassinBounds(entity) : entity.getBoundingBox();
		return new DungeonMobTargetMemory.Target(entity.getUUID(), entity.getId(), group,
			String.valueOf(entity.getType()), DungeonMobEspSupport.plainName(entity),
			new DungeonMobTargetMemory.Bounds(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ),
			roomKey, gameTick, fullShadowAssassin);
	}

	private void renderFelSkullMarker(PoseStack pose, MultiBufferSource buffers, Vec3 camera,
		Vector3fc forward, DungeonMobTargetMemory.Bounds bounds, double elapsedTicks) {
		double x = bounds.minX() - camera.x;
		double y = bounds.minY() - camera.y;
		double z = bounds.minZ() - camera.z;
		AABB box = new AABB(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ());
		double[] ringHeights = null;
		if ("Ring".equals(settings.felStyle.value())) {
			double feet = box.minY + 0.05;
			double head = Math.min(box.maxY - 0.05, feet + settings.felRingHeight.value());
			if (head < feet) head = feet;
			ringHeights = PestAnimation.ringPositions(feet, head, elapsedTicks,
				settings.felRingSpeed.value(), settings.felRingCount.intValue());
			for (double ringY : ringHeights) EspRenderer.renderRing(pose, buffers, bounds.centerX() - camera.x,
				ringY - camera.y, bounds.centerZ() - camera.z, settings.felRingRadius.value(),
				settings.felOutline.argb(), settings.felRingLineWidth.value(), settings.depthCheck.value());
		} else if (!"2D Box".equals(settings.felStyle.value())) {
			int fillColor = "Chams".equals(settings.felStyle.value()) ? settings.felFill.argb() : 0;
			EspRenderer.renderBox(pose, buffers, x, y, z, box.getXsize(), box.getYsize(), box.getZsize(),
				fillColor, settings.felOutline.argb(), settings.felLineWidth.value(), settings.depthCheck.value());
		}
		if (!settings.felSkullTracer.value()) return;
		Vec3 targetPoint = new Vec3(bounds.centerX(), bounds.centerY(), bounds.centerZ());
		if (ringHeights != null) {
			PestAnimation.RingTarget ring = PestAnimation.closestRingTarget(bounds.centerX(), bounds.centerZ(),
				camera.x, camera.y, camera.z, settings.felRingRadius.value(), ringHeights);
			targetPoint = new Vec3(ring.x(), ring.y(), ring.z());
		}
		BlockEspTracerGeometry.Endpoints endpoints = BlockEspTracerGeometry.endpoints(camera, forward, targetPoint, 0.5);
		EspRenderer.renderLine(pose, buffers, endpoints.start().x, endpoints.start().y, endpoints.start().z,
			endpoints.end().x, endpoints.end().y, endpoints.end().z,
			settings.felTracerColor.argb(), settings.felTracerWidth.value(), settings.depthCheck.value());
	}

	private static DungeonMobTargetMemory.Presence rememberedPresence(ClientLevel level,
		DungeonMobTargetMemory.Target target) {
		if (level == null || target == null) return DungeonMobTargetMemory.Presence.LOADED_GONE;
		Entity loaded = level.getEntity(target.entityId());
		if (loaded != null && target.uuid().equals(loaded.getUUID())) {
			return !loaded.isRemoved() && loaded.isAlive() ? DungeonMobTargetMemory.Presence.LOADED_ALIVE
				: DungeonMobTargetMemory.Presence.LOADED_GONE;
		}
		DungeonMobTargetMemory.Bounds bounds = target.bounds();
		BlockPos anchor = new BlockPos((int) Math.floor(bounds.centerX()), (int) Math.floor(bounds.minY()),
			(int) Math.floor(bounds.centerZ()));
		return level.hasChunkAt(anchor) ? DungeonMobTargetMemory.Presence.LOADED_GONE
			: DungeonMobTargetMemory.Presence.UNLOADED;
	}

	private DungeonMobTargetMemory.Target refreshRememberedTarget(Minecraft client,
		DungeonMobTargetMemory.Target target, String roomKey, long gameTick) {
		if (client == null || client.level == null || target == null) return null;
		Entity loaded = client.level.getEntity(target.entityId());
		if (target.group() == DungeonMobTargetMemory.Group.FEL_SKULL
			&& (!(loaded instanceof ArmorStand marker) || !DungeonMobEspSupport.isFelSkullMarker(marker))) return null;
		if (!(loaded instanceof LivingEntity entity) || entity.isRemoved() || !entity.isAlive()
			|| !target.uuid().equals(entity.getUUID()) || !isInCurrentRoom(client, entity)) return null;
		boolean fullShadowAssassin = target.fullShadowAssassin();
		DungeonMobTargetMemory.Target refreshed = memoryTarget(entity, target.group(), roomKey, gameTick, fullShadowAssassin);
		return new DungeonMobTargetMemory.Target(refreshed.uuid(), refreshed.entityId(), refreshed.group(),
			refreshed.type(), target.label(), refreshed.bounds(), roomKey, gameTick, refreshed.fullShadowAssassin());
	}

	public void render(LevelRenderContext context) {
		if (!isActive() || starredMatches.isEmpty() && minibossMatches.isEmpty() && movingFelMatches.isEmpty()
			&& stationaryFelSkulls.isEmpty() && rememberedTargets.isEmpty()) return;
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.gameRenderer == null) return;
		Vec3 camera = client.gameRenderer.getMainCamera().position();
		float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		double elapsedTicks = client.level == null ? 0 : client.level.getGameTime() + partialTick;
		PoseStack pose = context.poseStack();
		MultiBufferSource.BufferSource buffers = context.bufferSource();
		boolean drew = renderWorldGroup(client, pose, buffers, camera, elapsedTicks, partialTick, starredMatches,
			settings.starredStyle, settings.depthCheck, settings.starredOutline, settings.starredFill,
			settings.starredLineWidth, null, null, null, settings.starredRingCount, settings.starredRingHeight,
			settings.starredRingRadius, settings.starredRingSpeed, settings.starredRingLineWidth,
			STARRED_DEFAULT_LINE_WIDTH, null, List.of());
		boolean felRoomClear = DungeonMapRoomDetector.currentRoomCleared();
		if (!felRoomClear && settings.felsEnabled.value() && settings.highlightFels.value()) {
			drew |= renderWorldGroup(client, pose, buffers, camera, elapsedTicks, partialTick, currentRoomFels(client),
				settings.starredStyle, settings.depthCheck, settings.starredOutline, settings.starredFill,
				settings.starredLineWidth, null, null, null, settings.starredRingCount, settings.starredRingHeight,
				settings.starredRingRadius, settings.starredRingSpeed, settings.starredRingLineWidth,
				STARRED_DEFAULT_LINE_WIDTH, null, List.of());
		}
		TracerRenderDiagnostics tracerDiagnostics = settings.debug.value()
			? new TracerRenderDiagnostics(settings.minibossTracer.value(), settings.minibossTracerColor.argb(),
				settings.minibossTracerColor.alpha(), settings.minibossTracerWidth.value(),
				settings.minibossStyle.value(), settings.minibossRange.intValue(),
				settings.minibossOnlyRoom.value(), settings.minibossOnlyStarred.value(),
				settings.depthCheck.value()) : null;
		drew |= renderWorldGroup(client, pose, buffers, camera, elapsedTicks, partialTick, minibossMatches,
			settings.minibossStyle, settings.depthCheck, settings.minibossOutline, settings.minibossFill,
			settings.minibossLineWidth, settings.minibossTracer, settings.minibossTracerColor,
			settings.minibossTracerWidth, settings.minibossRingCount, settings.minibossRingHeight,
			settings.minibossRingRadius, settings.minibossRingSpeed, settings.minibossRingLineWidth,
			MINIBOSS_DEFAULT_LINE_WIDTH, tracerDiagnostics, shadowAssassinMatches);
		drew |= renderRememberedGroup(client, pose, buffers, camera, elapsedTicks, partialTick, DungeonMobTargetMemory.Group.STARRED,
			settings.starredStyle, settings.depthCheck, settings.starredOutline, settings.starredFill,
			settings.starredLineWidth, null, null, null, settings.starredRingCount, settings.starredRingHeight,
			settings.starredRingRadius, settings.starredRingSpeed, settings.starredRingLineWidth, STARRED_DEFAULT_LINE_WIDTH);
		drew |= renderRememberedGroup(client, pose, buffers, camera, elapsedTicks, partialTick, DungeonMobTargetMemory.Group.MINIBOSS,
			settings.minibossStyle, settings.depthCheck, settings.minibossOutline, settings.minibossFill,
			settings.minibossLineWidth, settings.minibossTracer, settings.minibossTracerColor,
			settings.minibossTracerWidth, settings.minibossRingCount, settings.minibossRingHeight,
			settings.minibossRingRadius, settings.minibossRingSpeed, settings.minibossRingLineWidth, MINIBOSS_DEFAULT_LINE_WIDTH);
		if (!felRoomClear && settings.felsEnabled.value() && settings.highlightFels.value()) {
			drew |= renderRememberedGroup(client, pose, buffers, camera, elapsedTicks, partialTick, DungeonMobTargetMemory.Group.FEL_MOVING,
				settings.starredStyle, settings.depthCheck, settings.starredOutline, settings.starredFill,
				settings.starredLineWidth, null, null, null, settings.starredRingCount, settings.starredRingHeight,
				settings.starredRingRadius, settings.starredRingSpeed, settings.starredRingLineWidth, STARRED_DEFAULT_LINE_WIDTH);
		}
		if (drew) GeilerAddonsRenderTypes.endBatches(buffers);
		if (tracerDiagnostics != null) reportTracerDiagnostic(tracerDiagnostics, drew);
		if (!felRoomClear && !stationaryFelSkulls.isEmpty()) {
			Vector3fc forward = client.gameRenderer.getMainCamera().forwardVector();
			for (LivingEntity entity : stationaryFelSkulls) {
				if (entity.isRemoved() || !entity.isAlive()
					|| settings.felOnlyRoom.value() && !isInCurrentRoom(client, entity)
					|| entity.distanceToSqr(client.player) > (double) settings.starredRange.intValue() * settings.starredRange.intValue()) continue;
				renderFelSkullMarker(pose, buffers, camera, forward,
					FelSkullMarkerGeometry.bounds(entity), elapsedTicks);
			}
			GeilerAddonsRenderTypes.endBatches(buffers);
		}
		if (!felRoomClear && settings.felsEnabled.value() && settings.highlightFelSkulls.value()) {
			boolean drewFelGhost = false;
			Vector3fc forward = client.gameRenderer.getMainCamera().forwardVector();
			String currentRoom = currentRoomKey(client);
			double felRangeSquared = (double) settings.starredRange.intValue() * settings.starredRange.intValue();
			for (DungeonMobTargetMemory.Target target : rememberedTargets) {
				if (target.group() != DungeonMobTargetMemory.Group.FEL_SKULL
					|| !java.util.Objects.equals(currentRoom, target.roomKey())) continue;
				DungeonMobTargetMemory.Bounds bounds = target.bounds();
				if (client.player.distanceToSqr(bounds.centerX(), bounds.centerY(), bounds.centerZ()) > felRangeSquared) continue;
				renderFelSkullMarker(pose, buffers, camera, forward, bounds, elapsedTicks);
				drewFelGhost = true;
			}
			if (drewFelGhost) GeilerAddonsRenderTypes.endBatches(buffers);
		}
	}

	private boolean renderRememberedGroup(Minecraft client, PoseStack pose, MultiBufferSource.BufferSource buffers,
		Vec3 camera, double elapsedTicks, float partialTick, DungeonMobTargetMemory.Group group, ChoiceSetting style,
		BooleanSetting depth, ColorSetting outline, ColorSetting fill, NumberSetting lineWidth,
		BooleanSetting tracer, ColorSetting tracerColor, NumberSetting tracerWidth, NumberSetting ringCount,
		NumberSetting ringHeight, NumberSetting ringRadius, NumberSetting ringSpeed, NumberSetting ringLineWidth,
		float defaultLineWidth) {
		boolean drew = false;
		Vector3fc forward = client.gameRenderer.getMainCamera().forwardVector();
		String currentRoom = currentRoomKey(client);
		double maxRange = group == DungeonMobTargetMemory.Group.MINIBOSS
			? settings.minibossRange.intValue() : settings.starredRange.intValue();
		double maxRangeSquared = maxRange * maxRange;
		for (DungeonMobTargetMemory.Target target : rememberedTargets) {
			if (target.group() != group || !java.util.Objects.equals(currentRoom, target.roomKey())) continue;
			DungeonMobTargetMemory.Bounds bounds = target.bounds();
			boolean shadowAssassin = group == DungeonMobTargetMemory.Group.MINIBOSS
				&& DungeonMobEspSupport.isShadowAssassin(target.label());
			AABB box = shadowAssassin ? rememberedShadowAssassinBounds(client, target, partialTick) : null;
			if (shadowAssassin && box == null) continue;
			if (box == null) box = new AABB(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ());
			if (client.player.distanceToSqr(box.getCenter().x, box.getCenter().y, box.getCenter().z) > maxRangeSquared) continue;
			double centerX = box.getCenter().x, centerY = box.getCenter().y, centerZ = box.getCenter().z;
			double[] ringHeights = null;
			if ("Ring".equals(style.value())) {
				double feet = box.minY + 0.05;
				double head = Math.min(box.maxY - 0.05, feet + ringHeight.value());
				if (head < feet) head = feet;
				ringHeights = PestAnimation.ringPositions(feet, head, elapsedTicks, ringSpeed.value(), ringCount.intValue());
				for (double y : ringHeights) EspRenderer.renderRing(pose, buffers, centerX - camera.x,
					y - camera.y, centerZ - camera.z, ringRadius.value(), outline.argb(), ringLineWidth.value(), depth.value());
				drew = true;
			} else if (!"2D Box".equals(style.value())) {
				int fillColor = "Chams".equals(style.value()) ? fill.argb() : 0;
				EspRenderer.renderBox(pose, buffers, box.minX - camera.x, box.minY - camera.y,
					box.minZ - camera.z, box.getXsize(), box.getYsize(), box.getZsize(), fillColor,
					outline.argb(), lineWidth.value() > 0 ? lineWidth.value() : defaultLineWidth, depth.value());
				drew = true;
			}
			if (tracer != null && tracer.value()) {
				Vec3 targetPoint = new Vec3(centerX, centerY, centerZ);
				if (ringHeights != null) {
					PestAnimation.RingTarget ring = PestAnimation.closestRingTarget(centerX, centerZ,
						camera.x, camera.y, camera.z, ringRadius.value(), ringHeights);
					targetPoint = new Vec3(ring.x(), ring.y(), ring.z());
				}
				BlockEspTracerGeometry.Endpoints endpoints = BlockEspTracerGeometry.endpoints(camera, forward, targetPoint, 0.5);
				EspRenderer.renderLine(pose, buffers, endpoints.start().x, endpoints.start().y, endpoints.start().z,
					endpoints.end().x, endpoints.end().y, endpoints.end().z, tracerColor.argb(), tracerWidth.value(), depth.value());
				drew = true;
			}
		}
		return drew;
	}

	private static AABB rememberedShadowAssassinBounds(Minecraft client,
		DungeonMobTargetMemory.Target target, float partialTick) {
		if (client == null || client.level == null || target == null
			|| !target.fullShadowAssassin()) return null;
		Entity loaded = client.level.getEntity(target.entityId());
		if (!(loaded instanceof LivingEntity entity) || entity.isRemoved() || !entity.isAlive()
			|| !target.uuid().equals(entity.getUUID())) return null;
		return DungeonMobEspSupport.fullShadowAssassinBounds(entity, partialTick);
	}

	private boolean renderWorldGroup(Minecraft client, PoseStack pose,
		MultiBufferSource.BufferSource buffers, Vec3 camera, double elapsedTicks, float partialTick,
		List<LivingEntity> entities, ChoiceSetting style, BooleanSetting depth, ColorSetting outline,
		ColorSetting fill, NumberSetting lineWidth, BooleanSetting tracer, ColorSetting tracerColor,
		NumberSetting tracerWidth, NumberSetting ringCount, NumberSetting ringHeight,
		NumberSetting ringRadius, NumberSetting ringSpeed, NumberSetting ringLineWidth,
		float defaultLineWidth, TracerRenderDiagnostics tracerDiagnostics,
		List<LivingEntity> fullShadowAssassins) {
		boolean drew = false;
		Vector3fc forward = client.gameRenderer.getMainCamera().forwardVector();
		for (LivingEntity entity : entities) {
			boolean removed = entity.isRemoved();
			boolean alive = entity.isAlive();
			if (tracerDiagnostics != null) tracerDiagnostics.consider(removed, alive);
			if (removed || !alive) continue;
			AABB box = fullShadowAssassins.contains(entity)
				? DungeonMobEspSupport.fullShadowAssassinBounds(entity, partialTick) : entity.getBoundingBox();
			double centerX = box.getCenter().x;
			double centerZ = box.getCenter().z;
			double[] ringHeights = null;
			if ("Ring".equals(style.value())) {
				double feet = box.minY + 0.05;
				double head = Math.min(box.maxY - 0.05, feet + ringHeight.value());
				if (head < feet) head = feet;
				ringHeights = PestAnimation.ringPositions(feet, head, elapsedTicks, ringSpeed.value(), ringCount.intValue());
				for (double y : ringHeights) {
					EspRenderer.renderRing(pose, buffers, centerX - camera.x, y - camera.y,
						centerZ - camera.z, ringRadius.value(), outline.argb(), ringLineWidth.value(), depth.value());
					drew = true;
				}
			} else if (!"2D Box".equals(style.value())) {
				int fillColor = "Chams".equals(style.value()) ? fill.argb() : 0;
				EspRenderer.renderBox(pose, buffers, box.minX - camera.x, box.minY - camera.y,
					box.minZ - camera.z, box.getXsize(), box.getYsize(), box.getZsize(),
					fillColor, outline.argb(), lineWidth.value() > 0 ? lineWidth.value() : defaultLineWidth,
					depth.value());
				drew = true;
			}
			if (tracer != null && tracer.value()) {
				Vec3 target = new Vec3(centerX, box.getCenter().y, centerZ);
				if (ringHeights != null) {
					PestAnimation.RingTarget ring = PestAnimation.closestRingTarget(centerX, centerZ,
						camera.x, camera.y, camera.z, ringRadius.value(), ringHeights);
					target = new Vec3(ring.x(), ring.y(), ring.z());
				}
				BlockEspTracerGeometry.Endpoints endpoints = BlockEspTracerGeometry.endpoints(camera, forward, target, 0.5);
				boolean verticesSubmitted = hasLineVertices(endpoints.start(), endpoints.end());
				EspRenderer.renderLine(pose, buffers,
					endpoints.start().x, endpoints.start().y, endpoints.start().z,
					endpoints.end().x, endpoints.end().y, endpoints.end().z,
					tracerColor.argb(), tracerWidth.value(), depth.value());
				if (tracerDiagnostics != null) {
					tracerDiagnostics.submit(entity, endpoints.start(), endpoints.end(), verticesSubmitted);
				}
				drew = true;
			}
		}
		return drew;
	}

	private static boolean hasLineVertices(Vec3 start, Vec3 end) {
		Vec3 segment = end.subtract(start);
		float x = (float) segment.x;
		float y = (float) segment.y;
		float z = (float) segment.z;
		float length = (float) Math.sqrt(x * x + y * y + z * z);
		return length != 0.0f;
	}

	private void reportTracerDiagnostic(TracerRenderDiagnostics diagnostic, boolean batchesFlushed) {
		if (!debug.value() || !isEnabled()) {
			lastTracerDiagnostic = "";
			return;
		}
		Minecraft client = Minecraft.getInstance();
		long tick = client.level == null ? 0 : client.level.getGameTime();
		if (!lastTracerDiagnostic.isEmpty() && tick - lastTracerDiagnosticTick < 100) return;
		String state = diagnostic.describe(batchesFlushed);
		if (state.equals(lastTracerDiagnostic)) {
			lastTracerDiagnosticTick = tick;
			return;
		}
		lastTracerDiagnostic = state;
		lastTracerDiagnosticTick = tick;
		GeilerAddonsLog.write(Category.F7, name(), tick, "miniboss tracer: " + state);
	}

	public void renderHud(GuiGraphicsExtractor graphics) {
		if (!isActive()) return;
		Minecraft client = Minecraft.getInstance();
		boolean felRoomClear = DungeonMapRoomDetector.currentRoomCleared();
		var camera = client.gameRenderer.getMainCamera();
		float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		renderHudGroup(client, graphics, camera, partialTick, starredMatches, settings.starredStyle,
			settings.depthCheck, settings.starredOutline, settings.starredFill, settings.starredLineWidth, List.of());
		if (!felRoomClear && settings.felsEnabled.value() && settings.highlightFels.value()) {
			renderHudGroup(client, graphics, camera, partialTick, currentRoomFels(client), settings.starredStyle,
				settings.depthCheck, settings.starredOutline, settings.starredFill, settings.starredLineWidth, List.of());
		}
		if (!felRoomClear && settings.felsEnabled.value() && settings.highlightFelSkulls.value()
			&& "2D Box".equals(settings.felStyle.value())) {
			for (LivingEntity entity : stationaryFelSkulls) {
				if (entity.isRemoved() || !entity.isAlive()
					|| settings.depthCheck.value() && !DungeonMobEspSupport.visibleFromPlayer(client,
						new Vec3(entity.getX(), FelSkullMarkerGeometry.bounds(entity).centerY(), entity.getZ()))) continue;
				DungeonMobTargetMemory.Bounds bounds = FelSkullMarkerGeometry.bounds(entity);
				AABB box = new AABB(bounds.minX(), bounds.minY(), bounds.minZ(),
					bounds.maxX(), bounds.maxY(), bounds.maxZ());
				DungeonMobEspSupport.draw2dBox(graphics, camera, entity, box, settings.felFill.argb(),
					settings.felOutline.argb(), Math.max(1, Math.round(settings.felLineWidth.value())));
			}
		}
		renderHudGroup(client, graphics, camera, partialTick, minibossMatches, settings.minibossStyle,
			settings.depthCheck, settings.minibossOutline, settings.minibossFill, settings.minibossLineWidth,
			shadowAssassinMatches);
		renderRememberedHudGroup(client, graphics, camera, DungeonMobTargetMemory.Group.STARRED,
			partialTick,
			settings.starredStyle, settings.depthCheck, settings.starredOutline, settings.starredFill,
			settings.starredLineWidth);
		renderRememberedHudGroup(client, graphics, camera, DungeonMobTargetMemory.Group.MINIBOSS,
			partialTick,
			settings.minibossStyle, settings.depthCheck, settings.minibossOutline, settings.minibossFill,
			settings.minibossLineWidth);
		renderRememberedHudGroup(client, graphics, camera, DungeonMobTargetMemory.Group.FEL_MOVING,
			partialTick,
			settings.starredStyle, settings.depthCheck, settings.starredOutline, settings.starredFill,
			settings.starredLineWidth);
		if (!felRoomClear && settings.felsEnabled.value() && settings.highlightFelSkulls.value()
			&& "2D Box".equals(settings.felStyle.value())) {
			String roomKey = currentRoomKey(client);
			double maxRangeSquared = (double) settings.starredRange.intValue() * settings.starredRange.intValue();
			for (DungeonMobTargetMemory.Target target : rememberedTargets) {
				if (target.group() != DungeonMobTargetMemory.Group.FEL_SKULL
					|| !java.util.Objects.equals(roomKey, target.roomKey())) continue;
				DungeonMobTargetMemory.Bounds bounds = target.bounds();
				if (client.player.distanceToSqr(bounds.centerX(), bounds.centerY(), bounds.centerZ()) > maxRangeSquared
					|| settings.depthCheck.value() && !DungeonMobEspSupport.visibleFromPlayer(client,
						new Vec3(bounds.centerX(), bounds.centerY(), bounds.centerZ()))) continue;
				AABB box = new AABB(bounds.minX(), bounds.minY(), bounds.minZ(),
					bounds.maxX(), bounds.maxY(), bounds.maxZ());
				DungeonMobEspSupport.draw2dBox(graphics, camera, null, box, settings.felFill.argb(),
					settings.felOutline.argb(), Math.max(1, Math.round(settings.felLineWidth.value())));
			}
		}
	}

	private void renderRememberedHudGroup(Minecraft client, GuiGraphicsExtractor graphics,
		net.minecraft.client.Camera camera, DungeonMobTargetMemory.Group group, float partialTick, ChoiceSetting style,
		BooleanSetting depth, ColorSetting outline, ColorSetting fill, NumberSetting lineWidth) {
		if (!"2D Box".equals(style.value())) return;
		String currentRoom = currentRoomKey(client);
		double maxRange = group == DungeonMobTargetMemory.Group.MINIBOSS
			? settings.minibossRange.intValue() : settings.starredRange.intValue();
		double maxRangeSquared = maxRange * maxRange;
		for (DungeonMobTargetMemory.Target target : rememberedTargets) {
			if (target.group() != group || !java.util.Objects.equals(currentRoom, target.roomKey())) continue;
			DungeonMobTargetMemory.Bounds cached = target.bounds();
			AABB bounds = target.fullShadowAssassin()
				? rememberedShadowAssassinBounds(client, target, partialTick) : null;
			if (target.fullShadowAssassin() && bounds == null) continue;
			if (bounds == null) bounds = new AABB(cached.minX(), cached.minY(), cached.minZ(),
				cached.maxX(), cached.maxY(), cached.maxZ());
			Vec3 center = bounds.getCenter();
			if (client.player.distanceToSqr(center.x, center.y, center.z) > maxRangeSquared) continue;
			Vec3 point = center;
			if (depth.value() && !DungeonMobEspSupport.visibleFromPlayer(client, point)) continue;
			DungeonMobEspSupport.draw2dBox(graphics, camera, null, bounds, fill.argb(), outline.argb(),
				Math.max(1, Math.round(lineWidth.value())));
		}
	}

	private static void renderHudGroup(Minecraft client, GuiGraphicsExtractor graphics,
		net.minecraft.client.Camera camera, float partialTick, List<LivingEntity> entities, ChoiceSetting style,
		BooleanSetting depth, ColorSetting outline, ColorSetting fill, NumberSetting lineWidth,
		List<LivingEntity> fullShadowAssassins) {
		if (!"2D Box".equals(style.value())) return;
		for (LivingEntity entity : entities) {
			if (entity.isRemoved() || !entity.isAlive()
				|| depth.value() && !DungeonMobEspSupport.visibleFromPlayer(client, entity)) continue;
			AABB bounds = fullShadowAssassins.contains(entity)
				? DungeonMobEspSupport.fullShadowAssassinBounds(entity, partialTick) : entity.getBoundingBox();
			DungeonMobEspSupport.draw2dBox(graphics, camera, entity, bounds, fill.argb(), outline.argb(),
				Math.max(1, Math.round(lineWidth.value())));
		}
	}

	/** Restores both old module groups once; saved settings on this module always win. */
	public void restoreLegacySettings(Map<String, Float> numbers, Map<String, Boolean> toggles,
		Map<String, String> choices, Map<String, int[]> colors) {
		restoreNumber(numbers, "Starred Mob ESP.Range", settings.starredRange);
		String depthKey = configName() + "." + settings.depthCheck.name();
		if (!toggles.containsKey(depthKey)) {
			Boolean legacyDepth = toggles.get("Starred Mob ESP.Depth Check");
			if (legacyDepth == null) legacyDepth = toggles.get("Miniboss ESP.Depth Check");
			if (legacyDepth != null) settings.depthCheck.setValue(legacyDepth);
		}
		restoreChoice(choices, "Starred Mob ESP.Style", settings.starredStyle);
		restoreColor(colors, "Starred Mob ESP.Outline Color", settings.starredOutline);
		restoreColor(colors, "Starred Mob ESP.Fill Color", settings.starredFill);
		restoreNumber(numbers, "Miniboss ESP.Range", settings.minibossRange);
		restoreToggle(toggles, "Miniboss ESP.Only Current Room", settings.minibossOnlyRoom);
		restoreChoice(choices, "Miniboss ESP.Style", settings.minibossStyle);
		restoreToggle(toggles, "Miniboss ESP.Only Starred Minibosses", settings.minibossOnlyStarred);
		restoreColor(colors, "Miniboss ESP.Outline Color", settings.minibossOutline);
		restoreColor(colors, "Miniboss ESP.Fill Color", settings.minibossFill);
		restoreToggle(toggles, "Miniboss ESP.Tracer", settings.minibossTracer);
		restoreColor(colors, "Miniboss ESP.Tracer Color", settings.minibossTracerColor);
		restoreNumber(numbers, "Miniboss ESP.Tracer Width", settings.minibossTracerWidth);
		restoreNumber(numbers, "Dungeon Mob ESP.Starred Line Width", settings.starredLineWidth);
		restoreNumber(numbers, "Dungeon Mob ESP.Miniboss Line Width", settings.minibossLineWidth);
	}

	private void restoreNumber(Map<String, Float> values, String oldKey, NumberSetting setting) {
		String newKey = configName() + "." + setting.name();
		if (!values.containsKey(newKey) && values.containsKey(oldKey)) setting.setValue(values.get(oldKey));
	}

	private void restoreToggle(Map<String, Boolean> values, String oldKey, BooleanSetting setting) {
		String newKey = configName() + "." + setting.name();
		if (!values.containsKey(newKey) && values.containsKey(oldKey)) setting.setValue(values.get(oldKey));
	}

	private void restoreChoice(Map<String, String> values, String oldKey, ChoiceSetting setting) {
		String newKey = configName() + "." + setting.name();
		if (!values.containsKey(newKey) && values.containsKey(oldKey)) setting.setValue(values.get(oldKey));
	}

	private void restoreColor(Map<String, int[]> values, String oldKey, ColorSetting setting) {
		String newKey = configName() + "." + setting.name();
		if (values.containsKey(newKey)) return;
		int[] rgba = values.get(oldKey);
		if (rgba != null && rgba.length == 4) setting.set(rgba[0], rgba[1], rgba[2], rgba[3]);
	}

	private static final class Settings {
		final BooleanSetting starredEnabled = new BooleanSetting("Starred Group Enabled", true);
		final BooleanSetting starredOnlyRoom = new BooleanSetting("Starred Only Current Room", false);
		final NumberSetting starredRange = new NumberSetting("Starred Range", 8, 128, 128, true);
		final BooleanSetting depthCheck = new BooleanSetting("Depth Check", true);
		final ChoiceSetting starredStyle = new ChoiceSetting("Starred Style", "Chams", "Chams", "3D Box", "2D Box", "Ring");
		final ColorSetting starredOutline = new ColorSetting("Starred Outline Color", 255, 255, 255, 255);
		final ColorSetting starredFill = new ColorSetting("Starred Fill Color", 0, 0, 0, 255);
		final NumberSetting starredLineWidth = new NumberSetting("Starred Outline Width", 0.5f, 25.0f, 5.0f);
		final NumberSetting starredRingCount = new NumberSetting("Starred Ring Count", 1, 8, 3, true);
		final NumberSetting starredRingHeight = new NumberSetting("Starred Ring Height", 0.4f, 6.0f, 2.0f);
		final NumberSetting starredRingRadius = new NumberSetting("Starred Ring Radius", 0.1f, 3.5f, 0.65f);
		final NumberSetting starredRingSpeed = new NumberSetting("Starred Ring Speed", 0.1f, 5.0f, 0.8f);
		final NumberSetting starredRingLineWidth = new NumberSetting("Starred Ring Line Width", 0.5f, 5.0f, 1.5f);
		final BooleanSetting minibossEnabled = new BooleanSetting("Minibosses Enabled", true);
		final NumberSetting minibossRange = new NumberSetting("Miniboss Range", 8, 128, 64, true);
		final BooleanSetting minibossOnlyRoom = new BooleanSetting("Miniboss Only Current Room", false);
		final ChoiceSetting minibossStyle = new ChoiceSetting("Miniboss Style", "Chams", "Chams", "3D Box", "2D Box", "Ring");
		final BooleanSetting minibossOnlyStarred = new BooleanSetting("Only Starred Minibosses", false);
		final ColorSetting minibossOutline = new ColorSetting("Miniboss Outline Color", 0, 0, 0, 255);
		final ColorSetting minibossFill = new ColorSetting("Miniboss Fill Color", 255, 255, 255, 255);
		final NumberSetting minibossLineWidth = new NumberSetting("Miniboss Outline Width", 0.5f, 25.0f, 5.0f);
		final BooleanSetting minibossTracer = new BooleanSetting("Miniboss Tracer", true);
		final ColorSetting minibossTracerColor = new ColorSetting("Miniboss Tracer Color", 255, 255, 255, 220);
		final NumberSetting minibossTracerWidth = new NumberSetting("Miniboss Tracer Width", 0.5f, 5.0f, 1.5f);
		final NumberSetting minibossRingCount = new NumberSetting("Miniboss Ring Count", 1, 8, 3, true);
		final NumberSetting minibossRingHeight = new NumberSetting("Miniboss Ring Height", 0.4f, 6.0f, 2.0f);
		final NumberSetting minibossRingRadius = new NumberSetting("Miniboss Ring Radius", 0.1f, 3.5f, 0.8f);
		final NumberSetting minibossRingSpeed = new NumberSetting("Miniboss Ring Speed", 0.1f, 5.0f, 0.8f);
		final NumberSetting minibossRingLineWidth = new NumberSetting("Miniboss Ring Line Width", 0.5f, 5.0f, 1.5f);
		final BooleanSetting debug = new BooleanSetting("Debug Target Matching", false);
		final BooleanSetting felsEnabled = new BooleanSetting("Fels Enabled", true);
		final BooleanSetting felOnlyRoom = new BooleanSetting("Fels Only Current Room", false);
		final BooleanSetting highlightFels = new BooleanSetting("Highlight Starred Fels", true);
		final BooleanSetting highlightFelSkulls = new BooleanSetting(
			"Highlight Hidden Fel Skulls", "Highlight Stationary Fels", true);
		final ChoiceSetting felStyle = new ChoiceSetting("Fel Style", "Chams", "Chams", "3D Box", "2D Box", "Ring");
		final ColorSetting felOutline = new ColorSetting("Fel Outline Color", 255, 255, 255, 255);
		final ColorSetting felFill = new ColorSetting("Fel Fill Color", 255, 255, 255, 255);
		final NumberSetting felLineWidth = new NumberSetting("Fel Outline Width", 0.5f, 25.0f, 5.0f);
		final NumberSetting felRingCount = new NumberSetting("Fel Ring Count", 1, 8, 3, true);
		final NumberSetting felRingHeight = new NumberSetting("Fel Ring Height", 0.4f, 6.0f, 2.0f);
		final NumberSetting felRingRadius = new NumberSetting("Fel Ring Radius", 0.1f, 3.5f, 0.65f);
		final NumberSetting felRingSpeed = new NumberSetting("Fel Ring Speed", 0.1f, 5.0f, 0.8f);
		final NumberSetting felRingLineWidth = new NumberSetting("Fel Ring Line Width", 0.5f, 5.0f, 1.5f);
		final BooleanSetting felSkullTracer = new BooleanSetting("Fel Skull Tracer", true);
		final ColorSetting felTracerColor = new ColorSetting("Fel Tracer Color", 255, 255, 255, 220);
		final NumberSetting felTracerWidth = new NumberSetting("Fel Tracer Width", 0.5f, 5.0f, 1.5f);
	}

	/** Remembers a previously identified Fel body only while its actual client entity stays verifiable. */
	private static final class KnownFelBody {
		private final UUID uuid;
		private int entityId;
		private boolean starred;

		KnownFelBody(LivingEntity entity) {
			this.uuid = entity.getUUID();
			observe(entity, false);
		}

		void observe(LivingEntity entity, boolean hasStar) {
			entityId = entity.getId();
			starred |= hasStar;
		}
	}

	private static final class TracerRenderDiagnostics {
		private final boolean enabled;
		private final int colorArgb;
		private final int alpha;
		private final float width;
		private final String style;
		private final int range;
		private final boolean onlyCurrentRoom;
		private final boolean onlyStarred;
		private final boolean depthTested;
		private int snapshotCandidates;
		private int liveEligible;
		private int removed;
		private int notAlive;
		private int renderLineCalls;
		private int vertexSubmissions;
		private Vec3 sampleStart;
		private Vec3 sampleEndpoint;
		private String sampleEntity = "none";

		private TracerRenderDiagnostics(boolean enabled, int colorArgb, int alpha, float width,
			String style, int range, boolean onlyCurrentRoom, boolean onlyStarred, boolean depthTested) {
			this.enabled = enabled;
			this.colorArgb = colorArgb;
			this.alpha = alpha;
			this.width = width;
			this.style = style == null ? "" : style;
			this.range = range;
			this.onlyCurrentRoom = onlyCurrentRoom;
			this.onlyStarred = onlyStarred;
			this.depthTested = depthTested;
		}

		private void consider(boolean isRemoved, boolean alive) {
			snapshotCandidates++;
			if (isRemoved) removed++;
			if (!alive) notAlive++;
			if (!isRemoved && alive) liveEligible++;
		}

		private void submit(LivingEntity entity, Vec3 start, Vec3 endpoint, boolean verticesWritten) {
			renderLineCalls++;
			if (verticesWritten) vertexSubmissions++;
			if (sampleEndpoint == null) {
				sampleStart = start;
				sampleEndpoint = endpoint;
				sampleEntity = entity.getClass().getSimpleName() + "#" + entity.getId();
			}
		}

		private String describe(boolean batchesFlushed) {
			String endpoints = sampleEndpoint == null ? "none"
				: String.format(Locale.ROOT, "(%.2f,%.2f,%.2f)->(%.2f,%.2f,%.2f)",
					sampleStart.x, sampleStart.y, sampleStart.z,
					sampleEndpoint.x, sampleEndpoint.y, sampleEndpoint.z);
			return String.format(Locale.ROOT,
				"enabled=%s, color=0x%08X, alpha=%d, width=%.2f, style=%s, range=%d, "
					+ "onlyCurrentRoom=%s, onlyStarred=%s, depthTested=%s, "
					+ "pipeline=%s, candidates=%d, eligible=%d, removed=%d, notAlive=%d, "
					+ "renderLineCalls=%d, vertexSubmissions=%d, sampleEntity=%s, endpoints=%s, targetDistance=%.2f, batchesFlushed=%s",
				enabled, colorArgb, alpha, width, style, range, onlyCurrentRoom, onlyStarred, depthTested,
				depthTested ? "DEPTH_LINES" : "ESP_LINES", snapshotCandidates, liveEligible,
				removed, notAlive, renderLineCalls, vertexSubmissions, sampleEntity, endpoints,
				sampleEndpoint == null || sampleStart == null ? -1.0 : sampleEndpoint.distanceTo(sampleStart), batchesFlushed);
		}
	}
}
