package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.ExperimentSolverModule;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stops the vanilla chest texture when a standard container hosts the custom experiment UI. */
@Mixin(ContainerScreen.class)
public abstract class ContainerScreenMixin {
	@Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
	private void geileraddons$hideExperimentContainerBackground(GuiGraphicsExtractor graphics,
		int mouseX, int mouseY, float delta, CallbackInfo ci) {
		AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
		if (ExperimentSolverModule.INSTANCE.ownsScreen(screen)) ci.cancel();
	}
}
