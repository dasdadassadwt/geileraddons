package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.TikiDebugModule;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Taps the player's own attack/use input for the tiki debug log. Injected at HEAD so the skull's
 * state is recorded as it was <em>before</em> the click, which is the half of the before/after
 * pair the block-update packet can't provide.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

	@Inject(method = "startAttack", at = @At("HEAD"))
	private void geileraddons$onStartAttack(CallbackInfoReturnable<Boolean> cir) {
		TikiDebugModule.INSTANCE.onClick(false);
	}

	@Inject(method = "startUseItem", at = @At("HEAD"))
	private void geileraddons$onStartUseItem(CallbackInfo ci) {
		TikiDebugModule.INSTANCE.onClick(true);
	}
}
