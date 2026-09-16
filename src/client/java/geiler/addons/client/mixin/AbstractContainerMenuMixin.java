package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.ExperimentSolverModule;
import geiler.addons.client.enchanting.ExperimentMenuUpdates;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.List;

/** Client inventory setters do not themselves notify ContainerListeners. */
@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {
	@Inject(method = "setItem", at = @At("RETURN"))
	private void geileraddons$slotUpdated(int slot, int stateId, ItemStack stack, CallbackInfo ci) {
		geileraddons$notifySolver();
	}
	@Inject(method = "initializeContents", at = @At("RETURN"))
	private void geileraddons$contentsUpdated(int stateId, List<ItemStack> items, ItemStack carried,
		CallbackInfo ci) {
		geileraddons$notifySolver();
	}
	@org.spongepowered.asm.mixin.Unique
	private void geileraddons$notifySolver() {
		AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
		ExperimentSolverModule solver = ExperimentSolverModule.INSTANCE;
		if (!solver.observesMenu(menu)) return;
		ExperimentMenuUpdates.afterServerUpdate(menu, () -> solver.onContainerUpdated(menu));
	}
}
