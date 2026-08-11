package geiler.addons.client.location;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Which SkyBlock area the player is standing in, read out of the scoreboard sidebar.
 *
 * <p>The detection mirrors what SkyHanni does (see {@code HypixelData.skyblockAreaPattern} in
 * hannibal002/SkyHanni): the area line is found by shape, not by which glyph it uses. Every area
 * has its own icon - a marker allowlist can only ever cover the icons someone happened to test
 * with, and missed exactly this way once already. The shape a real area line has is a run of one
 * or more spaces, a legacy colour code, exactly one character (the icon, whatever it is), a
 * literal space, another legacy colour code, then the area name - and nothing else on the
 * scoreboard is shaped like that.
 *
 * <p>Reading that shape needs the line's legacy formatting codes, which {@code Component#getString}
 * strips before this ever sees it. {@link #toLegacyText} rebuilds them from the component's style
 * runs instead of reading them off the wire, since Fabric's chat components never carry raw §
 * codes to begin with.
 *
 * <p>Kept deliberately cheap: the sidebar is only re-read once a second, and everything in between
 * answers from the cached string. Modules ask via {@link #isIn} rather than parsing anything
 * themselves, so any future module can gate on an area for free.
 */
public final class SkyBlockLocation {
	/** One second. The area only changes on a warp or a walk across a border, so this is plenty. */
	private static final int REFRESH_INTERVAL_TICKS = 20;

	/** RGB value of each legacy colour, keyed back to the code that produces it. */
	private static final Map<Integer, Character> LEGACY_COLORS_BY_RGB = buildLegacyColorTable();

	private static String area;
	/** Folded once per refresh: isIn() is called from render, so it must not allocate per frame. */
	private static String areaLower;
	private static int ticksSinceRefresh = REFRESH_INTERVAL_TICKS;

	private SkyBlockLocation() {
	}

	/** Call once per client tick. Does real work only every {@link #REFRESH_INTERVAL_TICKS}. */
	public static void tick() {
		if (Minecraft.getInstance().level == null) {
			// Cleared immediately rather than on the next refresh, so an area cannot survive the
			// gap between leaving one server and joining the next.
			set(null);
			ticksSinceRefresh = REFRESH_INTERVAL_TICKS;
			return;
		}
		if (++ticksSinceRefresh < REFRESH_INTERVAL_TICKS) return;
		ticksSinceRefresh = 0;
		set(readArea());
	}

	private static void set(String value) {
		area = value;
		areaLower = value == null ? null : value.toLowerCase(Locale.ROOT);
	}

	/** The current area name, or null when there is no sidebar to read (not on SkyBlock). */
	public static String area() {
		return area;
	}

	/**
	 * Whether the current area name contains any of the given fragments, ignoring case.
	 *
	 * <p>Substring rather than equality on purpose: Hypixel decorates area names in places
	 * ("The Catacombs (F7)"), and neighbouring sub-areas usually share a stem, so matching
	 * "torrhus" covers the whole region without needing every variant spelled out.
	 */
	public static boolean isIn(String... fragments) {
		String lower = areaLower;
		if (lower == null) return false;
		for (String fragment : fragments) {
			if (lower.contains(fragment.toLowerCase(Locale.ROOT))) return true;
		}
		return false;
	}

	private static String readArea() {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null) return null;

		Scoreboard scoreboard = level.getScoreboard();
		Objective objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
		if (objective == null) return null;

		for (PlayerScoreEntry entry : scoreboard.listPlayerScores(objective)) {
			if (entry.isHidden()) continue;
			// The visible text lives in the team prefix/suffix wrapped around the score holder,
			// not in the holder name itself, which is why this goes through the team formatting.
			PlayerTeam team = scoreboard.getPlayersTeam(entry.owner());
			Component formatted = PlayerTeam.formatNameForTeam(team, entry.ownerName());
			String area = parseAreaLine(toLegacyText(formatted));
			if (area != null) return area;
		}
		return null;
	}

	/**
	 * Matches the shape of a SkyHanni-style area line and returns the area name, or null if this
	 * line isn't one. Written by hand rather than as a regex: the icon is one Unicode code point,
	 * which can be two {@code char}s wide, and Java's {@code .} only ever matches one.
	 */
	private static String parseAreaLine(String legacy) {
		int i = 0;
		int len = legacy.length();
		while (i < len && legacy.charAt(i) == ' ') i++;

		if (!hasCodeAt(legacy, i)) return null;
		char firstCode = legacy.charAt(i + 1);
		// SkyHanni restricts the icon's own colour to a digit code specifically - every area line
		// observed uses one, and requiring it is what keeps this from matching arbitrary scoreboard
		// rows that also happen to have "symbol, space, text" shaped content.
		if (firstCode < '0' || firstCode > '9') return null;
		i += 2;

		if (i >= len) return null;
		int iconPoint = legacy.codePointAt(i);
		i += Character.charCount(iconPoint);

		if (i >= len || legacy.charAt(i) != ' ') return null;
		i++;

		if (!hasCodeAt(legacy, i)) return null;
		i += 2;

		String area = printable(legacy.substring(i));
		return area.isEmpty() ? null : area;
	}

	private static boolean hasCodeAt(String text, int index) {
		return index + 1 < text.length() && text.charAt(index) == ChatFormatting.PREFIX_CODE;
	}

	/**
	 * Rebuilds legacy-style formatting codes from a component's style runs, since Fabric's chat
	 * components carry {@link TextColor} rather than raw § codes. Only colour is reproduced - the
	 * shape this is matched against never depends on bold, italic or the like.
	 */
	private static String toLegacyText(Component component) {
		StringBuilder out = new StringBuilder();
		component.visit((style, text) -> {
			Character code = colorCode(style.getColor());
			if (code != null) {
				out.append(ChatFormatting.PREFIX_CODE).append(code.charValue());
			}
			out.append(text);
			return java.util.Optional.empty();
		}, Style.EMPTY);
		return out.toString();
	}

	private static Character colorCode(TextColor color) {
		return color == null ? null : LEGACY_COLORS_BY_RGB.get(color.getValue());
	}

	private static Map<Integer, Character> buildLegacyColorTable() {
		Map<Integer, Character> table = new HashMap<>();
		for (ChatFormatting formatting : ChatFormatting.values()) {
			if (!formatting.isColor()) continue;
			table.put(formatting.getColor(), formatting.getChar());
		}
		return table;
	}

	/**
	 * Reduces the area name to the text a player actually sees. Hypixel pads scoreboard lines with
	 * invisible characters to keep score holders unique; keeping only printable ASCII drops those
	 * without needing to know what any of them are.
	 */
	private static String printable(String text) {
		StringBuilder out = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c >= ' ' && c <= '~') out.append(c);
		}
		return out.toString().trim();
	}
}
