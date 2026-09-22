package geiler.addons.client.module;

public final class BooleanSetting implements Setting {
	private final String name;
	private final String displayName;
	private final boolean debugOnly;
	private final boolean cheatGated;
	/** Behaviour imposed while the Cheats gate is closed; only meaningful when {@link #cheatGated}. */
	private final boolean safeValue;
	private boolean value;

	public BooleanSetting(String name, boolean defaultValue) {
		this(name, name, defaultValue, false, false, false);
	}

	public BooleanSetting(String name, String displayName, boolean defaultValue) {
		this(name, displayName, defaultValue, false, false, false);
	}

	private BooleanSetting(String name, String displayName, boolean defaultValue, boolean debugOnly) {
		this(name, displayName, defaultValue, debugOnly, false, false);
	}

	private BooleanSetting(String name, String displayName, boolean defaultValue, boolean debugOnly,
		boolean cheatGated, boolean safeWhenGated) {
		this.name = name;
		this.displayName = displayName;
		this.debugOnly = debugOnly;
		this.cheatGated = cheatGated;
		this.safeValue = safeWhenGated;
		this.value = defaultValue;
	}

	/** Creates a persisted toggle that is visible and effective only while Dev Debug is enabled. */
	public static BooleanSetting debug(String name, boolean defaultValue) {
		return new BooleanSetting(name, name, defaultValue, true);
	}

	/**
	 * Creates a persisted toggle whose off-state is a cheat.
	 *
	 * <p>The safe state is a parameter rather than always false, because the safe answer is not the
	 * same for every cheat: a depth check forced <em>on</em> is what stops a see-through-walls
	 * overlay, so "off" cannot be what the gate imposes there. While the gate is closed the row
	 * reports {@code safeWhenGated} and does not respond to clicks, but the stored value is never
	 * touched - switching Cheats back on restores exactly what the player had set.
	 */
	public static BooleanSetting cheat(String name, String displayName, boolean defaultValue,
		boolean safeWhenGated) {
		return new BooleanSetting(name, displayName, defaultValue, false, true, safeWhenGated);
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
	 * The effective value consumed by modules.
	 *
	 * <p>Two gates can override the stored value, and the cheat gate is checked first because it is
	 * the one that decides whether an overlay is allowed to see through terrain at all. The raw
	 * value stays stored behind both of them, so re-enabling Debug or Cheats restores the user's
	 * previous choices.
	 */
	public boolean value() {
		if (cheatGated && !CheatsState.enabled()) return safeValue;
		return !debugOnly || DebugState.enabled() ? value : false;
	}

	/** The user-selected value, unaffected by the diagnostic or cheat gate. */
	public boolean rawValue() {
		return value;
	}

	@Override
	public boolean isCheatGated() {
		return cheatGated;
	}

	@Override
	public boolean forcedByCheats() {
		return cheatGated && !CheatsState.enabled();
	}

	public void setValue(boolean value) {
		this.value = value;
	}

	public void toggle() {
		this.value = !this.value;
	}
}
