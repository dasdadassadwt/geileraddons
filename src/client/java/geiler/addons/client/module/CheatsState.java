package geiler.addons.client.module;

import geiler.addons.client.module.impl.GeneralModule;

/**
 * Runtime gate for cheat-style options.
 *
 * <p>Reads the General module's switch directly rather than keeping a second copy of it: two
 * fields would eventually disagree, and the one the player can see is the one that is right. The
 * read is a plain field access on the same thread that renders the panel, so there is nothing to
 * synchronise. State is changed by {@code GeneralModule}.
 */
public final class CheatsState {
	private CheatsState() {
	}

	public static boolean enabled() {
		return GeneralModule.INSTANCE.cheats().value();
	}
}
