package geiler.addons.client.module;

public final class BooleanSetting implements Setting {
	private final String name;
	private boolean value;

	public BooleanSetting(String name, boolean defaultValue) {
		this.name = name;
		this.value = defaultValue;
	}

	@Override
	public String name() {
		return name;
	}

	public boolean value() {
		return value;
	}

	public void setValue(boolean value) {
		this.value = value;
	}

	public void toggle() {
		this.value = !this.value;
	}
}
