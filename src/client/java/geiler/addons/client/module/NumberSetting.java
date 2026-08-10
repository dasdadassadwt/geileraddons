package geiler.addons.client.module;

public final class NumberSetting {
	private final String name;
	private final float min;
	private final float max;
	/** Whole-number setting (tick counts and the like): snapped on every write, shown without decimals. */
	private final boolean integer;
	private float value;

	public NumberSetting(String name, float min, float max, float defaultValue) {
		this(name, min, max, defaultValue, false);
	}

	public NumberSetting(String name, float min, float max, float defaultValue, boolean integer) {
		this.name = name;
		this.min = min;
		this.max = max;
		this.integer = integer;
		this.value = clamp(defaultValue);
	}

	public String name() {
		return name;
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
		this.value = clamp(value);
	}

	/** 0-1 position along the slider track. */
	public float fraction() {
		return (value - min) / (max - min);
	}

	public void setFraction(float fraction) {
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
