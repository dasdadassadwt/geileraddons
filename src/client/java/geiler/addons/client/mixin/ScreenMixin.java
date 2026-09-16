package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.ExperimentSolverModule;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Removes the vanilla container background before the replacement experiment surface is
 * extracted. This is attached to Screen rather than a particular chest implementation because
 * Hypixel and other client mods can provide different AbstractContainerScreen subclasses.
 */
@Mixin(Screen.class)
public abstract class ScreenMixin {
	/**
	 * A container screen normally draws its background and then extracts the vanilla inventory
	 * contents. The experiment surface replaces both layers, so short-circuit the complete root
	 * extraction rather than relying only on the subclass hooks. This also covers Hypixel's custom
	 * container-screen subclasses, which may override the background method.
	 */
	@Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("HEAD"), cancellable = true)
	private void geileraddons$renderExperimentSurface(GuiGraphicsExtractor graphics, int mouseX,
		int mouseY, float delta, CallbackInfo ci) {
		Screen screen = (Screen) (Object) this;
		if (screen instanceof AbstractContainerScreen<?> container
			&& ExperimentSolverModule.INSTANCE.ownsScreen(container)) {
			graphics.nextStratum();
			ExperimentSolverModule.INSTANCE.renderCustomScreen(container, graphics, mouseX, mouseY, delta);
			// The replacement renderer may queue an item tooltip. The vanilla root method normally
			// drains that queue after extractRenderState; because this hook replaces the root, do it here.
			graphics.extractDeferredElements(mouseX, mouseY, delta);
			ci.cancel();
		}
	}

	/**
	 * ContainerScreen overrides extractBackground to draw the chest texture. Redirect the call at
	 * the common root so that override cannot reintroduce the inventory underneath our surface.
	 */
	@Redirect(method = "extractRenderStateWithTooltipAndSubtitles",
		at = @At(value = "INVOKE", target =
			"Lnet/minecraft/client/gui/screens/Screen;extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"))
	private void geileraddons$hideExperimentBackground(Screen screen, GuiGraphicsExtractor graphics,
		int mouseX, int mouseY, float delta) {
		if (screen instanceof AbstractContainerScreen<?> container
			&& ExperimentSolverModule.INSTANCE.ownsScreen(container)) return;
		screen.extractBackground(graphics, mouseX, mouseY, delta);
	}

	/** Covers non-container subclasses that do not override the base background method. */
	@Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
	private void geileraddons$hideBaseExperimentBackground(GuiGraphicsExtractor graphics, int mouseX,
		int mouseY, float delta, CallbackInfo ci) {
		Screen screen = (Screen) (Object) this;
		if (screen instanceof AbstractContainerScreen<?> container
			&& ExperimentSolverModule.INSTANCE.ownsScreen(container)) ci.cancel();
	}
}
