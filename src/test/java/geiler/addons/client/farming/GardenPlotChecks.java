package geiler.addons.client.farming;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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
		checkPestWidgetParsing();
		checkWidgetClearAndLifetime();
	}

	private static void checkGridMapping() {
		check(GardenPlotGrid.plots().size() == 25, "Garden grid contains exactly 25 plots");
		check(idAt(-192, 64, -192) == 21, "north-west plot center maps to server plot 21");
		check(idAt(0, 64, 0) == 0, "center plot maps to server plot 0");
		check(idAt(192, 64, 192) == 24, "south-east plot center maps to server plot 24");
		check(idAt(-240, 64, -240) == 21, "minimum Garden coordinate belongs to the outer plot");
		check(idAt(240, 64, 240) == 24, "maximum Garden coordinate is included in the final plot");
		check(idAt(-144, 64, 0) == 2, "an exact internal X boundary selects the plot to its east");
		// Increasing Z is south, so the boundary at z = -144 belongs to the row north of it: that is
		// the row holding server id 1, which is why the first entry of IDS_BY_ROW is the north edge.
		check(idAt(0, 64, -144) == 1, "an exact internal Z boundary selects the plot to its north");
		check(GardenPlotGrid.plotAt(240.001, 64, 0).isEmpty(), "coordinates past the grid do not map to plots");
		check(GardenPlotGrid.plotAt(0, 256, 0).isEmpty(), "positions above Garden build height do not map to plots");
		check(GardenPlotGrid.plotAt(Double.NaN, 64, 0).isEmpty(), "non-finite positions are rejected");
		check(GardenPlotGrid.plotAt(0, Double.POSITIVE_INFINITY, 0).isEmpty(),
			"an infinite height is rejected");
		// The two-argument form is the one that maps a pest marker's horizontal position, and it takes
		// no build-height guard with it, so its own finiteness rule has to hold on its own.
		check(GardenPlotGrid.plotAt(Double.NaN, 0).isEmpty(), "a non-finite X is rejected without a Y guard");
		check(GardenPlotGrid.plotAt(0, Double.NaN).isEmpty(), "a non-finite Z is rejected without a Y guard");
		check(GardenPlotGrid.plotAt(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY).isEmpty(),
			"infinite horizontal coordinates are rejected");
		check(GardenPlotGrid.plotAt(0, 0).isPresent(), "finite horizontal coordinates still map");
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
		// Unknown or expired evidence is dashed, so it cannot read as confirmed clear.
		List<GardenPlotGeometry.Segment> dashed = GardenPlotGeometry.dashedSegments(
			GardenPlotGeometry.perimeter(plot, 72.25, 0.5), 8, 6);
		check(dashed.size() > solid.size(), "unknown-state outline is segmented rather than solid");
		check(dashed.stream().allMatch(dash -> dash.length() <= 8.0001), "unknown outline respects dash length");
		check(GardenPlotGeometry.dashedSegments(GardenPlotGeometry.perimeter(plot, 72, 0), 0, 3).isEmpty(),
			"invalid dash settings fail closed");
		checkDashBudget(plot);
		checkBoxEdges(plot);
	}

	/**
	 * A box is a box only if its walls reach its rings. They did not: the inset was applied twice, so
	 * every vertical stood a full padding inside both rings and all eight corners were open. Asserting
	 * "at least this far inside" never caught it, so these checks compare the corners exactly.
	 */
	private static void checkBoxCorners(GardenPlotGrid.Plot plot) {
		for (double inset : new double[] { 0.0, 0.5, 2.0 }) {
			for (double[] heights : new double[][] { { 70, 73 }, { 73, 70 }, { 70, 70 } }) {
				List<GardenPlotGeometry.Segment> box =
					GardenPlotGeometry.boxEdges(plot, heights[0], heights[1], inset);
				String label = "a box at inset " + inset;
				check(box.size() == 12, label + " still has twelve edges");
				check(box.stream().allMatch(edge -> closeTo(edge.from().x(), plot.minX() + inset)
					|| closeTo(edge.from().x(), plot.maxX() - inset)),
					label + " keeps its X inset exactly once");
				check(box.stream().allMatch(edge -> closeTo(edge.from().z(), plot.minZ() + inset)
					|| closeTo(edge.from().z(), plot.maxZ() - inset)),
					label + " keeps its Z inset exactly once");
				// Every ring corner has to be the foot of a vertical, in all three axes.
				for (GardenPlotGeometry.Segment ring : box) {
					if (!isRing(ring)) continue;
					for (GardenPlotGeometry.Point corner : List.of(ring.from(), ring.to())) {
						check(box.stream().anyMatch(edge -> isVertical(edge)
								&& sameColumn(edge.from(), corner)),
							label + " closes its corner at " + corner.x() + "/" + corner.z());
					}
				}
			}
		}
	}

	/**
	 * Dashes have to stay affordable. A box dashes each of its twelve edges on its own, and the long
	 * rings used to explode into about sixty segments per plot - roughly fifteen hundred line draws
	 * in a frame where every plot is unknown, which is what a fresh session looks like.
	 */
	private static void checkDashBudget(GardenPlotGrid.Plot plot) {
		List<GardenPlotGeometry.Segment> box = GardenPlotGeometry.boxEdges(plot, 70, 73, 0.5);
		List<GardenPlotGeometry.Segment> bounded =
			GardenPlotGeometry.dashedSegments(box, 8, 6, GardenPlotGeometry.NO_DASH_BUDGET);
		check(bounded.size() > box.size(), "unbounded dashes still segment a box");
		// The long rings are what blew up, so the bound that matters is a tight one per edge.
		List<GardenPlotGeometry.Segment> capped = GardenPlotGeometry.dashedSegments(box, 8, 6, 1);
		check(capped.size() <= 12, "a per-edge budget of one caps a whole box at twelve dashes");
		check(capped.size() < bounded.size(), "the budget actually reduces the segment count here");
		check(!capped.isEmpty(), "a bounded box is still dashed rather than dropped");
		check(capped.stream().allMatch(dash -> dash.length() > 0.0), "every surviving dash has length");
		check(GardenPlotGeometry.NO_DASH_BUDGET == 0, "the no-budget sentinel stays at zero");
		// A lenient budget must not touch an edge that is already inside it.
		List<GardenPlotGeometry.Segment> lenient = GardenPlotGeometry.dashedSegments(box, 8, 6, 64);
		check(lenient.size() == bounded.size(), "a budget nothing exceeds changes nothing");
		check(GardenPlotGeometry.dashedSegments(
				GardenPlotGeometry.perimeter(plot, 72, 0.5), 8, 6, 64).size() == 28,
			"an unbudgeted ring keeps dashing at its requested length");

		// The budget is per edge, so one very long edge cannot eat the whole allowance.
		List<GardenPlotGeometry.Segment> oneLong =
			List.of(new GardenPlotGeometry.Segment(solidPoint(0, 70, 0), solidPoint(100, 70, 0)));
		int longDashes = GardenPlotGeometry.dashedSegments(oneLong, 8, 6, 3).size();
		check(longDashes <= 3,
			"a single long edge obeys the budget instead of dashing to its natural count, got "
				+ longDashes);

		check(GardenPlotGeometry.dashedSegments(box, 8, 6, -1).size() == bounded.size(),
			"a non-positive budget means no budget");
	}

	private static boolean isRing(GardenPlotGeometry.Segment edge) {
		return edge.from().y() == edge.to().y();
	}

	private static boolean isVertical(GardenPlotGeometry.Segment edge) {
		return edge.from().y() != edge.to().y();
	}

	private static boolean sameColumn(GardenPlotGeometry.Point a, GardenPlotGeometry.Point b) {
		return closeTo(a.x(), b.x()) && closeTo(a.z(), b.z());
	}

	private static boolean closeTo(double a, double b) {
		return Math.abs(a - b) < 0.000001;
	}

	private static GardenPlotGeometry.Point solidPoint(double x, double y, double z) {
		return new GardenPlotGeometry.Point(x, y, z);
	}

	/**
	 * The border is a fixed landmark now: every edge lives between the two stored heights and none
	 * of them can move when the player does.
	 */
	private static void checkBoxEdges(GardenPlotGrid.Plot plot) {
		List<GardenPlotGeometry.Segment> box = GardenPlotGeometry.boxEdges(plot, 70, 73, 0.5);
		check(box.size() == 12, "a plot box has twelve edges");
		check(box.stream().allMatch(edge -> edge.from().y() >= 70.0 && edge.to().y() >= 70.0
			&& edge.from().y() <= 73.0 && edge.to().y() <= 73.0),
			"every box edge stays between the fixed bottom and top heights");
		check(box.stream().anyMatch(edge -> edge.from().y() == 70.0 && edge.to().y() == 70.0),
			"the box has a bottom ring at the stored world height");
		check(box.stream().anyMatch(edge -> edge.from().y() == 73.0 && edge.to().y() == 73.0),
			"the box has a top ring at the wall height");
		check(box.stream().anyMatch(edge -> edge.from().y() == 70.0 && edge.to().y() == 73.0),
			"the box has vertical walls connecting them");
		check(box.stream().allMatch(edge -> edge.from().x() >= plot.minX() + 0.5
			&& edge.from().x() <= plot.maxX() - 0.5), "the box keeps its inset on the X axis");
		check(box.stream().allMatch(edge -> edge.from().z() >= plot.minZ() + 0.5
			&& edge.from().z() <= plot.maxZ() - 0.5), "the box keeps its inset on the Z axis");

		// Reversed and degenerate heights still produce a drawable box rather than nothing.
		check(GardenPlotGeometry.boxEdges(plot, 73, 70, 0.5).size() == 12,
			"reversed heights are normalised into a box");
		List<GardenPlotGeometry.Segment> flat = GardenPlotGeometry.boxEdges(plot, 70, 70, 0.5);
		check(flat.size() == 12, "a degenerate height still draws a box");
		check(flat.stream().allMatch(edge -> Math.abs(edge.length()) < 96.0001),
			"a degenerate box stays within the plot");
		check(GardenPlotGeometry.boxEdges(null, 70, 73, 0.5).isEmpty(), "a missing plot yields no geometry");
		check(GardenPlotGeometry.boxEdges(plot, Double.NaN, 73, 0.5).isEmpty(),
			"a non-finite height yields no geometry");

		List<GardenPlotGeometry.Segment> dashedBox = GardenPlotGeometry.dashedSegments(
			GardenPlotGeometry.boxEdges(plot, 70, 73, 0.5), 8, 6);
		check(dashedBox.size() > box.size(), "a dashed box is segmented rather than solid");
		check(dashedBox.stream().allMatch(dash -> dash.length() <= 8.0001), "a dashed box respects dash length");
		check(GardenPlotGeometry.dashedSegments(box, 0, 6).isEmpty(), "an invalid dash fails closed");
		checkBoxCorners(plot);
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

	/**
	 * The widget reader is the one whole-Garden source, so its failures matter as much as its
	 * successes: every rejection below is a case where believing a partial tab list would have
	 * certified a plot as clean that had simply not rendered yet.
	 */
	private static void checkPestWidgetParsing() {
		// A realistic tab list, with the Pests widget between two other widgets.
		List<String> tabList = List.of(
			"§7Players (4)",
			"§7Info",
			"§7Profile: §aApple",
			"§7Skills: §e30.5",
			"§7Bank: §e1,000,000",
			"§7The Garden",
			"§7Pests:",
			"§e5 §7pests",
			"§7Plots: §a4, 12, 13, 18, 20",
			"§7Visitors (§e3§7)",
			"§7Crop Milestones: §e12");
		check(read(tabList).equals(Optional.of(Set.of(4, 12, 13, 18, 20))),
			"the widget names every infested plot it lists");
		check(read(List.of("§7Pests:", "§e1 §7pest", "§7Plots: §a9"))
				.equals(Optional.of(Set.of(9))),
			"a single-plot widget is read, and a singular count row does not end the block");
		check(read(List.of("§7Pests:", "§7Plots: §aNone")).equals(Optional.of(Set.of())),
			"an explicit none is a decisive empty list, not a failed parse");
		check(read(List.of("§7Pests:", "§7Plots: §7None")).equals(Optional.of(Set.of())),
			"a colour-coded none is still read as none");
		check(read(List.of("§7Pests: §a4", "§7Plots: §a3")).equals(Optional.of(Set.of(3))),
			"a count on the header line does not hide the plot list");

		check(read(List.of("§7Bank: §e200", "§7Skills: §e12")).isEmpty(),
			"a tab list without the widget is unknown rather than clean");
		check(read(List.of()).isEmpty(), "an empty tab list yields nothing");
		check(read(List.of("§7Pests:")).isEmpty(),
			"a header with no data rows is a partial read and is rejected");
		check(read(List.of("§7Pests:", "§7Visitors (§e1§7)")).isEmpty(),
			"a header followed by another widget is rejected");
		check(read(List.of("§7Pests:", "§7Plots: §a3", "§dQueue: §bSkyBlock")).isEmpty(),
			"a row the reader does not understand inside the block rejects the whole read");
		check(read(List.of("§7Pests:", "§7Plots: §a3, 40")).isEmpty(),
			"an out-of-grid plot id is rejected");
		check(read(List.of("§7Pests:", "§7Plots: §a3, banana")).isEmpty(),
			"a non-numeric plot id is rejected");
		check(read(List.of("§7Pests:", "§7Plots: §a")).isEmpty(),
			"an empty plot list without an explicit none is rejected");
		check(read(List.of("§7Pests:", "§7Plots: §a4", "§7Pests:", "§7Plots: §a5")).isEmpty(),
			"two widget headers cannot both be the widget");
	}

	/** The clear path BUG-005 was about: a plot stops reading as infested once it leaves the widget. */
	private static void checkWidgetClearAndLifetime() {
		GardenPlotState state = new GardenPlotState();
		check(state.observePestsWidgetSnapshot(Set.of(3, 10), 10_000), "full widget snapshot is accepted");
		check(status(state.snapshot(10_001, FRESH_FOR), 10).status() == GardenPlotState.Status.INFESTED,
			"a listed plot is infested");
		check(state.observePestsWidgetSnapshot(Set.of(3), 11_000), "the next widget snapshot is accepted");
		GardenPlotState.PlotStatus cleared = status(state.snapshot(11_001, FRESH_FOR), 10);
		check(cleared.status() == GardenPlotState.Status.CLEAR
			&& Integer.valueOf(0).equals(cleared.pestCount())
			&& cleared.source() == GardenPlotState.Source.PESTS_WIDGET,
			"a plot that leaves the infested list is cleared by omission");

		// A steady garden republishes nothing, so the reader re-stamps what the widget still says.
		long refreshedAt = 20_000L;
		state.refreshWidgetObservation(refreshedAt);
		GardenPlotState.PlotStatus stayed = status(state.snapshot(refreshedAt, FRESH_FOR), 3);
		check(stayed.status() == GardenPlotState.Status.INFESTED && !stayed.stale()
			&& stayed.source() == GardenPlotState.Source.PESTS_WIDGET,
			"refreshing keeps widget evidence fresh without changing it");
		GardenPlotState.PlotStatus refreshedCleared = status(state.snapshot(refreshedAt, FRESH_FOR), 10);
		check(refreshedCleared.status() == GardenPlotState.Status.CLEAR && !refreshedCleared.stale(),
			"a refreshed clear stays clear");
		// Well past the age the first observation would have expired at, so only the re-stamp can
		// be keeping the widget's knowledge alive.
		long later = refreshedAt + FRESH_FOR / 2;
		check(status(state.snapshot(later, FRESH_FOR), 3).status() == GardenPlotState.Status.INFESTED
			&& !status(state.snapshot(later, FRESH_FOR), 3).stale(),
			"a refreshed plot is still infested when its original observation would have expired");

		// Direct observations are a different kind of fact and survive a blanket clear.
		GardenPlotState precedence = new GardenPlotState();
		check(precedence.observeVisiblePest(7, 5_000), "a visible pest is recorded");
		check(precedence.observeGardenPestTotal(0, 5_000), "a same-instant zero total is accepted");
		check(status(precedence.snapshot(5_001, FRESH_FOR), 7).status() == GardenPlotState.Status.INFESTED,
			"a blanket clear does not erase a directly observed pest");
		check(precedence.observeGardenPestTotal(0, 60_000), "a later zero total is accepted");
		check(status(precedence.snapshot(60_001, FRESH_FOR), 7).status() == GardenPlotState.Status.CLEAR,
			"a later blanket clear does clear a stale sighting");
	}

	/** Drives the parser through the reader's own seam rather than calling the parser directly. */
	private static Optional<Set<Integer>> read(List<String> tabList) {
		return new PestWidgetReader(() -> tabList).read();
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
