package geiler.addons.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonParseException;
import geiler.addons.GeilerAddons;
import geiler.addons.client.hud.HudManager;
import geiler.addons.client.collections.FolderTree;
import geiler.addons.client.enchanting.AutoExperimentDelayRange;
import geiler.addons.client.location.Island;
import geiler.addons.client.macro.MacroDefinition;
import geiler.addons.client.macro.MacroFunction;
import geiler.addons.client.macro.MacroRunner;
import geiler.addons.client.macro.MacroScript;
import geiler.addons.client.macro.MacroValue;
import geiler.addons.client.macro.MacroTriggerContext;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ChoiceSetting;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.ModuleKeybind;
import geiler.addons.client.module.ModuleManager;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.TextSetting;
import geiler.addons.client.module.impl.MobHighlight;
import geiler.addons.client.module.impl.MobHighlightModule;
import geiler.addons.client.module.impl.DungeonMobEspModule;
import geiler.addons.client.module.impl.DungeonMobEspKeybindMigration;
import geiler.addons.client.module.impl.BlockEspEntry;
import geiler.addons.client.module.impl.BlockEspModule;
import geiler.addons.client.module.impl.AutoExperimentsModule;
import geiler.addons.client.module.impl.GeneralModule;
import geiler.addons.client.module.impl.GardenPlotBordersModule;
import geiler.addons.client.module.impl.InventoryButtonPlacement;
import geiler.addons.client.module.impl.InventoryButtonsModule;
import geiler.addons.client.module.impl.MacrosModule;
import geiler.addons.client.module.impl.SlotIdsModule;
import geiler.addons.client.module.impl.TreeTrackerModule;
import geiler.addons.client.module.impl.VisualModule;
import geiler.addons.client.tree.TreeStats;
import geiler.addons.client.tree.TreeType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import com.mojang.blaze3d.platform.InputConstants;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.Set;

/** Persists module enabled-state, settings and the Tiki coordinate list across restarts. */
public final class ModConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** Root for the settings file and feature-specific data directories. */
	private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("geileraddons");
	private static final Path PATH = DIR.resolve("config.json");
	private static final Path MACRO_DIR = DIR.resolve("macros");
	private static final Path FUNCTION_DIR = DIR.resolve("functions");
	private static final Path MACRO_INDEX_PATH = DIR.resolve("macro-index.json");
	private static final int MAX_MACRO_RECORD_BYTES = 1_048_576;
	private static final int MAX_MACRO_INDEX_BYTES = 65_536;
	/** Where this file lived before it got its own folder; migrated from on first load. */
	private static final Path LEGACY_PATH = FabricLoader.getInstance().getConfigDir().resolve("geileraddons.json");

	private ModConfig() {
	}

	private static final class Data {
		Map<String, Boolean> enabled = new HashMap<>();
		Map<String, int[]> colors = new HashMap<>();
		Map<String, Float> numbers = new HashMap<>();
		Map<String, Boolean> toggles = new HashMap<>();
		Map<String, String> choices = new HashMap<>();
		Map<String, String> texts = new HashMap<>();
		Map<String, KeybindData> keybinds = new HashMap<>();
		/**
		 * Absent means "never saved", which is what seeds the built-in coordinates. An explicitly
		 * empty list is a list the user emptied out and must stay empty.
		 */
		List<int[]> tikiCoords;
		/** "x,y,z" -> block id, e.g. "minecraft:stone". Absent or missing entries just aren't learned yet. */
		Map<String, String> tikiFingerprints;
		/** HUD element id -> {x, y} as a fraction of its travel; see {@link geiler.addons.client.hud.HudManager}. */
		Map<String, float[]> hudPositions;
		/** Tree type name -> {sessionCount, sessionMillis, storedCount, storedMillis}. */
		Map<String, long[]> treeGifts;
		/**
		 * The Mob Highlight list. Its settings can't live in the flat maps above: those are keyed
		 * by setting name, and every highlight would write to the same "Mob Highlight.Match Text".
		 */
		List<MobHighlightData> mobHighlights;
		List<FolderTree.Folder> mobHighlightFolders;
		List<BlockEspData> blockEspEntries;
		List<FolderTree.Folder> blockEspFolders;
		/** Click GUI view state - see {@link ClickGuiState}. */
		String uiCategory;
		String uiOpenModule;
		String uiExpandedColor;
		int uiSettingsScroll;
		int uiCategoryScroll;
		Map<String, Integer> uiGridScrollByCategory = new HashMap<>();
		List<String> uiFavoriteCategories = new ArrayList<>();
		List<String> uiFavoriteModules = new ArrayList<>();
		/** Legacy/mirror of the General module's update row. See {@link #checkForUpdates()}. */
		Boolean checkForUpdates;
		/** Absent means "never saved", which keeps island detection on by default. */
		/** Legacy/mirror of the General module's island-detection row. See {@link #hypixelModApi()}. */
		Boolean hypixelModApi;
		/** Legacy inline workflows, read only for migration to one file per macro. */
		List<MacroData> macros;
		List<MacroFunctionData> macroFunctions;
		/** Snapshot-only payloads; transient fields never enter config.json. */
		transient List<MacroData> macroFiles;
		transient List<MacroFunctionData> functionFiles;
		JsonArray macroVariables;
		List<FolderTree.Folder> macroFolders;
		/** Spatial player-inventory layout stays local and is never embedded in macro transfers. */
		JsonArray inventoryButtons;
	}

	/**
	 * One saved highlight. Boxed where the default isn't the zero value, so a hand-edited file
	 * that omits a field gets the default back rather than false or a clamped zero.
	 */
	private static final class MobHighlightData {
		int id;
		Boolean enabled;
		Boolean matchName;
		String matchText;
		int[] outlineColor;
		int[] fillColor;
		Boolean depthCheck;
		Float scanInterval;
		String displayName;
		/** Island name to whether the highlight is wanted there; legacy files without this map mean all islands. */
		Map<String, Boolean> islands;
		String folderId;
	}

	private static final class BlockEspData {
		int id;
		String blockId;
		String folderId;
		Boolean enabled;
		Boolean box;
		Boolean fill;
		Boolean outline;
		Boolean showLabel;
		Boolean tracer;
		Boolean connectTouching;
		Boolean depthCheck;
		Boolean useCustomRange;
		Integer customRange;
		int[] outlineColor;
		int[] fillColor;
		String displayName;
		Map<String, Boolean> islands;
	}

	private static final class KeybindData {
		String key;
		int modifiers;
	}

	private static final class MacroData {
		int id;
		String name;
		Boolean enabled;
		String key;
		int modifiers;
		String triggerContext;
		Boolean islandRestricted;
		List<String> islands;
		JsonArray steps;
		JsonArray scripts;
		JsonArray detachedBlocks;
		Float canvasPanX;
		Float canvasPanY;
		Float canvasZoom;
		Integer canvasZoomVersion;
		String folderId;
	}

	private static final class MacroFunctionData {
		String id;
		String name;
		List<MacroFunctionParameterData> parameters;
		JsonArray steps;
	}

	private static final class MacroFunctionParameterData {
		String name;
		String type;
		String defaultValue;
	}

	/**
	 * Whether the mod may speak the Hypixel Mod API to learn which island it is on.
	 *
	 * <p>Reads the General module now that the switch is a settings-panel row, so the chat notice a
	 * stranded module prints can point at something the player can actually find. The first load after
	 * that move seeds the row from the old top-level key, so an install that had turned the API off
	 * does not silently get it back; the top-level key is still written as a mirror afterwards.
	 */
	public static boolean hypixelModApi() {
		return GeneralModule.INSTANCE.islandDetectionApi().value();
	}

	/** How long a debounced change may sit unwritten; a crash can cost at most this much of it. */
	private static final long FLUSH_INTERVAL_MILLIS = 60_000;
	private static final Object SAVE_LOCK = new Object();
	private static final ExecutorService SAVE_EXECUTOR = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "GeilerAddons config writer");
		thread.setDaemon(true);
		return thread;
	});

	private static volatile boolean dirty;
	private static volatile long lastFlush;
	private static volatile long mutationVersion;
	private static volatile boolean separatedMacroLoadBlocked;
	private static Future<?> pendingSave;
	private static long queuedVersion = -1;

	/**
	 * Whether the mod may contact GitHub once per launch to see if a newer release exists.
	 *
	 * <p>Reads the General module now that the switch is a settings-panel row. The first load after
	 * that move seeds the row from the old top-level key, so a player who had turned the check off
	 * does not silently get it back; the top-level key is still written as a mirror afterwards.
	 */
	public static boolean checkForUpdates() {
		return GeneralModule.INSTANCE.checkForUpdates().value();
	}

	public static void load() {
		Data data = readData();
		boolean migratedDefaultTheme = migrateLegacyDefaultTheme(data);
		boolean migratedIconColor = ThemeIconColorMigration.migrate(data.colors,
			settingKey(VisualModule.INSTANCE, "Icon color"), settingKey(GeneralModule.INSTANCE, "Icon color"));
		for (Module module : ModuleManager.modules()) {
			KeybindData savedKeybind = data.keybinds.get(module.configName());
			if (!module.showsKeybindControl()) {
				module.setKeybind(ModuleKeybind.NONE);
			} else if (savedKeybind != null && savedKeybind.key != null) {
				try {
					module.setKeybind(new ModuleKeybind(InputConstants.getKey(savedKeybind.key), savedKeybind.modifiers));
				} catch (RuntimeException ignored) {
					// A removed or hand-edited key must not prevent the client from starting.
				}
			}
			for (ColorSetting setting : module.colorSettings()) {
				int[] rgba = settingValue(data.colors, module, setting.name());
				if (rgba != null && rgba.length == 4) {
					setting.set(rgba[0], rgba[1], rgba[2], rgba[3]);
				}
			}
			for (NumberSetting setting : module.numberSettings()) {
				Float value = settingValue(data.numbers, module, setting.name());
				if (value != null) {
					setting.setValue(value);
				}
			}
			for (BooleanSetting setting : module.booleanSettings()) {
				Boolean value = settingValue(data.toggles, module, setting.name());
				if (value != null) {
					setting.setValue(value);
				}
			}
			for (ChoiceSetting setting : module.choiceSettings()) {
				String value = settingValue(data.choices, module, setting.name());
				if (value != null) setting.setValue(value);
			}
			for (TextSetting setting : module.textSettings()) {
				String value = settingValue(data.texts, module, setting.name());
				if (value != null) {
					setting.setValue(value);
				} else {
					// Keeps values when a numeric slider is deliberately replaced by a text input.
					Float legacyNumber = settingValue(data.numbers, module, setting.name());
					if (legacyNumber != null) setting.setValue(formatLegacyNumber(legacyNumber));
				}
			}
			Boolean savedEnabled = data.enabled.get(module.configName());
			module.setEnabled(savedEnabled != null ? savedEnabled : module.defaultEnabled());
		}
		boolean migratedDungeonMobEspKeybind = migrateLegacyDungeonMobEspKeybind(data);
		DungeonMobEspModule.INSTANCE.restoreLegacySettings(data.numbers, data.toggles,
			data.choices, data.colors);
		if (!data.enabled.containsKey(DungeonMobEspModule.INSTANCE.configName())
			&& (Boolean.TRUE.equals(data.enabled.get("Starred Mob ESP"))
				|| Boolean.TRUE.equals(data.enabled.get("Miniboss ESP")))) {
			DungeonMobEspModule.INSTANCE.setEnabled(true);
		}
		Float legacyFillOpacity = data.numbers.get("Garden Plot Borders.Fill Opacity");
		if (legacyFillOpacity != null) {
			int alpha = Math.round(Math.max(0.0f, Math.min(100.0f, legacyFillOpacity)) * 2.55f);
			GardenPlotBordersModule.INSTANCE.fillColor().setChannel(ColorSetting.Channel.ALPHA, alpha);
		}
		AutoExperimentsModule autoExperiments = AutoExperimentsModule.INSTANCE;
		Float legacyExperimentDelay = data.numbers.get("Auto Experiments.Click Delay (ms)");
		Float savedMinimumDelay = data.numbers.get(settingKey(autoExperiments,
			autoExperiments.minimumClickDelay().name()));
		Float savedMaximumDelay = data.numbers.get(settingKey(autoExperiments,
			autoExperiments.maximumClickDelay().name()));
		AutoExperimentDelayRange restoredDelay = AutoExperimentDelayRange.restore(legacyExperimentDelay,
			savedMinimumDelay, savedMaximumDelay, autoExperiments.minimumClickDelay().intValue(),
			autoExperiments.maximumClickDelay().intValue());
		autoExperiments.minimumClickDelay().setValue(restoredDelay.minimumMillis());
		autoExperiments.maximumClickDelay().setValue(restoredDelay.maximumMillis());
		// Slot IDs used to be a toggle under Debug. Preserve that setting only when the new
		// standalone module has never been saved, so diagnostics and the overlay stay independent.
		if (!data.enabled.containsKey(SlotIdsModule.INSTANCE.name())
			&& data.toggles.containsKey("Debug.Show Slot IDs")) {
			SlotIdsModule.INSTANCE.setEnabled(Boolean.TRUE.equals(data.toggles.get("Debug.Show Slot IDs")));
		}
		if (data.checkForUpdates != null
			&& !data.toggles.containsKey(settingKey(GeneralModule.INSTANCE,
				GeneralModule.INSTANCE.checkForUpdates().name()))) {
			// One-time move of the update preference into the General module. The module's own key
			// wins once it exists, so the legacy key can never undo a change made in the panel.
			GeneralModule.INSTANCE.checkForUpdates().setValue(data.checkForUpdates);
		}
		if (data.hypixelModApi != null
			&& !data.toggles.containsKey(settingKey(GeneralModule.INSTANCE,
				GeneralModule.INSTANCE.islandDetectionApi().name()))) {
			// Same one-time move as the update preference: the module's own key wins once it exists,
			// so a legacy value can never undo a change made in the settings panel.
			GeneralModule.INSTANCE.islandDetectionApi().setValue(data.hypixelModApi);
		}
		// The macro-editor colour toggle was renamed to name both surfaces it themes. Its value is
		// carried across once, and only when the new key has never been written, so a player who had
		// turned it off does not silently get it back.
		Boolean legacyMacroColors = settingValue(data.toggles, VisualModule.INSTANCE, "Theme Macro Colors");
		if (legacyMacroColors != null
			&& !data.toggles.containsKey(settingKey(VisualModule.INSTANCE,
				VisualModule.INSTANCE.themeSurfaces().name()))) {
			VisualModule.INSTANCE.themeSurfaces().setValue(legacyMacroColors);
		}
		loadTikiCoords(data);
		loadTikiFingerprints(data);
		if (data.hudPositions != null) {
			HudManager.restore(data.hudPositions);
		}
		loadTreeGifts(data);
		MobHighlightModule.INSTANCE.folders().restore(data.mobHighlightFolders);
		MacrosModule.INSTANCE.folders().restore(data.macroFolders);
		BlockEspModule.INSTANCE.folders().restore(data.blockEspFolders);
		loadMobHighlights(data);
		loadBlockEspEntries(data);
		MacrosModule.INSTANCE.globalVariables().restore(MacroVariableConfigCodec.decode(data.macroVariables));
		loadSeparatedMacroData(data);
		loadMacroFunctions(data);
		loadMacros(data);
		loadInventoryButtons(data);
		loadUiState(data);
		if (migratedDefaultTheme || migratedDungeonMobEspKeybind || migratedIconColor) markDirty();
	}

	private static boolean migrateLegacyDungeonMobEspKeybind(Data data) {
		DungeonMobEspModule module = DungeonMobEspModule.INSTANCE;
		KeybindData currentData = data.keybinds.get(module.configName());
		ModuleKeybind current = parseKeybind(currentData);
		boolean currentPresent = currentData != null && currentData.key != null && current != null;
		DungeonMobEspKeybindMigration.Resolution migration = DungeonMobEspKeybindMigration.resolve(
			currentPresent, current,
			parseKeybind(data.keybinds.get("Starred Mob ESP")),
			parseKeybind(data.keybinds.get("Miniboss ESP")));

		if (currentPresent) return false;
		if (migration.conflict()) {
			module.setKeybind(ModuleKeybind.NONE);
			GeilerAddons.LOGGER.warn("Legacy Starred Mob ESP and Miniboss ESP keybinds conflict. "
				+ "Dungeon Mob ESP was left unbound; set its keybind in the Click GUI.");
			return migration.migrated();
		}
		if (!migration.migrated()) return false;
		module.setKeybind(migration.keybind());
		return true;
	}

	private static ModuleKeybind parseKeybind(KeybindData saved) {
		if (saved == null || saved.key == null) return null;
		try {
			return new ModuleKeybind(InputConstants.getKey(saved.key), saved.modifiers);
		} catch (RuntimeException ignored) {
			return null;
		}
	}

	/** Move only old compiled-default theme channels to the approved Click GUI reference colors. */
	private static boolean migrateLegacyDefaultTheme(Data data) {
		VisualModule theme = VisualModule.INSTANCE;
		boolean migrated = false;
		migrated |= migrateLegacyThemeColor(data, theme, "Background",
			new int[]{14, 14, 18, 230}, new int[]{11, 18, 32, 129});
		migrated |= migrateLegacyThemeColor(data, theme, "Border",
			new int[]{255, 255, 255, 51}, new int[]{25, 29, 36, 255});
		migrated |= migrateLegacyThemeColor(data, theme, "Accent",
			new int[]{207, 207, 214, 255}, new int[]{59, 130, 246, 255});
		migrated |= migrateLegacyThemeColor(data, theme, "Text",
			new int[]{255, 255, 255, 255}, new int[]{234, 242, 255, 255});
		migrated |= migrateLegacyThemeColor(data, theme, "Muted Text",
			new int[]{140, 140, 153, 255}, new int[]{126, 143, 168, 255});
		return migrated;
	}

	/** Migrate one palette channel only when its persisted value is exactly the old compiled default. */
	private static boolean migrateLegacyThemeColor(Data data, VisualModule theme, String setting,
		int[] legacyDefault, int[] replacement) {
		if (!java.util.Arrays.equals(settingValue(data.colors, theme, setting), legacyDefault)) return false;
		data.colors.put(settingKey(theme, setting), replacement);
		return true;
	}

	private static String formatLegacyNumber(float value) {
		if (value == Math.rint(value)) return Long.toString((long) value);
		return Float.toString(value);
	}

	/**
	 * Marks the config as needing a write, without doing one.
	 *
	 * <p>For state that changes on its own while playing rather than because the user touched a
	 * setting - gift counts, learned fingerprints. Those can arrive in bursts, and serializing the
	 * whole config on the client thread each time is a stutter for no benefit, since nothing reads
	 * the file until the next launch. {@link #flushIfDirty} does the write, at most once every
	 * {@link #FLUSH_INTERVAL_MILLIS}.
	 */
	public static void markDirty() {
		synchronized (SAVE_LOCK) {
			dirty = true;
			mutationVersion++;
		}
	}

	/** Call once per client tick. Writes only if something asked for it and enough time has passed. */
	public static void flushIfDirty() {
		if (!dirty) return;
		long now = System.currentTimeMillis();
		if (now - lastFlush < FLUSH_INTERVAL_MILLIS) return;
		save();
	}

	/** Writes a pending change immediately - for shutdown, where there is no next tick. */
	public static boolean flushNow() {
		Future<?> future;
		if (dirty) {
			future = enqueueSave(snapshotData(), mutationVersion);
		} else {
			synchronized (SAVE_LOCK) {
				future = pendingSave;
			}
		}
		return waitFor(future);
	}

	/** Waits for the already queued client-thread snapshot without creating a snapshot off-thread. */
	public static boolean flushPrepared() {
		Future<?> future;
		synchronized (SAVE_LOCK) { future = pendingSave; }
		boolean saved = waitFor(future);
		synchronized (SAVE_LOCK) { return saved && !dirty; }
	}

	/** Queues an explicit snapshot so closing a settings screen never performs disk I/O inline. */
	public static void save() {
		enqueueSave(snapshotData(), mutationVersion);
	}

	private static Future<?> enqueueSave(Data data, long version) {
		if (data == null) return null;
		synchronized (SAVE_LOCK) {
			if (pendingSave != null && !pendingSave.isDone() && queuedVersion >= version) {
				return pendingSave;
			}
			pendingSave = SAVE_EXECUTOR.submit(() -> writeSnapshot(data, version));
			queuedVersion = version;
			return pendingSave;
		}
	}

	private static boolean waitFor(Future<?> future) {
		if (future == null) return true;
		try {
			Object result = future.get();
			return !(result instanceof Boolean value) || value;
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			GeilerAddons.LOGGER.warn("Interrupted while saving GeilerAddons config");
			return false;
		} catch (ExecutionException error) {
			GeilerAddons.LOGGER.error("Config writer failed", error.getCause());
			return false;
		}
	}

	/** Applies declared setting defaults, respecting the caller's selected ESP exclusions. */
	public static void resetModuleDefaultsExcept(Set<String> excludedConfigNames) {
		Set<String> excluded = excludedConfigNames == null ? Set.of() : Set.copyOf(excludedConfigNames);
		for (Module module : ModuleManager.modules()) {
			if (excluded.contains(module.configName())) continue;
			module.colorSettings().forEach(ColorSetting::reset);
			module.numberSettings().forEach(NumberSetting::reset);
			module.booleanSettings().forEach(BooleanSetting::reset);
			module.choiceSettings().forEach(geiler.addons.client.module.ChoiceSetting::reset);
			module.textSettings().forEach(TextSetting::reset);
			module.setKeybind(ModuleKeybind.NONE);
			module.setEnabled(module.defaultEnabled());
		}
		markDirty();
	}

	/** Applies declared defaults only to the named modules. */
	public static void resetNamedModuleDefaults(Set<String> configNames) {
		if (configNames == null || configNames.isEmpty()) return;
		for (Module module : ModuleManager.modules()) {
			if (!configNames.contains(module.configName())) continue;
			module.colorSettings().forEach(ColorSetting::reset);
			module.numberSettings().forEach(NumberSetting::reset);
			module.booleanSettings().forEach(BooleanSetting::reset);
			module.choiceSettings().forEach(geiler.addons.client.module.ChoiceSetting::reset);
			module.textSettings().forEach(TextSetting::reset);
			module.setKeybind(ModuleKeybind.NONE);
			module.setEnabled(module.defaultEnabled());
		}
		markDirty();
	}

	/** Resets every runtime collection before applying a full imported profile. Must run on the client thread. */
	public static void resetRuntimeToFactoryDefaults() {
		resetModuleDefaultsExcept(Set.of());
		MacroRunner.cancel("configuration reset or import");
		MacrosModule.INSTANCE.restore(List.of());
		MacrosModule.INSTANCE.restoreFunctions(List.of());
		MacrosModule.INSTANCE.globalVariables().restore(List.of());
		MacrosModule.INSTANCE.folders().restore(List.of());
		MobHighlightModule.INSTANCE.restore(List.of());
		MobHighlightModule.INSTANCE.folders().restore(List.of());
		BlockEspModule.INSTANCE.restore(List.of());
		BlockEspModule.INSTANCE.folders().restore(List.of());
		InventoryButtonsModule.INSTANCE.restore(List.of());
		TikiCoords.seedDefaults();
		TikiFingerprints.replaceAll(Map.of());
		HudManager.resetPositions();
		for (TreeType type : TreeType.values()) {
			TreeTrackerModule.INSTANCE.tracker().stats(type).restore(0, 0, 0, 0);
		}
		ClickGuiState.setCategory(Category.values()[0]);
		ClickGuiState.setOpenModule(null);
		ClickGuiState.setExpandedColor(null);
		ClickGuiState.setSettingsScroll(0);
		ClickGuiState.setCategoryScroll(0);
		for (Category category : Category.values()) ClickGuiState.setGridScroll(category, 0);
		ClickGuiState.setFavoriteCategoryIds(List.of());
		ClickGuiState.setFavoriteModuleIds(List.of());
	}

	/** Queues a current snapshot after a targeted reset has changed live settings or collections. */
	public static void saveAfterReset() { markDirty(); save(); }

	private static Data snapshotData() {
		Data data = new Data();
		for (Module module : ModuleManager.modules()) {
			data.enabled.put(module.configName(), module.isEnabled());
			if (module.showsKeybindControl() && module.keybind().isBound()) {
				KeybindData savedKeybind = new KeybindData();
				savedKeybind.key = module.keybind().key().getName();
				savedKeybind.modifiers = module.keybind().modifiers();
				data.keybinds.put(module.configName(), savedKeybind);
			}
			for (ColorSetting setting : module.colorSettings()) {
				data.colors.put(settingKey(module, setting.name()), new int[]{setting.red(), setting.green(), setting.blue(), setting.alpha()});
			}
			for (NumberSetting setting : module.numberSettings()) {
				data.numbers.put(settingKey(module, setting.name()), setting.value());
			}
			for (BooleanSetting setting : module.booleanSettings()) {
				// Debug-only toggles expose an effective value while Debug is off. Persist the raw
				// choice so closing that diagnostic gate never erases what reopening it should restore.
				data.toggles.put(settingKey(module, setting.name()), setting.rawValue());
			}
			for (ChoiceSetting setting : module.choiceSettings()) {
				data.choices.put(settingKey(module, setting.name()), setting.value());
			}
			for (TextSetting setting : module.textSettings()) {
				data.texts.put(settingKey(module, setting.name()), setting.value());
			}
		}
		data.tikiCoords = new ArrayList<>();
		for (BlockPos pos : TikiCoords.all()) {
			data.tikiCoords.add(new int[]{pos.getX(), pos.getY(), pos.getZ()});
		}
		data.tikiFingerprints = new HashMap<>();
		for (Map.Entry<BlockPos, Block> entry : TikiFingerprints.all().entrySet()) {
			data.tikiFingerprints.put(coordKey(entry.getKey()), TikiFingerprints.idOf(entry.getValue()));
		}
		data.hudPositions = HudManager.snapshot();
		data.treeGifts = new HashMap<>();
		for (TreeType type : TreeType.values()) {
			TreeStats stats = TreeTrackerModule.INSTANCE.tracker().stats(type);
			data.treeGifts.put(type.name(), new long[]{
				stats.rawSessionCount(), stats.rawSessionMillis(), stats.rawStoredCount(), stats.rawStoredMillis()
			});
		}
		data.mobHighlights = new ArrayList<>();
		data.mobHighlightFolders = MobHighlightModule.INSTANCE.folders().folders();
		for (MobHighlight highlight : MobHighlightModule.INSTANCE.highlights()) {
			MobHighlightData saved = new MobHighlightData();
			saved.id = highlight.id();
			saved.folderId = highlight.folderId();
			saved.enabled = highlight.enabled().rawValue();
			saved.matchName = highlight.matchName().rawValue();
			saved.matchText = highlight.matchText().value();
			saved.outlineColor = rgba(highlight.outlineColor());
			saved.fillColor = rgba(highlight.fillColor());
			saved.depthCheck = highlight.depthCheck().rawValue();
			saved.scanInterval = highlight.scanInterval().value();
			saved.displayName = highlight.displayName().value();
			saved.islands = new LinkedHashMap<>();
			for (Map.Entry<Island, BooleanSetting> island : highlight.islands().entrySet()) {
				saved.islands.put(island.getKey().name(), island.getValue().rawValue());
			}
			data.mobHighlights.add(saved);
		}
		data.blockEspFolders = BlockEspModule.INSTANCE.folders().folders();
		data.blockEspEntries = new ArrayList<>();
		for (BlockEspEntry entry : BlockEspModule.INSTANCE.entries()) {
			BlockEspData saved = new BlockEspData();
			saved.id = entry.id();
			saved.blockId = entry.blockId();
			saved.folderId = entry.folderId();
			saved.enabled = entry.enabled().rawValue();
			saved.box = entry.box().rawValue();
			saved.fill = entry.fill().rawValue();
			saved.outline = entry.outline().rawValue();
			saved.showLabel = entry.showLabel().rawValue();
			saved.tracer = entry.tracer().rawValue();
			saved.connectTouching = entry.connectTouching().rawValue();
			saved.depthCheck = entry.depthCheck().rawValue();
			saved.useCustomRange = entry.useCustomRange().rawValue();
			saved.customRange = entry.customRange().intValue();
			saved.outlineColor = rgba(entry.outlineColor());
			saved.fillColor = rgba(entry.fillColor());
			saved.displayName = entry.displayName().value();
			saved.islands = new LinkedHashMap<>();
			for (Map.Entry<Island, BooleanSetting> island : entry.islands().entrySet()) {
				saved.islands.put(island.getKey().name(), island.getValue().rawValue());
			}
			data.blockEspEntries.add(saved);
		}
		MacrosModule.INSTANCE.syncSettings();
		data.macroFiles = snapshotMacros();
		data.functionFiles = snapshotMacroFunctions();
		data.macros = null;
		data.macroFunctions = null;
		data.macroVariables = MacroVariableConfigCodec.encode(MacrosModule.INSTANCE.globalVariables().savedVariables());
		data.macroFolders = MacrosModule.INSTANCE.folders().folders();
		data.inventoryButtons = InventoryButtonConfigCodec.encode(InventoryButtonsModule.INSTANCE.placements());
		data.uiCategory = ClickGuiState.category().name();
		data.uiOpenModule = ClickGuiState.openModule() == null ? null : ClickGuiState.openModule().configName();
		data.uiExpandedColor = ClickGuiState.expandedColor() == null ? null : ClickGuiState.expandedColor().name();
		data.uiSettingsScroll = ClickGuiState.settingsScroll();
		data.uiCategoryScroll = ClickGuiState.categoryScroll();
		data.uiGridScrollByCategory = new HashMap<>();
		ClickGuiState.gridScrolls().forEach((category, scroll) ->
			data.uiGridScrollByCategory.put(category.name(), scroll));
		data.uiFavoriteCategories = ClickGuiState.favoriteCategoryIds().stream().sorted().toList();
		data.uiFavoriteModules = ClickGuiState.favoriteModuleIds().stream().sorted().toList();
		// Mirror of the General module row, kept so an older build reading this file still sees the
		// player's choice instead of silently re-enabling the check.
		data.checkForUpdates = GeneralModule.INSTANCE.checkForUpdates().value();
		data.hypixelModApi = GeneralModule.INSTANCE.islandDetectionApi().value();
		return data;
	}

	private static boolean writeSnapshot(Data data, long version) {
		Path temporary = null;
		boolean success = false;
		try {
			Files.createDirectories(PATH.getParent());
			writeSeparatedMacroData(data);
			temporary = Files.createTempFile(PATH.getParent(), "config-", ".tmp");
			try (Writer writer = Files.newBufferedWriter(temporary)) {
				GSON.toJson(data, writer);
			}
			try {
				Files.move(temporary, PATH, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException unsupported) {
				Files.move(temporary, PATH, StandardCopyOption.REPLACE_EXISTING);
			}
			temporary = null;
			success = true;
		} catch (IOException e) {
			GeilerAddons.LOGGER.error("Failed to save GeilerAddons config to {}", PATH, e);
		} catch (RuntimeException e) {
			GeilerAddons.LOGGER.error("Failed to serialize GeilerAddons config to {}", PATH, e);
		} finally {
			if (temporary != null) {
				try {
					Files.deleteIfExists(temporary);
				} catch (IOException cleanupError) {
					GeilerAddons.LOGGER.debug("Failed to remove temporary config file {}", temporary, cleanupError);
				}
			}
			synchronized (SAVE_LOCK) {
				lastFlush = System.currentTimeMillis();
				if (!success || mutationVersion != version) dirty = true;
				else dirty = false;
			}
		}
		return success;
	}

	private static void loadTikiCoords(Data data) {
		if (data.tikiCoords == null) {
			TikiCoords.seedDefaults();
			return;
		}
		List<BlockPos> coords = new ArrayList<>();
		for (int[] coord : data.tikiCoords) {
			if (coord != null && coord.length == 3) {
				coords.add(new BlockPos(coord[0], coord[1], coord[2]));
			}
		}
		TikiCoords.replaceAll(coords);
	}

	private static void loadTikiFingerprints(Data data) {
		if (data.tikiFingerprints == null) return;
		Map<BlockPos, Block> fingerprints = new HashMap<>();
		for (Map.Entry<String, String> entry : data.tikiFingerprints.entrySet()) {
			BlockPos pos = parseCoordKey(entry.getKey());
			Block block = TikiFingerprints.byId(entry.getValue());
			if (pos != null && block != null) {
				fingerprints.put(pos, block);
			}
		}
		TikiFingerprints.replaceAll(fingerprints);
	}

	/**
	 * Restores the tree counters, then immediately closes out the session they were saved in.
	 *
	 * <p>The rollover is the point of persisting a session at all: a session that ended because
	 * the game was closed still has to reach the all-time totals, and this is where that happens.
	 */
	private static void loadTreeGifts(Data data) {
		if (data.treeGifts != null) {
			for (TreeType type : TreeType.values()) {
				long[] values = data.treeGifts.get(type.name());
				if (values == null || values.length != 4) continue;
				TreeTrackerModule.INSTANCE.tracker().stats(type)
					.restore((int) values[0], values[1], (int) values[2], values[3]);
			}
		}
		TreeTrackerModule.INSTANCE.rollOverLoadedSession();
	}

	/** Absent means "never saved", which is simply a user who has not made a highlight yet. */
	private static void loadMobHighlights(Data data) {
		if (data.mobHighlights == null) return;
		List<MobHighlight> restored = new ArrayList<>();
		for (MobHighlightData saved : data.mobHighlights) {
			if (restored.size() >= MobHighlightModule.MAX_HIGHLIGHTS) break;
			if (saved == null) continue;
			MobHighlight highlight = MobHighlightModule.INSTANCE.blank(saved.id);
			highlight.setFolderId(saved.folderId);
			if (saved.enabled != null) highlight.enabled().setValue(saved.enabled);
			if (saved.matchName != null) highlight.matchName().setValue(saved.matchName);
			if (saved.matchText != null) highlight.matchText().setValue(saved.matchText);
			applyColor(highlight.outlineColor(), saved.outlineColor);
			applyColor(highlight.fillColor(), saved.fillColor);
			if (saved.depthCheck != null) highlight.depthCheck().setValue(saved.depthCheck);
			if (saved.scanInterval != null) highlight.scanInterval().setValue(saved.scanInterval);
			if (saved.displayName != null) highlight.displayName().setValue(saved.displayName);
			applyIslands(highlight, saved.islands);
			restored.add(highlight);
		}
		MobHighlightModule.INSTANCE.restore(restored);
	}

	private static void loadMacros(Data data) {
		if (data.macros == null) return;
		List<MacroDefinition> restored = new ArrayList<>();
		java.util.HashSet<Integer> ids = new java.util.HashSet<>();
		for (MacroData saved : data.macros) {
			if (saved == null || saved.id < 0 || !ids.add(saved.id)) continue;
			MacroDefinition macro = new MacroDefinition(saved.id);
			macro.setFolderId(saved.folderId);
			if (saved.name != null) macro.setName(saved.name);
			if (saved.enabled != null) macro.setEnabled(saved.enabled);
			if (saved.key != null) {
				try {
					macro.setKeybind(new ModuleKeybind(InputConstants.getKey(saved.key), saved.modifiers));
				} catch (RuntimeException ignored) {
					// Invalid hand-edited keys leave the macro safely unbound.
				}
			}
			try {
				if (saved.triggerContext != null) {
					macro.setTriggerContext(MacroTriggerContext.valueOf(saved.triggerContext));
				}
			} catch (IllegalArgumentException ignored) {
				// Keep the safe default for a removed context name.
			}
			if (saved.islandRestricted != null) macro.setIslandRestricted(saved.islandRestricted);
			macro.setIslands(parseIslands(saved.islands));
			float savedZoom = saved.canvasZoom == null ? 1.0f : saved.canvasZoom;
			int savedZoomVersion = saved.canvasZoomVersion == null ? 0 : saved.canvasZoomVersion;
			if (savedZoomVersion < 1) savedZoom *= 100.0f;
			if (savedZoomVersion < 2) savedZoom *= 100.0f;
			macro.setCanvasView(saved.canvasPanX == null ? 0 : saved.canvasPanX,
				saved.canvasPanY == null ? 0 : saved.canvasPanY, savedZoom);
			// Legacy macro-level defaults are intentionally ignored. Delays now belong to individual
			// workflow nodes, so an older file cannot silently reintroduce a hidden delay.
			if (saved.scripts != null && !saved.scripts.isEmpty()) {
				macro.restoreScripts(MacroScriptConfigCodec.decode(saved.scripts));
			} else {
				macro.steps().addAll(MacroStepConfigCodec.decode(saved.steps));
			}
			// Older configs have no loose-block list; keep their executable stacks exactly as before.
			macro.restoreDetachedBlocks(MacroStepConfigCodec.decode(saved.detachedBlocks));
			// Keep an intentionally empty macro visible so the user can finish it in the editor;
			// pressing its key simply reports that there are no steps yet.
			restored.add(macro);
		}
		MacrosModule.INSTANCE.restore(restored);
	}

	/** Loads separated macro files when present; otherwise the inline legacy lists remain the migration source. */
	private static void loadSeparatedMacroData(Data data) {
		if (!Files.isRegularFile(MACRO_INDEX_PATH)) return;
		try {
			if (Files.size(MACRO_INDEX_PATH) > MAX_MACRO_INDEX_BYTES) throw new IOException("macro index exceeds the size limit");
			MacroIndex index;
			try (Reader reader = Files.newBufferedReader(MACRO_INDEX_PATH)) { index = GSON.fromJson(reader, MacroIndex.class); }
			if (index == null || index.version != 1 || index.macros == null || index.functions == null
				|| index.macros.size() > 2_000 || index.functions.size() > MacroFunction.MAX_FUNCTIONS) {
				throw new IOException("macro index is malformed or exceeds the entry limit");
			}
			List<MacroData> macros = readMacroRecords(index.macros, MACRO_DIR, MacroData.class);
			List<MacroFunctionData> functions = readMacroRecords(index.functions, FUNCTION_DIR, MacroFunctionData.class);
			data.macros = macros;
			data.macroFunctions = functions;
		} catch (IOException | RuntimeException error) {
			GeilerAddons.LOGGER.error("Could not load separated macro/function files from {}", DIR, error);
			separatedMacroLoadBlocked = true;
		}
	}

	private static <T> List<T> readMacroRecords(List<String> files, Path directory, Class<T> type) throws IOException {
		List<T> result = new ArrayList<>();
		java.util.HashSet<String> seenFiles = new java.util.HashSet<>();
		for (String file : files) {
			if (file == null || !file.matches("[A-Za-z0-9._-]{1,128}\\.json")) throw new IOException("macro index contains an invalid file name");
			if (!seenFiles.add(file)) throw new IOException("macro index contains a duplicate file reference");
			Path path = directory.resolve(file).normalize();
			if (!path.getParent().equals(directory.normalize()) || !Files.isRegularFile(path)) throw new IOException("macro index references a missing or unsafe file");
			if (Files.size(path) > MAX_MACRO_RECORD_BYTES) {
				throw new IOException("separated macro file exceeds the size limit");
			}
			try (Reader reader = Files.newBufferedReader(path)) {
				T record = GSON.fromJson(reader, type);
				if (record == null) throw new IOException("separated macro file has no value");
				result.add(record);
			} catch (RuntimeException error) {
				throw new IOException("could not read separated macro file " + path.getFileName(), error);
			}
		}
		return result;
	}

	/** Writes each workflow atomically and commits the manifest last, so stale files are ignored. */
	private static void writeSeparatedMacroData(Data data) throws IOException {
		if (separatedMacroLoadBlocked || data.macroFiles == null || data.functionFiles == null) return;
		// Validate counts and every serialized entry before touching the existing files. A rejected
		// snapshot must never leave a partially updated catalog or an index the next load refuses.
		if (data.macroFiles.size() > 2_000 || data.functionFiles.size() > MacroFunction.MAX_FUNCTIONS) {
			throw new IOException("Macro catalog exceeds the supported limit (2,000 macros / "
				+ MacroFunction.MAX_FUNCTIONS + " functions)");
		}
		List<String> macroFiles = new ArrayList<>();
		for (MacroData macro : data.macroFiles) {
			String file = safeFilePart(macro.name) + "-" + macro.id + ".json";
			validateSerializedSize(macro, MAX_MACRO_RECORD_BYTES, "macro " + macro.name);
			macroFiles.add(file);
		}
		List<String> functionFiles = new ArrayList<>();
		for (MacroFunctionData function : data.functionFiles) {
			String file = safeFilePart(function.name) + "-" + safeFilePart(function.id) + "-"
				+ Integer.toUnsignedString(function.id.hashCode(), 36) + ".json";
			validateSerializedSize(function, MAX_MACRO_RECORD_BYTES, "function " + function.name);
			functionFiles.add(file);
		}
		List<String> previousMacroFiles = readMacroManifestFiles();
		MacroIndex index = new MacroIndex();
		index.version = 1;
		index.macros = macroFiles;
		index.functions = functionFiles;
		validateSerializedSize(index, MAX_MACRO_INDEX_BYTES, "macro index");

		boolean migrating = !Files.isRegularFile(MACRO_INDEX_PATH) && Files.isRegularFile(PATH);
		Files.createDirectories(MACRO_DIR);
		Files.createDirectories(FUNCTION_DIR);
		if (migrating) {
			Path backup = DIR.resolve("config.before-split.json");
			if (!Files.exists(backup)) Files.copy(PATH, backup);
		}
		for (int i = 0; i < data.macroFiles.size(); i++) {
			writeJsonAtomic(MACRO_DIR.resolve(macroFiles.get(i)), data.macroFiles.get(i));
		}
		for (int i = 0; i < data.functionFiles.size(); i++) {
			writeJsonAtomic(FUNCTION_DIR.resolve(functionFiles.get(i)), data.functionFiles.get(i));
		}
		writeJsonAtomic(MACRO_INDEX_PATH, index);
		deleteNewlyOrphanedMacroFiles(previousMacroFiles, macroFiles);
	}

	/** Reads only a trustworthy, bounded manifest to identify files the writer previously owned. */
	private static List<String> readMacroManifestFiles() {
		if (!Files.isRegularFile(MACRO_INDEX_PATH, LinkOption.NOFOLLOW_LINKS)) return List.of();
		try {
			if (Files.size(MACRO_INDEX_PATH) > MAX_MACRO_INDEX_BYTES) return List.of();
			MacroIndex index;
			try (Reader reader = Files.newBufferedReader(MACRO_INDEX_PATH)) {
				index = GSON.fromJson(reader, MacroIndex.class);
			}
			if (index == null || index.version != 1 || index.macros == null
				|| index.macros.size() > 2_000) return List.of();
			List<String> result = new ArrayList<>();
			for (String file : index.macros) {
				if (isWriterOwnedMacroFile(file) && !result.contains(file)) result.add(file);
			}
			return List.copyOf(result);
		} catch (IOException | RuntimeException error) {
			GeilerAddons.LOGGER.warn("Could not inspect the previous macro manifest; leaving stale macro files untouched", error);
			return List.of();
		}
	}

	/** Deletes only old manifest entries omitted from the successfully committed replacement. */
	private static void deleteNewlyOrphanedMacroFiles(List<String> previousFiles, List<String> currentFiles) {
		if (previousFiles == null || previousFiles.isEmpty()
			|| !Files.isDirectory(MACRO_DIR, LinkOption.NOFOLLOW_LINKS)) return;
		Path macroDirectory = MACRO_DIR.toAbsolutePath().normalize();
		for (String file : previousFiles) {
			if (!isWriterOwnedMacroFile(file) || currentFiles.contains(file)) continue;
			Path target = macroDirectory.resolve(file).normalize();
			if (!macroDirectory.equals(target.getParent()) || Files.isSymbolicLink(target)
				|| !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) continue;
			try {
				Files.deleteIfExists(target);
			} catch (IOException error) {
				GeilerAddons.LOGGER.warn("Could not delete orphaned macro file {}", target.getFileName(), error);
			}
		}
	}

	private static boolean isWriterOwnedMacroFile(String file) {
		return file != null && file.matches("[A-Za-z0-9._-]{1,48}-[0-9]+\\.json");
	}

	private static void validateSerializedSize(Object value, int maximumBytes, String label) throws IOException {
		int size = GSON.toJson(value).getBytes(StandardCharsets.UTF_8).length;
		if (size > maximumBytes) {
			throw new IOException(label + " exceeds the " + maximumBytes + " byte persistence limit");
		}
	}

	private static void writeJsonAtomic(Path path, Object value) throws IOException {
		Path temporary = Files.createTempFile(path.getParent(), "entry-", ".tmp");
		try {
			try (Writer writer = Files.newBufferedWriter(temporary)) { GSON.toJson(value, writer); }
			try {
				Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException unsupported) {
				Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private static String safeFilePart(String value) {
		if (value == null || value.isBlank()) return "unnamed";
		String safe = value.trim().replaceAll("[^A-Za-z0-9._-]+", "-");
		while (safe.startsWith(".")) safe = safe.substring(1);
		if (safe.isBlank()) safe = "unnamed";
		return safe.length() > 48 ? safe.substring(0, 48) : safe;
	}

	private static final class MacroIndex {
		int version;
		List<String> macros;
		List<String> functions;
	}

	private static void loadMacroFunctions(Data data) {
		List<MacroFunction> restored = new ArrayList<>();
		java.util.HashSet<String> ids = new java.util.HashSet<>();
		if (data.macroFunctions != null) for (MacroFunctionData saved : data.macroFunctions) {
			// Duplicate ids are dropped for the same reason macros and Block ESP entries drop them:
			// MacrosModule.function() answers by id, so a repeated id makes the second definition
			// unreachable by name and by export while it is still being written back to the file.
			if (saved == null || restored.size() >= MacroFunction.MAX_FUNCTIONS || !ids.add(saved.id)) continue;
			MacroFunction function = new MacroFunction(saved.id);
			function.setName(saved.name);
			if (saved.parameters != null) for (MacroFunctionParameterData parameter : saved.parameters) {
				if (function.parameters().size() >= 32 || parameter == null) break;
				function.parameters().add(new MacroFunction.Parameter(parameter.name,
					parseMacroValueType(parameter.type), parameter.defaultValue));
			}
			function.steps().addAll(MacroStepConfigCodec.decode(saved.steps));
			restored.add(function);
		}
		MacrosModule.INSTANCE.restoreFunctions(restored);
	}

	private static void loadBlockEspEntries(Data data) {
		if (data.blockEspEntries == null) return;
		Float legacyScanRadius = data.numbers == null ? null : data.numbers.get("Block ESP.Scan Radius (blocks)");
		List<BlockEspEntry> restored = new ArrayList<>();
		java.util.HashSet<Integer> ids = new java.util.HashSet<>();
		for (BlockEspData saved : data.blockEspEntries) {
			if (saved == null || saved.id < 0 || !ids.add(saved.id)) continue;
			if (restored.size() >= BlockEspModule.MAX_ENTRIES) break;
			BlockEspEntry entry = BlockEspModule.INSTANCE.blank(saved.id);
			entry.setBlockId(saved.blockId);
			entry.setFolderId(saved.folderId);
			if (saved.enabled != null) entry.enabled().setValue(saved.enabled);
			if (saved.box != null) entry.box().setValue(saved.box);
			if (saved.fill != null) entry.fill().setValue(saved.fill);
			if (saved.outline != null) entry.outline().setValue(saved.outline);
			if (saved.showLabel != null) entry.showLabel().setValue(saved.showLabel);
			if (saved.tracer != null) entry.tracer().setValue(saved.tracer);
			if (saved.connectTouching != null) entry.connectTouching().setValue(saved.connectTouching);
			if (saved.depthCheck != null) entry.depthCheck().setValue(saved.depthCheck);
			if (saved.useCustomRange != null) entry.useCustomRange().setValue(saved.useCustomRange);
			if (saved.customRange != null) entry.customRange().setValue(saved.customRange);
			else if (legacyScanRadius != null) entry.customRange().setValue(legacyScanRadius);
			applyColor(entry.outlineColor(), saved.outlineColor);
			applyColor(entry.fillColor(), saved.fillColor);
			if (saved.displayName != null) entry.displayName().setValue(saved.displayName);
			applyBlockIslands(entry, saved.islands);
			restored.add(entry);
		}
		BlockEspModule.INSTANCE.restore(restored);
	}

	private static void loadInventoryButtons(Data data) {
		List<InventoryButtonPlacement> restored = InventoryButtonConfigCodec.decode(data.inventoryButtons,
			id -> MacrosModule.INSTANCE.macro(id) != null, InventoryButtonsModule.INSTANCE::isPngIconAllowed);
		InventoryButtonsModule.INSTANCE.restore(restored);
	}

	private static void applyBlockIslands(BlockEspEntry entry, Map<String, Boolean> saved) {
		if (saved == null) return;
		for (Map.Entry<Island, BooleanSetting> island : entry.islands().entrySet()) {
			Boolean wanted = saved.get(island.getKey().name());
			if (wanted != null) island.getValue().setValue(wanted);
		}
	}

	private static java.util.EnumSet<Island> parseIslands(List<String> names) {
		java.util.EnumSet<Island> islands = java.util.EnumSet.noneOf(Island.class);
		if (names == null) return islands;
		for (String name : names) {
			if (name == null) continue;
			try {
				Island island = Island.valueOf(name);
				if (island.selectable()) islands.add(island);
			} catch (IllegalArgumentException ignored) {
				// Removed island names do not make the remaining macro unusable.
			}
		}
		return islands;
	}

	private static List<MacroData> snapshotMacros() {
		List<MacroData> result = new ArrayList<>();
		for (MacroDefinition macro : MacrosModule.INSTANCE.macros()) {
			MacroData data = new MacroData();
			data.id = macro.id();
			data.folderId = macro.folderId();
			data.name = macro.name();
			data.enabled = macro.enabled();
			data.key = macro.keybind().isBound() ? macro.keybind().key().getName() : null;
			data.modifiers = macro.keybind().modifiers();
			data.triggerContext = macro.triggerContext().name();
			data.islandRestricted = macro.islandRestricted();
			data.islands = new ArrayList<>();
			for (Island island : macro.islands()) data.islands.add(island.name());
			data.steps = MacroStepConfigCodec.encode(macro.steps());
			data.scripts = MacroScriptConfigCodec.encode(macro.scripts());
			data.detachedBlocks = MacroStepConfigCodec.encode(macro.detachedBlocks());
			data.canvasPanX = macro.canvasPanX();
			data.canvasPanY = macro.canvasPanY();
			data.canvasZoom = macro.canvasZoom();
			data.canvasZoomVersion = 2;
			result.add(data);
		}
		return result;
	}

	private static List<MacroFunctionData> snapshotMacroFunctions() {
		List<MacroFunctionData> result = new ArrayList<>();
		for (MacroFunction function : MacrosModule.INSTANCE.functions()) {
			MacroFunctionData data = new MacroFunctionData();
			data.id = function.id();
			data.name = function.name();
			data.steps = MacroStepConfigCodec.encode(function.steps());
			data.parameters = new ArrayList<>();
			for (MacroFunction.Parameter parameter : function.parameters()) {
				MacroFunctionParameterData saved = new MacroFunctionParameterData();
				saved.name = parameter.name();
				saved.type = parameter.type().name();
				saved.defaultValue = parameter.defaultValue();
				data.parameters.add(saved);
			}
			result.add(data);
		}
		return result;
	}

	private static MacroValue.Type parseMacroValueType(String value) {
		try { return MacroValue.Type.valueOf(value == null ? "TEXT" : value.toUpperCase(java.util.Locale.ROOT)); }
		catch (IllegalArgumentException ignored) { return MacroValue.Type.TEXT; }
	}

	/**
	 * Restores a highlight's island filter, or switches it fully on when there isn't one saved.
	 *
	 * <p>A new highlight starts with every island off, so it has to be told where it belongs. A
	 * highlight restored from a config written before the filter existed must not inherit that:
	 * it ran everywhere when it was saved, and silently coming back matching nothing would look
	 * exactly like the upgrade had broken it. An island simply missing from an otherwise present
	 * map is left alone, which is how an island added in a later version arrives switched off
	 * rather than turning itself on in every highlight the user already had.
	 */
	private static void applyIslands(MobHighlight highlight, Map<String, Boolean> saved) {
		if (saved == null) {
			for (BooleanSetting island : highlight.islands().values()) {
				island.setValue(true);
			}
			return;
		}
		for (Map.Entry<Island, BooleanSetting> island : highlight.islands().entrySet()) {
			Boolean wanted = saved.get(island.getKey().name());
			if (wanted != null) island.getValue().setValue(wanted);
		}
	}

	private static void applyColor(ColorSetting setting, int[] rgba) {
		if (rgba != null && rgba.length == 4) {
			setting.set(rgba[0], rgba[1], rgba[2], rgba[3]);
		}
	}

	private static int[] rgba(ColorSetting setting) {
		return new int[]{setting.red(), setting.green(), setting.blue(), setting.alpha()};
	}

	private static BlockPos parseCoordKey(String key) {
		String[] parts = key.split(",");
		if (parts.length != 3) return null;
		try {
			return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static String coordKey(BlockPos pos) {
		return pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	/** Resolves the saved names back to live objects; anything that no longer exists is dropped. */
	private static void loadUiState(Data data) {
		ClickGuiState.setCategoryScroll(data.uiCategoryScroll);
		ClickGuiState.setGridScrolls(data.uiGridScrollByCategory);
		ClickGuiState.setFavoriteCategoryIds(data.uiFavoriteCategories);
		ClickGuiState.setFavoriteModuleIds(data.uiFavoriteModules);
		if (data.uiCategory != null) {
			for (Category category : Category.values()) {
				if (category.name().equals(data.uiCategory)) {
					ClickGuiState.setCategory(category);
					break;
				}
			}
		}
		if (data.uiOpenModule == null) return;
		for (Module module : ModuleManager.modules()) {
			if (!module.configName().equals(data.uiOpenModule) || !module.hasSettings()) continue;
			ClickGuiState.setOpenModule(module);
			ClickGuiState.setSettingsScroll(Math.max(0, data.uiSettingsScroll));
			if (data.uiExpandedColor != null) {
				for (ColorSetting setting : module.colorSettings()) {
					if (setting.name().equals(data.uiExpandedColor)) {
						ClickGuiState.setExpandedColor(setting);
						break;
					}
				}
			}
			return;
		}
	}

	private static Data readData() {
		boolean legacy = !Files.exists(PATH) && Files.exists(LEGACY_PATH);
		Path source = legacy ? LEGACY_PATH : PATH;
		if (!Files.exists(source)) {
			return new Data();
		}

		Data data;
		try (Reader reader = Files.newBufferedReader(source)) {
			data = GSON.fromJson(reader, Data.class);
		} catch (IOException | JsonParseException e) {
			// A corrupt or unreadable config falls back to defaults rather than blocking startup.
			GeilerAddons.LOGGER.error("Failed to load GeilerAddons config from {}, using defaults", source, e);
			return new Data();
		}
		if (data == null) {
			data = new Data();
		}
		// Gson leaves absent fields at their initializers but writes a literal null straight
		// through, so normalize the maps before anything reads them.
		if (data.enabled == null) data.enabled = new HashMap<>();
		if (data.colors == null) data.colors = new HashMap<>();
		if (data.numbers == null) data.numbers = new HashMap<>();
		if (data.toggles == null) data.toggles = new HashMap<>();
		if (data.choices == null) data.choices = new HashMap<>();
		if (data.texts == null) data.texts = new HashMap<>();
		if (data.keybinds == null) data.keybinds = new HashMap<>();
		if (data.uiFavoriteCategories == null) data.uiFavoriteCategories = new ArrayList<>();
		if (data.uiFavoriteModules == null) data.uiFavoriteModules = new ArrayList<>();

		if (legacy) {
			migrateLegacyFile();
		}
		return data;
	}

	/**
	 * Moves the flat geileraddons.json into its own folder alongside whatever else this mod
	 * starts keeping there later. A straight file copy rather than a re-serialize of {@code data}:
	 * the parsed object is only a partial read at this point in load(), and the bytes on disk are
	 * already exactly what should end up at the new path.
	 */
	private static void migrateLegacyFile() {
		try {
			Files.createDirectories(DIR);
			Files.copy(LEGACY_PATH, PATH, StandardCopyOption.REPLACE_EXISTING);
			Files.deleteIfExists(LEGACY_PATH);
			GeilerAddons.LOGGER.info("Migrated GeilerAddons config from {} to {}", LEGACY_PATH, PATH);
		} catch (IOException e) {
			// Not fatal: the data was already parsed from the old file, and save() will simply
			// write a fresh copy at the new path next time, leaving the old one as an orphan.
			GeilerAddons.LOGGER.error("Failed to migrate GeilerAddons config from {} to {}", LEGACY_PATH, PATH, e);
		}
	}

	private static String settingKey(Module module, String settingName) {
		return module.configName() + "." + settingName;
	}

	/** Reads a renamed setting without making the shorter UI labels discard an existing value. */
	private static <T> T settingValue(Map<String, T> values, Module module, String settingName) {
		T value = values.get(settingKey(module, settingName));
		if (value != null) return value;
		String legacyKey = legacySettingKey(module, settingName);
		return legacyKey == null ? null : values.get(legacyKey);
	}

	private static String legacySettingKey(Module module, String settingName) {
		if (!"Auto Kick".equals(module.name())) return null;
		int separator = settingName.indexOf(' ');
		if (separator <= 0) return null;
		String floor = settingName.substring(0, separator);
		String suffix = settingName.substring(separator + 1);
		String legacySuffix = switch (suffix) {
			case "Ask Before" -> "Ask Before Kick";
			case "Dupe" -> "Dupe Check";
			case "Cata" -> "Minimum Cata";
			case "Class" -> "Minimum Joined Class";
			case "Class Avg" -> "Minimum Class Average";
			case "Secrets" -> "Minimum Secrets";
			case "Secret Avg" -> "Minimum Secret Average";
			case "MP" -> "Minimum Magical Power";
			case "Minimum PB" -> "Maximum PB Seconds";
			case "Bank" -> "Minimum Bank";
			case "Terminator" -> "Require Terminator";
			case "Hyperion" -> "Require Hyperion";
			case "GDrag" -> "Require Golden Dragon";
			default -> null;
		};
		return legacySuffix == null ? null : settingKey(module, floor + " " + legacySuffix);
	}
}
