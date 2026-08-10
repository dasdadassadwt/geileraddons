package geiler.addons.client.mixin;

import geiler.addons.client.gui.ClickGuiScreen;
import geiler.addons.client.module.impl.I4HelperModule;
import geiler.addons.client.module.impl.TikiHelperModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

	/**
	 * Handled directly on the raw chat command string rather than via a registered Brigadier
	 * command: a bare literal command with no arguments throws "Incorrect argument for command"
	 * if the typed text has any trailing whitespace (e.g. tab-completion appends a space before
	 * Enter is pressed) - this sidesteps that by matching the trimmed string ourselves.
	 */
	/**
	 * The chat screen unconditionally closes itself (setScreen(null)) right after handling
	 * Enter, which would immediately wipe out a screen opened synchronously here. Deferring
	 * to the next tick lets our screen open after the chat screen finishes closing itself.
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

	@Inject(method = "handleChunkBlocksUpdate", at = @At("TAIL"))
	private void geileraddons$onChunkBlocksUpdate(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
		packet.runUpdates((pos, state) -> {
			I4HelperModule.INSTANCE.onBlockChange(pos, state);
			TikiHelperModule.INSTANCE.onBlockChange(pos, state);
		});
	}

	/**
	 * HEAD rather than TAIL so this sees the raw server text: a chat-compacting mod that cancels
	 * or rewrites the message would otherwise hide it entirely, which is exactly what happened to
	 * the tiki debug log. HEAD also runs once on the netty thread before the packet is rescheduled,
	 * hence the thread guard for anything that touches level state.
	 */
	@Inject(method = "handleSystemChat", at = @At("HEAD"))
	private void geileraddons$onSystemChat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
		I4HelperModule.INSTANCE.onChatMessage(packet.content().getString());
		if (Minecraft.getInstance().isSameThread()) {
			TikiHelperModule.INSTANCE.onChatMessage(packet.content().getString());
		}
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
}
