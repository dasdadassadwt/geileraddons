package geiler.addons.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import geiler.addons.client.macro.MacroRunner;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(KeyMapping.class)
public abstract class KeyMappingInputBlockMixin {
	@Shadow @Final private String name;
	@Shadow protected InputConstants.Key key;

	@Inject(method = "setDown", at = @At("HEAD"), cancellable = true)
	private void geileraddons$blockPhysicalGameplayPress(boolean down, CallbackInfo ci) {
		if (!down || !MacroRunner.isPlayerInputBlocked() || MacroRunner.isApplyingSyntheticInput()) return;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.screen == null && isGameplayControl(name)) ci.cancel();
	}

	@Inject(method = "isDown", at = @At("HEAD"), cancellable = true)
	private void geileraddons$maskGameplayInputWhileBlocked(CallbackInfoReturnable<Boolean> cir) {
		if (!MacroRunner.isPlayerInputBlocked() || MacroRunner.isApplyingSyntheticInput()) return;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.screen != null || !isGameplayControl(name)) return;
		if (key != null && MacroRunner.isSyntheticKeyDown(key.getValue())) return;
		cir.setReturnValue(false);
	}

	private static boolean isGameplayControl(String name) {
		if (name == null) return false;
		return name.equals("key.forward") || name.equals("key.back")
			|| name.equals("key.left") || name.equals("key.right")
			|| name.equals("key.jump") || name.equals("key.sneak") || name.equals("key.sprint")
			|| name.equals("key.attack") || name.equals("key.use") || name.equals("key.pickItem")
			|| name.matches("key\\.hotbar\\.[1-9]");
	}
}
