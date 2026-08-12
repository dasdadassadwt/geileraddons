package geiler.addons.client.module;

import java.util.ArrayList;
import java.util.List;

public abstract class Module {
	private final String name;
	private final String description;
	private final Category category;
	private final List<Setting> settings;
	private final List<ColorSetting> colorSettings;
	private final List<NumberSetting> numberSettings;
	private final List<BooleanSetting> booleanSettings;
	private final List<TextSetting> textSettings;
	private final List<ModuleAction> actions;
	private List<SettingGroup> groups;
	private boolean enabled;

	/**
	 * One flat list of settings in declaration order, sorted into typed lists here.
	 *
	 * <p>Takes everything at once rather than a list per type: the per-type constructor this
	 * replaced needed a new parameter and a new overload every time a setting type was added, and
	 * callers had to keep four lists in step by hand. The typed accessors below are what the config
	 * and the settings panel read, so nothing downstream had to change.
	 */
	protected Module(String name, String description, Category category, Setting... settings) {
		this.name = name;
		this.description = description;
		this.category = category;
		this.settings = List.of(settings);
		this.colorSettings = ofType(ColorSetting.class);
		this.numberSettings = ofType(NumberSetting.class);
		this.booleanSettings = ofType(BooleanSetting.class);
		this.textSettings = ofType(TextSetting.class);
		this.actions = ofType(ModuleAction.class);
	}

	private <T extends Setting> List<T> ofType(Class<T> type) {
		List<T> matches = new ArrayList<>();
		for (Setting setting : settings) {
			if (type.isInstance(setting)) {
				matches.add(type.cast(setting));
			}
		}
		return List.copyOf(matches);
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

	public List<TextSetting> textSettings() {
		return textSettings;
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
			groups = List.of(new SettingGroup(null, settings));
		}
		return groups;
	}

	public boolean hasSettings() {
		return !settings.isEmpty();
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
