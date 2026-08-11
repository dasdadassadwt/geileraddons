package geiler.addons.client.module;

import java.util.List;

/**
 * A named, collapsible section of a module's settings panel.
 *
 * <p>Purely presentational. A module still owns its settings in the flat lists {@link Module}
 * holds, and those are what the config reads and writes - so settings can be regrouped or
 * reordered freely without moving a single key in {@code geileraddons.json}.
 *
 * @param name     section heading, or null for an unnamed section that renders without a header
 * @param settings rows in display order
 */
public record SettingGroup(String name, List<Setting> settings) {
	public SettingGroup(String name, Setting... settings) {
		this(name, List.of(settings));
	}
}
