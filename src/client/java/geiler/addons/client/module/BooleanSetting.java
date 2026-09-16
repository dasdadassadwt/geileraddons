package geiler.addons.client.module;

public final class BooleanSetting implements Setting {
	private final String name;
	private final String displayName;
	private final boolean debugOnly;
	private boolean value;

	public BooleanSetting(String name, boolean defaultValue) {
		this(name, name, defaultValue, false);
	}

	public BooleanSetting(String name, String displayName, boolean defaultValue) {
		this(name, displayName, defaultValue, false);
	}

	private BooleanSetting(String name, String displayName, boolean defaultValue, boolean debugOnly) {
		this.name = name;
		this.displayName = displayName;
		this.debugOnly = debugOnly;
		this.value = defaultValue;
	}

	/** Creates a persisted toggle that is visible and effective only while Dev Debug is enabled. */
	public static BooleanSetting debug(String name, boolean defaultValue) {
		return new BooleanSetting(name, name, defaultValue, true);
	}

	@Override
	public String name() {
		return name;
	}

	@Override
	public String displayName() {
		return displayName;
	}

	@Override
	public boolean isDebugOnly() {
		return debugOnly;
	}

	/**
	 * The effective value consumed by modules. The raw value remains stored while diagnostics are
	 * globally disabled, so re-enabling Debug restores the user's previous choices.
	 */
	public boolean value() {
		return !debugOnly || DebugState.enabled() ? value : false;
	}

	/** The user-selected value, unaffected by the global diagnostic gate. */
	public boolean rawValue() {
		return value;
	}

	public void setValue(boolean value) {
		this.value = value;
	}

	public void toggle() {
		this.value = !this.value;
	}
}
