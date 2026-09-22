package geiler.addons.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** Draws simple translucent ESP shapes: boxes, rings, UV spheres, lines and labels. */
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
		Map.entry('N', new float[][]{{0, 0, 0, 2}, {0, 0, 1, 2}, {1, 0, 1, 2}}),
		Map.entry('E', new float[][]{{0, 0, 0, 2}, {0, 0, 1, 0}, {0, 1, 0.8f, 1}, {0, 2, 1, 2}}),
		Map.entry('S', new float[][]{{0, 0, 1, 0}, {0, 0, 0, 1}, {0, 1, 1, 1}, {1, 1, 1, 2}, {0, 2, 1, 2}}),
		Map.entry('W', new float[][]{{0, 0, 0.25f, 2}, {0.25f, 2, 0.5f, 1}, {0.5f, 1, 0.75f, 2}, {0.75f, 2, 1, 0}}),
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
	private static final int RING_SEGMENTS = 48;
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

	/** Single straight line through walls; coordinates are camera-relative like the other shapes here. */
	public static void renderLine(PoseStack poseStack, MultiBufferSource bufferSource, double x0, double y0, double z0, double x1, double y1, double z1, int color, float width) {
		renderLine(poseStack, bufferSource, x0, y0, z0, x1, y1, z1, color, width, false);
	}

	/** Single straight line with the same optional depth test used by ESP boxes. */
	public static void renderLine(PoseStack poseStack, MultiBufferSource bufferSource, double x0, double y0, double z0, double x1, double y1, double z1, int color, float width, boolean depthTested) {
		line(bufferSource.getBuffer(GeilerAddonsRenderTypes.lines(depthTested)), poseStack.last(),
			(float) x0, (float) y0, (float) z0, (float) x1, (float) y1, (float) z1, color, width);
	}

	/** Draws only the exposed surfaces and outline edges of a connected voxel union. */
	public static void renderBlockUnion(PoseStack poseStack, MultiBufferSource bufferSource,
		Iterable<BlockPos> positions, double cameraX, double cameraY, double cameraZ,
		boolean fillEnabled, boolean outlineEnabled, int fillColor, int lineColor, float lineWidth, boolean depthTested) {
		Set<BlockPos> blocks;
		if (positions instanceof Set<?> existing) {
			@SuppressWarnings("unchecked")
			Set<BlockPos> typed = (Set<BlockPos>) existing;
			blocks = typed;
		} else {
			blocks = new HashSet<>();
			for (BlockPos position : positions) if (position != null) blocks.add(position.immutable());
		}
		renderBlockUnionInternal(poseStack, bufferSource, blocks, null, null, cameraX, cameraY, cameraZ,
			fillEnabled, outlineEnabled, fillColor, lineColor, lineWidth, depthTested);
	}

	/** Draws separated single-block boxes without allocating a set and contour map per block. */
	public static void renderIndividualBlocks(PoseStack poseStack, MultiBufferSource bufferSource,
		Iterable<BlockPos> positions, double cameraX, double cameraY, double cameraZ,
		boolean fillEnabled, boolean outlineEnabled, int fillColor, int lineColor, float lineWidth,
		boolean depthTested) {
		if (!fillEnabled && !outlineEnabled) return;
		PoseStack.Pose pose = poseStack.last();
		float margin = depthTested ? (float) DEPTH_MARGIN : 0;
		VertexConsumer quads = fillEnabled ? bufferSource.getBuffer(GeilerAddonsRenderTypes.quads(depthTested)) : null;
		VertexConsumer lines = outlineEnabled ? bufferSource.getBuffer(GeilerAddonsRenderTypes.lines(depthTested)) : null;
		boolean fill = fillEnabled && (fillColor >>> 24) != 0;
		for (BlockPos block : positions) {
			if (block == null) continue;
			float x0 = (float) (block.getX() - cameraX);
			float y0 = (float) (block.getY() - cameraY);
			float z0 = (float) (block.getZ() - cameraZ);
			float x1 = x0 + 1;
			float y1 = y0 + 1;
			float z1 = z0 + 1;
			if (fill) {
				quad(quads, pose, x0, y0, z0 - margin, x1, y0, z0 - margin,
					x1, y1, z0 - margin, x0, y1, z0 - margin, fillColor);
				quad(quads, pose, x1, y0, z1 + margin, x0, y0, z1 + margin,
					x0, y1, z1 + margin, x1, y1, z1 + margin, fillColor);
				quad(quads, pose, x0 - margin, y0, z1, x0 - margin, y0, z0,
					x0 - margin, y1, z0, x0 - margin, y1, z1, fillColor);
				quad(quads, pose, x1 + margin, y0, z0, x1 + margin, y0, z1,
					x1 + margin, y1, z1, x1 + margin, y1, z0, fillColor);
				quad(quads, pose, x0, y1 + margin, z0, x1, y1 + margin, z0,
					x1, y1 + margin, z1, x0, y1 + margin, z1, fillColor);
				quad(quads, pose, x0, y0 - margin, z1, x1, y0 - margin, z1,
					x1, y0 - margin, z0, x0, y0 - margin, z0, fillColor);
			}
			if (lines != null) {
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
		}
	}

	/** Draws a pre-indexed block union using cached contour edges. */
	public static void renderBlockUnionPrepared(PoseStack poseStack, MultiBufferSource bufferSource,
		Set<BlockPos> blocks, LongSet packedMembership, List<OutlineSegment> outlineSegments,
		double cameraX, double cameraY, double cameraZ,
		boolean fillEnabled, boolean outlineEnabled, int fillColor, int lineColor, float lineWidth, boolean depthTested) {
		renderBlockUnionInternal(poseStack, bufferSource, blocks, packedMembership, outlineSegments, cameraX, cameraY, cameraZ,
			fillEnabled, outlineEnabled, fillColor, lineColor, lineWidth, depthTested);
	}

	private static void renderBlockUnionInternal(PoseStack poseStack, MultiBufferSource bufferSource,
		Set<BlockPos> blocks, LongSet packedMembership, List<OutlineSegment> preparedOutline,
		double cameraX, double cameraY, double cameraZ,
		boolean fillEnabled, boolean outlineEnabled, int fillColor, int lineColor, float lineWidth, boolean depthTested) {
		if (blocks.isEmpty() || (!fillEnabled && !outlineEnabled)) return;
		PoseStack.Pose pose = poseStack.last();
		float margin = depthTested ? (float) DEPTH_MARGIN : 0;
		VertexConsumer quads = fillEnabled ? bufferSource.getBuffer(GeilerAddonsRenderTypes.quads(depthTested)) : null;
		boolean fill = fillEnabled && (fillColor >>> 24) != 0;
		for (BlockPos block : blocks) {
			int x = block.getX(), y = block.getY(), z = block.getZ();
			for (int axis = 0; axis < 3; axis++) {
				for (int direction = -1; direction <= 1; direction += 2) {
					int dx = axis == 0 ? direction : 0;
					int dy = axis == 1 ? direction : 0;
					int dz = axis == 2 ? direction : 0;
					boolean adjacent = packedMembership == null
						? blocks.contains(block.offset(dx, dy, dz))
						: packedMembership.contains(BlockPos.asLong(x + dx, y + dy, z + dz));
					if (adjacent) continue;
					Face face = face(x, y, z, axis, direction);
					if (fill) drawFace(quads, pose, face, cameraX, cameraY, cameraZ, margin, fillColor);
				}
			}
		}
		if (outlineEnabled) {
			VertexConsumer lines = bufferSource.getBuffer(GeilerAddonsRenderTypes.lines(depthTested));
			List<OutlineSegment> segments = preparedOutline == null ? blockOutlineSegments(blocks) : preparedOutline;
			for (OutlineSegment segment : segments) {
				line(lines, pose,
					(float) (segment.x0 - cameraX), (float) (segment.y0 - cameraY), (float) (segment.z0 - cameraZ),
					(float) (segment.x1 - cameraX), (float) (segment.y1 - cameraY), (float) (segment.z1 - cameraZ),
					lineColor, lineWidth);
			}
		}
	}

	/** Builds the outside contour, merging adjacent one-block segments into continuous edges. */
	public static List<OutlineSegment> blockOutlineSegments(Set<BlockPos> blocks) {
		Map<PlaneEdge, Integer> edgeCounts = new HashMap<>();
		int checked = 0;
		for (BlockPos block : blocks) {
			if ((checked++ & 0x3FF) == 0 && Thread.currentThread().isInterrupted()) {
				throw new CancellationException();
			}
			int x = block.getX(), y = block.getY(), z = block.getZ();
			for (int axis = 0; axis < 3; axis++) {
				for (int direction = -1; direction <= 1; direction += 2) {
					int dx = axis == 0 ? direction : 0;
					int dy = axis == 1 ? direction : 0;
					int dz = axis == 2 ? direction : 0;
					if (blocks.contains(block.offset(dx, dy, dz))) continue;
					Face face = face(x, y, z, axis, direction);
					for (int edge = 0; edge < 4; edge++) {
						GridEdge line = new GridEdge(face.corners[edge], face.corners[(edge + 1) % 4]);
						edgeCounts.merge(new PlaneEdge(face.axis, face.plane, line), 1, Integer::sum);
					}
				}
			}
		}
		Set<GridEdge> boundary = new HashSet<>();
		for (Map.Entry<PlaneEdge, Integer> entry : edgeCounts.entrySet()) {
			if (entry.getValue() == 1) boundary.add(entry.getKey().edge);
		}
		return mergeAdjacentEdges(boundary);
	}

	private static List<OutlineSegment> mergeAdjacentEdges(Set<GridEdge> edges) {
		Map<LineKey, List<Interval>> grouped = new HashMap<>();
		for (GridEdge edge : edges) {
			GridPoint a = edge.a;
			GridPoint b = edge.b;
			LineKey key;
			int start;
			int end;
			if (a.x != b.x) {
				key = new LineKey(0, a.y, a.z);
				start = a.x; end = b.x;
			} else if (a.y != b.y) {
				key = new LineKey(1, a.x, a.z);
				start = a.y; end = b.y;
			} else {
				key = new LineKey(2, a.x, a.y);
				start = a.z; end = b.z;
			}
			grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new Interval(start, end));
		}
		List<OutlineSegment> merged = new ArrayList<>();
		for (Map.Entry<LineKey, List<Interval>> entry : grouped.entrySet()) {
			List<Interval> intervals = entry.getValue();
			intervals.sort(Comparator.comparingInt(Interval::start));
			int start = intervals.getFirst().start;
			int end = intervals.getFirst().end;
			for (int index = 1; index < intervals.size(); index++) {
				Interval next = intervals.get(index);
				if (next.start <= end) {
					end = Math.max(end, next.end);
				} else {
					merged.add(segment(entry.getKey(), start, end));
					start = next.start;
					end = next.end;
				}
			}
			merged.add(segment(entry.getKey(), start, end));
		}
		return List.copyOf(merged);
	}

	private static OutlineSegment segment(LineKey key, int start, int end) {
		return switch (key.axis) {
			case 0 -> new OutlineSegment(start, key.first, key.second, end, key.first, key.second);
			case 1 -> new OutlineSegment(key.first, start, key.second, key.first, end, key.second);
			default -> new OutlineSegment(key.first, key.second, start, key.first, key.second, end);
		};
	}

	private static Face face(int x, int y, int z, int axis, int direction) {
		GridPoint[] corners;
		int plane;
		if (axis == 0 && direction < 0) {
			plane = x; corners = points(x,y,z, x,y+1,z, x,y+1,z+1, x,y,z+1);
		} else if (axis == 0) {
			plane = x + 1; corners = points(x+1,y,z+1, x+1,y+1,z+1, x+1,y+1,z, x+1,y,z);
		} else if (axis == 1 && direction < 0) {
			plane = y; corners = points(x,y,z+1, x+1,y,z+1, x+1,y,z, x,y,z);
		} else if (axis == 1) {
			plane = y + 1; corners = points(x,y+1,z, x+1,y+1,z, x+1,y+1,z+1, x,y+1,z+1);
		} else if (direction < 0) {
			plane = z; corners = points(x+1,y,z, x+1,y+1,z, x,y+1,z, x,y,z);
		} else {
			plane = z + 1; corners = points(x,y,z+1, x,y+1,z+1, x+1,y+1,z+1, x+1,y,z+1);
		}
		return new Face(axis, plane, direction, corners);
	}

	private static GridPoint[] points(int x0, int y0, int z0, int x1, int y1, int z1,
		int x2, int y2, int z2, int x3, int y3, int z3) {
		return new GridPoint[]{new GridPoint(x0,y0,z0), new GridPoint(x1,y1,z1),
			new GridPoint(x2,y2,z2), new GridPoint(x3,y3,z3)};
	}

	private static void drawFace(VertexConsumer quads, PoseStack.Pose pose, Face face,
		double cameraX, double cameraY, double cameraZ, float margin, int color) {
		for (GridPoint point : face.corners) {
			float x = (float) (point.x - cameraX + (face.axis == 0 ? face.direction * margin : 0));
			float y = (float) (point.y - cameraY + (face.axis == 1 ? face.direction * margin : 0));
			float z = (float) (point.z - cameraZ + (face.axis == 2 ? face.direction * margin : 0));
			quads.addVertex(pose, x, y, z).setColor(color);
		}
	}

	private record GridPoint(int x, int y, int z) implements Comparable<GridPoint> {
		@Override public int compareTo(GridPoint other) {
			int result = Integer.compare(x, other.x);
			if (result == 0) result = Integer.compare(y, other.y);
			if (result == 0) result = Integer.compare(z, other.z);
			return result;
		}
	}
	private record GridEdge(GridPoint a, GridPoint b) {
		GridEdge {
			if (a.compareTo(b) > 0) {
				GridPoint swap = a;
				a = b;
				b = swap;
			}
		}
	}
	private record Face(int axis, int plane, int direction, GridPoint[] corners) { }
	private record PlaneEdge(int axis, int plane, GridEdge edge) { }
	private record LineKey(int axis, int first, int second) { }
	private record Interval(int start, int end) { }
	public record OutlineSegment(int x0, int y0, int z0, int x1, int y1, int z1) { }

	/** Draws a horizontal camera-relative ring using the requested line pipeline. */
	public static void renderRing(PoseStack poseStack, MultiBufferSource bufferSource, double cx, double cy, double cz, float radius, int color, float width, boolean depthTested) {
		if (!Float.isFinite(radius) || radius <= 0) return;

		VertexConsumer lines = bufferSource.getBuffer(GeilerAddonsRenderTypes.lines(depthTested));
		PoseStack.Pose pose = poseStack.last();
		double step = Math.PI * 2.0 / RING_SEGMENTS;
		float previousX = (float) (cx + radius);
		float previousZ = (float) cz;
		for (int segment = 1; segment <= RING_SEGMENTS; segment++) {
			double angle = step * segment;
			float currentX = (float) (cx + Math.cos(angle) * radius);
			float currentZ = (float) (cz + Math.sin(angle) * radius);
			line(lines, pose, previousX, (float) cy, previousZ, currentX, (float) cy, currentZ, color, width);
			previousX = currentX;
			previousZ = currentZ;
		}
	}

	/** Draws a filled square, disc, or annulus parallel to the ground. Coordinates are camera-relative. */
	public static void renderFlatArea(PoseStack poseStack, MultiBufferSource bufferSource,
		double cx, double cy, double cz, double outerRadius, double innerRadius, boolean square,
		int color, boolean depthTested) {
		if (!Double.isFinite(outerRadius) || outerRadius <= 0 || !Double.isFinite(innerRadius)
			|| innerRadius < 0 || innerRadius >= outerRadius) return;

		PoseStack.Pose pose = poseStack.last();
		VertexConsumer quads = bufferSource.getBuffer(GeilerAddonsRenderTypes.quads(depthTested));
		if (square) {
			float x0 = (float) (cx - outerRadius);
			float x1 = (float) (cx + outerRadius);
			float z0 = (float) (cz - outerRadius);
			float z1 = (float) (cz + outerRadius);
			quad(quads, pose, x0, (float) cy, z0, x0, (float) cy, z1,
				x1, (float) cy, z1, x1, (float) cy, z0, color);
			return;
		}

		double step = Math.PI * 2.0 / RING_SEGMENTS;
		for (int segment = 0; segment < RING_SEGMENTS; segment++) {
			double firstAngle = step * segment;
			double secondAngle = step * (segment + 1);
			float outerX0 = (float) (cx + Math.cos(firstAngle) * outerRadius);
			float outerZ0 = (float) (cz + Math.sin(firstAngle) * outerRadius);
			float outerX1 = (float) (cx + Math.cos(secondAngle) * outerRadius);
			float outerZ1 = (float) (cz + Math.sin(secondAngle) * outerRadius);
			if (innerRadius == 0) {
				quad(quads, pose, (float) cx, (float) cy, (float) cz,
					outerX0, (float) cy, outerZ0, outerX1, (float) cy, outerZ1,
					outerX1, (float) cy, outerZ1, color);
				continue;
			}
			float innerX0 = (float) (cx + Math.cos(firstAngle) * innerRadius);
			float innerZ0 = (float) (cz + Math.sin(firstAngle) * innerRadius);
			float innerX1 = (float) (cx + Math.cos(secondAngle) * innerRadius);
			float innerZ1 = (float) (cz + Math.sin(secondAngle) * innerRadius);
			quad(quads, pose, outerX0, (float) cy, outerZ0, outerX1, (float) cy, outerZ1,
				innerX1, (float) cy, innerZ1, innerX0, (float) cy, innerZ0, color);
		}
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
