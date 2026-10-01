package geiler.addons.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import geiler.addons.client.farming.GardenPlotGeometry;
import geiler.addons.client.farming.GardenPlotGrid;
import geiler.addons.client.farming.GardenPlotState;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Renders Garden plot outlines and screen-facing status pills without owning plot state. */
public final class GardenPlotBorderRenderer {
	private static final double UNKNOWN_DASH_LENGTH = 8.0;
	private static final double UNKNOWN_DASH_GAP = 6.0;
	/** SkyBlock plot bounds are inclusive/exclusive grid edges, so the outline follows them exactly. */
	private static final double BORDER_INSET = 0.0;
	private static final double MIN_WALL_HEIGHT = 0.5;
	private static final double MAX_WALL_HEIGHT = 16.0;
	/**
	 * Farthest plot **edge** that still draws a box, in blocks.
	 *
	 * <p>The Garden spans 480 blocks corner to corner, so this keeps every plot a player can see from
	 * anywhere on the island while dropping the far side of the grid, whose boxes would be small,
	 * occluded and behind the camera anyway. Turning those into geometry is what made an all-unknown
	 * garden expensive: 25 boxes is 300 segments before dashing, on every frame.
	 */
	private static final double MAX_PLOT_DISTANCE = 384.0;
	/** Labels cost far more than boxes, so they are culled harder. */
	private static final double MAX_LABEL_DISTANCE = 160.0;
	/** Most labels drawn at once, chosen by distance so the nearest answers win. */
	private static final int MAX_LABELS = 8;
	/**
	 * Most dashes one edge may contribute.
	 *
	 * <p>Each of a box's twelve edges dashes on its own, so the two 95-block rings used to turn one
	 * unknown plot into about sixty segments - roughly fifteen hundred line draws in a frame where
	 * every plot is unknown, which is exactly what a fresh session looks like.
	 */
	private static final int MAX_DASHES_PER_EDGE = 1;

	private GardenPlotBorderRenderer() { }

	public static void renderWorld(LevelRenderContext context, List<GardenPlotState.PlotStatus> plots,
		double borderY, double wallHeight, float lineWidth, boolean depthTested, boolean showClear,
		boolean showUnknown, boolean fillEnabled, int fillColor,
		int infestedColor, int clearColor, int unknownColor) {
		if (context == null || plots == null || plots.isEmpty() || !Double.isFinite(borderY)
			|| !Double.isFinite(wallHeight)) return;
		Minecraft mc = Minecraft.getInstance();
		if (!mc.isSameThread() || mc.level == null || mc.player == null || mc.gameRenderer == null) return;

		Camera camera = mc.gameRenderer.getMainCamera();
		Vec3 cameraPosition = camera.position();
		PoseStack poseStack = context.poseStack();
		MultiBufferSource.BufferSource bufferSource = context.bufferSource();
		float width = Math.max(0.5f, Math.min(5.0f, lineWidth));
		// The box is fixed in the world: neither end of it follows the player, which is the whole
		// point of a border you can navigate by.
		double top = borderY + Math.max(MIN_WALL_HEIGHT, Math.min(MAX_WALL_HEIGHT, wallHeight));
		boolean drew = false;

		for (GardenPlotState.PlotStatus status : withinRange(plots, cameraPosition, MAX_PLOT_DISTANCE)) {
			GardenPlotGrid.Plot plot = GardenPlotGrid.plotById(status.plotId()).orElse(null);
			if (plot == null) continue;
			int color;
			switch (status.status()) {
				case INFESTED -> color = infestedColor;
				case CLEAR -> {
					if (!showClear) continue;
					color = clearColor;
				}
				case UNKNOWN -> {
					if (!showUnknown) continue;
					color = unknownColor;
				}
				default -> throw new IllegalStateException("Unhandled plot state " + status.status());
			}

			List<GardenPlotGeometry.Segment> border = status.status() == GardenPlotState.Status.UNKNOWN
				? GardenPlotGeometry.dashedSegments(GardenPlotGeometry.boxEdges(plot, borderY, top, BORDER_INSET),
					UNKNOWN_DASH_LENGTH, UNKNOWN_DASH_GAP, MAX_DASHES_PER_EDGE)
				: GardenPlotGeometry.boxEdges(plot, borderY, top, BORDER_INSET);
			if (fillEnabled) {
				EspRenderer.renderBox(poseStack, bufferSource,
					plot.minX() - cameraPosition.x, borderY - cameraPosition.y, plot.minZ() - cameraPosition.z,
					plot.maxX() - plot.minX(), top - borderY, plot.maxZ() - plot.minZ(),
					fillColor, 0, 0.5f, depthTested);
				drew = true;
			}
			// The border is intentionally opaque at every visible distance. Alpha belongs to the
			// optional fill only; translucent outlines become hard to read against Garden crops.
			int opaqueColor = color | 0xFF000000;
			drew |= renderSegments(poseStack, bufferSource, cameraPosition, border, opaqueColor,
				status.status() == GardenPlotState.Status.CLEAR ? Math.min(width, 1.8f) : width, depthTested);
		}

		if (drew) GeilerAddonsRenderTypes.endBatches(bufferSource);
	}

	public static void renderLabels(GuiGraphicsExtractor graphics, List<GardenPlotState.PlotStatus> plots,
		double borderY, double wallHeight, float scale,
		int infestedColor, int clearColor, int unknownColor) {
		if (graphics == null || plots == null || plots.isEmpty() || !Double.isFinite(borderY)
			|| !Double.isFinite(wallHeight)) return;
		Minecraft mc = Minecraft.getInstance();
		if (!mc.isSameThread() || mc.level == null || mc.player == null || mc.options.hideGui) return;

		Camera camera = mc.gameRenderer.getMainCamera();
		float safeScale = Math.max(0.55f, Math.min(2.0f, scale));
		double labelY = borderY + Math.max(MIN_WALL_HEIGHT, Math.min(MAX_WALL_HEIGHT, wallHeight)) / 2.0;

		for (GardenPlotState.PlotStatus status : nearestLabels(plots, camera.position())) {
			if (status.status() != GardenPlotState.Status.INFESTED || status.pestCount() == null) continue;
			GardenPlotGrid.Plot plot = GardenPlotGrid.plotById(status.plotId()).orElse(null);
			if (plot == null) continue;

			String label = status.pestCount() + (status.pestCount() == 1 ? " pest" : " pests");
			Vec3 world = new Vec3(plot.centerX(), labelY, plot.centerZ());
			ProjectedLabelRenderer.drawFloatingText(graphics, camera, mc.font, world, label,
				infestedColor | 0xFF000000, safeScale);
		}
	}

	/**
	 * The plots close enough to be worth drawing, in the order they were given.
	 *
	 * <p>Distance is measured to the plot's centre against the camera. Pure and Minecraft-free so the
	 * cut-off can be asserted offline: the input order is preserved, so the renderer's output stays as
	 * stable frame to frame as the snapshot it came from.
	 */
	static List<GardenPlotState.PlotStatus> withinRange(List<GardenPlotState.PlotStatus> plots,
		Vec3 cameraPosition, double maxDistance) {
		if (plots == null || plots.isEmpty() || cameraPosition == null
			|| !Double.isFinite(maxDistance) || maxDistance < 0.0) {
			return List.of();
		}
		double limit = maxDistance * maxDistance;
		List<GardenPlotState.PlotStatus> kept = new java.util.ArrayList<>(plots.size());
		for (GardenPlotState.PlotStatus status : plots) {
			if (status == null || edgeDistanceSquared(status.plotId(), cameraPosition) > limit) continue;
			kept.add(status);
		}
		return List.copyOf(kept);
	}

	/**
	 * The labels worth drawing: at most {@link #MAX_LABELS} of the nearest plots inside the label
	 * range. Each label is a projection that probes for a free spot, so the count has to be bounded
	 * and the nearest plots are the ones that answer the player's actual question.
	 */
	static List<GardenPlotState.PlotStatus> nearestLabels(List<GardenPlotState.PlotStatus> plots,
		Vec3 cameraPosition) {
		List<GardenPlotState.PlotStatus> candidates = withinRange(plots, cameraPosition, MAX_LABEL_DISTANCE);
		if (candidates.size() <= MAX_LABELS) return candidates;
		return candidates.stream()
			.sorted(java.util.Comparator.comparingDouble(status -> centreDistanceSquared(status.plotId(), cameraPosition)))
			.limit(MAX_LABELS)
			.toList();
	}

	/**
	 * Squared distance from the camera to the nearest point of a plot, in the horizontal plane.
	 *
	 * <p>Measured to the box rather than its centre: a plot is 96 blocks across, so a centre-distance
	 * test would cull a plot the player is standing right beside.
	 */
	private static double edgeDistanceSquared(int plotId, Vec3 cameraPosition) {
		GardenPlotGrid.Plot plot = GardenPlotGrid.plotById(plotId).orElse(null);
		if (plot == null) return Double.MAX_VALUE;
		double dx = Math.max(0.0, Math.max(plot.minX() - cameraPosition.x, cameraPosition.x - plot.maxX()));
		double dz = Math.max(0.0, Math.max(plot.minZ() - cameraPosition.z, cameraPosition.z - plot.maxZ()));
		return dx * dx + dz * dz;
	}

	/** Squared distance to a plot's centre, which is where its label is drawn. */
	private static double centreDistanceSquared(int plotId, Vec3 cameraPosition) {
		GardenPlotGrid.Plot plot = GardenPlotGrid.plotById(plotId).orElse(null);
		if (plot == null) return Double.MAX_VALUE;
		double dx = plot.centerX() - cameraPosition.x;
		double dz = plot.centerZ() - cameraPosition.z;
		return dx * dx + dz * dz;
	}

	private static boolean renderSegments(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource,
		Vec3 cameraPosition, List<GardenPlotGeometry.Segment> segments, int color, float width,
		boolean depthTested) {
		for (GardenPlotGeometry.Segment segment : segments) {
			GardenPlotGeometry.Point from = segment.from();
			GardenPlotGeometry.Point to = segment.to();
			EspRenderer.renderLine(poseStack, bufferSource,
				from.x() - cameraPosition.x, from.y() - cameraPosition.y, from.z() - cameraPosition.z,
				to.x() - cameraPosition.x, to.y() - cameraPosition.y, to.z() - cameraPosition.z,
				color, width, depthTested);
		}
		return !segments.isEmpty();
	}

}
