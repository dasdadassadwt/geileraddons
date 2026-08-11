package geiler.addons.client.location;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.Locale;

/**
 * Which SkyBlock area the player is standing in, read out of the scoreboard sidebar.
 *
 * <p>Hypixel marks the area line with a glyph rather than a fixed position, and the sidebar is
 * reordered constantly (piece count, timers and event lines come and go), so the line is found by
 * its marker wherever it happens to sit rather than by index.
 *
 * <p>Kept deliberately cheap: the sidebar is only re-read once a second, and everything in between
 * answers from the cached string. Modules ask via {@link #isIn} rather than parsing anything
 * themselves, so any future module can gate on an area for free.
 */
public final class SkyBlockLocation {
	/**
	 * The glyph Hypixel puts in front of the area name. U+23E3 is the normal SkyBlock marker;
	 * U+0444 is the one the Rift uses instead.
	 */
	private static final char[] AREA_MARKERS = {'⏣', 'ф'};

	/** One second. The area only changes on a warp or a walk across a border, so this is plenty. */
	private static final int REFRESH_INTERVAL_TICKS = 20;

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
			String raw = PlayerTeam.formatNameForTeam(team, entry.ownerName()).getString();
			if (!hasAreaMarker(raw)) continue;
			String cleaned = clean(raw);
			if (!cleaned.isEmpty()) return cleaned;
		}
		return null;
	}

	private static boolean hasAreaMarker(String raw) {
		for (char marker : AREA_MARKERS) {
			if (raw.indexOf(marker) >= 0) return true;
		}
		return false;
	}

	/**
	 * Reduces a sidebar line to the text a player actually sees. Hypixel pads lines with invisible
	 * characters to keep score holders unique, and the area marker itself is decoration - keeping
	 * only printable ASCII drops all of it, the marker included, without a list of exceptions.
	 */
	private static String clean(String raw) {
		String stripped = ChatFormatting.stripFormatting(raw);
		if (stripped == null) return "";
		StringBuilder out = new StringBuilder(stripped.length());
		for (int i = 0; i < stripped.length(); i++) {
			char c = stripped.charAt(i);
			if (c >= ' ' && c <= '~') out.append(c);
		}
		return out.toString().trim();
	}
}
