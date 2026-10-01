package geiler.addons.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Pure, bounded archive codec for portable GeilerAddons profiles. */
public final class ConfigProfileCodec {
	public static final int MAX_ARCHIVE_BYTES = 32 * 1024 * 1024;
	public static final int MAX_ENTRY_BYTES = 8 * 1024 * 1024;
	public static final int MAX_ICON_BYTES = 4 * 1024 * 1024;
	public static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;
	public static final int MAX_ENTRIES = 10_000;
	private static final int MAX_JSON_DEPTH = 160;
	private static final int MAX_JSON_STRING_CHARS = 1_048_576;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FORMAT = "geileraddons-config";
	private ConfigProfileCodec() { }

	public record Archive(Map<String, byte[]> files, String createdAt, long totalBytes) {
		public Archive { files = Map.copyOf(files); }
	}

	public static byte[] encode(Map<String, byte[]> files) throws IOException {
		if (files == null || files.isEmpty() || !files.containsKey("config.json") || files.size() > MAX_ENTRIES)
			throw new IOException("Profile payload is missing config.json or exceeds the entry limit.");
		Manifest manifest = new Manifest(); manifest.format = FORMAT; manifest.version = 1; manifest.createdAt = Instant.now().toString();
		manifest.entries = new ArrayList<>();
		Set<String> foldedPaths = new HashSet<>();
		for (Map.Entry<String, byte[]> entry : files.entrySet()) {
			String path = entry.getKey(); byte[] data = entry.getValue();
			if (!safePath(path) || !foldedPaths.add(path.toLowerCase(Locale.ROOT)) || data == null || data.length > MAX_ENTRY_BYTES)
				throw new IOException("Profile contains an unsafe, duplicate, or oversized entry.");
			if (isIconPath(path)) validateIconPng(data);
			Entry entryMeta = new Entry(); entryMeta.path = path; entryMeta.size = data.length; entryMeta.sha256 = sha256(data);
			manifest.entries.add(entryMeta); manifest.totalBytes += data.length;
			if (manifest.totalBytes > MAX_TOTAL_BYTES) throw new IOException("Profile exceeds the expanded-size limit.");
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			put(zip, "manifest.json", GSON.toJson(manifest).getBytes(java.nio.charset.StandardCharsets.UTF_8));
			for (Map.Entry<String, byte[]> entry : files.entrySet()) put(zip, entry.getKey(), entry.getValue());
		}
		byte[] result = bytes.toByteArray();
		if (result.length > MAX_ARCHIVE_BYTES) throw new IOException("Profile archive exceeds the compressed-size limit.");
		return result;
	}

	public static Archive decode(byte[] archive) throws IOException {
		if (archive == null || archive.length == 0 || archive.length > MAX_ARCHIVE_BYTES) throw new IOException("Archive size is outside the limit.");
		Map<String, byte[]> files = new LinkedHashMap<>(); byte[] manifestBytes = null; long total = 0;
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				String name = entry.getName();
				if (entry.isDirectory() || !safeName(name)) throw new IOException("Unsafe archive path: " + name);
				byte[] contents = readBounded(zip, MAX_ENTRY_BYTES);
				if (name.equals("manifest.json")) {
					if (manifestBytes != null) throw new IOException("Duplicate archive manifest.");
					manifestBytes = contents;
				} else {
					if (!safePath(name)) throw new IOException("Unrecognized persisted profile path: " + name);
					if (isIconPath(name)) validateIconPng(contents);
					if (files.putIfAbsent(name, contents) != null) throw new IOException("Duplicate archive member: " + name);
					total += contents.length;
					if (total > MAX_TOTAL_BYTES || files.size() > MAX_ENTRIES) throw new IOException("Archive exceeds entry or expanded-size limits.");
				}
			}
		}
		if (manifestBytes == null) throw new IOException("Archive manifest is missing.");
		validateJsonBounds(manifestBytes);
		Manifest manifest;
		try { manifest = GSON.fromJson(new String(manifestBytes, java.nio.charset.StandardCharsets.UTF_8), Manifest.class); }
		catch (RuntimeException | StackOverflowError invalid) { throw new IOException("Archive manifest is invalid.", invalid); }
		if (manifest == null || !FORMAT.equals(manifest.format) || manifest.version != 1 || manifest.createdAt == null
			|| manifest.entries == null || manifest.entries.size() != files.size() || manifest.entries.size() > MAX_ENTRIES
			|| manifest.totalBytes != total || !files.containsKey("config.json")) throw new IOException("Unsupported or incomplete profile manifest.");
		try { Instant.parse(manifest.createdAt); }
		catch (RuntimeException invalid) { throw new IOException("Profile timestamp is invalid.", invalid); }
		Set<String> listed = new HashSet<>();
		Set<String> foldedPaths = new HashSet<>();
		for (Entry meta : manifest.entries) {
			if (meta == null || !safePath(meta.path) || !listed.add(meta.path)
				|| !foldedPaths.add(meta.path.toLowerCase(Locale.ROOT)) || meta.sha256 == null
				|| !meta.sha256.matches("[0-9a-f]{64}")) throw new IOException("Manifest has an invalid or duplicate entry.");
			byte[] contents = files.get(meta.path);
			if (contents == null || meta.size != contents.length || !sha256(contents).equals(meta.sha256))
				throw new IOException("Entry checksum or size mismatch: " + meta.path);
		}
		if (!listed.equals(files.keySet())) throw new IOException("Archive has unlisted or omitted payload members.");
		for (Map.Entry<String, byte[]> payload : files.entrySet()) if (payload.getKey().endsWith(".json")) validateJsonBounds(payload.getValue());
		return new Archive(files, manifest.createdAt, total);
	}

	/** Shallow lexical preflight before Gson's recursive tree parser sees untrusted JSON. */
	public static void validateJsonBounds(byte[] json) throws IOException {
		if (json == null || json.length == 0 || json.length > MAX_ENTRY_BYTES) throw new IOException("JSON member is empty or too large.");
		int depth = 0, maxDepth = 0, stringChars = 0; boolean inString = false, escaped = false;
		for (byte raw : json) {
			int ch = raw & 0xff;
			if (inString) {
				if (escaped) { escaped = false; continue; }
				if (ch == '\\') { escaped = true; continue; }
				if (ch == '"') { inString = false; continue; }
				if (++stringChars > MAX_JSON_STRING_CHARS) throw new IOException("JSON string exceeds the supported limit.");
				continue;
			}
			if (ch == '"') { inString = true; stringChars = 0; }
			else if (ch == '{' || ch == '[') { maxDepth = Math.max(maxDepth, ++depth); if (maxDepth > MAX_JSON_DEPTH) throw new IOException("JSON nesting exceeds the supported limit."); }
			else if (ch == '}' || ch == ']') { if (--depth < 0) throw new IOException("JSON nesting is malformed."); }
		}
		if (inString || depth != 0) throw new IOException("JSON string or nesting is unterminated.");
	}

	/** Checks PNG allocation bounds from the fixed header before any decoder sees imported icon data. */
	public static void validateIconPng(byte[] png) throws IOException {
		byte[] signature = { (byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10 };
		if (png == null || png.length < 24 || png.length > MAX_ICON_BYTES) throw new IOException("PNG icon is empty, truncated, or exceeds the 4 MiB limit.");
		for (int i = 0; i < signature.length; i++) if (png[i] != signature[i]) throw new IOException("Icon does not have a PNG signature.");
		if (unsignedInt(png, 8) != 13L || png[12] != 'I' || png[13] != 'H' || png[14] != 'D' || png[15] != 'R')
			throw new IOException("PNG icon is missing the required IHDR header.");
		long width = unsignedInt(png, 16), height = unsignedInt(png, 20);
		if (width < 1 || height < 1 || width > 1024 || height > 1024) throw new IOException("PNG icon dimensions exceed the 1024×1024 limit.");
	}

	private static long unsignedInt(byte[] bytes, int offset) {
		return ((bytes[offset] & 0xffL) << 24) | ((bytes[offset + 1] & 0xffL) << 16)
			| ((bytes[offset + 2] & 0xffL) << 8) | (bytes[offset + 3] & 0xffL);
	}

	public static boolean safeName(String name) {
		if (name == null || name.isBlank() || name.length() > 240 || name.startsWith("/") || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0) return false;
		for (String part : name.split("/", -1)) if (part.isBlank() || part.equals(".") || part.equals("..")) return false;
		return true;
	}

	/** Exact allowlist for active config, owned split data, and known migration archives. */
	public static boolean safePath(String path) {
		if (!safeName(path) || path.startsWith(".")) return false;
		if (path.equals("config.json") || path.equals("config.before-split.json") || path.equals("macro-index.json")
			|| path.equals("dungeon-guides/index.json") || path.equals("dungeon-guides.json")
			|| path.equals("dungeon-guides/archive/legacy-v1/index.json")
			|| path.equals("dungeon-guides/archive/legacy-v1/dungeon-guides.json")) return true;
		if (path.startsWith("macros/")) return path.matches("macros/[A-Za-z0-9._-]{1,48}-[0-9]+\\.json");
		if (path.startsWith("functions/")) return path.matches("functions/[A-Za-z0-9._-]{1,48}-[A-Za-z0-9._-]{1,48}-[0-9a-z]+\\.json");
		if (path.matches("dungeon-guides/(?:Entrance|F[1-7]|M[1-7])/[A-Z][A-Z0-9_]{0,31}/[a-z0-9._-]{1,48}-[0-9a-z]+\\.json")) return true;
		if (path.startsWith("dungeon-guides/archive/legacy-v1/routes/"))
			return path.matches("dungeon-guides/archive/legacy-v1/routes/(?:Entrance|F[1-7]|M[1-7])/[A-Z][A-Z0-9_]{0,31}/[a-z0-9._-]{1,48}-[0-9a-z]+\\.json");
		return isIconPath(path);
	}

	private static boolean isIconPath(String path) {
		if (path == null || !path.startsWith("inventory-button-icons/")) return false;
		String filename = path.substring("inventory-button-icons/".length());
		return !filename.isBlank() && filename.length() <= 216 && filename.indexOf('/') < 0
			&& filename.toLowerCase(Locale.ROOT).endsWith(".png") && safeName(filename);
	}

	private static byte[] readBounded(ZipInputStream input, int limit) throws IOException {
		ByteArrayOutputStream result = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int read; int size = 0;
		while ((read = input.read(buffer)) >= 0) { size += read; if (size > limit) throw new IOException("Archive entry exceeds the size limit."); result.write(buffer, 0, read); }
		return result.toByteArray();
	}
	private static void put(ZipOutputStream zip, String name, byte[] value) throws IOException { zip.putNextEntry(new ZipEntry(name)); zip.write(value); zip.closeEntry(); }
	private static String sha256(byte[] value) {
		try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
		catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
	}
	private static final class Manifest { String format; int version; String createdAt; long totalBytes; List<Entry> entries; }
	private static final class Entry { String path; long size; String sha256; }
}
