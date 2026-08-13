package geiler.addons.client.render;

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;

/**
 * Custom render types that always pass the depth test, so ESP boxes/spheres/lines draw
 * through walls instead of being occluded by world geometry.
 */
public final class GeilerAddonsRenderTypes {
	private static final RenderPipeline ESP_QUADS_PIPELINE = RenderPipelines.register(
		RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
			.withLocation("pipeline/geileraddons_esp_quads")
			.withCull(false)
			.withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
			.build()
	);

	private static final RenderPipeline ESP_LINES_PIPELINE = RenderPipelines.register(
		RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
			.withLocation("pipeline/geileraddons_esp_lines")
			.withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
			.build()
	);

	/**
	 * The same two shapes, but occluded by world geometry - what a highlight with its depth check
	 * on draws into. Depth is tested and deliberately not written: these are translucent overlays,
	 * and writing depth would let one of them hide the next.
	 */
	private static final RenderPipeline DEPTH_QUADS_PIPELINE = RenderPipelines.register(
		RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
			.withLocation("pipeline/geileraddons_depth_quads")
			.withCull(false)
			.withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
			.build()
	);

	private static final RenderPipeline DEPTH_LINES_PIPELINE = RenderPipelines.register(
		RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
			.withLocation("pipeline/geileraddons_depth_lines")
			.withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
			.build()
	);

	public static final RenderType ESP_QUADS = RenderType.create(
		"geileraddons_esp_quads",
		RenderSetup.builder(ESP_QUADS_PIPELINE).sortOnUpload().createRenderSetup()
	);

	public static final RenderType ESP_LINES = RenderType.create(
		"geileraddons_esp_lines",
		RenderSetup.builder(ESP_LINES_PIPELINE).createRenderSetup()
	);

	public static final RenderType DEPTH_QUADS = RenderType.create(
		"geileraddons_depth_quads",
		RenderSetup.builder(DEPTH_QUADS_PIPELINE).sortOnUpload().createRenderSetup()
	);

	public static final RenderType DEPTH_LINES = RenderType.create(
		"geileraddons_depth_lines",
		RenderSetup.builder(DEPTH_LINES_PIPELINE).createRenderSetup()
	);

	private GeilerAddonsRenderTypes() {
	}

	public static RenderType quads(boolean depthTested) {
		return depthTested ? DEPTH_QUADS : ESP_QUADS;
	}

	public static RenderType lines(boolean depthTested) {
		return depthTested ? DEPTH_LINES : ESP_LINES;
	}

	/**
	 * Flushes all four batches.
	 *
	 * <p>All of them rather than the pair just drawn into: a module whose highlights don't agree on
	 * their depth check fills both pairs in one pass, and an empty batch costs nothing to end.
	 */
	public static void endBatches(MultiBufferSource.BufferSource bufferSource) {
		bufferSource.endBatch(ESP_QUADS);
		bufferSource.endBatch(ESP_LINES);
		bufferSource.endBatch(DEPTH_QUADS);
		bufferSource.endBatch(DEPTH_LINES);
	}
}
