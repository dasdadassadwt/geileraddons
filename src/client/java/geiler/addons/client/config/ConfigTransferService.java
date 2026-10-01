package geiler.addons.client.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import geiler.addons.GeilerAddons;
import geiler.addons.client.dungeon.ClientJsonFile;
import geiler.addons.client.dungeon.DungeonGuideStore;
import geiler.addons.client.macro.MacroRunner;
import geiler.addons.client.module.impl.BlockEspModule;
import geiler.addons.client.module.impl.DungeonMobEspModule;
import geiler.addons.client.module.impl.InventoryButtonsModule;
import geiler.addons.client.module.impl.MacrosModule;
import geiler.addons.client.module.impl.MobHighlightModule;
import geiler.addons.client.module.Category;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.awt.Desktop;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Bounded full-profile archive, recovery and confirmed reset operations. */
public final class ConfigTransferService {
	private static final Path ROOT = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("geileraddons");
	private static final Path EXPORTS = ROOT.resolve("exports");
	private static final int MAX_ARCHIVE_BYTES = 32 * 1024 * 1024;
	private static final int MAX_ENTRY_BYTES = 8 * 1024 * 1024;
	private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;
	private static final int MAX_ENTRIES = 10_000;
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
	private static final AtomicBoolean BUSY = new AtomicBoolean();

	private ConfigTransferService() { }

	/** True while a whole-profile transaction is flushing, installing, or applying. */
	public static boolean isBusy() { return BUSY.get(); }
	public static Path configDirectory() { return ROOT; }
	public static Path exportDirectory() { return EXPORTS; }
	public static void openFolder(Path path) {
		try {
			if (path != null && Desktop.isDesktopSupported()) Desktop.getDesktop().open(path.toFile());
			else GeilerAddons.LOGGER.warn("No desktop file browser is available for {}", path);
		} catch (IOException | RuntimeException error) {
			GeilerAddons.LOGGER.warn("Could not open configuration folder {}", path, error);
		}
	}

	public enum ResetArea {
		MODULES("Module settings, enable switches, keybinds, Click GUI and theme"),
		MACROS("Macros, reusable functions, variables and folders"),
		MOB_ESP("Dungeon Mob ESP settings, Mob Highlight rules and Block ESP entries"),
		DUNGEON_GUIDES("Dungeon Guide routes, route settings and custom segments"),
		INVENTORY_BUTTONS("Inventory Button layout and custom PNG icons"),
		TIKI_DATA("Tiki coordinates and learned block fingerprints"),
		HUD_AND_TREE("HUD positions and Tree Tracker totals");

		private final String label;
		ResetArea(String label) { this.label = label; }
		public String label() { return label; }
	}

	public record ResetSelection(Set<ResetArea> areas) {
		public ResetSelection { areas = areas == null ? Set.of() : Set.copyOf(areas); }
		public static ResetSelection all() { return new ResetSelection(EnumSet.allOf(ResetArea.class)); }
		public boolean includes(ResetArea area) { return areas.contains(area); }
	}

	public record Result(boolean success, String message, Path path, Path recoveryPath) { }
	public record Profile(Path path, String name, int entries, long bytes, String createdAt) { }
	public record PreviewResult(boolean success, String message, Profile profile) { }

	/** Writes a complete snapshot and links the caller to the config folder. */
	public static void saveCurrent(Consumer<Result> callback) {
		if (!beginTransfer(callback)) return;
		try { prepareFlushOnClient(); }
		catch (Throwable error) { BUSY.set(false); callback.accept(new Result(false, message(error), null, null)); return; }
		CompletableFuture.supplyAsync(ConfigTransferService::flushAll).whenComplete((saved, failure) -> Minecraft.getInstance().execute(() -> {
			BUSY.set(false);
			Result result = failure != null ? new Result(false, "Configuration save failed: " + message(failure), null, null)
				: Boolean.TRUE.equals(saved) ? new Result(true, "Configuration and split collections saved.", ROOT, null)
				: new Result(false, "A configuration or dungeon data save failed; no success was reported.", null, null);
			callback.accept(result);
		}));
	}

	public static void exportProfile(String requestedName, Consumer<Result> callback) {
		if (!beginTransfer(callback)) return;
		try { prepareFlushOnClient(); }
		catch (Throwable error) { BUSY.set(false); callback.accept(new Result(false, message(error), null, null)); return; }
		String name = safeProfileName(requestedName);
		CompletableFuture.supplyAsync(() -> {
			try {
				if (!flushAll()) throw new IOException("Could not flush the current configuration.");
				return new Result(true, "Configuration exported.", writeArchive(uniqueArchiveName(name)).path(), null);
			}
			catch (Exception error) { return new Result(false, message(error), null, null); }
		}).whenComplete((result, failure) -> Minecraft.getInstance().execute(() -> {
			BUSY.set(false);
			callback.accept(failure == null ? result : new Result(false, message(failure), null, null));
		}));
	}

	public static void listProfiles(Consumer<List<Profile>> callback) {
		CompletableFuture.supplyAsync(() -> {
			List<Profile> result = new ArrayList<>();
			try {
				if (!Files.isDirectory(EXPORTS, LinkOption.NOFOLLOW_LINKS)) return List.<Profile>of();
				try (var paths = Files.list(EXPORTS)) {
					for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".gacfg.zip"))
						.sorted(Comparator.comparing(p -> p.getFileName().toString())).toList()) {
						try { result.add(readArchive(path).profile()); }
						catch (Exception ignored) { }
					}
				}
			} catch (IOException error) { GeilerAddons.LOGGER.warn("Could not list config exports", error); }
			return List.copyOf(result);
		}).whenComplete((profiles, failure) -> Minecraft.getInstance().execute(() -> callback.accept(
			failure == null && profiles != null ? profiles : List.of())));
	}

	public static void preview(Path path, Consumer<PreviewResult> callback) {
		CompletableFuture.supplyAsync(() -> {
			try { return new PreviewResult(true, "Profile is valid.", readArchive(path).profile()); }
			catch (Exception error) { return new PreviewResult(false, message(error), null); }
		}).whenComplete((result, failure) -> Minecraft.getInstance().execute(() -> callback.accept(failure == null && result != null
			? result : new PreviewResult(false, failure == null ? "Profile preview failed." : message(failure), null))));
	}

	/** Creates a recovery profile before replacing any active file; the whole archive is validated first. */
	public static void importProfile(Path path, Consumer<Result> callback) {
		if (!beginTransfer(callback)) return;
		try { prepareFlushOnClient(); }
		catch (Throwable error) { BUSY.set(false); callback.accept(new Result(false, message(error), null, null)); return; }
		AtomicReference<Path> recoveryArchive = new AtomicReference<>();
		CompletableFuture.supplyAsync(() -> {
			try {
				if (!flushAll()) throw new IOException("Could not flush current data before import.");
				Validated incoming = readArchive(path);
				Path backup = writeArchive(uniqueArchiveName("recovery-before-import")).path();
				recoveryArchive.set(backup);
				Validated recovery = readArchive(backup);
				if (!installWithRollback(incoming.files(), recovery.files()).installed())
					return new Result(false, "Import failed; recovery was attempted.", null, backup);
				return new Result(true, "Profile files installed.", path, backup);
			} catch (Exception error) { return new Result(false, message(error), null, recoveryArchive.get()); }
		}).whenComplete((result, failure) -> Minecraft.getInstance().execute(() -> {
			Result completed = failure != null || result == null ? new Result(false,
				failure == null ? "Import failed unexpectedly." : message(failure), null, recoveryArchive.get()) : result;
			if (!completed.success()) { BUSY.set(false); callback.accept(completed); return; }
			Result applied;
			try {
				reloadInstalledProfile();
				ModConfig.markDirty();
				ModConfig.save();
				applied = new Result(true, "Configuration imported and applied.", completed.path(), completed.recoveryPath());
			} catch (Throwable error) {
				rollbackAndReload(completed.recoveryPath(), callback, "Imported profile could not be applied: " + message(error));
				return;
			}
			BUSY.set(false);
			callback.accept(applied);
		}));
	}

	/** Called only after the user has passed the large final confirmation screen. */
	public static void reset(ResetSelection selection, Consumer<Result> callback) {
		if (selection == null || selection.areas().isEmpty()) {
			callback.accept(new Result(false, "Select at least one configuration area to reset.", null, null)); return;
		}
		if (!beginTransfer(callback)) return;
		try { prepareFlushOnClient(); }
		catch (Throwable error) { BUSY.set(false); callback.accept(new Result(false, message(error), null, null)); return; }
		CompletableFuture.runAsync(() -> {
			if (!flushAll()) throw new java.util.concurrent.CompletionException(new IOException("Could not flush current data before reset."));
		}).whenComplete((nothing, flushFailure) -> Minecraft.getInstance().execute(() -> {
			if (flushFailure != null) { BUSY.set(false); callback.accept(new Result(false, message(flushFailure), null, null)); return; }
			CompletableFuture.supplyAsync(() -> {
				try { return writeArchive(uniqueArchiveName("recovery-before-reset")); }
				catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
			}).whenComplete((backup, backupFailure) -> Minecraft.getInstance().execute(() -> {
				if (backupFailure != null) { BUSY.set(false); callback.accept(new Result(false, message(backupFailure), null, null)); return; }
				try {
					applyReset(selection);
					prepareFlushOnClient();
					CompletableFuture.runAsync(() -> {
						try {
							if (!flushAll()) throw new IOException("The reset could not be saved.");
							if (selection.includes(ResetArea.INVENTORY_BUTTONS)) deleteIconDirectory();
						} catch (IOException error) { throw new java.util.concurrent.CompletionException(error); }
					}).whenComplete((ignored, saveFailure) -> Minecraft.getInstance().execute(() -> {
						if (saveFailure == null) { BUSY.set(false); callback.accept(new Result(true,
							"Selected configuration reset. A recovery profile was created first.", null, backup.path())); }
						else rollbackAndReload(backup.path(), callback, "Reset save failed: " + message(saveFailure));
					}));
				} catch (Throwable error) {
					rollbackAndReload(backup.path(), callback, "Reset could not be completed: " + message(error));
				}
			}));
		}));
	}

	private static void applyReset(ResetSelection selection) throws IOException {
		Set<String> mobModuleNames = Set.of(DungeonMobEspModule.INSTANCE.configName(),
			MobHighlightModule.INSTANCE.configName(), BlockEspModule.INSTANCE.configName());
		ConfigResetPlan.Plan resetPlan = ConfigResetPlan.create(selection.includes(ResetArea.MODULES),
			selection.includes(ResetArea.MACROS), selection.includes(ResetArea.MOB_ESP),
			MacrosModule.INSTANCE.configName(), mobModuleNames);
		if (resetPlan.resetAllModules()) {
			ModConfig.resetModuleDefaultsExcept(resetPlan.excludedModules());
			resetClickGuiState();
		} else if (!resetPlan.namedModules().isEmpty()) ModConfig.resetNamedModuleDefaults(resetPlan.namedModules());
		if (selection.includes(ResetArea.MACROS)) {
			MacroRunner.cancel("macro configuration reset");
			MacrosModule.INSTANCE.restore(List.of());
			MacrosModule.INSTANCE.restoreFunctions(List.of());
			MacrosModule.INSTANCE.globalVariables().restore(List.of());
			MacrosModule.INSTANCE.folders().restore(List.of());
		}
		if (selection.includes(ResetArea.MOB_ESP)) {
			MobHighlightModule.INSTANCE.restore(List.of());
			MobHighlightModule.INSTANCE.folders().restore(List.of());
			BlockEspModule.INSTANCE.restore(List.of());
			BlockEspModule.INSTANCE.folders().restore(List.of());
		}
		if (selection.includes(ResetArea.DUNGEON_GUIDES) && !DungeonGuideStore.resetToFactoryDefaults())
			throw new IOException("Dungeon Guide data is blocked or could not be saved safely.");
		if (selection.includes(ResetArea.INVENTORY_BUTTONS)) {
			InventoryButtonsModule.INSTANCE.restore(List.of());
			geiler.addons.client.gui.InventoryButtonOverlay.clearIconTextureCache();
		}
		if (selection.includes(ResetArea.TIKI_DATA)) {
			geiler.addons.client.config.TikiCoords.seedDefaults();
			geiler.addons.client.config.TikiFingerprints.replaceAll(Map.of());
		}
		if (selection.includes(ResetArea.HUD_AND_TREE)) {
			geiler.addons.client.hud.HudManager.resetPositions();
			for (geiler.addons.client.tree.TreeType type : geiler.addons.client.tree.TreeType.values()) {
				geiler.addons.client.module.impl.TreeTrackerModule.INSTANCE.tracker().stats(type).restore(0, 0, 0, 0);
			}
		}
		ModConfig.saveAfterReset();
	}

	private static void resetClickGuiState() {
		ClickGuiState.setCategory(Category.values()[0]);
		ClickGuiState.setOpenModule(null);
		ClickGuiState.setExpandedColor(null);
		ClickGuiState.setSettingsScroll(0);
		ClickGuiState.setCategoryScroll(0);
		for (Category category : Category.values()) ClickGuiState.setGridScroll(category, 0);
		ClickGuiState.setFavoriteCategoryIds(List.of());
		ClickGuiState.setFavoriteModuleIds(List.of());
	}

	private static boolean flushAll() {
		try {
			boolean configSaved = ModConfig.flushPrepared();
			boolean guidesSaved = DungeonGuideStore.flushAndReport();
			ClientJsonFile.flush();
			return configSaved && guidesSaved;
		} catch (RuntimeException error) {
			GeilerAddons.LOGGER.error("Could not flush configuration before transfer", error);
			return false;
		}
	}

	private static boolean beginTransfer(Consumer<Result> callback) {
		if (!BUSY.compareAndSet(false, true)) {
			callback.accept(new Result(false, "Another configuration operation is already in progress.", null, null));
			return false;
		}
		return true;
	}

	private static void prepareFlushOnClient() {
		ModConfig.save();
		DungeonGuideStore.prepareFlush();
	}

	private static void reloadInstalledProfile() throws IOException {
		geiler.addons.client.gui.InventoryButtonOverlay.clearIconTextureCache();
		ModConfig.resetRuntimeToFactoryDefaults();
		ModConfig.load();
		geiler.addons.client.module.impl.VisualModule.INSTANCE.refreshTheme();
		if (!DungeonGuideStore.reloadFromDisk()) throw new IOException("Dungeon Guide profile data could not be loaded.");
	}

	private static void rollbackAndReload(Path backup, Consumer<Result> callback, String failure) {
		if (backup == null) { BUSY.set(false); callback.accept(new Result(false, failure + " Recovery archive is unavailable.", null, null)); return; }
		CompletableFuture.supplyAsync(() -> {
			try { return installWithRollback(readArchive(backup).files(), Map.of()).installed(); }
			catch (Exception error) { return false; }
		}).whenComplete((restored, restoreFailure) -> Minecraft.getInstance().execute(() -> {
			boolean finalRestored = restoreFailure == null && Boolean.TRUE.equals(restored);
			if (finalRestored) {
				try { reloadInstalledProfile(); }
				catch (Throwable error) { finalRestored = false; }
			}
			BUSY.set(false);
			callback.accept(new Result(false, failure + (finalRestored ? " Recovery profile restored." :
				" Restore failed; recovery archive remains at " + backup), null, backup));
		}));
	}

	private static Profile writeArchive(String baseName) throws IOException {
		Map<String, byte[]> files = collectCurrentFiles();
		if (!files.containsKey("config.json")) throw new IOException("The active config.json snapshot is missing.");
		byte[] archive = ConfigProfileCodec.encode(files);
		ConfigProfileCodec.Archive checked = ConfigProfileCodec.decode(archive);
		ensureSafeRoot();
		ConfigPathGuard.Root rootGuard = ConfigPathGuard.checkedDirectory(ROOT);
		Files.createDirectories(EXPORTS);
		ConfigPathGuard.Root exportsGuard = ConfigPathGuard.checkedDirectory(EXPORTS);
		ConfigPathGuard.assertContained(rootGuard, EXPORTS);
		Path target = EXPORTS.resolve(baseName + ".gacfg.zip").normalize();
		if (!target.getParent().equals(EXPORTS.normalize())) throw new IOException("Unsafe export path.");
		Path temporary = Files.createTempFile(EXPORTS, "profile-", ".tmp");
		try {
			ConfigPathGuard.assertContained(exportsGuard, temporary);
			Files.write(temporary, archive);
			if (Files.size(temporary) > MAX_ARCHIVE_BYTES) throw new IOException("Profile archive exceeds the 32 MB limit.");
			int suffix = 2;
			while (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
				target = EXPORTS.resolve(baseName + "-" + suffix++ + ".gacfg.zip");
			}
			ConfigPathGuard.rejectReparseChain(target);
			moveAtomic(temporary, target);
			ConfigPathGuard.assertContained(exportsGuard, target);
			return new Profile(target, target.getFileName().toString(), files.size(), checked.totalBytes(), checked.createdAt());
		} finally {
			// Do not follow a path that may have been swapped to a junction during archive writing.
			if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
				try {
					ConfigPathGuard.assertContained(exportsGuard, temporary);
					Files.deleteIfExists(temporary);
				} catch (IOException unsafeCleanup) {
					GeilerAddons.LOGGER.warn("Leaving unsafe config-profile temp file in place rather than deleting outside the exports folder", unsafeCleanup);
				}
			}
		}
	}

	private static Map<String, byte[]> collectCurrentFiles() throws IOException {
		Map<String, byte[]> files = new LinkedHashMap<>();
		ensureSafeRoot();
		if (!Files.exists(ROOT, LinkOption.NOFOLLOW_LINKS)) return files;
		ConfigPathGuard.Root rootGuard = ConfigPathGuard.checkedDirectory(ROOT);
		Files.walkFileTree(ROOT, new SimpleFileVisitor<>() {
			@Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				ConfigPathGuard.assertSafe(dir, attrs);
				ConfigPathGuard.assertContained(rootGuard, dir);
				if (!dir.equals(ROOT) && dir.startsWith(EXPORTS)) return FileVisitResult.SKIP_SUBTREE;
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				ConfigPathGuard.assertSafe(file, attrs);
				ConfigPathGuard.assertContained(rootGuard, file);
				if (!attrs.isRegularFile()) throw new IOException("Only regular config files can be exported.");
				String relative = ROOT.relativize(file).toString().replace('\\', '/');
				if (relative.startsWith("exports/")) return FileVisitResult.CONTINUE;
				if (!safePersistentPath(relative)) throw new IOException("Unrecognized persistent config file: " + relative);
				long size = attrs.size();
				if (size < 0 || size > MAX_ENTRY_BYTES) throw new IOException("Config entry exceeds the 8 MB limit: " + relative);
				byte[] bytes = Files.readAllBytes(file);
				if (bytes.length != size) throw new IOException("Config entry changed while it was being read: " + relative);
				files.put(relative, bytes);
				if (files.size() > MAX_ENTRIES) throw new IOException("Too many config files to export.");
				long total = 0;
				for (byte[] value : files.values()) total += value.length;
				if (total > MAX_TOTAL_BYTES) throw new IOException("Config files exceed the 64 MB expanded-size limit.");
				return FileVisitResult.CONTINUE;
			}
		});
		return files;
	}

	private static Validated readArchive(Path path) throws IOException {
		Path safe = validateProfilePath(path);
		long zipSize = Files.size(safe);
		if (zipSize <= 0 || zipSize > MAX_ARCHIVE_BYTES) throw new IOException("Archive size is outside the 32 MB limit.");
		byte[] archive = Files.readAllBytes(safe);
		ConfigProfileCodec.Archive payload = ConfigProfileCodec.decode(archive);
		validateConfigSchema(payload.files());
		return new Validated(new Profile(safe, safe.getFileName().toString(), payload.files().size(),
			payload.totalBytes(), payload.createdAt()), payload.files());
	}

	private static void validateConfigSchema(Map<String, byte[]> files) throws IOException {
		JsonObject config = parseObject(files.get("config.json"), "config.json");
		requirePrimitiveMap(config, "enabled", PrimitiveKind.BOOLEAN);
		requirePrimitiveMap(config, "toggles", PrimitiveKind.BOOLEAN);
		requirePrimitiveMap(config, "choices", PrimitiveKind.STRING);
		requirePrimitiveMap(config, "texts", PrimitiveKind.STRING);
		requirePrimitiveMap(config, "numbers", PrimitiveKind.NUMBER);
		requireColorMap(config, "colors");
		JsonObject keybinds = requiredObject(config, "keybinds", 4096);
		for (Map.Entry<String, JsonElement> item : keybinds.entrySet()) {
			if (!item.getValue().isJsonObject()) throw new IOException("Config keybind is not an object.");
			JsonObject bind = item.getValue().getAsJsonObject();
			if (!stringPrimitive(bind.get("key")) || !numberPrimitive(bind.get("modifiers")))
				throw new IOException("Config keybind has invalid key or modifier fields.");
		}
		validateOptionalConfigData(config);
		byte[] indexBytes = files.get("macro-index.json");
		if (indexBytes != null) {
			JsonObject index = parseObject(indexBytes, "macro-index.json");
			if (intValue(index, "version") != 1 || !index.has("macros") || !index.get("macros").isJsonArray()
				|| !index.has("functions") || !index.get("functions").isJsonArray()
				|| index.getAsJsonArray("macros").size() > 2000 || index.getAsJsonArray("functions").size() > 64)
				throw new IOException("Macro index schema is invalid.");
			validateMacroRefs(files, index.getAsJsonArray("macros"), "macros/");
			validateMacroRefs(files, index.getAsJsonArray("functions"), "functions/");
		}
		for (String name : files.keySet()) {
			if ((name.startsWith("macros/") || name.startsWith("functions/")) && indexBytes == null)
				throw new IOException("Split macro data is present without its index.");
		}
		validateGuideData(files);
	}

	private static void validateOptionalConfigData(JsonObject config) throws IOException {
		optionalArray(config, "tikiCoords", 4096);
		optionalArray(config, "mobHighlights", 256);
		optionalArray(config, "blockEspEntries", 256);
		optionalArray(config, "macroFolders", 4096);
		optionalArray(config, "mobHighlightFolders", 4096);
		optionalArray(config, "blockEspFolders", 4096);
		optionalArray(config, "inventoryButtons", 256);
		optionalArray(config, "macros", 2000);
		optionalArray(config, "macroFunctions", 64);
		optionalArray(config, "macroVariables", 512);
		optionalMap(config, "tikiFingerprints", 8192, PrimitiveKind.STRING);
		optionalMap(config, "uiGridScrollByCategory", 64, PrimitiveKind.NUMBER);
		optionalMap(config, "uiGridScrolls", 64, PrimitiveKind.NUMBER);
		optionalString(config, "uiCategory"); optionalString(config, "uiOpenModule"); optionalString(config, "uiExpandedColor");
		optionalNumber(config, "uiSettingsScroll"); optionalNumber(config, "uiCategoryScroll");
		for (String key : List.of("checkForUpdates", "hypixelModApi"))
			if (config.has(key) && !config.get(key).isJsonNull() && !booleanPrimitive(config.get(key))) throw new IOException("Config " + key + " flag is invalid.");
		for (String key : List.of("uiFavoriteCategories", "uiFavoriteModules")) {
			JsonArray values = optionalArray(config, key, 4096);
			if (values != null) for (JsonElement value : values) if (!stringPrimitive(value)) throw new IOException("Config favorite id is not text.");
		}
		for (String key : List.of("tikiCoords", "macroFolders", "mobHighlightFolders", "blockEspFolders", "inventoryButtons", "mobHighlights", "blockEspEntries", "macros", "macroFunctions", "macroVariables")) {
			JsonArray values = optionalArray(config, key, Integer.MAX_VALUE);
			if (values == null) continue;
			for (JsonElement value : values) if (!value.isJsonObject() && !value.isJsonArray() && !value.isJsonPrimitive())
				throw new IOException("Config " + key + " entry has an invalid JSON type.");
		}
		JsonArray coords = optionalArray(config, "tikiCoords", 4096);
		if (coords != null) for (JsonElement coord : coords) if (!coord.isJsonArray() || coord.getAsJsonArray().size() != 3
			|| coord.getAsJsonArray().asList().stream().anyMatch(value -> !numberPrimitive(value))) throw new IOException("Tiki coordinate is invalid.");
		JsonObject hud = optionalObject(config, "hudPositions", 256);
		if (hud != null) for (Map.Entry<String, JsonElement> item : hud.entrySet())
			if (!item.getValue().isJsonArray() || item.getValue().getAsJsonArray().size() != 2
				|| item.getValue().getAsJsonArray().asList().stream().anyMatch(value -> !numberPrimitive(value))) throw new IOException("HUD position is invalid.");
		JsonObject trees = optionalObject(config, "treeGifts", 64);
		if (trees != null) for (Map.Entry<String, JsonElement> item : trees.entrySet())
			if (!item.getValue().isJsonArray() || item.getValue().getAsJsonArray().size() != 4
				|| item.getValue().getAsJsonArray().asList().stream().anyMatch(value -> !numberPrimitive(value))) throw new IOException("Tree Tracker totals are invalid.");
	}

	private static void validateGuideData(Map<String, byte[]> files) throws IOException {
		byte[] indexBytes = files.get("dungeon-guides/index.json");
		if (indexBytes == null) return;
		JsonObject index = parseObject(indexBytes, "Dungeon Guide index");
		int version = intValue(index, "version");
		if ((version != 1 && version != 2) || !index.has("routes") || !index.get("routes").isJsonArray()
			|| index.getAsJsonArray("routes").size() > ClientJsonFile.MAX_ENTRIES) throw new IOException("Dungeon Guide index schema is invalid.");
		Set<String> routeFiles = new HashSet<>();
		for (JsonElement routeElement : index.getAsJsonArray("routes")) {
			if (!routeElement.isJsonObject()) throw new IOException("Dungeon Guide route entry is invalid.");
			JsonObject route = routeElement.getAsJsonObject();
			String floor = stringValue(route, "floor"), phase = stringValue(route, "phase"), name = stringValue(route, "file");
			if (floor == null || !floor.matches("(?:Entrance|F[1-7]|M[1-7])") || phase == null || !phase.matches("[A-Z][A-Z0-9_]{0,31}")
				|| name == null || !name.matches("[a-z0-9._-]{1,48}-[0-9a-z]+\\.json")) throw new IOException("Dungeon Guide route reference is invalid.");
			String member = "dungeon-guides/" + floor + "/" + phase + "/" + name;
			if (!routeFiles.add(member) || !files.containsKey(member)) throw new IOException("Dungeon Guide route file is duplicated or missing.");
			JsonObject routeFile = parseObject(files.get(member), member);
			if (intValue(routeFile, "version") != 1 || !routeFile.has("nodes") || !routeFile.get("nodes").isJsonArray()
				|| routeFile.getAsJsonArray("nodes").size() > ClientJsonFile.MAX_ENTRIES) throw new IOException("Dungeon Guide route file schema is invalid.");
			for (JsonElement node : routeFile.getAsJsonArray("nodes")) if (!node.isJsonObject()
				|| !stringPrimitive(node.getAsJsonObject().get("id")) || !stringPrimitive(node.getAsJsonObject().get("floor"))
				|| !stringPrimitive(node.getAsJsonObject().get("phase")) || !stringPrimitive(node.getAsJsonObject().get("routeName")))
				throw new IOException("Dungeon Guide node schema is invalid.");
		}
	}

	private enum PrimitiveKind { BOOLEAN, NUMBER, STRING }
	private static void requirePrimitiveMap(JsonObject root, String key, PrimitiveKind kind) throws IOException {
		JsonObject object = requiredObject(root, key, 4096);
		for (Map.Entry<String, JsonElement> item : object.entrySet()) if (!primitive(item.getValue(), kind))
			throw new IOException("Config map " + key + " contains a value of the wrong type.");
	}
	private static JsonObject requiredObject(JsonObject root, String key, int limit) throws IOException {
		if (!root.has(key) || !root.get(key).isJsonObject() || root.getAsJsonObject(key).size() > limit) throw new IOException("Config has an invalid " + key + " map.");
		return root.getAsJsonObject(key);
	}
	private static JsonObject optionalObject(JsonObject root, String key, int limit) throws IOException {
		if (!root.has(key) || root.get(key).isJsonNull()) return null;
		if (!root.get(key).isJsonObject() || root.getAsJsonObject(key).size() > limit) throw new IOException("Config has an invalid " + key + " map.");
		return root.getAsJsonObject(key);
	}
	private static JsonArray optionalArray(JsonObject root, String key, int limit) throws IOException {
		if (!root.has(key) || root.get(key).isJsonNull()) return null;
		if (!root.get(key).isJsonArray() || root.getAsJsonArray(key).size() > limit) throw new IOException("Config has an invalid " + key + " list.");
		return root.getAsJsonArray(key);
	}
	private static void optionalMap(JsonObject root, String key, int limit, PrimitiveKind kind) throws IOException {
		JsonObject object = optionalObject(root, key, limit);
		if (object != null) for (JsonElement value : object.asMap().values()) if (!primitive(value, kind)) throw new IOException("Config map " + key + " has a value of the wrong type.");
	}
	private static void optionalString(JsonObject root, String key) throws IOException { if (root.has(key) && !root.get(key).isJsonNull() && !stringPrimitive(root.get(key))) throw new IOException("Config " + key + " must be text."); }
	private static void optionalNumber(JsonObject root, String key) throws IOException { if (root.has(key) && !root.get(key).isJsonNull() && !numberPrimitive(root.get(key))) throw new IOException("Config " + key + " must be numeric."); }
	private static boolean primitive(JsonElement value, PrimitiveKind kind) { return switch (kind) { case BOOLEAN -> booleanPrimitive(value); case NUMBER -> numberPrimitive(value); case STRING -> stringPrimitive(value); }; }
	private static boolean booleanPrimitive(JsonElement value) { return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean(); }
	private static boolean numberPrimitive(JsonElement value) { if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return false; double n = value.getAsDouble(); return Double.isFinite(n); }
	private static boolean stringPrimitive(JsonElement value) { return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(); }
	private static void requireColorMap(JsonObject root, String key) throws IOException {
		JsonObject colors = requiredObject(root, key, 4096);
		for (JsonElement value : colors.asMap().values()) {
			if (!value.isJsonArray() || value.getAsJsonArray().size() != 4) throw new IOException("Config color entry must contain RGBA channels.");
			for (JsonElement channel : value.getAsJsonArray()) if (!numberPrimitive(channel) || channel.getAsInt() < 0 || channel.getAsInt() > 255)
				throw new IOException("Config color channel is invalid.");
		}
	}
	private static String stringValue(JsonObject object, String key) { return stringPrimitive(object.get(key)) ? object.get(key).getAsString() : null; }

	private static void validateMacroRefs(Map<String, byte[]> files, JsonArray names, String prefix) throws IOException {
		Set<String> unique = new HashSet<>();
		for (JsonElement element : names) {
			if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) throw new IOException("Macro index has a non-text file reference.");
			String name = element.getAsString();
			if (!name.matches("[A-Za-z0-9._-]{1,128}\\.json") || !unique.add(name)
				|| !files.containsKey(prefix + name)) throw new IOException("Macro index references a missing or unsafe file.");
			JsonObject data = parseObject(files.get(prefix + name), prefix + name);
			if (prefix.equals("macros/")) {
				if (!numberPrimitive(data.get("id")) || !stringPrimitive(data.get("name"))
					|| !data.has("steps") || !data.get("steps").isJsonArray() || data.getAsJsonArray("steps").size() > 4096)
					throw new IOException("Macro record schema is invalid.");
			} else if (!stringPrimitive(data.get("id")) || !stringPrimitive(data.get("name"))
				|| !data.has("parameters") || !data.get("parameters").isJsonArray() || data.getAsJsonArray("parameters").size() > 32
				|| !data.has("steps") || !data.get("steps").isJsonArray() || data.getAsJsonArray("steps").size() > 4096) {
				throw new IOException("Reusable function record schema is invalid.");
			}
		}
	}

	private static JsonObject parseObject(byte[] bytes, String name) throws IOException {
		try {
			ConfigProfileCodec.validateJsonBounds(bytes);
			JsonElement parsed = JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
			if (!parsed.isJsonObject()) throw new IOException(name + " must contain a JSON object.");
			return parsed.getAsJsonObject();
		} catch (RuntimeException | StackOverflowError error) { throw new IOException(name + " is invalid JSON.", error); }
	}

	private static InstallResult installWithRollback(Map<String, byte[]> incoming, Map<String, byte[]> backup) {
		try { ensureSafeRoot(); }
		catch (IOException error) { GeilerAddons.LOGGER.error("Config transfer root is not safe", error); return new InstallResult(false, false); }
		ConfigProfileInstaller.Result result = ConfigProfileInstaller.install(ROOT, incoming, backup,
			ConfigProfileCodec::safePath, ConfigTransferService::validateConfigSchema, null);
		return new InstallResult(result.installed(), result.recoveryRestored());
	}

	private static void deleteIconDirectory() throws IOException {
		Path icons = InventoryButtonsModule.INSTANCE.iconDirectory().toAbsolutePath().normalize();
		Path configRoot = ROOT.toAbsolutePath().normalize();
		if (!icons.startsWith(configRoot) || !icons.equals(configRoot.resolve("inventory-button-icons")))
			throw new IOException("Inventory icon directory failed its safety check.");
		ensureSafeRoot();
		if (Files.exists(icons, LinkOption.NOFOLLOW_LINKS)) {
			ConfigPathGuard.Root configGuard = ConfigPathGuard.checkedDirectory(configRoot);
			ConfigPathGuard.assertContained(configGuard, icons);
		}
		deleteTree(icons);
	}

	private static void deleteTree(Path root) throws IOException {
		if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
		ConfigPathGuard.rejectReparseChain(root);
		ConfigPathGuard.Root rootGuard = ConfigPathGuard.checkedDirectory(root);
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				ConfigPathGuard.assertSafe(file, attrs);
				ConfigPathGuard.assertContained(rootGuard, file);
				if (!attrs.isRegularFile()) throw new IOException("Refusing to remove a non-regular file.");
				Files.delete(file); return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				ConfigPathGuard.assertSafe(dir, attrs); ConfigPathGuard.assertContained(rootGuard, dir); return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
				if (error != null) throw error;
				ConfigPathGuard.assertContained(rootGuard, dir);
				Files.delete(dir); return FileVisitResult.CONTINUE;
			}
		});
	}

	private static Path validateProfilePath(Path path) throws IOException {
		if (path == null) throw new IOException("No profile was selected.");
		Path absolute = path.toAbsolutePath().normalize();
		Path exportRoot = EXPORTS.toAbsolutePath().normalize();
		ensureSafeRoot();
		ConfigPathGuard.Root rootGuard = ConfigPathGuard.checkedDirectory(ROOT);
		ConfigPathGuard.Root exportsGuard = ConfigPathGuard.checkedDirectory(exportRoot);
		ConfigPathGuard.assertContained(rootGuard, exportRoot);
		if (!absolute.startsWith(exportRoot) || !absolute.getParent().equals(exportRoot))
			throw new IOException("Profiles can be imported only from the GeilerAddons exports folder.");
		ConfigPathGuard.assertContained(exportsGuard, absolute);
		if (!ConfigPathGuard.readExisting(absolute).isRegularFile()) throw new IOException("Profile is not a regular file.");
		return absolute;
	}

	private static void ensureSafeRoot() throws IOException {
		Path configDir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().toAbsolutePath().normalize();
		ConfigPathGuard.Root configGuard = ConfigPathGuard.checkedDirectory(configDir);
		ConfigPathGuard.rejectReparseChain(ROOT);
		if (Files.exists(ROOT, LinkOption.NOFOLLOW_LINKS)) {
			ConfigPathGuard.Root rootGuard = ConfigPathGuard.checkedDirectory(ROOT);
			ConfigPathGuard.assertContained(configGuard, ROOT);
		} else return;
		ConfigPathGuard.rejectReparseChain(EXPORTS);
		if (Files.exists(EXPORTS, LinkOption.NOFOLLOW_LINKS)) {
			ConfigPathGuard.Root rootGuard = ConfigPathGuard.checkedDirectory(ROOT);
			ConfigPathGuard.assertContained(rootGuard, EXPORTS);
			if (!ConfigPathGuard.readExisting(EXPORTS).isDirectory()) throw new IOException("The exports path is not a directory.");
		}
	}

	private static boolean safePersistentPath(String path) { return ConfigProfileCodec.safePath(path); }

	private static String safeProfileName(String requested) {
		String value = requested == null ? "" : requested.trim().replaceAll("[^A-Za-z0-9._-]+", "-");
		while (value.startsWith(".")) value = value.substring(1);
		if (value.isBlank()) value = "geileraddons-" + LocalDateTime.now().format(STAMP);
		return value.length() > 64 ? value.substring(0, 64) : value;
	}

	private static String uniqueArchiveName(String requested) { return safeProfileName(requested); }
	private static int intValue(JsonObject object, String key) {
		try { return object.get(key).getAsInt(); } catch (RuntimeException error) { return -1; }
	}
	private static void moveAtomic(Path source, Path target) throws IOException {
		try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
		catch (AtomicMoveNotSupportedException unsupported) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
	}
	private static String message(Throwable error) {
		Throwable cause = error;
		while (cause.getCause() != null) cause = cause.getCause();
		return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
	}

	private record Validated(Profile profile, Map<String, byte[]> files) { }
	private record InstallResult(boolean installed, boolean recoveryRestored) { }
}
