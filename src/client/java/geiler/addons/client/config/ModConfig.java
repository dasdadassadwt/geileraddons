package geiler.addons.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import geiler.addons.GeilerAddons;
import geiler.addons.client.hud.HudManager;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.ModuleManager;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.TextSetting;
import geiler.addons.client.module.impl.TreeTrackerModule;
import geiler.addons.client.tree.TreeStats;
import geiler.addons.client.tree.TreeType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
		Map<String, String> texts = new HashMap<>();
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
		/** Click GUI view state - see {@link ClickGuiState}. */
		String uiCategory;
		String uiOpenModule;
		String uiExpandedColor;
		int uiSettingsScroll;
		/** Folded-shut settings sections, as "module.group" keys. */
		List<String> uiCollapsedGroups;
		/** Absent means "never saved", which keeps the check on by default. */
		Boolean checkForUpdates;
	}

	/**
	 * Whether the mod may contact GitHub once per launch to see if a newer release exists.
	 * Config-file only: it is the mod's single outbound request and belongs with the other
	 * one-off preferences rather than in a module's settings panel.
	 */
	private static boolean checkForUpdates = true;

	/** How long a debounced change may sit unwritten; a crash can cost at most this much of it. */
	private static final long FLUSH_INTERVAL_MILLIS = 60_000;

	private static boolean dirty;
	private static long lastFlush;

	public static boolean checkForUpdates() {
		return checkForUpdates;
	}

	public static void load() {
		Data data = readData();
		for (Module module : ModuleManager.modules()) {
			for (ColorSetting setting : module.colorSettings()) {
				int[] rgba = data.colors.get(settingKey(module, setting.name()));
				if (rgba != null && rgba.length == 4) {
					setting.set(rgba[0], rgba[1], rgba[2], rgba[3]);
				}
			}
			for (NumberSetting setting : module.numberSettings()) {
				Float value = data.numbers.get(settingKey(module, setting.name()));
				if (value != null) {
					setting.setValue(value);
				}
			}
			for (BooleanSetting setting : module.booleanSettings()) {
				Boolean value = data.toggles.get(settingKey(module, setting.name()));
				if (value != null) {
					setting.setValue(value);
				}
			}
			for (TextSetting setting : module.textSettings()) {
				String value = data.texts.get(settingKey(module, setting.name()));
				if (value != null) {
					setting.setValue(value);
				}
			}
			if (Boolean.TRUE.equals(data.enabled.get(module.name()))) {
				module.setEnabled(true);
			}
		}
		if (data.checkForUpdates != null) {
			checkForUpdates = data.checkForUpdates;
		}
		loadTikiCoords(data);
		loadTikiFingerprints(data);
		if (data.hudPositions != null) {
			HudManager.restore(data.hudPositions);
		}
		loadTreeGifts(data);
		loadUiState(data);
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
		dirty = true;
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
		if (dirty) {
			save();
		}
	}

	public static void save() {
		dirty = false;
		lastFlush = System.currentTimeMillis();
		Data data = new Data();
		for (Module module : ModuleManager.modules()) {
			data.enabled.put(module.name(), module.isEnabled());
			for (ColorSetting setting : module.colorSettings()) {
				data.colors.put(settingKey(module, setting.name()), new int[]{setting.red(), setting.green(), setting.blue(), setting.alpha()});
			}
			for (NumberSetting setting : module.numberSettings()) {
				data.numbers.put(settingKey(module, setting.name()), setting.value());
			}
			for (BooleanSetting setting : module.booleanSettings()) {
				data.toggles.put(settingKey(module, setting.name()), setting.value());
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
		data.uiCategory = ClickGuiState.category().name();
		data.uiOpenModule = ClickGuiState.openModule() == null ? null : ClickGuiState.openModule().name();
		data.uiExpandedColor = ClickGuiState.expandedColor() == null ? null : ClickGuiState.expandedColor().name();
		data.uiSettingsScroll = ClickGuiState.settingsScroll();
		data.uiCollapsedGroups = new ArrayList<>(ClickGuiState.collapsedGroups());
		data.checkForUpdates = checkForUpdates;
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH)) {
				GSON.toJson(data, writer);
			}
		} catch (IOException e) {
			// Never propagated: save() runs from screen teardown and from every setting toggle, so
			// a read-only config dir or a full disk would otherwise take the screen down with it.
			GeilerAddons.LOGGER.error("Failed to save GeilerAddons config to {}", PATH, e);
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
		if (data.texts == null) data.texts = new HashMap<>();

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
}
