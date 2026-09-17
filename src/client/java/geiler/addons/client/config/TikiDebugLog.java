package geiler.addons.client.config;

import geiler.addons.GeilerAddons;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One log file per debug session under the game's {@code logs/} folder. Lines are queued to a
 * daemon writer so block rescans never perform disk I/O on the client thread.
 */
public final class TikiDebugLog {
	private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS");
	private static final DateTimeFormatter LINE_STAMP = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
	private static final String STOP = "\u0000GEILERADDONS_TIKI_STOP\u0000";
	private static final int MAX_QUEUED_LINES = 8192;

	private static volatile Path path;
	private static volatile Writer writer;
	private static volatile LinkedBlockingQueue<String> queue;
	private static volatile Thread worker;
	private static final AtomicLong droppedLines = new AtomicLong();

	private TikiDebugLog() {
	}

	public static synchronized void open() {
		close();
		Writer opened = null;
		try {
			Path directory = FabricLoader.getInstance().getGameDir().resolve("logs");
			Files.createDirectories(directory);
			String stem = "tiki-debug-" + LocalDateTime.now().format(FILE_STAMP);
			Path candidate = directory.resolve(stem + ".log");
			int suffix = 1;
			while (Files.exists(candidate)) candidate = directory.resolve(stem + "-" + suffix++ + ".log");
			path = candidate;
			opened = Files.newBufferedWriter(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
			writer = opened;
			queue = new LinkedBlockingQueue<>(MAX_QUEUED_LINES);
			droppedLines.set(0);
			LinkedBlockingQueue<String> sessionQueue = queue;
			Thread sessionWorker = new Thread(() -> runWriter(sessionQueue), "GeilerAddons tiki log");
			sessionWorker.setDaemon(true);
			worker = sessionWorker;
			sessionWorker.start();
			write(0, "SESSION START");
		} catch (IOException e) {
			GeilerAddons.LOGGER.error("Could not open the tiki debug log", e);
			if (opened != null) {
				try {
					opened.close();
				} catch (IOException closeError) {
					GeilerAddons.LOGGER.debug("Could not close a partially opened tiki debug log", closeError);
				}
			}
			path = null;
			writer = null;
			queue = null;
			worker = null;
		}
	}

	public static synchronized void close() {
		LinkedBlockingQueue<String> currentQueue = queue;
		Thread currentWorker = worker;
		if (writer == null || currentQueue == null || currentWorker == null) {
			path = null;
			writer = null;
			queue = null;
			worker = null;
			return;
		}
		write(0, "SESSION END");
		// A bounded queue can be full while a large scan is being logged. Make room for the
		// sentinel so close never leaves a writer thread alive indefinitely.
		while (!currentQueue.offer(STOP)) currentQueue.poll();
		try {
			currentWorker.join(1_000);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			GeilerAddons.LOGGER.warn("Interrupted while closing the tiki debug log");
		}
		if (currentWorker.isAlive()) {
			GeilerAddons.LOGGER.warn("Tiki debug log did not close within one second");
		}
		path = null;
		writer = null;
		queue = null;
		worker = null;
	}

	/** @param tick client ticks since the module was enabled, so events can be ordered exactly */
	public static void write(long tick, String line) {
		LinkedBlockingQueue<String> currentQueue = queue;
		if (writer == null || currentQueue == null) return;
		String entry = "[" + LocalDateTime.now().format(LINE_STAMP) + "] [t" + tick + "] "
			+ line + System.lineSeparator();
		if (!currentQueue.offer(entry)) {
			droppedLines.incrementAndGet();
		}
	}

	public static Path path() {
		return path;
	}

	private static void runWriter(LinkedBlockingQueue<String> currentQueue) {
		Writer currentWriter = writer;
		if (currentWriter == null) return;
		int pending = 0;
		long lastFlush = System.nanoTime();
		try {
			while (true) {
				String line = currentQueue.poll(250, TimeUnit.MILLISECONDS);
				if (line == null) {
					if (pending > 0) {
						currentWriter.flush();
						pending = 0;
						lastFlush = System.nanoTime();
					}
					continue;
				}
				if (STOP.equals(line)) {
					String remaining;
					while ((remaining = currentQueue.poll()) != null) {
						if (!STOP.equals(remaining)) currentWriter.write(remaining);
					}
					currentWriter.flush();
					break;
				}
				currentWriter.write(line);
				pending++;
				if (pending >= 32 || System.nanoTime() - lastFlush >= 250_000_000L) {
					currentWriter.flush();
					pending = 0;
					lastFlush = System.nanoTime();
				}
			}
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			GeilerAddons.LOGGER.warn("Tiki debug log writer interrupted");
		} catch (IOException e) {
			GeilerAddons.LOGGER.error("Could not write to the tiki debug log", e);
		} finally {
			long dropped = droppedLines.getAndSet(0);
			if (dropped > 0) {
				GeilerAddons.LOGGER.warn("Dropped {} queued tiki debug lines because the log queue was full", dropped);
			}
			try {
				currentWriter.close();
			} catch (IOException e) {
				GeilerAddons.LOGGER.error("Could not close the tiki debug log", e);
			}
			synchronized (TikiDebugLog.class) {
				// A writer can fail while the module remains armed. Clear all handles so the next tick
				// can reopen a fresh session instead of silently discarding every later line.
				if (writer == currentWriter) {
					path = null;
					writer = null;
					queue = null;
					worker = null;
				}
			}
		}
	}
}
