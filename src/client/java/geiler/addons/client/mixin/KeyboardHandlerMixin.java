package geiler.addons.client.mixin;

import geiler.addons.client.module.ModuleKeybindManager;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
	@Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
	private void geileraddons$onKeyPress(long window, int action, KeyEvent event, CallbackInfo ci) {
		if (ModuleKeybindManager.handleKeyEvent(Minecraft.getInstance(), action, event)) {
			ci.cancel();
		}
	}
}
