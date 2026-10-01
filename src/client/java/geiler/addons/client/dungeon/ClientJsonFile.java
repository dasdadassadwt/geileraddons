package geiler.addons.client.dungeon;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import geiler.addons.GeilerAddons;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CompletableFuture;

/** Small bounded, off-thread JSON persistence shared by the two independent dungeon catalogs. */
public final class ClientJsonFile {
	public static final int MAX_FILE_BYTES = 1_048_576;
	public static final int MAX_PACKAGE_BYTES = 262_144;
	public static final int MAX_ENTRIES = 2_000;
	public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "GeilerAddons dungeon data writer");
		thread.setDaemon(true);
		return thread;
	});
	private static Future<?> lastWrite;

	private ClientJsonFile() { }

	/** Load a bundled file from the classpath first, then resolve it through Fabric's packaged mod container. */
	public static InputStream openBundledResource(Class<?> anchor, String resource) throws IOException {
		if (anchor == null || resource == null || resource.isBlank()) {
			throw new IOException("Missing bundled resource location");
		}
		InputStream classpath = anchor.getResourceAsStream(resource);
		if (classpath != null) return classpath;
		String modPath = resource.startsWith("/") ? resource.substring(1) : resource;
		Path packaged = FabricLoader.getInstance().getModContainer(GeilerAddons.MOD_ID)
			.flatMap(container -> container.findPath(modPath))
			.orElseThrow(() -> new IOException("Bundled resource is missing: " + modPath));
		return Files.newInputStream(packaged);
	}

	public static <T> T read(Path path, Class<T> type) {
		return readResult(path, type).value();
	}

	public static <T> ReadResult<T> readResult(Path path, Class<T> type) {
		if (path == null || type == null) return new ReadResult<>(false, null, "missing path or type");
		try {
			if (!Files.exists(path)) return new ReadResult<>(false, null, null);
			if (!Files.isRegularFile(path)) return new ReadResult<>(true, null, "path is not a regular file");
			long size = Files.size(path);
			if (size <= 0 || size > MAX_FILE_BYTES) return new ReadResult<>(true, null, "file size is outside the allowed range");
			String json = Files.readString(path, StandardCharsets.UTF_8);
			T value = GSON.fromJson(json, type);
			return value == null ? new ReadResult<>(true, null, "JSON did not contain a value")
				: new ReadResult<>(true, value, null);
		} catch (IOException | RuntimeException error) {
			GeilerAddons.LOGGER.warn("Could not read client JSON file {}", path, error);
			return new ReadResult<>(true, null, error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
		}
	}

	public static synchronized Future<Boolean> writeAsync(Path path, String json) {
		if (path == null || json == null) return CompletableFuture.completedFuture(false);
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		if (bytes.length > MAX_FILE_BYTES) {
			GeilerAddons.LOGGER.error("Refusing to write oversized client JSON file {} ({} bytes)", path, bytes.length);
			return CompletableFuture.completedFuture(false);
		}
		return writeBatchAsync(List.of(new Write(path, bytes)));
	}

	/** Writes all data files first and the index last on one serialized worker. */
	public static synchronized Future<Boolean> writeBatchAsync(List<Write> writes) {
		if (writes == null || writes.isEmpty()) return CompletableFuture.completedFuture(false);
		List<Write> snapshot = new ArrayList<>(writes.size());
		for (Write write : writes) {
			if (write == null || write.path() == null || write.bytes() == null || write.bytes().length > MAX_FILE_BYTES) {
				GeilerAddons.LOGGER.error("Refusing an invalid or oversized client JSON write batch");
				return CompletableFuture.completedFuture(false);
			}
			snapshot.add(new Write(write.path(), write.bytes().clone()));
		}
		Future<Boolean> future = WRITER.submit(() -> {
			for (Write write : snapshot) {
				Path temporary = null;
				try {
					Path parent = write.path().getParent();
					if (parent == null) throw new IOException("target has no parent directory");
					Files.createDirectories(parent);
					temporary = Files.createTempFile(parent, "geiler-data-", ".tmp");
					Files.write(temporary, write.bytes());
					try {
						Files.move(temporary, write.path(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
					} catch (AtomicMoveNotSupportedException unsupported) {
						Files.move(temporary, write.path(), StandardCopyOption.REPLACE_EXISTING);
					}
					temporary = null;
				} catch (IOException error) {
					GeilerAddons.LOGGER.error("Could not write client JSON file {}", write.path(), error);
					return false;
				} finally {
					if (temporary != null) try { Files.deleteIfExists(temporary); }
					catch (IOException error) { GeilerAddons.LOGGER.debug("Could not remove temporary file {}", temporary, error); }
				}
			}
			return true;
		});
		lastWrite = future;
		return future;
	}

	public static void flush() {
		Future<?> pending;
		synchronized (ClientJsonFile.class) { pending = lastWrite; }
		if (pending == null) return;
		try {
			if (Boolean.FALSE.equals(pending.get())) GeilerAddons.LOGGER.error("A client JSON save did not complete");
		} catch (Exception error) { GeilerAddons.LOGGER.error("Could not wait for client JSON save", error); }
	}

	public record ReadResult<T>(boolean exists, T value, String error) {
		public boolean valid() { return error == null; }
	}
	public record Write(Path path, byte[] bytes) { }
}
