package geiler.addons.client.module.impl;

import geiler.addons.client.dungeon.DungeonClass;
import geiler.addons.client.dungeon.DungeonFloor;
import geiler.addons.client.dungeon.DungeonQueueFloorTracker;
import geiler.addons.client.dungeon.DungeonStats;
import geiler.addons.client.dungeon.DungeonStatsService;
import geiler.addons.client.party.PartyListBackend;
import geiler.addons.client.party.PartyMember;
import geiler.addons.client.party.PartySnapshot;
import geiler.addons.GeilerAddons;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.SettingGroup;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Displays cached profile statistics when a Party Finder player joins. */
public final class PartyFinderStatsModule extends Module {
	public static final PartyFinderStatsModule INSTANCE = new PartyFinderStatsModule();

	private static final Pattern DUNGEON_JOIN = Pattern.compile(
		"^Party Finder > (?:\\[[^]]*]\\s*)?([A-Za-z0-9_]{1,16}) joined the dungeon group! \\(([^)]+) Level \\d+\\)$");
	private static final Pattern QUEUE_SUCCESS = Pattern.compile(
		"^Party Finder\\s*>\\s*Your party has been queued in the dungeon finder!$");
	private static final int LIST_FALLBACK_TICKS = 20;
	private static final int CHAT_CARD_GAP_TICKS = 5;

	private final BooleanSetting compact;
	private final BooleanSetting debug;
	private DungeonFloor requestedFloor;
	private boolean fetchRequested;
	private boolean cycleStarted;
	private int ticksSinceRequest;
	private String cycleTargetName;
	private int pendingResults;
	private boolean initialDispatching;
	private boolean initialScanFinished;
	private boolean localJoinedThroughFinder;
	private int chatCardCooldown;
	private final Map<String, DungeonStats> cycleStats = new HashMap<>();
	private final Map<String, DungeonClass> deferredJoins = new LinkedHashMap<>();
	private final Set<String> checkedPlayers = new HashSet<>();
	private final Deque<Component> pendingChatCards = new ArrayDeque<>();

	private PartyFinderStatsModule() {
		this(new BooleanSetting("Compact", false), new BooleanSetting("Debug", false));
	}

	private PartyFinderStatsModule(BooleanSetting compact, BooleanSetting debug) {
		super("Party Finder Stats", "Shows dungeon statistics for Party Finder members.", Category.F7, compact, debug);
		this.compact = compact;
		this.debug = debug;
		group(new SettingGroup("Display", compact), new SettingGroup("Diagnostics", debug));
	}

	public void onChatMessage(String message) {
		String normalized = message == null ? "" : message.trim();
		if (QUEUE_SUCCESS.matcher(normalized).matches()) {
			DungeonFloor floor = DungeonQueueFloorTracker.confirmQueued();
			requestedFloor = floor;
			if (floor == null) {
				debug("Queue confirmed, but no fresh Group Builder floor was captured");
			} else {
				debug("Queue confirmed; activated Group Builder floor %s", floor.displayName());
			}
			return;
		}
		if (isPartyLifecycleReset(normalized)) {
			pendingChatCards.clear();
			if (DungeonQueueFloorTracker.clear()) debug("Cleared queued floor after party lifecycle reset");
		}
		Matcher matcher = DUNGEON_JOIN.matcher(normalized);
		if (!matcher.matches()) {
			if (debug.value() && normalized.startsWith("Party Finder >")) {
				debug("Ignored Party Finder line: %s", normalized);
			}
			return;
		}
		String name = matcher.group(1);
		String classOrFloor = matcher.group(2);
		boolean localJoin = name.equalsIgnoreCase(localName());
		if (localJoin) {
			DungeonFloor joinedFloor = DungeonQueueFloorTracker.confirmJoinedListing();
			debug(joinedFloor == null
				? "Local Party Finder join had no fresh clicked-listing floor"
				: "Local Party Finder join activated clicked listing floor %s",
				joinedFloor == null ? "" : joinedFloor.displayName());
		}
		DungeonFloor floor = DungeonQueueFloorTracker.currentFloor();
		DungeonClass joinedClass = DungeonClass.parse(classOrFloor);
		if (!wantsData()) {
			debug("Join detected for %s (%s), but both dungeon modules are disabled", name, classOrFloor);
			return;
		}
		if (joinedClass != null) PartyListBackend.setClass(name, joinedClass);
		PartySnapshot currentParty = PartyListBackend.snapshot();
		if (cycleStarted && currentParty.inParty()) {
			DungeonFloor effectiveFloor = floor == null ? requestedFloor : floor;
			if (floor != null) requestedFloor = floor;
			if (fetchRequested || !initialScanFinished) {
				deferredJoins.put(name.toLowerCase(Locale.ROOT), joinedClass);
				debug("Queued %s for the active initial party scan", name);
			} else {
				debug("Detected later join for %s as %s; fetching that player", name, classOrFloor);
				fetchJoinedPlayer(effectiveFloor, currentParty.generation(), name, joinedClass);
			}
			return;
		}

		requestedFloor = floor;
		cycleTargetName = name;
		localJoinedThroughFinder = localJoin;
		deferredJoins.clear();
		fetchRequested = true;
		cycleStarted = true;
		initialScanFinished = false;
		ticksSinceRequest = 0;
		debug("Detected %s joining as %s; queued floor=%s; requesting /p list", name, classOrFloor,
			floor == null ? "unknown" : floor.displayName());
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && mc.player.connection != null) {
			mc.player.connection.sendCommand("p list");
		} else {
			debug("Could not request /p list because the client connection is unavailable");
		}
	}

	public void tick() {
		tickChatCards();
		if (DungeonQueueFloorTracker.tick(Minecraft.getInstance())) {
			pendingChatCards.clear();
			debug("Cleared queued floor after a client-level change");
		}
		if (!fetchRequested) {
			if (!PartyListBackend.snapshot().inParty()) {
				cycleStarted = false;
				pendingResults = 0;
				initialDispatching = false;
				initialScanFinished = false;
				localJoinedThroughFinder = false;
				deferredJoins.clear();
				checkedPlayers.clear();
			}
			return;
		}
		ticksSinceRequest++;
		boolean stableList = PartyListBackend.consumeStableListResponse();
		if (!stableList && ticksSinceRequest < LIST_FALLBACK_TICKS) return;
		fetchRequested = false;
		PartySnapshot snapshot = PartyListBackend.snapshot();
		if (!snapshot.inParty()) {
			debug("Party scan stopped: the party list is empty");
			return;
		}
		long generation = snapshot.generation();
		DungeonFloor queuedFloor = requestedFloor;
		String targetName = cycleTargetName;
		cycleStats.clear();
		for (Map.Entry<String, DungeonClass> entry : deferredJoins.entrySet()) {
			if (entry.getValue() != null) PartyListBackend.setClass(entry.getKey(), entry.getValue());
		}
		String localName = localName();
		List<PartyMember> membersToFetch = new ArrayList<>();
		for (PartyMember member : snapshot.members()) {
			String key = member.name().toLowerCase(Locale.ROOT);
			if (localName != null && member.name().equalsIgnoreCase(localName)) {
				debug("Skipping local player %s during party scan", member.name());
				continue;
			}
			if (checkedPlayers.add(key)) membersToFetch.add(member);
		}
		pendingResults = membersToFetch.size();
		debug("Starting stats scan for %d party member(s), floor=%s, trigger=%s, list=%s", pendingResults,
			queuedFloor == null ? "unknown" : queuedFloor.displayName(), targetName,
			stableList ? "stable" : "fallback");
		initialDispatching = true;
		for (PartyMember member : membersToFetch) {
			DungeonStatsService.fetch(member.name(), result -> onResult(queuedFloor, generation, targetName, member.name(), result));
		}
		initialDispatching = false;
		if (pendingResults == 0) finishInitialScan(queuedFloor, generation, targetName);
	}

	/** Called by the container click mixin before Hypixel closes Group Builder. */
	public void onContainerSlotClick(AbstractContainerScreen<?> screen, Slot slot, int button,
		ContainerInput input) {
		if (!wantsData()) return;
		DungeonQueueFloorTracker.CaptureResult result =
			DungeonQueueFloorTracker.captureConfirmation(screen, slot, button, input);
		if (result.attempted()) {
			if (result.floor() == null) {
				debug("Group Builder confirmation detected, but floor capture failed: %s", result.error());
			} else {
				debug("Captured Group Builder floor candidate %s; waiting for queue confirmation",
					result.floor().displayName());
			}
			return;
		}
		result = DungeonQueueFloorTracker.captureJoinedListing(screen, slot, button, input);
		if (!result.attempted()) return;
		if (result.floor() == null) {
			debug("Party Finder listing click detected, but floor capture failed: %s", result.error());
		} else {
			debug("Captured clicked Party Finder listing floor candidate %s",
				result.floor().displayName());
		}
	}

	private void onResult(DungeonFloor floor, long generation, String targetName, String requestedName, DungeonStatsService.Result result) {
		PartySnapshot snapshot = PartyListBackend.snapshot();
		if (!snapshot.inParty() || snapshot.generation() != generation) return;
		if (!result.available()) {
			debug("Stats unavailable for %s: %s", requestedName, result.error());
			if (isEnabled() || AutoKickModule.INSTANCE.wantsData()) showUnavailable(requestedName, result.error());
		} else {
			DungeonStats stats = result.stats();
			debug("Stats loaded for %s: cata=%d, class=%s, secrets=%d, mp=%d", stats.name(),
				stats.catacombsLevel(), stats.selectedClass() == null ? "unknown" : stats.selectedClass().displayName(),
				stats.totalSecrets(), stats.magicalPower());
			cycleStats.put(stats.name().toLowerCase(Locale.ROOT), stats);
			PartyListBackend.setUuid(stats.name(), stats.uuid());
			PartyListBackend.setClassIfUnknown(stats.name(), stats.selectedClass());
			PartyMember member = findMember(PartyListBackend.snapshot(), stats.name());
			if (member != null && isEnabled()) showStats(floor, member, stats);
		}
		pendingResults = Math.max(0, pendingResults - 1);
		debug("Stats result received for %s (%d remaining)", requestedName, pendingResults);
		if (pendingResults > 0 || initialDispatching) return;
		finishInitialScan(floor, generation, targetName);
	}

	private void finishInitialScan(DungeonFloor floor, long generation, String targetName) {
		if (initialScanFinished) {
			debug("Ignored duplicate completion for party scan generation %d", generation);
			return;
		}
		initialScanFinished = true;
		DungeonStats target = targetName == null ? null : cycleStats.get(targetName.toLowerCase(Locale.ROOT));
		debug("Stats scan finished; target=%s; auto-kick evaluation floor=%s", targetName,
			floor == null ? "unknown" : floor.displayName());
		if (target != null) AutoKickModule.INSTANCE.onStats(floor, target);
		for (Map.Entry<String, DungeonClass> entry : deferredJoins.entrySet()) {
			DungeonStats stats = cycleStats.get(entry.getKey());
			if (stats != null) {
				AutoKickModule.INSTANCE.onStats(floor, stats);
			} else {
				fetchJoinedPlayer(floor, generation, entry.getKey(), entry.getValue());
			}
		}
		deferredJoins.clear();
		PartySnapshot snapshot = PartyListBackend.snapshot();
		if (localJoinedThroughFinder && snapshot.inParty() && !snapshot.isLeader(localName()) && isEnabled()) {
			queueChatCard(leavePartyAction());
			debug("Queued one Leave Party action after the joined-party stats scan");
		}
	}

	private void fetchJoinedPlayer(DungeonFloor floor, long generation, String name, DungeonClass joinedClass) {
		String localName = localName();
		if (localName != null && name.equalsIgnoreCase(localName)) {
			debug("Skipping local player %s", name);
			return;
		}
		String key = name.toLowerCase(Locale.ROOT);
		if (!checkedPlayers.add(key)) {
			debug("Skipping duplicate stats check for %s", name);
			return;
		}
		DungeonStatsService.fetch(name, result -> {
			PartySnapshot snapshot = PartyListBackend.snapshot();
			if (!snapshot.inParty() || snapshot.generation() != generation) {
				debug("Discarded stale stats result for %s", name);
				return;
			}
			if (!result.available()) {
				debug("Stats unavailable for later join %s: %s", name, result.error());
				showUnavailable(name, result.error());
				return;
			}
			DungeonStats stats = result.stats();
			PartyListBackend.setUuid(stats.name(), stats.uuid());
			if (joinedClass != null) PartyListBackend.setClass(stats.name(), joinedClass);
			else PartyListBackend.setClassIfUnknown(stats.name(), stats.selectedClass());
			PartyMember member = findMember(PartyListBackend.snapshot(), stats.name());
			debug("Later-join stats loaded for %s: cata=%d, mp=%d, class=%s", stats.name(),
				stats.catacombsLevel(), stats.magicalPower(),
				member == null || member.dungeonClass() == null ? "unknown" : member.dungeonClass().displayName());
			if (member != null && isEnabled()) showStats(floor, member, stats);
			AutoKickModule.INSTANCE.onStats(floor, stats);
		});
	}

	private boolean wantsData() {
		return isEnabled() || AutoKickModule.INSTANCE.wantsData();
	}

	private static String localName() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player == null ? null : mc.player.getGameProfile().name();
	}

	private static PartyMember findMember(PartySnapshot snapshot, String name) {
		for (PartyMember member : snapshot.members()) if (member.name().equalsIgnoreCase(name)) return member;
		return null;
	}

	private void showUnavailable(String name, String error) {
		String reason = error == null || error.isBlank() ? "unavailable" : error;
		queueChatCard(Component.literal("[PF] ").withStyle(ChatFormatting.YELLOW)
			.append(Component.literal(name + " stats unavailable (" + reason + ") ").withStyle(ChatFormatting.GRAY))
			.append(pvAction(name)));
	}

	private void showStats(DungeonFloor floor, PartyMember member, DungeonStats stats) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui == null) return;
		DungeonClass dungeonClass = member.dungeonClass() != null ? member.dungeonClass() : stats.selectedClass();
		Component classPart = hover(Component.literal(stats.selectedClassLine(dungeonClass)).withStyle(classColor(dungeonClass)),
			stats.allClassLevels());
		Component secretPart = hover(Component.literal(format(stats.secretAverage())).withStyle(ChatFormatting.AQUA),
			"Total secrets: " + stats.totalSecrets() + "\nRuns: " + stats.totalRuns());
		String currentPb = floor == null ? "-" : DungeonStatsService.formatTime(stats.fastestSPlusSeconds(floor));
		Component pbPart = hover(Component.literal(currentPb).withStyle(currentPb.equals("-") ? ChatFormatting.YELLOW : ChatFormatting.GREEN),
			allPbs(stats));

		MutableComponent header = Component.literal("✦ ").withStyle(ChatFormatting.AQUA)
			.append(Component.literal(stats.name()).withStyle(style -> style
				.withColor(ChatFormatting.WHITE).withBold(true)))
			.append(Component.literal(" joined").withStyle(ChatFormatting.GRAY))
			.append(Component.literal("  ").withStyle(ChatFormatting.DARK_GRAY))
			.append(Component.literal(floor == null ? "[Floor ?]" : "[" + floor.displayName() + "]")
				.withStyle(floor == null ? ChatFormatting.YELLOW : floor.master() ? ChatFormatting.RED : ChatFormatting.GOLD));

		MutableComponent overview = Component.literal("Cata ").withStyle(ChatFormatting.GRAY)
			.append(Component.literal(Integer.toString(stats.catacombsLevel())).withStyle(ChatFormatting.GOLD))
			.append(separator()).append(classPart)
			.append(separator()).append(Component.literal("CA ").withStyle(ChatFormatting.GRAY))
			.append(Component.literal(format(stats.classAverage())).withStyle(ChatFormatting.AQUA))
			.append(separator()).append(Component.literal("MP ").withStyle(ChatFormatting.GRAY))
			.append(Component.literal(String.format(Locale.ROOT, "%,d", stats.magicalPower())).withStyle(ChatFormatting.LIGHT_PURPLE))
			.append(separator()).append(Component.literal("SA ").withStyle(ChatFormatting.GRAY)).append(secretPart)
			.append(separator()).append(Component.literal("PB ").withStyle(ChatFormatting.GRAY)).append(pbPart);
		Component bankPart = stats.bankKnown()
			? Component.literal(formatBank(stats.bank())).withStyle(ChatFormatting.GOLD)
			: hover(Component.literal("?").withStyle(ChatFormatting.YELLOW), "Bank API data unavailable");
		MutableComponent gear = Component.literal("Gear ").withStyle(ChatFormatting.DARK_GRAY)
			.append(Component.literal("Term ").withStyle(ChatFormatting.GRAY)).append(mark(stats.gearKnown(), stats.has(DungeonStats.Gear.TERMINATOR)))
			.append(separator()).append(Component.literal("Hype ").withStyle(ChatFormatting.GRAY)).append(mark(stats.gearKnown(), stats.has(DungeonStats.Gear.HYPERION)))
			.append(separator()).append(Component.literal("GDrag ").withStyle(ChatFormatting.GRAY)).append(mark(stats.gearKnown(), stats.has(DungeonStats.Gear.GOLDEN_DRAGON)))
			.append(separator()).append(Component.literal("Bank ").withStyle(ChatFormatting.GRAY)).append(bankPart);
		if (PartyListBackend.snapshot().isLeader(localName())) {
			gear.append(separator()).append(kickAction(stats.name()));
		}
		if (!stats.gearKnown() || !stats.bankKnown()) {
			gear.append(separator()).append(pvAction(stats.name()));
		}
		if (compact.value()) {
			MutableComponent compactHeader = Component.literal("[✦ PF] ").withStyle(style -> style
				.withColor(ChatFormatting.AQUA).withBold(true));
			queueChatCard(compactHeader.append(header).append(separator())
				.append(overview).append(separator()).append(gear));
		} else {
			MutableComponent card = Component.empty()
				.append(cardTop(mc)).append("\n")
				.append(Component.literal("┃ ").withStyle(ChatFormatting.DARK_AQUA)).append(header).append("\n")
				.append(Component.literal("┃ ").withStyle(ChatFormatting.DARK_AQUA)).append(overview).append("\n")
				.append(Component.literal("┃ ").withStyle(ChatFormatting.DARK_AQUA)).append(gear).append("\n")
				.append(cardBottom(mc));
			queueChatCard(card);
		}
	}

	private static Component cardTop(Minecraft mc) {
		Component title = Component.literal(" ✦ PARTY FINDER ✦ ").withStyle(style -> style
			.withColor(ChatFormatting.AQUA).withBold(true));
		int available = Math.max(0, chatTextWidth(mc) - mc.font.width("╭╮") - mc.font.width(title));
		int dashCount = available / Math.max(1, mc.font.width("━"));
		int left = dashCount / 2;
		int right = dashCount - left;
		return Component.literal("╭" + "━".repeat(left)).withStyle(ChatFormatting.DARK_AQUA)
			.append(title)
			.append(Component.literal("━".repeat(right) + "╮").withStyle(ChatFormatting.DARK_AQUA));
	}

	private static Component cardBottom(Minecraft mc) {
		int available = Math.max(0, chatTextWidth(mc) - mc.font.width("╰╯"));
		int dashCount = available / Math.max(1, mc.font.width("━"));
		return Component.literal("╰" + "━".repeat(dashCount) + "╯")
			.withStyle(ChatFormatting.DARK_AQUA);
	}

	private static int chatTextWidth(Minecraft mc) {
		double scale = Math.max(0.01D, mc.options.chatScale().get());
		return Math.max(20, (int) Math.floor(ChatComponent.getWidth(mc.options.chatWidth().get()) / scale) - 8);
	}

	private void queueChatCard(Component card) {
		if (card == null) return;
		if (pendingChatCards.size() >= 16) {
			pendingChatCards.removeFirst();
			GeilerAddons.LOGGER.debug("[Party Finder Stats] Dropped oldest queued chat card to keep output bounded");
		}
		pendingChatCards.addLast(card);
	}

	private void tickChatCards() {
		if (chatCardCooldown > 0) chatCardCooldown--;
		if (chatCardCooldown > 0 || pendingChatCards.isEmpty()) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui == null) return;
		mc.gui.getChat().addClientSystemMessage(pendingChatCards.removeFirst());
		chatCardCooldown = CHAT_CARD_GAP_TICKS;
	}

	private static Component leavePartyAction() {
		return Component.literal("[PF] ").withStyle(style -> style
			.withColor(ChatFormatting.AQUA).withBold(true))
			.append(Component.literal("Finished loading this party  ").withStyle(ChatFormatting.GRAY))
			.append(Component.literal("[Leave Party]").withStyle(style -> style
				.withColor(ChatFormatting.RED).withBold(true).withUnderlined(true)
				.withClickEvent(new ClickEvent.RunCommand("/party leave"))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Run /party leave")))));
	}

	@Override
	protected void onDisable() {
		pendingChatCards.clear();
		chatCardCooldown = 0;
	}

	private static Component separator() {
		return Component.literal(" │ ").withStyle(ChatFormatting.DARK_GRAY);
	}

	private static ChatFormatting classColor(DungeonClass dungeonClass) {
		if (dungeonClass == null) return ChatFormatting.YELLOW;
		return switch (dungeonClass) {
			case TANK -> ChatFormatting.GREEN;
			case HEALER -> ChatFormatting.LIGHT_PURPLE;
			case MAGE -> ChatFormatting.AQUA;
			case BERSERK -> ChatFormatting.RED;
			case ARCHER -> ChatFormatting.GOLD;
		};
	}

	private static Component mark(boolean known, boolean present) {
		Component mark = Component.literal(!known ? "?" : present ? "✓" : "X")
			.withStyle(known && present ? ChatFormatting.GREEN : known ? ChatFormatting.RED : ChatFormatting.YELLOW);
		return known ? mark : hover(mark, "Inventory API data unavailable");
	}

	private static Component pvAction(String name) {
		return Component.literal("PV").withStyle(style -> style.withColor(ChatFormatting.GREEN)
			.withUnderlined(true)
			.withClickEvent(new ClickEvent.RunCommand("/pv " + name))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal("Run /pv " + name))));
	}

	private static Component kickAction(String name) {
		return Component.literal("[Kick]").withStyle(style -> style.withColor(ChatFormatting.RED)
			.withUnderlined(true)
			.withClickEvent(new ClickEvent.RunCommand("/party kick " + name))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal("/party kick " + name))));
	}

	private static Component hover(Component component, String text) {
		return component.copy().withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(Component.literal(text))));
	}

	private static String allPbs(DungeonStats stats) {
		List<String> lines = new ArrayList<>();
		for (DungeonFloor floor : geiler.addons.client.dungeon.DungeonFloor.values()) {
			lines.add(floor.displayName() + ": " + DungeonStatsService.formatTime(stats.fastestSPlusSeconds(floor)));
		}
		return String.join("\n", lines);
	}

	private static boolean isPartyLifecycleReset(String message) {
		String lower = message.toLowerCase(Locale.ROOT);
		return lower.equals("you left the party.")
			|| lower.contains("the party was disbanded")
			|| lower.contains("you have been kicked from the party")
			|| lower.contains("you removed your group from the party finder")
			|| lower.contains("you are not currently in a party")
			|| lower.equals("you are not in a party.");
	}

	private void debug(String format, Object... arguments) {
		String message = String.format(Locale.ROOT, format, arguments);
		GeilerAddons.LOGGER.debug("[Party Finder Stats] {}", message);
		if (!debug.value()) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui != null) {
			mc.gui.getChat().addClientSystemMessage(Component.literal("[PF Debug] ").withStyle(ChatFormatting.AQUA)
				.append(Component.literal(message).withStyle(ChatFormatting.GRAY)));
		}
	}

	private static String format(double value) {
		return String.format(Locale.ROOT, "%.2f", value);
	}

	private static String formatBank(long value) {
		return String.format(Locale.ROOT, "%,d", value);
	}
}
