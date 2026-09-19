package geiler.addons.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import geiler.addons.client.macro.MacroRunner;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(InputConstants.class)
public abstract class InputConstantsMixin {
	@Inject(method = "isKeyDown", at = @At("HEAD"), cancellable = true)
	private static void geileraddons$includeMacroHeldKey(Window window, int keyCode,
		CallbackInfoReturnable<Boolean> cir) {
		if (MacroRunner.isSyntheticKeyDown(keyCode)) cir.setReturnValue(true);
	}
}
