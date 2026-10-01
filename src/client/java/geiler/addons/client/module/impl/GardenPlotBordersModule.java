package geiler.addons.client.module.impl;

import geiler.addons.client.config.ModConfig;
import geiler.addons.client.farming.GardenPlotGrid;
import geiler.addons.client.farming.GardenPlotState;
import geiler.addons.client.farming.GardenPlotState.PlotStatus;
import geiler.addons.client.farming.PestDetectionCache;
import geiler.addons.client.farming.PestDetector;
import geiler.addons.client.farming.PestWidgetReader;
import geiler.addons.client.entity.ClientEntitySnapshot;
import geiler.addons.client.location.HypixelModApi;
import geiler.addons.client.location.Island;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.ModuleAction;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.render.GardenPlotBorderRenderer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Draws status-aware Garden plot borders from client-visible pest observations. */
public final class GardenPlotBordersModule extends Module {
	public static final GardenPlotBordersModule INSTANCE = new GardenPlotBordersModule();

	private static final long MILLIS_PER_SECOND = 1_000L;
	private static final long NANOS_PER_MILLISECOND = 1_000_000L;
	/** The visible-pest fallback is a proximity scan, so it is cheap but the slowest to update. */
	private static final int ENTITY_SCAN_INTERVAL_TICKS = 20;
	/**
	 * How often the tab list is re-read. The widget is the module's primary source, and it is polled
	 * rather than event-driven because the tab list only reaches the client when it changes - polling
	 * is also what keeps a steady garden's evidence from ageing out.
	 */
	private static final int WIDGET_READ_INTERVAL_TICKS = 10;
	/** Roughly two seconds of unconfirmed context before the session is treated as over. */
	private static final int CONTEXT_GRACE_TICKS = 40;
	/** How long the island-detection notice stays quiet before it may say the same thing again. */
	private static final int ISLAND_NOTICE_COOLDOWN_TICKS = 1_200;
	/** The config row's own name, so the chat line points at something the player can find. */
	private static final String ISLAND_API_SETTING = "Island Detection API";
	private final BooleanSetting showClear;
	private final BooleanSetting showUnknown;
	private final BooleanSetting showLabels;
	private final BooleanSetting depthCheck;
	private final NumberSetting lineWidth;
	private final NumberSetting borderY;
	private final NumberSetting wallHeight;
	private final NumberSetting labelScale;
	private final NumberSetting staleAfterSeconds;
	private final ColorSetting infestedColor;
	private final ColorSetting clearColor;
	private final ColorSetting unknownColor;
	private final BooleanSetting fillEnabled;
	private final ColorSetting fillColor;
	/**
	 * Whether the border floor has been decided for this session.
	 *
	 * <p>Session state, not a user choice: it exists only to stop the automatic capture running again
	 * after the floor has been set. It used to be a hidden {@link BooleanSetting}, which meant it was
	 * written to the config like any preference - a copied or reinstalled config could come back with
	 * the flag set and the capture never ran, leaving the boxes at whatever height the old file had.
	 */
	private boolean heightCapturedThisSession;

	private final GardenPlotState state = new GardenPlotState();
	private final PestWidgetReader widgetReader = new PestWidgetReader();
	/** The tick hook publishes immutable status snapshots for render hooks. */
	private volatile List<PlotStatus> renderSnapshot = List.of();
	private ClientLevel lastLevel;
	private boolean wasActive;
	private int ticksSinceEntityScan;
	private int ticksSinceWidgetRead;
	/**
	 * Consecutive ticks the Garden context could not be confirmed. A single tick without island data
	 * is ordinary - the location packet is asynchronous and a config toggle resets it - so the session
	 * is only torn down once the player has really stopped being in the Garden.
	 */
	private int ticksWithoutContext;
	/** Ticks left before the island-detection notice may be repeated; zero means it may fire. */
	private int islandNoticeCooldown;

	private GardenPlotBordersModule() {
		this(new Settings());
	}

	private GardenPlotBordersModule(Settings s) {
		super("Infested plot", "Draws infested, clear, and unknown pest status around Garden plots.",
			Category.FARMING, s.showClear, s.showUnknown, s.showLabels, s.depthCheck, s.lineWidth,
			s.borderY, s.wallHeight, s.captureHeight, s.labelScale, s.staleAfterSeconds,
			s.infestedColor, s.clearColor, s.unknownColor, s.fillEnabled, s.fillColor);
		showClear = s.showClear;
		showUnknown = s.showUnknown;
		showLabels = s.showLabels;
		depthCheck = s.depthCheck;
		lineWidth = s.lineWidth;
		borderY = s.borderY;
		wallHeight = s.wallHeight;
		labelScale = s.labelScale;
		staleAfterSeconds = s.staleAfterSeconds;
		infestedColor = s.infestedColor;
		clearColor = s.clearColor;
		unknownColor = s.unknownColor;
		fillEnabled = s.fillEnabled;
		fillColor = s.fillColor;

		group(
			new SettingGroup("Display", s.showClear, s.showUnknown, s.showLabels, s.depthCheck),
			new SettingGroup("Border Style", s.lineWidth, s.wallHeight, s.borderY, s.captureHeight,
				s.staleAfterSeconds),
			new SettingGroup("Labels", s.labelScale),
			new SettingGroup("Status Colours", s.infestedColor, s.clearColor, s.unknownColor),
			new SettingGroup("Fill", s.fillEnabled, s.fillColor)
		);
	}

	private static final class Settings {
		final BooleanSetting showClear = new BooleanSetting("Show Clear Plots", false);
		final BooleanSetting showUnknown = new BooleanSetting("Show Unknown Plots", false);
		final BooleanSetting showLabels = new BooleanSetting("Show Labels", true);
		final BooleanSetting depthCheck = new BooleanSetting("Depth Check", true);
		final NumberSetting lineWidth = new NumberSetting("Border Width", 0.5f, 5.0f, 4.410061f);
		final NumberSetting wallHeight = new NumberSetting("Wall Height", 0.5f, 16.0f, 10.943598f);
		/**
		 * Fixed world height of the border's floor. Every plot box is drawn at this Y, so the
		 * borders stay put while the player walks and jumps instead of riding their eye level.
		 */
		final NumberSetting borderY = new NumberSetting("Border Y", 0, 320, 72, true);
		final ModuleAction captureHeight = new ModuleAction("Capture Current Y",
			"Store your current block height as the border's fixed world height.",
			() -> INSTANCE.captureHeight());
		final NumberSetting labelScale = new NumberSetting("Label Scale", 0.55f, 2.0f, 1.4208841f);
		final NumberSetting staleAfterSeconds = new NumberSetting("Data Max Age", 15, 600, 120, true);
		final ColorSetting infestedColor = new ColorSetting("Infested Colour", 255, 94, 80, 255);
		final ColorSetting clearColor = new ColorSetting("Clear Colour", 65, 218, 167, 205);
		final ColorSetting unknownColor = new ColorSetting("Unknown Colour", 249, 183, 75, 205);
		final BooleanSetting fillEnabled = new BooleanSetting("Fill Plots", false);
		final ColorSetting fillColor = new ColorSetting("Fill Colour", 255, 94, 80, 51);
	}

	@Override
	public boolean isActive() {
		Minecraft mc = Minecraft.getInstance();
		return isEnabled() && mc.level != null && mc.player != null && HypixelModApi.onGarden();
	}

	@Override
	public String inactiveReason() {
		if (!isEnabled() || isActive()) return null;
		if (Minecraft.getInstance().level == null || Minecraft.getInstance().player == null) {
			return "Waiting for a loaded Garden world";
		}
		return HypixelModApi.reasonNotOn(Island.GARDEN);
	}

	@Override
	protected void onDisable() {
		runOnClientThread(this::resetSession);
	}

	/** Client-tick hook: detects world/session changes, reads the widget, and publishes a snapshot. */
	public void tick() {
		Minecraft mc = Minecraft.getInstance();
		if (!mc.isSameThread()) {
			mc.execute(this::tick);
			return;
		}
		// Before the context gate: when island detection is off the gate can never pass, and the
		// notice is the only thing that explains why the module is doing nothing.
		notifyIfIslandDetectionIsOff(mc);
		if (!ensureGardenContext()) return;
		captureHeightIfNeeded(mc);
		if (++ticksSinceWidgetRead >= WIDGET_READ_INTERVAL_TICKS) {
			ticksSinceWidgetRead = 0;
			observePestsWidget(mc);
		}
		if (++ticksSinceEntityScan >= ENTITY_SCAN_INTERVAL_TICKS) {
			ticksSinceEntityScan = 0;
			observeNearbyPests(mc);
		}
		publishSnapshot(nowMillis());
	}

	/**
	 * Takes the player's height once, the first time they are standing somewhere usable in the Garden.
	 *
	 * <p>A fresh install has no idea where the plot floor is, and that first tick is the only moment
	 * the mod can find out without scanning chunks. It is written to the setting and then left alone:
	 * the border is a landmark, so it must not follow the player afterwards.
	 *
	 * <p>The guards matter more than the capture. Taking the height on the first Garden tick meant
	 * enabling the module mid-fall, mid-jump, on the barn roof or on a visitor platform latched that
	 * height for the life of the profile, with no way to tell it had happened - the only repair was
	 * for the player to find **Capture Current Y**. So the height is only taken from a player standing
	 * on something, inside the Garden's own build bounds; otherwise the capture simply waits for a
	 * tick that qualifies.
	 */
	private void captureHeightIfNeeded(Minecraft mc) {
		if (heightCapturedThisSession || mc.player == null) return;
		if (!canCaptureHeight(HypixelModApi.onGarden(), mc.player.onGround(), mc.player.getY(),
			heightCapturedThisSession)) return;
		heightCapturedThisSession = true;
		borderY.setValue((float) Math.floor(mc.player.getY()));
		ModConfig.markDirty();
	}

	/** Whether a height seen right now is a sound border floor rather than a fall or a jump. */
	static boolean canCaptureHeight(boolean gardenContext, boolean onGround, double y, boolean alreadyCaptured) {
		if (!gardenContext || alreadyCaptured || !onGround || !Double.isFinite(y)) return false;
		return y >= GardenPlotGrid.MIN_BUILD_Y && y < GardenPlotGrid.MAX_BUILD_Y;
	}

	/** Retains the original pure height-boundary check for existing offline coverage. */
	static boolean canCaptureHeight(boolean onGround, double y, boolean alreadyCaptured) {
		return canCaptureHeight(true, onGround, y, alreadyCaptured);
	}

	/** Stores the player's current block height as the border's fixed world Y. */
	public void captureHeight() {
		Minecraft mc = Minecraft.getInstance();
		if (!mc.isSameThread()) {
			mc.execute(this::captureHeight);
			return;
		}
		if (mc.player == null || mc.level == null || !HypixelModApi.onGarden()) return;
		heightCapturedThisSession = true;
		borderY.setValue((float) Math.floor(mc.player.getY()));
		ModConfig.markDirty();
	}

	/**
	 * Fallback source: a pest whose model the client can actually see.
	 *
	 * <p>Restricted to the player's own plot. The widget already names every infested plot in the
	 * Garden, so letting this scan mark plots 128 blocks away added nothing except a second, weaker
	 * opinion about plots the widget knows better.
	 */
	private void observeNearbyPests(Minecraft mc) {
		if (mc.level == null || mc.player == null) return;
		Optional<GardenPlotGrid.Plot> current =
			GardenPlotGrid.plotAt(mc.player.getX(), mc.player.getY(), mc.player.getZ());
		if (current.isEmpty()) return;
		int plotId = current.get().id();
		for (Entity candidate : ClientEntitySnapshot.nearby(mc.level, mc.player, 128.0)) {
			if (!(candidate instanceof ArmorStand stand) || !stand.isAlive()) continue;
			// Place the marker before classifying it: running the whole 128-block scan through the
			// texture lookup would pay for every pest in the neighbourhood and use none of them.
			int markerPlot = GardenPlotGrid.plotAt(stand.getX(), stand.getY(), stand.getZ())
				.map(GardenPlotGrid.Plot::id).orElse(-1);
			if (markerPlot != plotId) continue;
			if (!PestDetectionCache.SHARED.get(stand.getUUID(), stand.getItemBySlot(EquipmentSlot.HEAD),
				() -> PestDetector.detect(stand)).isPresent()) continue;
			state.observeVisiblePest(plotId, nowMillis());
			return;
		}
	}

	/**
	 * Primary source: the whole Garden, as the server describes it in the tab-list Pests widget.
	 *
	 * <p>An unreadable widget is left alone on purpose. The widget only reports when it changes, and
	 * "I could not read it this tick" is not evidence that the Garden is clean - treating it as such
	 * would clear every border the moment the tab list flickered.
	 */
	private void observePestsWidget(Minecraft mc) {
		if (mc.level == null || mc.player == null) return;
		Optional<Set<Integer>> found = widgetReader.read();
		if (found.isEmpty()) return;
		Set<Integer> infested = found.get();
		long now = nowMillis();
		if (!state.observePestsWidgetSnapshot(infested, now)) return;
		// Re-stamped on every read, not just on a change: a widget that keeps saying the same thing is
		// still the current truth, and its evidence must not expire underneath the player.
		state.refreshWidgetObservation(now);
		// Published on every successful read even when the widget said the same thing: this is also
		// where evidence in *other* sources ages out, and that change has to reach the render snapshot.
		publishSnapshot(now);
	}

	/** Raw inbound chat hook; only explicit plot-clean and no-pests server messages change state. */
	public void onChatMessage(String rawMessage) {
		if (rawMessage == null) return;
		runOnClientThread(() -> {
			if (!ensureGardenContext()) return;
			state.observeChatMessage(rawMessage, nowMillis());
			publishSnapshot(nowMillis());
		});
	}

	/** Feed a complete, currently visible Pests-widget list (not a partial/delta update). */
	public void onPestsWidgetSnapshot(Collection<Integer> infestedPlotIds) {
		Collection<Integer> copy = infestedPlotIds == null ? null : new ArrayList<>(infestedPlotIds);
		runOnClientThread(() -> {
			if (!ensureGardenContext()) return;
			if (state.observePestsWidgetSnapshot(copy, nowMillis())) publishSnapshot(nowMillis());
		});
	}

	/** Feed plot-id to exact-pest-count values read from visible Configure Plots inventory lore. */
	public void onPlotMenuCounts(Map<Integer, Integer> pestCounts) {
		Map<Integer, Integer> copy = pestCounts == null ? null : new HashMap<>(pestCounts);
		runOnClientThread(() -> {
			if (!ensureGardenContext()) return;
			if (state.observePlotMenuCounts(copy, nowMillis())) publishSnapshot(nowMillis());
		});
	}

	/** Feed the current plot's exact pest count from a visible Garden scoreboard line. */
	public void onCurrentPlotScoreboardCount(int pestCount) {
		runOnClientThread(() -> {
			if (!ensureGardenContext() || pestCount < 0) return;
			LocalPlayer player = Minecraft.getInstance().player;
			GardenPlotGrid.plotAt(player.getX(), player.getY(), player.getZ()).ifPresent(plot -> {
				if (state.observeCurrentPlotScoreboardCount(plot.id(), pestCount, nowMillis())) {
					publishSnapshot(nowMillis());
				}
			});
		});
	}

	/** Feed the visible Garden-wide scoreboard total; a positive total cannot leave blanket-clear data trusted. */
	public void onGardenPestTotal(int totalPests) {
		runOnClientThread(() -> {
			if (!ensureGardenContext()) return;
			if (state.observeGardenPestTotal(totalPests, nowMillis())) publishSnapshot(nowMillis());
		});
	}

	/** Optional hook for a client-side detector that has confirmed a pest entity in the player's plot. */
	public void onVisiblePestInCurrentPlot() {
		runOnClientThread(() -> {
			if (!ensureGardenContext()) return;
			LocalPlayer player = Minecraft.getInstance().player;
			GardenPlotGrid.plotAt(player.getX(), player.getY(), player.getZ()).ifPresent(plot -> {
				if (state.observeVisiblePest(plot.id(), nowMillis())) publishSnapshot(nowMillis());
			});
		});
	}

	/** Optional explicit world-change hook; the tick hook also detects level identity changes. */
	public void onWorldChanged() {
		runOnClientThread(this::resetSession);
	}

	/** World-render hook. Call from the existing AFTER_TRANSLUCENT_FEATURES listener. */
	public void render(LevelRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (!mc.isSameThread() || !ensureGardenContext()) return;
		GardenPlotBorderRenderer.renderWorld(context, renderSnapshot,
			borderY.value(), wallHeight.value(), lineWidth.value(), depthCheck.value(),
			showClear.value(), showUnknown.value(), fillEnabled.value(), fillColor.argb(),
			infestedColor.argb(), clearColor.argb(), unknownColor.argb());
	}

	/** HUD-label hook. Register beside the other projected world-label renderers. */
	public void renderHud(GuiGraphicsExtractor graphics) {
		Minecraft mc = Minecraft.getInstance();
		if (!mc.isSameThread() || !ensureGardenContext() || !showLabels.value()) return;
		GardenPlotBorderRenderer.renderLabels(graphics, renderSnapshot,
			borderY.value(), wallHeight.value(), labelScale.value(),
			infestedColor.argb(), clearColor.argb(), unknownColor.argb());
	}

	/**
	 * Confirms the module is on, in the Garden, with a loaded world, and owns the session reset.
	 *
	 * <p>A confirmed Garden resets immediately, because a different world's evidence is worthless. A
	 * tick that simply fails to confirm one is tolerated for {@link #CONTEXT_GRACE_TICKS} first: the
	 * location packet is asynchronous, a config toggle clears it, and the render hooks call this too.
	 * Tearing the session down on any single one of those ticks would throw away every plot the
	 * module had learned and force the player to walk near a pest again to get it back.
	 */
	private boolean ensureGardenContext() {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (!isEnabled() || level == null || mc.player == null || !HypixelModApi.onGarden()) {
			if ((wasActive || lastLevel != null) && ++ticksWithoutContext >= CONTEXT_GRACE_TICKS) {
				resetSession();
			}
			return false;
		}
		ticksWithoutContext = 0;
		if (lastLevel != level) {
			state.reset();
			renderSnapshot = List.of();
			heightCapturedThisSession = false;
			lastLevel = level;
		}
		wasActive = true;
		return true;
	}

	/**
	 * Says, in chat, when the module cannot work because island detection is switched off.
	 *
	 * <p>Without this the feature is silently inert: the module is enabled, nothing is drawn, and the
	 * only explanation lives inside the Click GUI card. No fallback detector is added - the mod is not
	 * allowed to guess the island from the scoreboard or the terrain - so the honest answer is to name
	 * the switch. It repeats only after a long pause, so it cannot become chat spam.
	 */
	private void notifyIfIslandDetectionIsOff(Minecraft mc) {
		if (!isEnabled() || mc.level == null || mc.player == null) {
			islandNoticeCooldown = 0;
			return;
		}
		if (ModConfig.hypixelModApi()) {
			islandNoticeCooldown = 0;
			return;
		}
		if (islandNoticeCooldown > 0) {
			islandNoticeCooldown--;
			return;
		}
		islandNoticeCooldown = ISLAND_NOTICE_COOLDOWN_TICKS;
		if (mc.gui == null) return;
		mc.gui.getChat().addClientSystemMessage(Component.literal(
			"[GeilerAddons] Infested plot needs the Island Detection API, which is off under "
				+ "Miscellaneous → General → " + ISLAND_API_SETTING + "."));
	}

	@Override public String configName() { return "Garden Plot Borders"; }

	public ColorSetting fillColor() { return fillColor; }

	private void publishSnapshot(long now) {
		long maxAge = staleAfterSeconds.intValue() * MILLIS_PER_SECOND;
		renderSnapshot = state.snapshot(now, maxAge);
	}

	private void resetSession() {
		state.reset();
		renderSnapshot = List.of();
		heightCapturedThisSession = false;
		lastLevel = null;
		wasActive = false;
		ticksSinceEntityScan = 0;
		ticksSinceWidgetRead = 0;
		ticksWithoutContext = 0;
	}

	private static long nowMillis() {
		return System.nanoTime() / NANOS_PER_MILLISECOND;
	}

	/** Returns only a fresh, known Garden status for client-side macro conditions. */
	public static Optional<PlotStatus> freshPlotStatus(int plotId) {
		if (!INSTANCE.isActive() || !GardenPlotGrid.isValidId(plotId)) return Optional.empty();
		for (PlotStatus status : INSTANCE.renderSnapshot) {
			if (status.plotId() == plotId && !status.stale() && status.status() != GardenPlotState.Status.UNKNOWN) {
				return Optional.of(status);
			}
		}
		return Optional.empty();
	}

	private static void runOnClientThread(Runnable action) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.isSameThread()) action.run();
		else mc.execute(action);
	}
}
