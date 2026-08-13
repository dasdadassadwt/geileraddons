package geiler.addons.client.mixin;

import geiler.addons.client.gui.ClickGuiScreen;
import geiler.addons.client.module.impl.I4HelperModule;
import geiler.addons.client.module.impl.SafariFloorDropsModule;
import geiler.addons.client.module.impl.TikiHelperModule;
import geiler.addons.client.module.impl.TreeNotifierModule;
import geiler.addons.client.module.impl.TreeTrackerModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
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
	 * the tiki debug log.
	 *
	 * <p>The guard is doing two jobs. The method first runs on the netty thread, throws to
	 * reschedule itself, then runs again on the client thread - so this injection fires twice per
	 * message. Keeping to the client-thread pass both drops the duplicate and makes it safe for
	 * the handlers to touch GUI, level and module state, none of which the netty thread may read.
	 */
	@Inject(method = "handleSystemChat", at = @At("HEAD"), cancellable = true)
	private void geileraddons$onSystemChat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
		if (!Minecraft.getInstance().isSameThread()) return;
		String content = packet.content().getString();
		I4HelperModule.INSTANCE.onChatMessage(content);
		TikiHelperModule.INSTANCE.onChatMessage(content);
		TreeTrackerModule.INSTANCE.onChatMessage(content);
		// Last, and the only one that may cancel: everything above has to see the line whether or
		// not it ends up displayed, since hiding a gift message must not stop it being counted.
		// Overlay messages are the action bar rather than chat, so they are left alone.
		if (!packet.overlay() && TreeNotifierModule.INSTANCE.onChatMessage(content, packet.content())) {
			ci.cancel();
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
}
