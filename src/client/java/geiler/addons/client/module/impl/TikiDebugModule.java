package geiler.addons.client.module.impl;

import com.mojang.blaze3d.vertex.PoseStack;
import geiler.addons.client.config.TikiCoords;
import geiler.addons.client.config.TikiDebugLog;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.render.EspRenderer;
import geiler.addons.client.tiki.TikiSolver;
import geiler.addons.client.tiki.TikiStacks;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Research tool for working out how the tiki puzzle behaves. While the player stands near one of
 * the stored tiki coordinates it records, into a per-session log file, every skull rotation it can
 * see, every rotation change, every left/right click and the sounds and chat that follow - so the
 * click-to-rotation rule can be read straight off the timeline.
 *
 * <p>Deliberately gated on proximity: away from a tiki this would just log every skull in the world.
 */
public final class TikiDebugModule extends Module {
	/** Full re-sweep cadence; block-update packets already deliver changes as they happen. */
	private static final int RESCAN_INTERVAL = 20;
	/** Extra slack past the track radius for sounds, which are emitted from the block's center. */
	private static final double SOUND_SLACK = 8.0;
	private static final float LABEL_SCALE = 0.025f;
	/** Ticks to wait after a click before checking the result against the solver's move rule. */
	private static final int VERIFY_DELAY = 8;

	public static final TikiDebugModule INSTANCE = new TikiDebugModule();

	private final ColorSetting labelColor;
	private final NumberSetting armRange;
	private final NumberSetting trackRadius;
	private final BooleanSetting showRotations;
	private final BooleanSetting trackSounds;
	private final BooleanSetting trackChat;
	private final BooleanSetting verifySolver;
	private final BooleanSetting echoToChat;

	private final Map<BlockPos, Skull> tracked = new HashMap<>();

	private boolean armed;
	private long tick;
	private int ticksSinceRescan;
	private ClientLevel lastLevel;
	private PendingVerification pending;

	private TikiDebugModule() {
		this(
			new ColorSetting("Label Color", 255, 255, 0, 255),
			new NumberSetting("Arm Range", 1, 32, 7, true),
			new NumberSetting("Track Radius", 2, 32, 8, true),
			new BooleanSetting("Show Rotations", true),
			new BooleanSetting("Track Sounds", true),
			new BooleanSetting("Track Chat", true),
			new BooleanSetting("Verify Solver", true),
			new BooleanSetting("Echo To Chat", false)
		);
	}

	private TikiDebugModule(
		ColorSetting labelColor, NumberSetting armRange, NumberSetting trackRadius,
		BooleanSetting showRotations, BooleanSetting trackSounds, BooleanSetting trackChat,
		BooleanSetting verifySolver, BooleanSetting echoToChat
	) {
		super("Tiki Debug", "Logs skull rotations, clicks, sounds and chat near tiki coordinates.", Category.HUNTING,
			List.of(labelColor),
			List.of(armRange, trackRadius),
			List.of(showRotations, trackSounds, trackChat, verifySolver, echoToChat),
			List.of());
		this.labelColor = labelColor;
		this.armRange = armRange;
		this.trackRadius = trackRadius;
		this.showRotations = showRotations;
		this.trackSounds = trackSounds;
		this.trackChat = trackChat;
		this.verifySolver = verifySolver;
		this.echoToChat = echoToChat;
	}

	@Override
	protected void onEnable() {
		tracked.clear();
		armed = false;
		tick = 0;
		ticksSinceRescan = 0;
		TikiDebugLog.open();
		if (TikiDebugLog.path() != null) {
			chat("Tiki debug logging to " + TikiDebugLog.path().getFileName());
		} else {
			chat("Tiki debug could not open its log file - see the game log.");
		}
	}

	@Override
	protected void onDisable() {
		tracked.clear();
		armed = false;
		pending = null;
		TikiDebugLog.close();
	}

	// ---- lifecycle ----------------------------------------------------------------------

	public void tick() {
		if (!isEnabled()) return;
		tick++;

		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		LocalPlayer player = mc.player;

		if (level != lastLevel) {
			lastLevel = level;
			setArmed(false, null);
		}
		if (level == null || player == null) {
			setArmed(false, null);
			return;
		}

		BlockPos nearest = nearestTiki(player);
		if ((nearest != null) != armed) {
			setArmed(nearest != null, nearest);
		}
		if (!armed) return;

		// Both block updates from one rotation land within a tick or two of each other, so the
		// comparison waits for them to settle rather than judging a half-applied state.
		if (pending != null && tick - pending.tick >= VERIFY_DELAY) {
			runVerification(level);
		}

		if (++ticksSinceRescan >= RESCAN_INTERVAL) {
			ticksSinceRescan = 0;
			rescan(level, player);
		}
	}

	/** @return the stored coordinate the player is standing near, or null if none is in range */
	private BlockPos nearestTiki(LocalPlayer player) {
		double range = armRange.intValue();
		double rangeSq = range * range;
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
		if (armed) {
			log("ARMED near=" + format(near));
			Minecraft mc = Minecraft.getInstance();
			if (mc.level != null && mc.player != null) {
				// Baseline sweep so the first real change has something to diff against.
				rescan(mc.level, mc.player);
			}
		} else {
			log("DISARMED");
			tracked.clear();
		}
	}

	// ---- scanning -----------------------------------------------------------------------

	private void rescan(ClientLevel level, LocalPlayer player) {
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

	/** Immediate, precise reaction to a server block update - the periodic sweep is only a net. */
	public void onBlockChange(BlockPos pos, BlockState state) {
		if (!isEnabled() || !armed) return;
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

	private static Skull snapshot(ClientLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof AbstractSkullBlock)) return null;

		int rotation = state.hasProperty(SkullBlock.ROTATION) ? state.getValue(SkullBlock.ROTATION) : -1;
		String facing = state.hasProperty(WallSkullBlock.FACING) ? state.getValue(WallSkullBlock.FACING).getName() : "";
		String blockId = state.getBlock().getName().getString();
		return new Skull(blockId, rotation, facing, ownerOf(level, pos));
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

	/** The whole vertical run of skulls this position belongs to, e.g. {@code stack@x,y,z=[4,4,12]}. */
	private static String stackOf(ClientLevel level, BlockPos pos) {
		BlockPos base = pos;
		while (level.isLoaded(base.below()) && level.getBlockState(base.below()).getBlock() instanceof AbstractSkullBlock) {
			base = base.below();
		}
		List<String> labels = new ArrayList<>();
		BlockPos cursor = base;
		while (level.isLoaded(cursor) && level.getBlockState(cursor).getBlock() instanceof AbstractSkullBlock) {
			Skull skull = snapshot(level, cursor);
			labels.add(skull == null ? "?" : skull.label());
			cursor = cursor.above();
		}
		return "stack@" + format(base) + "=" + labels;
	}

	// ---- event hooks --------------------------------------------------------------------

	public void onClick(boolean rightClick) {
		if (!isEnabled() || !armed) return;
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		LocalPlayer player = mc.player;
		if (level == null || player == null) return;

		String kind = rightClick ? "RCLICK" : "LCLICK";
		ItemStack held = player.getMainHandItem();
		String heldName = " held=" + (held.isEmpty() ? "empty" : held.getHoverName().getString());
		HitResult hit = mc.hitResult;

		// Logged even when the crosshair isn't on a skull: clicks that land on an interaction
		// entity in front of the block used to vanish from the log while still rotating it.
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

		log(kind + " " + format(pos) + " " + skull
			+ " face=" + blockHit.getDirection().getName()
			+ heldName
			+ " " + stackOf(level, pos));

		if (verifySolver.value()) {
			armVerification(level, pos, rightClick ? -1 : 1);
		}
	}

	/**
	 * Snapshots the stack so the next tick batch can be checked against {@link TikiSolver}'s move
	 * rule - if Hypixel ever changes the puzzle, the log says so instead of the solver quietly
	 * giving wrong advice.
	 */
	private void armVerification(ClientLevel level, BlockPos clicked, int direction) {
		BlockPos base = clicked;
		while (TikiStacks.isSkull(level, base.below())) {
			base = base.below();
		}
		if (!TikiStacks.isStackBase(level, base)) return;
		int index = clicked.getY() - base.getY();
		int[] before = TikiStacks.readRotations(level, base);
		if (before == null || index < 0 || index >= TikiStacks.STACK_HEIGHT) return;
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
		// The top skull is never clicked in practice, so the rule for index 2 is inferred; the
		// solver never suggests it, but a real click there is still worth checking.
		int[] expected = TikiSolver.applyMove(check.before, check.index, check.direction);
		if (Arrays.equals(expected, actual)) {
			log("VERIFY ok " + Arrays.toString(check.before) + " -> " + Arrays.toString(actual));
		} else {
			log("VERIFY MISMATCH click=idx" + check.index + " dir=" + (check.direction > 0 ? "+" : "-")
				+ " before=" + Arrays.toString(check.before)
				+ " expected=" + Arrays.toString(expected)
				+ " actual=" + Arrays.toString(actual));
		}
	}

	public void onSound(Holder<SoundEvent> sound, double x, double y, double z, float volume, float pitch) {
		if (!isEnabled() || !armed || !trackSounds.value()) return;
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) return;

		double distance = player.position().distanceTo(new Vec3(x, y, z));
		if (distance > trackRadius.intValue() + SOUND_SLACK) return;

		String name = sound.unwrapKey()
			.map(key -> key.identifier().toString())
			.orElseGet(() -> sound.value().location().toString());
		log(String.format("SOUND  %s pitch=%.3f vol=%.2f at=%.1f,%.1f,%.1f d=%.1f", name, pitch, volume, x, y, z, distance));
	}

	public void onChatMessage(String message) {
		if (!isEnabled() || !armed || !trackChat.value()) return;
		log("CHAT   " + message);
	}

	// ---- rendering ----------------------------------------------------------------------

	public void render(LevelRenderContext context) {
		if (!isEnabled() || !armed || !showRotations.value() || tracked.isEmpty()) return;

		Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
		Vec3 camPos = camera.position();
		PoseStack poseStack = context.poseStack();
		MultiBufferSource.BufferSource bufferSource = context.bufferSource();
		int color = labelColor.argb();

		for (Map.Entry<BlockPos, Skull> entry : tracked.entrySet()) {
			BlockPos pos = entry.getKey();
			EspRenderer.renderLabel(poseStack, bufferSource, camera.rotation(),
				pos.getX() + 0.5 - camPos.x, pos.getY() + 1.0 - camPos.y, pos.getZ() + 0.5 - camPos.z,
				entry.getValue().label(), color, LABEL_SCALE);
		}
		bufferSource.endBatch();
	}

	// ---- helpers ------------------------------------------------------------------------

	private void log(String line) {
		TikiDebugLog.write(tick, line);
		if (echoToChat.value()) {
			chat(line);
		}
	}

	private static void chat(String message) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui == null) return;
		mc.gui.getChat().addClientSystemMessage(Component.literal("[TikiDebug] ").withStyle(ChatFormatting.AQUA)
			.append(Component.literal(message).withStyle(ChatFormatting.GRAY)));
	}

	private static String format(BlockPos pos) {
		return pos == null ? "none" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	/** A click whose effect hasn't been read back yet - see {@link #runVerification}. */
	private record PendingVerification(BlockPos base, int index, int direction, int[] before, long tick) {
	}

	/**
	 * @param rotation 0-15 for a floor skull, -1 for a wall skull (which uses {@code facing} instead)
	 */
	private record Skull(String blockId, int rotation, String facing, String owner) {
		String orientation() {
			return rotation >= 0 ? "rot=" + rotation : "facing=" + facing;
		}

		/** Short form for the in-world overlay. */
		String label() {
			return rotation >= 0 ? String.valueOf(rotation) : facing;
		}

		@Override
		public String toString() {
			return orientation() + " block=" + blockId + (owner.isEmpty() ? "" : " owner=" + owner);
		}
	}
}
