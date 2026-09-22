package geiler.addons.client.farming;

import java.util.ArrayList;
import java.util.List;

/** Pure world-space border geometry, separated for offline validation. */
public final class GardenPlotGeometry {
	private GardenPlotGeometry() { }

	public static List<Segment> perimeter(GardenPlotGrid.Plot plot, double y, double inset) {
		if (plot == null || !Double.isFinite(y) || !Double.isFinite(inset)) return List.of();
		double padding = clamp(inset, 0.0, GardenPlotGrid.PLOT_SIZE / 2.0 - 0.01);
		double minX = plot.minX() + padding;
		double maxX = plot.maxX() - padding;
		double minZ = plot.minZ() + padding;
		double maxZ = plot.maxZ() - padding;
		Point northWest = new Point(minX, y, minZ);
		Point northEast = new Point(maxX, y, minZ);
		Point southEast = new Point(maxX, y, maxZ);
		Point southWest = new Point(minX, y, maxZ);
		return List.of(
			new Segment(northWest, northEast),
			new Segment(northEast, southEast),
			new Segment(southEast, southWest),
			new Segment(southWest, northWest)
		);
	}

	/**
	 * The twelve edges of a plot box between two fixed world heights.
	 *
	 * <p>Nothing here is relative to the player: a border is a landmark, so it has to stay where it
	 * was put while the player walks, jumps and changes floors. The two heights are the box's floor
	 * and ceiling, and a degenerate pair still yields a visible sliver rather than nothing.
	 */
	public static List<Segment> boxEdges(GardenPlotGrid.Plot plot, double bottomY, double topY, double inset) {
		if (plot == null || !Double.isFinite(bottomY) || !Double.isFinite(topY) || !Double.isFinite(inset)) {
			return List.of();
		}
		double low = Math.min(bottomY, topY);
		double high = Math.max(bottomY, topY);
		if (high - low < MIN_BOX_HEIGHT) high = low + MIN_BOX_HEIGHT;
		double padding = clamp(inset, 0.0, GardenPlotGrid.PLOT_SIZE / 2.0 - 0.01);
		double minX = plot.minX() + padding;
		double maxX = plot.maxX() - padding;
		double minZ = plot.minZ() + padding;
		double maxZ = plot.maxZ() - padding;
		List<Segment> edges = new ArrayList<>(12);
		// The four verticals first, so a dashed box still shows its corners before its rings.
		edges.add(new Segment(new Point(minX, low, minZ), new Point(minX, high, minZ)));
		edges.add(new Segment(new Point(maxX, low, minZ), new Point(maxX, high, minZ)));
		edges.add(new Segment(new Point(maxX, low, maxZ), new Point(maxX, high, maxZ)));
		edges.add(new Segment(new Point(minX, low, maxZ), new Point(minX, high, maxZ)));
		edges.addAll(perimeter(plot, high, padding));
		edges.addAll(perimeter(plot, low, padding));
		return List.copyOf(edges);
	}

	/** Dashes any list of edges, so a flat perimeter and a box share one dash rule. */
	public static List<Segment> dashedSegments(List<Segment> edges, double dashLength, double gapLength) {
		if (edges == null || !Double.isFinite(dashLength) || !Double.isFinite(gapLength)
			|| dashLength <= 0.0 || gapLength < 0.0) {
			return List.of();
		}
		List<Segment> dashes = new ArrayList<>();
		for (Segment edge : edges) {
			if (edge == null) continue;
			double length = edge.length();
			for (double start = 0.0; start < length; start += dashLength + gapLength) {
				double end = Math.min(length, start + dashLength);
				dashes.add(new Segment(edge.pointAt(start / length), edge.pointAt(end / length)));
			}
		}
		return List.copyOf(dashes);
	}

	/** Shortest box the renderer will draw, in blocks. */
	private static final double MIN_BOX_HEIGHT = 0.05;

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	public record Point(double x, double y, double z) { }

	public record Segment(Point from, Point to) {
		public double length() {
			return Math.sqrt(square(to.x() - from.x()) + square(to.y() - from.y()) + square(to.z() - from.z()));
		}

		private Point pointAt(double fraction) {
			return new Point(
				from.x() + (to.x() - from.x()) * fraction,
				from.y() + (to.y() - from.y()) * fraction,
				from.z() + (to.z() - from.z()) * fraction
			);
		}

		private static double square(double value) { return value * value; }
	}
}
