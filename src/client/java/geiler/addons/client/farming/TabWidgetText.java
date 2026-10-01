package geiler.addons.client.farming;

import geiler.addons.client.tree.ChatText;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure parser for the Hypixel tab-list {@code Pests} widget.
 *
 * <p>The widget is the one client-visible source that names every infested plot in the Garden, which
 * is why the plot borders are built on it. Its shape is a {@code Pests:} header, the data rows, and
 * then the next widget's header; the reference implementation reads it the same way. Line ordering
 * is the caller's responsibility, because the order is what the client renders and it cannot be
 * recovered from the text alone.
 *
 * <p>Everything here fails closed. A half-rendered tab list must never be read as "no pests", so a
 * block that is truncated, interleaved with something unrecognised, or missing its {@code Plots:}
 * row is rejected outright rather than partially believed.
 */
public final class TabWidgetText {
	/** The widget's own header, or the start of a line such as {@code Pests: 3}. */
	private static final String PESTS_HEADER = "pests:";
	/** The authoritative row: every infested plot id, or an explicit "there are none" spelling. */
	private static final Pattern PESTS_IN_PLOTS = Pattern.compile("^plots:\\s*(.*)$");
	private static final Pattern NO_PLOTS = Pattern.compile("(?i)^(?:none|n/a|-*)$");
	/** One plot id, as the widget prints them. Bounded so a stray word cannot reach the parser. */
	private static final Pattern PLOT_ID = Pattern.compile("^\\d{1,2}$");

	/**
	 * Every other widget's header, so the end of the {@code Pests} block can be found.
	 *
	 * <p>These delimit the block rather than being read, so one whose wording drifts only ever costs
	 * a rejected parse - never a wrong plot. Lowercase, and matched as a prefix.
	 */
	private static final String[] OTHER_WIDGETS = {
		"players (", "area:", "dungeon:", "server:", "gems:", "fairy souls:", "profile:",
		"sb level:", "bank:", "interest:", "soulflow:", "pet:", "pet training:", "kat:",
		"fire sales:", "election:", "event:", "skills:", "stats:", "guests (", "coop ",
		"minions:", "island closes in:", "north stars:", "collection:", "jacob's contest:",
		"slayer:", "daily quests:", "active effects:", "bestiary:", "essence:", "forges:",
		"timers:", "opened rooms:", "party:", "trapper:", "commissions:", "powders:",
		"crystals:", "unclaimed chests:", "thunder:", "rain:", "broodmother:", "eyes placed:",
		"protector:", "dragon:", "volcano:", "reputation:", "faction quests:", "trophy fish:",
		"good to know:", "shen:", "advertisement:", "composter:", "garden level:", "copper:",
		"sowdust:", "pest traps:", "full traps:", "no bait:", "visitors (", "crop milestones:",
		"frozen corpses:", "scrap:", "event trackers:", "agatha's contest:", "miria's contest:",
		"salts:", "starborn temple:", "pity:", "pickaxe ability:"
	};
	/** Headers that are a word on their own, so a name that merely starts with them cannot match. */
	private static final String[] OTHER_WIDGET_WORDS = { "info", "island", "puzzle", "dungeon stats", "shard traps" };

	private TabWidgetText() {
	}

	/**
	 * Reads one already-ordered tab list.
	 *
	 * @return the infested plot ids, or empty when the widget is absent or was not fully rendered.
	 *     Empty means "unknown" and must never be treated as "clear".
	 */
	public static Optional<Set<Integer>> parse(List<String> lines) {
		if (lines == null || lines.isEmpty()) return Optional.empty();

		List<String> rows = new ArrayList<>(lines.size());
		for (String line : lines) rows.add(row(line));

		int header = -1;
		for (int index = 0; index < rows.size(); index++) {
			if (!rows.get(index).startsWith(PESTS_HEADER)) continue;
			// Two headers cannot both be the widget; prefer rejecting a tab list we do not understand.
			if (header >= 0) return Optional.empty();
			header = index;
		}
		if (header < 0) return Optional.empty();

		Set<Integer> infested = null;
		for (int index = header + 1; index < rows.size(); index++) {
			String text = rows.get(index);
			if (text.isEmpty()) continue;
			if (isOtherWidget(text)) break;

			Matcher plots = PESTS_IN_PLOTS.matcher(text);
			if (plots.matches()) {
				Optional<Set<Integer>> parsed = parsePlotIds(plots.group(1));
				if (parsed.isEmpty()) return Optional.empty();
				infested = parsed.get();
				continue;
			}
			// The widget's own count row, such as "5 pests". It carries no plot id, so it is only
			// recognised here to stop it ending a block that is otherwise valid.
			if (isPestCount(text)) continue;
			// Anything else inside the block means the widget was not fully enumerated, and a
			// partial read could certify a plot as clear that had simply not rendered yet.
			return Optional.empty();
		}
		return infested == null ? Optional.empty() : Optional.of(infested);
	}

	/** Splits a {@code Plots:} value; an explicit "none" is a decisive empty list, not a failure. */
	private static Optional<Set<Integer>> parsePlotIds(String value) {
		String text = value.trim();
		// A value that is missing entirely is a half-rendered row, not a statement that there are no
		// pests: only the widget's own spelling of "none" may certify the whole Garden as clear.
		if (text.isEmpty()) return Optional.empty();
		if (NO_PLOTS.matcher(text).matches()) return Optional.of(new LinkedHashSet<>());
		Set<Integer> ids = new LinkedHashSet<>();
		for (String token : text.split(",")) {
			String candidate = token.trim();
			if (!PLOT_ID.matcher(candidate).matches()) return Optional.empty();
			int id;
			try {
				id = Integer.parseInt(candidate);
			} catch (NumberFormatException ignored) {
				return Optional.empty();
			}
			if (!GardenPlotGrid.isValidId(id)) return Optional.empty();
			ids.add(id);
		}
		return Optional.of(ids);
	}

	/** Whether a row is the widget's pest-count line, such as {@code 5 pests} or {@code 1 pest}. */
	private static boolean isPestCount(String text) {
		int space = text.indexOf(' ');
		if (space <= 0) return false;
		for (int index = 0; index < space; index++) {
			if (!Character.isDigit(text.charAt(index))) return false;
		}
		String noun = text.substring(space + 1).trim();
		return noun.equals("pest") || noun.equals("pests");
	}

	/** Normalises one tab-list line for matching: formatting, hypixel glyphs and padding removed. */
	private static String row(String line) {
		return (line == null ? "" : ChatText.stripForMatch(line)).toLowerCase(Locale.ROOT);
	}

	private static boolean isOtherWidget(String text) {
		for (String word : OTHER_WIDGET_WORDS) {
			if (text.equals(word)) return true;
		}
		for (String header : OTHER_WIDGETS) {
			if (text.startsWith(header)) return true;
		}
		return false;
	}
}
