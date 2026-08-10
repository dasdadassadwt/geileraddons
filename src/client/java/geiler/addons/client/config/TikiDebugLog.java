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

/**
 * One log file per debug session under the game's {@code logs/} folder. Every line is flushed
 * immediately - a crash or an alt-F4 mid-experiment must not cost the data it was gathering.
 */
public final class TikiDebugLog {
	private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
	private static final DateTimeFormatter LINE_STAMP = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

	private static Path path;
	private static Writer writer;

	private TikiDebugLog() {
	}

	public static void open() {
		close();
		try {
			Path directory = FabricLoader.getInstance().getGameDir().resolve("logs");
			Files.createDirectories(directory);
			path = directory.resolve("tiki-debug-" + LocalDateTime.now().format(FILE_STAMP) + ".log");
			writer = Files.newBufferedWriter(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			write(0, "SESSION START");
		} catch (IOException e) {
			GeilerAddons.LOGGER.error("Could not open the tiki debug log", e);
			path = null;
			writer = null;
		}
	}

	public static void close() {
		if (writer == null) return;
		try {
			write(0, "SESSION END");
			writer.close();
		} catch (IOException e) {
			GeilerAddons.LOGGER.error("Could not close the tiki debug log", e);
		}
		writer = null;
	}

	/** @param tick client ticks since the module was enabled, so events can be ordered exactly */
	public static void write(long tick, String line) {
		if (writer == null) return;
		try {
			writer.write("[" + LocalDateTime.now().format(LINE_STAMP) + "] [t" + tick + "] " + line + System.lineSeparator());
			writer.flush();
		} catch (IOException e) {
			GeilerAddons.LOGGER.error("Could not write to the tiki debug log", e);
		}
	}

	public static Path path() {
		return path;
	}
}
