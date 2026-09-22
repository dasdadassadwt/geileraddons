package geiler.addons.client.party;

import geiler.addons.GeilerAddons;
import geiler.addons.client.dungeon.DungeonClass;
import geiler.addons.client.tree.ChatText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Data-only party state built from Hypixel's party chat and list messages. */
public final class PartyListBackend {
	private static final Pattern JOINED = Pattern.compile("^.*?([A-Za-z0-9_]{1,16}) joined the party\\.$");
	private static final Pattern LEFT = Pattern.compile("^.*?([A-Za-z0-9_]{1,16}) has left the party\\.$");
	private static final Pattern REMOVED = Pattern.compile("^.*?([A-Za-z0-9_]{1,16}) has been removed from the party\\.$");
	private static final Pattern JOINED_SELF = Pattern.compile("^You have joined .*?([A-Za-z0-9_]{1,16})'?s party!$");
	private static final Pattern LEADER_LINE = Pattern.compile("^Party Leader:\\s*(.+)$");
	private static final Pattern MEMBERS_LINE = Pattern.compile("^Party (?:Moderators|Members):\\s*(.*)$");
	private static final Pattern PARTY_CHAT = Pattern.compile("^Party > (?:\\[[^]]*]\\s*)?([A-Za-z0-9_]{1,16}):");
	private static final Pattern LEADER_TRANSFER = Pattern.compile("^.*?([A-Za-z0-9_]{1,16}) is now the party leader!$");
	private static final Pattern CLASS_IN_JOIN = Pattern.compile("^Party Finder > (?:\\[[^]]*]\\s*)?([A-Za-z0-9_]{1,16}) joined the dungeon group! \\((?:MM )?([A-Za-z]+) Level \\d+\\)$");

	private static final Map<String, PartyMember> MEMBERS = new LinkedHashMap<>();
	private static Map<String, PartyMember> listMetadata = Map.of();
	private static String leaderName;
	private static boolean inParty;
	/** Session token: ordinary joins/leaves and repeated /p list snapshots do not invalidate work. */
	private static long generation;
	private static long tick;
	private static long lastListMessageTick = Long.MIN_VALUE;
	private static boolean listMessageDirty;
	private static boolean stableListObserved;
	private static ClientLevel lastLevel;

	private PartyListBackend() {
	}

	public static void onChatMessage(String rawMessage) {
		String message = ChatText.plain(rawMessage).trim();
		if (message.isEmpty()) return;
		if (message.startsWith("Party ") || message.startsWith("Party Finder >")
			|| message.contains("party was disbanded") || message.contains("has left the party")) {
			GeilerAddons.LOGGER.debug("[Party List] Chat: {}", message);
		}

		if (message.startsWith("Party Members (")) {
			beginList();
			return;
		}
		if (message.contains("The party was disbanded") || message.contains("has disbanded the party")
			|| message.equals("You left the party.") || message.startsWith("You have been kicked from the party")
			|| message.equals("You are not currently in a party.")) {
			GeilerAddons.LOGGER.debug("[Party List] Clearing party state because of: {}", message);
			clear();
			return;
		}

		Matcher self = JOINED_SELF.matcher(message);
		if (self.matches()) {
			String localName = localName();
			addMember(localName);
			addMember(extractName(self.group(1)));
			leaderName = extractName(self.group(1));
			return;
		}

		Matcher joined = JOINED.matcher(message);
		if (joined.matches()) {
			addMember(joined.group(1));
			GeilerAddons.LOGGER.debug("[Party List] Added {} from party join", joined.group(1));
			return;
		}
		String departedMember = departedMemberName(message);
		if (departedMember != null) {
			removeMember(departedMember);
			if (REMOVED.matcher(message).matches()) {
				GeilerAddons.LOGGER.debug("[Party List] Removed {} because they were kicked", departedMember);
			} else {
				GeilerAddons.LOGGER.debug("[Party List] Removed {} because they left", departedMember);
			}
			return;
		}

		Matcher leader = LEADER_LINE.matcher(message);
		if (leader.matches()) {
			String name = extractName(leader.group(1));
			if (name != null) {
				thisLeader(name);
			}
			markListMessage();
			return;
		}
		Matcher members = MEMBERS_LINE.matcher(message);
		if (members.matches()) {
			for (String entry : members.group(1).split("\\s*●\\s*")) {
				addMember(extractName(entry));
			}
			markListMessage();
			return;
		}

		Matcher partyChat = PARTY_CHAT.matcher(message);
		if (partyChat.find()) {
			addMember(partyChat.group(1));
			return;
		}
		Matcher leaderTransfer = LEADER_TRANSFER.matcher(message);
		if (leaderTransfer.matches()) {
			thisLeader(leaderTransfer.group(1));
			return;
		}

		Matcher classJoin = CLASS_IN_JOIN.matcher(message);
		if (classJoin.matches()) {
			addMember(classJoin.group(1));
			setClass(classJoin.group(1), DungeonClass.parse(classJoin.group(2)));
			GeilerAddons.LOGGER.debug("[Party List] Added {} from dungeon join as {}", classJoin.group(1), classJoin.group(2));
		}
	}

	/** Returns the player named by a confirmed party-leave or kick chat message. */
	public static String departedMemberName(String rawMessage) {
		String message = ChatText.plain(rawMessage == null ? "" : rawMessage).trim();
		Matcher left = LEFT.matcher(message);
		if (left.matches()) return left.group(1);
		Matcher removed = REMOVED.matcher(message);
		return removed.matches() ? removed.group(1) : null;
	}

	public static void tick() {
		ClientLevel level = Minecraft.getInstance().level;
		if (level != lastLevel) {
			if (lastLevel != null) {
				GeilerAddons.LOGGER.debug("[Party List] Clearing state after world change");
				clear();
			}
			lastLevel = level;
		}
		tick++;
	}

	/** Returns true once a /p list response has been quiet for two client ticks. */
	public static boolean consumeStableListResponse() {
		if (!listMessageDirty || tick - lastListMessageTick < 2) return false;
		listMessageDirty = false;
		listMetadata = Map.of();
		if (MEMBERS.isEmpty()) {
			clear();
		} else {
			stableListObserved = true;
		}
		return true;
	}

	/** True after a complete list snapshot has been observed in the current party session. */
	public static boolean hasStableList() {
		return stableListObserved;
	}

	public static PartySnapshot snapshot() {
		return new PartySnapshot(new ArrayList<>(MEMBERS.values()), leaderName, inParty, generation);
	}

	public static void setUuid(String name, UUID uuid) {
		if (name == null || uuid == null) return;
		String key = key(name);
		PartyMember member = MEMBERS.get(key);
		if (member != null) MEMBERS.put(key, member.withUuid(uuid));
	}

	public static void setClass(String name, DungeonClass dungeonClass) {
		if (name == null || dungeonClass == null) return;
		String key = key(name);
		PartyMember member = MEMBERS.get(key);
		if (member != null) {
			MEMBERS.put(key, member.withClass(dungeonClass));
			GeilerAddons.LOGGER.debug("[Party List] Set {} class to {}", member.name(), dungeonClass.displayName());
		}
	}

	/** Uses profile data only as a fallback; a Party Finder join class is more authoritative. */
	public static void setClassIfUnknown(String name, DungeonClass dungeonClass) {
		if (name == null || dungeonClass == null) return;
		String key = key(name);
		PartyMember member = MEMBERS.get(key);
		if (member != null && member.dungeonClass() == null) {
			MEMBERS.put(key, member.withClass(dungeonClass));
			GeilerAddons.LOGGER.debug("[Party List] Filled unknown class for {} with profile class {}",
				member.name(), dungeonClass.displayName());
		}
	}

	public static void clear() {
		GeilerAddons.LOGGER.debug("[Party List] State cleared (generation {})", generation + 1);
		MEMBERS.clear();
		leaderName = null;
		inParty = false;
		generation++;
		listMessageDirty = false;
		stableListObserved = false;
		listMetadata = Map.of();
	}

	private static void beginList() {
		GeilerAddons.LOGGER.debug("[Party List] Beginning /p list response");
		listMetadata = new LinkedHashMap<>(MEMBERS);
		MEMBERS.clear();
		leaderName = null;
		inParty = true;
		stableListObserved = false;
		markListMessage();
	}

	private static void addMember(String name) {
		if (name == null || name.isBlank()) return;
		String normalized = extractName(name);
		if (normalized == null) return;
		String key = key(normalized);
		PartyMember previous = listMetadata.get(key);
		PartyMember member = previous == null ? new PartyMember(normalized, null, null)
			: new PartyMember(normalized, previous.uuid(), previous.dungeonClass());
		boolean added = MEMBERS.putIfAbsent(key, member) == null;
		inParty = true;
		if (added) GeilerAddons.LOGGER.debug("[Party List] Member now tracked: {}", normalized);
	}

	private static void removeMember(String name) {
		if (name == null) return;
		MEMBERS.remove(key(name));
		if (leaderName != null && leaderName.equalsIgnoreCase(name)) leaderName = null;
		inParty = true;
		// An empty tracked roster can mean the local player is the only remaining member. The
		// explicit leave/disband messages above, not chat-delta churn, end a party session.
	}

	private static void thisLeader(String name) {
		leaderName = name;
		addMember(name);
	}

	private static void markListMessage() {
		lastListMessageTick = tick;
		listMessageDirty = true;
		GeilerAddons.LOGGER.debug("[Party List] Received party list line at tick {}", tick);
	}

	private static String localName() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player == null ? null : mc.player.getGameProfile().name();
	}

	private static String extractName(String value) {
		if (value == null) return null;
		Matcher matcher = Pattern.compile("([A-Za-z0-9_]{1,16})(?:\\s*●)?\\s*$").matcher(value.trim());
		if (!matcher.find()) return null;
		String name = matcher.group(1);
		return name.equalsIgnoreCase("none") || name.equalsIgnoreCase("nobody") ? null : name;
	}

	private static String key(String name) {
		return name.trim().toLowerCase(java.util.Locale.ROOT);
	}
}
