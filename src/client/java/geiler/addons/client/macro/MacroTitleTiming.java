package geiler.addons.client.macro;

/** Pure fade envelope for a centered macro title. Times are elapsed and configured milliseconds. */
public final class MacroTitleTiming {
	private MacroTitleTiming() { }

	public static float opacity(long elapsedMillis, int fadeInMillis, int holdMillis, int fadeOutMillis) {
		long elapsed = Math.max(0, elapsedMillis);
		int fadeIn = Math.max(0, fadeInMillis);
		int hold = Math.max(0, holdMillis);
		int fadeOut = Math.max(0, fadeOutMillis);
		float in = fadeIn == 0 ? 1.0f : Math.min(1.0f, elapsed / (float) fadeIn);
		long fadeOutStart = (long) fadeIn + hold;
		float out = fadeOut == 0 || elapsed < fadeOutStart ? 1.0f
			: Math.max(0.0f, 1.0f - (elapsed - fadeOutStart) / (float) fadeOut);
		return Math.min(in, out);
	}
}
