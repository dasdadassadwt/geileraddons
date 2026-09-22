package geiler.addons.client.mixin;

import geiler.addons.client.gui.InventoryButtonOverlay;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Handles inventory buttons before the recipe-book screen gets first refusal on pointer events. */
@Mixin(AbstractRecipeBookScreen.class)
public abstract class AbstractRecipeBookScreenMixin {
	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void geileraddons$handleInventoryButtonClick(MouseButtonEvent event, boolean doubleClick,
		CallbackInfoReturnable<Boolean> cir) {
		if (InventoryButtonOverlay.mouseClicked((AbstractContainerScreen<?>) (Object) this, event)) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
	private void geileraddons$handleInventoryButtonDrag(MouseButtonEvent event, double dragX, double dragY,
		CallbackInfoReturnable<Boolean> cir) {
		if (InventoryButtonOverlay.mouseDragged((AbstractContainerScreen<?>) (Object) this, event)) {
			cir.setReturnValue(true);
		}
	}
}
