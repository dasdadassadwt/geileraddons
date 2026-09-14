package geiler.addons.client.module;

/**
 * A button row inside a module's settings panel - e.g. "Manage Tiki Coords", which opens a
 * screen of its own rather than changing a value in place.
 */
public record ModuleAction(String label, Runnable onClick) implements Setting {
	@Override
	public String name() {
		return label;
	}
}
