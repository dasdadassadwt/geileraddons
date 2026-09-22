package geiler.addons.client.dungeon;

import geiler.addons.client.module.impl.PartyFinderStatsModule;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/** Client-only handler for the local {@code /ga dstats <name>} command. */
public final class DungeonStatsCommand {
	private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

	private DungeonStatsCommand() {
	}

	public static boolean handle(String rawCommand) {
		ParseResult parsed = parse(rawCommand);
		if (!parsed.recognized()) return false;
		if (!parsed.valid()) showUsage();
		else lookup(parsed.name());
		return true;
	}

	public static int showUsage() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.gui != null) {
			minecraft.gui.getChat().addClientSystemMessage(Component.literal(
				"[DStats] Usage: /ga dstats \"player-name\""));
		}
		return 0;
	}

	public static int showRetryUsage() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.gui != null) {
			minecraft.gui.getChat().addClientSystemMessage(Component.literal(
				"[PF] Usage: /ga pfretry <player-name>"));
		}
		return 0;
	}

	public static int lookup(String name) {
		if (name == null || !PLAYER_NAME.matcher(name).matches()) return showUsage();
		PartyFinderStatsModule.INSTANCE.lookupStats(name);
		return 1;
	}

	public static int retryPartyMember(String name) {
		if (name == null || !PLAYER_NAME.matcher(name).matches()) return showRetryUsage();
		PartyFinderStatsModule.INSTANCE.retryPlayer(name);
		return 1;
	}

	/** Parses a command without touching Minecraft state, so quoting and usage edges stay testable offline. */
	public static ParseResult parse(String rawCommand) {
		if (rawCommand == null) return ParseResult.NOT_RECOGNIZED;
		String input = rawCommand.trim();
		if (input.startsWith("/")) input = input.substring(1).trim();
		int firstSpace = firstWhitespace(input);
		String root = (firstSpace < 0 ? input : input.substring(0, firstSpace)).toLowerCase(Locale.ROOT);
		if (!root.equals("ga") || firstSpace < 0) return ParseResult.NOT_RECOGNIZED;
		String tail = input.substring(firstSpace).trim();
		int commandEnd = firstWhitespace(tail);
		String subcommand = (commandEnd < 0 ? tail : tail.substring(0, commandEnd)).toLowerCase(Locale.ROOT);
		if (!subcommand.equals("dstats")) return ParseResult.NOT_RECOGNIZED;
		if (commandEnd < 0) return ParseResult.USAGE;

		String name = tail.substring(commandEnd).trim();
		if (name.startsWith("\"")) {
			if (name.length() < 2 || !name.endsWith("\"")) return ParseResult.USAGE;
			name = name.substring(1, name.length() - 1);
		} else if (containsWhitespace(name) || name.indexOf('"') >= 0) {
			return ParseResult.USAGE;
		}
		return PLAYER_NAME.matcher(name).matches() ? new ParseResult(true, name, null) : ParseResult.USAGE;
	}

	private static int firstWhitespace(String value) {
		for (int i = 0; i < value.length(); i++) if (Character.isWhitespace(value.charAt(i))) return i;
		return -1;
	}

	private static boolean containsWhitespace(String value) {
		for (int i = 0; i < value.length(); i++) if (Character.isWhitespace(value.charAt(i))) return true;
		return false;
	}

	public record ParseResult(boolean recognized, String name, String error) {
		private static final ParseResult NOT_RECOGNIZED = new ParseResult(false, null, null);
		private static final ParseResult USAGE = new ParseResult(true, null, "usage");

		public boolean valid() {
			return recognized && name != null;
		}
	}
}
