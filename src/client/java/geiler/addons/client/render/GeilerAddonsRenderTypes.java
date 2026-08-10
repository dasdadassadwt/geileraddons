package geiler.addons.client.render;

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
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

	public static final RenderType ESP_QUADS = RenderType.create(
		"geileraddons_esp_quads",
		RenderSetup.builder(ESP_QUADS_PIPELINE).sortOnUpload().createRenderSetup()
	);

	public static final RenderType ESP_LINES = RenderType.create(
		"geileraddons_esp_lines",
		RenderSetup.builder(ESP_LINES_PIPELINE).createRenderSetup()
	);

	private GeilerAddonsRenderTypes() {
	}
}
