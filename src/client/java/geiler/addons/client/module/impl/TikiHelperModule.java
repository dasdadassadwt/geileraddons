package geiler.addons.client.module.impl;

import com.mojang.blaze3d.vertex.PoseStack;
import geiler.addons.client.config.TikiCoords;
import geiler.addons.client.config.TikiDebugLog;
import geiler.addons.client.gui.TikiCoordManagerScreen;
import geiler.addons.client.location.TorrhusPresence;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.ModuleAction;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.render.EspRenderer;
import geiler.addons.client.render.GeilerAddonsRenderTypes;
import geiler.addons.client.render.WorldToScreen;
import geiler.addons.client.tiki.TikiSolver;
import geiler.addons.client.tiki.TikiStacks;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.WallSkullBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything for hunting Sneaky Tikis, in one module with a sub-toggle per part.
 *
 * <p><b>Waypoints</b> box each stored coordinate green or red by whether a tiki is standing there,
 * remembering what it last saw so a coordinate spotted at the edge of render distance keeps its
 * marker after the chunk unloads. <b>Solver</b> floats the next click on the head that needs it.
 * <b>Debug logging</b> records rotations, clicks, sounds and chat to a file for working out the
 * puzzle's rules.
 *
 * <p>A tiki is three heads on the three blocks above a stored coordinate. Slots are read by
 * position rather than by hunting for a column of skulls, because a slot can hold an ordinary
 * block instead of a head - it still rotates and still counts toward the solution.
 */
public final class TikiHelperModule extends Module {
	private static final int SCAN_HEIGHT = TikiStacks.STACK_HEIGHT;
	/** Heads needed at a coordinate for the waypoint to count as a live tiki. */
	private static final int REQUIRED_SKULLS = 2;
	private static final double TRACER_START_DISTANCE = 0.5;
	private static final float BOX_LINE_WIDTH = 2.0f;

	private static final String LOCKED_LABEL = ".";
	private static final String UNKNOWN_LABEL = "?";
	private static final float LABEL_LINE_WIDTH = 4.0f;
	private static final float LOCKED_LABEL_SCALE = 0.6f;
	/** Sideways, not toward the camera: a nearer label parallaxes onto the neighbouring head. */
	private static final double LABEL_OFFSET = 0.8;
	private static final double LABEL_ANCHOR = 0.25;
	/** World-Y drop per future label sharing a head another label already occupies. */
	private static final double FUTURE_STACK_STEP = 0.18;
	/** Half a line of text, so the HUD label centres on the projected point. */
	private static final int HUD_LABEL_HALF_HEIGHT = 4;

	private static final int DEBUG_RESCAN_INTERVAL = 20;
	private static final double SOUND_SLACK = 8.0;
	private static final int VERIFY_DELAY = 8;
	private static final float DEBUG_LABEL_HEIGHT = 0.3f;

	/** Matched with contains(), so a chat-compacting mod appending a counter still hits. */
	private static final String AWAKENED_MESSAGE = "Looks like you woke the Tiki up!";

	public static final TikiHelperModule INSTANCE = new TikiHelperModule();

	private final BooleanSetting waypoints;
	private final BooleanSetting solver;
	private final BooleanSetting debugLogging;

	private final ColorSetting validColor;
	private final ColorSetting invalidColor;
	private final ColorSetting tracerColor;
	private final NumberSetting scanInterval;
	private final NumberSetting tracerWidth;
	private final BooleanSetting tracer;

	private final ColorSetting leftColor;
	private final ColorSetting rightColor;
	private final ColorSetting lockedColor;
	private final NumberSetting range;
	private final NumberSetting labelHeight;
	private final BooleanSetting showLocked;
	private final BooleanSetting showUnknown;
	private final BooleanSetting worldLabels;
	private final BooleanSetting hypixelRule;
	private final BooleanSetting showFuturePlan;
	private final ColorSetting futureColor;

	private final NumberSetting trackRadius;
	private final BooleanSetting trackSounds;
	private final BooleanSetting trackChat;
	private final BooleanSetting verifySolver;
	private final BooleanSetting echoToChat;

	/** Last known state per stored coordinate; survives the chunk unloading. */
	private final Map<BlockPos, Boolean> remembered = new LinkedHashMap<>();
	/** Tikis woken since they were last seen standing; skipped until a fresh one is there. */
	private final Set<BlockPos> woken = new HashSet<>();

	private BlockPos slotBase;
	private int[] rotations;
	private int hiddenIndex = -1;
	private TikiSolver.Plan plan;
	/** Remaining runs after {@link #plan}, greyed out - empty rather than null when there are none. */
	private List<TikiSolver.Plan> futurePlans = List.of();

	private final Map<BlockPos, Skull> tracked = new HashMap<>();
	/** Tracks the active edge so leaving the region clears state exactly once. */
	private boolean wasActive;
	private boolean armed;
	private long tick;
	private int ticksSinceScan;
	private int ticksSinceDebugScan;
	private PendingVerification pending;
	private ClientLevel lastLevel;

	private TikiHelperModule() {
		this(new Settings());
	}

	private TikiHelperModule(Settings s) {
		super("Tiki Helper", "Waypoints, click solver and research logging for Sneaky Tikis.", Category.HUNTING,
			s.waypoints, s.validColor, s.invalidColor, s.scanInterval, s.tracer, s.tracerColor, s.tracerWidth,
			s.solver, s.leftColor, s.rightColor, s.lockedColor, s.range, s.labelHeight, s.showLocked,
			s.showUnknown, s.worldLabels, s.hypixelRule, s.showFuturePlan, s.futureColor,
			s.debugLogging, s.trackRadius, s.trackSounds, s.trackChat, s.verifySolver, s.echoToChat,
			s.manageCoords);
		this.waypoints = s.waypoints;
		this.solver = s.solver;
		this.debugLogging = s.debugLogging;
		this.validColor = s.validColor;
		this.invalidColor = s.invalidColor;
		this.tracerColor = s.tracerColor;
		this.scanInterval = s.scanInterval;
		this.tracerWidth = s.tracerWidth;
		this.tracer = s.tracer;
		this.leftColor = s.leftColor;
		this.rightColor = s.rightColor;
		this.lockedColor = s.lockedColor;
		this.range = s.range;
		this.labelHeight = s.labelHeight;
		this.showLocked = s.showLocked;
		this.showUnknown = s.showUnknown;
		this.worldLabels = s.worldLabels;
		this.hypixelRule = s.hypixelRule;
		this.showFuturePlan = s.showFuturePlan;
		this.futureColor = s.futureColor;
		this.trackRadius = s.trackRadius;
		this.trackSounds = s.trackSounds;
		this.trackChat = s.trackChat;
		this.verifySolver = s.verifySolver;
		this.echoToChat = s.echoToChat;
		// Each section leads with the sub-toggle that switches the whole part on, so the first row
		// is always the one that decides whether the rest of the section matters.
		group(
			new SettingGroup("Waypoints", s.waypoints, s.validColor, s.invalidColor, s.scanInterval,
				s.tracer, s.tracerColor, s.tracerWidth),
			new SettingGroup("Solver", s.solver, s.leftColor, s.rightColor, s.lockedColor, s.range,
				s.labelHeight, s.showLocked, s.showUnknown, s.worldLabels, s.hypixelRule,
				s.showFuturePlan, s.futureColor),
			new SettingGroup("Debug Logging", s.debugLogging, s.trackRadius, s.trackSounds,
				s.trackChat, s.verifySolver, s.echoToChat),
			new SettingGroup("Coordinates", s.manageCoords)
		);
	}

	/** Just a carrier so the constructor can both build the settings and keep references to them. */
	private static final class Settings {
		final BooleanSetting waypoints = new BooleanSetting("Waypoints", true);
		final BooleanSetting solver = new BooleanSetting("Solver", true);
		final BooleanSetting debugLogging = new BooleanSetting("Debug Logging", false);

		final ColorSetting validColor = new ColorSetting("Valid Color", 0, 255, 0, 128);
		final ColorSetting invalidColor = new ColorSetting("Invalid Color", 255, 0, 0, 128);
		final ColorSetting tracerColor = new ColorSetting("Tracer Color", 0, 255, 0, 255);
		final NumberSetting scanInterval = new NumberSetting("Scan Interval", 1, 200, 40, true);
		final NumberSetting tracerWidth = new NumberSetting("Tracer Width", 0.5f, 10.0f, 2.0f);
		final BooleanSetting tracer = new BooleanSetting("Tracer Line", true);

		final ColorSetting leftColor = new ColorSetting("Left Color", 85, 255, 85, 255);
		final ColorSetting rightColor = new ColorSetting("Right Color", 255, 60, 60, 255);
		final ColorSetting lockedColor = new ColorSetting("Locked Color", 160, 160, 160, 200);
		final NumberSetting range = new NumberSetting("Solver Range", 2, 32, 8, true);
		final NumberSetting labelHeight = new NumberSetting("Label Height", 0.1f, 1.5f, 0.35f);
		final BooleanSetting showLocked = new BooleanSetting("Show Locked", true);
		final BooleanSetting showUnknown = new BooleanSetting("Show Unknown", true);
		/** Off means the real font on the HUD; on falls back to the stroked in-world glyphs. */
		final BooleanSetting worldLabels = new BooleanSetting("World Labels", false);
		/** Plan against the wiki's wording instead of the rule the logs actually show. */
		final BooleanSetting hypixelRule = new BooleanSetting("Hypixel Rule", false);
		final BooleanSetting showFuturePlan = new BooleanSetting("Show Future Clicks", true);
		final ColorSetting futureColor = new ColorSetting("Future Color", 200, 200, 200, 130);

		final NumberSetting trackRadius = new NumberSetting("Track Radius", 2, 32, 8, true);
		final BooleanSetting trackSounds = new BooleanSetting("Track Sounds", true);
		final BooleanSetting trackChat = new BooleanSetting("Track Chat", true);
		final BooleanSetting verifySolver = new BooleanSetting("Verify Solver", true);
		final BooleanSetting echoToChat = new BooleanSetting("Echo To Chat", false);

		final ModuleAction manageCoords = new ModuleAction("Manage Tiki Coords", TikiHelperModule::openCoordManager);
	}

	// ---- lifecycle ----------------------------------------------------------------------

	@Override
	protected void onEnable() {
		resetState();
		tick = 0;
		ticksSinceScan = 0;
		ticksSinceDebugScan = 0;
	}

	@Override
	protected void onDisable() {
		resetState();
		TikiDebugLog.close();
	}

	/**
	 * Tikis only exist in Torrhus Canyon, so everywhere else the module stays switched on but does
	 * nothing. Gating here rather than on the enabled flag means the user's switch keeps meaning
	 * what they set it to, and the whole per-tick solver scan stops costing anything for the rest
	 * of SkyBlock.
	 */
	@Override
	public boolean isActive() {
		return isEnabled() && TorrhusPresence.isPresent();
	}

	@Override
	public String inactiveReason() {
		if (!isEnabled() || isActive()) return null;
		return TorrhusPresence.reason();
	}

	private void resetState() {
		remembered.clear();
		woken.clear();
		clearSolver();
		tracked.clear();
		armed = false;
		pending = null;
	}

	/** Re-reads the waypoints on the next tick - the coordinate list just changed under us. */
	public void requestScan() {
		ticksSinceScan = Integer.MAX_VALUE - 1;
		remembered.keySet().retainAll(TikiCoords.all());
	}

	public void tick() {
		if (!isEnabled()) return;
		if (!isActive()) {
			if (wasActive) {
				wasActive = false;
				// Leaving the region is not the same as switching the module off, but the
				// remembered waypoints and the solver plan all describe somewhere we are no
				// longer standing - drop them instead of flashing stale markers on the way back.
				resetState();
			}
			return;
		}
		wasActive = true;
		tick++;

		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		LocalPlayer player = mc.player;

		if (level != lastLevel) {
			lastLevel = level;
			remembered.clear();
			woken.clear();
			tracked.clear();
			clearSolver();
			setArmed(false, null);
		}
		if (level == null || player == null) {
			clearSolver();
			setArmed(false, null);
			return;
		}

		if (waypoints.value() && ++ticksSinceScan >= scanInterval.intValue()) {
			ticksSinceScan = 0;
			scanWaypoints(level, player);
		}
		if (solver.value()) {
			updateSolver(level, player);
		} else {
			clearSolver();
		}
		tickDebug(level, player);
	}

	// ---- waypoints ----------------------------------------------------------------------

	/**
	 * Only coordinates inside the render distance are re-read; everything else keeps whatever it
	 * last showed, so a marker spotted at the edge of view stays put once the chunk unloads.
	 */
	private void scanWaypoints(ClientLevel level, LocalPlayer player) {
		Minecraft mc = Minecraft.getInstance();
		int renderDistance = mc.options.getEffectiveRenderDistance();
		int playerChunkX = SectionPos.blockToSectionCoord(player.getBlockX());
		int playerChunkZ = SectionPos.blockToSectionCoord(player.getBlockZ());

		for (BlockPos coord : TikiCoords.all()) {
			int chunkX = SectionPos.blockToSectionCoord(coord.getX());
			int chunkZ = SectionPos.blockToSectionCoord(coord.getZ());
			if (Math.abs(chunkX - playerChunkX) > renderDistance || Math.abs(chunkZ - playerChunkZ) > renderDistance) continue;
			if (!level.isLoaded(coord)) continue;

			int skulls = 0;
			for (int dy = 1; dy <= SCAN_HEIGHT; dy++) {
				BlockPos above = coord.above(dy);
				if (level.isLoaded(above) && level.getBlockState(above).getBlock() instanceof AbstractSkullBlock) {
					skulls++;
				}
			}
			// Reading it up close is what clears a woken flag: once the heads are gone the next
			// tiki to stand here is a fresh puzzle.
			if (skulls == 0) {
				woken.remove(coord);
			}
			remembered.put(coord.immutable(), skulls >= REQUIRED_SKULLS && !woken.contains(coord));
		}
	}

	// ---- solver -------------------------------------------------------------------------

	private void updateSolver(ClientLevel level, LocalPlayer player) {
		BlockPos base = nearestSlotBase(level, player, range.intValue());
		if (base == null) {
			clearSolver();
			return;
		}
		slotBase = base;

		int[] read = new int[SCAN_HEIGHT];
		int hidden = -1;
		int hiddenCount = 0;
		for (int i = 0; i < SCAN_HEIGHT; i++) {
			read[i] = TikiStacks.readRotation(level, base.above(i));
			if (read[i] < 0) {
				hidden = i;
				hiddenCount++;
			}
		}
		rotations = read;
		hiddenIndex = hiddenCount == 1 ? hidden : -1;
		if (hiddenCount == 0) {
			// solveWithHidden has no equivalent walk-forward - it solves without ever learning the
			// hidden slot's real rotation, so there is no predictable future state to preview.
			List<TikiSolver.Plan> sequence = TikiSolver.solveSequence(read, rule());
			plan = sequence.isEmpty() ? null : sequence.get(0);
			futurePlans = sequence.size() > 1 ? sequence.subList(1, sequence.size()) : List.of();
		} else if (hiddenCount == 1) {
			plan = TikiSolver.solveWithHidden(read, hidden);
			futurePlans = List.of();
		} else {
			plan = null;
			futurePlans = List.of();
		}
	}

	/** The three blocks above the nearest stored coordinate that still holds a tiki. */
	private BlockPos nearestSlotBase(ClientLevel level, LocalPlayer player, int range) {
		double rangeSq = (double) range * range;
		Vec3 position = player.position();
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (BlockPos coord : TikiCoords.all()) {
			double distance = position.distanceToSqr(coord.getCenter());
			if (distance > rangeSq || distance >= bestDistance) continue;
			BlockPos base = coord.above();
			if (TikiStacks.readableSlots(level, base) < REQUIRED_SKULLS) continue;
			bestDistance = distance;
			best = base;
		}
		return best;
	}

	/** Which reading of the click rule the solver and the log verification plan against. */
	private TikiSolver.Rule rule() {
		return hypixelRule.value() ? TikiSolver.Rule.WIKI : TikiSolver.Rule.OBSERVED;
	}

	private void clearSolver() {
		slotBase = null;
		rotations = null;
		hiddenIndex = -1;
		plan = null;
		futurePlans = List.of();
	}

	// ---- debug --------------------------------------------------------------------------

	private void tickDebug(ClientLevel level, LocalPlayer player) {
		if (!debugLogging.value()) {
			if (armed) setArmed(false, null);
			return;
		}
		BlockPos nearest = nearestCoord(player, range.intValue());
		if ((nearest != null) != armed) {
			setArmed(nearest != null, nearest);
		}
		if (!armed) return;

		if (pending != null && tick - pending.tick >= VERIFY_DELAY) {
			runVerification(level);
		}
		if (++ticksSinceDebugScan >= DEBUG_RESCAN_INTERVAL) {
			ticksSinceDebugScan = 0;
			rescanSkulls(level, player);
		}
	}

	private BlockPos nearestCoord(LocalPlayer player, int range) {
		double rangeSq = (double) range * range;
		Vec3 position = player.position();
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (BlockPos coord : TikiCoords.all()) {
			double distance = position.distanceToSqr(coord.getCenter());
			if (distance <= rangeSq && distance < bestDistance) {
				bestDistance = distance;
				best = coord;
			}
		}
		return best;
	}

	private void setArmed(boolean value, BlockPos near) {
		if (armed == value) return;
		armed = value;
		pending = null;
		if (!armed) {
			log("DISARMED");
			tracked.clear();
			return;
		}
		if (TikiDebugLog.path() == null) {
			TikiDebugLog.open();
			if (TikiDebugLog.path() != null) {
				chat("Logging to " + TikiDebugLog.path().getFileName());
			}
		}
		log("ARMED near=" + format(near));
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null && mc.player != null) {
			rescanSkulls(mc.level, mc.player);
		}
	}

	private void rescanSkulls(ClientLevel level, LocalPlayer player) {
		int radius = trackRadius.intValue();
		BlockPos center = player.blockPosition();
		Map<BlockPos, Skull> current = new HashMap<>();

		for (int dx = -radius; dx <= radius; dx++) {
			for (int dy = -radius; dy <= radius; dy++) {
				for (int dz = -radius; dz <= radius; dz++) {
					BlockPos pos = center.offset(dx, dy, dz);
					if (!level.isLoaded(pos)) continue;
					Skull skull = snapshot(level, pos);
					if (skull != null) current.put(pos.immutable(), skull);
				}
			}
		}

		for (Map.Entry<BlockPos, Skull> entry : current.entrySet()) {
			Skull previous = tracked.get(entry.getKey());
			if (previous == null) {
				log("ADD    " + format(entry.getKey()) + " " + entry.getValue() + " " + stackOf(level, entry.getKey()));
			} else if (!previous.equals(entry.getValue())) {
				log("CHANGE " + format(entry.getKey()) + " " + previous.orientation() + " -> " + entry.getValue().orientation()
					+ " " + stackOf(level, entry.getKey()));
			}
		}
		for (Map.Entry<BlockPos, Skull> entry : tracked.entrySet()) {
			if (!current.containsKey(entry.getKey())) {
				log("REMOVE " + format(entry.getKey()) + " last=" + entry.getValue());
			}
		}

		tracked.clear();
		tracked.putAll(current);
	}

	// ---- events -------------------------------------------------------------------------

	public void onBlockChange(BlockPos pos, BlockState state) {
		if (!isActive() || !debugLogging.value() || !armed) return;
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		LocalPlayer player = mc.player;
		if (level == null || player == null) return;
		if (!player.blockPosition().closerThan(pos, trackRadius.intValue())) return;

		BlockPos key = pos.immutable();
		Skull previous = tracked.get(key);
		Skull now = state.getBlock() instanceof AbstractSkullBlock ? snapshot(level, key) : null;

		if (now == null) {
			if (previous != null) {
				tracked.remove(key);
				log("REMOVE " + format(key) + " last=" + previous + " (became " + state.getBlock().getName().getString() + ")");
			}
			return;
		}
		if (previous == null) {
			tracked.put(key, now);
			log("ADD    " + format(key) + " " + now + " " + stackOf(level, key));
		} else if (!previous.equals(now)) {
			tracked.put(key, now);
			log("CHANGE " + format(key) + " " + previous.orientation() + " -> " + now.orientation() + " " + stackOf(level, key));
		}
	}

	public void onClick(boolean rightClick) {
		if (!isActive()) return;
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		LocalPlayer player = mc.player;
		if (level == null || player == null) return;
		if (!debugLogging.value() || !armed) return;

		String kind = rightClick ? "RCLICK" : "LCLICK";
		ItemStack held = player.getMainHandItem();
		String heldName = " held=" + (held.isEmpty() ? "empty" : held.getHoverName().getString());
		HitResult hit = mc.hitResult;

		if (hit == null || hit.getType() == HitResult.Type.MISS) {
			log(kind + " target=nothing" + heldName);
			return;
		}
		if (hit instanceof EntityHitResult entityHit) {
			Entity entity = entityHit.getEntity();
			log(kind + " target=entity " + entity.getClass().getSimpleName() + " \"" + entity.getName().getString() + "\""
				+ " at=" + format(entity.blockPosition()) + heldName);
			return;
		}
		if (!(hit instanceof BlockHitResult blockHit)) return;

		BlockPos pos = blockHit.getBlockPos();
		Skull skull = snapshot(level, pos);
		if (skull == null) {
			log(kind + " target=block " + format(pos) + " " + level.getBlockState(pos).getBlock().getName().getString() + heldName);
			return;
		}
		log(kind + " " + format(pos) + " " + skull + " face=" + blockHit.getDirection().getName() + heldName
			+ " " + stackOf(level, pos));

		if (verifySolver.value()) {
			armVerification(level, pos, rightClick ? -1 : 1);
		}
	}

	public void onSound(Holder<SoundEvent> sound, double x, double y, double z, float volume, float pitch) {
		if (!isActive() || !debugLogging.value() || !armed || !trackSounds.value()) return;
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) return;

		double distance = player.position().distanceTo(new Vec3(x, y, z));
		if (distance > trackRadius.intValue() + SOUND_SLACK) return;

		String name = sound.unwrapKey()
			.map(key -> key.identifier().toString())
			.orElseGet(() -> sound.value().location().toString());
		log(String.format("SOUND  %s pitch=%.3f vol=%.2f at=%.1f,%.1f,%.1f d=%.1f", name, pitch, volume, x, y, z, distance));
	}

	/**
	 * Fed the raw server text before any chat-compacting mod can merge or cancel it, and matched
	 * with contains() so a counter suffix or rank prefix still hits.
	 */
	public void onChatMessage(String message) {
		if (!isActive()) return;
		if (message.contains(AWAKENED_MESSAGE)) {
			onTikiAwakened();
		}
		if (debugLogging.value() && armed && trackChat.value()) {
			log("CHAT   " + message);
		}
	}

	/** The tiki just woken stops being a target until a fresh one stands in its place. */
	private void onTikiAwakened() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) return;
		BlockPos coord = nearestCoord(player, range.intValue());
		if (coord == null) return;

		woken.add(coord.immutable());
		remembered.put(coord.immutable(), false);
		clearSolver();
		log("AWAKENED " + format(coord));
	}

	// ---- rendering ----------------------------------------------------------------------

	/** Geometry: waypoint boxes, the tracer, and vector labels if the font path is switched off. */
	public void render(LevelRenderContext context) {
		if (!isActive()) return;

		Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
		Vec3 camPos = camera.position();
		PoseStack poseStack = context.poseStack();
		MultiBufferSource.BufferSource bufferSource = context.bufferSource();
		boolean drew = false;

		if (waypoints.value() && !remembered.isEmpty()) {
			for (Map.Entry<BlockPos, Boolean> entry : remembered.entrySet()) {
				BlockPos pos = entry.getKey();
				int color = entry.getValue() ? validColor.argb() : invalidColor.argb();
				poseStack.pushPose();
				poseStack.translate(pos.getX() - camPos.x, pos.getY() - camPos.y, pos.getZ() - camPos.z);
				EspRenderer.renderBox(poseStack, bufferSource, 0, 0, 0, 1, 1, 1, color, color, BOX_LINE_WIDTH);
				poseStack.popPose();
				drew = true;
			}
			if (tracer.value() && renderTracer(poseStack, bufferSource, camera, camPos)) {
				drew = true;
			}
		}

		if (solver.value() && worldLabels.value() && eachSolverLabel((index, text, color, height, verticalOffset) ->
			EspRenderer.renderLabel(poseStack, bufferSource, camera.rotation(),
				labelX(index, camPos), labelY(index, height, camPos) + verticalOffset, labelZ(index, camPos),
				text, color, height, LABEL_LINE_WIDTH))) {
			drew = true;
		}

		if (debugLogging.value() && armed && worldLabels.value()) {
			int debugColor = lockedColor.argb();
			for (Map.Entry<BlockPos, Skull> entry : tracked.entrySet()) {
				BlockPos pos = entry.getKey();
				EspRenderer.renderLabel(poseStack, bufferSource, camera.rotation(),
					pos.getX() + 0.5 - camPos.x, pos.getY() + 0.6 - camPos.y, pos.getZ() + 0.5 - camPos.z,
					entry.getValue().label(), debugColor, DEBUG_LABEL_HEIGHT, LABEL_LINE_WIDTH);
				drew = true;
			}
		}

		if (drew) {
			bufferSource.endBatch(GeilerAddonsRenderTypes.ESP_QUADS);
			bufferSource.endBatch(GeilerAddonsRenderTypes.ESP_LINES);
		}
	}

	/**
	 * Labels in the real game font, drawn on the HUD at the projected position of each skull.
	 *
	 * <p>In-world text does not render from any level-render stage this mod can reach, so the
	 * label is placed by projecting the world position into GUI coordinates and drawing it with
	 * the same text call the Click GUI uses.
	 */
	public void renderHud(GuiGraphicsExtractor graphics) {
		if (!isActive() || !solver.value() || worldLabels.value()) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || mc.options.hideGui) return;

		Camera camera = mc.gameRenderer.getMainCamera();
		Font font = mc.font;
		eachSolverLabel((index, text, color, height, verticalOffset) -> {
			Vec3 world = new Vec3(slotBase.getX() + 0.5, slotBase.getY() + index + LABEL_ANCHOR + verticalOffset, slotBase.getZ() + 0.5);
			float[] screen = WorldToScreen.project(camera, world);
			if (screen == null) return;
			graphics.text(font, text, Math.round(screen[0]) - font.width(text) / 2, Math.round(screen[1]) - HUD_LABEL_HALF_HEIGHT, color);
		});
	}

	/** @return true if anything was emitted */
	private boolean eachSolverLabel(LabelSink sink) {
		if (slotBase == null || rotations == null) return false;

		if (plan == null) {
			if (!showUnknown.value()) return false;
			sink.accept(1, UNKNOWN_LABEL, rightColor.argb(), labelHeight.value(), 0);
			return true;
		}

		// How many labels already sit on each head, so a future run landing on an occupied head
		// stacks below the ones already there instead of overlapping them.
		int[] stack = new int[SCAN_HEIGHT];
		boolean any = false;
		for (int i = 0; i < SCAN_HEIGHT; i++) {
			String text;
			int color;
			float height = labelHeight.value();
			if (plan.index() == i) {
				String count = plan.clicks() == TikiSolver.UNKNOWN_CLICKS ? UNKNOWN_LABEL : String.valueOf(plan.clicks());
				text = (plan.direction() > 0 ? "+" : "-") + count;
				color = plan.direction() > 0 ? leftColor.argb() : rightColor.argb();
			} else if (showLocked.value() && hiddenIndex < 0 && TikiSolver.isLocked(rotations, i, rule())) {
				text = LOCKED_LABEL;
				color = lockedColor.argb();
				height *= LOCKED_LABEL_SCALE;
			} else {
				continue;
			}
			sink.accept(i, text, color, height, 0);
			stack[i]++;
			any = true;
		}

		if (showFuturePlan.value() && !futurePlans.isEmpty()) {
			for (TikiSolver.Plan future : futurePlans) {
				int index = future.index();
				String count = String.valueOf(future.clicks());
				String text = (future.direction() > 0 ? "+" : "-") + count;
				double verticalOffset = -stack[index] * FUTURE_STACK_STEP;
				sink.accept(index, text, futureColor.argb(), labelHeight.value(), verticalOffset);
				stack[index]++;
				any = true;
			}
		}

		return any;
	}

	private interface LabelSink {
		/** @param verticalOffset added straight into the world Y the label is placed at */
		void accept(int index, String text, int color, float height, double verticalOffset);
	}

	private double labelOffsetX(Vec3 camPos) {
		double dx = camPos.x - (slotBase.getX() + 0.5);
		double dz = camPos.z - (slotBase.getZ() + 0.5);
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		return horizontal > 1.0e-3 ? -dz / horizontal * LABEL_OFFSET : 0;
	}

	private double labelOffsetZ(Vec3 camPos) {
		double dx = camPos.x - (slotBase.getX() + 0.5);
		double dz = camPos.z - (slotBase.getZ() + 0.5);
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		return horizontal > 1.0e-3 ? dx / horizontal * LABEL_OFFSET : 0;
	}

	private double labelX(int index, Vec3 camPos) {
		return slotBase.getX() + 0.5 + labelOffsetX(camPos) - camPos.x;
	}

	private double labelY(int index, float height, Vec3 camPos) {
		return slotBase.getY() + index + LABEL_ANCHOR + height / 2.0f - camPos.y;
	}

	private double labelZ(int index, Vec3 camPos) {
		return slotBase.getZ() + 0.5 + labelOffsetZ(camPos) - camPos.z;
	}

	private boolean renderTracer(PoseStack poseStack, MultiBufferSource bufferSource, Camera camera, Vec3 camPos) {
		BlockPos target = closestValid(camPos);
		if (target == null) return false;

		Vector3fc forward = camera.forwardVector();
		Vec3 center = target.getCenter();
		EspRenderer.renderLine(poseStack, bufferSource,
			forward.x() * TRACER_START_DISTANCE, forward.y() * TRACER_START_DISTANCE, forward.z() * TRACER_START_DISTANCE,
			center.x - camPos.x, center.y - camPos.y, center.z - camPos.z,
			tracerColor.argb(), tracerWidth.value());
		return true;
	}

	private BlockPos closestValid(Vec3 from) {
		BlockPos closest = null;
		double closestDistance = Double.MAX_VALUE;
		for (Map.Entry<BlockPos, Boolean> entry : remembered.entrySet()) {
			if (!entry.getValue()) continue;
			double distance = from.distanceToSqr(entry.getKey().getCenter());
			if (distance < closestDistance) {
				closestDistance = distance;
				closest = entry.getKey();
			}
		}
		return closest;
	}

	// ---- verification -------------------------------------------------------------------

	private void armVerification(ClientLevel level, BlockPos clicked, int direction) {
		BlockPos base = clicked;
		while (TikiStacks.isSkull(level, base.below())) {
			base = base.below();
		}
		int index = clicked.getY() - base.getY();
		if (index < 0 || index >= SCAN_HEIGHT) return;
		int[] before = TikiStacks.readRotations(level, base);
		if (before == null) return;
		pending = new PendingVerification(base.immutable(), index, direction, before, tick);
	}

	private void runVerification(ClientLevel level) {
		PendingVerification check = pending;
		pending = null;

		int[] actual = TikiStacks.readRotations(level, check.base);
		if (actual == null) {
			log("VERIFY skipped - stack gone (completed or out of range)");
			return;
		}
		if (Arrays.equals(actual, check.before)) {
			log("VERIFY no-op - click did not register (rotation cooldown)");
			return;
		}
		int[] expected = TikiSolver.applyMove(check.before, check.index, check.direction, rule());
		if (Arrays.equals(expected, actual)) {
			log("VERIFY ok " + Arrays.toString(check.before) + " -> " + Arrays.toString(actual));
		} else {
			log("VERIFY MISMATCH click=idx" + check.index + " dir=" + (check.direction > 0 ? "+" : "-")
				+ " before=" + Arrays.toString(check.before)
				+ " expected=" + Arrays.toString(expected)
				+ " actual=" + Arrays.toString(actual));
		}
	}

	// ---- helpers ------------------------------------------------------------------------

	private static Skull snapshot(ClientLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof AbstractSkullBlock)) return null;
		int rotation = state.hasProperty(SkullBlock.ROTATION) ? state.getValue(SkullBlock.ROTATION) : -1;
		String facing = state.hasProperty(WallSkullBlock.FACING) ? state.getValue(WallSkullBlock.FACING).getName() : "";
		return new Skull(state.getBlock().getName().getString(), rotation, facing, ownerOf(level, pos));
	}

	private static String ownerOf(ClientLevel level, BlockPos pos) {
		BlockEntity blockEntity = level.getBlockEntity(pos);
		if (!(blockEntity instanceof SkullBlockEntity skull)) return "";
		var profile = skull.getOwnerProfile();
		if (profile == null) return "";

		String name = profile.name().orElse("?");
		// Hypixel's decorative heads share a blank-ish name, so the texture blob is what actually
		// tells two tiki variants apart - hashed to keep the log line short.
		String skin = "";
		var textures = profile.partialProfile().properties().get("textures");
		if (!textures.isEmpty()) {
			skin = Integer.toHexString(textures.iterator().next().value().hashCode());
		}
		return name + (skin.isEmpty() ? "" : "/" + skin);
	}

	private static String stackOf(ClientLevel level, BlockPos pos) {
		BlockPos base = pos;
		while (TikiStacks.isSkull(level, base.below())) {
			base = base.below();
		}
		List<String> labels = new ArrayList<>();
		BlockPos cursor = base;
		while (TikiStacks.isSkull(level, cursor)) {
			Skull skull = snapshot(level, cursor);
			labels.add(skull == null ? "?" : skull.label());
			cursor = cursor.above();
		}
		return "stack@" + format(base) + "=" + labels;
	}

	private void log(String line) {
		if (!debugLogging.value()) return;
		TikiDebugLog.write(tick, line);
		if (echoToChat.value()) {
			chat(line);
		}
	}

	private static void chat(String message) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui == null) return;
		mc.gui.getChat().addClientSystemMessage(Component.literal("[Tiki] ").withStyle(ChatFormatting.AQUA)
			.append(Component.literal(message).withStyle(ChatFormatting.GRAY)));
	}

	private static String format(BlockPos pos) {
		return pos == null ? "none" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	private static void openCoordManager() {
		Minecraft mc = Minecraft.getInstance();
		mc.setScreen(new TikiCoordManagerScreen(mc.screen));
	}

	private record PendingVerification(BlockPos base, int index, int direction, int[] before, long tick) {
	}

	/** @param rotation 0-15 for a floor skull, -1 for a wall skull, which uses {@code facing} */
	private record Skull(String blockId, int rotation, String facing, String owner) {
		String orientation() {
			return rotation >= 0 ? "rot=" + rotation : "facing=" + facing;
		}

		String label() {
			return rotation >= 0 ? String.valueOf(rotation) : facing;
		}

		@Override
		public String toString() {
			return orientation() + " block=" + blockId + (owner.isEmpty() ? "" : " owner=" + owner);
		}
	}
}
