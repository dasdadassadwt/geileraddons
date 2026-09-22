package geiler.addons.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import geiler.addons.client.farming.GardenPlotGeometry;
import geiler.addons.client.farming.GardenPlotGrid;
import geiler.addons.client.farming.GardenPlotState;
import geiler.addons.client.gui.GuiTheme;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Renders Garden plot outlines and screen-facing status pills without owning plot state. */
public final class GardenPlotBorderRenderer {
	private static final double INFESTED_ACCENT_INSET = 2.0;
	private static final double UNKNOWN_DASH_LENGTH = 8.0;
	private static final double UNKNOWN_DASH_GAP = 6.0;

	private GardenPlotBorderRenderer() { }

	public static void renderWorld(LevelRenderContext context, List<GardenPlotState.PlotStatus> plots,
		double borderY, float lineWidth, boolean depthTested, boolean showClear, boolean showUnknown,
		int infestedColor, int clearColor, int unknownColor) {
		if (context == null || plots == null || plots.isEmpty() || !Double.isFinite(borderY)) return;
		Minecraft mc = Minecraft.getInstance();
		if (!mc.isSameThread() || mc.level == null || mc.player == null || mc.gameRenderer == null) return;

		Camera camera = mc.gameRenderer.getMainCamera();
		Vec3 cameraPosition = camera.position();
		PoseStack poseStack = context.poseStack();
		MultiBufferSource.BufferSource bufferSource = context.bufferSource();
		float width = Math.max(0.5f, Math.min(5.0f, lineWidth));
		boolean drew = false;

		for (GardenPlotState.PlotStatus status : plots) {
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
				? GardenPlotGeometry.dashedPerimeter(plot, borderY, 0.5, UNKNOWN_DASH_LENGTH, UNKNOWN_DASH_GAP)
				: GardenPlotGeometry.perimeter(plot, borderY, 0.5);
			drew |= renderSegments(poseStack, bufferSource, cameraPosition, border, color,
				status.status() == GardenPlotState.Status.CLEAR ? Math.min(width, 1.8f) : width, depthTested);

			if (status.status() == GardenPlotState.Status.INFESTED) {
				List<GardenPlotGeometry.Segment> accent = GardenPlotGeometry.perimeter(plot,
					borderY + 0.025, INFESTED_ACCENT_INSET);
				drew |= renderSegments(poseStack, bufferSource, cameraPosition, accent,
					GuiTheme.withOpacity(color, 0.58f), Math.max(0.7f, width * 0.45f), depthTested);
			}
		}

		if (drew) GeilerAddonsRenderTypes.endBatches(bufferSource);
	}

	public static void renderLabels(GuiGraphicsExtractor graphics, List<GardenPlotState.PlotStatus> plots,
		double borderY, float scale, boolean showClear, boolean showUnknown,
		int infestedColor, int clearColor, int unknownColor) {
		if (graphics == null || plots == null || plots.isEmpty() || !Double.isFinite(borderY)) return;
		Minecraft mc = Minecraft.getInstance();
		if (!mc.isSameThread() || mc.level == null || mc.player == null || mc.options.hideGui) return;

		Camera camera = mc.gameRenderer.getMainCamera();
		float safeScale = Math.max(0.55f, Math.min(2.0f, scale));

		for (GardenPlotState.PlotStatus status : plots) {
			if (status.status() == GardenPlotState.Status.CLEAR && !showClear) continue;
			if (status.status() == GardenPlotState.Status.UNKNOWN && !showUnknown) continue;
			GardenPlotGrid.Plot plot = GardenPlotGrid.plotById(status.plotId()).orElse(null);
			if (plot == null) continue;

			String label = label(status);
			int color = color(status, infestedColor, clearColor, unknownColor);
			Vec3 world = new Vec3(plot.centerX(), borderY + 2.0, plot.centerZ());
			ProjectedLabelRenderer.draw(graphics, camera, mc.font, world, label, color, safeScale);
		}
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

	private static String label(GardenPlotState.PlotStatus status) {
		String prefix = "Plot " + status.plotId() + "  |  ";
		return switch (status.status()) {
			case INFESTED -> status.pestCount() == null
				? prefix + "INFESTED"
				: prefix + status.pestCount() + (status.pestCount() == 1 ? " pest" : " pests");
			case CLEAR -> prefix + "CLEAR";
			case UNKNOWN -> prefix + (status.stale() ? "UNKNOWN  |  STALE" : "UNKNOWN");
		};
	}

	private static int color(GardenPlotState.PlotStatus status, int infested, int clear, int unknown) {
		return switch (status.status()) {
			case INFESTED -> infested;
			case CLEAR -> clear;
			case UNKNOWN -> unknown;
		};
	}

}
