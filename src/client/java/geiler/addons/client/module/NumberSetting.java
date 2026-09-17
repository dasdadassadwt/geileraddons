package geiler.addons.client.module;

public final class NumberSetting implements Setting {
	private final String name;
	private final String displayName;
	private final float min;
	private final float max;
	/** Whole-number setting (tick counts and the like): snapped on every write, shown without decimals. */
	private final boolean integer;
	private float value;

	public NumberSetting(String name, float min, float max, float defaultValue) {
		this(name, name, min, max, defaultValue, false);
	}

	public NumberSetting(String name, float min, float max, float defaultValue, boolean integer) {
		this(name, name, min, max, defaultValue, integer);
	}

	public NumberSetting(String name, String displayName, float min, float max, float defaultValue) {
		this(name, displayName, min, max, defaultValue, false);
	}

	public NumberSetting(String name, String displayName, float min, float max, float defaultValue,
		boolean integer) {
		if (!Float.isFinite(min) || !Float.isFinite(max) || min > max) {
			throw new IllegalArgumentException("Invalid number setting range");
		}
		if (!Float.isFinite(defaultValue)) defaultValue = min;
		this.name = name;
		this.displayName = displayName;
		this.min = min;
		this.max = max;
		this.integer = integer;
		this.value = clamp(defaultValue);
	}

	@Override
	public String name() {
		return name;
	}

	@Override
	public String displayName() {
		return displayName;
	}

	public float value() {
		return value;
	}

	public int intValue() {
		return Math.round(value);
	}

	public boolean isInteger() {
		return integer;
	}

	public void setValue(float value) {
		if (!Float.isFinite(value)) return;
		this.value = clamp(value);
	}

	/** 0-1 position along the slider track. */
	public float fraction() {
		if (max == min) return 0;
		return (value - min) / (max - min);
	}

	public void setFraction(float fraction) {
		if (!Float.isFinite(fraction)) return;
		fraction = Math.max(0, Math.min(1, fraction));
		this.value = clamp(min + (max - min) * fraction);
	}

	/** Slider read-out text - "40" reads better than "40.00" for a tick count. */
	public String display() {
		return integer ? String.valueOf(intValue()) : String.format("%.2f", value);
	}

	private float clamp(float v) {
		v = Math.max(min, Math.min(max, v));
		return integer ? Math.round(v) : v;
	}
}
