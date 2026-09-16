package geiler.addons.client.location;

/**
 * Named gate for Tiki features.
 *
 * <p>The official shared Hypixel Mod API is the sole location authority. In particular, a missing
 * handshake, a null/unknown server type, and an unknown island mode all fail closed instead of
 * allowing terrain guesses or stale state to activate the module.</p>
 */
public final class TorrhusPresence {
	private TorrhusPresence() {
	}

	/**
	 * Kept as a lifecycle no-op for the existing client tick wiring. Location state is event-driven
	 * now, so polling terrain here would reintroduce the false-positive path this gate prevents.
	 */
	public static void tick() {
	}

	/** Whether the latest location event explicitly names Torrhus Canyon. */
	public static boolean isPresent() {
		return HypixelModApi.currentIsland() == Island.TORRHUS_CANYON;
	}

	/** Why the Tiki module is idle, or null when the API names Torrhus Canyon. */
	public static String reason() {
		return HypixelModApi.reasonNotOn(Island.TORRHUS_CANYON);
	}
}
