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

	/** Builds a dashed perimeter used for unknown or expired evidence, so it cannot read as confirmed clear. */
	public static List<Segment> dashedPerimeter(GardenPlotGrid.Plot plot, double y, double inset,
		double dashLength, double gapLength) {
		if (!Double.isFinite(dashLength) || !Double.isFinite(gapLength) || dashLength <= 0.0 || gapLength < 0.0) {
			return List.of();
		}
		List<Segment> dashes = new ArrayList<>();
		for (Segment edge : perimeter(plot, y, inset)) {
			double length = edge.length();
			for (double start = 0.0; start < length; start += dashLength + gapLength) {
				double end = Math.min(length, start + dashLength);
				dashes.add(new Segment(edge.pointAt(start / length), edge.pointAt(end / length)));
			}
		}
		return List.copyOf(dashes);
	}

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
