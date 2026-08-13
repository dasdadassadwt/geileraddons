package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.SafariFloorDropsModule;
import geiler.addons.client.module.impl.TikiHelperModule;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Taps the player's own attack/use input. Injected at HEAD so the skull's state is recorded as it
 * was <em>before</em> the click, which is the half of the before/after pair the block-update packet
 * can't provide, and so a floor drop stops being marked the instant it is acted on.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

	@Inject(method = "startAttack", at = @At("HEAD"))
	private void geileraddons$onStartAttack(CallbackInfoReturnable<Boolean> cir) {
		TikiHelperModule.INSTANCE.onClick(false);
		SafariFloorDropsModule.INSTANCE.onClick();
	}

	@Inject(method = "startUseItem", at = @At("HEAD"))
	private void geileraddons$onStartUseItem(CallbackInfo ci) {
		TikiHelperModule.INSTANCE.onClick(true);
		SafariFloorDropsModule.INSTANCE.onClick();
	}
}
