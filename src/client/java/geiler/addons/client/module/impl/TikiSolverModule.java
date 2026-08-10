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

	public static final TikiSolverModule INSTANCE = new TikiSolverModule();

	private final ColorSetting leftColor;
	private final ColorSetting rightColor;
	private final ColorSetting lockedColor;
	private final NumberSetting range;
	private final NumberSetting labelSize;
	private final BooleanSetting showLocked;

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
			new NumberSetting("Label Size", 0.01f, 0.06f, 0.025f),
			new BooleanSetting("Show Locked", true)
		);
	}

	private TikiSolverModule(
		ColorSetting leftColor, ColorSetting rightColor, ColorSetting lockedColor,
		NumberSetting range, NumberSetting labelSize, BooleanSetting showLocked
	) {
		super("Tiki Solver", "Shows which skull to click and how many times to align a tiki.", Category.HUNTING,
			List.of(leftColor, rightColor, lockedColor),
			List.of(range, labelSize),
			List.of(showLocked),
			List.of());
		this.leftColor = leftColor;
		this.rightColor = rightColor;
		this.lockedColor = lockedColor;
		this.range = range;
		this.labelSize = labelSize;
		this.showLocked = showLocked;
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
		boolean drew = false;

		for (int i = 0; i < TikiStacks.STACK_HEIGHT; i++) {
			String label;
			int color;
			if (plan != null && plan.index() == i) {
				label = (plan.direction() > 0 ? "+" : "-") + plan.clicks();
				color = plan.direction() > 0 ? leftColor.argb() : rightColor.argb();
			} else if (showLocked.value() && TikiSolver.isLocked(rotations, i)) {
				label = LOCKED_LABEL;
				color = lockedColor.argb();
			} else {
				continue;
			}

			BlockPos pos = stackBase.above(i);
			EspRenderer.renderLabel(poseStack, bufferSource, camera.rotation(),
				pos.getX() + 0.5 - camPos.x, pos.getY() + 0.85 - camPos.y, pos.getZ() + 0.5 - camPos.z,
				label, color, labelSize.value());
			drew = true;
		}

		if (drew) {
			bufferSource.endBatch();
		}
	}

	private void clear() {
		stackBase = null;
		rotations = null;
		plan = null;
		ticksSinceSearch = 0;
	}
}
