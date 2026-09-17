package geiler.addons.client.module;

/**
 * A free-text row in a module's settings panel, for values a slider can't express well.
 *
 * <p>Stores whatever was typed, including text that isn't valid yet - a field that refused
 * intermediate states would be unusable, since "3" has to be typeable on the way to "30".
 * Interpreting the string is the module's job; see {@link #intValue}.
 */
public final class TextSetting implements Setting {
	private final String name;
	private final String displayName;
	private final String defaultValue;
	private final int maxLength;
	private String value;

	public TextSetting(String name, String defaultValue, int maxLength) {
		this(name, name, defaultValue, maxLength);
	}

	public TextSetting(String name, String displayName, String defaultValue, int maxLength) {
		this.name = name;
		this.displayName = displayName;
		this.defaultValue = defaultValue == null ? "" : defaultValue;
		this.maxLength = Math.max(0, maxLength);
		this.value = this.defaultValue.length() > this.maxLength
			? this.defaultValue.substring(0, this.maxLength) : this.defaultValue;
	}

	@Override
	public String name() {
		return name;
	}

	@Override
	public String displayName() {
		return displayName;
	}

	public String value() {
		return value;
	}

	public int maxLength() {
		return maxLength;
	}

	public void setValue(String value) {
		if (value == null) return;
		this.value = value.length() > maxLength ? value.substring(0, maxLength) : value;
	}

	/** The value as a whole number, or {@code fallback} if it isn't one. */
	public int intValue(int fallback) {
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/** The value as a whole number that may exceed the range of an int, or {@code fallback}. */
	public long longValue(long fallback) {
		try {
			return Long.parseLong(value.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/** The value as a decimal number, or {@code fallback} if it is not one. */
	public double doubleValue(double fallback) {
		try {
			double parsed = Double.parseDouble(value.trim());
			return Double.isFinite(parsed) ? parsed : fallback;
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	public void reset() {
		value = defaultValue.length() > maxLength ? defaultValue.substring(0, maxLength) : defaultValue;
	}
}
