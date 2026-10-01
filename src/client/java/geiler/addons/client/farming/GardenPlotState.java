package geiler.addons.client.farming;

import geiler.addons.client.tree.ChatText;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client-session Garden pest knowledge. Callers feed only observations already visible to the client;
 * no request or persistence layer exists here.
 */
public final class GardenPlotState {
	private static final Pattern CLEAN_PLOT = Pattern.compile("(?i)^Plot\\s*-\\s*(\\d+)\\s+is now clean!$");
	private static final Pattern NO_PESTS = Pattern.compile(
		"(?i)^There are not any Pests on your Garden right now! Keep farming!$");
	private final Map<Integer, Evidence> evidenceByPlot = new HashMap<>();

	public enum Status { INFESTED, CLEAR, UNKNOWN }

	public enum Source {
		NONE,
		PESTS_WIDGET,
		PLOT_MENU,
		CURRENT_PLOT_SCOREBOARD,
		VISIBLE_PEST,
		CLEAN_CHAT,
		NO_PESTS_CHAT,
		GARDEN_SCOREBOARD
	}

	/**
	 * A whole-widget snapshot is authoritative for the current grid. The listed plots are infested;
	 * omitted plots are clear only because this method requires a complete, valid observation.
	 */
	public boolean observePestsWidgetSnapshot(Collection<Integer> infestedPlotIds, long observedAtMillis) {
		if (infestedPlotIds == null) return false;
		Set<Integer> infested = new HashSet<>();
		for (Integer id : infestedPlotIds) {
			if (id == null || !GardenPlotGrid.isValidId(id)) return false;
			infested.add(id);
		}
		for (GardenPlotGrid.Plot plot : GardenPlotGrid.plots()) {
			if (infested.contains(plot.id())) {
				put(plot.id(), Status.INFESTED, null, observedAtMillis, Source.PESTS_WIDGET);
			} else {
				put(plot.id(), Status.CLEAR, 0, observedAtMillis, Source.PESTS_WIDGET);
			}
		}
		return true;
	}

	/** Applies exact pest counts for the plots actually represented in a visible Configure Plots menu. */
	public boolean observePlotMenuCounts(Map<Integer, Integer> pestCounts, long observedAtMillis) {
		return observeCounts(pestCounts, observedAtMillis, Source.PLOT_MENU);
	}

	/**
	 * Re-stamps existing widget knowledge when the widget is still visible and still says the same
	 * thing.
	 *
	 * <p>The tab widget only reaches the client when it changes, so a garden whose pest situation is
	 * steady would otherwise age out of {@link #snapshot} while the player is looking straight at a
	 * correct widget. Only evidence already attributed to the widget is touched: a sighting or a menu
	 * read keeps its own timestamp and can still expire on its own schedule.
	 */
	public void refreshWidgetObservation(long observedAtMillis) {
		for (Map.Entry<Integer, Evidence> entry : new ArrayList<>(evidenceByPlot.entrySet())) {
			Evidence evidence = entry.getValue();
			if (evidence.source() != Source.PESTS_WIDGET) continue;
			if (evidence.observedAtMillis() == observedAtMillis) continue;
			evidenceByPlot.put(entry.getKey(), new Evidence(evidence.status(), evidence.pestCount(),
				observedAtMillis, evidence.source()));
		}
	}

	public boolean observeCurrentPlotScoreboardCount(int plotId, int pestCount, long observedAtMillis) {
		if (!validCount(plotId, pestCount)) return false;
		put(plotId, pestCount == 0 ? Status.CLEAR : Status.INFESTED, pestCount,
			observedAtMillis, Source.CURRENT_PLOT_SCOREBOARD);
		return true;
	}

	/** Marks a plot infested when an actual pest entity is visible there, without inventing a count. */
	public boolean observeVisiblePest(int plotId, long observedAtMillis) {
		if (!GardenPlotGrid.isValidId(plotId)) return false;
		put(plotId, Status.INFESTED, null, observedAtMillis, Source.VISIBLE_PEST);
		return true;
	}

	/** A visible whole-Garden zero is decisive; a positive total invalidates older blanket-clear knowledge. */
	public boolean observeGardenPestTotal(int totalPests, long observedAtMillis) {
		if (totalPests < 0) return false;
		if (totalPests == 0) {
			// A zero total does clear every plot, but it must not erase an infestation the client
			// watched appear. Only whole-Garden knowledge is superseded here: a direct sighting is a
			// different kind of fact and is never overwritten by a blanket clear.
			for (GardenPlotGrid.Plot plot : GardenPlotGrid.plots()) {
				putWholeGardenClear(plot.id(), observedAtMillis, Source.GARDEN_SCOREBOARD);
			}
			return true;
		}

		// A total above zero proves the Garden is not empty, so any clear that was only ever a
		// blanket statement - or a count of zero - cannot still be trusted. Evidence at the same
		// instant counts as older here: the total was read at that moment.
		for (Map.Entry<Integer, Evidence> entry : new ArrayList<>(evidenceByPlot.entrySet())) {
			Evidence existing = entry.getValue();
			if (existing.status() == Status.CLEAR && observedAtMillis >= existing.observedAtMillis()) {
				evidenceByPlot.put(entry.getKey(), new Evidence(Status.UNKNOWN, null, observedAtMillis,
					Source.GARDEN_SCOREBOARD));
			}
		}
		return true;
	}

	/** Parses only the two explicit server confirmations that carry plot-clean state. */
	public boolean observeChatMessage(String rawMessage, long observedAtMillis) {
		if (rawMessage == null || rawMessage.isBlank()) return false;
		String message = ChatText.stripForMatch(rawMessage);
		Matcher clean = CLEAN_PLOT.matcher(message);
		if (clean.matches()) {
			try {
				int plotId = Integer.parseInt(clean.group(1));
				if (!GardenPlotGrid.isValidId(plotId)) return false;
				put(plotId, Status.CLEAR, 0, observedAtMillis, Source.CLEAN_CHAT);
				return true;
			} catch (NumberFormatException ignored) {
				return false;
			}
		}
		if (NO_PESTS.matcher(message).matches()) {
			for (GardenPlotGrid.Plot plot : GardenPlotGrid.plots()) {
				put(plot.id(), Status.CLEAR, 0, observedAtMillis, Source.NO_PESTS_CHAT);
			}
			return true;
		}
		return false;
	}

	/** Returns all 25 plots in stable grid order; expired data becomes UNKNOWN, never CLEAR. */
	public List<PlotStatus> snapshot(long nowMillis, long maxAgeMillis) {
		long maxAge = Math.max(0L, maxAgeMillis);
		List<PlotStatus> snapshot = new ArrayList<>(GardenPlotGrid.GRID_SIZE * GardenPlotGrid.GRID_SIZE);
		for (GardenPlotGrid.Plot plot : GardenPlotGrid.plots()) {
			Evidence evidence = evidenceByPlot.get(plot.id());
			if (evidence == null) {
				snapshot.add(new PlotStatus(plot.id(), Status.UNKNOWN, null, 0L, false, Source.NONE));
				continue;
			}
			long age = nowMillis - evidence.observedAtMillis();
			if (age < 0L || age > maxAge) {
				snapshot.add(new PlotStatus(plot.id(), Status.UNKNOWN, null,
					evidence.observedAtMillis(), true, evidence.source()));
			} else {
				snapshot.add(new PlotStatus(plot.id(), evidence.status(), evidence.pestCount(),
					evidence.observedAtMillis(), false, evidence.source()));
			}
		}
		return List.copyOf(snapshot);
	}

	public void reset() {
		evidenceByPlot.clear();
	}

	private boolean observeCounts(Map<Integer, Integer> counts, long observedAtMillis, Source source) {
		if (counts == null || counts.isEmpty()) return false;
		for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
			if (entry.getKey() == null || entry.getValue() == null || !validCount(entry.getKey(), entry.getValue())) {
				return false;
			}
		}
		for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
			int count = entry.getValue();
			put(entry.getKey(), count == 0 ? Status.CLEAR : Status.INFESTED, count, observedAtMillis, source);
		}
		return true;
	}

	private static boolean validCount(int plotId, int count) {
		return GardenPlotGrid.isValidId(plotId) && count >= 0;
	}

	private void put(int plotId, Status status, Integer pestCount, long observedAtMillis, Source source) {
		Evidence previous = evidenceByPlot.get(plotId);
		if (previous != null && observedAtMillis < previous.observedAtMillis()) return;
		evidenceByPlot.put(plotId, new Evidence(status, pestCount, observedAtMillis, source));
	}

	/**
	 * A whole-Garden zero, which is the one observation that clears plots it never named.
	 *
	 * <p>A count of zero proves the Garden holds no pests at all, so it supersedes anything older -
	 * including a plot a sighting had marked infested. What it must not do is override something
	 * observed later, which is the BUG-005 shape of failure: a plot that is infested right now must
	 * not read as clean because a stale whole-Garden number said so. A same-instant tie goes to the
	 * direct observation, since the two were read from the same frame.
	 */
	private void putWholeGardenClear(int plotId, long observedAtMillis, Source source) {
		Evidence previous = evidenceByPlot.get(plotId);
		if (previous != null && observedAtMillis <= previous.observedAtMillis()) return;
		evidenceByPlot.put(plotId, new Evidence(Status.CLEAR, 0, observedAtMillis, source));
	}

	private record Evidence(Status status, Integer pestCount, long observedAtMillis, Source source) { }

	public record PlotStatus(int plotId, Status status, Integer pestCount, long observedAtMillis,
		boolean stale, Source source) { }
}
