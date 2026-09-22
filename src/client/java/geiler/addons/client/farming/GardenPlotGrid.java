package geiler.addons.client.farming;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Coordinate and server-id mapping for the Garden's 5 by 5 plot grid. */
public final class GardenPlotGrid {
	public static final int GRID_SIZE = 5;
	public static final double PLOT_SIZE = 96.0;
	public static final double MIN_COORDINATE = -240.0;
	public static final double MAX_COORDINATE = 240.0;
	public static final double MIN_BUILD_Y = 0.0;
	public static final double MAX_BUILD_Y = 256.0;

	/* Server plot IDs are arranged by Z row, not in simple row-major ID order. */
	private static final int[][] IDS_BY_ROW = {
		{21, 13, 9, 14, 22},
		{15, 5, 1, 6, 16},
		{10, 2, 0, 3, 11},
		{17, 7, 4, 8, 18},
		{23, 19, 12, 20, 24}
	};
	private static final List<Plot> PLOTS = buildPlots();
	private static final Map<Integer, Plot> BY_ID = indexById(PLOTS);

	private GardenPlotGrid() { }

	/** Maps an in-Garden world position to its plot; the outer +240 edge belongs to the final row/column. */
	public static Optional<Plot> plotAt(double x, double y, double z) {
		if (!Double.isFinite(y) || y < MIN_BUILD_Y || y >= MAX_BUILD_Y) return Optional.empty();
		int column = coordinateIndex(x);
		int row = coordinateIndex(z);
		if (row < 0 || column < 0) return Optional.empty();
		return Optional.of(BY_ID.get(IDS_BY_ROW[row][column]));
	}

	/** Maps horizontal coordinates when the caller already established Garden/build-height context. */
	public static Optional<Plot> plotAt(double x, double z) {
		int column = coordinateIndex(x);
		int row = coordinateIndex(z);
		if (row < 0 || column < 0) return Optional.empty();
		return Optional.of(BY_ID.get(IDS_BY_ROW[row][column]));
	}

	public static Optional<Plot> plotById(int id) {
		return Optional.ofNullable(BY_ID.get(id));
	}

	public static boolean isValidId(int id) {
		return BY_ID.containsKey(id);
	}

	public static List<Plot> plots() {
		return PLOTS;
	}

	private static int coordinateIndex(double coordinate) {
		if (!Double.isFinite(coordinate) || coordinate < MIN_COORDINATE || coordinate > MAX_COORDINATE) {
			return -1;
		}
		if (coordinate == MAX_COORDINATE) return GRID_SIZE - 1;
		return (int) Math.floor((coordinate - MIN_COORDINATE) / PLOT_SIZE);
	}

	private static List<Plot> buildPlots() {
		List<Plot> plots = new ArrayList<>(GRID_SIZE * GRID_SIZE);
		for (int row = 0; row < GRID_SIZE; row++) {
			for (int column = 0; column < GRID_SIZE; column++) {
				double minX = MIN_COORDINATE + column * PLOT_SIZE;
				double minZ = MIN_COORDINATE + row * PLOT_SIZE;
				plots.add(new Plot(IDS_BY_ROW[row][column], row, column, minX, minZ));
			}
		}
		return List.copyOf(plots);
	}

	private static Map<Integer, Plot> indexById(List<Plot> plots) {
		Map<Integer, Plot> byId = new HashMap<>();
		for (Plot plot : plots) {
			if (byId.put(plot.id(), plot) != null) {
				throw new ExceptionInInitializerError("Duplicate Garden plot id " + plot.id());
			}
		}
		if (byId.size() != GRID_SIZE * GRID_SIZE) {
			throw new ExceptionInInitializerError("Garden plot grid must contain 25 unique plots");
		}
		return Map.copyOf(byId);
	}

	public record Plot(int id, int row, int column, double minX, double minZ) {
		public double maxX() { return minX + PLOT_SIZE; }
		public double maxZ() { return minZ + PLOT_SIZE; }
		public double centerX() { return minX + PLOT_SIZE / 2.0; }
		public double centerZ() { return minZ + PLOT_SIZE / 2.0; }
	}
}
