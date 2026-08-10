package geiler.addons.client.module.impl;

import com.mojang.blaze3d.vertex.PoseStack;
import geiler.addons.client.config.TikiCoords;
import geiler.addons.client.gui.TikiCoordManagerScreen;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.ModuleAction;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.render.EspRenderer;
import geiler.addons.client.render.GeilerAddonsRenderTypes;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;

/**
 * Periodically checks every stored tiki coordinate for skulls stacked directly above it and
 * boxes the coordinate green (enough skulls) or red (not enough). Optionally draws a tracer
 * from the crosshair to the nearest green one.
 */
public final class TikiHelperModule extends Module {
	/** Blocks checked above each stored coordinate: y+1 through y+SCAN_HEIGHT. */
	private static final int SCAN_HEIGHT = 4;
	/** Skulls needed in that column for a coordinate to count as valid. */
	private static final int REQUIRED_SKULLS = 2;
	/** How far in front of the camera the tracer starts, so it appears to leave the crosshair. */
	private static final double TRACER_START_DISTANCE = 0.5;
	private static final float BOX_LINE_WIDTH = 2.0f;

	public static final TikiHelperModule INSTANCE = new TikiHelperModule();

	private final ColorSetting validColor;
	private final ColorSetting invalidColor;
	private final ColorSetting tracerColor;
	private final NumberSetting scanInterval;
	private final NumberSetting tracerWidth;
	private final BooleanSetting tracer;

	private final List<BlockPos> valid = new ArrayList<>();
	private final List<BlockPos> invalid = new ArrayList<>();

	private int ticksSinceScan;
	private boolean scanPending;
	private ClientLevel lastLevel;

	private TikiHelperModule() {
		this(
			new ColorSetting("Valid Color", 0, 255, 0, 128),
			new ColorSetting("Invalid Color", 255, 0, 0, 128),
			new ColorSetting("Tracer Color", 0, 255, 0, 255),
			new NumberSetting("Scan Interval", 1, 200, 40, true),
			new NumberSetting("Tracer Width", 0.5f, 10.0f, 2.0f),
			new BooleanSetting("Tracer Line", true)
		);
	}

	private TikiHelperModule(
		ColorSetting validColor, ColorSetting invalidColor, ColorSetting tracerColor,
		NumberSetting scanInterval, NumberSetting tracerWidth, BooleanSetting tracer
	) {
		super("Tiki Helper", "Highlights tiki coordinates by how many skulls are stacked above them.", Category.HUNTING,
			List.of(validColor, invalidColor, tracerColor),
			List.of(scanInterval, tracerWidth),
			List.of(tracer),
			List.of(new ModuleAction("Manage Tiki Coords", TikiHelperModule::openCoordManager)));
		this.validColor = validColor;
		this.invalidColor = invalidColor;
		this.tracerColor = tracerColor;
		this.scanInterval = scanInterval;
		this.tracerWidth = tracerWidth;
		this.tracer = tracer;
	}

	@Override
	protected void onEnable() {
		clearResults();
		// Deferred to the next tick rather than scanned here: this also runs during config load,
		// before the client has a level (or even a finished Minecraft instance) to read.
		scanPending = true;
	}

	@Override
	protected void onDisable() {
		clearResults();
	}

	/** Re-runs on the next tick - used when the coordinate list changes under us. */
	public void requestScan() {
		scanPending = true;
	}

	public void tick() {
		if (!isEnabled()) return;

		ClientLevel level = Minecraft.getInstance().level;
		if (level != lastLevel) {
			// Joining, leaving or changing dimension: the old results describe a world that
			// isn't on screen any more.
			lastLevel = level;
			clearResults();
			scanPending = true;
		}

		ticksSinceScan++;
		if (!scanPending && ticksSinceScan < scanInterval.intValue()) return;

		scanPending = false;
		ticksSinceScan = 0;
		scan();
	}

	private void scan() {
		clearResults();

		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		LocalPlayer player = mc.player;
		if (level == null || player == null) return;

		// Coordinates past the render distance are skipped outright: their chunks aren't drawn,
		// so there is nothing to highlight and nothing worth reading out of the world.
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
				// isLoaded also rejects positions outside build height, which getBlockState would not like.
				if (level.isLoaded(above) && level.getBlockState(above).getBlock() instanceof AbstractSkullBlock) {
					skulls++;
				}
			}

			(skulls >= REQUIRED_SKULLS ? valid : invalid).add(coord);
		}
	}

	public void render(LevelRenderContext context) {
		if (!isEnabled()) return;
		if (valid.isEmpty() && invalid.isEmpty()) return;

		Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
		Vec3 camPos = camera.position();
		PoseStack poseStack = context.poseStack();
		MultiBufferSource.BufferSource bufferSource = context.bufferSource();

		renderBoxes(poseStack, bufferSource, camPos, valid, validColor.argb());
		renderBoxes(poseStack, bufferSource, camPos, invalid, invalidColor.argb());

		if (tracer.value()) {
			renderTracer(poseStack, bufferSource, camera, camPos);
		}

		bufferSource.endBatch(GeilerAddonsRenderTypes.ESP_QUADS);
		bufferSource.endBatch(GeilerAddonsRenderTypes.ESP_LINES);
	}

	private void renderBoxes(PoseStack poseStack, MultiBufferSource bufferSource, Vec3 camPos, List<BlockPos> positions, int color) {
		for (BlockPos pos : positions) {
			poseStack.pushPose();
			poseStack.translate(pos.getX() - camPos.x, pos.getY() - camPos.y, pos.getZ() - camPos.z);
			EspRenderer.renderBox(poseStack, bufferSource, 0, 0, 0, 1, 1, 1, color, color, BOX_LINE_WIDTH);
			poseStack.popPose();
		}
	}

	private void renderTracer(PoseStack poseStack, MultiBufferSource bufferSource, Camera camera, Vec3 camPos) {
		BlockPos target = closestValid(camPos);
		if (target == null) return;

		// Everything here is camera-relative, so the camera itself sits at the origin and the
		// tracer starts a short way down the look vector - visually, at the crosshair.
		Vector3fc forward = camera.forwardVector();
		Vec3 center = target.getCenter();
		EspRenderer.renderLine(poseStack, bufferSource,
			forward.x() * TRACER_START_DISTANCE, forward.y() * TRACER_START_DISTANCE, forward.z() * TRACER_START_DISTANCE,
			center.x - camPos.x, center.y - camPos.y, center.z - camPos.z,
			tracerColor.argb(), tracerWidth.value());
	}

	private BlockPos closestValid(Vec3 from) {
		BlockPos closest = null;
		double closestDistance = Double.MAX_VALUE;
		for (BlockPos pos : valid) {
			double distance = from.distanceToSqr(pos.getCenter());
			if (distance < closestDistance) {
				closestDistance = distance;
				closest = pos;
			}
		}
		return closest;
	}

	private void clearResults() {
		valid.clear();
		invalid.clear();
	}

	private static void openCoordManager() {
		Minecraft mc = Minecraft.getInstance();
		mc.setScreen(new TikiCoordManagerScreen(mc.screen));
	}
}
