package geiler.addons.client.module.impl;

import geiler.addons.client.module.Category;
import geiler.addons.client.module.Module;

/** Shows the vanilla container slot index used by macro slot-click steps. */
public final class SlotIdsModule extends Module {
	public static final SlotIdsModule INSTANCE = new SlotIdsModule();

	private SlotIdsModule() {
		super("Slot IDs", "Shows each container slot's runtime index for macro setup.", Category.DEV);
	}
}
