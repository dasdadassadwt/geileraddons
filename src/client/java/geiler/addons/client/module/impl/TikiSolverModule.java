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
 * Floats the next click straight onto the skull that needs it: green "+2" means left-click it
 * twice, red "-3" means right-click it three times. Skulls that have locked in show a dot.
 *
 * <p>A column that is present but not a solvable three-skull tiki gets a "?" instead of nothing,
 * so a shape the solver doesn't understand reads as "I can't help here" rather than as the
 * overlay being broken.
 */
public final class TikiSolverModule extends Module {
	/** Ticks between full sweeps while no column is held; a held column is re-read every tick. */
	private static final int SEARCH_INTERVAL = 10;
	private static final String LOCKED_LABEL = "·";
	private static final String UNKNOWN_LABEL = "?";
	/**
	 * Labels sit this far to the side of the stack. Sideways rather than toward the camera on
	 * purpose: a label pulled toward the viewer sits closer than the skull it belongs to, so
	 * looking down at the tiki projects it onto a neighbouring skull and it reads as labelling
	 * the wrong one. Offsetting perpendicular to the view keeps every label at the stack's own
	 * depth, so it stays level with its skull from any angle.
	 */
	private static final double LABEL_OFFSET = 0.8;
	/** Vertical anchor within the skull's block - a floor skull only fills the bottom half. */
	private static final double LABEL_HEIGHT = 0.4;
	private static final float LOCKED_LABEL_SCALE = 0.6f;
	/** Columns this short or tall are still worth flagging; anything else isn't a tiki at all. */
	private static final int MIN_COLUMN = 2;
	private static final int MAX_COLUMN = 4;

	public static final TikiSolverModule INSTANCE = new TikiSolverModule();

	private final ColorSetting leftColor;
	private final ColorSetting rightColor;
	private final ColorSetting lockedColor;
	private final NumberSetting range;
	private final NumberSetting labelSize;
	private final BooleanSetting showLocked;
	private final BooleanSetting showUnknown;

	private BlockPos columnBase;
	private int columnHeight;
	private int[] rotations;
	private TikiSolver.Plan plan;
	private int ticksSinceSearch;

	private TikiSolverModule() {
		this(
			new ColorSetting("Left Color", 85, 255, 85, 255),
			new ColorSetting("Right Color", 255, 60, 60, 255),
			new ColorSetting("Locked Color", 160, 160, 160, 200),
			new NumberSetting("Range", 2, 32, 8, true),
			new NumberSetting("Label Size", 0.01f, 0.06f, 0.03f),
			new BooleanSetting("Show Locked", true),
			new BooleanSetting("Show Unknown", true)
		);
	}

	private TikiSolverModule(
		ColorSetting leftColor, ColorSetting rightColor, ColorSetting lockedColor,
		NumberSetting range, NumberSetting labelSize, BooleanSetting showLocked, BooleanSetting showUnknown
	) {
		super("Tiki Solver", "Shows which skull to click and how many times to align a tiki.", Category.HUNTING,
			List.of(leftColor, rightColor, lockedColor),
			List.of(range, labelSize),
			List.of(showLocked, showUnknown),
			List.of());
		this.leftColor = leftColor;
		this.rightColor = rightColor;
		this.lockedColor = lockedColor;
		this.range = range;
		this.labelSize = labelSize;
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
		if (columnBase != null && (!player.blockPosition().closerThan(columnBase, range) || !TikiStacks.isColumnBase(level, columnBase))) {
			columnBase = null;
		}
		if (columnBase == null) {
			// Sweeping every tick would be thousands of block reads; once a column is held the
			// per-tick cost is a handful, which is what keeps the label instant.
			resetTarget();
			if (++ticksSinceSearch < SEARCH_INTERVAL) return;
			ticksSinceSearch = 0;
			columnBase = TikiStacks.findNearestColumnBase(level, player.position(), player.blockPosition(), range, MIN_COLUMN, MAX_COLUMN);
			if (columnBase == null) return;
		}

		columnHeight = TikiStacks.columnHeight(level, columnBase);
		rotations = columnHeight == TikiStacks.STACK_HEIGHT ? TikiStacks.readRotations(level, columnBase) : null;
		plan = rotations != null ? TikiSolver.solve(rotations) : null;
	}

	public void render(LevelRenderContext context) {
		if (!isEnabled() || columnBase == null) return;

		Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
		Vec3 camPos = camera.position();
		PoseStack poseStack = context.poseStack();
		MultiBufferSource.BufferSource bufferSource = context.bufferSource();

		// Perpendicular to the line of sight, so the labels shift sideways on screen without
		// moving nearer to or further from the camera.
		double offsetX = 0;
		double offsetZ = 0;
		double dx = camPos.x - (columnBase.getX() + 0.5);
		double dz = camPos.z - (columnBase.getZ() + 0.5);
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		if (horizontal > 1.0e-3) {
			offsetX = -dz / horizontal * LABEL_OFFSET;
			offsetZ = dx / horizontal * LABEL_OFFSET;
		}

		if (rotations == null) {
			// Found a skull column the solver can't read - say so rather than showing nothing.
			if (showUnknown.value()) {
				label(poseStack, bufferSource, camera, camPos, offsetX, offsetZ,
					Math.max(0, columnHeight - 1) / 2, UNKNOWN_LABEL, rightColor.argb(), labelSize.value());
			}
			bufferSource.endBatch();
			return;
		}

		for (int i = 0; i < TikiStacks.STACK_HEIGHT; i++) {
			String text;
			int color;
			float scale = labelSize.value();
			if (plan != null && plan.index() == i) {
				text = (plan.direction() > 0 ? "+" : "-") + plan.clicks();
				color = plan.direction() > 0 ? leftColor.argb() : rightColor.argb();
			} else if (showLocked.value() && TikiSolver.isLocked(rotations, i)) {
				text = LOCKED_LABEL;
				color = lockedColor.argb();
				scale *= LOCKED_LABEL_SCALE;
			} else {
				continue;
			}
			label(poseStack, bufferSource, camera, camPos, offsetX, offsetZ, i, text, color, scale);
		}

		bufferSource.endBatch();
	}

	private void label(PoseStack poseStack, MultiBufferSource bufferSource, Camera camera, Vec3 camPos, double offsetX, double offsetZ, int index, String text, int color, float scale) {
		BlockPos pos = columnBase.above(index);
		EspRenderer.renderLabel(poseStack, bufferSource, camera.rotation(),
			pos.getX() + 0.5 + offsetX - camPos.x,
			pos.getY() + LABEL_HEIGHT - camPos.y,
			pos.getZ() + 0.5 + offsetZ - camPos.z,
			text, color, scale);
	}

	private void resetTarget() {
		columnHeight = 0;
		rotations = null;
		plan = null;
	}

	private void clear() {
		columnBase = null;
		ticksSinceSearch = 0;
		resetTarget();
	}
}
