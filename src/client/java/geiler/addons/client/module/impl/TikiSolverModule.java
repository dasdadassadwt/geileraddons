package geiler.addons.client.module.impl;

import com.mojang.blaze3d.vertex.PoseStack;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.render.EspRenderer;
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
 * Floats the next click on the skull that needs it: "+3" means left-click it three times, "-2"
 * means right-click it twice. Skulls that have locked in show a dot, and the plan recomputes as
 * soon as a rotation lands.
 */
public final class TikiSolverModule extends Module {
	/** Ticks between full sweeps while no stack is held; a held stack is re-read every tick. */
	private static final int SEARCH_INTERVAL = 10;
	private static final String LOCKED_LABEL = "·";
	/** Labels float this far toward the camera so they sit in front of the stack, not inside it. */
	private static final double LABEL_OFFSET = 0.7;
	/** A skull model only fills the middle half of its block, so the box hugs that instead. */
	private static final double SKULL_INSET = 0.25;
	private static final double SKULL_SIZE = 0.5;
	private static final float BOX_LINE_WIDTH = 2.0f;
	private static final int BOX_FILL_ALPHA = 0x50;
	private static final float LOCKED_LABEL_SCALE = 0.6f;

	public static final TikiSolverModule INSTANCE = new TikiSolverModule();

	private final ColorSetting leftColor;
	private final ColorSetting rightColor;
	private final ColorSetting lockedColor;
	private final NumberSetting range;
	private final NumberSetting labelSize;
	private final BooleanSetting showLocked;
	private final BooleanSetting highlightTarget;

	private BlockPos stackBase;
	private int[] rotations;
	private TikiSolver.Plan plan;
	private int ticksSinceSearch;

	private TikiSolverModule() {
		this(
			new ColorSetting("Left Click Color", 85, 255, 85, 255),
			new ColorSetting("Right Click Color", 255, 170, 0, 255),
			new ColorSetting("Locked Color", 140, 140, 140, 200),
			new NumberSetting("Range", 2, 32, 8, true),
			new NumberSetting("Label Size", 0.01f, 0.06f, 0.03f),
			new BooleanSetting("Show Locked", true),
			new BooleanSetting("Highlight Target", true)
		);
	}

	private TikiSolverModule(
		ColorSetting leftColor, ColorSetting rightColor, ColorSetting lockedColor,
		NumberSetting range, NumberSetting labelSize, BooleanSetting showLocked, BooleanSetting highlightTarget
	) {
		super("Tiki Solver", "Shows which skull to click and how many times to align a tiki.", Category.HUNTING,
			List.of(leftColor, rightColor, lockedColor),
			List.of(range, labelSize),
			List.of(showLocked, highlightTarget),
			List.of());
		this.leftColor = leftColor;
		this.rightColor = rightColor;
		this.lockedColor = lockedColor;
		this.range = range;
		this.labelSize = labelSize;
		this.showLocked = showLocked;
		this.highlightTarget = highlightTarget;
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
		if (stackBase != null && (!player.blockPosition().closerThan(stackBase, range) || !TikiStacks.isStackBase(level, stackBase))) {
			stackBase = null;
		}
		if (stackBase == null) {
			// Sweeping every tick would be thousands of block reads; once a stack is held the
			// per-tick cost is three reads, which is what keeps the label instant.
			rotations = null;
			plan = null;
			if (++ticksSinceSearch < SEARCH_INTERVAL) return;
			ticksSinceSearch = 0;
			stackBase = TikiStacks.findNearestStackBase(level, player.position(), player.blockPosition(), range);
			if (stackBase == null) return;
		}

		rotations = TikiStacks.readRotations(level, stackBase);
		plan = rotations == null ? null : TikiSolver.solve(rotations);
	}

	public void render(LevelRenderContext context) {
		if (!isEnabled() || stackBase == null || rotations == null) return;

		Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
		Vec3 camPos = camera.position();
		PoseStack poseStack = context.poseStack();
		MultiBufferSource.BufferSource bufferSource = context.bufferSource();

		// Push labels out toward the viewer along the horizontal, so they hang in front of the
		// stack instead of being swallowed by the skull above.
		double offsetX = 0;
		double offsetZ = 0;
		double dx = camPos.x - (stackBase.getX() + 0.5);
		double dz = camPos.z - (stackBase.getZ() + 0.5);
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		if (horizontal > 1.0e-3) {
			offsetX = dx / horizontal * LABEL_OFFSET;
			offsetZ = dz / horizontal * LABEL_OFFSET;
		}

		if (highlightTarget.value() && plan != null) {
			renderTargetBox(poseStack, bufferSource, camPos, stackBase.above(plan.index()),
				plan.direction() > 0 ? leftColor.argb() : rightColor.argb());
		}

		for (int i = 0; i < TikiStacks.STACK_HEIGHT; i++) {
			String label;
			int color;
			float scale = labelSize.value();
			if (plan != null && plan.index() == i) {
				label = (plan.direction() > 0 ? "+" : "-") + plan.clicks();
				color = plan.direction() > 0 ? leftColor.argb() : rightColor.argb();
			} else if (showLocked.value() && TikiSolver.isLocked(rotations, i)) {
				label = LOCKED_LABEL;
				color = lockedColor.argb();
				scale *= LOCKED_LABEL_SCALE;
			} else {
				continue;
			}

			BlockPos pos = stackBase.above(i);
			EspRenderer.renderLabel(poseStack, bufferSource, camera.rotation(),
				pos.getX() + 0.5 + offsetX - camPos.x,
				pos.getY() + 0.5 - camPos.y,
				pos.getZ() + 0.5 + offsetZ - camPos.z,
				label, color, scale);
		}

		bufferSource.endBatch();
	}

	private void renderTargetBox(PoseStack poseStack, MultiBufferSource bufferSource, Vec3 camPos, BlockPos pos, int color) {
		int fill = (color & 0x00FFFFFF) | (BOX_FILL_ALPHA << 24);
		poseStack.pushPose();
		poseStack.translate(pos.getX() - camPos.x, pos.getY() - camPos.y, pos.getZ() - camPos.z);
		EspRenderer.renderBox(poseStack, bufferSource, SKULL_INSET, 0, SKULL_INSET,
			SKULL_SIZE, SKULL_SIZE, SKULL_SIZE, fill, color, BOX_LINE_WIDTH);
		poseStack.popPose();
	}

	private void clear() {
		stackBase = null;
		rotations = null;
		plan = null;
		ticksSinceSearch = 0;
	}
}
