package geiler.addons.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import geiler.addons.GeilerAddons;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.ModuleManager;
import geiler.addons.client.module.NumberSetting;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;

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
		/**
		 * Absent means "never saved", which is what seeds the built-in coordinates. An explicitly
		 * empty list is a list the user emptied out and must stay empty.
		 */
		List<int[]> tikiCoords;
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
			if (Boolean.TRUE.equals(data.enabled.get(module.name()))) {
				module.setEnabled(true);
			}
		}
		if (data.checkForUpdates != null) {
			checkForUpdates = data.checkForUpdates;
		}
		loadTikiCoords(data);
		loadUiState(data);
	}

	public static void save() {
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
		}
		data.tikiCoords = new ArrayList<>();
		for (BlockPos pos : TikiCoords.all()) {
			data.tikiCoords.add(new int[]{pos.getX(), pos.getY(), pos.getZ()});
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
