package geiler.addons.client.module.impl;

import com.mojang.blaze3d.vertex.PoseStack;
import geiler.addons.client.config.TikiCoords;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.render.EspRenderer;
import geiler.addons.client.render.GeilerAddonsRenderTypes;
import geiler.addons.client.tiki.TikiSolver;
import geiler.addons.client.tiki.TikiStacks;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Floats the next click straight onto the skull that needs it: green "+2" means left-click it
 * twice, red "-3" means right-click it three times. Skulls that have locked in show a dot.
 *
 * <p>A tiki occupies the three blocks above one of the stored coordinates. Slots are read from
 * there rather than by looking for a column of skulls, because a slot can hold an ordinary block
 * instead of a head - it still rotates and still counts, so the tiki has to be located by
 * position rather than by what happens to be standing in it. Such a slot gets "+?": keep clicking
 * it, the count can't be known. Tikis outside the coordinate list are still picked up by a plain
 * column search, as long as all three heads are visible.
 */
public final class TikiSolverModule extends Module {
	/** Ticks between column sweeps for unlisted tikis; the stored-coordinate path runs every tick. */
	private static final int SEARCH_INTERVAL = 10;
	private static final String LOCKED_LABEL = ".";
	private static final String UNKNOWN_LABEL = "?";
	private static final float LABEL_LINE_WIDTH = 3.0f;
	/**
	 * Labels sit this far to the side of the stack. Sideways rather than toward the camera on
	 * purpose: a label pulled toward the viewer sits closer than the skull it belongs to, so
	 * looking down at the tiki projects it onto a neighbouring skull and it reads as labelling
	 * the wrong one. Offsetting perpendicular to the view keeps every label at the stack's own
	 * depth, so it stays level with its skull from any angle.
	 */
	private static final double LABEL_OFFSET = 0.8;
	/** Bottom of the label within the skull's block - a floor skull only fills the lower half. */
	private static final double LABEL_ANCHOR = 0.25;
	private static final float LOCKED_LABEL_SCALE = 0.6f;
	/** Below this many readable heads there is nothing to reason about. */
	private static final int MIN_READABLE_SLOTS = 2;

	public static final TikiSolverModule INSTANCE = new TikiSolverModule();

	private final ColorSetting leftColor;
	private final ColorSetting rightColor;
	private final ColorSetting lockedColor;
	private final NumberSetting range;
	private final NumberSetting labelHeight;
	private final BooleanSetting showLocked;
	private final BooleanSetting showUnknown;

	private BlockPos slotBase;
	private int[] rotations;
	private int hiddenIndex = -1;
	private TikiSolver.Plan plan;
	private int ticksSinceSearch;

	private TikiSolverModule() {
		this(
			new ColorSetting("Left Color", 85, 255, 85, 255),
			new ColorSetting("Right Color", 255, 60, 60, 255),
			new ColorSetting("Locked Color", 160, 160, 160, 200),
			new NumberSetting("Range", 2, 32, 8, true),
			new NumberSetting("Label Height", 0.1f, 1.5f, 0.35f),
			new BooleanSetting("Show Locked", true),
			new BooleanSetting("Show Unknown", true)
		);
	}

	private TikiSolverModule(
		ColorSetting leftColor, ColorSetting rightColor, ColorSetting lockedColor,
		NumberSetting range, NumberSetting labelHeight, BooleanSetting showLocked, BooleanSetting showUnknown
	) {
		super("Tiki Solver", "Shows which skull to click and how many times to align a tiki.", Category.HUNTING,
			List.of(leftColor, rightColor, lockedColor),
			List.of(range, labelHeight),
			List.of(showLocked, showUnknown),
			List.of());
		this.leftColor = leftColor;
		this.rightColor = rightColor;
		this.lockedColor = lockedColor;
		this.range = range;
		this.labelHeight = labelHeight;
		this.showLocked = showLocked;
		this.showUnknown = showUnknown;
	}

	@Override
	protected void onEnable() {
		clear();
	}

	@Override
	protected void onDisable() {
		clear();
	}

	public void tick() {
		if (!isEnabled()) return;

		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		LocalPlayer player = mc.player;
		if (level == null || player == null) {
			clear();
			return;
		}

		int range = this.range.intValue();
		BlockPos base = nearestStoredSlotBase(level, player, range);
		if (base == null) {
			base = unlistedColumnBase(level, player, range);
		}
		if (base == null) {
			// Note: no counter reset here. Resetting it on every empty tick meant the sweep
			// interval never elapsed and the unlisted-tiki search could never run at all.
			forgetTarget();
			return;
		}

		slotBase = base;
		readSlots(level, base);
	}

	/** The three blocks above the nearest stored coordinate that still holds a tiki. */
	private BlockPos nearestStoredSlotBase(ClientLevel level, LocalPlayer player, int range) {
		double rangeSq = (double) range * range;
		Vec3 position = player.position();
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (BlockPos coord : TikiCoords.all()) {
			double distance = position.distanceToSqr(coord.getCenter());
			if (distance > rangeSq || distance >= bestDistance) continue;
			BlockPos base = coord.above();
			if (TikiStacks.readableSlots(level, base) < MIN_READABLE_SLOTS) continue;
			bestDistance = distance;
			best = base;
		}
		return best;
	}

	/** Fallback for a tiki that isn't in the coordinate list - only works if all three heads show. */
	private BlockPos unlistedColumnBase(ClientLevel level, LocalPlayer player, int range) {
		if (slotBase != null && player.blockPosition().closerThan(slotBase, range) && TikiStacks.isStackBase(level, slotBase)) {
			return slotBase;
		}
		// Sweeping every tick would be thousands of block reads, and this path only exists for
		// tikis the coordinate list doesn't know about.
		if (++ticksSinceSearch < SEARCH_INTERVAL) return null;
		ticksSinceSearch = 0;
		return TikiStacks.findNearestColumnBase(level, player.position(), player.blockPosition(), range,
			TikiStacks.STACK_HEIGHT, TikiStacks.STACK_HEIGHT);
	}

	private void readSlots(ClientLevel level, BlockPos base) {
		int[] read = new int[TikiStacks.STACK_HEIGHT];
		int hidden = -1;
		int hiddenCount = 0;
		for (int i = 0; i < TikiStacks.STACK_HEIGHT; i++) {
			read[i] = TikiStacks.readRotation(level, base.above(i));
			if (read[i] < 0) {
				hidden = i;
				hiddenCount++;
			}
		}

		rotations = read;
		hiddenIndex = hiddenCount == 1 ? hidden : -1;
		if (hiddenCount == 0) {
			plan = TikiSolver.solve(read);
		} else if (hiddenCount == 1) {
			plan = TikiSolver.solveWithHidden(read, hidden);
		} else {
			plan = null;
		}
	}

	public void render(LevelRenderContext context) {
		if (!isEnabled() || slotBase == null || rotations == null) return;

		Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
		Vec3 camPos = camera.position();
		PoseStack poseStack = context.poseStack();
		MultiBufferSource.BufferSource bufferSource = context.bufferSource();

		// Perpendicular to the line of sight, so the labels shift sideways on screen without
		// moving nearer to or further from the camera.
		double offsetX = 0;
		double offsetZ = 0;
		double dx = camPos.x - (slotBase.getX() + 0.5);
		double dz = camPos.z - (slotBase.getZ() + 0.5);
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		if (horizontal > 1.0e-3) {
			offsetX = -dz / horizontal * LABEL_OFFSET;
			offsetZ = dx / horizontal * LABEL_OFFSET;
		}

		if (plan == null) {
			if (showUnknown.value()) {
				label(poseStack, bufferSource, camera, camPos, offsetX, offsetZ,
					1, UNKNOWN_LABEL, rightColor.argb(), labelHeight.value());
			}
			bufferSource.endBatch(GeilerAddonsRenderTypes.ESP_LINES);
			return;
		}

		for (int i = 0; i < TikiStacks.STACK_HEIGHT; i++) {
			String text;
			int color;
			float height = labelHeight.value();
			if (plan.index() == i) {
				String count = plan.clicks() == TikiSolver.UNKNOWN_CLICKS ? UNKNOWN_LABEL : String.valueOf(plan.clicks());
				text = (plan.direction() > 0 ? "+" : "-") + count;
				color = plan.direction() > 0 ? leftColor.argb() : rightColor.argb();
			} else if (showLocked.value() && hiddenIndex < 0 && TikiSolver.isLocked(rotations, i)) {
				text = LOCKED_LABEL;
				color = lockedColor.argb();
				height *= LOCKED_LABEL_SCALE;
			} else {
				continue;
			}
			label(poseStack, bufferSource, camera, camPos, offsetX, offsetZ, i, text, color, height);
		}

		bufferSource.endBatch(GeilerAddonsRenderTypes.ESP_LINES);
	}

	private void label(PoseStack poseStack, MultiBufferSource bufferSource, Camera camera, Vec3 camPos, double offsetX, double offsetZ, int index, String text, int color, float height) {
		BlockPos pos = slotBase.above(index);
		EspRenderer.renderLabel(poseStack, bufferSource, camera.rotation(),
			pos.getX() + 0.5 + offsetX - camPos.x,
			pos.getY() + LABEL_ANCHOR + height / 2.0f - camPos.y,
			pos.getZ() + 0.5 + offsetZ - camPos.z,
			text, color, height, LABEL_LINE_WIDTH);
	}

	private void forgetTarget() {
		slotBase = null;
		rotations = null;
		hiddenIndex = -1;
		plan = null;
	}

	private void clear() {
		forgetTarget();
		ticksSinceSearch = 0;
	}
}
