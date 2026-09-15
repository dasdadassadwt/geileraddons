package geiler.addons.client.module;

import java.util.Arrays;
import java.util.List;

/**
 * A setting whose value must be one of a small, fixed set of named choices.
 *
 * <p>Unlike a {@link TextSetting}, this cannot hold an intermediate or invalid value. That is
 * useful for presentation preferences: hand-edited config data and UI clicks both get the same
 * bounded behavior, while the settings screen can render the current choice without guessing how
 * to validate it.</p>
 */
public final class ChoiceSetting implements Setting {
	private final String name;
	private final List<String> choices;
	private String value;

	public ChoiceSetting(String name, String defaultValue, String... choices) {
		if (choices.length == 0) throw new IllegalArgumentException("A choice setting needs at least one choice");
		this.name = name;
		this.choices = List.copyOf(Arrays.asList(choices));
		if (this.choices.stream().anyMatch(choice -> choice == null || choice.isBlank())
			|| this.choices.stream().distinct().count() != this.choices.size()) {
			throw new IllegalArgumentException("Choice names must be non-empty and unique");
		}
		if (!this.choices.contains(defaultValue)) {
			throw new IllegalArgumentException("Default choice is not in the choice list");
		}
		this.value = defaultValue;
	}

	@Override
	public String name() {
		return name;
	}

	public List<String> choices() {
		return choices;
	}

	public String value() {
		return value;
	}

	public int index() {
		return choices.indexOf(value);
	}

	/** Applies a persisted or programmatic value, ignoring unknown choices safely. */
	public void setValue(String value) {
		if (choices.contains(value)) this.value = value;
	}

	public void selectNext() {
		select(index() + 1);
	}

	public void selectPrevious() {
		select(index() - 1);
	}

	private void select(int requested) {
		int wrapped = Math.floorMod(requested, choices.size());
		value = choices.get(wrapped);
	}
}
