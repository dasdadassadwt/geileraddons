package geiler.addons.client.module;

import java.util.List;

/**
 * A named, collapsible section of a module's settings panel.
 *
 * <p>Purely presentational. A module still owns its settings in the flat lists {@link Module}
 * holds, and those are what the config reads and writes - so settings can be regrouped or
 * reordered freely without moving a single key in {@code geileraddons.json}.
 *
 * @param name               section heading, or null for an unnamed section that renders without a header
 * @param toggle             a switch drawn on the heading itself, or null for a plain heading
 * @param collapsedByDefault whether the section starts folded shut
 * @param settings           rows in display order
 * @param children           sub-sections drawn beneath those rows, and hidden when this one folds
 */
public record SettingGroup(String name, BooleanSetting toggle, boolean collapsedByDefault,
                           List<Setting> settings, List<SettingGroup> children) {
	public SettingGroup(String name, Setting... settings) {
		this(name, null, false, List.of(settings), List.of());
	}

	/**
	 * A section whose heading carries its own on/off switch.
	 *
	 * <p>A static factory rather than a constructor overload, because {@link BooleanSetting} is
	 * itself a {@link Setting}: an overload would win over the varargs one for every existing group
	 * that happens to open with a toggle - silently promoting that first row onto the header.
	 */
	public static SettingGroup switched(String name, BooleanSetting toggle, Setting... settings) {
		return new SettingGroup(name, toggle, false, List.of(settings), List.of());
	}

	/** A section that starts folded shut, for one long enough to bury everything after it. */
	public static SettingGroup folded(String name, Setting... settings) {
		return new SettingGroup(name, null, true, List.of(settings), List.of());
	}

	/** This section with sub-sections nested inside it, which fold away when it does. */
	public SettingGroup containing(SettingGroup... nested) {
		return new SettingGroup(name, toggle, collapsedByDefault, settings, List.of(nested));
	}
}
