package geiler.addons.client.farming;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Offline geometry and evidence-lifetime checks for Garden plot borders. */
public final class GardenPlotChecks {
	private static final long FRESH_FOR = 120_000L;

	private GardenPlotChecks() { }

	public static void main(String[] args) {
		run();
	}

	public static void run() {
		checkGridMapping();
		checkGeometry();
		checkEvidenceStates();
		checkVisibleSourcesAndChat();
	}

	private static void checkGridMapping() {
		check(GardenPlotGrid.plots().size() == 25, "Garden grid contains exactly 25 plots");
		check(idAt(-192, 64, -192) == 21, "north-west plot center maps to server plot 21");
		check(idAt(0, 64, 0) == 0, "center plot maps to server plot 0");
		check(idAt(192, 64, 192) == 24, "south-east plot center maps to server plot 24");
		check(idAt(-240, 64, -240) == 21, "minimum Garden coordinate belongs to the outer plot");
		check(idAt(240, 64, 240) == 24, "maximum Garden coordinate is included in the final plot");
		check(idAt(-144, 64, 0) == 2, "an exact internal X boundary selects the plot to its east");
		check(idAt(0, 64, -144) == 1, "an exact internal Z boundary selects the plot to its south");
		check(GardenPlotGrid.plotAt(240.001, 64, 0).isEmpty(), "coordinates past the grid do not map to plots");
		check(GardenPlotGrid.plotAt(0, 256, 0).isEmpty(), "positions above Garden build height do not map to plots");
		check(GardenPlotGrid.plotAt(Double.NaN, 64, 0).isEmpty(), "non-finite positions are rejected");
		for (int id = 0; id < 25; id++) {
			GardenPlotGrid.Plot plot = GardenPlotGrid.plotById(id).orElseThrow();
			check(idAt(plot.centerX(), 64, plot.centerZ()) == id, "plot id round-trips through its center: " + id);
		}
	}

	private static void checkGeometry() {
		GardenPlotGrid.Plot plot = GardenPlotGrid.plotById(0).orElseThrow();
		List<GardenPlotGeometry.Segment> solid = GardenPlotGeometry.perimeter(plot, 72.25, 0.5);
		check(solid.size() == 4, "solid plot border has four perimeter edges");
		check(solid.getFirst().from().x() == plot.minX() + 0.5
			&& solid.getFirst().from().z() == plot.minZ() + 0.5,
			"border inset stays just inside the plot boundary");
		check(Math.abs(solid.getFirst().length() - 95.0) < 0.0001,
			"inset border spans the 96-block plot minus both margins");
		check(solid.stream().allMatch(edge -> edge.from().y() == 72.25 && edge.to().y() == 72.25),
			"all border segments stay on the selected render plane");
		List<GardenPlotGeometry.Segment> dashed = GardenPlotGeometry.dashedPerimeter(plot, 72.25, 0.5, 8, 6);
		check(dashed.size() > solid.size(), "unknown-state outline is segmented rather than solid");
		check(dashed.stream().allMatch(dash -> dash.length() <= 8.0001), "unknown outline respects dash length");
		check(GardenPlotGeometry.dashedPerimeter(plot, 72, 0, 0, 3).isEmpty(),
			"invalid dash settings fail closed");
	}

	private static void checkEvidenceStates() {
		GardenPlotState state = new GardenPlotState();
		check(all(state.snapshot(10_000, FRESH_FOR), GardenPlotState.Status.UNKNOWN),
			"plots without evidence start unknown");
		check(state.observePestsWidgetSnapshot(Set.of(3, 10), 10_000), "valid full widget snapshot is accepted");
		List<GardenPlotState.PlotStatus> snapshot = state.snapshot(10_000 + FRESH_FOR, FRESH_FOR);
		check(status(snapshot, 3).status() == GardenPlotState.Status.INFESTED
			&& status(snapshot, 3).pestCount() == null,
			"widget membership confirms infestation without inventing an exact count");
		check(status(snapshot, 4).status() == GardenPlotState.Status.CLEAR
			&& Integer.valueOf(0).equals(status(snapshot, 4).pestCount()),
			"omission from a valid full widget snapshot confirms clear");
		List<GardenPlotState.PlotStatus> stale = state.snapshot(10_001 + FRESH_FOR, FRESH_FOR);
		check(status(stale, 3).status() == GardenPlotState.Status.UNKNOWN && status(stale, 3).stale()
			&& status(stale, 3).pestCount() == null,
			"expired infestation becomes explicitly stale unknown rather than clean");
		check(status(stale, 4).status() == GardenPlotState.Status.UNKNOWN && status(stale, 4).stale(),
			"expired clear evidence also becomes stale unknown");

		state.reset();
		check(state.observePlotMenuCounts(Map.of(7, 0, 8, 4), 20_000), "visible plot counts are accepted");
		snapshot = state.snapshot(20_001, FRESH_FOR);
		check(status(snapshot, 7).status() == GardenPlotState.Status.CLEAR
			&& Integer.valueOf(0).equals(status(snapshot, 7).pestCount()), "menu zero count confirms clear");
		check(status(snapshot, 8).status() == GardenPlotState.Status.INFESTED
			&& Integer.valueOf(4).equals(status(snapshot, 8).pestCount()), "menu count confirms infestation and exact count");
		check(status(snapshot, 9).status() == GardenPlotState.Status.UNKNOWN, "partial menu data leaves absent plots unknown");
		check(!state.observePestsWidgetSnapshot(Set.of(3, 25), 20_100), "invalid full snapshots are rejected atomically");
		check(status(state.snapshot(20_101, FRESH_FOR), 8).status() == GardenPlotState.Status.INFESTED,
			"rejected snapshot leaves prior evidence unchanged");
		check(state.observeGardenPestTotal(2, 20_200), "positive visible Garden total is accepted");
		snapshot = state.snapshot(20_201, FRESH_FOR);
		check(status(snapshot, 7).status() == GardenPlotState.Status.UNKNOWN,
			"positive total invalidates older blanket-clear data");
		check(status(snapshot, 8).status() == GardenPlotState.Status.INFESTED,
			"positive total does not erase a specific infested observation");
		check(state.observeGardenPestTotal(0, 20_300), "zero Garden total is accepted");
		check(all(state.snapshot(20_301, FRESH_FOR), GardenPlotState.Status.CLEAR),
			"visible zero Garden total confirms the whole grid clear");
	}

	private static void checkVisibleSourcesAndChat() {
		GardenPlotState state = new GardenPlotState();
		check(state.observeChatMessage("§aPlot §r§7- §r§b10 §r§ais now clean!", 1_000),
			"formatted explicit plot-clean chat is recognized");
		GardenPlotState.PlotStatus clean = status(state.snapshot(1_001, FRESH_FOR), 10);
		check(clean.status() == GardenPlotState.Status.CLEAR && clean.source() == GardenPlotState.Source.CLEAN_CHAT,
			"clean chat confirms only its named plot");
		check(status(state.snapshot(1_001, FRESH_FOR), 11).status() == GardenPlotState.Status.UNKNOWN,
			"clean chat does not imply other plots are clear");
		check(state.observeChatMessage("§cThere are not any Pests on your Garden right now! Keep farming!", 2_000),
			"explicit no-pests chat is recognized");
		check(all(state.snapshot(2_001, FRESH_FOR), GardenPlotState.Status.CLEAR),
			"no-pests chat confirms all plots clear");
		check(!state.observeChatMessage("Plot - 10 might be clean", 3_000), "ambiguous chat is ignored");
		check(state.observeCurrentPlotScoreboardCount(4, 2, 4_000), "current-plot count is accepted");
		check(state.observeVisiblePest(5, 4_100), "visible pest observation is accepted");
		List<GardenPlotState.PlotStatus> snapshot = state.snapshot(4_101, FRESH_FOR);
		check(status(snapshot, 4).status() == GardenPlotState.Status.INFESTED
			&& Integer.valueOf(2).equals(status(snapshot, 4).pestCount()), "current-plot scoreboard supplies exact count");
		check(status(snapshot, 5).status() == GardenPlotState.Status.INFESTED
			&& status(snapshot, 5).pestCount() == null, "visible entity proves infestation but not a count");
		state.reset();
		check(all(state.snapshot(5_000, FRESH_FOR), GardenPlotState.Status.UNKNOWN), "session reset clears transient data");
	}

	private static int idAt(double x, double y, double z) {
		return GardenPlotGrid.plotAt(x, y, z).orElseThrow().id();
	}

	private static GardenPlotState.PlotStatus status(List<GardenPlotState.PlotStatus> snapshot, int id) {
		return snapshot.stream().filter(plot -> plot.plotId() == id).findFirst().orElseThrow();
	}

	private static boolean all(List<GardenPlotState.PlotStatus> snapshot, GardenPlotState.Status expected) {
		return snapshot.size() == 25 && snapshot.stream().allMatch(plot -> plot.status() == expected);
	}

	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}
}
