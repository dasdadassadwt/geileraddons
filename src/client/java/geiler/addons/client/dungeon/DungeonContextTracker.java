package geiler.addons.client.dungeon;

import geiler.addons.client.tree.ChatText;
import net.minecraft.client.Minecraft;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared, client-side dungeon context independent of Party Finder module settings. */
public final class DungeonContextTracker {
	private static final long EXIT_GRACE_NANOS = 2_000_000_000L;
	private static final Pattern FLOOR = Pattern.compile(
		"(?i)(?:\\b(M|F)\\s*(VII|VI|IV|V|III|II|I|[1-7])\\b|\\b(Entrance)\\b)");
	private static boolean inDungeon;
	/** Legacy pre-blood Clear phase, kept separate from Dungeon Mob ESP's longer gate. */
	private static boolean clearPhase;
	/** Preserves an explicit blood/boss signal that can arrive just before the first sidebar tick. */
	private static boolean clearEspClosedForRun;
	/** Dungeon Mob ESP stays active through Blood and closes only when a boss-entry stage is seen. */
	private static boolean mobEspBossPhase;
	private static long lastDungeonSignal;
	private static DungeonFloor detectedFloor;
	private static DungeonFloor manualFloor;
	private static Object level;

	private DungeonContextTracker() { }

	/** Call every client tick. Map geometry is gated by Catacombs context and an active consumer. */
	public static void tick(Minecraft client, boolean mapGeometryEnabled, boolean mapEntryScanEnabled) {
		if (client == null || client.level == null) {
			clear();
			return;
		}
		if (level != client.level) {
			level = client.level;
			// Keep the user's manual choice through Hypixel's brief transfer-level replacement.
			lastDungeonSignal = inDungeon ? System.nanoTime() : 0L;
		}
		Signal signal = readSidebar(client.level.getScoreboard());
		long now = System.nanoTime();
		boolean mapEntryCandidate = DungeonMapRoomDetector.hasEntryCandidate(client);
		boolean wasInDungeon = inDungeon;
		if (signal.inDungeon() || wasInDungeon && mapEntryCandidate) {
			inDungeon = true;
			if (!wasInDungeon) clearPhase = shouldBeginClearPhase(clearEspClosedForRun);
			lastDungeonSignal = now;
			if (signal.floor() != null) detectedFloor = signal.floor();
		} else if (inDungeon && lastDungeonSignal != 0L && now - lastDungeonSignal > EXIT_GRACE_NANOS) {
			inDungeon = false;
			clearPhase = false;
			mobEspBossPhase = false;
			manualFloor = null;
			detectedFloor = null;
		}

		if (inDungeon && mapGeometryEnabled && client.player != null) {
			DungeonMapRoomDetector.tick(client);
			return;
		}

		// Outside a confirmed dungeon, a cheap map-marker/color probe may authorize one bounded
		// geometry check per second, but only while Dungeon Mob ESP is enabled. The geometry check
		// validates a dungeon map entry; it performs no source-room or chunk reads.
		if (!wasInDungeon && mapEntryCandidate && mapEntryScanEnabled && client.player != null
			&& shouldRunFullMapScan(false, true) && DungeonMapRoomDetector.shouldRunEntryScan(now)) {
			DungeonMapRoomDetector.tick(client);
			if (DungeonMapRoomDetector.current().confirmsDungeonRoom()) {
				inDungeon = true;
				clearPhase = true;
				lastDungeonSignal = now;
				if (signal.floor() != null) detectedFloor = signal.floor();
				return;
			}
		}
		DungeonMapRoomDetector.deferUntilDungeonContext();
	}

	/** Compatibility entry points for callers that only need lightweight sidebar context updates. */
	public static void tick(Minecraft client, boolean mapGeometryEnabled) {
		tick(client, mapGeometryEnabled, false);
	}
	public static void tick(Minecraft client) { tick(client, false, false); }

	static boolean shouldRunFullMapScan(boolean dungeonContext, boolean mapEntryCandidate) {
		return dungeonContext || mapEntryCandidate;
	}

	public static boolean inDungeon() { return inDungeon; }
	/** Current pre-blood Clear phase for consumers that need that narrower context. */
	public static boolean isClearPhase() { return shouldEnableClearEsp(inDungeon, clearPhase); }
	public static boolean shouldEnableClearEsp(boolean dungeon, boolean clear) { return dungeon && clear; }
	/** Dungeon Mob ESP remains available from Entry through Blood Clear, until boss entry is observed. */
	public static boolean isMobEspPhase() { return shouldEnableMobEsp(inDungeon, mobEspBossPhase); }
	public static boolean shouldEnableMobEsp(boolean dungeon, boolean bossPhase) { return dungeon && !bossPhase; }

	static boolean mobEspBossPhaseAfter(boolean bossPhase, String segmentId) {
		if (segmentId == null) return bossPhase;
		return switch (segmentId) {
			case "ENTRY" -> false;
			case "BOSS_ENTRY", "COMPLETE", "BONZO_SIKE", "BONZO", "SCARF_MINIONS", "SCARF",
				"GUARDIANS", "PROFESSOR", "TRANSFORMED_PROFESSOR", "THORN", "LIVID", "TERRACOTTAS",
				"SADAN_GIANTS", "SADAN", "MAXOR", "STORM", "TERMINALS", "GOLDOR", "NECRON",
				"WITHER_KING" -> true;
			default -> bossPhase;
		};
	}

	static boolean shouldBeginClearPhase(boolean closedByObservedBloodOrBossSignal) {
		return !closedByObservedBloodOrBossSignal;
	}

	/** Receives normalized client chat independently of the optional Dungeon Guide module. */
	public static void onChatMessage(String message) {
		if (message == null || message.isBlank()) return;
		String normalized = message.toLowerCase(Locale.ROOT);
		// The blood-door line is floor-independent and can arrive before a scoreboard floor is
		// available. Close the legacy Clear-only gate from that event rather than waiting for Guide state.
		if (normalized.contains("blood door has been opened")) {
			clearPhase = false;
			clearEspClosedForRun = true;
			return;
		}
		if (normalized.contains("mort:") && (normalized.contains("found this map")
			|| normalized.contains("right-click the orb"))) {
			clearPhase = inDungeon;
			clearEspClosedForRun = false;
			mobEspBossPhase = false;
			return;
		}
		DungeonFloor floor = currentFloor();
		if (floor == null) return;
		DungeonGuideSegments.Event event = DungeonGuideSegments.fromChat(floor, message);
		if (event == null) return;
		mobEspBossPhase = mobEspBossPhaseAfter(mobEspBossPhase, event.segmentId());
		switch (event.segmentId()) {
			case "ENTRY", "CLEAR" -> {
				clearEspClosedForRun = false;
				clearPhase = inDungeon;
			}
			case "BLOOD_OPEN", "BLOOD_CLEAR", "BOSS_ENTRY", "COMPLETE",
				"BONZO_SIKE", "BONZO", "SCARF_MINIONS", "SCARF", "GUARDIANS", "PROFESSOR",
				"TRANSFORMED_PROFESSOR", "THORN", "LIVID", "TERRACOTTAS", "SADAN_GIANTS",
				"SADAN", "MAXOR", "STORM", "TERMINALS", "GOLDOR", "NECRON", "WITHER_KING" -> {
				clearPhase = false;
				clearEspClosedForRun = true;
			}
			default -> { }
		}
	}
	public static DungeonFloor detectedFloor() { return detectedFloor; }
	public static DungeonFloor manualFloor() { return manualFloor; }
	public static DungeonFloor currentFloor() {
		return floorSelection(inDungeon, manualFloor, detectedFloor, DungeonQueueFloorTracker.currentFloor()).floor();
	}

	/** Exposes which validated source currently supplies the effective floor. */
	public static FloorSource floorSource() {
		return floorSelection(inDungeon, manualFloor, detectedFloor, DungeonQueueFloorTracker.currentFloor()).source();
	}

	public static String diagnostics() {
		DungeonFloor floor = currentFloor();
		return "dungeon=" + inDungeon + ", floor=" + (floor == null ? "unknown" : floor.displayName())
			+ " [" + floorSource() + "], detectedFloor=" + (detectedFloor == null ? "unknown" : detectedFloor.displayName())
			+ ", manualFloor=" + (manualFloor == null ? "none" : manualFloor.displayName())
			+ ", " + DungeonMapRoomDetector.diagnostics();
	}

	static FloorSelection floorSelection(boolean dungeon, DungeonFloor manual, DungeonFloor sidebar, DungeonFloor queue) {
		if (!dungeon) return new FloorSelection(null, FloorSource.UNKNOWN);
		if (manual != null) return new FloorSelection(manual, FloorSource.MANUAL);
		if (sidebar != null) return new FloorSelection(sidebar, FloorSource.SIDEBAR);
		if (queue != null) return new FloorSelection(queue, FloorSource.QUEUE);
		return new FloorSelection(null, FloorSource.UNKNOWN);
	}

	/** Manual selection is accepted only while a dungeon context is active. */
	public static boolean setManualFloor(DungeonFloor floor) {
		if (!inDungeon || floor == null) return false;
		manualFloor = floor;
		return true;
	}

	public static boolean clearManualFloor() {
		boolean changed = manualFloor != null;
		manualFloor = null;
		return changed;
	}

	private static Signal readSidebar(Scoreboard scoreboard) {
		if (scoreboard == null) return new Signal(false, null);
		Objective sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
		if (sidebar == null) return new Signal(false, null);
		StringBuilder text = new StringBuilder(ChatText.plain(sidebar.getDisplayName().getString()));
		for (PlayerScoreEntry entry : scoreboard.listPlayerScores(sidebar)) {
			if (entry == null || entry.isHidden()) continue;
			Component lineComponent = entry.display();
			if (lineComponent == null) {
				PlayerTeam team = scoreboard.getPlayersTeam(entry.owner());
				lineComponent = team == null ? entry.ownerName() : team.getFormattedName(entry.ownerName());
			}
			String line = lineComponent.getString();
			if (line != null && !line.isBlank()) text.append('\n').append(ChatText.plain(line));
		}
		return readSidebarText(text.toString());
	}

	static Signal readSidebarText(String plain) {
		if (plain == null || plain.isBlank()) return new Signal(false, null);
		String normalized = plain.toLowerCase(Locale.ROOT);
		Matcher matcher = FLOOR.matcher(plain);
		DungeonFloor floor = null;
		while (matcher.find()) {
			if (matcher.group(3) != null) { floor = DungeonFloor.ENTRANCE; break; }
			String mode = matcher.group(1);
			if (normalized.contains("master mode")) mode = "M";
			String number = matcher.group(2);
			int parsed = romanFloor(number);
			if (parsed > 0) { floor = DungeonFloor.parse((mode == null ? "F" : mode.toUpperCase(Locale.ROOT)) + parsed); break; }
		}
		// Hypixel has used both "The Catacombs (F7)" and "Dungeon: F7"-style sidebar
		// entries. Require a dungeon-specific label or an explicit floor alongside "dungeon"
		// so the party finder and ordinary SkyBlock scoreboard cannot activate this context.
		boolean dungeon = normalized.contains("catacombs") || normalized.contains("dungeon cleared")
			|| normalized.contains("dungeon: entrance")
			|| normalized.contains("dungeon") && (floor != null || normalized.contains("floor"));
		return new Signal(dungeon, floor);
	}

	private static int romanFloor(String value) {
		return switch (value.toUpperCase(Locale.ROOT)) {
			case "I", "1" -> 1; case "II", "2" -> 2; case "III", "3" -> 3;
			case "IV", "4" -> 4; case "V", "5" -> 5; case "VI", "6" -> 6;
			case "VII", "7" -> 7; default -> 0;
		};
	}

	private static void clear() {
		level = null;
		inDungeon = false;
		clearPhase = false;
		clearEspClosedForRun = false;
		mobEspBossPhase = false;
		lastDungeonSignal = 0L;
		detectedFloor = null;
		manualFloor = null;
		DungeonMapRoomDetector.tick(null);
	}

	public enum FloorSource { UNKNOWN, MANUAL, SIDEBAR, QUEUE }
	static record FloorSelection(DungeonFloor floor, FloorSource source) { }
	record Signal(boolean inDungeon, DungeonFloor floor) { }
}
