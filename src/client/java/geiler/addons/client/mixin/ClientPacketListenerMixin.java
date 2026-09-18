package geiler.addons.client.mixin;

import geiler.addons.client.gui.ClickGuiScreen;
import geiler.addons.client.module.impl.I4HelperModule;
import geiler.addons.client.module.impl.SafariFloorDropsModule;
import geiler.addons.client.module.impl.TikiHelperModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

	/**
	 * Handled directly on the raw chat command string rather than via a registered Brigadier
	 * command: a bare literal command with no arguments throws "Incorrect argument for command"
	 * if the typed text has any trailing whitespace (e.g. tab-completion appends a space before
	 * Enter is pressed) - this sidesteps that by matching the trimmed string ourselves.
	 *
	 * <p>Opening is deferred a tick because the chat screen unconditionally closes itself
	 * (setScreen(null)) right after handling Enter, which would immediately wipe out a screen
	 * opened synchronously here.
	 */
	@Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
	private void geileraddons$onSendCommand(String command, CallbackInfo ci) {
		if (command.trim().equalsIgnoreCase("ga")) {
			Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreen(new ClickGuiScreen()));
			ci.cancel();
		}
	}

	@Inject(method = "handleBlockUpdate", at = @At("TAIL"))
	private void geileraddons$onBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
		I4HelperModule.INSTANCE.onBlockChange(packet.getPos(), packet.getBlockState());
		TikiHelperModule.INSTANCE.onBlockChange(packet.getPos(), packet.getBlockState());
	}

	/** Wraps Vanilla's one update traversal instead of replaying the packet after Vanilla returns. */
	@Redirect(method = "handleChunkBlocksUpdate", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/network/protocol/game/ClientboundSectionBlocksUpdatePacket;runUpdates(Ljava/util/function/BiConsumer;)V"))
	private void geileraddons$observeChunkUpdates(ClientboundSectionBlocksUpdatePacket packet,
		BiConsumer<BlockPos, BlockState> vanilla) {
		List<BlockChange> changes = new ArrayList<>();
		packet.runUpdates((pos, state) -> {
			vanilla.accept(pos, state);
			changes.add(new BlockChange(pos, state));
		});
		for (BlockChange change : changes) {
			I4HelperModule.INSTANCE.onBlockChange(change.position(), change.state());
			TikiHelperModule.INSTANCE.onBlockChange(change.position(), change.state());
		}
	}

	@Inject(method = "handleParticleEvent", at = @At("TAIL"))
	private void geileraddons$onParticleEvent(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
		SafariFloorDropsModule.INSTANCE.onParticle(packet);
	}

	@Inject(method = "handleSoundEvent", at = @At("TAIL"))
	private void geileraddons$onSoundEvent(ClientboundSoundPacket packet, CallbackInfo ci) {
		TikiHelperModule.INSTANCE.onSound(packet.getSound(), packet.getX(), packet.getY(), packet.getZ(),
			packet.getVolume(), packet.getPitch());
	}

	@Inject(method = "setSubtitleText", at = @At("HEAD"), cancellable = true)
	private void geileraddons$onSubtitle(ClientboundSetSubtitleTextPacket packet, CallbackInfo ci) {
		if (I4HelperModule.INSTANCE.onSubtitle(packet.text().getString())) {
			ci.cancel();
		}
	}

	private record BlockChange(BlockPos position, BlockState state) {
	}
}
