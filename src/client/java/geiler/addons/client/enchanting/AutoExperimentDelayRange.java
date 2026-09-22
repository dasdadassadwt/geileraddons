package geiler.addons.client.enchanting;

import java.util.function.LongUnaryOperator;

/** Normalized, inclusive delay range used between successful Auto Experiments clicks. */
public record AutoExperimentDelayRange(int minimumMillis, int maximumMillis) {
	public static final int MINIMUM_MILLIS = 0;
	public static final int MAXIMUM_MILLIS = 2_000;
	public static final int DEFAULT_MINIMUM_MILLIS = 190;
	public static final int DEFAULT_MAXIMUM_MILLIS = 260;
	private static final int LEGACY_RANGE_MILLIS = 70;

	public AutoExperimentDelayRange {
		int minimum = clamp(minimumMillis);
		int maximum = clamp(maximumMillis);
		if (minimum > maximum) {
			int swap = minimum;
			minimum = maximum;
			maximum = swap;
		}
		minimumMillis = minimum;
		maximumMillis = maximum;
	}

	/** Preserves an old single delay as the lower endpoint and widens it by 70 ms. */
	public static AutoExperimentDelayRange fromLegacyDelay(float legacyDelayMillis) {
		if (!Float.isFinite(legacyDelayMillis)) {
			return new AutoExperimentDelayRange(DEFAULT_MINIMUM_MILLIS, DEFAULT_MAXIMUM_MILLIS);
		}
		int minimum = clamp(Math.round(legacyDelayMillis));
		int maximum = Math.min(MAXIMUM_MILLIS, minimum + LEGACY_RANGE_MILLIS);
		return new AutoExperimentDelayRange(minimum, maximum);
	}

	/** Restores new endpoint values first, using an old saved single delay for missing endpoints. */
	public static AutoExperimentDelayRange restore(Float legacyDelayMillis, Float savedMinimumMillis,
		Float savedMaximumMillis, int currentMinimumMillis, int currentMaximumMillis) {
		AutoExperimentDelayRange fallback = legacyDelayMillis == null
			? new AutoExperimentDelayRange(currentMinimumMillis, currentMaximumMillis)
			: fromLegacyDelay(legacyDelayMillis);
		int minimum = restoredValue(savedMinimumMillis, fallback.minimumMillis);
		int maximum = restoredValue(savedMaximumMillis, fallback.maximumMillis);
		return new AutoExperimentDelayRange(minimum, maximum);
	}

	/**
	 * Selects one value from the normalized inclusive range. The supplier receives the exclusive
	 * upper bound for a zero-based random offset, which keeps boundary behavior easy to verify.
	 */
	public int sampleMillis(LongUnaryOperator boundedRandom) {
		if (boundedRandom == null) throw new IllegalArgumentException("boundedRandom must not be null");
		long width = (long) maximumMillis - minimumMillis + 1L;
		long offset = boundedRandom.applyAsLong(width);
		if (offset < 0 || offset >= width) {
			throw new IllegalArgumentException("random offset must be within the requested bound");
		}
		return minimumMillis + (int) offset;
	}

	public long sampleNanos(LongUnaryOperator boundedRandom) {
		return sampleMillis(boundedRandom) * 1_000_000L;
	}

	private static int clamp(int value) {
		return Math.max(MINIMUM_MILLIS, Math.min(MAXIMUM_MILLIS, value));
	}

	private static int restoredValue(Float savedValue, int fallback) {
		return savedValue == null || !Float.isFinite(savedValue) ? fallback : clamp(Math.round(savedValue));
	}
}
