package geiler.addons.client.macro;

/** Pure guards shared by the macro runtime and its offline checks. */
public final class MacroRuntimeRules {
	private MacroRuntimeRules() {
	}

	public static boolean canStart(boolean sameStackRunning, int activeRuns, int maximumRuns) {
		return !sameStackRunning && activeRuns >= 0 && maximumRuns > 0 && activeRuns < maximumRuns;
	}

	public static boolean canEnterCall(int currentDepth, int maximumDepth, boolean recursive) {
		return !recursive && currentDepth >= 0 && maximumDepth > 0 && currentDepth < maximumDepth;
	}

	public static boolean shouldStartWorldRun(boolean inside, boolean entered, boolean sameStackRunning,
		boolean oncePerWorld, boolean firedThisWorld, long completedAtNanos, long nowNanos,
		long repeatDelayNanos) {
		if (!inside || sameStackRunning) return false;
		if (oncePerWorld) return entered && !firedThisWorld;
		if (entered) return true;
		return completedAtNanos > 0 && nowNanos >= completedAtNanos
			&& nowNanos - completedAtNanos >= Math.max(0, repeatDelayNanos);
	}

	public static boolean canSelectHotbar(boolean containerOpen) {
		return !containerOpen;
	}

	public static boolean shouldReleaseHeldMouse(boolean screenOpen, long nowNanos, long deadlineNanos) {
		return screenOpen || nowNanos >= deadlineNanos;
	}

	public static int colorWithOpacity(int opaqueColor, int opacity) {
		int alpha = Math.max(0, Math.min(255, opacity));
		return (opaqueColor & 0x00FFFFFF) | (alpha << 24);
	}
}
