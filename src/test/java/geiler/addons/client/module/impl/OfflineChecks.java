package geiler.addons.client.module.impl;

import geiler.addons.client.enchanting.ExperimentCell;
import geiler.addons.client.enchanting.ExperimentBoardGeometry;
import geiler.addons.client.enchanting.ChronomatronEvent;
import geiler.addons.client.enchanting.ChronomatronModel;
import geiler.addons.client.enchanting.ExperimentMilestone;
import geiler.addons.client.enchanting.ExperimentPhase;
import geiler.addons.client.enchanting.ExperimentSnapshot;
import geiler.addons.client.enchanting.ExperimentSolverEngine;
import geiler.addons.client.enchanting.ExperimentTier;
import geiler.addons.client.enchanting.ExperimentType;
import geiler.addons.client.enchanting.SuperpairsBoard;
import geiler.addons.client.location.Island;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.DebugState;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.module.TextSetting;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Small no-server checks for the state boundaries that are easy to regress. */
public final class OfflineChecks {
	private OfflineChecks() {
	}

	public static void main(String[] args) {
		checkIslandModes();
		checkPersonalBestBoundaries();
		checkAutoKickLabels();
		checkEveryStatsToggleCombination();
		checkGlobalDebugGate();
		checkChronomatronModel();
		checkExperimentSolverEngine();
		checkExperimentStateEdges();
	}

	private static void checkExperimentStateEdges() {
		assertSame(ExperimentPhase.ROUND_COMPLETE,
			ExperimentPhase.detect(ExperimentType.CHRONOMATRON, "§eRound Complete"),
			"Round Complete is not swallowed by the terminal complete check");
		assertSame(ExperimentPhase.COMPLETE,
			ExperimentPhase.detect(ExperimentType.CHRONOMATRON, "Max clicks reached!"),
			"max-click status remains terminal");
		assertSame(ExperimentPhase.WAITING,
			ExperimentPhase.detect(ExperimentType.CHRONOMATRON, "server is syncing"),
			"unknown sequence status fails closed");
		assertSame(ExperimentPhase.WAITING,
			ExperimentPhase.detect(ExperimentType.CHRONOMATRON, "Correct!"),
			"transient click feedback does not end a Chronomatron round");
		assertTrue(ExperimentType.fromTitle("Chronomatron (Metaphysical)").isPresent(),
			"valid experiment title is recognized");
		assertFalse(ExperimentType.fromTitle("Chronomatron (Not A Tier)").isPresent(),
			"malformed experiment title is ignored");

		ChronomatronModel endModel = new ChronomatronModel();
		endModel.observe(ChronomatronEvent.board(17, "red", true));
		endModel.observe(ChronomatronEvent.status("Timer: 4.0s"));
		endModel.observe(ChronomatronEvent.board(17, "red", false));
		assertTrue(endModel.click("red"), "Chronomatron reaches its end state after the click");
		endModel.observe(ChronomatronEvent.status("Remember the pattern!"));
		assertSame(ChronomatronModel.State.REMEMBER, endModel.state(), "next memory starts replay");
		assertEquals(17, endModel.currentRevealSlot(), "replay retains the reveal latch until a clear");
		endModel.observe(ChronomatronEvent.board(17, "red", true));
		assertEquals(0, endModel.chainLengthCount(), "lingering highlight is not a replay pulse");
		endModel.observe(ChronomatronEvent.board(17, "red", false));
		assertEquals(0, endModel.currentRevealSlot(), "real clear releases the reveal latch");

		SequenceSolverChecks.run();

		ExperimentSolverEngine pairEngine = new ExperimentSolverEngine();
		String pairTitle = "Superpairs (High)";
		List<ExperimentCell> hidden = List.of(
			new ExperimentCell(11, null, -1, false, false, false),
			new ExperimentCell(12, null, -1, false, false, false));
		pairEngine.observe(new ExperimentSnapshot(pairTitle, "Next button is instantly rewarded!", hidden));
		assertTrue(pairEngine.onClick(11).expected(), "Superpairs accepts the first selection");
		pairEngine.observe(new ExperimentSnapshot(pairTitle, "Next button is instantly rewarded!", List.of(
			ExperimentCell.token(11, "BOOK", false), hidden.get(1))));
		assertTrue(pairEngine.onClick(12).expected(), "Superpairs accepts the second selection");
		pairEngine.observe(new ExperimentSnapshot(pairTitle, "Next button is instantly rewarded!", List.of(
			ExperimentCell.token(11, "BOOK", false), ExperimentCell.token(12, "BOOK", false))));
		pairEngine.observe(new ExperimentSnapshot(pairTitle, "Next button is instantly rewarded!", hidden));
		assertEquals(1, pairEngine.view().superpairs().resolvedPairs(),
			"Superpairs resolves only after both revealed cards hide again");
	}

	private static void checkChronomatronModel() {
		ChronomatronModel model = new ChronomatronModel();
		model.observe(ChronomatronEvent.board(17, "red", true));
		assertSame(ChronomatronModel.State.WAIT, model.state(),
			"the first new reveal closes Chronomatron memory capture");
		assertEquals(List.of("red"), model.items(), "Chronomatron stores item identity, not slot id");
		model.observe(ChronomatronEvent.board(18, "blue", true));
		assertEquals(List.of("red"), model.items(),
			"extra glints during WAIT cannot become clicks");

		model.observe(ChronomatronEvent.status("Timer: 4.0s"));
		assertSame(ChronomatronModel.State.SHOW, model.state(), "Timer opens the solve state");
		model.observe(ChronomatronEvent.board(18, "blue", true));
		assertEquals(List.of("red"), model.items(),
			"the player's solve-phase glint is never recorded");
		assertTrue(model.click("red"), "the expected live item advances the solve ordinal");
		assertSame(ChronomatronModel.State.END, model.state(), "the completed chain closes the round");

		model.observe(ChronomatronEvent.status("Remember the pattern!"));
		assertSame(ChronomatronModel.State.REMEMBER, model.state(), "Remember starts the next replay");
		// Skyblocker's model keeps the active reveal slot until that exact slot loses its glint.
		model.observe(ChronomatronEvent.board(17, "red", false));
		model.observe(ChronomatronEvent.board(17, "red", true));
		model.observe(ChronomatronEvent.board(17, "red", true));
		assertEquals(1, model.chainLengthCount(), "duplicate replay packets count only once");
		model.observe(ChronomatronEvent.board(17, "red", false));
		model.observe(ChronomatronEvent.board(18, "blue", true));
		assertEquals(List.of("red", "blue"), model.items(),
			"only the final new reveal is appended in round two");
		model.observe(ChronomatronEvent.board(19, "green", true));
		assertEquals(List.of("red", "blue"), model.items(),
			"a stale extra reveal cannot grow a closed memory round");

		ChronomatronModel statusDuringMemory = new ChronomatronModel();
		statusDuringMemory.observe(ChronomatronEvent.board(17, "red", true));
		statusDuringMemory.observe(ChronomatronEvent.status("Remember the pattern!"));
		assertSame(ChronomatronModel.State.WAIT, statusDuringMemory.state(),
			"a repeated remember label does not restart an in-progress memory reveal");
		statusDuringMemory.observe(ChronomatronEvent.status("Timer: 4.0s"));
		assertSame(ChronomatronModel.State.SHOW, statusDuringMemory.state(),
			"the timer still opens solve after a repeated memory status");

		ExperimentSolverEngine roundBoundary = new ExperimentSolverEngine();
		roundBoundary.observe(new ExperimentSnapshot("Chronomatron (High)", "Remember the pattern!",
			List.of(), -1, 0, -1, 1), ChronomatronEvent.board(17, "red", true));
		var boundary = roundBoundary.observe(new ExperimentSnapshot("Chronomatron (High)",
			"Round Complete", List.of(), -1, 0, -1, 2), ChronomatronEvent.status("Round Complete"));
		assertSame(ExperimentPhase.WAITING, boundary.phase(),
			"only a timer event opens WAIT; an unrelated label cannot override the model");

		ChronomatronModel tenRounds = new ChronomatronModel();
		List<String> colors = List.of("red", "blue", "green", "yellow", "purple", "pink", "cyan",
			"orange", "lime", "light_blue");
		for (int round = 0; round < colors.size(); round++) {
			if (round > 0) {
				tenRounds.observe(ChronomatronEvent.status("Remember the pattern!"));
				tenRounds.observe(ChronomatronEvent.board(tenRounds.currentRevealSlot(), "old", false));
			}
			for (int previous = 0; previous < round; previous++) {
				int slot = 17 + previous;
				tenRounds.observe(ChronomatronEvent.board(slot, colors.get(previous), true));
				tenRounds.observe(ChronomatronEvent.board(slot, colors.get(previous), false));
			}
			int newSlot = 17 + round;
			tenRounds.observe(ChronomatronEvent.board(newSlot, colors.get(round), true));
			tenRounds.observe(ChronomatronEvent.status("Timer: 4.0s"));
			for (String color : colors.subList(0, round + 1)) {
				assertTrue(tenRounds.click(color), "Chronomatron accepts round " + (round + 1) + " click");
			}
		}
		assertEquals(9, tenRounds.completedRounds(),
			"Chronomatron keeps the local completed-round count over ten rounds");
	}

	private static void checkIslandModes() {
		assertSame(Island.PRIVATE_ISLAND, Island.fromMode("dynamic"), "known island mode");
		assertSame(Island.SAFARI, Island.fromMode("SAFARI"), "case-insensitive island mode");
		assertSame(Island.OTHER, Island.fromMode(null), "missing mode");
		assertSame(Island.OTHER, Island.fromMode("unknown_mode"), "unknown mode");
	}

	private static void checkPersonalBestBoundaries() {
		assertTrue(AutoKickRules.personalBestPasses(0, 0), "zero limit disables PB check");
		assertTrue(AutoKickRules.personalBestPasses(60, 60), "PB equal to limit passes");
		assertTrue(AutoKickRules.personalBestPasses(59, 60), "faster PB passes");
		assertFalse(AutoKickRules.personalBestPasses(61, 60), "slower PB fails");
		assertFalse(AutoKickRules.personalBestPasses(0, 60), "missing PB fails a configured check");
	}

	private static void checkAutoKickLabels() {
		List<String> expectedBooleanLabels = List.of("Auto Kick", "Ask Before", "Dupe", "Terminator", "Hyperion", "GDrag");
		List<String> expectedTextLabels = List.of("Min Cata Level", "Min Class Level", "Min Class Avg", "Min Secrets",
			"Min Secret Avg", "Min MP", "Minimum PB", "Min Bank");
		List<BooleanSetting> booleans = AutoKickModule.INSTANCE.booleanSettings();
		List<TextSetting> texts = AutoKickModule.INSTANCE.textSettings();
		// The first BooleanSetting is module diagnostics; the next six are the first floor policy.
		for (int i = 0; i < expectedBooleanLabels.size(); i++) {
			assertEquals(expectedBooleanLabels.get(i), booleans.get(i + 1).displayName(), "AutoKick boolean label " + i);
		}
		for (int i = 0; i < expectedTextLabels.size(); i++) {
			assertEquals(expectedTextLabels.get(i), texts.get(i).displayName(), "AutoKick text label " + i);
		}
		assertEquals("F1 Auto Kick", booleans.get(1).name(), "AutoKick stable boolean key");
		assertEquals("F1 Minimum PB", texts.get(6).name(), "AutoKick stable PB key");
	}

	private static void checkEveryStatsToggleCombination() {
		String[] markers = {"Cata 42", "Mage 45", "CA 46.25", "MP 720", "SA 11.14", "PB 6:42",
			"Term ✓", "Hype X", "GDrag ✓", "Bank 125,000,000"};
		for (int mask = 0; mask < 1 << markers.length; mask++) {
			boolean[] enabled = new boolean[markers.length];
			for (int bit = 0; bit < enabled.length; bit++) enabled[bit] = (mask & (1 << bit)) != 0;
			PartyFinderStatsModule.DisplayOptions options = new PartyFinderStatsModule.DisplayOptions(true,
				enabled[0], enabled[1], enabled[2], enabled[3], enabled[4], enabled[5], enabled[6],
				enabled[7], enabled[8], enabled[9]);
			List<Component> lines = PartyFinderStatsModule.CardFormatter.format(null,
				PartyFinderStatsModule.StatsView.preview(), options, 160, List.of());
			String output = lines.get(0).getString();
			for (int bit = 0; bit < markers.length; bit++) {
				if (enabled[bit] != output.contains(markers[bit])) {
					throw new AssertionError("toggle combination " + mask + " rendered marker " + markers[bit]
						+ " incorrectly: " + output);
				}
			}
			if (output.contains("│  │")) throw new AssertionError("empty stat separator in: " + output);
		}
	}

	private static void checkGlobalDebugGate() {
		assertFalse(DebugModule.INSTANCE.isEnabled(), "Dev Debug module defaults off");
		assertEquals("DEV", DebugModule.INSTANCE.category().name(), "Dev Debug module category");
		DebugState.setEnabled(false);
		BooleanSetting debug = BooleanSetting.debug("Debug", true);
		assertFalse(debug.value(), "debug setting is effectively off behind the global gate");
		assertTrue(debug.rawValue(), "debug setting keeps its raw value while gated");

		DebugState.setEnabled(true);
		assertTrue(debug.value(), "debug setting restores its raw value when enabled");
		DebugState.setEnabled(false);
		assertFalse(debug.value(), "debug setting is gated again after disabling diagnostics");
		assertTrue(debug.rawValue(), "disabling diagnostics does not erase the raw value");

		assertDebugSetting(AutoKickModule.INSTANCE, "Debug");
		assertDebugSetting(PartyFinderStatsModule.INSTANCE, "Debug");
		assertDebugSetting(HideyhoFinderModule.INSTANCE, "Debug Mode");
		assertDebugSetting(SparklingCritterModule.INSTANCE, "Debug");
		assertDebugSetting(TikiHelperModule.INSTANCE, "Debug Logging");
		assertDebugGroup(AutoKickModule.INSTANCE, "Diagnostics");
		assertDebugGroup(PartyFinderStatsModule.INSTANCE, "Diagnostics");
		assertDebugGroup(TikiHelperModule.INSTANCE, "Debug Logging");

		// Standalone checks must not leak the gate into a later harness invocation.
		DebugState.setEnabled(false);
	}

	private static void checkExperimentSolverEngine() {
		ExperimentBoardGeometry chronoGeometry = ExperimentBoardGeometry.forExperiment(
			ExperimentType.CHRONOMATRON, ExperimentTier.HIGH);
		assertTrue(chronoGeometry.containsSlot(17), "Chronomatron includes the first playable cell");
		assertTrue(chronoGeometry.containsSlot(25), "Chronomatron includes the last single-row cell");
		assertFalse(chronoGeometry.containsSlot(16), "Chronomatron excludes the frame before the board");
		assertEquals(0, chronoGeometry.column(17), "Chronomatron column mapping");
		assertEquals(8, chronoGeometry.column(25), "Chronomatron last column mapping");
		ExperimentBoardGeometry longChronoGeometry = ExperimentBoardGeometry.forExperiment(
			ExperimentType.CHRONOMATRON, ExperimentTier.METAPHYSICAL);
		assertEquals(17, longChronoGeometry.slotId(0, 0), "Metaphysical Chronomatron first slot");
		assertEquals(34, longChronoGeometry.slotId(8, 1), "Metaphysical Chronomatron last slot");

		ExperimentBoardGeometry ultraGeometry = ExperimentBoardGeometry.forExperiment(
			ExperimentType.ULTRASEQUENCER, ExperimentTier.SUPREME);
		assertEquals(9, ultraGeometry.slotId(0, 0), "Ultrasequencer includes the first board slot");
		assertEquals(44, ultraGeometry.slotId(8, 3), "Ultrasequencer includes the last board slot");
		assertTrue(ultraGeometry.containsSlot(44), "decorative panes remain part of the capture range");
		ExperimentBoardGeometry pairsGeometry = ExperimentBoardGeometry.forExperiment(
			ExperimentType.SUPERPAIRS, ExperimentTier.HIGH);
		assertTrue(pairsGeometry.containsSlot(11), "High Superpairs includes its centered first cell");
		assertFalse(pairsGeometry.containsSlot(10), "High Superpairs excludes the side frame");

		ExperimentSolverEngine engine = new ExperimentSolverEngine(
			new ExperimentSolverEngine.Configuration(true, 0));
		String chronoTitle = "Chronomatron (Metaphysical)";
		var memory = engine.observe(new ExperimentSnapshot(chronoTitle,
			"Remember the pattern!", List.of(), -1, 0, -1, 1),
			ChronomatronEvent.status("Remember the pattern!"));
		assertSame(ExperimentType.CHRONOMATRON, memory.type(), "Chronomatron title detection");
		assertSame(ExperimentTier.METAPHYSICAL, memory.tier(), "Metaphysical title detection");
		assertSame(ExperimentPhase.MEMORIZE, memory.phase(), "memory phase detection");
		var captured = engine.observe(new ExperimentSnapshot(chronoTitle, "Remember the pattern!",
			List.of(), -1, 0, -1, 1), ChronomatronEvent.board(17, "red", true));
		assertSame(ExperimentPhase.WAITING, captured.phase(),
			"a new reveal immediately closes memory capture like Skyblocker");
		engine.observe(new ExperimentSnapshot(chronoTitle, "Timer: 4.0s", List.of(),
			-1, 0, 0, 2), ChronomatronEvent.status("Timer: 4.0s"));
		var solve = engine.observe(new ExperimentSnapshot(chronoTitle, "Timer: 4.0s",
			List.of(ExperimentCell.token(17, "red", false)), -1, 0, 0, 2));
		assertEquals(1, solve.sequence().size(), "Chronomatron remembers the revealed sequence");
		assertTrue(solve.current().isPresent(), "current solution is exposed");
		assertEquals(List.of(17), solve.sequence().get(0).slotIds(),
			"the remembered item is resolved against the current live board");
		assertEquals(1, solve.upcoming(2).size(), "a one-click round does not invent a future click");
		var transientFeedback = engine.observe(new ExperimentSnapshot(chronoTitle, "Correct!",
			List.of(ExperimentCell.token(17, "red", false)), -1, 0, -1, 3));
		assertSame(ExperimentPhase.SOLVE, transientFeedback.phase(),
			"transient Chronomatron feedback preserves the trusted solve phase");
		assertTrue(transientFeedback.current().isPresent(),
			"transient Chronomatron feedback keeps the correct button visible");
		var staleChrono = engine.observe(new ExperimentSnapshot(chronoTitle, "Remember the pattern!",
			List.of(ExperimentCell.token(17, "red", false)), -1, 0, -1, 2),
			ChronomatronEvent.status("Remember the pattern!"));
		assertSame(ExperimentPhase.SOLVE, staleChrono.phase(),
			"an out-of-order Chronomatron callback cannot erase a trusted solve state");
		assertTrue(staleChrono.current().isPresent(),
			"an out-of-order Chronomatron callback keeps the current button visible");

		// A real Hypixel transition can briefly clear/replace slot 49 while the final reveal is still
		// arriving. That status frame must not suppress the board event or truncate the next round.
		var transientMemory = new ExperimentSolverEngine();
		transientMemory.observe(new ExperimentSnapshot(chronoTitle, "Remember the pattern!", List.of(),
			-1, 0, -1, 1), ChronomatronEvent.status("Remember the pattern!"));
		transientMemory.observe(new ExperimentSnapshot(chronoTitle, "Remember the pattern!", List.of(),
			-1, 0, -1, 2), ChronomatronEvent.board(17, "red", true));
		transientMemory.observe(new ExperimentSnapshot(chronoTitle, "Timer: 4.0s",
			List.of(ExperimentCell.token(17, "red", false)),
			-1, 0, -1, 3), ChronomatronEvent.status("Timer: 4.0s"));
		assertTrue(transientMemory.onClick(17).expected(),
			"the first Chronomatron round accepts its remembered click");
		assertTrue(transientMemory.confirmClick(17).visualStateChanged(),
			"the first Chronomatron round reaches its end state");
		transientMemory.observe(new ExperimentSnapshot(chronoTitle, "Round Complete", List.of(),
			-1, 0, -1, 4), ChronomatronEvent.status("Timer: 0.0s"));
		transientMemory.observe(new ExperimentSnapshot(chronoTitle, "Remember the pattern!", List.of(),
			-1, 1, 1, 5), ChronomatronEvent.status("Remember the pattern!"));
		transientMemory.observe(new ExperimentSnapshot(chronoTitle, "Remember the pattern!", List.of(),
			-1, 1, 1, 5), ChronomatronEvent.board(17, "red", false));
		transientMemory.observe(new ExperimentSnapshot(chronoTitle, "Remember the pattern!", List.of(),
			-1, 1, 1, 6), ChronomatronEvent.board(17, "red", true));
		transientMemory.observe(new ExperimentSnapshot(chronoTitle, "", List.of(),
			-1, 1, 1, 7), ChronomatronEvent.board(17, "red", false));
		transientMemory.observe(new ExperimentSnapshot(chronoTitle, "Correct!", List.of(),
			-1, 1, 1, 8), ChronomatronEvent.board(18, "blue", true));
		var recoveredMemory = transientMemory.observe(new ExperimentSnapshot(chronoTitle, "Timer: 4.0s",
			List.of(ExperimentCell.token(17, "red", false), ExperimentCell.token(18, "blue", false)),
			-1, 1, 1, 9), ChronomatronEvent.status("Timer: 4.0s"));
		assertEquals(List.of("red", "blue"), recoveredMemory.sequence().stream()
			.map(step -> step.value()).toList(),
			"Chronomatron keeps a reveal that arrived during a transient status frame");
		assertEquals(2, recoveredMemory.upcoming(2).size(),
			"Chronomatron exposes both clicks after a transient status frame");
		assertEquals(0, recoveredMemory.visualIndex(),
			"previous-round progress cannot make Chronomatron repeat or skip click one");
		assertTrue(transientMemory.onClick(17).expected(),
			"round two accepts the first Chronomatron click");
		assertTrue(transientMemory.confirmClick(17).visualStateChanged(),
			"round two advances after its first Chronomatron click");
		var falseChronoMemory = transientMemory.observe(new ExperimentSnapshot(chronoTitle,
			"Remember the pattern!",
			List.of(ExperimentCell.token(17, "red", false),
				ExperimentCell.token(18, "blue", false)),
			-1, 1, 0, 10), ChronomatronEvent.status("Remember the pattern!"));
		assertSame(ExperimentPhase.SOLVE, falseChronoMemory.phase(),
			"a stale memory label cannot end an unfinished Chronomatron solve");
		assertEquals(1, falseChronoMemory.visualIndex(),
			"a stale memory label cannot roll Chronomatron back to click one");
		assertEquals("blue", falseChronoMemory.current().orElseThrow().value(),
			"Chronomatron keeps click two selected after transient memory feedback");
		assertTrue(transientMemory.onClick(18).expected(),
			"Chronomatron's second click remains playable after transient feedback");
		assertTrue(transientMemory.confirmClick(18).visualStateChanged(),
			"Chronomatron completes the two-click round");
		var staleCompletedMemory = transientMemory.observe(new ExperimentSnapshot(chronoTitle,
			"Remember the pattern!", List.of(), -1, 1, 0, 11));
		assertSame(ExperimentPhase.ROUND_COMPLETE, staleCompletedMemory.phase(),
			"a polled old memory label cannot restart a completed Chronomatron round");
		assertEquals(2, staleCompletedMemory.visualIndex(),
			"stale polling cannot roll back a completed Chronomatron cursor");
		var nextChronoRound = transientMemory.observe(new ExperimentSnapshot(chronoTitle,
			"Remember the pattern!", List.of(), -1, 2, 2, 12),
			ChronomatronEvent.status("Remember the pattern!"));
		assertSame(ExperimentPhase.MEMORIZE, nextChronoRound.phase(),
			"Chronomatron accepts memory after the prior sequence is complete");
		assertEquals(0, nextChronoRound.visualIndex(),
			"Chronomatron resets its cursor at the real next-round boundary");
		// A complete two-round trace. The stale timer/polling frames between the final click and the
		// next Remember callback must not reopen click one or truncate the next sequence.
		var directChrono = new ExperimentSolverEngine();
		directChrono.observe(new ExperimentSnapshot(chronoTitle, "Remember the pattern!", List.of(),
			-1, 0, -1, 20), ChronomatronEvent.status("Remember the pattern!"));
		directChrono.observe(new ExperimentSnapshot(chronoTitle, "Remember the pattern!", List.of(),
			-1, 0, -1, 21), ChronomatronEvent.board(17, "red", true));
		directChrono.observe(new ExperimentSnapshot(chronoTitle, "Timer: 4.0s", List.of(),
			-1, 0, -1, 22), ChronomatronEvent.status("Timer: 4.0s"));
		directChrono.observe(new ExperimentSnapshot(chronoTitle, "Timer: 4.0s",
			List.of(ExperimentCell.token(17, "red", false)), -1, 0, -1, 22));
		assertTrue(directChrono.onClick(17).expected(), "round one accepts its expected tile");
		assertTrue(directChrono.confirmClick(17).visualStateChanged(),
			"round one advances after the vanilla click dispatch");
		directChrono.observe(new ExperimentSnapshot(chronoTitle, "Round Complete", List.of(),
			-1, 0, -1, 23), ChronomatronEvent.status("Timer: 0.0s"));
		directChrono.observe(new ExperimentSnapshot(chronoTitle, "Remember the pattern!", List.of(),
			-1, 0, -1, 24), ChronomatronEvent.status("Remember the pattern!"));
		directChrono.observe(new ExperimentSnapshot(chronoTitle, "Remember the pattern!", List.of(),
			-1, 0, -1, 25), ChronomatronEvent.board(17, "red", false));
		directChrono.observe(new ExperimentSnapshot(chronoTitle, "Remember the pattern!", List.of(),
			-1, 0, -1, 26), ChronomatronEvent.board(17, "red", true));
		directChrono.observe(new ExperimentSnapshot(chronoTitle, "Remember the pattern!", List.of(),
			-1, 0, -1, 27), ChronomatronEvent.board(17, "red", false));
		directChrono.observe(new ExperimentSnapshot(chronoTitle, "Correct!", List.of(),
			-1, 0, -1, 28), ChronomatronEvent.board(18, "blue", true));
		var directRoundTwo = directChrono.observe(new ExperimentSnapshot(chronoTitle, "Timer: 4.0s",
			List.of(ExperimentCell.token(20, "red", false), ExperimentCell.token(21, "blue", false)),
			-1, 0, -1, 29), ChronomatronEvent.status("Timer: 4.0s"));
		assertEquals(List.of("red", "blue"), directRoundTwo.sequence().stream()
			.map(step -> step.value()).toList(), "round two keeps the old item and appends one new item");
		assertEquals(List.of(20), directRoundTwo.sequence().get(0).slotIds(),
			"Chronomatron resolves click one against the live board");
		assertEquals(List.of(21), directRoundTwo.sequence().get(1).slotIds(),
			"Chronomatron resolves click two against the live board");
		assertEquals(0, directRoundTwo.visualIndex(), "the next round starts at click one");
		assertTrue(directChrono.onClick(20).expected(), "round two accepts click one");
		assertTrue(directChrono.confirmClick(20).visualStateChanged(), "round two advances to click two");
		var staleTimer = directChrono.observe(new ExperimentSnapshot(chronoTitle, "Remember the pattern!",
			List.of(ExperimentCell.token(20, "red", false), ExperimentCell.token(21, "blue", false)),
			-1, 0, 0, 30));
		assertEquals(1, staleTimer.visualIndex(), "a stale memory label cannot roll the cursor back");
		assertSame(ExperimentPhase.SOLVE, staleTimer.phase(),
			"a stale memory label cannot end an unfinished solve");

		var predictionEngine = new ExperimentSolverEngine(
			new ExperimentSolverEngine.Configuration(true, 0));
		predictionEngine.observe(new ExperimentSnapshot(chronoTitle, "Remember the pattern!", List.of(),
			-1, 0, -1, 31), ChronomatronEvent.board(17, "red", true));
		predictionEngine.observe(new ExperimentSnapshot(chronoTitle, "Timer: 4.0s", List.of(),
			-1, 0, -1, 32), ChronomatronEvent.status("Timer: 4.0s"));
		predictionEngine.observe(new ExperimentSnapshot(chronoTitle, "Timer: 4.0s",
			List.of(ExperimentCell.token(17, "red", false)), -1, 0, -1, 32));
		assertTrue(predictionEngine.onClick(17).predicted(), "0 Ping advances only the local model");
		predictionEngine.observe(new ExperimentSnapshot(chronoTitle, "Timer: 3.0s",
			List.of(ExperimentCell.token(17, "red", false)), -1, 0, 0, 33));
		assertEquals(1, predictionEngine.view().visualIndex(),
			"a later snapshot cannot overwrite the 0 Ping cursor");

		var normalEngine = new ExperimentSolverEngine(
			new ExperimentSolverEngine.Configuration(false, 0));
		normalEngine.observe(new ExperimentSnapshot(chronoTitle, "Remember the pattern!", List.of(),
			-1, 0, -1, 1), ChronomatronEvent.board(17, "red", true));
		normalEngine.observe(new ExperimentSnapshot(chronoTitle, "Timer: 4.0s", List.of(),
			-1, 0, 0, 2), ChronomatronEvent.status("Timer: 4.0s"));
		normalEngine.observe(new ExperimentSnapshot(chronoTitle, "Timer: 4.0s",
			List.of(ExperimentCell.token(17, "red", false)), -1, 0, 0, 2));
		assertTrue(normalEngine.onClick(17).expected(), "normal Chronomatron click is accepted");
		assertTrue(normalEngine.confirmClick(17).visualStateChanged(),
			"normal click confirmation advances without an item-stack diff");
		assertEquals(1, normalEngine.view().visualIndex(), "normal click advances the authoritative cursor");

		var ultra = new ExperimentSolverEngine(new ExperimentSolverEngine.Configuration(false, 3));
		var ultraMemory = ultra.observe(new ExperimentSnapshot("Ultrasequencer (Metaphysical)",
			"Remember the pattern!", List.of(ExperimentCell.number(30, 1), ExperimentCell.number(31, 2),
				ExperimentCell.number(32, 3), ExperimentCell.number(33, 4), ExperimentCell.number(34, 5),
				ExperimentCell.number(35, 6)), 6, 5, -1, 1));
		assertEquals(6, ultraMemory.sequence().size(), "Ultrasequencer remembers numbers by order");
		var ultraHandoff = ultra.observe(new ExperimentSnapshot("Ultrasequencer (Metaphysical)",
			"Remember the pattern!", List.of(), -1, 5, -1, 2));
		assertEquals(6, ultraHandoff.sequence().size(),
			"Ultrasequencer retains its sequence while numbered items become panes");
		var ultraView = ultra.observe(new ExperimentSnapshot("Ultrasequencer (Metaphysical)",
			"Timer: 1.0s", List.of(), -1, 5, 0, 2));
		assertEquals(List.of(30), ultraView.sequence().get(0).slotIds(),
			"Ultrasequencer keeps the remembered slot for click 1 during SOLVE");
		assertEquals(List.of(31), ultraView.sequence().get(1).slotIds(),
			"Ultrasequencer keeps the remembered slot for click 2 during SOLVE");
		assertEquals(3, ultraView.upcoming(3).size(), "future preview is capped to available steps");
		assertEquals(List.of(1, 2, 3), ultraView.upcoming(3).stream()
			.map(step -> step.index() + 1).toList(), "future clicks expose their absolute order");
		assertFalse(ultraView.milestoneReached(),
			"Ultrasequencer does not infer completed rounds from a stale snapshot counter");
		ExperimentMilestone milestone = ultraView.milestone().orElseThrow();
		assertEquals(6, milestone.displayedSequenceLength(), "serums lower the displayed target");
		assertEquals(5, milestone.completedRoundThreshold(), "Ultrasequencer target is one round earlier");

		var ultraZeroPing = new ExperimentSolverEngine(
			new ExperimentSolverEngine.Configuration(true, 0));
		String zeroPingTitle = "Ultrasequencer (High)";
		ultraZeroPing.observe(new ExperimentSnapshot(zeroPingTitle, "Remember the pattern!",
			List.of(ExperimentCell.number(30, 1), ExperimentCell.number(31, 2)), "black", 50));
		ultraZeroPing.observe(new ExperimentSnapshot(zeroPingTitle, "Timer: 1.0s",
			List.of(ExperimentCell.token(30, "white", false)), "white", 51));
		assertTrue(ultraZeroPing.onClick(30).predicted(),
			"Ultrasequencer 0 Ping advances its local cursor immediately");
		ultraZeroPing.observe(new ExperimentSnapshot(zeroPingTitle, "Timer: 0.5s",
			List.of(ExperimentCell.token(30, "white", false)), "white", 52));
		assertEquals(1, ultraZeroPing.view().visualIndex(),
			"Ultrasequencer snapshots cannot overwrite a 0 Ping cursor");

		SuperpairsBoard board = new SuperpairsBoard();
		board.observe(List.of(new ExperimentCell(10, "BOOK", -1, true, false, false),
			new ExperimentCell(11, "BOOK", -1, true, false, false),
			new ExperimentCell(12, null, -1, false, false, false)));
		assertEquals(11, board.suggestedMatch(10).orElseThrow(), "Superpairs remembers a matching card");
		assertSame(SuperpairsBoard.CardState.MATCH, board.view().cards().get(0).state(),
			"known Superpairs pair is exposed to the renderer");

		SuperpairsBoard delayedPair = new SuperpairsBoard();
		delayedPair.observe(List.of(new ExperimentCell(10, null, -1, false, false, false),
			new ExperimentCell(11, null, -1, false, false, false)));
		delayedPair.click(10);
		delayedPair.observe(List.of(new ExperimentCell(10, "BOOK", -1, true, false, false),
			new ExperimentCell(11, null, -1, false, false, false)));
		delayedPair.click(11);
		delayedPair.observe(List.of(new ExperimentCell(10, "BOOK", -1, true, false, false),
			new ExperimentCell(11, "BOOK", -1, true, false, false)));
		assertSame(SuperpairsBoard.CardState.MATCH, delayedPair.view().cards().get(0).state(),
			"Superpairs marks a revealed matching pair before it is collected");
		delayedPair.observe(List.of(new ExperimentCell(10, null, -1, false, false, false),
			new ExperimentCell(11, null, -1, false, false, false)));
		assertSame(SuperpairsBoard.CardState.RESOLVED, delayedPair.view().cards().get(0).state(),
			"Superpairs resolves a pair after both cards hide again");
		assertEquals(1, delayedPair.view().resolvedPairs(), "Superpairs exposes completed pair count");
		assertEquals(2, delayedPair.view().knownCount(),
			"Superpairs keeps the discovered card count in a partial board fixture");
		SuperpairsBoard hiddenKnownPair = new SuperpairsBoard();
		hiddenKnownPair.observe(List.of(new ExperimentCell(10, "BOOK", -1, true, false, false),
			new ExperimentCell(11, "BOOK", -1, true, false, false)));
		hiddenKnownPair.observe(List.of(new ExperimentCell(10, null, -1, false, false, false),
			new ExperimentCell(11, null, -1, false, false, false)));
		assertTrue(hiddenKnownPair.click(10), "Superpairs can select a remembered hidden card");
		assertTrue(hiddenKnownPair.click(11), "Superpairs can select its remembered match");
		assertEquals(0, hiddenKnownPair.view().resolvedPairs(),
			"Superpairs does not resolve a hidden pair before server reveals");
		hiddenKnownPair.observe(List.of(new ExperimentCell(10, "BOOK", -1, true, false, false),
			new ExperimentCell(11, "BOOK", -1, true, false, false)));
		assertEquals(0, hiddenKnownPair.view().resolvedPairs(),
			"Superpairs keeps a matching pair visible until it hides again");
		hiddenKnownPair.observe(List.of(new ExperimentCell(10, null, -1, false, false, false),
			new ExperimentCell(11, null, -1, false, false, false)));
		assertEquals(1, hiddenKnownPair.view().resolvedPairs(),
			"Superpairs resolves a remembered pair after both cards hide");
		SuperpairsBoard noLocalPair = new SuperpairsBoard();
		noLocalPair.observe(List.of(new ExperimentCell(10, "BOOK", -1, true, false, false),
			new ExperimentCell(11, "BOOK", -1, true, false, false),
			new ExperimentCell(12, null, -1, false, false, false)));
		noLocalPair.observe(List.of(new ExperimentCell(10, "BOOK", -1, true, false, false),
			new ExperimentCell(11, "BOOK", -1, true, false, false),
			new ExperimentCell(12, "SWORD", -1, true, false, false)));
		assertEquals(0, noLocalPair.view().resolvedPairs(),
			"Superpairs does not count a pair without two local clicks");
		SuperpairsBoard rapidClicks = new SuperpairsBoard();
		rapidClicks.observe(List.of(new ExperimentCell(10, null, -1, false, false, false),
			new ExperimentCell(11, null, -1, false, false, false),
			new ExperimentCell(12, null, -1, false, false, false)));
		assertTrue(rapidClicks.click(10), "Superpairs accepts the first rapid click");
		assertTrue(rapidClicks.click(11), "Superpairs accepts a second click while the first reveal is pending");
		assertTrue(rapidClicks.click(12), "Superpairs accepts a third click while the previous pair is pending");

		SuperpairsBoard mismatch = new SuperpairsBoard();
		mismatch.observe(List.of(new ExperimentCell(10, "BOOK", -1, true, false, false),
			new ExperimentCell(11, "SWORD", -1, true, false, false)));
		mismatch.click(10);
		mismatch.click(11);
		mismatch.observe(List.of(new ExperimentCell(10, "BOOK", -1, true, false, false),
			new ExperimentCell(11, "SWORD", -1, true, false, false)));
		mismatch.observe(List.of(new ExperimentCell(10, null, -1, false, false, false),
			new ExperimentCell(11, null, -1, false, false, false)));
		assertEquals(-1, mismatch.view().selectedSlot(), "Superpairs clears a failed selection after hiding");
		assertSame(SuperpairsBoard.CardState.KNOWN, mismatch.view().cards().get(0).state(),
			"an unmatched hidden card remains playable instead of becoming resolved");
		assertSame(ExperimentPhase.SOLVE,
			new ExperimentSnapshot("Superpairs (Metaphysical)", "Next button is instantly rewarded!",
				List.of()).phase(), "Superpairs instant-reward status keeps the game playable");
		assertSame(ExperimentPhase.COMPLETE,
			new ExperimentSnapshot("Superpairs (Metaphysical)", "Max clicks reached!",
				List.of()).phase(), "Superpairs max-click status is recognized");
		var completedPairs = new ExperimentSolverEngine();
		completedPairs.observe(new ExperimentSnapshot("Superpairs (High)", "Max clicks reached!",
			List.of(ExperimentCell.token(10, "BOOK", false)),
			-1, -1, -1, 1));
		var restartedPairs = completedPairs.observe(new ExperimentSnapshot("Superpairs (High)",
			"Next button is instantly rewarded!", List.of(ExperimentCell.token(11, "SWORD", false)),
			-1, -1, -1, 2));
		assertEquals(1, restartedPairs.superpairs().knownCount(),
			"Superpairs clears a completed board when Hypixel starts the next game");

		assertSame(ExperimentPhase.IDLE, engine.observe(null).phase(), "null snapshot resets the engine");
		assertFalse(ExperimentType.fromTitle("Chest").isPresent(), "unrelated title is ignored");
		assertTrue(ExperimentSolverModule.INSTANCE.supports(ExperimentType.CHRONOMATRON),
			"solver module exposes Chronomatron configuration");
	}

	private static void assertDebugSetting(Module module, String name) {
		for (BooleanSetting setting : module.booleanSettings()) {
			if (setting.name().equals(name)) {
				assertTrue(setting.isDebugOnly(), module.name() + " marks " + name + " as debug-only");
				return;
			}
		}
		throw new AssertionError(module.name() + " is missing debug setting " + name);
	}

	private static void assertDebugGroup(Module module, String name) {
		for (SettingGroup group : module.groups()) {
			if (group.name() != null && group.name().equals(name)) {
				assertTrue(group.debugOnly(), module.name() + " marks " + name + " as debug-only");
				return;
			}
		}
		throw new AssertionError(module.name() + " is missing debug group " + name);
	}

	private static void assertSame(Object expected, Object actual, String label) {
		if (expected != actual) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static void assertFalse(boolean value, String label) {
		if (value) throw new AssertionError(label);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected " + expected + ", got " + actual);
		}
	}
}
