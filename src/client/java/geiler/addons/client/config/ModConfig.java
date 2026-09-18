package geiler.addons.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import geiler.addons.GeilerAddons;
import geiler.addons.client.hud.HudManager;
import geiler.addons.client.location.Island;
import geiler.addons.client.macro.MacroCondition;
import geiler.addons.client.macro.MacroDefinition;
import geiler.addons.client.macro.MacroStep;
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
import geiler.addons.client.module.impl.MacrosModule;
import geiler.addons.client.module.impl.SlotIdsModule;
import geiler.addons.client.module.impl.TreeTrackerModule;
import geiler.addons.client.tree.TreeStats;
import geiler.addons.client.tree.TreeType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import com.mojang.blaze3d.platform.InputConstants;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
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

/** Persists module enabled-state, settings and the Tiki coordinate list across restarts. */
public final class ModConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** Every GeilerAddons file lives under here - just this config today, but not forever. */
	private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("geileraddons");
	private static final Path PATH = DIR.resolve("config.json");
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
		/** Click GUI view state - see {@link ClickGuiState}. */
		String uiCategory;
		String uiOpenModule;
		String uiExpandedColor;
		int uiSettingsScroll;
		/** Folded-shut settings sections, as "module.group" keys. */
		List<String> uiCollapsedGroups;
		/** Absent means "never saved", which keeps the check on by default. */
		Boolean checkForUpdates;
		/** Absent means "never saved", which keeps island detection on by default. */
		Boolean hypixelModApi;
		/** User-authored workflows. Absent means no macros have been created yet. */
		List<MacroData> macros;
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
		Integer defaultDelayMin;
		Integer defaultDelayMax;
		List<StepData> steps;
	}

	private static final class StepData {
		String type;
		Integer delayMin;
		Integer delayMax;
		String command;
		String message;
		Integer minMillis;
		Integer maxMillis;
		String key;
		Boolean hold;
		Integer holdMillis;
		Integer slotId;
		Integer button;
		Boolean shift;
		String name;
		Boolean contains;
		String scope;
		Integer occurrence;
		String island;
		ConditionData condition;
		List<StepData> thenSteps;
		List<StepData> elseSteps;
		Boolean forever;
		Integer count;
		List<StepData> steps;
	}

	private static final class ConditionData {
		String type;
		Boolean expected;
		String title;
		Boolean mustBeOpen;
		Boolean contains;
		Integer slotId;
		Boolean mustExist;
		String name;
		Boolean includePlayerInventory;
		String text;
		Boolean mustBeInWorld;
		List<ConditionData> children;
		ConditionData child;
	}

	/**
	 * Whether the mod may contact GitHub once per launch to see if a newer release exists.
	 * Config-file only: it is the mod's single outbound request and belongs with the other
	 * one-off preferences rather than in a module's settings panel.
	 */
	private static boolean checkForUpdates = true;

	/**
	 * Whether the mod may speak the Hypixel Mod API to learn which island it is on.
	 * Config-file only, like {@link #checkForUpdates}: it decides how the mod talks to the server
	 * rather than what any one module does, so it is not any module's setting.
	 */
	private static boolean hypixelModApi = true;

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
	private static Future<?> pendingSave;
	private static long queuedVersion = -1;

	public static boolean checkForUpdates() {
		return checkForUpdates;
	}

	public static boolean hypixelModApi() {
		return hypixelModApi;
	}

	public static void load() {
		Data data = readData();
		for (Module module : ModuleManager.modules()) {
			KeybindData savedKeybind = data.keybinds.get(module.name());
			if (savedKeybind != null && savedKeybind.key != null) {
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
			if (Boolean.TRUE.equals(data.enabled.get(module.name()))) {
				module.setEnabled(true);
			}
		}
		// Slot IDs used to be a toggle under Debug. Preserve that setting only when the new
		// standalone module has never been saved, so diagnostics and the overlay stay independent.
		if (!data.enabled.containsKey(SlotIdsModule.INSTANCE.name())
			&& data.toggles.containsKey("Debug.Show Slot IDs")) {
			SlotIdsModule.INSTANCE.setEnabled(Boolean.TRUE.equals(data.toggles.get("Debug.Show Slot IDs")));
		}
		if (data.checkForUpdates != null) {
			checkForUpdates = data.checkForUpdates;
		}
		if (data.hypixelModApi != null) {
			hypixelModApi = data.hypixelModApi;
		}
		loadTikiCoords(data);
		loadTikiFingerprints(data);
		if (data.hudPositions != null) {
			HudManager.restore(data.hudPositions);
		}
		loadTreeGifts(data);
		loadMobHighlights(data);
		loadMacros(data);
		loadUiState(data);
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
	public static void flushNow() {
		Future<?> future;
		if (dirty) {
			future = enqueueSave(snapshotData(), mutationVersion);
		} else {
			synchronized (SAVE_LOCK) {
				future = pendingSave;
			}
		}
		waitFor(future);
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

	private static void waitFor(Future<?> future) {
		if (future == null) return;
		try {
			future.get();
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			GeilerAddons.LOGGER.warn("Interrupted while saving GeilerAddons config");
		} catch (ExecutionException error) {
			GeilerAddons.LOGGER.error("Config writer failed", error.getCause());
		}
	}

	private static Data snapshotData() {
		Data data = new Data();
		for (Module module : ModuleManager.modules()) {
			data.enabled.put(module.name(), module.isEnabled());
			if (module.keybind().isBound()) {
				KeybindData savedKeybind = new KeybindData();
				savedKeybind.key = module.keybind().key().getName();
				savedKeybind.modifiers = module.keybind().modifiers();
				data.keybinds.put(module.name(), savedKeybind);
			}
			for (ColorSetting setting : module.colorSettings()) {
				data.colors.put(settingKey(module, setting.name()), new int[]{setting.red(), setting.green(), setting.blue(), setting.alpha()});
			}
			for (NumberSetting setting : module.numberSettings()) {
				data.numbers.put(settingKey(module, setting.name()), setting.value());
			}
			for (BooleanSetting setting : module.booleanSettings()) {
				// Debug-only toggles expose an effective false while the global gate is off. Persist the
				// raw user choice so disabling diagnostics never erases what re-enabling should restore.
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
		for (MobHighlight highlight : MobHighlightModule.INSTANCE.highlights()) {
			MobHighlightData saved = new MobHighlightData();
			saved.id = highlight.id();
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
		MacrosModule.INSTANCE.syncSettings();
		data.macros = snapshotMacros();
		data.uiCategory = ClickGuiState.category().name();
		data.uiOpenModule = ClickGuiState.openModule() == null ? null : ClickGuiState.openModule().name();
		data.uiExpandedColor = ClickGuiState.expandedColor() == null ? null : ClickGuiState.expandedColor().name();
		data.uiSettingsScroll = ClickGuiState.settingsScroll();
		data.uiCollapsedGroups = new ArrayList<>(ClickGuiState.collapsedGroups());
		data.checkForUpdates = checkForUpdates;
		data.hypixelModApi = hypixelModApi;
		return data;
	}

	private static void writeSnapshot(Data data, long version) {
		Path temporary = null;
		boolean success = false;
		try {
			Files.createDirectories(PATH.getParent());
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
			// Legacy macro-level defaults are intentionally ignored. Delays now belong to individual
			// workflow nodes, so an older file cannot silently reintroduce a hidden delay.
			if (saved.steps != null) {
				for (StepData step : saved.steps) {
					MacroStep parsed = parseStep(step, 0);
					if (parsed != null) macro.steps().add(parsed);
				}
			}
			// Keep an intentionally empty macro visible so the user can finish it in the editor;
			// pressing its key simply reports that there are no steps yet.
			restored.add(macro);
		}
		MacrosModule.INSTANCE.restore(restored);
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

	private static MacroStep parseStep(StepData data, int depth) {
		if (data == null || data.type == null || depth > 8) return null;
		MacroStep step;
		try {
			step = switch (data.type) {
				case "command" -> new MacroStep.Command(data.command);
				case "chat" -> new MacroStep.Chat(data.message);
				case "wait" -> new MacroStep.Wait(data.minMillis == null ? 0 : data.minMillis,
					data.maxMillis == null ? (data.minMillis == null ? 0 : data.minMillis) : data.maxMillis);
				case "key" -> new MacroStep.Key(data.key, Boolean.TRUE.equals(data.hold),
					data.holdMillis == null ? 250 : data.holdMillis);
				case "click_slot" -> new MacroStep.ClickSlot(data.slotId == null ? 0 : data.slotId,
					data.button == null ? 0 : data.button, Boolean.TRUE.equals(data.shift));
				case "click_item" -> new MacroStep.ClickItem(data.name, Boolean.TRUE.equals(data.contains),
					data.scope, data.occurrence == null ? 0 : data.occurrence,
					data.button == null ? 0 : data.button, Boolean.TRUE.equals(data.shift));
				case "close_screen" -> new MacroStep.CloseScreen();
				case "world_switch" -> new MacroStep.WorldSwitch(parseSelectableIsland(data.island));
				case "wait_until" -> new MacroStep.WaitUntil(parseCondition(data.condition, depth + 1));
				case "if" -> {
					MacroStep.IfElse branch = new MacroStep.IfElse(parseCondition(data.condition, depth + 1));
					addSteps(branch.thenSteps(), data.thenSteps, depth + 1);
					addSteps(branch.elseSteps(), data.elseSteps, depth + 1);
					yield branch;
				}
				case "repeat" -> {
					MacroStep.Repeat repeat = new MacroStep.Repeat(Boolean.TRUE.equals(data.forever),
						data.count == null ? 1 : data.count);
					addSteps(repeat.steps(), data.steps, depth + 1);
					yield repeat;
				}
				default -> null;
			};
		} catch (RuntimeException invalid) {
			return null;
		}
		if (step == null) return null;
		if (data.delayMin != null && data.delayMax != null) step.setDelay(data.delayMin, data.delayMax);
		return step;
	}

	private static void addSteps(List<MacroStep> target, List<StepData> source, int depth) {
		if (source == null || depth > 8) return;
		int limit = Math.min(source.size(), 512);
		for (int i = 0; i < limit; i++) {
			MacroStep step = parseStep(source.get(i), depth);
			if (step != null) target.add(step);
		}
	}

	private static MacroCondition parseCondition(ConditionData data, int depth) {
		if (data == null || depth > 8 || data.type == null) return new MacroCondition.Always(true);
		try {
			return switch (data.type) {
				case "always" -> new MacroCondition.Always(!Boolean.FALSE.equals(data.expected));
				case "screen" -> new MacroCondition.Screen(data.title, !Boolean.FALSE.equals(data.mustBeOpen),
					Boolean.TRUE.equals(data.contains));
				case "slot" -> new MacroCondition.Slot(data.slotId == null ? 0 : data.slotId,
					!Boolean.FALSE.equals(data.mustExist));
				case "item" -> new MacroCondition.Item(data.name, !Boolean.FALSE.equals(data.mustExist),
					Boolean.TRUE.equals(data.contains), Boolean.TRUE.equals(data.includePlayerInventory));
				case "chat" -> new MacroCondition.Chat(data.text, Boolean.TRUE.equals(data.contains));
				case "world" -> new MacroCondition.World(!Boolean.FALSE.equals(data.mustBeInWorld));
				case "all" -> new MacroCondition.All(parseConditions(data.children, depth + 1));
				case "any" -> new MacroCondition.Any(parseConditions(data.children, depth + 1));
				case "not" -> new MacroCondition.Not(parseCondition(data.child, depth + 1));
				default -> new MacroCondition.Always(true);
			};
		} catch (RuntimeException invalid) {
			return new MacroCondition.Always(true);
		}
	}

	private static List<MacroCondition> parseConditions(List<ConditionData> source, int depth) {
		List<MacroCondition> result = new ArrayList<>();
		if (source == null || depth > 8) return result;
		for (ConditionData child : source) result.add(parseCondition(child, depth));
		return result;
	}

	private static List<MacroData> snapshotMacros() {
		List<MacroData> result = new ArrayList<>();
		for (MacroDefinition macro : MacrosModule.INSTANCE.macros()) {
			MacroData data = new MacroData();
			data.id = macro.id();
			data.name = macro.name();
			data.enabled = macro.enabled();
			data.key = macro.keybind().isBound() ? macro.keybind().key().getName() : null;
			data.modifiers = macro.keybind().modifiers();
			data.triggerContext = macro.triggerContext().name();
			data.islandRestricted = macro.islandRestricted();
			data.islands = new ArrayList<>();
			for (Island island : macro.islands()) data.islands.add(island.name());
			data.steps = snapshotSteps(macro.steps(), 0);
			result.add(data);
		}
		return result;
	}

	private static List<StepData> snapshotSteps(List<MacroStep> steps, int depth) {
		List<StepData> result = new ArrayList<>();
		if (steps == null || depth > 8) return result;
		for (MacroStep step : steps) {
			if (step == null) continue;
			StepData data = new StepData();
			data.type = step.type();
			data.delayMin = step.delayMin();
			data.delayMax = step.delayMax();
			if (step instanceof MacroStep.Command command) data.command = command.command();
			if (step instanceof MacroStep.Chat chat) data.message = chat.message();
			if (step instanceof MacroStep.Wait wait) { data.minMillis = wait.minMillis(); data.maxMillis = wait.maxMillis(); }
			if (step instanceof MacroStep.Key key) { data.key = key.key(); data.hold = key.hold(); data.holdMillis = key.holdMillis(); }
			if (step instanceof MacroStep.ClickSlot click) { data.slotId = click.slotId(); data.button = click.button(); data.shift = click.shift(); }
			if (step instanceof MacroStep.ClickItem click) {
				data.name = click.name(); data.contains = click.contains(); data.scope = click.scope();
				data.occurrence = click.occurrence(); data.button = click.button(); data.shift = click.shift();
			}
			if (step instanceof MacroStep.WorldSwitch worldSwitch) data.island = worldSwitch.target().name();
			if (step instanceof MacroStep.WaitUntil wait) data.condition = snapshotCondition(wait.condition(), depth + 1);
			if (step instanceof MacroStep.IfElse branch) {
				data.condition = snapshotCondition(branch.condition(), depth + 1);
				data.thenSteps = snapshotSteps(branch.thenSteps(), depth + 1);
				data.elseSteps = snapshotSteps(branch.elseSteps(), depth + 1);
			}
			if (step instanceof MacroStep.Repeat repeat) {
				data.forever = repeat.forever(); data.count = repeat.count();
				data.steps = snapshotSteps(repeat.steps(), depth + 1);
			}
			result.add(data);
		}
		return result;
	}

	private static Island parseSelectableIsland(String value) {
		if (value == null) return Island.HUB;
		try {
			Island island = Island.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
			return island.selectable() ? island : Island.HUB;
		} catch (IllegalArgumentException ignored) {
			for (Island island : Island.values()) {
				if (island.selectable() && island.label().equalsIgnoreCase(value.trim())) return island;
			}
			return Island.HUB;
		}
	}

	private static ConditionData snapshotCondition(MacroCondition condition, int depth) {
		ConditionData data = new ConditionData();
		if (condition == null || depth > 8) { data.type = "always"; data.expected = true; return data; }
		if (condition instanceof MacroCondition.Always value) { data.type = "always"; data.expected = value.expected(); }
		else if (condition instanceof MacroCondition.Screen value) { data.type = "screen"; data.title = value.title(); data.mustBeOpen = value.mustBeOpen(); data.contains = value.contains(); }
		else if (condition instanceof MacroCondition.Slot value) { data.type = "slot"; data.slotId = value.slotId(); data.mustExist = value.mustExist(); }
		else if (condition instanceof MacroCondition.Item value) { data.type = "item"; data.name = value.name(); data.mustExist = value.mustExist(); data.contains = value.contains(); data.includePlayerInventory = value.includePlayerInventory(); }
		else if (condition instanceof MacroCondition.Chat value) { data.type = "chat"; data.text = value.text(); data.contains = value.contains(); }
		else if (condition instanceof MacroCondition.World value) { data.type = "world"; data.mustBeInWorld = value.mustBeInWorld(); }
		else if (condition instanceof MacroCondition.All value) { data.type = "all"; data.children = snapshotConditions(value.children(), depth + 1); }
		else if (condition instanceof MacroCondition.Any value) { data.type = "any"; data.children = snapshotConditions(value.children(), depth + 1); }
		else if (condition instanceof MacroCondition.Not value) { data.type = "not"; data.child = snapshotCondition(value.child(), depth + 1); }
		else { data.type = "always"; data.expected = true; }
		return data;
	}

	private static List<ConditionData> snapshotConditions(List<MacroCondition> conditions, int depth) {
		List<ConditionData> result = new ArrayList<>();
		if (conditions == null || depth > 8) return result;
		for (MacroCondition condition : conditions) result.add(snapshotCondition(condition, depth));
		return result;
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
		if (data.uiCategory != null) {
			for (Category category : Category.values()) {
				if (category.name().equals(data.uiCategory)) {
					ClickGuiState.setCategory(category);
					break;
				}
			}
		}
		if (data.uiCollapsedGroups != null) {
			ClickGuiState.setCollapsedGroups(data.uiCollapsedGroups);
		}
		if (data.uiOpenModule == null) return;
		for (Module module : ModuleManager.modules()) {
			if (!module.name().equals(data.uiOpenModule) || !module.hasSettings()) continue;
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
		return module.name() + "." + settingName;
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
