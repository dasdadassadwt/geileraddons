package geiler.addons.client.config;

import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.NumberSetting;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Isolated archive, reset-scope, and transactional config-install checks. */
public final class ConfigProfileChecks {
	private ConfigProfileChecks() { }

	public static void run() {
		checkArchiveRoundTripAndValidation();
		checkResponsiveScreenGeometry();
		checkResetExclusions();
		checkTransactionalInstallAndRollback();
		checkReparsePointGuard();
		checkSettingsRestoreConstructorDefaults();
	}

	private static void checkArchiveRoundTripAndValidation() {
		Map<String, byte[]> files = sampleProfile();
		try {
			byte[] encoded = ConfigProfileCodec.encode(files);
			ConfigProfileCodec.Archive decoded = ConfigProfileCodec.decode(encoded);
			assertTrue(decoded.files().keySet().equals(files.keySet()), "profile archive round-trips every payload member");
			for (String name : files.keySet()) assertTrue(java.util.Arrays.equals(files.get(name), decoded.files().get(name)), "profile member bytes survive round-trip: " + name);
			assertRejected(zip(Map.of("../escape.json", new byte[] { '{', '}' })), "zip-slip traversal is rejected");
			assertRejected(without(encoded, "macros/Plan-7.json"), "an omitted manifest member is rejected");
			assertRejected(withMember(encoded, "unknown.data", new byte[] { 1 }), "unknown archive members are rejected");
			assertRejected(corruptChecksum(encoded), "a corrupted payload checksum is rejected");
			assertRejected(duplicateManifestPath(), "duplicate manifest payload paths are rejected");
			assertRejected(zip(Map.of("config.json", new byte[ConfigProfileCodec.MAX_ENTRY_BYTES + 1])), "oversized compressed members are rejected before allocation");
			assertRejected(tooManyMembers(), "archives exceeding the member count are rejected");
			byte[] deeplyNestedJson = ("[".repeat(200) + "0" + "]".repeat(200)).getBytes(StandardCharsets.UTF_8);
			assertRejected(ConfigProfileCodec.encode(Map.of("config.json", deeplyNestedJson)), "deeply nested JSON is rejected before recursive parsing");
			boolean collisionRejected = false;
			try {
				ConfigProfileCodec.encode(Map.of("config.json", bytes("{}"), "macros/Plan-7.json", bytes("{}"), "macros/plan-7.json", bytes("{}")));
			} catch (IOException expected) { collisionRejected = true; }
			assertTrue(collisionRejected, "case-insensitive duplicate paths are rejected for Windows installs");
			Map<String, byte[]> iconProfile = new LinkedHashMap<>();
			iconProfile.put("config.json", bytes("{}")); iconProfile.put("inventory-button-icons/My Icon.PNG", minimalPng(32, 16));
			byte[] iconArchive = ConfigProfileCodec.encode(iconProfile);
			assertTrue(ConfigProfileCodec.decode(iconArchive).files().containsKey("inventory-button-icons/My Icon.PNG"), "spaced, uppercase PNG filenames and bounded icon headers survive profile round-trip");
			Map<String, byte[]> invalidHeader = unzip(iconArchive); invalidHeader.put("inventory-button-icons/My Icon.PNG", new byte[] { 1, 2, 3 });
			assertRejected(zip(invalidHeader), "malformed PNG signatures are rejected before checksum processing");
			Map<String, byte[]> oversizedDimensions = unzip(iconArchive); oversizedDimensions.put("inventory-button-icons/My Icon.PNG", minimalPng(1025, 1));
			assertRejected(zip(oversizedDimensions), "PNG dimensions beyond 1024 pixels are rejected");
			Map<String, byte[]> oversizedIcon = unzip(iconArchive); oversizedIcon.put("inventory-button-icons/My Icon.PNG", new byte[ConfigProfileCodec.MAX_ICON_BYTES + 1]);
			assertRejected(zip(oversizedIcon), "PNG icons above 4 MiB are rejected during archive validation");
			boolean largeIconRejected = false;
			try { ConfigProfileCodec.encode(Map.of("config.json", bytes("{}"), "inventory-button-icons/large.png", new byte[ConfigProfileCodec.MAX_ICON_BYTES + 1])); }
			catch (IOException expected) { largeIconRejected = true; }
			assertTrue(largeIconRejected, "PNG icons above 4 MiB are rejected during archive creation");
		} catch (IOException failure) { throw new AssertionError("profile codec check failed unexpectedly", failure); }
	}

	private static void checkResponsiveScreenGeometry() {
		int[][] viewports = { { 256, 144 }, { 320, 180 }, { 427, 240 }, { 1280, 720 } };
		for (int[] size : viewports) {
			ConfigScreenGeometry.Rect panel = ConfigScreenGeometry.panel(size[0], size[1], 680, 470);
			assertTrue(panel.x() >= 0 && panel.y() >= 0 && panel.right() <= size[0] && panel.bottom() <= size[1],
				"config panel stays inside logical viewport " + size[0] + "x" + size[1]);
			ConfigScreenGeometry.Rect resetBody = ConfigScreenGeometry.content(panel, 37, 63);
			ConfigScreenGeometry.ResetFooter resetFooter = ConfigScreenGeometry.resetFooter(panel);
			assertTrue(resetBody.height() >= 0 && resetBody.bottom() <= resetFooter.back().y(), "reset review clip remains above fixed actions");
			assertTrue(!resetFooter.back().overlaps(resetFooter.action()), "reset review buttons never overlap");
			for (boolean confirming : new boolean[] { false, true }) {
				ConfigScreenGeometry.Footer footer = ConfigScreenGeometry.transferFooter(panel, confirming);
				assertTrue(!footer.folder().overlaps(footer.action()) && !footer.action().overlaps(footer.done())
					&& !footer.folder().overlaps(footer.done()), "transfer footer hit rectangles never overlap");
				ConfigScreenGeometry.Rect profiles = ConfigScreenGeometry.profileViewport(panel);
				assertTrue(profiles.height() >= 0 && profiles.bottom() <= footer.folder().y(), "profile clip remains above fixed footer");
				int visible = ConfigScreenGeometry.visibleRows(profiles, 26);
				if (size[0] <= 320 && size[1] <= 180) assertTrue(visible >= 1, "compact profile chooser keeps at least one selectable row at " + size[0] + "x" + size[1]);
				int maxScroll = ConfigScreenGeometry.maxScroll(100, visible);
				assertTrue(maxScroll == Math.max(0, 100 - visible) && visible > 0 && maxScroll + visible >= 100,
					"profile scroll range reaches the last exported profile");
				ConfigScreenGeometry.Rect confirmation = ConfigScreenGeometry.previewViewport(panel);
				assertTrue(confirmation.height() >= 0 && confirmation.bottom() <= footer.folder().y(), "preview body stays above footer actions");
			}
		}
	}

	private static void checkResetExclusions() {
		Set<String> esp = Set.of("Dungeon Mob ESP", "Mob Highlight", "Block ESP");
		ConfigResetPlan.Plan preserveBoth = ConfigResetPlan.create(true, false, false, "Macros", esp);
		assertTrue(preserveBoth.excludedModules().containsAll(esp) && preserveBoth.excludedModules().contains("Macros"),
			"reset exclusions preserve the full ESP and macro module settings and binds");
		assertTrue(!preserveBoth.resetMacros() && !preserveBoth.resetMobEsp(), "unchecked macro and ESP areas preserve their collections");
		ConfigResetPlan.Plan selectedWithoutAll = ConfigResetPlan.create(false, true, true, "Macros", esp);
		assertTrue(selectedWithoutAll.namedModules().containsAll(esp) && selectedWithoutAll.namedModules().contains("Macros"),
			"selecting only macro and ESP areas resets their named module settings");
	}

	private static void checkTransactionalInstallAndRollback() {
		Path root = null;
		try {
			root = Files.createTempDirectory("ga-profile-check-");
			Path profile = root.resolve("active"); Files.createDirectories(profile.resolve("macros"));
			Files.write(profile.resolve("config.json"), bytes("old-config"));
			Files.write(profile.resolve("macro-index.json"), bytes("old-index"));
			Files.write(profile.resolve("macros/Plan-7.json"), bytes("old-macro"));
			Map<String, byte[]> backup = new LinkedHashMap<>();
			backup.put("config.json", bytes("old-config")); backup.put("macro-index.json", bytes("old-index")); backup.put("macros/Plan-7.json", bytes("old-macro"));
			Map<String, byte[]> incoming = new LinkedHashMap<>();
			incoming.put("config.json", bytes("new-config")); incoming.put("macro-index.json", bytes("new-index"));
			ConfigProfileInstaller.Result restored = ConfigProfileInstaller.install(profile, incoming, backup, path -> path.matches("config\\.json|macro-index\\.json|macros/[A-Za-z0-9._-]{1,48}-[0-9]+\\.json"), files -> { },
				(restoring, index, target) -> { if (!restoring && index == 1) throw new IOException("injected post-first-replacement failure"); });
			assertTrue(!restored.installed() && restored.recoveryRestored(), "failed installation restores the complete recovery snapshot");
			assertFile(profile.resolve("config.json"), "old-config", "rollback restores config.json");
			assertFile(profile.resolve("macro-index.json"), "old-index", "rollback restores macro-index.json");
			assertFile(profile.resolve("macros/Plan-7.json"), "old-macro", "rollback preserves referenced macro data");

			Path failedRoot = root.resolve("rollback-failure"); Files.createDirectories(failedRoot);
			Files.write(failedRoot.resolve("config.json"), bytes("old-config"));
			Path recoveryArchive = root.resolve("recovery.ga-config"); Files.write(recoveryArchive, bytes("recovery archive marker"));
			Map<String, byte[]> partiallyApplied = new LinkedHashMap<>();
			// The fault injector is index-based: make config.json the deterministic first replacement
			// so the failed second install and failed first restore leave the expected partial state.
			partiallyApplied.put("config.json", bytes("new-config"));
			partiallyApplied.put("macro-index.json", bytes("new-index"));
			ConfigProfileInstaller.Result failedRestore = ConfigProfileInstaller.install(failedRoot,
				partiallyApplied,
				Map.of("config.json", bytes("old-config")), path -> path.equals("config.json") || path.equals("macro-index.json"), files -> { },
				(restoring, index, target) -> { if ((!restoring && index == 1) || (restoring && index == 0)) throw new IOException("injected install and rollback failures"); });
			assertTrue(!failedRestore.installed() && !failedRestore.recoveryRestored(), "a failed rollback is reported as an incomplete recovery");
			assertTrue(Files.exists(recoveryArchive), "recovery archive remains available after a failed rollback");
			assertFile(failedRoot.resolve("config.json"), "new-config", "partial state is not reported as a successful install after rollback failure");

			Path unsafeRoot = root.resolve("unsafe"); Files.createDirectories(unsafeRoot);
			ConfigProfileInstaller.Result unsafe = ConfigProfileInstaller.install(unsafeRoot, Map.of("../escape.json", bytes("bad")),
				Map.of(), ConfigProfileCodec::safePath, files -> { }, null);
			assertTrue(!unsafe.installed() && !Files.exists(root.resolve("escape.json")), "installer rejects traversal without writing outside its root");
		} catch (IOException failure) { throw new AssertionError("installer test setup failed", failure); }
		finally { if (root != null) deleteTree(root); }
	}

	private static void checkSettingsRestoreConstructorDefaults() {
		BooleanSetting enabled = new BooleanSetting("test", true); enabled.setValue(false); enabled.reset();
		NumberSetting amount = new NumberSetting("amount", 0, 10, 3); amount.setValue(9); amount.reset();
		assertTrue(enabled.value() && amount.value() == 3.0f, "factory reset restores original constructor defaults");
	}

	private static void checkReparsePointGuard() {
		if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows")) return;
		Path temporaryRoot = null;
		Path junction = null;
		boolean safeToClean = true;
		try {
			temporaryRoot = Files.createTempDirectory("ga-junction-check-");
			Path owned = Files.createDirectories(temporaryRoot.resolve("owned"));
			Path exterior = Files.createDirectories(temporaryRoot.resolve("exterior"));
			Path sentinel = exterior.resolve("sentinel.txt");
			Files.write(sentinel, bytes("outside-data"));
			junction = owned.resolve("linked");
			Process process;
			try {
				process = new ProcessBuilder("cmd.exe", "/c", "mklink /J \"" + junction + "\" \"" + exterior + "\"")
					.redirectErrorStream(true).start();
			} catch (IOException | SecurityException unavailable) { return; }
			try {
				if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
					process.destroyForcibly();
					return;
				}
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new AssertionError("junction fixture creation was interrupted", interrupted);
			}
			if (process.exitValue() != 0 || !Files.exists(junction, LinkOption.NOFOLLOW_LINKS)) return;
			BasicFileAttributes attributes = Files.readAttributes(junction, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			assertTrue(attributes.isOther(), "Windows JDK exposes a directory junction as a reparse point through isOther()");
			boolean rejected = false;
			try { ConfigPathGuard.rejectReparseChain(junction.resolve("sentinel.txt")); }
			catch (IOException expected) { rejected = true; }
			assertTrue(rejected, "Windows junction ancestors are rejected as filesystem reparse points");
			ConfigProfileInstaller.Result install = ConfigProfileInstaller.install(owned,
				Map.of("config.json", bytes("new-config")), null, ConfigProfileCodec::safePath, files -> { }, null);
			assertTrue(!install.installed(), "profile installer refuses to traverse a junction in its owned tree");
			assertFile(sentinel, "outside-data", "rejected junction traversal leaves exterior sentinel untouched");
		} catch (IOException failure) {
			throw new AssertionError("junction guard regression check failed after fixture creation", failure);
		} finally {
			if (junction != null) {
				try { Files.deleteIfExists(junction); }
				catch (IOException cannotDetachJunction) { safeToClean = false; }
			}
			if (safeToClean && temporaryRoot != null) deleteTree(temporaryRoot);
		}
	}

	private static Map<String, byte[]> sampleProfile() {
		Map<String, byte[]> files = new LinkedHashMap<>();
		files.put("config.json", bytes("{}")); files.put("macro-index.json", bytes("{}"));
		files.put("macros/Plan-7.json", bytes("{\"id\":\"Plan-7\"}"));
		return files;
	}
	private static byte[] minimalPng(int width, int height) {
		byte[] png = new byte[24]; byte[] signature = { (byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10 };
		System.arraycopy(signature, 0, png, 0, signature.length);
		png[11] = 13; png[12] = 'I'; png[13] = 'H'; png[14] = 'D'; png[15] = 'R';
		putInt(png, 16, width); putInt(png, 20, height); return png;
	}
	private static void putInt(byte[] bytes, int offset, int value) {
		bytes[offset] = (byte) (value >>> 24); bytes[offset + 1] = (byte) (value >>> 16);
		bytes[offset + 2] = (byte) (value >>> 8); bytes[offset + 3] = (byte) value;
	}
	private static byte[] corruptChecksum(byte[] archive) throws IOException {
		Map<String, byte[]> items = unzip(archive); String manifest = new String(items.get("manifest.json"), StandardCharsets.UTF_8);
		int index = manifest.indexOf("\"sha256\""); int value = manifest.indexOf(':', index) + 1;
		while (value < manifest.length() && manifest.charAt(value) != '"') value++;
		int digit = value + 1; manifest = manifest.substring(0, digit) + (manifest.charAt(digit) == '0' ? '1' : '0') + manifest.substring(digit + 1);
		items.put("manifest.json", bytes(manifest)); return zip(items);
	}
	private static byte[] duplicateManifestPath() throws IOException {
		byte[] config = bytes("{}"); String hash;
		try { hash = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(config)); }
		catch (Exception impossible) { throw new IOException(impossible); }
		String entry = "{\"path\":\"config.json\",\"size\":2,\"sha256\":\"" + hash + "\"}";
		String manifest = "{\"format\":\"geileraddons-config\",\"version\":1,\"createdAt\":\"2026-09-28T12:00:00Z\",\"totalBytes\":2,\"entries\":[" + entry + "," + entry + "]}";
		return zip(Map.of("manifest.json", bytes(manifest), "config.json", config));
	}
	private static byte[] tooManyMembers() throws IOException {
		Map<String, byte[]> items = new LinkedHashMap<>(); items.put("config.json", bytes("{}"));
		for (int i = 0; i < ConfigProfileCodec.MAX_ENTRIES; i++) items.put("inventory-button-icons/icon-" + i + ".png", new byte[] { 1 });
		return zip(items);
	}
	private static byte[] without(byte[] archive, String name) throws IOException { Map<String, byte[]> files = unzip(archive); files.remove(name); return zip(files); }
	private static byte[] withMember(byte[] archive, String name, byte[] data) throws IOException { Map<String, byte[]> files = unzip(archive); files.put(name, data); return zip(files); }
	private static Map<String, byte[]> unzip(byte[] bytes) throws IOException {
		Map<String, byte[]> files = new LinkedHashMap<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
			ZipEntry item; while ((item = zip.getNextEntry()) != null) { ByteArrayOutputStream data = new ByteArrayOutputStream(); zip.transferTo(data); files.put(item.getName(), data.toByteArray()); }
		}
		return files;
	}
	private static byte[] zip(Map<String, byte[]> files) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			for (Map.Entry<String, byte[]> file : files.entrySet()) { zip.putNextEntry(new ZipEntry(file.getKey())); zip.write(file.getValue()); zip.closeEntry(); }
		}
		return bytes.toByteArray();
	}
	private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
	private static void assertRejected(byte[] data, String message) {
		try { ConfigProfileCodec.decode(data); throw new AssertionError(message); }
		catch (IOException expected) { }
	}
	private static void assertFile(Path path, String expected, String message) throws IOException {
		assertTrue(Files.exists(path) && new String(Files.readAllBytes(path), StandardCharsets.UTF_8).equals(expected), message);
	}
	private static void deleteTree(Path root) {
		try {
			if (!Files.exists(root)) return;
			try (var walk = Files.walk(root)) { for (Path item : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(item); }
		} catch (IOException failure) { throw new AssertionError("isolated temp folder cleanup failed", failure); }
	}
	private static void assertTrue(boolean result, String message) { if (!result) throw new AssertionError(message); }
}
