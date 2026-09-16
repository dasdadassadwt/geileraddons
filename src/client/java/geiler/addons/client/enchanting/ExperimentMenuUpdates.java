package geiler.addons.client.enchanting;

import net.minecraft.world.inventory.AbstractContainerMenu;

/** Shared boundary for both single-slot and full-content server updates. */
public final class ExperimentMenuUpdates {
	private ExperimentMenuUpdates() { }

	public static void afterServerUpdate(AbstractContainerMenu menu, Runnable markDirty) {
		markDirty.run();
		// broadcastChanges diffs lastSlots, making a second broadcaster (e.g. Skyblocker) harmless.
		menu.broadcastChanges();
	}
}
