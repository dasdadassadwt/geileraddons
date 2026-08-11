package geiler.addons.client.module;

import java.util.ArrayList;
import java.util.List;

public abstract class Module {
	private final String name;
	private final String description;
	private final Category category;
	private final List<ColorSetting> colorSettings;
	private final List<NumberSetting> numberSettings;
	private final List<BooleanSetting> booleanSettings;
	private final List<ModuleAction> actions;
	private List<SettingGroup> groups;
	private boolean enabled;

	protected Module(String name, String description, Category category, List<ColorSetting> colorSettings) {
		this(name, description, category, colorSettings, List.of(), List.of(), List.of());
	}

	protected Module(String name, String description, Category category, List<ColorSetting> colorSettings, List<NumberSetting> numberSettings) {
		this(name, description, category, colorSettings, numberSettings, List.of(), List.of());
	}

	protected Module(
		String name, String description, Category category, List<ColorSetting> colorSettings,
		List<NumberSetting> numberSettings, List<BooleanSetting> booleanSettings, List<ModuleAction> actions
	) {
		this.name = name;
		this.description = description;
		this.category = category;
		this.colorSettings = colorSettings;
		this.numberSettings = numberSettings;
		this.booleanSettings = booleanSettings;
		this.actions = actions;
	}

	public String name() {
		return name;
	}

	public String description() {
		return description;
	}

	public Category category() {
		return category;
	}

	public List<ColorSetting> colorSettings() {
		return colorSettings;
	}

	public List<NumberSetting> numberSettings() {
		return numberSettings;
	}

	public List<BooleanSetting> booleanSettings() {
		return booleanSettings;
	}

	public List<ModuleAction> actions() {
		return actions;
	}

	/**
	 * Declares how the settings panel is sectioned. Call from the subclass constructor.
	 *
	 * <p>Presentation only - the flat setting lists stay the source of truth for persistence, so
	 * regrouping or reordering settings never moves a key in the config file.
	 */
	protected final void group(SettingGroup... groups) {
		this.groups = List.of(groups);
	}

	/**
	 * The settings panel's sections. A module that never called {@link #group} gets a single
	 * unnamed section holding everything, which renders exactly as the panel did before groups
	 * existed.
	 */
	public List<SettingGroup> groups() {
		if (groups == null) {
			List<Setting> all = new ArrayList<>();
			all.addAll(colorSettings);
			all.addAll(numberSettings);
			all.addAll(booleanSettings);
			all.addAll(actions);
			groups = List.of(new SettingGroup(null, all));
		}
		return groups;
	}

	public boolean hasSettings() {
		return !colorSettings.isEmpty() || !numberSettings.isEmpty() || !booleanSettings.isEmpty() || !actions.isEmpty();
	}

	public boolean isEnabled() {
		return enabled;
	}

	/**
	 * Whether the module should actually be doing anything right now.
	 *
	 * <p>{@link #isEnabled()} is the user's intent; this is that intent plus context, so a module
	 * can stay switched on while sitting idle somewhere it does not apply. Overriding this is how
	 * a module gates itself on location without ever flipping its own switch behind the user's
	 * back - the Click GUI dims anything enabled but inactive and shows {@link #inactiveReason()}.
	 */
	public boolean isActive() {
		return enabled;
	}

	/**
	 * Short explanation of why an enabled module is idle, shown in the Click GUI. Null whenever
	 * the module is active, or disabled outright - there is nothing to explain in either case.
	 */
	public String inactiveReason() {
		return null;
	}

	public final void setEnabled(boolean enabled) {
		if (this.enabled == enabled) return;
		this.enabled = enabled;
		if (enabled) {
			onEnable();
		} else {
			onDisable();
		}
	}

	public final void toggle() {
		setEnabled(!enabled);
	}

	/** Called when the module is switched on, after {@link #isEnabled()} already reflects the new state. */
	protected void onEnable() {
	}

	/** Called when the module is switched off, after {@link #isEnabled()} already reflects the new state. */
	protected void onDisable() {
	}
}
