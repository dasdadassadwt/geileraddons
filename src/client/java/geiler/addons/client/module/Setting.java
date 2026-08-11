package geiler.addons.client.module;

/**
 * Anything that can appear as a row in a module's settings panel.
 *
 * <p>Exists so a {@link SettingGroup} can hold colors, numbers, toggles and buttons in whatever
 * order reads best, instead of the panel being stuck rendering one type after another.
 */
public sealed interface Setting permits BooleanSetting, ColorSetting, NumberSetting, ModuleAction {
	String name();
}
