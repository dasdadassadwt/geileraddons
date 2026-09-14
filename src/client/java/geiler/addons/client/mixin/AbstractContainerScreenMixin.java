package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.PartyFinderStatsModule;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures Group Builder selections before the confirmation click closes the menu. */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {
	@Inject(method = "slotClicked", at = @At("HEAD"))
	private void geileraddons$captureDungeonFloor(Slot slot, int slotId, int button,
		ContainerInput input, CallbackInfo ci) {
		PartyFinderStatsModule.INSTANCE.onContainerSlotClick(
			(AbstractContainerScreen<?>) (Object) this, slot, button, input);
	}
}
