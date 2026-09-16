package geiler.addons.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Lets the custom experiment surface feed an accepted click back through vanilla's container
 * path. Keeping this as an invoker is important: the server sees precisely the same slot id,
 * button and {@link ContainerInput} that the normal inventory screen would have sent.
 */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenInvoker {
	@Invoker("slotClicked")
	void geileraddons$invokeSlotClicked(Slot slot, int slotId, int button, ContainerInput input);
}
