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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Central, opt-in asynchronous sink for GeilerAddons diagnostics. */
public final class GeilerAddonsLog {
	private static final DateTimeFormatter SESSION_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS");
	private static final DateTimeFormatter LINE_STAMP = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
	private static final int MAX_QUEUED_LINES = 8192;
	private static final Path ROOT = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("geileraddons");

	private static volatile boolean enabled;
	private static boolean masterEnabled;
	private static final Set<DiagnosticSource> independentSources = new HashSet<>();
	private static volatile boolean stopRequested;
	private static volatile Path sessionDirectory;
	private static volatile LinkedBlockingQueue<Entry> queue;
	private static volatile Thread worker;
	private static volatile String failure;
	private static boolean restartRequested;
	private static boolean closeRequested;
	private static final AtomicLong droppedLines = new AtomicLong();

	private GeilerAddonsLog() {
	}

	/** Starts or resumes the current game-session writer when Dev Debug is enabled. */
	public static synchronized void start() {
		masterEnabled = true;
		ensureWriter();
	}

	/**
	 * Keeps one module's opt-in diagnostics active without enabling Dev Debug for the rest of the mod.
	 * Call from that module's client tick so turning the setting off releases its writer interest.
	 */
	public static synchronized void setModuleDiagnosticsEnabled(Category category, String module,
		boolean requested) {
		if (category == null || module == null) return;
		DiagnosticSource source = new DiagnosticSource(category, module);
		boolean changed = requested ? independentSources.add(source) : independentSources.remove(source);
		boolean shouldRun = masterEnabled || !independentSources.isEmpty();
		if (shouldRun) {
			if (changed || worker == null && failure == null) ensureWriter();
		} else if (changed || worker != null) {
			stopWriter();
		}
	}

	/** Starts the writer while preserving the current global/opt-in ownership state. */
	private static void ensureWriter() {
		if (worker != null && worker.isAlive()) {
			if (stopRequested) restartRequested = true;
			else enabled = true;
			return;
		}
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
			stopRequested = false;
			restartRequested = false;
			closeRequested = false;
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
		masterEnabled = false;
		if (!independentSources.isEmpty()) {
			ensureWriter();
			return;
		}
		stopWriter();
	}

	private static void stopWriter() {
		enabled = false;
		Thread currentWorker = worker;
		if (currentWorker == null || !currentWorker.isAlive()) {
			queue = null;
			worker = null;
			stopRequested = false;
			return;
		}
		// The writer drains what is already queued and exits once the queue is empty. Never wait on
		// disk from the client thread, and never discard older diagnostic lines to make room for a
		// stop marker.
		stopRequested = true;
	}

	/** Closes the current writer at game shutdown. */
	public static void close() {
		Thread currentWorker;
		synchronized (GeilerAddonsLog.class) {
			masterEnabled = false;
			independentSources.clear();
			enabled = false;
			restartRequested = false;
			closeRequested = true;
			currentWorker = worker;
			if (currentWorker != null && currentWorker.isAlive()) {
				stopRequested = true;
				sessionDirectory = null;
			} else {
				sessionDirectory = null;
				queue = null;
				worker = null;
				stopRequested = false;
				closeRequested = false;
				return;
			}
		}
		if (currentWorker == Thread.currentThread()) return;
		try {
			currentWorker.join(1_000L);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
		if (currentWorker.isAlive()) {
			GeilerAddons.LOGGER.warn("GeilerAddons diagnostic writer did not drain before shutdown timeout");
		}
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
		String safeLine = line.replace('\r', ' ').replace('\n', ' ');
		String entry = "[" + LocalDateTime.now().format(LINE_STAMP) + "] [t" + tick + "] ["
			+ category.displayName() + "/" + module + "] " + safeLine + System.lineSeparator();
		synchronized (GeilerAddonsLog.class) {
			if (!enabled || !masterEnabled && !independentSources.contains(new DiagnosticSource(category, module))) return;
			LinkedBlockingQueue<Entry> currentQueue = queue;
			if (currentQueue == null) return;
			Path target = path(category, module);
			if (!currentQueue.offer(new Entry(target, entry))) droppedLines.incrementAndGet();
		}
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
					if (stopRequested && currentQueue.isEmpty()) break;
					continue;
				}
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
				if (remaining.path() == null) continue;
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
			synchronized (GeilerAddonsLog.class) {
				enabled = false;
				stopRequested = true;
			}
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
			boolean restart = false;
			synchronized (GeilerAddonsLog.class) {
				if (worker == Thread.currentThread()) {
					worker = null;
					queue = null;
					stopRequested = false;
					if (closeRequested) sessionDirectory = null;
					restart = restartRequested && !closeRequested
						&& (masterEnabled || !independentSources.isEmpty());
					restartRequested = false;
					closeRequested = false;
				}
			}
			if (restart) {
				synchronized (GeilerAddonsLog.class) {
					ensureWriter();
				}
			}
		}
	}

	private static void flush(Map<Path, Writer> writers) throws IOException {
		for (Writer writer : writers.values()) writer.flush();
	}

	private static String safe(String value) {
		String result = value.replaceAll("[^A-Za-z0-9._-]+", "_").replaceAll("_+", "_");
		return result.isBlank() ? "unknown" : result;
	}

	private record Entry(Path path, String text) {
	}

	private record DiagnosticSource(Category category, String module) {
	}
}
