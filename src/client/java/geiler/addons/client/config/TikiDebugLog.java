package geiler.addons.client.config;

import geiler.addons.client.module.Category;

import java.nio.file.Path;

/** Compatibility facade for the Tiki module's existing debug setting. */
public final class TikiDebugLog {
	private static final Category CATEGORY = Category.HUNTING;
	private static final String MODULE = "Tiki Helper";

	private TikiDebugLog() {
	}

	public static void open() {
		GeilerAddonsLog.start();
	}

	/** The central writer is shared with every module, so closing Tiki alone must not stop it. */
	public static void close() {
	}

	public static void write(long tick, String line) {
		GeilerAddonsLog.write(CATEGORY, MODULE, tick, line);
	}

	public static Path path() {
		return GeilerAddonsLog.path(CATEGORY, MODULE);
	}
}
