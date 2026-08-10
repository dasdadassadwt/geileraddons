package geiler.addons.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Draws simple translucent, always-visible-through-walls shapes: boxes, UV spheres, lines and labels. */
public final class EspRenderer {
	/** Packed light for "ignore world lighting", so labels stay readable in the dark. */
	private static final int FULL_BRIGHT = 0xF000F0;

	private EspRenderer() {
	}

	public static void renderBox(PoseStack poseStack, MultiBufferSource bufferSource, double x, double y, double z, double sizeX, double sizeY, double sizeZ, int fillColor, int lineColor, float lineWidth) {
		PoseStack.Pose pose = poseStack.last();
		float x0 = (float) x;
		float y0 = (float) y;
		float z0 = (float) z;
		float x1 = (float) (x + sizeX);
		float y1 = (float) (y + sizeY);
		float z1 = (float) (z + sizeZ);

		VertexConsumer quads = bufferSource.getBuffer(GeilerAddonsRenderTypes.ESP_QUADS);
		quad(quads, pose, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, fillColor);
		quad(quads, pose, x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1, fillColor);
		quad(quads, pose, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, fillColor);
		quad(quads, pose, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, fillColor);
		quad(quads, pose, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, fillColor);
		quad(quads, pose, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, fillColor);

		VertexConsumer lines = bufferSource.getBuffer(GeilerAddonsRenderTypes.ESP_LINES);
		float[][] corners = {
			{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1},
			{x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}
		};
		int[][] edges = {
			{0, 1}, {1, 2}, {2, 3}, {3, 0},
			{4, 5}, {5, 6}, {6, 7}, {7, 4},
			{0, 4}, {1, 5}, {2, 6}, {3, 7}
		};
		for (int[] edge : edges) {
			line(lines, pose, corners[edge[0]], corners[edge[1]], lineColor, lineWidth);
		}
	}

	public static void renderSphere(PoseStack poseStack, MultiBufferSource bufferSource, double cx, double cy, double cz, float radius, int fillColor) {
		int latSegments = 16;
		int lonSegments = 24;
		PoseStack.Pose pose = poseStack.last();
		VertexConsumer quads = bufferSource.getBuffer(GeilerAddonsRenderTypes.ESP_QUADS);

		for (int lat = 0; lat < latSegments; lat++) {
			double theta0 = Math.PI * lat / latSegments;
			double theta1 = Math.PI * (lat + 1) / latSegments;
			for (int lon = 0; lon < lonSegments; lon++) {
				double phi0 = 2 * Math.PI * lon / lonSegments;
				double phi1 = 2 * Math.PI * (lon + 1) / lonSegments;

				float[] a = sphereVertex(cx, cy, cz, radius, theta0, phi0);
				float[] b = sphereVertex(cx, cy, cz, radius, theta1, phi0);
				float[] c = sphereVertex(cx, cy, cz, radius, theta1, phi1);
				float[] d = sphereVertex(cx, cy, cz, radius, theta0, phi1);

				quad(quads, pose, a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2], d[0], d[1], d[2], fillColor);
			}
		}
	}

	/** Single straight line; coordinates are camera-relative like the other shapes here. */
	public static void renderLine(PoseStack poseStack, MultiBufferSource bufferSource, double x0, double y0, double z0, double x1, double y1, double z1, int color, float width) {
		float[] from = {(float) x0, (float) y0, (float) z0};
		float[] to = {(float) x1, (float) y1, (float) z1};
		// A zero-length line would make the normal below NaN, which corrupts the whole line batch.
		if (from[0] == to[0] && from[1] == to[1] && from[2] == to[2]) return;
		line(bufferSource.getBuffer(GeilerAddonsRenderTypes.ESP_LINES), poseStack.last(), from, to, color, width);
	}

	/**
	 * Billboarded text at a camera-relative position, drawn through walls. Used for the debug
	 * overlay's rotation numbers.
	 */
	public static void renderLabel(PoseStack poseStack, MultiBufferSource bufferSource, Quaternionf cameraRotation, double x, double y, double z, String text, int color, float scale) {
		Font font = Minecraft.getInstance().font;
		poseStack.pushPose();
		poseStack.translate(x, y, z);
		poseStack.mulPose(cameraRotation);
		// Negative on x/y because text is laid out top-left down, opposite of world axes.
		poseStack.scale(-scale, -scale, scale);
		font.drawInBatch(text, -font.width(text) / 2.0f, 0.0f, color, false, poseStack.last().pose(), bufferSource,
			Font.DisplayMode.SEE_THROUGH, 0, FULL_BRIGHT);
		poseStack.popPose();
	}

	private static float[] sphereVertex(double cx, double cy, double cz, float radius, double theta, double phi) {
		double sinTheta = Math.sin(theta);
		float x = (float) (cx + radius * sinTheta * Math.cos(phi));
		float y = (float) (cy + radius * Math.cos(theta));
		float z = (float) (cz + radius * sinTheta * Math.sin(phi));
		return new float[]{x, y, z};
	}

	private static void quad(VertexConsumer buffer, PoseStack.Pose pose, float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3, int color) {
		buffer.addVertex(pose, x0, y0, z0).setColor(color);
		buffer.addVertex(pose, x1, y1, z1).setColor(color);
		buffer.addVertex(pose, x2, y2, z2).setColor(color);
		buffer.addVertex(pose, x3, y3, z3).setColor(color);
	}

	private static void line(VertexConsumer buffer, PoseStack.Pose pose, float[] from, float[] to, int color, float width) {
		Vector3f normal = new Vector3f(to[0] - from[0], to[1] - from[1], to[2] - from[2]).normalize();
		buffer.addVertex(pose, from[0], from[1], from[2]).setColor(color).setNormal(pose, normal).setLineWidth(width);
		buffer.addVertex(pose, to[0], to[1], to[2]).setColor(color).setNormal(pose, normal).setLineWidth(width);
	}
}
