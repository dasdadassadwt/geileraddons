package geiler.addons.client.gui;

/** Offline checks for the close lifecycle curve, panel scale, and configured durations. */
public final class ClickGuiMotionChecks {
	private ClickGuiMotionChecks() { }

	public static void run() {
		checkProfile(ClickGuiMotion.NONE, 0, 0, 1.0f);
		checkProfile(ClickGuiMotion.REDUCED, 140, 220, 0.90f);
		checkProfile(ClickGuiMotion.EXPRESSIVE, 280, 420, 0.72f);
		check(closeNear(ClickGuiMotion.EXPRESSIVE.closeEase(0.5f), 0.75f),
			"closing uses the specified quadratic ease-out");
	}

	private static void checkProfile(ClickGuiMotion motion, int transitionMillis,
		int lifecycleMillis, float closedScale) {
		check(motion.transitionMillis() == transitionMillis, motion + " keeps its transition duration");
		check(motion.lifecycleMillis() == lifecycleMillis, motion + " keeps its lifecycle duration");
		if (motion == ClickGuiMotion.NONE) {
			check(motion.closeProgress(0) == 1.0f && motion.closeScale(1) == 1.0f,
				"None still closes immediately at unit scale");
			return;
		}

		check(motion.closeProgress(0) == 0.0f, motion + " begins at the open state");
		check(motion.closeProgress(lifecycleMillis - 1) < 1.0f,
			motion + " does not finish before its lifecycle endpoint");
		check(motion.closeProgress(lifecycleMillis) == 1.0f,
			motion + " reaches completion at its unchanged lifecycle endpoint");
		check(motion.closeProgress(lifecycleMillis + 50) == 1.0f,
			motion + " clamps after its lifecycle endpoint");

		float lastProgress = -1.0f;
		float lastScale = Float.POSITIVE_INFINITY;
		for (int step = 0; step <= 1000; step++) {
			float input = step / 1000.0f;
			float progress = motion.closeEase(input);
			float scale = motion.closeScale(progress);
			check(progress >= lastProgress, motion + " close progress is monotonic");
			check(scale <= lastScale, motion + " close panel scale is monotonic");
			lastProgress = progress;
			lastScale = scale;
		}
		check(closeNear(motion.closeScale(1.0f), closedScale),
			motion + " ends at its configured closed scale");
	}

	private static boolean closeNear(float actual, float expected) {
		return Math.abs(actual - expected) < 0.00001f;
	}

	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}
}
