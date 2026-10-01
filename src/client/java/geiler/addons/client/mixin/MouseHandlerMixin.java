package geiler.addons.client.mixin;

import geiler.addons.client.module.ModuleKeybindManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
	private void geileraddons$onMouseButton(long window, MouseButtonInfo button, int action, CallbackInfo ci) {
		if (ModuleKeybindManager.handleMouseButtonEvent(Minecraft.getInstance(), action, button)) ci.cancel();
	}
}
