package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.PartyFinderStatsModule;
import geiler.addons.client.module.impl.ExperimentSolverModule;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Captures Group Builder selections before the confirmation click closes the menu. */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {
	@Inject(method = "init", at = @At("HEAD"))
	private void geileraddons$attachExperimentListener(CallbackInfo ci) {
		ExperimentSolverModule.INSTANCE.onScreenOpened((AbstractContainerScreen<?>) (Object) this);
	}
	/**
	 * Replaces the vanilla inventory extraction only while the experiment adapter owns the
	 * recognized screen. The adapter draws the complete surface, so the vanilla slot grid cannot
	 * peek through or receive a second render pass underneath it.
	 */
	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void geileraddons$renderExperimentSurface(GuiGraphicsExtractor graphics, int mouseX,
		int mouseY, float delta, CallbackInfo ci) {
		AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
		if (ExperimentSolverModule.INSTANCE.renderCustomScreen(screen, graphics, mouseX, mouseY, delta)) {
			ci.cancel();
		}
	}

	/**
	 * The root extraction hook normally cancels this path already. Keep this guard as well because
	 * 26.1 has separate extraction methods: if another mixin re-enters contents extraction, the
	 * original puzzle item textures must still not appear behind the replacement surface.
	 */
	@Inject(method = "extractContents", at = @At("HEAD"), cancellable = true)
	private void geileraddons$hideVanillaExperimentContents(GuiGraphicsExtractor graphics, int mouseX,
		int mouseY, float delta, CallbackInfo ci) {
		AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
		if (ExperimentSolverModule.INSTANCE.ownsScreen(screen)) ci.cancel();
	}

	/**
	 * Custom coordinates are translated back to the original menu slot here. Rejected clicks are
	 * swallowed while the custom surface is active so a hidden vanilla slot cannot be activated.
	 */
	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void geileraddons$clickExperimentSurface(MouseButtonEvent event, boolean doubleClick,
		CallbackInfoReturnable<Boolean> cir) {
		AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
		if (ExperimentSolverModule.INSTANCE.handleCustomClick(screen, event,
			(slot, slotId, button, input) -> {
				((geiler.addons.client.mixin.AbstractContainerScreenInvoker) (Object) screen)
					.geileraddons$invokeSlotClicked(slot, slotId, button, input);
			})) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "slotClicked", at = @At("HEAD"))
	private void geileraddons$captureDungeonFloor(Slot slot, int slotId, int button,
		ContainerInput input, CallbackInfo ci) {
		PartyFinderStatsModule.INSTANCE.onContainerSlotClick(
			(AbstractContainerScreen<?>) (Object) this, slot, button, input);
	}

	/**
	 * The replacement surface has no vanilla slot coordinates. Prevent a click that missed its
	 * custom panel from activating an invisible inventory slot, but keep -999 outside-click handling
	 * and the module's deliberate invoker dispatch available.
	 */
	@Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true)
	private void geileraddons$blockExperimentClickThrough(Slot slot, int slotId, int button,
		ContainerInput input, CallbackInfo ci) {
		AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
		if (slotId >= 0 && ExperimentSolverModule.INSTANCE.ownsScreen(screen)
			&& !ExperimentSolverModule.INSTANCE.isDispatchingCustomClick()) {
			ci.cancel();
		}
	}

	@Inject(method = "removed", at = @At("HEAD"))
	private void geileraddons$resetExperimentSurface(CallbackInfo ci) {
		ExperimentSolverModule.INSTANCE.onScreenClosed();
	}
}
