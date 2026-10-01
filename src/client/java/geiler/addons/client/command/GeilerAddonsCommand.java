package geiler.addons.client.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import geiler.addons.client.config.ConfigTransferService;
import geiler.addons.client.dungeon.DungeonStatsCommand;
import geiler.addons.client.gui.ClickGuiScreen;
import geiler.addons.client.gui.ConfigResetScreen;
import geiler.addons.client.gui.ConfigTransferScreen;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;

/** Brigadier commands owned by the client mod, including tab-completable /ga commands. */
public final class GeilerAddonsCommand {
	private GeilerAddonsCommand() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			var root = ClientCommands.literal("ga")
				.executes(context -> openClickGui())
				.then(ClientCommands.literal("reset")
					.executes(context -> openResetScreen()))
				.then(ClientCommands.literal("config")
					.then(ClientCommands.literal("export")
						.executes(context -> exportConfig(""))
						.then(ClientCommands.argument("name", StringArgumentType.greedyString())
							.executes(context -> exportConfig(StringArgumentType.getString(context, "name")))))
					.then(ClientCommands.literal("import")
						.executes(context -> openConfigTransfer()))
					.then(ClientCommands.literal("save")
						.executes(context -> saveConfig()))
					.then(ClientCommands.literal("folder")
						.executes(context -> openConfigFolder())))
				.then(ClientCommands.literal("dstats")
					.executes(context -> DungeonStatsCommand.lookupLocalPlayer())
					.then(ClientCommands.argument("name", StringArgumentType.string())
						.suggests((context, builder) -> {
							var player = Minecraft.getInstance().player;
							if (player != null && player.connection != null) {
								var profileNames = player.connection.getListedOnlinePlayers().stream()
									.map(info -> info.getProfile().name())
									.toList();
								for (String suggestion : DungeonStatsCommand.suggestions(
									profileNames, builder.getRemaining())) {
									builder.suggest(suggestion);
								}
							}
							return builder.buildFuture();
						})
						.executes(context -> DungeonStatsCommand.lookup(
							StringArgumentType.getString(context, "name")))))
				.then(ClientCommands.literal("pfretry")
					.executes(context -> DungeonStatsCommand.showRetryUsage())
					.then(ClientCommands.argument("name", StringArgumentType.word())
						.executes(context -> DungeonStatsCommand.retryPartyMember(
							StringArgumentType.getString(context, "name")))));
			var registeredRoot = dispatcher.register(root);
			for (String alias : new String[]{"Ga", "gA", "GA"}) {
				dispatcher.register(ClientCommands.literal(alias)
					.executes(context -> openClickGui())
					.redirect(registeredRoot));
			}
		});
	}

	private static int openClickGui() {
		if (ConfigTransferService.isBusy()) return busyNotice();
		Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreen(new ClickGuiScreen()));
		return 1;
	}

	private static int openResetScreen() {
		if (ConfigTransferService.isBusy()) return busyNotice();
		Minecraft.getInstance().setScreen(new ConfigResetScreen(Minecraft.getInstance().screen));
		return 1;
	}

	private static int openConfigTransfer() {
		if (ConfigTransferService.isBusy()) return busyNotice();
		Minecraft.getInstance().setScreen(new ConfigTransferScreen(Minecraft.getInstance().screen));
		return 1;
	}

	private static int exportConfig(String name) {
		if (ConfigTransferService.isBusy()) return busyNotice();
		Minecraft.getInstance().gui.getChat().addClientSystemMessage(Component.literal("[GeilerAddons] Exporting configuration…"));
		ConfigTransferService.exportProfile(name, GeilerAddonsCommand::reportResult);
		return 1;
	}

	private static int saveConfig() {
		if (ConfigTransferService.isBusy()) return busyNotice();
		Minecraft.getInstance().gui.getChat().addClientSystemMessage(Component.literal("[GeilerAddons] Saving configuration…"));
		ConfigTransferService.saveCurrent(GeilerAddonsCommand::reportResult);
		return 1;
	}

	private static int openConfigFolder() {
		if (ConfigTransferService.isBusy()) return busyNotice();
		ConfigTransferService.openFolder(ConfigTransferService.configDirectory());
		return 1;
	}

	private static int busyNotice() {
		Minecraft.getInstance().gui.getChat().addClientSystemMessage(Component.literal(
			"[GeilerAddons] A configuration operation is in progress; wait for it to finish first."));
		return 0;
	}

	private static void reportResult(ConfigTransferService.Result result) {
		var chat = Minecraft.getInstance().gui.getChat();
		chat.addClientSystemMessage(Component.literal("[GeilerAddons] " + result.message()));
		if (result.path() != null) {
			String label = result.path().toAbsolutePath().toString();
			chat.addClientSystemMessage(Component.literal("Saved: " + label + "  [Open GeilerAddons folder]")
				.withStyle(style -> style.withUnderlined(true).withClickEvent(new ClickEvent.RunCommand("/ga config folder"))));
		}
		if (result.recoveryPath() != null) {
			chat.addClientSystemMessage(Component.literal("Recovery profile: " + result.recoveryPath().toAbsolutePath()
				+ "  [Open folder]").withStyle(style -> style.withUnderlined(true)
					.withClickEvent(new ClickEvent.RunCommand("/ga config folder"))));
		}
	}
}
