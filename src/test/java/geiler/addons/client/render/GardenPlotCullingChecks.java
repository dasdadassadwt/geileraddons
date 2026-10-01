package geiler.addons.client.render;

import geiler.addons.client.farming.GardenPlotGrid;
import geiler.addons.client.farming.GardenPlotState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Offline checks for the Garden border renderer's culling.
 *
 * <p>A full grid is 25 boxes, each twelve segments before any dashing, projected and drawn every
 * frame. These checks pin the cut-offs that keep that bounded, and pin the one thing a cut-off can
 * get wrong: culling a plot the player is standing beside, because a 96-block plot is far from its
 * own centre.
 */
public final class GardenPlotCullingChecks {
	private GardenPlotCullingChecks() { }

	public static void run() {
		checkRange();
		checkLabels();
		checkDegenerateInput();
	}

	private static void checkRange() {
		// The camera sits in the middle of plot 0, which spans -48..48 on both axes.
		Vec3 centre = new Vec3(0, 70, 0);
		List<GardenPlotState.PlotStatus> all = allPlots();

		List<GardenPlotState.PlotStatus> own = GardenPlotBorderRenderer.withinRange(all, centre, 10.0);
		check(own.size() == 1 && own.getFirst().plotId() == 0,
			"the plot the camera stands in is never culled, however small the range");

		// Plot 1 is the -144..-48 column of the same row: its nearest edge is 48 blocks away, so a
		// range of 47 must drop it and a range of 49 must keep it.
		check(!GardenPlotBorderRenderer.withinRange(all, centre, 47.0).stream()
				.anyMatch(status -> status.plotId() == 1),
			"a plot beyond the range is dropped");
		check(GardenPlotBorderRenderer.withinRange(all, centre, 49.0).stream()
				.anyMatch(status -> status.plotId() == 1),
			"a plot whose edge is inside the range is kept");

		// Distance is measured to the plot's edge, so a plot stays visible while the camera is beside
		// it even though its centre is most of a plot away. At x = 40 the camera is inside plot 0 and
		// eight blocks from plot 3's nearest edge, while plot 1's edge is 88 blocks off.
		Vec3 nearEdge = new Vec3(40, 70, 0);
		List<GardenPlotState.PlotStatus> beside = GardenPlotBorderRenderer.withinRange(all, nearEdge, 20.0);
		check(beside.stream().anyMatch(status -> status.plotId() == 0),
			"the plot the camera is standing in survives a range smaller than its own size");
		check(beside.stream().anyMatch(status -> status.plotId() == 3),
			"a plot whose nearest edge is inside the range is kept even though its centre is 104 blocks away");
		check(beside.stream().noneMatch(status -> status.plotId() == 1),
			"a plot whose edge is out of range is still dropped");
		// A centre-based cut-off and an edge-based one disagree only just past the grid's corner: at
		// x = 200 and z = 100 this is outside the grid, so plot 0's centre is 152 blocks away and
		// would be culled by a 150 range, while its nearest edge is 152 too - but plot 3's edge is
		// only 60 blocks away despite its centre being 188 off. Judging by edge is what keeps the
		// plot the player is beside on screen.
		Vec3 outside = new Vec3(200, 70, 100);
		List<GardenPlotState.PlotStatus> fromOutside = GardenPlotBorderRenderer.withinRange(all, outside, 150.0);
		check(fromOutside.stream().noneMatch(status -> status.plotId() == 0),
			"a plot whose edge is beyond the range is dropped even with the camera outside the grid");
		check(fromOutside.stream().anyMatch(status -> status.plotId() == 3),
			"the plot the camera is beside survives although its centre is 188 blocks away");
		check(!GardenPlotBorderRenderer.withinRange(all, outside, 30.0).isEmpty(),
			"a plot with an edge inside a small range is kept from outside the grid");

		List<GardenPlotState.PlotStatus> ordered = GardenPlotBorderRenderer.withinRange(all, centre, 4000.0);
		check(ordered.size() == 25, "the whole grid stays visible from the middle");
		check(ordered.equals(all), "culling preserves the snapshot's own order");
	}

	private static void checkLabels() {
		Vec3 centre = new Vec3(0, 70, 0);
		List<GardenPlotState.PlotStatus> labelled = GardenPlotBorderRenderer.nearestLabels(allPlots(), centre);
		check(labelled.size() <= 8, "at most eight labels are drawn at once, got " + labelled.size());
		check(!labelled.isEmpty(), "the nearby plots still get labels");
		check(labelled.stream().allMatch(status -> status.plotId() == 0 || distance(status) <= 160.0),
			"chosen labels are inside the label range");
		check(labelled.stream().noneMatch(status -> status.plotId() == 24),
			"a far corner of the Garden is not labelled from the centre");

		// The nearest plots win, so the label set is the eight closest rather than an arbitrary eight.
		Set<Integer> chosen = new HashSet<>();
		for (GardenPlotState.PlotStatus status : labelled) chosen.add(status.plotId());
		List<GardenPlotState.PlotStatus> byDistance = new ArrayList<>(allPlots());
		byDistance.sort(java.util.Comparator.comparingDouble(GardenPlotCullingChecks::distance));
		for (int index = 0; index < labelled.size(); index++) {
			check(chosen.contains(byDistance.get(index).plotId()),
				"label " + index + " is one of the nearest plots");
		}
	}

	private static void checkDegenerateInput() {
		Vec3 centre = new Vec3(0, 70, 0);
		check(GardenPlotBorderRenderer.withinRange(null, centre, 100.0).isEmpty(),
			"a missing snapshot yields nothing to draw");
		check(GardenPlotBorderRenderer.withinRange(List.of(), centre, 100.0).isEmpty(),
			"an empty snapshot yields nothing to draw");
		check(GardenPlotBorderRenderer.withinRange(allPlots(), null, 100.0).isEmpty(),
			"a missing camera position yields nothing to draw");
		check(GardenPlotBorderRenderer.withinRange(allPlots(), centre, -1.0).isEmpty(),
			"a negative range yields nothing to draw");
		check(GardenPlotBorderRenderer.withinRange(allPlots(), centre, Double.NaN).isEmpty(),
			"a non-finite range yields nothing to draw");
		check(GardenPlotBorderRenderer.nearestLabels(allPlots(), null).isEmpty(),
			"labels need a camera position too");
	}

	private static double distance(GardenPlotState.PlotStatus status) {
		GardenPlotGrid.Plot plot = GardenPlotGrid.plotById(status.plotId()).orElseThrow();
		return Math.hypot(plot.centerX(), plot.centerZ());
	}

	private static List<GardenPlotState.PlotStatus> allPlots() {
		List<GardenPlotState.PlotStatus> statuses = new ArrayList<>(25);
		for (GardenPlotGrid.Plot plot : GardenPlotGrid.plots()) {
			statuses.add(new GardenPlotState.PlotStatus(plot.id(), GardenPlotState.Status.UNKNOWN, null,
				1_000L, false, GardenPlotState.Source.NONE));
		}
		return List.copyOf(statuses);
	}

	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}
}
