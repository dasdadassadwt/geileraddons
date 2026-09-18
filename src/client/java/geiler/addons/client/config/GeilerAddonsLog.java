package geiler.addons.client.config;

import geiler.addons.GeilerAddons;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.ModuleManager;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Central, opt-in asynchronous sink for GeilerAddons diagnostics. */
public final class GeilerAddonsLog {
	private static final DateTimeFormatter SESSION_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS");
	private static final DateTimeFormatter LINE_STAMP = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
	private static final String STOP = "\u0000GEILERADDONS_LOG_STOP\u0000";
	private static final int MAX_QUEUED_LINES = 8192;
	private static final Path ROOT = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("geileraddons");

	private static volatile boolean enabled;
	private static volatile Path sessionDirectory;
	private static volatile LinkedBlockingQueue<Entry> queue;
	private static volatile Thread worker;
	private static volatile String failure;
	private static final AtomicLong droppedLines = new AtomicLong();

	private GeilerAddonsLog() {
	}

	/** Starts or resumes the current game-session writer when Dev Debug is enabled. */
	public static synchronized void start() {
		if (worker != null && worker.isAlive()) return;
		try {
			if (sessionDirectory == null) {
				Files.createDirectories(ROOT);
				sessionDirectory = ROOT.resolve(LocalDateTime.now().format(SESSION_STAMP));
				int suffix = 1;
			while (Files.exists(sessionDirectory)) {
					sessionDirectory = ROOT.resolve(LocalDateTime.now().format(SESSION_STAMP) + "-" + suffix++);
			}
			Files.createDirectories(sessionDirectory);
			initializeModuleFiles();
			}
			LinkedBlockingQueue<Entry> sessionQueue = new LinkedBlockingQueue<>(MAX_QUEUED_LINES);
			queue = sessionQueue;
			droppedLines.set(0);
			failure = null;
			enabled = true;
			Thread sessionWorker = new Thread(() -> runWriter(sessionQueue), "GeilerAddons log writer");
			sessionWorker.setDaemon(true);
			worker = sessionWorker;
			sessionWorker.start();
			write(Category.DEV, "Core", 0, "LOG SESSION START");
		} catch (IOException error) {
			enabled = false;
			GeilerAddons.LOGGER.error("Could not open the GeilerAddons diagnostic log", error);
		}
	}

	/** Creates the stable category/module tree up front, including quiet modules with no events. */
	private static void initializeModuleFiles() throws IOException {
		for (Category category : Category.values()) {
			Files.createDirectories(sessionDirectory.resolve(safe(category.displayName())));
		}
		for (Module module : ModuleManager.modules()) {
			Path file = path(module.category(), module.name());
			if (file == null) continue;
			Files.createDirectories(file.getParent());
			Files.writeString(file, "", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		}
	}

	/** Stops the writer but keeps the session directory so a later toggle resumes it. */
	public static synchronized void stop() {
		enabled = false;
		LinkedBlockingQueue<Entry> currentQueue = queue;
		Thread currentWorker = worker;
		if (currentQueue == null || currentWorker == null) {
			queue = null;
			worker = null;
			return;
		}
		currentQueue.offer(new Entry(null, "LOG SESSION PAUSE\n"));
		while (!currentQueue.offer(new Entry(null, STOP))) currentQueue.poll();
		join(currentWorker);
		queue = null;
		worker = null;
	}

	/** Closes the current writer at game shutdown. */
	public static synchronized void close() {
		stop();
		sessionDirectory = null;
	}

	public static boolean enabled() {
		return enabled;
	}

	/** Non-null when the asynchronous writer failed and diagnostics are currently unavailable. */
	public static String failure() {
		return failure;
	}

	public static Path sessionDirectory() {
		return sessionDirectory;
	}

	/** Returns the eventual path for a module log, or null when diagnostics are not active. */
	public static Path path(Category category, String module) {
		Path session = sessionDirectory;
		if (session == null || category == null || module == null) return null;
		return session.resolve(safe(category.displayName())).resolve(safe(module) + ".log");
	}

	public static void write(Category category, String module, long tick, String line) {
		if (!enabled || category == null || module == null || line == null) return;
		LinkedBlockingQueue<Entry> currentQueue = queue;
		if (currentQueue == null) return;
		String safeLine = line.replace('\r', ' ').replace('\n', ' ');
		String entry = "[" + LocalDateTime.now().format(LINE_STAMP) + "] [t" + tick + "] ["
			+ category.displayName() + "/" + module + "] " + safeLine + System.lineSeparator();
		Path target = path(category, module);
		if (!currentQueue.offer(new Entry(target, entry))) droppedLines.incrementAndGet();
	}

	private static void runWriter(LinkedBlockingQueue<Entry> currentQueue) {
		Map<Path, Writer> writers = new HashMap<>();
		long lastFlush = System.nanoTime();
		int pending = 0;
		try {
			while (true) {
				Entry entry = currentQueue.poll(250, TimeUnit.MILLISECONDS);
				if (entry == null) {
					if (pending > 0) flush(writers);
					pending = 0;
					lastFlush = System.nanoTime();
					continue;
				}
				if (STOP.equals(entry.text())) break;
				if (entry.path() != null) {
					Writer writer = writers.get(entry.path());
					if (writer == null) {
						Files.createDirectories(entry.path().getParent());
						writer = Files.newBufferedWriter(entry.path(), StandardOpenOption.CREATE,
							StandardOpenOption.APPEND, StandardOpenOption.WRITE);
						writers.put(entry.path(), writer);
					}
					writer.write(entry.text());
					pending++;
				}
				if (pending >= 32 || System.nanoTime() - lastFlush >= 250_000_000L) {
					flush(writers);
					pending = 0;
					lastFlush = System.nanoTime();
				}
			}
			Entry remaining;
			while ((remaining = currentQueue.poll()) != null) {
				if (STOP.equals(remaining.text()) || remaining.path() == null) continue;
				Writer writer = writers.get(remaining.path());
				if (writer == null) {
					Files.createDirectories(remaining.path().getParent());
					writer = Files.newBufferedWriter(remaining.path(), StandardOpenOption.CREATE,
						StandardOpenOption.APPEND, StandardOpenOption.WRITE);
					writers.put(remaining.path(), writer);
				}
				writer.write(remaining.text());
			}
			flush(writers);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			GeilerAddons.LOGGER.warn("GeilerAddons log writer interrupted");
		} catch (IOException error) {
			enabled = false;
			failure = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
			GeilerAddons.LOGGER.error("Could not write the GeilerAddons diagnostic log", error);
		} finally {
			long dropped = droppedLines.getAndSet(0);
			if (dropped > 0) GeilerAddons.LOGGER.warn("Dropped {} GeilerAddons diagnostic lines", dropped);
			for (Writer writer : writers.values()) {
				try {
					writer.close();
				} catch (IOException error) {
					GeilerAddons.LOGGER.error("Could not close a GeilerAddons diagnostic log", error);
				}
			}
			if (worker == Thread.currentThread()) {
				worker = null;
				queue = null;
			}
		}
	}

	private static void flush(Map<Path, Writer> writers) throws IOException {
		for (Writer writer : writers.values()) writer.flush();
	}

	private static void join(Thread thread) {
		try {
			thread.join(1_000);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			GeilerAddons.LOGGER.warn("Interrupted while closing GeilerAddons logs");
		}
		if (thread.isAlive()) GeilerAddons.LOGGER.warn("GeilerAddons log writer did not close within one second");
	}

	private static String safe(String value) {
		String result = value.replaceAll("[^A-Za-z0-9._-]+", "_").replaceAll("_+", "_");
		return result.isBlank() ? "unknown" : result;
	}

	private record Entry(Path path, String text) {
	}
}
