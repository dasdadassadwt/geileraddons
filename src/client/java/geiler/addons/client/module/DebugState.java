package geiler.addons.client.module;

/** Runtime gate for optional diagnostic settings. State is changed by the Dev Debug module. */
public final class DebugState {
	private static volatile boolean enabled;

	private DebugState() {
	}

	public static boolean enabled() {
		return enabled;
	}

	/** Called by {@code DebugModule} when its persisted module switch changes. */
	public static void setEnabled(boolean enabled) {
		DebugState.enabled = enabled;
	}
}
