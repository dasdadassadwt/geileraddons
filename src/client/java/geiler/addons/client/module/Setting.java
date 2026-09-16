package geiler.addons.client.module;

/**
 * Anything that can appear as a row in a module's settings panel.
 *
 * <p>Exists so a {@link SettingGroup} can hold colors, numbers, toggles and buttons in whatever
 * order reads best, instead of the panel being stuck rendering one type after another.
 */
public sealed interface Setting permits BooleanSetting, ChoiceSetting, ColorSetting, NumberSetting, TextSetting, ModuleAction {
	String name();

	/**
	 * Label shown in the settings panel. Keep this separate from {@link #name()}, which is the
	 * stable persistence key for the setting.
	 */
	default String displayName() {
		return name();
	}

	/** Whether this row is part of the optional diagnostic surface. */
	default boolean isDebugOnly() {
		return false;
	}
}
