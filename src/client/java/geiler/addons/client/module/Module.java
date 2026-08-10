package geiler.addons.client.module;

import java.util.List;

public abstract class Module {
	private final String name;
	private final String description;
	private final Category category;
	private final List<ColorSetting> colorSettings;
	private final List<NumberSetting> numberSettings;
	private final List<BooleanSetting> booleanSettings;
	private final List<ModuleAction> actions;
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

	public boolean hasSettings() {
		return !colorSettings.isEmpty() || !numberSettings.isEmpty() || !booleanSettings.isEmpty() || !actions.isEmpty();
	}

	public boolean isEnabled() {
		return enabled;
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
