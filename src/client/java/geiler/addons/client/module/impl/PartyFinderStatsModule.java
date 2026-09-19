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
import geiler.addons.client.module.ModulePreview;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.tree.ChatText;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Displays cached profile statistics when a Party Finder player joins. */
public final class PartyFinderStatsModule extends Module implements ModulePreview {
	public static final PartyFinderStatsModule INSTANCE = new PartyFinderStatsModule();

	private static final Pattern DUNGEON_JOIN = Pattern.compile(
		"^Party Finder > (?:\\[[^]]*]\\s*)?([A-Za-z0-9_]{1,16}) joined the dungeon group! \\(([^)]+) Level \\d+\\)$");
	private static final Pattern QUEUE_SUCCESS = Pattern.compile(
		"^Party Finder\\s*>\\s*Your party has been queued in the dungeon finder!$");
	private static final int LIST_FALLBACK_TICKS = 20;
	private static final int CHAT_CARD_GAP_TICKS = 5;

	private final BooleanSetting compact;
	private final BooleanSetting debug;
	private final BooleanSetting showCata;
	private final BooleanSetting showClass;
	private final BooleanSetting showClassAverage;
	private final BooleanSetting showMagicalPower;
	private final BooleanSetting showSecretAverage;
	private final BooleanSetting showPersonalBest;
	private final BooleanSetting showTerminator;
	private final BooleanSetting showHyperion;
	private final BooleanSetting showGoldenDragon;
	private final BooleanSetting showBank;
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
	private long scanSequence;
	private long activeScanToken = -1;
	/** Invalidates display-owned later-join callbacks when the module is disabled. */
	private long displayLifecycleEpoch;
	private final Map<String, DungeonStats> cycleStats = new HashMap<>();
	/** UUID captured with each result so a late callback cannot be applied to a replacement name. */
	private final Map<String, UUID> cycleStatUuids = new HashMap<>();
	private final Map<String, DungeonClass> deferredJoins = new LinkedHashMap<>();
	private final Map<Long, ScanState> scanPending = new HashMap<>();
	private final Map<String, PartyMember> checkedPlayers = new HashMap<>();
	private final Deque<Component> pendingChatCards = new ArrayDeque<>();

	private PartyFinderStatsModule() {
		this(new Settings());
	}

	private PartyFinderStatsModule(Settings settings) {
		super("Party Finder Stats", "Shows dungeon statistics for Party Finder members.", Category.F7,
			settings.allSettings().toArray(Setting[]::new));
		this.compact = settings.compact;
		this.debug = settings.debug;
		this.showCata = settings.showCata;
		this.showClass = settings.showClass;
		this.showClassAverage = settings.showClassAverage;
		this.showMagicalPower = settings.showMagicalPower;
		this.showSecretAverage = settings.showSecretAverage;
		this.showPersonalBest = settings.showPersonalBest;
		this.showTerminator = settings.showTerminator;
		this.showHyperion = settings.showHyperion;
		this.showGoldenDragon = settings.showGoldenDragon;
		this.showBank = settings.showBank;
		group(new SettingGroup("Display", settings.compact, settings.showCata, settings.showClass,
			settings.showClassAverage, settings.showMagicalPower, settings.showSecretAverage,
			settings.showPersonalBest, settings.showTerminator, settings.showHyperion,
			settings.showGoldenDragon, settings.showBank), SettingGroup.debug("Diagnostics", settings.debug));
	}

	private static final class Settings {
		final BooleanSetting compact = new BooleanSetting("Compact", false);
		final BooleanSetting showCata = new BooleanSetting("Cata", true);
		final BooleanSetting showClass = new BooleanSetting("Class", true);
		final BooleanSetting showClassAverage = new BooleanSetting("CA", true);
		final BooleanSetting showMagicalPower = new BooleanSetting("MP", true);
		final BooleanSetting showSecretAverage = new BooleanSetting("SA", true);
		final BooleanSetting showPersonalBest = new BooleanSetting("PB", true);
		final BooleanSetting showTerminator = new BooleanSetting("Terminator", true);
		final BooleanSetting showHyperion = new BooleanSetting("Hyperion", true);
		final BooleanSetting showGoldenDragon = new BooleanSetting("GDrag", true);
		final BooleanSetting showBank = new BooleanSetting("Bank", true);
		final BooleanSetting debug = BooleanSetting.debug("Debug", false);

		List<Setting> allSettings() {
			return List.of(compact, showCata, showClass, showClassAverage, showMagicalPower,
				showSecretAverage, showPersonalBest, showTerminator, showHyperion, showGoldenDragon,
				showBank, debug);
		}
	}

	public void onChatMessage(String message) {
		String normalized = ChatText.plain(message == null ? "" : message).trim();
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
				fetchJoinedPlayer(effectiveFloor, name, joinedClass);
			}
			return;
		}

		requestedFloor = floor;
		cycleTargetName = name;
		localJoinedThroughFinder = localJoin;
		cycleStats.clear();
		cycleStatUuids.clear();
		checkedPlayers.clear();
		scanPending.clear();
		activeScanToken = -1;
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
		boolean stableList = PartyListBackend.consumeStableListResponse();
		if (!fetchRequested) {
			if (stableList && cycleStarted && initialScanFinished) {
				reconcileStableList();
			}
			if (!PartyListBackend.snapshot().inParty()) {
				cycleStarted = false;
				pendingResults = 0;
				initialDispatching = false;
				initialScanFinished = false;
				localJoinedThroughFinder = false;
				activeScanToken = -1;
				scanPending.clear();
				cycleStats.clear();
				cycleStatUuids.clear();
				deferredJoins.clear();
				checkedPlayers.clear();
			}
			return;
		}
		ticksSinceRequest++;
		if (!stableList && ticksSinceRequest < LIST_FALLBACK_TICKS) return;
		fetchRequested = false;
		PartySnapshot snapshot = PartyListBackend.snapshot();
		if (!snapshot.inParty()) {
			debug("Party scan stopped: the party list is empty");
			return;
		}
		long generation = snapshot.generation();
		long scanToken = ++scanSequence;
		activeScanToken = scanToken;
		DungeonFloor queuedFloor = requestedFloor;
		String targetName = cycleTargetName;
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
			PartyMember previous = checkedPlayers.get(key);
			if (previous == null || !sameMember(previous, member)) {
				checkedPlayers.put(key, member);
				membersToFetch.add(member);
			}
		}
		pendingResults = membersToFetch.size();
		scanPending.put(scanToken, new ScanState(generation, pendingResults));
		debug("Starting stats scan for %d party member(s), floor=%s, trigger=%s, list=%s", pendingResults,
			queuedFloor == null ? "unknown" : queuedFloor.displayName(), targetName,
			stableList ? "stable" : "fallback");
		initialDispatching = true;
		for (PartyMember member : membersToFetch) {
			DungeonStatsService.fetch(member.name(), result -> onResult(scanToken, queuedFloor, targetName, member, result));
		}
		initialDispatching = false;
		if (pendingResults == 0) finishInitialScan(scanToken, queuedFloor, generation, targetName);
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

	private void onResult(long scanToken, DungeonFloor floor, String targetName,
		PartyMember requestedMember, DungeonStatsService.Result result) {
		ScanState scan = scanPending.get(scanToken);
		if (scan == null) return;
		String requestedName = requestedMember.name();
		PartySnapshot snapshot = PartyListBackend.snapshot();
		boolean sameGeneration = snapshot.generation() == scan.generation();
		PartyMember member = snapshot.inParty() ? findMember(snapshot, requestedName) : null;
		boolean sameIdentity = sameGeneration && member != null && identityCompatible(requestedMember, member);
		if (sameGeneration && !result.available()) {
			debug("Stats unavailable for %s: %s", requestedName, result.error());
			if (sameIdentity) {
				if (isEnabled() || AutoKickModule.INSTANCE.wantsData()) showUnavailable(requestedName, result.error());
				AutoKickModule.INSTANCE.onStatsUnavailable(floor, snapshot.generation(), requestedName, result.error());
			} else if (member == null) {
				// The request finished while a /p list refresh temporarily hid the member. Do not
				// let that transient absence mark an unavailable profile as permanently checked.
				checkedPlayers.remove(requestedName.toLowerCase(Locale.ROOT));
			} else {
				// A same-name replacement must not inherit an unavailable result from the old request.
				checkedPlayers.put(requestedName.toLowerCase(Locale.ROOT), member);
				debug("Ignored unavailable result for replaced member %s", requestedName);
			}
		} else if (sameGeneration && result.available()) {
			DungeonStats stats = result.stats();
			debug("Stats loaded for %s: cata=%d, class=%s, secrets=%d, mp=%d", stats.name(),
				stats.catacombsLevel(), stats.selectedClass() == null ? "unknown" : stats.selectedClass().displayName(),
				stats.totalSecrets(), stats.magicalPower());
			if (member == null || sameIdentity) {
				putCycleStats(stats, member == null ? requestedMember : member);
			}
			if (sameIdentity) {
				PartyListBackend.setUuid(stats.name(), stats.uuid());
				PartyListBackend.setClassIfUnknown(stats.name(), stats.selectedClass());
				member = findMember(PartyListBackend.snapshot(), stats.name());
				if (member != null) {
					checkedPlayers.put(stats.name().toLowerCase(Locale.ROOT), member);
					putCycleStats(stats, member);
				}
				if (member != null && isEnabled()) showStats(floor, member, stats);
				AutoKickModule.INSTANCE.onStats(floor, PartyListBackend.snapshot().generation(), stats);
			} else if (member != null) {
				checkedPlayers.put(requestedName.toLowerCase(Locale.ROOT), member);
				debug("Ignored stale stats result for replaced member %s", requestedName);
			}
		}

		int next = scan.remaining() - 1;
		if (next <= 0) {
			scanPending.remove(scanToken);
			if (scanToken == activeScanToken) {
				pendingResults = 0;
				if (!sameGeneration) {
					// A disband/rejoin creates a new backend generation. Retire the old callbacks, but do
					// not leave the state machine waiting forever for results that are being discarded.
					activeScanToken = -1;
					initialScanFinished = false;
					cycleStarted = false;
					cycleTargetName = null;
					cycleStats.clear();
					cycleStatUuids.clear();
					deferredJoins.clear();
					checkedPlayers.clear();
					debug("Retired stale party scan %d after generation changed", scanToken);
				} else {
					debug("Stats result received for %s (0 remaining)", requestedName);
					if (!initialDispatching) finishInitialScan(scanToken, floor, scan.generation(), targetName);
				}
			}
			return;
		}
		scanPending.put(scanToken, new ScanState(scan.generation(), next));
		if (scanToken == activeScanToken) pendingResults = next;
		debug("Stats result received for %s (%d remaining)", requestedName, next);
	}

	private void finishInitialScan(long scanToken, DungeonFloor floor, long generation, String targetName) {
		if (scanToken != activeScanToken) return;
		if (PartyListBackend.snapshot().generation() != generation) {
			activeScanToken = -1;
			pendingResults = 0;
			initialScanFinished = false;
			cycleStarted = false;
			return;
		}
		if (initialScanFinished) {
			debug("Ignored duplicate completion for party scan generation %d", generation);
			return;
		}
		initialScanFinished = true;
		PartySnapshot currentParty = PartyListBackend.snapshot();
		PartyMember targetMember = targetName == null ? null : findMember(currentParty, targetName);
		DungeonStats target = statsFor(targetMember);
		debug("Stats scan finished; target=%s; auto-kick evaluation floor=%s", targetName,
			floor == null ? "unknown" : floor.displayName());
		if (target != null) AutoKickModule.INSTANCE.onStats(floor, PartyListBackend.snapshot().generation(), target);
		// A /p list refresh can briefly hide a member while its callback arrives. The result is
		// retained above; replay every retained result against the now-stable active party so that
		// transient list timing cannot permanently skip an enforcement decision.
		PartySnapshot activeParty = PartyListBackend.snapshot();
		for (DungeonStats stats : cycleStats.values()) {
			PartyMember member = findMember(activeParty, stats.name());
			if (member != null && statsFor(member) == stats) {
				AutoKickModule.INSTANCE.onStats(floor, activeParty.generation(), stats);
			}
		}
		for (Map.Entry<String, DungeonClass> entry : deferredJoins.entrySet()) {
			PartyMember member = findMember(PartyListBackend.snapshot(), entry.getKey());
			DungeonStats stats = statsFor(member);
			if (stats != null) {
				AutoKickModule.INSTANCE.onStats(floor, PartyListBackend.snapshot().generation(), stats);
			} else {
				fetchJoinedPlayer(floor, entry.getKey(), entry.getValue());
			}
		}
		deferredJoins.clear();
		PartySnapshot snapshot = PartyListBackend.snapshot();
		if (localJoinedThroughFinder && snapshot.inParty() && !snapshot.isLeader(localName()) && isEnabled()) {
			queueChatCard(leavePartyAction());
			debug("Queued one Leave Party action after the joined-party stats scan");
		}
	}

	private void fetchJoinedPlayer(DungeonFloor floor, String name, DungeonClass joinedClass) {
		String localName = localName();
		if (localName != null && name.equalsIgnoreCase(localName)) {
			debug("Skipping local player %s", name);
			return;
		}
		String key = name.toLowerCase(Locale.ROOT);
		PartyMember currentMember = findMember(PartyListBackend.snapshot(), name);
		if (currentMember == null) return;
		PartyMember previous = checkedPlayers.get(key);
		if (previous != null && sameMember(previous, currentMember)) {
			debug("Skipping duplicate stats check for %s", name);
			return;
		}
		checkedPlayers.put(key, currentMember);
		PartyMember requestedMember = currentMember;
		long requestedGeneration = PartyListBackend.snapshot().generation();
		long requestedDisplayEpoch = displayLifecycleEpoch;
		DungeonStatsService.fetch(name, result -> onLaterJoinResult(requestedDisplayEpoch, floor, name,
			joinedClass, requestedMember, requestedGeneration, result));
	}

	private void onLaterJoinResult(long requestedDisplayEpoch, DungeonFloor floor, String name,
		DungeonClass joinedClass, PartyMember requestedMember, long requestedGeneration,
		DungeonStatsService.Result result) {
		boolean autoKickActive = AutoKickModule.INSTANCE.wantsData();
		LaterJoinDisposition disposition = laterJoinDisposition(requestedDisplayEpoch, displayLifecycleEpoch,
			isEnabled(), autoKickActive);
		// If neither consumer remains active, a callback from before disablement must not recreate
		// chat cards or repopulate the display's party caches after onDisable cleared them.
		if (disposition == LaterJoinDisposition.IGNORE) return;
		boolean displayCurrent = disposition == LaterJoinDisposition.DISPLAY;

		PartySnapshot snapshot = PartyListBackend.snapshot();
		if (snapshot.generation() != requestedGeneration) {
			debug("Discarded stale later-join result for %s after party generation changed", name);
			return;
		}
		PartyMember member = snapshot.inParty() ? findMember(snapshot, name) : null;
		if (member == null) {
			if (displayCurrent) {
				if (result.available()) putCycleStats(result.stats(), requestedMember);
				else checkedPlayers.remove(name.toLowerCase(Locale.ROOT));
			}
			debug("Discarded stale stats result for %s", name);
			return;
		}
		if (!identityCompatible(requestedMember, member)) {
			if (displayCurrent) checkedPlayers.put(name.toLowerCase(Locale.ROOT), member);
			debug("Ignored stale later-join result for replaced member %s", name);
			return;
		}
		if (!result.available()) {
			debug("Stats unavailable for later join %s: %s", name, result.error());
			// Keep the existing Auto Kick fallback card behavior, but never emit anything when both
			// consumers are inactive; the early return above enforces that boundary.
			if (displayCurrent || autoKickActive) showUnavailable(name, result.error());
			if (autoKickActive) {
				AutoKickModule.INSTANCE.onStatsUnavailable(floor, snapshot.generation(), name, result.error());
			}
			return;
		}
		DungeonStats stats = result.stats();
		// Auto Kick may still need the shared party identity enrichment after the display module was
		// disabled, but display-owned maps are only updated by a current display request.
		PartyListBackend.setUuid(stats.name(), stats.uuid());
		PartyListBackend.setClassIfUnknown(stats.name(), joinedClass);
		PartyListBackend.setClassIfUnknown(stats.name(), stats.selectedClass());
		member = findMember(PartyListBackend.snapshot(), stats.name());
		if (displayCurrent && member != null) {
			checkedPlayers.put(stats.name().toLowerCase(Locale.ROOT), member);
			putCycleStats(stats, member);
		}
		debug("Later-join stats loaded for %s: cata=%d, mp=%d, class=%s", stats.name(),
			stats.catacombsLevel(), stats.magicalPower(),
			member == null || member.dungeonClass() == null ? "unknown" : member.dungeonClass().displayName());
		if (displayCurrent && member != null) showStats(floor, member, stats);
		if (autoKickActive) {
			AutoKickModule.INSTANCE.onStats(floor, PartyListBackend.snapshot().generation(), stats);
		}
	}

	/** Classifies the consumers that may still receive a later-join callback. */
	static LaterJoinDisposition laterJoinDisposition(long requestedEpoch, long currentEpoch,
		boolean displayEnabled, boolean autoKickEnabled) {
		if (requestedEpoch == currentEpoch && displayEnabled) return LaterJoinDisposition.DISPLAY;
		return autoKickEnabled ? LaterJoinDisposition.AUTO_KICK_ONLY : LaterJoinDisposition.IGNORE;
	}

	enum LaterJoinDisposition {
		DISPLAY,
		AUTO_KICK_ONLY,
		IGNORE
	}

	private boolean wantsData() {
		return isEnabled() || AutoKickModule.INSTANCE.wantsData();
	}

	/**
	 * A repeated /p list is only a view refresh, not a new party session. Keep the results already
	 * shown, but pick up members that appeared while the original asynchronous scan was running.
	 */
	private void reconcileStableList() {
		PartySnapshot snapshot = PartyListBackend.snapshot();
		if (!snapshot.inParty()) return;
		String local = localName();
		for (PartyMember member : snapshot.members()) {
			if (local != null && member.name().equalsIgnoreCase(local)) continue;
			String key = member.name().toLowerCase(Locale.ROOT);
			PartyMember previous = checkedPlayers.get(key);
			DungeonStats stats = statsFor(member);
			if (previous == null || !sameMember(previous, member)) {
				fetchJoinedPlayer(requestedFloor, member.name(), member.dungeonClass());
			} else if (stats == null) {
				// An unavailable result is retryable after a later list refresh; it must not become
				// a permanent "checked" marker.
				checkedPlayers.remove(key);
				fetchJoinedPlayer(requestedFloor, member.name(), member.dungeonClass());
			} else {
				// Re-apply retained results to the current party view. AutoKick's handled key keeps
				// completed actions idempotent, while a changed class/UUID gets a new key.
				AutoKickModule.INSTANCE.onStats(requestedFloor, snapshot.generation(), stats);
			}
		}
	}

	private static String localName() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player == null ? null : mc.player.getGameProfile().name();
	}

	private static PartyMember findMember(PartySnapshot snapshot, String name) {
		for (PartyMember member : snapshot.members()) if (member.name().equalsIgnoreCase(name)) return member;
		return null;
	}

	/** Keeps completed results through ordinary leave/join churn, but detects a changed identity. */
	private static boolean sameMember(PartyMember previous, PartyMember current) {
		if (previous == null || current == null) return false;
		if (!previous.name().equalsIgnoreCase(current.name())) return false;
		if (previous.uuid() == null || current.uuid() == null) {
			// /p list reconstructs immutable records even when membership did not change. Preserve
			// already loaded results in that case, while still treating a class change as a new check.
			return previous.uuid() == null && current.uuid() == null
				&& previous.dungeonClass() == current.dungeonClass();
		}
		// A stable UUID proves the player is the same account, not that their Party Finder
		// class is unchanged. Re-evaluate when the class changes so a cached result cannot
		// carry a stale duplicate-class or selected-class decision into a new listing.
		return Objects.equals(previous.uuid(), current.uuid())
			&& previous.dungeonClass() == current.dungeonClass();
	}

	private void putCycleStats(DungeonStats stats, PartyMember member) {
		if (stats == null || member == null) return;
		String key = member.name().toLowerCase(Locale.ROOT);
		cycleStats.put(key, stats);
		cycleStatUuids.put(key, member.uuid());
	}

	private DungeonStats statsFor(PartyMember member) {
		if (member == null) return null;
		String key = member.name().toLowerCase(Locale.ROOT);
		DungeonStats stats = cycleStats.get(key);
		if (stats == null) return null;
		UUID storedUuid = cycleStatUuids.get(key);
		if (storedUuid != null || member.uuid() != null) {
			if (!Objects.equals(storedUuid, member.uuid())) return null;
		}
		return stats;
	}

	/** Without UUID data, only the exact party record captured for the request is trusted. */
	private static boolean identityCompatible(PartyMember requested, PartyMember current) {
		if (requested == null || current == null || !requested.name().equalsIgnoreCase(current.name())) return false;
		if (requested.uuid() != null) return Objects.equals(requested.uuid(), current.uuid());
		return current.uuid() == null && requested.dungeonClass() == current.dungeonClass();
	}

	private record ScanState(long generation, int remaining) {
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
		StatsView view = StatsView.from(floor, member, stats, dungeonClass);
		queueChatCard(joinLines(formatCard(mc.font, view, chatTextWidth(mc), true)));
	}

	@Override
	public List<Component> previewLines(Font font, int width) {
		return formatCard(font, StatsView.preview(), Math.max(40, width), false);
	}

	private List<Component> formatCard(Font font, StatsView view, int width, boolean includeActions) {
		List<Component> actions = includeActions ? liveActions(view) : List.of();
		return CardFormatter.format(font, view, displayOptions(), width, actions);
	}

	private DisplayOptions displayOptions() {
		return new DisplayOptions(compact.value(), showCata.value(), showClass.value(),
			showClassAverage.value(), showMagicalPower.value(), showSecretAverage.value(),
			showPersonalBest.value(), showTerminator.value(), showHyperion.value(),
			showGoldenDragon.value(), showBank.value());
	}

	private List<Component> liveActions(StatsView view) {
		List<Component> actions = new ArrayList<>();
		if (PartyListBackend.snapshot().isLeader(localName())) actions.add(kickAction(view.name()));
		if (!view.gearKnown() || !view.bankKnown()) actions.add(pvAction(view.name()));
		return actions;
	}

	record DisplayOptions(boolean compact, boolean showCata, boolean showClass,
		boolean showClassAverage, boolean showMagicalPower, boolean showSecretAverage,
		boolean showPersonalBest, boolean showTerminator, boolean showHyperion,
		boolean showGoldenDragon, boolean showBank) {
	}

	/** Pure card construction shared by chat output and the side-effect-free Click GUI preview. */
	static final class CardFormatter {
		private CardFormatter() {
		}

		static List<Component> format(Font font, StatsView view, DisplayOptions options, int width,
			List<Component> actions) {
		MutableComponent header = Component.literal("✦ ").withStyle(ChatFormatting.AQUA)
			.append(Component.literal(view.name()).withStyle(style -> style
				.withColor(ChatFormatting.WHITE).withBold(true)))
			.append(Component.literal(" joined").withStyle(ChatFormatting.GRAY))
			.append(Component.literal("  ").withStyle(ChatFormatting.DARK_GRAY))
			.append(Component.literal(view.floor() == null ? "[Floor ?]" : "[" + view.floor().displayName() + "]")
				.withStyle(view.floor() == null ? ChatFormatting.YELLOW
					: view.floor().master() ? ChatFormatting.RED : ChatFormatting.GOLD));

		List<Component> overviewParts = new ArrayList<>();
		if (options.showCata()) {
			overviewParts.add(Component.literal("Cata ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(Integer.toString(view.catacombsLevel())).withStyle(ChatFormatting.GOLD)));
		}
		if (options.showClass()) {
			Component classPart = hover(Component.literal(view.selectedClass() == null
				? "Class ?" : view.selectedClass().displayName() + " " + view.selectedClassLevel())
				.withStyle(classColor(view.selectedClass())), view.allClassLevels());
			overviewParts.add(classPart);
		}
		if (options.showClassAverage()) {
			overviewParts.add(Component.literal("CA ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(PartyFinderStatsModule.format(view.classAverage())).withStyle(ChatFormatting.AQUA)));
		}
		if (options.showMagicalPower()) {
			overviewParts.add(Component.literal("MP ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(String.format(Locale.ROOT, "%,d", view.magicalPower()))
					.withStyle(ChatFormatting.LIGHT_PURPLE)));
		}
		if (options.showSecretAverage()) {
			Component secretPart = hover(Component.literal(PartyFinderStatsModule.format(view.secretAverage())).withStyle(ChatFormatting.AQUA),
				"Total secrets: " + view.totalSecrets() + "\nRuns: " + view.totalRuns());
			overviewParts.add(Component.literal("SA ").withStyle(ChatFormatting.GRAY).append(secretPart));
		}
		if (options.showPersonalBest()) {
			Component pbPart = hover(Component.literal(view.personalBest()).withStyle(
				view.personalBest().equals("-") ? ChatFormatting.YELLOW : ChatFormatting.GREEN), view.allPbs());
			overviewParts.add(Component.literal("PB ").withStyle(ChatFormatting.GRAY).append(pbPart));
		}

		List<Component> gearParts = new ArrayList<>();
		if (options.showTerminator()) {
			gearParts.add(Component.literal("Term ").withStyle(ChatFormatting.GRAY)
				.append(mark(view.terminatorKnown(), view.terminator())));
		}
		if (options.showHyperion()) {
			gearParts.add(Component.literal("Hype ").withStyle(ChatFormatting.GRAY)
				.append(mark(view.hyperionKnown(), view.hyperion())));
		}
		if (options.showGoldenDragon()) {
			gearParts.add(Component.literal("GDrag ").withStyle(ChatFormatting.GRAY)
				.append(mark(view.goldenDragonKnown(), view.goldenDragon())));
		}
		if (options.showBank()) {
			Component bankPart = view.bankKnown()
				? Component.literal(formatBank(view.bank())).withStyle(ChatFormatting.GOLD)
				: hover(Component.literal("?").withStyle(ChatFormatting.YELLOW), "Bank API data unavailable");
			gearParts.add(Component.literal("Bank ").withStyle(ChatFormatting.GRAY).append(bankPart));
		}
		gearParts.addAll(actions);

		List<Component> segments = new ArrayList<>();
		segments.add(header);
		if (!overviewParts.isEmpty()) segments.add(joinParts(overviewParts));
		if (!gearParts.isEmpty()) segments.add(joinParts(gearParts));
		if (options.compact()) {
			MutableComponent compactLine = Component.literal("[✦ PF] ").withStyle(style -> style
				.withColor(ChatFormatting.AQUA).withBold(true));
			return List.of(compactLine.append(joinParts(segments)));
		}

		List<Component> lines = new ArrayList<>();
		lines.add(cardTop(font, width));
		for (Component segment : segments) {
			lines.add(Component.literal("┃ ").withStyle(ChatFormatting.DARK_AQUA).append(segment));
		}
		lines.add(cardBottom(font, width));
		return lines;
		}
	}

	private static Component joinParts(List<Component> parts) {
		MutableComponent joined = Component.empty();
		for (int i = 0; i < parts.size(); i++) {
			if (i > 0) joined.append(separator());
			joined.append(parts.get(i));
		}
		return joined;
	}

	private static Component joinLines(List<Component> lines) {
		MutableComponent joined = Component.empty();
		for (int i = 0; i < lines.size(); i++) {
			if (i > 0) joined.append("\n");
			joined.append(lines.get(i));
		}
		return joined;
	}

	private static Component cardTop(Font font, int width) {
		Component title = Component.literal(" ✦ PARTY FINDER ✦ ").withStyle(style -> style
			.withColor(ChatFormatting.AQUA).withBold(true));
		int available = Math.max(0, width - font.width("╭╮") - font.width(title));
		int dashCount = available / Math.max(1, font.width("━"));
		int left = dashCount / 2;
		int right = dashCount - left;
		return Component.literal("╭" + "━".repeat(left)).withStyle(ChatFormatting.DARK_AQUA)
			.append(title)
			.append(Component.literal("━".repeat(right) + "╮").withStyle(ChatFormatting.DARK_AQUA));
	}

	private static Component cardBottom(Font font, int width) {
		int available = Math.max(0, width - font.width("╰╯"));
		int dashCount = available / Math.max(1, font.width("━"));
		return Component.literal("╰" + "━".repeat(dashCount) + "╯")
			.withStyle(ChatFormatting.DARK_AQUA);
	}

	private static int chatTextWidth(Minecraft mc) {
		double scale = Math.max(0.01D, mc.options.chatScale().get());
		return Math.max(20, (int) Math.floor(ChatComponent.getWidth(mc.options.chatWidth().get()) / scale) - 8);
	}

	record StatsView(String name, DungeonFloor floor, DungeonClass selectedClass, int selectedClassLevel,
		int catacombsLevel, double classAverage, long totalSecrets, long totalRuns, double secretAverage,
		int magicalPower, long bank, boolean bankKnown, boolean gearKnown, boolean terminatorKnown,
		boolean hyperionKnown, boolean goldenDragonKnown, boolean terminator, boolean hyperion,
		boolean goldenDragon, String personalBest, String allClassLevels, String allPbs) {
		static StatsView from(DungeonFloor floor, PartyMember member, DungeonStats stats, DungeonClass selectedClass) {
			String personalBest = floor == null ? "-" : DungeonStatsService.formatTime(stats.fastestSPlusSeconds(floor));
			return new StatsView(stats.name(), floor, selectedClass,
				selectedClass == null ? 0 : stats.classLevel(selectedClass), stats.catacombsLevel(),
				stats.classAverage(), stats.totalSecrets(), stats.totalRuns(), stats.secretAverage(),
				stats.magicalPower(), stats.bank(), stats.bankKnown(), stats.gearKnown(),
				stats.hasGearData(DungeonStats.Gear.TERMINATOR),
				stats.hasGearData(DungeonStats.Gear.HYPERION),
				stats.hasGearData(DungeonStats.Gear.GOLDEN_DRAGON),
				stats.has(DungeonStats.Gear.TERMINATOR), stats.has(DungeonStats.Gear.HYPERION),
				stats.has(DungeonStats.Gear.GOLDEN_DRAGON), personalBest, stats.allClassLevels(),
				PartyFinderStatsModule.allPbs(stats));
		}

		static StatsView preview() {
			return new StatsView("ExamplePlayer", DungeonFloor.F7, DungeonClass.MAGE, 45, 42, 46.25,
				12_480, 1_120, 11.14, 720, 125_000_000L, true, true, true, true, true, true, false, true,
				"6:42", "Tank 38\nHealer 29\nMage 45\nBerserk 41\nArcher 40",
				"F1: 2:10\nF2: 2:32\nF3: 3:01\nF4: 3:44\nF5: 4:18\nF6: 5:31\nF7: 6:42");
		}
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
		displayLifecycleEpoch++;
		pendingChatCards.clear();
		chatCardCooldown = 0;
		fetchRequested = false;
		cycleStarted = false;
		ticksSinceRequest = 0;
		cycleTargetName = null;
		pendingResults = 0;
		initialDispatching = false;
		initialScanFinished = false;
		localJoinedThroughFinder = false;
		activeScanToken = -1;
		scanPending.clear();
		cycleStats.clear();
		cycleStatUuids.clear();
		deferredJoins.clear();
		checkedPlayers.clear();
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
