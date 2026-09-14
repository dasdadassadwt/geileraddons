package geiler.addons.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Quaternionf;

import java.util.Map;

/** Draws simple translucent, always-visible-through-walls shapes: boxes, UV spheres, lines and labels. */
public final class EspRenderer {
	/** Glyph cell, in the label's own units: y runs downward, matching text layout. */
	private static final float GLYPH_WIDTH = 1.0f;
	private static final float GLYPH_HEIGHT = 2.0f;
	private static final float GLYPH_SPACING = 0.4f;

	/**
	 * Seven-segment strokes per character, each entry {x0, y0, x1, y1} in the glyph cell.
	 * Only the characters the overlays actually use are defined.
	 */
	private static final Map<Character, float[][]> GLYPHS = Map.ofEntries(
		Map.entry('0', new float[][]{{0, 0, 1, 0}, {1, 0, 1, 1}, {1, 1, 1, 2}, {0, 2, 1, 2}, {0, 1, 0, 2}, {0, 0, 0, 1}}),
		Map.entry('1', new float[][]{{1, 0, 1, 1}, {1, 1, 1, 2}}),
		Map.entry('2', new float[][]{{0, 0, 1, 0}, {1, 0, 1, 1}, {0, 1, 1, 1}, {0, 1, 0, 2}, {0, 2, 1, 2}}),
		Map.entry('3', new float[][]{{0, 0, 1, 0}, {1, 0, 1, 1}, {0, 1, 1, 1}, {1, 1, 1, 2}, {0, 2, 1, 2}}),
		Map.entry('4', new float[][]{{0, 0, 0, 1}, {0, 1, 1, 1}, {1, 0, 1, 1}, {1, 1, 1, 2}}),
		Map.entry('5', new float[][]{{0, 0, 1, 0}, {0, 0, 0, 1}, {0, 1, 1, 1}, {1, 1, 1, 2}, {0, 2, 1, 2}}),
		Map.entry('6', new float[][]{{0, 0, 1, 0}, {0, 0, 0, 1}, {0, 1, 1, 1}, {1, 1, 1, 2}, {0, 1, 0, 2}, {0, 2, 1, 2}}),
		Map.entry('7', new float[][]{{0, 0, 1, 0}, {1, 0, 1, 1}, {1, 1, 1, 2}}),
		Map.entry('8', new float[][]{{0, 0, 1, 0}, {1, 0, 1, 1}, {1, 1, 1, 2}, {0, 2, 1, 2}, {0, 1, 0, 2}, {0, 0, 0, 1}, {0, 1, 1, 1}}),
		Map.entry('9', new float[][]{{0, 0, 1, 0}, {1, 0, 1, 1}, {1, 1, 1, 2}, {0, 2, 1, 2}, {0, 0, 0, 1}, {0, 1, 1, 1}}),
		// The vertical runs nearly the full cell so plus and minus can't be confused at distance -
		// a half-height stroke read as a stray mark and the sign looked unreliable.
		Map.entry('+', new float[][]{{0, 1, 1, 1}, {0.5f, 0.15f, 0.5f, 1.85f}}),
		Map.entry('-', new float[][]{{0, 1, 1, 1}}),
		Map.entry('?', new float[][]{{0, 0, 1, 0}, {1, 0, 1, 1}, {0.5f, 1, 1, 1}, {0.5f, 1, 0.5f, 1.4f}, {0.5f, 1.8f, 0.5f, 2}}),
		Map.entry('.', new float[][]{{0.35f, 1, 0.65f, 1}})
	);

	/**
	 * How far a depth-tested box is grown past what it wraps, in blocks.
	 *
	 * <p>Big enough to clear the depth buffer's precision at the distances these are read at, small
	 * enough that a box still looks like it is on the block rather than around it.
	 */
	private static final double DEPTH_MARGIN = 0.01;

	private static final int SPHERE_LATITUDES = 16;
	private static final int SPHERE_LONGITUDES = 24;
	private static final int SPHERE_ROW = SPHERE_LONGITUDES + 1;
	/**
	 * A unit sphere's vertices, three floats each, row-major over latitude then longitude.
	 *
	 * <p>Built once. Generating these per frame meant four trig calls for every one of the 768
	 * vertices of every sphere on screen, which with a full device panel's worth of aim markers ran
	 * into five figures of sin/cos per frame - by a wide margin the most expensive thing this class
	 * did. Every sphere is the same shape, so all a frame has to do now is scale and offset it.
	 */
	private static final float[] SPHERE = buildUnitSphere();

	private EspRenderer() {
	}

	public static void renderBox(PoseStack poseStack, MultiBufferSource bufferSource, double x, double y, double z, double sizeX, double sizeY, double sizeZ, int fillColor, int lineColor, float lineWidth) {
		renderBox(poseStack, bufferSource, x, y, z, sizeX, sizeY, sizeZ, fillColor, lineColor, lineWidth, false);
	}

	/** @param depthTested true to let world geometry hide the box, false to draw it through walls */
	public static void renderBox(PoseStack poseStack, MultiBufferSource bufferSource, double x, double y, double z, double sizeX, double sizeY, double sizeZ, int fillColor, int lineColor, float lineWidth, boolean depthTested) {
		PoseStack.Pose pose = poseStack.last();
		// Nudged outward when the depth test is on, because the surfaces this wraps are exactly the
		// ones it is being compared against: a box around a block is coplanar with that block's
		// faces, and one around a mob is coplanar with the mob's own model. Coplanar surfaces at
		// equal depth flicker as the two fight for the same pixels, or lose outright and vanish.
		// Through-walls boxes pass the depth test regardless, so they are left exactly on the mark.
		double margin = depthTested ? DEPTH_MARGIN : 0;
		float x0 = (float) (x - margin);
		float y0 = (float) (y - margin);
		float z0 = (float) (z - margin);
		float x1 = (float) (x + sizeX + margin);
		float y1 = (float) (y + sizeY + margin);
		float z1 = (float) (z + sizeZ + margin);

		VertexConsumer quads = bufferSource.getBuffer(GeilerAddonsRenderTypes.quads(depthTested));
		quad(quads, pose, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, fillColor);
		quad(quads, pose, x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1, fillColor);
		quad(quads, pose, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, fillColor);
		quad(quads, pose, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, fillColor);
		quad(quads, pose, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, fillColor);
		quad(quads, pose, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, fillColor);

		// Written out rather than walked from a corner and edge table: the table was rebuilt for
		// every box every frame, and with a route's worth of waypoints on screen that was the bulk
		// of this renderer's garbage.
		VertexConsumer lines = bufferSource.getBuffer(GeilerAddonsRenderTypes.lines(depthTested));
		line(lines, pose, x0, y0, z0, x1, y0, z0, lineColor, lineWidth);
		line(lines, pose, x1, y0, z0, x1, y0, z1, lineColor, lineWidth);
		line(lines, pose, x1, y0, z1, x0, y0, z1, lineColor, lineWidth);
		line(lines, pose, x0, y0, z1, x0, y0, z0, lineColor, lineWidth);
		line(lines, pose, x0, y1, z0, x1, y1, z0, lineColor, lineWidth);
		line(lines, pose, x1, y1, z0, x1, y1, z1, lineColor, lineWidth);
		line(lines, pose, x1, y1, z1, x0, y1, z1, lineColor, lineWidth);
		line(lines, pose, x0, y1, z1, x0, y1, z0, lineColor, lineWidth);
		line(lines, pose, x0, y0, z0, x0, y1, z0, lineColor, lineWidth);
		line(lines, pose, x1, y0, z0, x1, y1, z0, lineColor, lineWidth);
		line(lines, pose, x1, y0, z1, x1, y1, z1, lineColor, lineWidth);
		line(lines, pose, x0, y0, z1, x0, y1, z1, lineColor, lineWidth);
	}

	public static void renderSphere(PoseStack poseStack, MultiBufferSource bufferSource, double cx, double cy, double cz, float radius, int fillColor) {
		PoseStack.Pose pose = poseStack.last();
		VertexConsumer quads = bufferSource.getBuffer(GeilerAddonsRenderTypes.ESP_QUADS);

		for (int lat = 0; lat < SPHERE_LATITUDES; lat++) {
			for (int lon = 0; lon < SPHERE_LONGITUDES; lon++) {
				sphereVertex(quads, pose, index(lat, lon), cx, cy, cz, radius, fillColor);
				sphereVertex(quads, pose, index(lat + 1, lon), cx, cy, cz, radius, fillColor);
				sphereVertex(quads, pose, index(lat + 1, lon + 1), cx, cy, cz, radius, fillColor);
				sphereVertex(quads, pose, index(lat, lon + 1), cx, cy, cz, radius, fillColor);
			}
		}
	}

	/** Single straight line; coordinates are camera-relative like the other shapes here. */
	public static void renderLine(PoseStack poseStack, MultiBufferSource bufferSource, double x0, double y0, double z0, double x1, double y1, double z1, int color, float width) {
		line(bufferSource.getBuffer(GeilerAddonsRenderTypes.ESP_LINES), poseStack.last(),
			(float) x0, (float) y0, (float) z0, (float) x1, (float) y1, (float) z1, color, width);
	}

	/**
	 * Billboarded label at a camera-relative position, drawn through walls.
	 *
	 * <p>Stroked from line segments rather than rendered with {@link net.minecraft.client.gui.Font}:
	 * since 26.1 in-world text has to be handed to {@code SubmitNodeCollector.submitText}, and
	 * {@code Font.drawInBatch} against the level's buffer source has no draw pass behind it - the
	 * glyphs are buffered and silently dropped. These segments go through the same ESP line
	 * pipeline as every other shape here, which does draw.
	 *
	 * @param height how tall the label should be, in blocks
	 */
	public static void renderLabel(PoseStack poseStack, MultiBufferSource bufferSource, Quaternionf cameraRotation, double x, double y, double z, String text, int color, float height, float lineWidth) {
		if (text.isEmpty()) return;
		float scale = height / GLYPH_HEIGHT;

		poseStack.pushPose();
		poseStack.translate(x, y, z);
		poseStack.mulPose(cameraRotation);
		// Negative on x/y because glyphs are laid out top-left down, opposite of world axes.
		poseStack.scale(-scale, -scale, scale);

		float advance = GLYPH_WIDTH + GLYPH_SPACING;
		float cursorX = -(text.length() * advance - GLYPH_SPACING) / 2.0f;
		PoseStack.Pose pose = poseStack.last();
		VertexConsumer lines = bufferSource.getBuffer(GeilerAddonsRenderTypes.ESP_LINES);

		for (int i = 0; i < text.length(); i++) {
			float[][] glyph = GLYPHS.get(text.charAt(i));
			if (glyph != null) {
				for (float[] segment : glyph) {
					line(lines, pose, cursorX + segment[0], segment[1], 0, cursorX + segment[2], segment[3], 0, color, lineWidth);
				}
			}
			cursorX += advance;
		}
		poseStack.popPose();
	}

	private static float[] buildUnitSphere() {
		float[] vertices = new float[(SPHERE_LATITUDES + 1) * SPHERE_ROW * 3];
		int at = 0;
		for (int lat = 0; lat <= SPHERE_LATITUDES; lat++) {
			double theta = Math.PI * lat / SPHERE_LATITUDES;
			double sinTheta = Math.sin(theta);
			double cosTheta = Math.cos(theta);
			for (int lon = 0; lon <= SPHERE_LONGITUDES; lon++) {
				double phi = 2 * Math.PI * lon / SPHERE_LONGITUDES;
				vertices[at++] = (float) (sinTheta * Math.cos(phi));
				vertices[at++] = (float) cosTheta;
				vertices[at++] = (float) (sinTheta * Math.sin(phi));
			}
		}
		return vertices;
	}

	private static int index(int lat, int lon) {
		return (lat * SPHERE_ROW + lon) * 3;
	}

	private static void sphereVertex(VertexConsumer buffer, PoseStack.Pose pose, int at, double cx, double cy, double cz, float radius, int color) {
		buffer.addVertex(pose,
			(float) (cx + radius * SPHERE[at]),
			(float) (cy + radius * SPHERE[at + 1]),
			(float) (cz + radius * SPHERE[at + 2])).setColor(color);
	}

	private static void quad(VertexConsumer buffer, PoseStack.Pose pose, float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3, int color) {
		buffer.addVertex(pose, x0, y0, z0).setColor(color);
		buffer.addVertex(pose, x1, y1, z1).setColor(color);
		buffer.addVertex(pose, x2, y2, z2).setColor(color);
		buffer.addVertex(pose, x3, y3, z3).setColor(color);
	}

	private static void line(VertexConsumer buffer, PoseStack.Pose pose, float ax, float ay, float az, float bx, float by, float bz, int color, float width) {
		float dx = bx - ax;
		float dy = by - ay;
		float dz = bz - az;
		float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		// A zero-length line normalises to NaN, which corrupts the whole line batch, not just it.
		if (length == 0) return;
		dx /= length;
		dy /= length;
		dz /= length;
		buffer.addVertex(pose, ax, ay, az).setColor(color).setNormal(pose, dx, dy, dz).setLineWidth(width);
		buffer.addVertex(pose, bx, by, bz).setColor(color).setNormal(pose, dx, dy, dz).setLineWidth(width);
	}
}
