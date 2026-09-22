package geiler.addons.client.mixin;

import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Reads the recipe book's real render origin for exclusion from the inventory-button grid. */
@Mixin(RecipeBookComponent.class)
public interface RecipeBookComponentInvoker {
	@Invoker("getXOrigin")
	int geileraddons$getXOrigin();

	@Invoker("getYOrigin")
	int geileraddons$getYOrigin();
}
