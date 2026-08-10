package geiler.addons.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import geiler.addons.client.module.BooleanSetting;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Persists module enabled-state, settings and the Tiki coordinate list across restarts. */
public final class ModConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("geileraddons.json");

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
		loadTikiCoords(data);
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
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH)) {
				GSON.toJson(data, writer);
			}
		} catch (IOException e) {
			throw new RuntimeException("Failed to save GeilerAddons config to " + PATH, e);
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

	private static Data readData() {
		if (!Files.exists(PATH)) {
			return new Data();
		}
		Data data;
		try (Reader reader = Files.newBufferedReader(PATH)) {
			data = GSON.fromJson(reader, Data.class);
		} catch (IOException e) {
			throw new RuntimeException("Failed to load GeilerAddons config from " + PATH, e);
		}
		if (data == null) {
			return new Data();
		}
		// Gson leaves absent fields at their initializers but writes a literal null straight
		// through, so normalize the maps before anything reads them.
		if (data.enabled == null) data.enabled = new HashMap<>();
		if (data.colors == null) data.colors = new HashMap<>();
		if (data.numbers == null) data.numbers = new HashMap<>();
		if (data.toggles == null) data.toggles = new HashMap<>();
		return data;
	}

	private static String settingKey(Module module, String settingName) {
		return module.name() + "." + settingName;
	}
}
