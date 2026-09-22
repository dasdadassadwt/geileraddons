package geiler.addons.client.module.impl;

import geiler.addons.client.config.ModConfig;
import geiler.addons.client.farming.GardenPlotGrid;
import geiler.addons.client.farming.GardenPlotState;
import geiler.addons.client.farming.GardenPlotState.PlotStatus;
import geiler.addons.client.farming.PestDetector;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Draws status-aware Garden plot borders from client-visible pest observations. */
public final class GardenPlotBordersModule extends Module {
	public static final GardenPlotBordersModule INSTANCE = new GardenPlotBordersModule();

	private static final long MILLIS_PER_SECOND = 1_000L;
	private static final long NANOS_PER_MILLISECOND = 1_000_000L;
	private final BooleanSetting showClear;
	private final BooleanSetting showUnknown;
	private final BooleanSetting showLabels;
	private final BooleanSetting depthCheck;
	private final NumberSetting lineWidth;
	private final NumberSetting borderY;
	private final NumberSetting wallHeight;
	/** Hidden row: remembers that the border height was taken from the player once and never again. */
	private final BooleanSetting heightCaptured;
	private final NumberSetting labelScale;
	private final NumberSetting staleAfterSeconds;
	private final ColorSetting infestedColor;
	private final ColorSetting clearColor;
	private final ColorSetting unknownColor;

	private final GardenPlotState state = new GardenPlotState();
	/** The tick hook publishes immutable status snapshots for render hooks. */
	private volatile List<PlotStatus> renderSnapshot = List.of();
	private ClientLevel lastLevel;
	private boolean wasActive;
	private int ticksSinceEntityScan;

	private GardenPlotBordersModule() {
		this(new Settings());
	}

	private GardenPlotBordersModule(Settings s) {
		super("Garden Plot Borders", "Draws clear, infested, and unknown pest status as fixed 3D boxes around Garden plots.",
			Category.FARMING, s.showClear, s.showUnknown, s.showLabels, s.depthCheck, s.lineWidth,
			s.borderY, s.wallHeight, s.heightCaptured, s.captureHeight, s.labelScale, s.staleAfterSeconds,
			s.infestedColor, s.clearColor, s.unknownColor);
		showClear = s.showClear;
		showUnknown = s.showUnknown;
		showLabels = s.showLabels;
		depthCheck = s.depthCheck;
		lineWidth = s.lineWidth;
		borderY = s.borderY;
		wallHeight = s.wallHeight;
		heightCaptured = s.heightCaptured;
		labelScale = s.labelScale;
		staleAfterSeconds = s.staleAfterSeconds;
		infestedColor = s.infestedColor;
		clearColor = s.clearColor;
		unknownColor = s.unknownColor;

		group(
			new SettingGroup("Display", s.showClear, s.showUnknown, s.showLabels, s.depthCheck),
			new SettingGroup("Border Style", s.lineWidth, s.wallHeight, s.borderY, s.captureHeight,
				s.staleAfterSeconds),
			new SettingGroup("Labels", s.labelScale),
			new SettingGroup("Status Colours", s.infestedColor, s.clearColor, s.unknownColor)
		);
	}

	private static final class Settings {
		final BooleanSetting showClear = new BooleanSetting("Show Clear Plots", false);
		final BooleanSetting showUnknown = new BooleanSetting("Show Unknown Plots", false);
		final BooleanSetting showLabels = new BooleanSetting("Show Labels", true);
		final BooleanSetting depthCheck = new BooleanSetting("Depth Check", true);
		final NumberSetting lineWidth = new NumberSetting("Border Width", 0.5f, 5.0f, 2.4f);
		final NumberSetting wallHeight = new NumberSetting("Wall Height", 0.5f, 16.0f, 3.0f);
		/**
		 * Fixed world height of the border's floor. Every plot box is drawn at this Y, so the
		 * borders stay put while the player walks and jumps instead of riding their eye level.
		 */
		final NumberSetting borderY = new NumberSetting("Border Y", 0, 320, 70, true);
		final BooleanSetting heightCaptured = new BooleanSetting("Height Captured", false);
		final ModuleAction captureHeight = new ModuleAction("Capture Current Y",
			"Store your current block height as the border's fixed world height.",
			() -> INSTANCE.captureHeight());
		final NumberSetting labelScale = new NumberSetting("Label Scale", 0.55f, 2.0f, 0.9f);
		final NumberSetting staleAfterSeconds = new NumberSetting("Data Max Age", 15, 600, 120, true);
		final ColorSetting infestedColor = new ColorSetting("Infested Colour", 255, 94, 80, 228);
		final ColorSetting clearColor = new ColorSetting("Clear Colour", 65, 218, 167, 205);
		final ColorSetting unknownColor = new ColorSetting("Unknown Colour", 249, 183, 75, 205);
	}

	/** The hidden capture flag is state, not a choice, so it never renders as a row. */
	@Override
	public boolean isSettingVisible(Setting setting) {
		return setting != heightCaptured;
	}

	@Override
	public boolean isActive() {
		Minecraft mc = Minecraft.getInstance();
		return isEnabled() && mc.level != null && mc.player != null
			&& HypixelModApi.currentIsland() == Island.GARDEN;
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

	/** Client-tick hook: detects world/session changes, expires old evidence, and publishes an immutable snapshot. */
	public void tick() {
		Minecraft mc = Minecraft.getInstance();
		if (!mc.isSameThread()) {
			mc.execute(this::tick);
			return;
		}
		if (!ensureGardenContext()) return;
		captureHeightIfNeeded(mc);
		if (++ticksSinceEntityScan >= 20) {
			ticksSinceEntityScan = 0;
			observeNearbyPests(mc);
		}
		publishSnapshot(nowMillis());
	}

	/**
	 * Takes the player's height once, the first time they are in the Garden with no stored height.
	 *
	 * <p>A fresh install has no idea where the plot floor is, and that first tick is the only moment
	 * the mod can find out without scanning chunks. It is written to the setting and then left
	 * alone: the border is a landmark, so it must not follow the player afterwards.
	 */
	private void captureHeightIfNeeded(Minecraft mc) {
		if (heightCaptured.value() || mc.player == null) return;
		heightCaptured.setValue(true);
		borderY.setValue((float) Math.floor(mc.player.getY()));
		ModConfig.markDirty();
	}

	/** Stores the player's current block height as the border's fixed world Y. */
	public void captureHeight() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) return;
		heightCaptured.setValue(true);
		borderY.setValue((float) Math.floor(mc.player.getY()));
		ModConfig.markDirty();
	}

	private void observeNearbyPests(Minecraft mc) {
		if (mc.level == null || mc.player == null) return;
		long now = nowMillis();
		for (Entity candidate : ClientEntitySnapshot.nearby(mc.level, mc.player, 128.0)) {
			if (!(candidate instanceof ArmorStand stand) || !stand.isAlive()
				|| !stand.hasItemInSlot(EquipmentSlot.HEAD) || PestDetector.detect(stand).isEmpty()) continue;
			GardenPlotGrid.plotAt(stand.getX(), stand.getY(), stand.getZ())
				.ifPresent(plot -> state.observeVisiblePest(plot.id(), now));
		}
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
			showClear.value(), showUnknown.value(), infestedColor.argb(), clearColor.argb(), unknownColor.argb());
	}

	/** HUD-label hook. Register beside the other projected world-label renderers. */
	public void renderHud(GuiGraphicsExtractor graphics) {
		Minecraft mc = Minecraft.getInstance();
		if (!mc.isSameThread() || !ensureGardenContext() || !showLabels.value()) return;
		GardenPlotBorderRenderer.renderLabels(graphics, renderSnapshot,
			borderY.value(), wallHeight.value(), labelScale.value(), showClear.value(), showUnknown.value(),
			infestedColor.argb(), clearColor.argb(), unknownColor.argb());
	}

	private boolean ensureGardenContext() {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (!isEnabled() || level == null || mc.player == null
			|| HypixelModApi.currentIsland() != Island.GARDEN) {
			if (wasActive || lastLevel != null) resetSession();
			return false;
		}
		if (lastLevel != level) {
			state.reset();
			renderSnapshot = List.of();
			lastLevel = level;
		}
		wasActive = true;
		return true;
	}

	private void publishSnapshot(long now) {
		long maxAge = staleAfterSeconds.intValue() * MILLIS_PER_SECOND;
		renderSnapshot = state.snapshot(now, maxAge);
	}

	private void resetSession() {
		state.reset();
		renderSnapshot = List.of();
		lastLevel = null;
		wasActive = false;
		ticksSinceEntityScan = 0;
	}

	private static long nowMillis() {
		return System.nanoTime() / NANOS_PER_MILLISECOND;
	}

	private static void runOnClientThread(Runnable action) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.isSameThread()) action.run();
		else mc.execute(action);
	}
}
