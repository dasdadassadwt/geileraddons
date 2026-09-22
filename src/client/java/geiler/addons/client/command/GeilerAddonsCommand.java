package geiler.addons.client.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import geiler.addons.client.dungeon.DungeonStatsCommand;
import geiler.addons.client.gui.ClickGuiScreen;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;

/** Brigadier commands owned by the client mod, including tab-completable /ga commands. */
public final class GeilerAddonsCommand {
	private GeilerAddonsCommand() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			dispatcher.register(ClientCommands.literal("ga")
				.executes(context -> {
					Minecraft.getInstance().execute(() ->
						Minecraft.getInstance().setScreen(new ClickGuiScreen()));
					return 1;
				})
				.then(ClientCommands.literal("dstats")
					.executes(context -> DungeonStatsCommand.showUsage())
					.then(ClientCommands.argument("name", StringArgumentType.string())
						.executes(context -> DungeonStatsCommand.lookup(
						StringArgumentType.getString(context, "name")))))
				.then(ClientCommands.literal("pfretry")
					.executes(context -> DungeonStatsCommand.showRetryUsage())
					.then(ClientCommands.argument("name", StringArgumentType.word())
						.executes(context -> DungeonStatsCommand.retryPartyMember(
							StringArgumentType.getString(context, "name")))))));
	}
}
