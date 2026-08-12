package geiler.addons.client.module.impl;

import geiler.addons.client.config.ModConfig;
import geiler.addons.client.gui.GuiTheme;
import geiler.addons.client.hud.HudElement;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.ModuleAction;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.module.TextSetting;
import geiler.addons.client.tree.TreeStats;
import geiler.addons.client.tree.TreeTracker;
import geiler.addons.client.tree.TreeType;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Counts tree gifts from chat and shows the running rate for whichever tree gifted last. */
public final class TreeTrackerModule extends Module implements HudElement {
	public static final TreeTrackerModule INSTANCE = new TreeTrackerModule();

	private static final String HUD_ID = "tree_tracker";
	private static final String HEADER = "Tree Gifts";
	private static final String TOTAL_HEADER = "Tree Gifts (Total)";
	/** Used when there isn't enough elapsed time to divide by yet. */
	private static final String NO_RATE = "-";
	/** Never drawn in the world - the panel is hidden before a first gift - but sizes its box in Move Elements. */
	private static final String IDLE = "No gifts yet";

	private static final int LINE_HEIGHT = 10;
	private static final int PADDING = 4;
	/** Wide enough for the longest line the panel can produce, so the box doesn't resize as it counts. */
	private static final int MIN_WIDTH = 96;

	private static final int DEFAULT_PAUSE_SECONDS = 60;
	private static final int MAX_PAUSE_SECONDS = 86400;

	private final TextSetting pauseTimeout;
	private final BooleanSetting totalMode;
	private final BooleanSetting showBackground;

	private final TreeTracker tracker = new TreeTracker();

	private String[] cachedLines;
	private long cachedSecond = -1;
	private boolean cachedTotals;

	private TreeTrackerModule() {
		this(new Settings());
	}

	private TreeTrackerModule(Settings s) {
		super("Tree Tracker", "Counts Helix, Fig and Mangrove gifts and shows gifts per hour.",
			Category.FORAGING,
			s.pauseTimeout, s.totalMode, s.showBackground, s.resetSession);
		this.pauseTimeout = s.pauseTimeout;
		this.totalMode = s.totalMode;
		this.showBackground = s.showBackground;
		group(
			new SettingGroup("Counting", s.pauseTimeout, s.totalMode, s.resetSession),
			new SettingGroup("Display", s.showBackground)
		);
	}

	/** Just a carrier so the constructor can both build the settings and keep references to them. */
	private static final class Settings {
		final TextSetting pauseTimeout = new TextSetting("Pause Timeout (s)", String.valueOf(DEFAULT_PAUSE_SECONDS), 6);
		final BooleanSetting totalMode = new BooleanSetting("Show Totals", false);
		final BooleanSetting showBackground = new BooleanSetting("Background", true);
		final ModuleAction resetSession = new ModuleAction("Reset Session", TreeTrackerModule::resetSession);
	}

	public TreeTracker tracker() {
		return tracker;
	}

	// ---- counting -----------------------------------------------------------------------

	public void onChatMessage(String message) {
		if (!isEnabled()) return;
		if (tracker.onChatMessage(message, System.currentTimeMillis(), timeoutMillis()) == null) return;
		// Debounced rather than written here: gifts arrive in bursts, and a config write per gift
		// would put file I/O on the client thread in the middle of one.
		ModConfig.markDirty();
	}

	private static void resetSession() {
		TreeTrackerModule module = INSTANCE;
		module.tracker.resetSession(System.currentTimeMillis(), module.timeoutMillis());
		ModConfig.save();
	}

	/** Ends whatever session was restored from disk, crediting it to the totals. */
	public void rollOverLoadedSession() {
		tracker.rollOverLoadedSession();
	}

	private long timeoutMillis() {
		int seconds = pauseTimeout.intValue(DEFAULT_PAUSE_SECONDS);
		// A zero or negative timeout would freeze the clock permanently and read as a broken
		// module rather than as a setting mistake, so treat anything unusable as the default.
		if (seconds <= 0) seconds = DEFAULT_PAUSE_SECONDS;
		return Math.min(seconds, MAX_PAUSE_SECONDS) * 1000L;
	}

	// ---- hud ----------------------------------------------------------------------------

	@Override
	public String id() {
		return HUD_ID;
	}

	@Override
	public String displayName() {
		return "Tree Tracker";
	}

	/**
	 * Shown only while gifts are actually coming in.
	 *
	 * <p>Hides on the same timeout that freezes the clock, so the panel is on screen exactly when
	 * it is counting - there is no state where a visible read-out is quietly stale. Coming back is
	 * a resume rather than a restart: the session was never cleared, only paused.
	 */
	@Override
	public boolean visible() {
		if (!isEnabled()) return false;
		TreeType type = tracker.lastGifted();
		return type != null && !tracker.stats(type).paused(System.currentTimeMillis(), timeoutMillis());
	}

	@Override
	public int width(Font font) {
		int width = MIN_WIDTH;
		for (String line : lines()) {
			width = Math.max(width, font.width(line));
		}
		return width + PADDING * 2;
	}

	@Override
	public int height(Font font) {
		return lines().length * LINE_HEIGHT + PADDING * 2;
	}

	@Override
	public void render(GuiGraphicsExtractor graphics, Font font, int x, int y) {
		String[] lines = lines();
		if (showBackground.value()) {
			GuiTheme.roundedRect(graphics, x, y, width(font), height(font), 3, GuiTheme.PANEL_TOP);
		}
		int textY = y + PADDING;
		for (int i = 0; i < lines.length; i++) {
			// The header is dimmed so the numbers under it are what the eye lands on first.
			int color = i == 0 ? GuiTheme.TEXT_SECONDARY : GuiTheme.TEXT_PRIMARY;
			graphics.text(font, lines[i], x + PADDING, textY, color);
			textY += LINE_HEIGHT;
		}
	}

	/**
	 * The panel's text, rebuilt at most once a second.
	 *
	 * <p>Cached because width, height and render each ask for it, so an uncached build would run
	 * several times a frame to produce the same three strings - nothing in them changes faster
	 * than the seconds in the duration.
	 */
	private String[] lines() {
		long second = System.currentTimeMillis() / 1000;
		boolean totals = totalMode.value();
		if (cachedLines == null || second != cachedSecond || totals != cachedTotals) {
			cachedSecond = second;
			cachedTotals = totals;
			cachedLines = buildLines(totals);
		}
		return cachedLines;
	}

	private String[] buildLines(boolean totals) {
		String header = totals ? TOTAL_HEADER : HEADER;
		TreeType type = tracker.lastGifted();
		if (type == null) {
			return new String[]{header, IDLE};
		}

		long now = System.currentTimeMillis();
		long timeout = timeoutMillis();
		TreeStats stats = tracker.stats(type);
		int count = totals ? stats.totalCount() : stats.sessionCount();
		long millis = totals ? stats.totalMillis(now, timeout) : stats.sessionMillis(now, timeout);
		int rate = TreeStats.ratePerHour(count, millis);

		String name = type.displayName();
		return new String[]{
			header,
			name + "  " + (rate < 0 ? NO_RATE : String.valueOf(rate)) + "/h",
			name + "  " + count + "  " + formatDuration(millis)
		};
	}

	/** mm:ss until it passes an hour, then h:mm:ss. */
	static String formatDuration(long millis) {
		long totalSeconds = Math.max(0, millis) / 1000;
		long hours = totalSeconds / 3600;
		long minutes = (totalSeconds % 3600) / 60;
		long seconds = totalSeconds % 60;
		if (hours > 0) {
			return String.format("%d:%02d:%02d", hours, minutes, seconds);
		}
		return String.format("%02d:%02d", minutes, seconds);
	}
}
