package geiler.addons.client.module.impl;

import geiler.addons.client.dungeon.DungeonContextTracker;
import geiler.addons.client.dungeon.DungeonRoomTracker;
import geiler.addons.client.entity.ClientEntitySnapshot;
import geiler.addons.client.entity.Nameplates;
import geiler.addons.client.render.WorldToScreen;
import geiler.addons.client.tree.ChatText;
import com.mojang.authlib.properties.Property;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Shared client-thread scanning and projected 2D boxes for dungeon mob ESP modules. */
final class DungeonMobEspSupport {
	// Skyblocker HeadTextures.FEL, verified in dc1176a HeadTextures.java.
	private static final String FEL_HEAD_TEXTURE = "ewogICJ0aW1lc3RhbXAiIDogMTcyMDAyNTQ4Njg2MywKICAicHJvZmlsZUlkIiA6ICIzZDIxZTYyMTk2NzQ0Y2QwYjM3NjNkNTU3MWNlNGJlZSIsCiAgInByb2ZpbGVOYW1lIiA6ICJTcl83MUJsYWNrYmlyZCIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS9jMjg2ZGFjYjBmMjE0NGQ3YTQxODdiZTM2YmJhYmU4YTk4ODI4ZjdjNzlkZmY1Y2UwMTM2OGI2MzAwMTU1NjYzIiwKICAgICAgIm1ldGFkYXRhIiA6IHsKICAgICAgICAibW9kZWwiIDogInNsaW0iCiAgICAgIH0KICAgIH0KICB9Cn0=";
	private static final List<String> MINIBOSS_NAMES = List.of(
		"shadow assassin", "lost adventurer", "diamond guy", "angry archaeologist", "frozen adventurer", "king midas"
	);
	private static final java.util.regex.Pattern DUNGEON_LEVEL_PREFIX = java.util.regex.Pattern.compile("(?i)^\\[\\s*lv\\s*\\d+\\]\\s*");
	private static final java.util.regex.Pattern DUNGEON_ATTRIBUTE_PREFIX = java.util.regex.Pattern.compile(
		"(?i)^(?:Flaming|Stormy|Speedy|Fortified|Healthy|Healing|Boomer|Golden|Stealth)\\s+");
	private static final java.util.regex.Pattern DUNGEON_BRACKET_PREFIX = java.util.regex.Pattern.compile("^\\[[\\w\\d]+\\]\\s*");
	private static final java.util.regex.Pattern DUNGEON_HEALTH_SUFFIX = java.util.regex.Pattern.compile(
		"(?i)\\s+\\d[\\d,]*(?:\\.\\d+)?\\s*[kmbt]?(?:/\\d[\\d,]*(?:\\.\\d+)?\\s*[kmbt]?)?\\s*❤\\s*$");

	private DungeonMobEspSupport() { }

	static ScanResult scan(Minecraft client, int range, boolean currentRoomOnly,
		Predicate<String> nameFilter, boolean starredLabel, boolean captureNameSamples) {
		return scan(client, range, currentRoomOnly, nameFilter, starredLabel, captureNameSamples,
			currentConfirmedRoomFootprint());
	}

	static ScanResult scan(Minecraft client, int range, boolean currentRoomOnly,
		Predicate<String> nameFilter, boolean starredLabel, boolean captureNameSamples,
		DungeonRoomTracker.MapRoomFootprint roomFootprint) {
		if (client == null || client.level == null || client.player == null || nameFilter == null
			|| !DungeonContextTracker.inDungeon()) return ScanResult.empty("dungeon context unavailable");
		ClientLevel level = client.level;
		LocalPlayer player = client.player;
		int boundedRange = Math.max(1, Math.min(128, range));
		DungeonRoomTracker.MapRoomFootprint room = currentRoomOnly ? resolveRoomFootprint(roomFootprint) : null;
		if (currentRoomOnly && room == null) return ScanResult.empty("room occupancy unavailable");
		List<Entity> nearby = ClientEntitySnapshot.nearby(level, player, boundedRange);
		List<LivingEntity> bodies = nearby.stream().filter(LivingEntity.class::isInstance)
			.filter(entity -> !(entity instanceof ArmorStand))
			.map(LivingEntity.class::cast).toList();
		Set<Entity> unique = Collections.newSetFromMap(new IdentityHashMap<>());
		List<LivingEntity> matches = new ArrayList<>();
		List<LivingEntity> shadowAssassins = new ArrayList<>();
		int namedCandidates = 0;
		int resolvedBodies = 0;
		int unresolvedBodies = 0;
		int rangeRejected = 0;
		int roomRejected = 0;
		int shadowAssassinLabels = 0;
		int shadowAssassinLabelBodies = 0;
		int shadowAssassinLabelRejects = 0;
		int exactProfileBodies = 0;
		int exactProfileAccepted = 0;
		int exactProfileRangeRejects = 0;
		int exactProfileRoomRejects = 0;
		int exactProfileDeadRejects = 0;
		String lastLabelRejection = "no-label-found";
		boolean shadowAssassinProfilePathEnabled = nameFilter.test("Shadow Assassin");
		String lastProfileRejection = shadowAssassinProfilePathEnabled ? "no-exact-profile-found" : "profile-path-disabled";
		List<String> nameSamples = new ArrayList<>(8);
		for (Entity nameSource : nearby) {
			if (nameSource == player || !nameSource.isAlive()) continue;
			String name = plainName(nameSource);
			if (captureNameSamples && nameSamples.size() < 8 && !name.isBlank()
				&& (nameSource instanceof ArmorStand || nameSource.hasCustomName())) {
				String sample = nameSource.getClass().getSimpleName() + "#" + nameSource.getId() + "=\"" + name + "\"";
				nameSamples.add(sample.length() <= 96 ? sample : sample.substring(0, 93) + "...");
			}
			if (!nameFilter.test(name)) continue;
			namedCandidates++;
			boolean shadowAssassinName = isShadowAssassin(name);
			boolean shadowAssassinLabel = nameSource instanceof ArmorStand && shadowAssassinName;
			if (shadowAssassinLabel) {
				shadowAssassinLabels++;
				lastLabelRejection = "awaiting-body-association";
			}
			// Hypixel uses a separate armor-stand label. Reject unrelated nameplates at room scale and
			// associate Shadow Assassins only through Devonian's named-label ID - 1 relationship.
			if (!(nameSource instanceof ArmorStand) && !(nameSource instanceof LivingEntity)) continue;
			Entity body = null;
			if ((starredLabel || shadowAssassinName) && nameSource instanceof ArmorStand) {
				// Devonian's BoxStarMob associates a named armor-stand label with the body at ID - 1;
				// Withermancer labels use -3. Shadow Assassin labels must not fall back to proximity,
				// which could attach a nearby unrelated miniboss in a crowded room.
				int offset = name.toLowerCase(java.util.Locale.ROOT).contains("withermancer") ? 3 : 1;
				Entity byProtocolOrder = level.getEntity(nameSource.getId() - offset);
				if (shadowAssassinLabel) {
					String association = shadowAssassinAssociationReason(name, nameSource.getId(),
						byProtocolOrder == null ? -1 : byProtocolOrder.getId(),
						byProtocolOrder instanceof LivingEntity, byProtocolOrder instanceof ArmorStand,
						byProtocolOrder == null ? Double.NaN : byProtocolOrder.distanceToSqr(nameSource));
					if ("associated".equals(association)) {
						body = (LivingEntity) byProtocolOrder;
						shadowAssassinLabelBodies++;
						lastLabelRejection = "none";
					} else {
						shadowAssassinLabelRejects++;
						lastLabelRejection = association;
					}
				} else if (!shadowAssassinLabel && byProtocolOrder instanceof LivingEntity
					&& !(byProtocolOrder instanceof ArmorStand) && byProtocolOrder.distanceToSqr(nameSource) <= 9.0) {
					body = byProtocolOrder;
				}
			}
			// Prefer the source's adjacent entity ID. If that association is unavailable, require a
			// clearly closer body so a crowded nameplate cannot paint a neighboring mob.
			if (body == null && !shadowAssassinName) body = Nameplates.resolveUnambiguousBody(nameSource, bodies, true);
			if (!(body instanceof LivingEntity living) || living == player || !living.isAlive()) {
				unresolvedBodies++;
				if (shadowAssassinLabel && "none".equals(lastLabelRejection)) {
					lastLabelRejection = body == null ? "associated-body-unavailable"
						: body == player ? "body-is-local-player" : "associated-body-not-alive";
					shadowAssassinLabelRejects++;
				}
				continue;
			}
			resolvedBodies++;
			if (living.distanceToSqr(player) > (double) boundedRange * boundedRange) {
				rangeRejected++;
				if (shadowAssassinLabel) {
					shadowAssassinLabelRejects++;
					lastLabelRejection = "body-out-of-range";
				}
				continue;
			}
			if (!isAllowedByRoomFilter(currentRoomOnly, room, living.blockPosition())) {
				roomRejected++;
				if (shadowAssassinLabel) {
					shadowAssassinLabelRejects++;
					lastLabelRejection = roomFilterRejection(currentRoomOnly, room, living.blockPosition());
				}
				continue;
			}
			if (!unique.add(living)) {
				if (shadowAssassinLabel) lastLabelRejection = "already-added-by-another-shadow-assassin-source";
				continue;
			}
			matches.add(living);
			if (shadowAssassinLabel) shadowAssassins.add(living);
		}
		// Hypixel's invisible Shadow Assassin can be represented by a player entity whose exact profile
		// name is the NPC name, even when its separate armor-stand label is absent. Resolve that body
		// directly; unrelated party members cannot match this exact profile identity.
		if (shadowAssassinProfilePathEnabled) for (Entity entity : nearby) {
			if (!(entity instanceof net.minecraft.world.entity.player.Player playerEntity)
				|| playerEntity == player || !isShadowAssassinProfile(playerEntity)) continue;
			exactProfileBodies++;
			lastProfileRejection = "exact-profile-found";
			if (!playerEntity.isAlive()) {
				exactProfileDeadRejects++;
				lastProfileRejection = "profile-body-not-alive";
				continue;
			}
			if (playerEntity.distanceToSqr(player) > (double) boundedRange * boundedRange) {
				rangeRejected++;
				exactProfileRangeRejects++;
				lastProfileRejection = "profile-body-out-of-range";
				continue;
			}
			if (!isAllowedByRoomFilter(currentRoomOnly, room, playerEntity.blockPosition())) {
				roomRejected++;
				exactProfileRoomRejects++;
				lastProfileRejection = roomFilterRejection(currentRoomOnly, room, playerEntity.blockPosition());
				continue;
			}
			exactProfileAccepted++;
			namedCandidates++;
			if (!unique.add(playerEntity)) {
				lastProfileRejection = "already-added-by-nameplate";
				continue;
			}
			resolvedBodies++;
			matches.add(playerEntity);
			shadowAssassins.add(playerEntity);
			lastProfileRejection = "matched";
		}
		return new ScanResult(List.copyOf(matches), nearby.size(), namedCandidates, resolvedBodies,
			unresolvedBodies, rangeRejected, roomRejected, List.copyOf(nameSamples), List.copyOf(shadowAssassins), "",
			new ShadowAssassinScanDiagnostics(shadowAssassinProfilePathEnabled, shadowAssassinLabels,
				shadowAssassinLabelBodies, shadowAssassinLabelRejects, exactProfileBodies, exactProfileAccepted,
				exactProfileRangeRejects, exactProfileRoomRejects, exactProfileDeadRejects,
				lastLabelRejection, lastProfileRejection, ""));
	}

	/** Finds Fel marker armor stands by the exact player-head texture used by Skyblocker. */
	static List<ArmorStand> findFelSkullMarkers(Minecraft client, int range, boolean currentRoomOnly,
		DungeonRoomTracker.MapRoomFootprint roomFootprint) {
		if (client == null || client.level == null || client.player == null || !DungeonContextTracker.inDungeon()) return List.of();
		int boundedRange = Math.max(1, Math.min(128, range));
		DungeonRoomTracker.MapRoomFootprint room = currentRoomOnly ? resolveRoomFootprint(roomFootprint) : null;
		if (currentRoomOnly && room == null) return List.of();
		List<ArmorStand> result = new ArrayList<>();
		for (Entity entity : ClientEntitySnapshot.nearby(client.level, client.player, boundedRange)) {
			if (!(entity instanceof ArmorStand marker) || !isFelSkullMarker(marker)
				|| marker.distanceToSqr(client.player) > (double) boundedRange * boundedRange
				|| currentRoomOnly && !room.contains(marker.blockPosition())) continue;
			result.add(marker);
		}
		return List.copyOf(result);
	}

	static boolean isFelSkullMarker(ArmorStand marker) {
		return marker != null && isFelSkullMarker(marker.isMarker(), marker.hasItemInSlot(EquipmentSlot.HEAD),
			headTexture(marker.getItemBySlot(EquipmentSlot.HEAD)));
	}

	static boolean isFelSkullMarker(boolean marker, boolean hasHead, String texture) {
		return marker && hasHead && isFelHeadTexture(texture);
	}

	static boolean isFelHeadTexture(String texture) {
		return FEL_HEAD_TEXTURE.equals(texture);
	}

	private static String headTexture(ItemStack stack) {
		if (stack == null || !stack.is(Items.PLAYER_HEAD)) return "";
		ResolvableProfile profile = stack.get(DataComponents.PROFILE);
		if (profile == null) return "";
		return profile.partialProfile().properties().get("textures").stream()
			.filter(java.util.Objects::nonNull)
			.map(Property::value)
			.filter(java.util.Objects::nonNull)
			.findFirst().orElse("");
	}

	/** Resolves the Fels nameplate to a nearby living body without guessing beyond the normal nameplate radius. */
	static List<FelCandidate> findFels(Minecraft client, int range, boolean currentRoomOnly) {
		return findFels(client, range, currentRoomOnly, currentConfirmedRoomFootprint());
	}

	static List<FelCandidate> findFels(Minecraft client, int range, boolean currentRoomOnly,
		DungeonRoomTracker.MapRoomFootprint roomFootprint) {
		if (client == null || client.level == null || client.player == null || !DungeonContextTracker.inDungeon()) return List.of();
		int boundedRange = Math.max(1, Math.min(128, range));
		DungeonRoomTracker.MapRoomFootprint room = currentRoomOnly ? resolveRoomFootprint(roomFootprint) : null;
		if (currentRoomOnly && room == null) return List.of();
		List<Entity> nearby = ClientEntitySnapshot.nearby(client.level, client.player, boundedRange);
		List<LivingEntity> bodies = nearby.stream().filter(LivingEntity.class::isInstance)
			.filter(entity -> !(entity instanceof ArmorStand)).map(LivingEntity.class::cast).toList();
		Map<LivingEntity, Boolean> candidates = new IdentityHashMap<>();
		for (Entity source : nearby) {
			if (source == client.player || !isFelName(plainName(source))) continue;
			Entity body = source;
			if (source instanceof ArmorStand) {
				Entity byProtocolOrder = client.level.getEntity(source.getId() - 1);
				if (byProtocolOrder instanceof LivingEntity && !(byProtocolOrder instanceof ArmorStand)
					&& byProtocolOrder.distanceToSqr(source) <= 9.0) body = byProtocolOrder;
				else body = Nameplates.resolveUnambiguousBody(source, bodies, true);
			}
			if (!(body instanceof LivingEntity living) || living == client.player || !living.isAlive()
				|| living.distanceToSqr(client.player) > (double) boundedRange * boundedRange
				|| currentRoomOnly && !room.contains(living.blockPosition())) continue;
			candidates.merge(living, isStarred(plainName(source)), Boolean::logicalOr);
		}
		return candidates.entrySet().stream().map(entry -> new FelCandidate(entry.getKey(), entry.getValue())).toList();
	}

	private static DungeonRoomTracker.MapRoomFootprint resolveRoomFootprint(
		DungeonRoomTracker.MapRoomFootprint footprint) {
		if (footprint != null) return footprint;
		return currentConfirmedRoomFootprint();
	}

	static DungeonRoomTracker.MapRoomFootprint confirmedRoomFootprint(boolean calibratedMapGeometry,
		List<DungeonRoomTracker.CellKey> cells) {
		if (!calibratedMapGeometry || cells == null || cells.isEmpty()) return null;
		return new DungeonRoomTracker.MapRoomFootprint(null, cells);
	}

	static DungeonRoomTracker.MapRoomFootprint currentConfirmedRoomFootprint() {
		return DungeonContextTracker.inDungeon() ? DungeonRoomTracker.currentFootprint() : null;
	}

	static boolean shouldHighlightFel(boolean enabled, boolean starred) { return enabled && starred; }

	static boolean isFelName(String name) {
		String normalized = normalizedMobName(name);
		return "fel".equals(normalized) || "fels".equals(normalized);
	}

	static boolean shouldRetainRoomFootprint(boolean sameLevel, boolean playerInside) {
		return sameLevel && playerInside;
	}

	static boolean isEligibleMinibossName(String name, boolean onlyStarred) {
		return isNamedMiniboss(name) && (!onlyStarred || isStarred(name));
	}

	static boolean shouldClassifyFelsForStarFilter(boolean starredEnabled, boolean felsEnabled) {
		return starredEnabled || felsEnabled;
	}

	static boolean isStarredGroupTarget(boolean felBody, boolean shadowAssassinBody) {
		return !felBody && !shadowAssassinBody;
	}

	static boolean nearAnyStarredPosition(double x, double y, double z, List<WorldPosition> positions, double radius) {
		if (positions == null || positions.isEmpty() || !Double.isFinite(x) || !Double.isFinite(y)
			|| !Double.isFinite(z) || !Double.isFinite(radius) || radius < 0) return false;
		double radiusSquared = radius * radius;
		for (WorldPosition position : positions) {
			if (position == null || !Double.isFinite(position.x()) || !Double.isFinite(position.y())
				|| !Double.isFinite(position.z())) continue;
			double dx = position.x() - x, dy = position.y() - y, dz = position.z() - z;
			if (dx * dx + dy * dy + dz * dz <= radiusSquared) return true;
		}
		return false;
	}

	static boolean isShadowAssassinProfile(net.minecraft.world.entity.player.Player player) {
		return player != null && isShadowAssassinProfileName(player.getGameProfile().name());
	}

	static boolean isShadowAssassinProfileName(String name) {
		return "Shadow Assassin".equals(name);
	}

	static boolean isAllowedByRoomFilter(boolean currentRoomOnly,
		DungeonRoomTracker.MapRoomFootprint footprint, BlockPos position) {
		return !currentRoomOnly || footprint != null && position != null && footprint.contains(position);
	}

	static String roomFilterRejection(boolean currentRoomOnly,
		DungeonRoomTracker.MapRoomFootprint footprint, BlockPos position) {
		if (!currentRoomOnly) return "room-filter-disabled";
		if (footprint == null) return "confirmed-room-footprint-unavailable";
		if (position == null) return "body-position-unavailable";
		return footprint.contains(position) ? "inside-confirmed-room" : "outside-confirmed-room";
	}

	static String shadowAssassinAssociationReason(String name, int armorStandId, int bodyId,
		boolean bodyLiving, boolean bodyArmorStand, double distanceSquared) {
		if (!isShadowAssassin(name)) return "label-name-mismatch";
		if (armorStandId <= 0) return "invalid-label-entity-id";
		if (bodyId < 0) return "body-not-found";
		if (bodyId != armorStandId - 1) return "body-id-not-label-minus-one";
		if (!bodyLiving) return "associated-body-not-living";
		if (bodyArmorStand) return "associated-body-is-armor-stand";
		if (!Double.isFinite(distanceSquared) || distanceSquared < 0.0) return "invalid-association-distance";
		if (distanceSquared > 9.0) return "associated-body-too-far";
		return "associated";
	}

	/** Devonian correlates the exact named armor-stand label with the living entity at ID - 1. */
	static boolean isNamedShadowAssassinArmorStandAssociation(String name, int armorStandId, int bodyId,
		boolean bodyLiving, boolean bodyArmorStand, double distanceSquared) {
		return "associated".equals(shadowAssassinAssociationReason(name, armorStandId, bodyId,
			bodyLiving, bodyArmorStand, distanceSquared));
	}

	static <T> boolean addShadowAssassinCandidateOnce(List<T> minibosses, List<T> shadowAssassins,
		Set<T> seenByIdentity, T candidate) {
		if (minibosses == null || shadowAssassins == null || seenByIdentity == null || candidate == null
			|| !seenByIdentity.add(candidate)) return false;
		minibosses.add(candidate);
		shadowAssassins.add(candidate);
		return true;
	}

	record WorldPosition(double x, double y, double z) { }

	record FelCandidate(LivingEntity entity, boolean starred) { }

	record ScanResult(List<LivingEntity> matches, int nearbyEntities, int namedCandidates,
		int resolvedBodies, int unresolvedBodies, int rangeRejected, int roomRejected,
		List<String> nameSamples, List<LivingEntity> shadowAssassins, String reason,
		ShadowAssassinScanDiagnostics shadowAssassinDiagnostics) {
		static ScanResult empty(String reason) {
			return new ScanResult(List.of(), 0, 0, 0, 0, 0, 0, List.of(), List.of(), reason,
				ShadowAssassinScanDiagnostics.empty(reason));
		}
		String summary() {
			return reason.isBlank()
				? "entities=" + nearbyEntities + ", names=" + namedCandidates + ", bodies=" + resolvedBodies
					+ ", unresolved=" + unresolvedBodies + ", range=" + rangeRejected
					+ ", room=" + roomRejected + ", rendered=" + matches.size()
					+ ", named entities=" + nameSamples
				: reason;
		}
	}

	record ShadowAssassinScanDiagnostics(boolean profilePathEnabled, int labels, int labelBodyAssociations,
		int labelRejections, int exactProfiles, int exactProfileAccepted, int exactProfileRangeRejections,
		int exactProfileRoomRejections, int exactProfileDeadRejections, String lastLabelRejection,
		String lastProfileRejection, String earlyExitReason) {
		static ShadowAssassinScanDiagnostics empty(String reason) {
			return new ShadowAssassinScanDiagnostics(false, 0, 0, 0, 0, 0, 0, 0, 0,
				"not-scanned", "not-scanned", reason == null ? "" : reason);
		}

		String summary() {
			return "profilePath=" + profilePathEnabled + ", labels=" + labels
				+ ", labelBodies=" + labelBodyAssociations + ", labelRejected=" + labelRejections
				+ " (last=" + lastLabelRejection + ")"
				+ ", exactProfiles=" + exactProfiles + ", profileAccepted=" + exactProfileAccepted
				+ ", profileRangeRejected=" + exactProfileRangeRejections
				+ ", profileRoomRejected=" + exactProfileRoomRejections
				+ ", profileDeadRejected=" + exactProfileDeadRejections
				+ " (last=" + lastProfileRejection + ")"
				+ (earlyExitReason == null || earlyExitReason.isBlank() ? "" : ", scanExit=" + earlyExitReason);
		}
	}

	static String plainName(Entity entity) {
		if (entity == null) return "";
		var custom = entity.getCustomName();
		return ChatText.plain((custom == null ? entity.getName() : custom).getString()).trim();
	}

	static boolean isStarred(String name) {
		if (name == null || name.isBlank()) return false;
		String plain = ChatText.plain(name).trim();
		// Hypixel's stand label can contain formatting or a level prefix before the star. After
		// formatting is stripped the star is not guaranteed to be the first visible character.
		return plain.indexOf('✯') >= 0;
	}

	static boolean isNamedMiniboss(String name) {
		String normalized = normalizedMobName(name);
		return MINIBOSS_NAMES.contains(normalized);
	}

	static boolean isShadowAssassin(String name) {
		return normalizedMobName(name).equals("shadow assassin");
	}

	/** Expands the Shadow Assassin target to the full body used by the pinned reference behavior. */
	static AABB fullShadowAssassinBounds(LivingEntity entity) {
		if (entity == null) return null;
		return fullShadowAssassinBounds(entity.getX(), entity.getY(), entity.getZ());
	}

	static AABB fullShadowAssassinBounds(LivingEntity entity, float partialTick) {
		if (entity == null) return null;
		return fullShadowAssassinBounds(entity.xo, entity.yo, entity.zo,
			entity.getX(), entity.getY(), entity.getZ(), partialTick);
	}

	static AABB fullShadowAssassinBounds(double previousX, double previousY, double previousZ,
		double currentX, double currentY, double currentZ, float partialTick) {
		return fullShadowAssassinBounds(Mth.lerp(partialTick, previousX, currentX),
			Mth.lerp(partialTick, previousY, currentY), Mth.lerp(partialTick, previousZ, currentZ));
	}

	static AABB fullShadowAssassinBounds(double x, double y, double z) {
		return new AABB(x - 0.4, y, z - 0.4, x + 0.4, y + 2.0, z + 0.4);
	}

	static String normalizedMobName(String value) {
		String plain = ChatText.plain(value == null ? "" : value).trim().replace("✯", " ").trim();
		// Hypixel prefixes some dungeon attributes with private-use glyphs. Remove those glyphs
		// before the anchored attribute matcher, otherwise "Healthy Fels" becomes an unmatched name.
		plain = stripPrivateUseGlyphs(plain);
		// Port the explicit dungeon-name affixes used before the captured name: optional level,
		// optional dungeon attribute, and the optional bracket token. Keep roster matching exact.
		plain = DUNGEON_LEVEL_PREFIX.matcher(plain).replaceFirst("");
		plain = DUNGEON_ATTRIBUTE_PREFIX.matcher(plain).replaceFirst("");
		plain = DUNGEON_BRACKET_PREFIX.matcher(plain).replaceFirst("");
		// SkyHanni's dungeon grammar treats the final health value as one token, including current/max.
		plain = DUNGEON_HEALTH_SUFFIX.matcher(plain).replaceFirst("");
		plain = plain.replace("ᛤ", " ");
		return plain.replaceAll("[^A-Za-z]+", " ").trim().toLowerCase(java.util.Locale.ROOT)
			.replaceAll("\\s+", " ");
	}

	private static String stripPrivateUseGlyphs(String value) {
		if (value == null || value.isEmpty()) return "";
		StringBuilder result = new StringBuilder(value.length());
		value.codePoints().filter(codePoint -> Character.getType(codePoint) != Character.PRIVATE_USE)
			.forEach(result::appendCodePoint);
		return result.toString().trim();
	}

	static void draw2dBox(GuiGraphicsExtractor graphics, Camera camera, LivingEntity entity, AABB bounds,
		int fillColor, int outlineColor, int outlineWidth) {
		if (graphics == null || camera == null || bounds == null) return;
		AABB box = bounds;
		float[] projected = new float[16];
		float[] out = new float[2];
		int point = 0;
		for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++) for (int x = 0; x < 2; x++) {
			Vec3 corner = new Vec3(x == 0 ? box.minX : box.maxX, y == 0 ? box.minY : box.maxY,
				z == 0 ? box.minZ : box.maxZ);
			if (!WorldToScreen.projectInto(camera, corner, out)) return;
			projected[point++] = out[0];
			projected[point++] = out[1];
		}
		float left = Float.POSITIVE_INFINITY, top = Float.POSITIVE_INFINITY;
		float right = Float.NEGATIVE_INFINITY, bottom = Float.NEGATIVE_INFINITY;
		for (int i = 0; i < projected.length; i += 2) {
			left = Math.min(left, projected[i]);
			right = Math.max(right, projected[i]);
			top = Math.min(top, projected[i + 1]);
			bottom = Math.max(bottom, projected[i + 1]);
		}
		int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
		int screenHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();
		int x0 = Math.clamp((int) Math.floor(left), 0, screenWidth);
		int y0 = Math.clamp((int) Math.floor(top), 0, screenHeight);
		int x1 = Math.clamp((int) Math.ceil(right), 0, screenWidth);
		int y1 = Math.clamp((int) Math.ceil(bottom), 0, screenHeight);
		if (x1 <= x0 || y1 <= y0) return;
		if ((fillColor >>> 24) != 0) graphics.fill(x0 + 1, y0 + 1, Math.max(x0 + 1, x1 - 1), Math.max(y0 + 1, y1 - 1), fillColor);
		int line = Math.max(1, Math.min(6, outlineWidth));
		if ((outlineColor >>> 24) == 0) return;
		graphics.fill(x0, y0, x1, Math.min(y1, y0 + line), outlineColor);
		graphics.fill(x0, Math.max(y0, y1 - line), x1, y1, outlineColor);
		graphics.fill(x0, y0 + line, Math.min(x1, x0 + line), Math.max(y0 + line, y1 - line), outlineColor);
		graphics.fill(Math.max(x0, x1 - line), y0 + line, x1, Math.max(y0 + line, y1 - line), outlineColor);
	}

	static boolean visibleFromPlayer(Minecraft client, LivingEntity entity) {
		return client != null && client.player != null && entity != null && client.player.hasLineOfSight(entity);
	}

	static boolean visibleFromPlayer(Minecraft client, Vec3 target) {
		if (client == null || client.player == null || client.level == null || target == null) return false;
		Vec3 eye = client.player.getEyePosition();
		return client.level.clip(new ClipContext(eye, target, ClipContext.Block.COLLIDER,
			ClipContext.Fluid.NONE, client.player)).getType() == HitResult.Type.MISS;
	}
}
