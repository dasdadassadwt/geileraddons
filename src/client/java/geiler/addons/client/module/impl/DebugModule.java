package geiler.addons.client.module.impl;

import geiler.addons.client.module.Category;
import geiler.addons.client.module.DebugState;
import geiler.addons.client.module.Module;

/**
 * The always-available master switch for optional diagnostic controls throughout the mod.
 *
 * <p>This is intentionally a module switch rather than another BooleanSetting: it reuses the
 * existing enabled-state persistence and makes the Dev category's only card self-explanatory.
 */
public final class DebugModule extends Module {
	public static final DebugModule INSTANCE = new DebugModule();

	private DebugModule() {
		super("Debug", "Enables diagnostic settings and output across the mod.", Category.DEV);
	}

	@Override
	protected void onEnable() {
		DebugState.setEnabled(true);
	}

	@Override
	protected void onDisable() {
		DebugState.setEnabled(false);
	}
}
