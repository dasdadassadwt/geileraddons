package geiler.addons.client.gui;

/**
 * Motion profiles for the Click GUI. The setting stores the display name so old config files stay
 * human-readable; this enum owns the timings and easing so the screen never scatters magic
 * numbers through its input and rendering paths.
 */
public enum ClickGuiMotion {
	NONE("None", 0, 0, 0, 0, 0.0f),
	REDUCED("Reduced", 140, 220, 0, 180, 0.65f),
	EXPRESSIVE("Expressive", 280, 420, 28, 280, 1.35f);

	private final String settingValue;
	private final int transitionMillis;
	private final int lifecycleMillis;
	private final int cardStaggerMillis;
	private final int hoverMillis;
	private final float hoverAmplitude;

	ClickGuiMotion(String settingValue, int transitionMillis, int lifecycleMillis, int cardStaggerMillis,
		int hoverMillis, float hoverAmplitude) {
		this.settingValue = settingValue;
		this.transitionMillis = transitionMillis;
		this.lifecycleMillis = lifecycleMillis;
		this.cardStaggerMillis = cardStaggerMillis;
		this.hoverMillis = hoverMillis;
		this.hoverAmplitude = hoverAmplitude;
	}

	public int transitionMillis() {
		return transitionMillis;
	}

	public int lifecycleMillis() {
		return lifecycleMillis;
	}

	public int cardStaggerMillis() {
		return cardStaggerMillis;
	}

	public int hoverMillis() {
		return hoverMillis;
	}

	public float hoverAmplitude() {
		return hoverAmplitude;
	}

	public boolean animated() {
		return this != NONE;
	}

	/** Resolves a config value without allowing a hand-edited file to disable the GUI accidentally. */
	public static ClickGuiMotion fromSetting(String value) {
		for (ClickGuiMotion motion : values()) {
			if (motion.settingValue.equals(value)) return motion;
		}
		return EXPRESSIVE;
	}

	/** Easing for movement: fast response, soft landing, and no overshoot into another control. */
	public float ease(float progress) {
		progress = clamp(progress);
		if (this == NONE) return 1.0f;
		if (this == REDUCED) {
			return 1.0f - (float) Math.pow(1.0f - progress, 3.0);
		}
		// Quintic ease-out gives Expressive its lively first movement without making navigation
		// wait at the end of the transition.
		return 1.0f - (float) Math.pow(1.0f - progress, 5.0);
	}

	/** Close-only quadratic ease-out; unlike the opening profile it has no spring or long tail. */
	public float closeEase(float progress) {
		progress = clamp(progress);
		if (this == NONE) return 1.0f;
		return 1.0f - (1.0f - progress) * (1.0f - progress);
	}

	/** Close progress computed against the unchanged lifecycle duration. */
	public float closeProgress(long elapsedMillis) {
		if (this == NONE) return 1.0f;
		return closeEase(Math.max(0L, elapsedMillis) / (float) lifecycleMillis);
	}

	/** Monotonic panel scale for closing, kept separate from the expressive opening spring. */
	public float closeScale(float progress) {
		if (this == NONE) return 1.0f;
		float remaining = 1.0f - clamp(progress);
		return this == REDUCED ? 0.90f + 0.10f * remaining : 0.72f + 0.28f * remaining;
	}

	/** Small, bounded overshoot used only for the panel's scale and card entrance. */
	public float spring(float progress) {
		progress = clamp(progress);
		if (this == NONE) return 1.0f;
		if (this == REDUCED) return ease(progress);
		float c1 = 1.70158f;
		float c3 = c1 + 1.0f;
		float shifted = progress - 1.0f;
		return 1.0f + c3 * shifted * shifted * shifted + c1 * shifted * shifted;
	}

	private static float clamp(float value) {
		return Math.max(0.0f, Math.min(1.0f, value));
	}
}
