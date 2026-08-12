package geiler.addons.client.module.impl;

import com.mojang.blaze3d.vertex.PoseStack;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.render.EspRenderer;
import geiler.addons.client.render.GeilerAddonsRenderTypes;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Ported from the original ChatTriggers "i4helper" module: highlights the 9-block device
 * panel on the Catacombs F7/M7 4th-device puzzle and marks which block to click next.
 */
public final class I4HelperModule extends Module {
	private static final BlockPos[] POSITIONS = {
		new BlockPos(68, 130, 50), new BlockPos(66, 130, 50), new BlockPos(64, 130, 50),
		new BlockPos(68, 128, 50), new BlockPos(66, 128, 50), new BlockPos(64, 128, 50),
		new BlockPos(68, 126, 50), new BlockPos(66, 126, 50), new BlockPos(64, 126, 50)
	};

	/** Feet height of the pressure plate. The device itself disables the instant you leave it, so
	 * this is deliberately exact rather than a tolerant band - stepping off (a jump included) is
	 * supposed to reset the overlay, because the real device just went inactive too. */
	private static final int DEVICE_Y = 127;

	/** Not on the device, or not yet known - no highlight. */
	private static final int NONE = 0;
	/** Still blue terracotta, not yet hit - shown red, still a candidate for a future target. */
	private static final int REMAINING = 1;
	/** Currently emerald - the active shot target, shown green. */
	private static final int ACTIVE = 2;
	/** Was active and got hit, reverted to blue terracotta - done, no highlight, never re-targeted. */
	private static final int COMPLETED = 3;

	private static final Pattern COMPLETED_DEVICE = Pattern.compile("^(.*) completed a device! \\(\\d/\\d\\)$");
	private static final Pattern[] SUPPRESSED_SUBTITLES = {
		Pattern.compile("^.* completed a device! \\(\\d/\\d\\)$"),
		Pattern.compile("^.* activated a terminal! \\(\\d/\\d\\)$"),
		Pattern.compile("^.* activated a lever! \\(\\d/\\d\\)$"),
		Pattern.compile("^The gate will open in 5 seconds!$"),
		Pattern.compile("^The gate has been destroyed!$")
	};

	// Must come after the static constants above: the constructor (triggered by this field's
	// initializer) reads POSITIONS via the highlighted-array field initializer.
	public static final I4HelperModule INSTANCE = new I4HelperModule();

	private final ColorSetting completedColor;
	private final ColorSetting activeColor;
	private final ColorSetting aimColor;
	private final ColorSetting prefireColor;
	private final NumberSetting aimSphereSize;
	private final NumberSetting prefireSphereSize;

	private final int[] highlighted = new int[POSITIONS.length];
	private final List<DelayedSound> delayedSounds = new ArrayList<>();

	private boolean onDevice;
	private boolean notified;

	private I4HelperModule() {
		this(
			new ColorSetting("Completed Block", 255, 0, 0, 255),
			new ColorSetting("Active Block", 0, 255, 0, 255),
			new ColorSetting("Aim Sphere", 255, 255, 0, 200),
			new ColorSetting("Prefire Sphere", 255, 140, 0, 200),
			new NumberSetting("Aim Sphere Size", 0.05f, 0.6f, 0.25f),
			new NumberSetting("Prefire Sphere Size", 0.05f, 0.6f, 0.2f)
		);
	}

	private I4HelperModule(
		ColorSetting completedColor, ColorSetting activeColor, ColorSetting aimColor, ColorSetting prefireColor,
		NumberSetting aimSphereSize, NumberSetting prefireSphereSize
	) {
		super("i4 helper", "Highlights the F7/M7 device panel and marks the next block to click.", Category.F7,
			completedColor, activeColor, aimColor, prefireColor, aimSphereSize, prefireSphereSize);
		this.completedColor = completedColor;
		this.activeColor = activeColor;
		this.aimColor = aimColor;
		this.prefireColor = prefireColor;
		this.aimSphereSize = aimSphereSize;
		this.prefireSphereSize = prefireSphereSize;
		// Each sphere's color sits next to its own size slider rather than all colors together,
		// so a row reads as one thing to adjust instead of two halves in different sections.
		group(
			new SettingGroup("Blocks", completedColor, activeColor),
			new SettingGroup("Aim Spheres", aimColor, aimSphereSize, prefireColor, prefireSphereSize)
		);
	}

	@Override
	protected void onEnable() {
		onDevice = false;
		notified = false;
		Arrays.fill(highlighted, NONE);
		delayedSounds.clear();
	}

	@Override
	protected void onDisable() {
		onDevice = false;
		notified = false;
		Arrays.fill(highlighted, NONE);
		delayedSounds.clear();
	}

	public void tick() {
		if (!isEnabled()) return;

		LocalPlayer player = Minecraft.getInstance().player;
		boolean nowOnDevice = false;
		if (player != null) {
			double x = player.getX();
			double z = player.getZ();
			int y = Mth.floor(player.getY());
			nowOnDevice = x > 62 && x < 65 && y == DEVICE_Y && z > 33 && z < 37;
		}

		if (nowOnDevice != onDevice) {
			onDevice = nowOnDevice;
			if (onDevice) {
				// The server sends no update packet for blocks that haven't changed, so the panel
				// is assumed untouched and then corrected against the level below.
				Arrays.fill(highlighted, REMAINING);
				syncActiveFromLevel(true);
			} else {
				notified = false;
				Arrays.fill(highlighted, NONE);
			}
		} else if (onDevice) {
			// Picks the emerald back up after a chunk reload or a missed packet. Upgrade-only, so
			// a block already known COMPLETED is never demoted back to REMAINING.
			syncActiveFromLevel(false);
		}

		tickDelayedSounds();
	}

	/**
	 * Reads the panel straight out of the level so arriving part-way through a device shows the
	 * live target instead of a blank board. Only the emerald is recoverable: a completed block
	 * reverts to the same blue terracotta an untouched one shows, so past progress genuinely
	 * cannot be read back and those positions stay REMAINING until seen completed.
	 *
	 * @param reset when true, every non-emerald position is forced back to REMAINING
	 */
	private void syncActiveFromLevel(boolean reset) {
		var level = Minecraft.getInstance().level;
		if (level == null) return;
		for (int i = 0; i < POSITIONS.length; i++) {
			BlockPos pos = POSITIONS[i];
			if (!level.isLoaded(pos)) continue;
			if (level.getBlockState(pos).is(Blocks.EMERALD_BLOCK)) {
				highlighted[i] = ACTIVE;
			} else if (reset) {
				highlighted[i] = REMAINING;
			}
		}
	}

	public void onBlockChange(BlockPos pos, BlockState state) {
		if (!isEnabled() || !onDevice) return;
		int index = indexOf(pos);
		if (index == -1) return;

		if (state.is(Blocks.BLUE_TERRACOTTA)) {
			// Only a just-hit active target reverts to blue terracotta during play, so this
			// always means "done" - anything else turning up blue terracotta just stays open.
			highlighted[index] = highlighted[index] == ACTIVE ? COMPLETED : REMAINING;
		} else if (state.is(Blocks.EMERALD_BLOCK)) {
			highlighted[index] = ACTIVE;
		}
	}

	public void onChatMessage(String message) {
		if (!isEnabled() || !onDevice) return;
		var matcher = COMPLETED_DEVICE.matcher(message);
		if (!matcher.matches()) return;

		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) return;
		// Defensive: Hypixel usually sends styled components, whose flattened text carries no
		// section codes, but it does embed legacy codes in some messages - strip either way.
		String name = ChatFormatting.stripFormatting(matcher.group(1));
		if (name == null || !name.equals(player.getGameProfile().name())) return;

		notifyDone();
	}

	/** @return true if the subtitle should be suppressed */
	public boolean onSubtitle(String message) {
		if (!isEnabled() || !onDevice) return false;
		for (Pattern pattern : SUPPRESSED_SUBTITLES) {
			if (pattern.matcher(message).matches()) return true;
		}
		return false;
	}

	public void render(LevelRenderContext context) {
		if (!isEnabled() || !onDevice) return;

		Vec3 camPos = Minecraft.getInstance().gameRenderer.getMainCamera().position();
		PoseStack poseStack = context.poseStack();
		MultiBufferSource.BufferSource bufferSource = context.bufferSource();

		// Neighbors already completed don't need a second shot, so aim spots favor whichever
		// side is still open - see computeAimSpots.
		List<Integer> done = new ArrayList<>();
		int activeIndex = -1;
		for (int i = 0; i < highlighted.length; i++) {
			if (highlighted[i] == COMPLETED) done.add(i);
			if (highlighted[i] == ACTIVE) activeIndex = i;
		}

		// Boxes: only completed (red) and the active target (green) get one - still-open blocks
		// stay invisible, matching the vanilla panel showing nothing distinctive about them.
		for (int i = 0; i < highlighted.length; i++) {
			if (highlighted[i] != COMPLETED && highlighted[i] != ACTIVE) continue;

			BlockPos pos = POSITIONS[i];
			int color = highlighted[i] == COMPLETED ? completedColor.argb() : activeColor.argb();

			poseStack.pushPose();
			poseStack.translate(pos.getX() - camPos.x, pos.getY() - camPos.y, pos.getZ() - camPos.z);
			EspRenderer.renderBox(poseStack, bufferSource, 0, 0, 0, 1, 1, 1, color, color, 2.0f);
			poseStack.popPose();
		}

		// Yellow: the exact aim spot(s) for the current emerald target.
		List<double[]> activeSpots = List.of();
		if (activeIndex != -1) {
			activeSpots = computeAimSpots(activeIndex, done);
			for (double[] spot : activeSpots) {
				sphereAt(poseStack, bufferSource, camPos, spot[0], spot[1], spot[2], aimSphereSize.value(), aimColor.argb());
			}
		}

		// Orange: every still-open block's own aim spot(s), so you can see where a future emerald
		// could land - skipping any spot that already coincides with the yellow one.
		for (int i = 0; i < highlighted.length; i++) {
			if (highlighted[i] != REMAINING) continue;
			for (double[] spot : computeAimSpots(i, done)) {
				if (matchesAny(spot, activeSpots)) continue;
				sphereAt(poseStack, bufferSource, camPos, spot[0], spot[1], spot[2], prefireSphereSize.value(), prefireColor.argb());
			}
		}

		bufferSource.endBatch(GeilerAddonsRenderTypes.ESP_QUADS);
		bufferSource.endBatch(GeilerAddonsRenderTypes.ESP_LINES);
	}

	/** Each entry is {x, y, z} in world space; sphere size is applied by the caller. */
	private List<double[]> computeAimSpots(int i, List<Integer> done) {
		BlockPos pos = POSITIONS[i];
		double blockX = pos.getX();
		double blockY = pos.getY() + 1;
		double blockZ = pos.getZ();
		int columnIndex = i % 3;
		List<double[]> spots = new ArrayList<>();

		if (columnIndex == 2) {
			spots.add(new double[]{blockX + 1.5, blockY, blockZ});
		} else if (columnIndex == 0) {
			spots.add(new double[]{blockX - 0.5, blockY, blockZ});
		} else {
			boolean leftDone = done.contains(i + 1);
			boolean rightDone = done.contains(i - 1);
			if (leftDone && !rightDone) {
				spots.add(new double[]{blockX + 1.5, blockY, blockZ});
			} else if (rightDone && !leftDone) {
				spots.add(new double[]{blockX - 0.5, blockY, blockZ});
			} else {
				spots.add(new double[]{blockX - 0.5, blockY, blockZ});
				spots.add(new double[]{blockX + 1.5, blockY, blockZ});
			}
		}
		return spots;
	}

	private static boolean matchesAny(double[] spot, List<double[]> others) {
		for (double[] other : others) {
			if (Math.abs(spot[0] - other[0]) < 1e-6 && Math.abs(spot[1] - other[1]) < 1e-6 && Math.abs(spot[2] - other[2]) < 1e-6) {
				return true;
			}
		}
		return false;
	}

	private void sphereAt(PoseStack poseStack, MultiBufferSource bufferSource, Vec3 camPos, double x, double y, double z, float radius, int color) {
		EspRenderer.renderSphere(poseStack, bufferSource, x - camPos.x, y - camPos.y, z - camPos.z, radius, color);
	}

	private void notifyDone() {
		if (notified) return;
		notified = true;

		Minecraft mc = Minecraft.getInstance();
		mc.gui.setTimes(0, 30, 0);
		mc.gui.setTitle(Component.literal(""));
		mc.gui.setSubtitle(Component.literal("i4 Done!").withStyle(ChatFormatting.GREEN));

		LocalPlayer player = mc.player;
		if (player != null) {
			player.level().playLocalSound(player, SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 1.0f, 1.414f);
		}
		delayedSounds.add(new DelayedSound(3, 1.587f));
		delayedSounds.add(new DelayedSound(6, 1.782f));
	}

	private void tickDelayedSounds() {
		if (delayedSounds.isEmpty()) return;
		LocalPlayer player = Minecraft.getInstance().player;

		var iterator = delayedSounds.iterator();
		while (iterator.hasNext()) {
			DelayedSound sound = iterator.next();
			sound.ticksRemaining--;
			if (sound.ticksRemaining <= 0) {
				if (player != null) {
					player.level().playLocalSound(player, SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 1.0f, sound.pitch);
				}
				iterator.remove();
			}
		}
	}

	private static int indexOf(BlockPos pos) {
		for (int i = 0; i < POSITIONS.length; i++) {
			if (POSITIONS[i].equals(pos)) return i;
		}
		return -1;
	}

	private static final class DelayedSound {
		int ticksRemaining;
		final float pitch;

		DelayedSound(int ticksRemaining, float pitch) {
			this.ticksRemaining = ticksRemaining;
			this.pitch = pitch;
		}
	}
}
