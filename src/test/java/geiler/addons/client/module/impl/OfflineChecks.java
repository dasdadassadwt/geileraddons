package geiler.addons.client.module.impl;

import geiler.addons.client.dungeon.DungeonStatsChecks;
import geiler.addons.client.dungeon.DungeonStatsCommand;
import geiler.addons.client.collections.FolderTreeChecks;
import geiler.addons.client.gui.ClickGuiMotionChecks;
import geiler.addons.client.gui.SlotIdBadgeLayoutChecks;
import geiler.addons.client.entity.ClientEntitySnapshotChecks;
import geiler.addons.client.entity.NameplatesChecks;
import geiler.addons.client.farming.GardenPlotChecks;
import geiler.addons.client.render.BlockOutlineChecks;
import geiler.addons.client.enchanting.ExperimentCell;
import geiler.addons.client.enchanting.ExperimentBoardGeometry;
import geiler.addons.client.enchanting.AutoExperimentAutomation;
import geiler.addons.client.enchanting.AutoExperimentDelayRange;
import geiler.addons.client.enchanting.ChronomatronEvent;
import geiler.addons.client.enchanting.ChronomatronModel;
import geiler.addons.client.enchanting.ExperimentClickGate;
import geiler.addons.client.enchanting.ExperimentMilestone;
import geiler.addons.client.enchanting.ExperimentPhase;
import geiler.addons.client.enchanting.ExperimentSnapshot;
import geiler.addons.client.enchanting.ExperimentSolverEngine;
import geiler.addons.client.enchanting.ExperimentTier;
import geiler.addons.client.enchanting.ExperimentType;
import geiler.addons.client.enchanting.SuperpairsBoard;
import geiler.addons.client.location.Island;
import geiler.addons.client.farming.PestChecks;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.ChoiceSetting;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.DebugState;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.ModuleKeybind;
import geiler.addons.client.module.ModuleKeybindManager;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.module.TextSetting;
import geiler.addons.client.macro.MacroChecks;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.client.input.KeyEvent;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.List;

/** Small no-server checks for the state boundaries that are easy to regress. */
public final class OfflineChecks {
	private OfflineChecks() {
	}

	public static void main(String[] args) {
		checkIslandModes();
		checkDungeonStatsCommand();
		checkMacroKeyCapturePolicy();
		DungeonStatsChecks.run();
		PestChecks.run();
		GardenPlotChecks.run();
		ClientEntitySnapshotChecks.run();
		NameplatesChecks.run();
		HideyhoChecks.run();
		PartyFinderLifecycleChecks.run();
		checkMobHighlightFailClosed();
		checkPersonalBestBoundaries();
		checkAutoKickLabels();
		checkExperimentPreviewConfiguration();
		checkSuperpairsCacheGate();
		checkEveryStatsToggleCombination();
		checkDungeonStatsHovers();
		checkGlobalDebugGate();
		checkModuleKeybinds();
		checkSettingInputBounds();
		checkChoiceDirection();
		checkChronomatronModel();
		checkExperimentSolverEngine();
		checkAutoExperiments();
		checkExperimentStateEdges();
		MacroChecks.run();
		FolderTreeChecks.run();
		BlockEspChecks.run();
		BlockOutlineChecks.run();
		InventoryButtonChecks.run();
		SlotIdBadgeLayoutChecks.run();
		ClickGuiMotionChecks.run();
	}

	private static void checkModuleKeybinds() {
		ModuleKeybind bind = ModuleKeybind.from(new KeyEvent(InputConstants.KEY_G, 0, InputConstants.MOD_CONTROL));
		assertTrue(bind.isBound(), "a key event creates a bound module keybind");
		assertTrue(bind.matches(new KeyEvent(InputConstants.KEY_G, 0, InputConstants.MOD_CONTROL)),
			"the matching modifier combination activates the bind");
		assertFalse(bind.matches(new KeyEvent(InputConstants.KEY_G, 0, 0)),
			"a missing modifier does not activate a combination bind");
		assertFalse(new ModuleKeybind(InputConstants.getKey(new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0)), 0).isBound(),
			"Escape is reserved for clearing a bind");
		assertTrue(ModuleKeybind.NONE.displayName().equals("None"), "unbound modules display None");
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
		pairEngine.observe(new ExperimentSnapshot(pairTitle, "Next button is instantly rewarded!", hidden));
		assertEquals(1, pairEngine.view().superpairs().resolvedPairs(),
			"an instant-reward status update cannot undo a collected Superpairs pair");
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

	private static void checkDungeonStatsCommand() {
		DungeonStatsCommand.ParseResult quoted = DungeonStatsCommand.parse("ga dstats \"Notch\"");
		assertTrue(quoted.valid(), "dstats accepts a quoted player name");
		assertEquals("Notch", quoted.name(), "dstats preserves the requested player name");
		DungeonStatsCommand.ParseResult unquoted = DungeonStatsCommand.parse("/ga dstats Notch");
		assertTrue(unquoted.valid(), "dstats accepts an unquoted player name");
		assertEquals("Notch", unquoted.name(), "dstats strips the command slash");
		assertTrue(DungeonStatsCommand.parse("ga dstats").recognized(), "dstats recognizes a missing name for usage feedback");
		assertFalse(DungeonStatsCommand.parse("ga other").recognized(), "unrelated ga commands remain unhandled");
		assertFalse(DungeonStatsCommand.parse("ga dstats Notch extra").valid(), "dstats rejects multiple names");
		assertFalse(DungeonStatsCommand.parse("ga dstats \"bad name\"").valid(), "dstats rejects invalid player-name characters");
	}

	private static void checkMacroKeyCapturePolicy() {
		assertTrue(ModuleKeybindManager.canBindMacroStepKey(InputConstants.KEY_LSHIFT),
			"macro Key nodes accept left Shift as a standalone key");
		assertTrue(ModuleKeybindManager.canBindMacroStepKey(InputConstants.KEY_RSHIFT),
			"macro Key nodes accept right Shift as a standalone key");
		assertTrue(ModuleKeybindManager.canBindMacroStepKey(InputConstants.KEY_LCONTROL),
			"macro Key nodes accept Control as a standalone key");
		assertTrue(ModuleKeybindManager.canBindMacroStepKey(InputConstants.KEY_LALT),
			"macro Key nodes accept Alt as a standalone key");
		assertTrue(ModuleKeybindManager.canBindMacroStepKey(InputConstants.KEY_LSUPER),
			"macro Key nodes accept Super as a standalone key");
		assertFalse(ModuleKeybindManager.canBindMacroStepKey(InputConstants.KEY_ESCAPE),
			"Escape remains reserved to clear a key capture");
	}

	private static void checkMobHighlightFailClosed() {
		assertFalse(MobHighlight.isKnownSkyBlockIsland(Island.NONE),
			"unknown location keeps Mob Highlight disabled");
		assertFalse(MobHighlight.isKnownSkyBlockIsland(Island.OTHER),
			"non-SkyBlock location keeps Mob Highlight disabled");
	}

	private static void checkPersonalBestBoundaries() {
		assertEquals(402, AutoKickRules.parsePersonalBestLimitSeconds("6:42"), "PB m:ss input parses to seconds");
		assertEquals(362, AutoKickRules.parsePersonalBestLimitSeconds("6:2"), "PB accepts an unpadded seconds part");
		assertEquals(402, AutoKickRules.parsePersonalBestLimitSeconds("402"), "legacy PB seconds remain supported");
		assertEquals(0, AutoKickRules.parsePersonalBestLimitSeconds("0"), "zero PB limit disables the check");
		assertEquals(0, AutoKickRules.parsePersonalBestLimitSeconds("6:60"), "invalid PB seconds are rejected");
		assertEquals(0, AutoKickRules.parsePersonalBestLimitSeconds("6:42:1"), "multiple PB separators are rejected");
		assertTrue(AutoKickRules.personalBestPasses(0, 0), "zero limit disables PB check");
		assertTrue(AutoKickRules.personalBestPasses(60, 60), "PB equal to limit passes");
		assertTrue(AutoKickRules.personalBestPasses(59, 60), "faster PB passes");
		assertFalse(AutoKickRules.personalBestPasses(61, 60), "slower PB fails");
		assertFalse(AutoKickRules.personalBestPasses(0, 60), "missing PB fails a configured check");
		ExperimentMilestone chrono = ExperimentMilestone.forExperiment(
			ExperimentType.CHRONOMATRON, ExperimentTier.HIGH, 0).orElseThrow();
		assertFalse(chrono.reached(chrono.displayedSequenceLength() - 1, 0),
			"Chronomatron does not reach max clicks before the final sequence is known");
		assertFalse(chrono.reached(chrono.displayedSequenceLength(), 8),
			"Chronomatron keeps round nine playable before round ten starts");
		assertTrue(chrono.reached(chrono.displayedSequenceLength(), 8, ExperimentPhase.ROUND_COMPLETE),
			"Chronomatron reaches its max-click milestone as soon as target round nine is completed");
		assertTrue(chrono.reached(chrono.displayedSequenceLength(), 9),
			"Chronomatron keeps the next-round counter as a compatible milestone signal");
		assertTrue(chrono.reached(chrono.displayedSequenceLength() + 1, -1),
			"Chronomatron fallback waits for a sequence beyond the target");
	}

	private static void checkAutoKickLabels() {
		List<String> expectedBooleanLabels = List.of("Auto Kick", "Ask Before", "Dupe", "Terminator", "Hyperion", "GDrag");
		List<String> expectedTextLabels = List.of("Min Cata Level", "Min Class Level", "Min Class Avg", "Min Secrets",
			"Min Secret Avg", "Min MP", "PB limit (m:ss or sec; 0 off)", "Min Bank");
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

	private static void checkExperimentPreviewConfiguration() {
		ExperimentSolverModule module = ExperimentSolverModule.INSTANCE;
		NumberSetting chronomatron = module.chronomatronFutureClicks();
		NumberSetting ultrasequencer = module.ultrasequencerFutureClicks();
		float oldChronomatron = chronomatron.value();
		float oldUltrasequencer = ultrasequencer.value();
		ColorSetting next = colorSetting(module, "Next Color");
		ColorSetting nextNext = colorSetting(module, "Next Next Color");
		ColorSetting nextNextNext = colorSetting(module, "Next Next Next Color");
		try {
			assertEquals(1, chronomatron.intValue(), "Chronomatron preview default");
			assertEquals(2, ultrasequencer.intValue(), "Ultrasequencer preview default");
			assertEquals(2, module.previewSteps(ExperimentType.CHRONOMATRON),
				"Chronomatron preview includes exactly one future click by default");
			assertEquals(3, module.previewSteps(ExperimentType.ULTRASEQUENCER),
				"Ultrasequencer preview includes exactly two future clicks by default");

			chronomatron.setValue(-1);
			assertEquals(0, chronomatron.intValue(), "Chronomatron preview clamps its lower bound");
			chronomatron.setValue(4);
			assertEquals(3, chronomatron.intValue(), "Chronomatron preview clamps its upper bound");
			ultrasequencer.setValue(-1);
			assertEquals(0, ultrasequencer.intValue(), "Ultrasequencer preview clamps its lower bound");
			chronomatron.setValue(0);
			assertEquals(1, module.previewSteps(ExperimentType.ULTRASEQUENCER),
				"zero future clicks leaves only the current button");
			assertFalse(module.isSettingVisible(next), "Next color hides when both future previews are zero");
			assertFalse(module.isSettingVisible(nextNext), "Next Next color hides when both future previews are zero");
			assertFalse(module.isSettingVisible(nextNextNext),
				"Next Next Next color hides when both future previews are zero");

			chronomatron.setValue(3);
			assertEquals(4, module.previewSteps(ExperimentType.CHRONOMATRON),
				"three future clicks expose current plus three buttons");
			assertTrue(module.isSettingVisible(next), "Next color shows for a future preview");
			assertTrue(module.isSettingVisible(nextNext), "Next Next color shows for two future previews");
			assertTrue(module.isSettingVisible(nextNextNext),
				"Next Next Next color shows for three future previews");
		} finally {
			chronomatron.setValue(oldChronomatron);
			ultrasequencer.setValue(oldUltrasequencer);
		}
	}

	private static void checkSuperpairsCacheGate() {
		ExperimentSolverModule.SuperpairsCacheGate gate = new ExperimentSolverModule.SuperpairsCacheGate();
		assertTrue(gate.consumeRenderDirty(), "a new Superpairs session builds its initial render cache");
		assertFalse(gate.consumeRenderDirty(), "an unchanged Superpairs frame does not rebuild its render cache");
		assertTrue(gate.consumeObservationDirty(), "a new Superpairs session observes its initial board");
		assertFalse(gate.consumeObservationDirty(), "an unchanged Superpairs frame skips board observation");
		gate.invalidateObservation();
		assertFalse(gate.consumeRenderDirty(), "a status-only change does not rebuild board render metadata");
		assertTrue(gate.consumeObservationDirty(), "a status-only change observes the board once");
		gate.invalidateRender();
		assertTrue(gate.consumeRenderDirty(), "a board change rebuilds render metadata");
		assertTrue(gate.consumeObservationDirty(), "a board change observes the board once");
	}

	private static ColorSetting colorSetting(Module module, String name) {
		for (ColorSetting setting : module.colorSettings()) {
			if (setting.name().equals(name)) return setting;
		}
		throw new AssertionError(module.name() + " is missing color setting " + name);
	}

	private static void checkEveryStatsToggleCombination() {
		String[] markers = {"Cata 42", "Mage 45", "CA 46.25", "MP 720", "SA 11.14", "PB 6:42",
			"Term ✓", "Hype ✓", "GDrag ✓", "Bank 125,000,000"};
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
		PartyFinderStatsModule.DisplayOptions statusOptions = new PartyFinderStatsModule.DisplayOptions(true,
			true, true, true, true, true, true, true, true, true, true);
		String manualStatus = PartyFinderStatsModule.CardFormatter.format(null,
			PartyFinderStatsModule.StatsView.preview(), statusOptions, 160, List.of(), true, true)
			.getFirst().getString();
		assertTrue(manualStatus.contains("[✦ DStats]"), "manual stats use their own compact heading");
		assertTrue(manualStatus.contains("Island detection API is off"), "stats name the disabled island API");
		assertTrue(manualStatus.contains("Normal PBs") && manualStatus.contains("Master PBs"),
			"manual stats show separate Normal and Master PB summaries");
		assertTrue(PartyFinderStatsModule.StatsView.preview().normalPbs().contains("F7")
			&& !PartyFinderStatsModule.StatsView.preview().normalPbs().contains("M1"),
			"Normal PB summary includes only normal floors");
		assertTrue(PartyFinderStatsModule.StatsView.preview().masterPbs().contains("M7")
			&& !PartyFinderStatsModule.StatsView.preview().masterPbs().contains("F7"),
			"Master PB summary includes only Master floors");
		assertFalse(manualStatus.contains("joined"), "manual stats do not use the Party Finder joined heading");
		assertEquals("Dungeon stats API unavailable: Player (network timeout)",
			PartyFinderStatsModule.apiUnavailableStatus("Player", "network timeout"),
			"profile lookup failure has a named API status");
		assertEquals("Dungeon stats API unavailable: Player (unavailable)",
			PartyFinderStatsModule.apiUnavailableStatus("Player", ""),
			"empty profile lookup errors remain visibly unavailable");
	}

	private static void checkDungeonStatsHovers() {
		String[] expectedDetails = {
			"Total Catacombs XP", "XP in current level", "Tank 38", "Class average",
			"Magical Power", "Total secrets", "Runs:", "F7 personal best",
			"Normal mode personal bests", "Master mode personal bests",
			"Shortbow: Instantly shoots!", "Wither Impact", "LEGENDARY",
			"Item ID: TERMINATOR", "PET_ITEM_TIER_BOOST", "Bank balance"
		};
		PartyFinderStatsModule.StatsView preview = PartyFinderStatsModule.StatsView.preview();
		for (boolean compact : new boolean[] {true, false}) {
			PartyFinderStatsModule.DisplayOptions options = new PartyFinderStatsModule.DisplayOptions(compact,
				true, true, true, true, true, true, true, true, true, true);
			List<Component> partyFinder = PartyFinderStatsModule.CardFormatter.format(null, preview,
				options, 160, List.of());
			String partyHovers = statsHoverText(partyFinder);
			for (String expected : expectedDetails) {
				assertTrue(partyHovers.contains(expected),
					(compact ? "compact" : "full") + " Party Finder card has hover detail: " + expected);
			}
			List<Component> manualLookup = PartyFinderStatsModule.CardFormatter.format(null, preview,
				options, 160, List.of(), true, false);
			String manualHovers = statsHoverText(manualLookup);
			for (String expected : expectedDetails) {
				if (expected.equals("F7 personal best")) continue;
				assertTrue(manualHovers.contains(expected),
					(compact ? "compact" : "full") + " /ga dstats card has hover detail: " + expected);
			}
			assertTrue(manualHovers.contains("F7: 6:42"),
				(compact ? "compact" : "full") + " /ga dstats card exposes the queued floor in its PB hover");
		}
		PartyFinderStatsModule.DisplayOptions cataOnly = new PartyFinderStatsModule.DisplayOptions(true,
			true, false, false, false, false, false, false, false, false, false);
		String overflowHover = statsHoverText(PartyFinderStatsModule.CardFormatter.format(null,
			preview.withCatacombsExperience(569_821_985L), cataOnly, 160, List.of()));
		assertTrue(overflowHover.contains("Overflow XP: 12,345"),
			"Cata hover shows XP beyond level 50");
		PartyFinderStatsModule.StatsView unavailable = new PartyFinderStatsModule.StatsView(
			"Unavailable", null, null, 0, 0, 0, 0, 0, 0, 0, 0,
			false, false, false, false, false, false, false, false,
			"-", "", "", "", false, 0, false, false, false, false, false, false,
			List.of(), List.of());
		PartyFinderStatsModule.DisplayOptions allFields = new PartyFinderStatsModule.DisplayOptions(true,
			true, true, true, true, true, true, true, true, true, true);
		String unavailableHovers = statsHoverText(PartyFinderStatsModule.CardFormatter.format(null,
			unavailable, allFields, 160, List.of()));
		assertTrue(unavailableHovers.contains("Catacombs XP data unavailable"),
			"missing Catacombs XP has a clear hover state");
		assertTrue(unavailableHovers.contains("Inventory API data unavailable"),
			"missing weapon inventory has a clear hover state");
		assertTrue(unavailableHovers.contains("Pet API data unavailable"),
			"missing pet data has a clear hover state");
		assertTrue(unavailableHovers.contains("Magical Power data unavailable in profile"),
			"missing magical power has a clear hover state");
		assertTrue(unavailableHovers.contains("Total secrets: unavailable in profile")
			&& unavailableHovers.contains("Runs: unavailable in profile"),
			"missing secret and run values are identified in the average hover");
		assertTrue(unavailableHovers.contains("Bank API data unavailable"),
			"missing bank data has a clear hover state");
		assertTrue(unavailableHovers.contains("Selected dungeon class unavailable in profile"),
			"missing selected class has a clear hover state");
		assertTrue(unavailableHovers.contains("Personal best data unavailable in profile"),
			"missing personal best data has a clear hover state");
		assertTrue(unavailableHovers.contains("Normal mode personal best data unavailable in profile")
			&& unavailableHovers.contains("Master mode personal best data unavailable in profile"),
			"normal and master personal best sections report missing profile data separately");
	}

	private static String statsHoverText(List<Component> components) {
		StringBuilder output = new StringBuilder();
		for (Component component : components) collectStatsHoverText(component, output);
		return output.toString();
	}

	private static void collectStatsHoverText(Component component, StringBuilder output) {
		if (component.getStyle().getHoverEvent() instanceof HoverEvent.ShowText showText) {
			output.append(showText.value().getString()).append('\n');
		}
		for (Component child : component.getSiblings()) collectStatsHoverText(child, output);
	}

	private static void checkGlobalDebugGate() {
		assertFalse(DebugModule.INSTANCE.isEnabled(), "Dev Debug module defaults off");
		assertEquals("DEV", DebugModule.INSTANCE.category().name(), "Dev Debug module category");
		assertFalse(SlotIdsModule.INSTANCE.isEnabled(), "Slot IDs module defaults off");
		assertEquals("DEV", SlotIdsModule.INSTANCE.category().name(), "Slot IDs module category");
		assertEquals("slot_ids", SlotIdsModule.INSTANCE.id(), "Slot IDs has a stable HUD position key");
		assertFalse(SlotIdsModule.INSTANCE.renderOnHud(),
			"Slot IDs has a movable editor preview but keeps live badges attached to inventory slots");
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

	private static void checkSettingInputBounds() {
		NumberSetting number = new NumberSetting("test", 0, 10, 5, true);
		number.setValue(Float.NaN);
		assertEquals(5, number.intValue(), "NaN does not poison a number setting");
		number.setFraction(Float.POSITIVE_INFINITY);
		assertEquals(5, number.intValue(), "infinite slider input is ignored");
		assertFalse(number.prefersTextInput(), "small ranges remain sliders");
		NumberSetting large = new NumberSetting("large", 0, 200, 100, true);
		assertTrue(large.prefersTextInput(), "large ranges use direct numeric input");
		TextSetting text = new TextSetting("test", "value", 8);
		text.setValue(null);
		assertEquals("value", text.value(), "null text input is ignored");
		ColorSetting color = new ColorSetting("test", 1, 2, 3, 4);
		assertFalse(color.setHex(null), "null color input is rejected");
	}

	private static void checkChoiceDirection() {
		ChoiceSetting choice = new ChoiceSetting("test", "A", "A", "B", "C");
		choice.selectNext();
		assertEquals("B", choice.value(), "choice next selects the following value");
		choice.selectPrevious();
		assertEquals("A", choice.value(), "choice previous selects the preceding value");
		choice.selectPrevious();
		assertEquals("C", choice.value(), "choice previous wraps at the beginning");
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
			new ExperimentSolverEngine.Configuration(0));
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
			new ExperimentSolverEngine.Configuration(0));
		predictionEngine.observe(new ExperimentSnapshot(chronoTitle, "Remember the pattern!", List.of(),
			-1, 0, -1, 31), ChronomatronEvent.board(17, "red", true));
		predictionEngine.observe(new ExperimentSnapshot(chronoTitle, "Timer: 4.0s", List.of(),
			-1, 0, -1, 32), ChronomatronEvent.status("Timer: 4.0s"));
		predictionEngine.observe(new ExperimentSnapshot(chronoTitle, "Timer: 4.0s",
			List.of(ExperimentCell.token(17, "red", false)), -1, 0, -1, 32));
		var pendingPrediction = predictionEngine.onClick(17);
		assertTrue(pendingPrediction.expected(), "sequence click is accepted for vanilla dispatch");
		assertFalse(pendingPrediction.predicted(), "sequence clicks never use local 0 Ping prediction");
		predictionEngine.observe(new ExperimentSnapshot(chronoTitle, "Timer: 3.0s",
			List.of(ExperimentCell.token(17, "red", false)), -1, 0, 0, 33));
		assertEquals(0, predictionEngine.view().visualIndex(),
			"a later snapshot leaves the cursor unchanged until confirmation");
		assertTrue(predictionEngine.confirmClick(17).visualStateChanged(),
			"vanilla dispatch confirmation advances the sequence cursor");

		var normalEngine = new ExperimentSolverEngine(
			new ExperimentSolverEngine.Configuration(0));
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

		var ultra = new ExperimentSolverEngine(new ExperimentSolverEngine.Configuration(3));
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

		var ultraPending = new ExperimentSolverEngine(new ExperimentSolverEngine.Configuration(0));
		String ultraTitle = "Ultrasequencer (High)";
		ultraPending.observe(new ExperimentSnapshot(ultraTitle, "Remember the pattern!",
			List.of(ExperimentCell.number(30, 1), ExperimentCell.number(31, 2)), "black", 50));
		ultraPending.observe(new ExperimentSnapshot(ultraTitle, "Timer: 1.0s",
			List.of(ExperimentCell.token(30, "white", false)), "white", 51));
		assertFalse(ultraPending.onClick(30).predicted(),
			"Ultrasequencer does not advance through local 0 Ping prediction");
		ultraPending.observe(new ExperimentSnapshot(ultraTitle, "Timer: 0.5s",
			List.of(ExperimentCell.token(30, "white", false)), "white", 52));
		assertEquals(0, ultraPending.view().visualIndex(),
			"Ultrasequencer waits for dispatch confirmation");
		assertTrue(ultraPending.confirmClick(30).visualStateChanged(),
			"Ultrasequencer advances after dispatch confirmation");

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
		SuperpairsBoard instantReward = new SuperpairsBoard();
		instantReward.observe(List.of(new ExperimentCell(10, "BOOK", -1, true, false, false),
			new ExperimentCell(11, "BOOK", -1, true, false, false)));
		instantReward.observe(List.of(new ExperimentCell(10, null, -1, false, false, false),
			new ExperimentCell(11, null, -1, false, false, false)));
		assertTrue(instantReward.click(10) && instantReward.click(11),
			"known Superpairs cards remain selectable after the board hides them");
		instantReward.observe(List.of(new ExperimentCell(10, null, -1, false, false, false),
			new ExperimentCell(11, null, -1, false, false, false)));
		assertEquals(1, instantReward.view().resolvedPairs(),
			"an instant-reward pair completes without requiring an intermediate visible frame");
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
		assertFalse(rapidClicks.click(11), "Superpairs blocks a second click while the first reveal is pending");
		assertFalse(rapidClicks.click(12), "Superpairs blocks later clicks while the first reveal is pending");
		rapidClicks.observe(List.of(new ExperimentCell(10, "BOOK", -1, true, false, false),
			new ExperimentCell(11, null, -1, false, false, false),
			new ExperimentCell(12, null, -1, false, false, false)));
		assertTrue(rapidClicks.click(11), "Superpairs accepts the next click after the reveal arrives");

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

	private static void checkAutoExperiments() {
		AutoExperimentsModule module = AutoExperimentsModule.INSTANCE;
		assertFalse(module.isEnabled(), "Auto Experiments defaults off");
		assertTrue(module.chronomatron().value(), "Chronomatron automation defaults on per game");
		assertTrue(module.ultrasequencer().value(), "Ultrasequencer automation defaults on per game");
		assertEquals(360, module.firstClickDelay().intValue(), "first-click delay follows the saved AutoTerms profile");
		assertEquals(AutoExperimentDelayRange.DEFAULT_MINIMUM_MILLIS,
			module.minimumClickDelay().intValue(), "fresh minimum click delay uses the selected range default");
		assertEquals(AutoExperimentDelayRange.DEFAULT_MAXIMUM_MILLIS,
			module.maximumClickDelay().intValue(), "fresh maximum click delay uses the selected range default");
		assertFalse(module.supports(ExperimentType.SUPERPAIRS), "Auto Experiments excludes Superpairs");

		AutoExperimentDelayRange standardRange = new AutoExperimentDelayRange(190, 260);
		long[] requestedBound = {-1L};
		assertEquals(190, standardRange.sampleMillis(bound -> {
			requestedBound[0] = bound;
			return 0L;
		}), "random delay can deterministically select the inclusive minimum");
		assertEquals(71L, requestedBound[0], "inclusive sampling requests one offset per millisecond value");
		assertEquals(260, standardRange.sampleMillis(bound -> bound - 1L),
			"random delay can deterministically select the inclusive maximum");
		AutoExperimentDelayRange reversedRange = new AutoExperimentDelayRange(260, 190);
		assertEquals(190, reversedRange.minimumMillis(), "reversed endpoints normalize before sampling");
		assertEquals(260, reversedRange.maximumMillis(), "reversed endpoints retain the larger maximum");
		assertEquals(221, new AutoExperimentDelayRange(221, 221).sampleMillis(bound -> {
			assertEquals(1L, bound, "an equal endpoint range has exactly one choice");
			return 0L;
		}), "an equal delay range always samples its one value");
		assertEquals(new AutoExperimentDelayRange(190, 260),
			AutoExperimentDelayRange.fromLegacyDelay(190), "legacy delay migrates to itself plus 70 ms");
		assertEquals(new AutoExperimentDelayRange(1_970, 2_000),
			AutoExperimentDelayRange.fromLegacyDelay(1_970), "legacy maximum is capped at 2,000 ms");
		assertEquals(new AutoExperimentDelayRange(220, 260),
			AutoExperimentDelayRange.restore(190f, 220f, null, 190, 260),
			"saved new minimum wins while the missing maximum migrates from the legacy value");
		assertEquals(new AutoExperimentDelayRange(220, 240),
			AutoExperimentDelayRange.restore(190f, 220f, 240f, 190, 260),
			"saved new endpoints take precedence over the old single delay");
		assertEquals(new AutoExperimentDelayRange(200, 300),
			AutoExperimentDelayRange.restore(null, null, null, 200, 300),
			"a fresh config retains the module's default endpoints");
		assertSame(ExperimentType.CHRONOMATRON,
			AutoExperimentsModule.hintedSequenceType("Chronomatron (Unrecognized Tier)"),
			"malformed Chronomatron tier titles can be paused without becoming click-eligible");
		assertSame(ExperimentType.ULTRASEQUENCER,
			AutoExperimentsModule.hintedSequenceType("Ultrasequencer (Unrecognized Tier)"),
			"malformed Ultrasequencer tier titles can be paused without becoming click-eligible");
		assertSame(null, AutoExperimentsModule.hintedSequenceType("Superpairs (Unrecognized Tier)"),
			"unknown Superpairs titles remain outside Auto Experiments");
		assertTrue(ExperimentController.hasActiveOwner(ExperimentType.CHRONOMATRON,
			true, true, true, true), "Solver and Auto share one Chronomatron owner path when both are enabled");
		assertSame(ExperimentController.INSTANCE.engine(), ExperimentSolverModule.INSTANCE.engine(),
			"the Solver facade exposes the controller's single shared engine");
		assertTrue(ExperimentController.hasActiveOwner(ExperimentType.ULTRASEQUENCER,
			false, true, true, true), "Auto can own Ultrasequencer observation while Solver is off");
		assertFalse(ExperimentController.hasActiveOwner(ExperimentType.SUPERPAIRS,
			false, false, true, true), "Auto never takes ownership of Superpairs");

		ExperimentSolverEngine clickEngine = new ExperimentSolverEngine();
		String ultraTitle = "Ultrasequencer (High)";
		List<ExperimentCell> ultraCells = List.of(ExperimentCell.number(30, 1),
			ExperimentCell.number(31, 2));
		clickEngine.observe(new ExperimentSnapshot(ultraTitle, "Remember the pattern!",
			ultraCells, "black", 1));
		var clickView = clickEngine.observe(new ExperimentSnapshot(ultraTitle, "Timer: 1.0s",
			ultraCells, "white", 2));
		assertSame(ExperimentPhase.SOLVE, clickView.phase(), "Ultrasequencer test fixture enters solve phase");
		ExperimentClickGate clickGate = new ExperimentClickGate();
		int[] vanillaClicks = {0};
		assertFalse(clickGate.dispatchSequenceClick(clickEngine, 30, false,
			ignored -> vanillaClicks[0]++), "a replaced screen/menu context blocks a click");
		assertFalse(clickGate.dispatchSequenceClick(clickEngine, 31, true,
			ignored -> vanillaClicks[0]++), "a future or stale slot never dispatches");
		assertFalse(clickGate.dispatchSequenceClick(clickEngine, 1, 30, true,
			ignored -> vanillaClicks[0]++), "an expected slot paired with a stale index never dispatches");
		assertEquals(0, vanillaClicks[0], "rejected clicks never reach vanilla");
		assertEquals(0, clickEngine.view().visualIndex(), "rejected clicks do not advance the shared cursor");
		assertTrue(clickGate.dispatchSequenceClick(clickEngine, 30, true,
			ignored -> vanillaClicks[0]++), "the exact current slot dispatches through the common click gate");
		assertEquals(1, vanillaClicks[0], "one accepted action invokes vanilla exactly once");
		assertEquals(1, clickEngine.view().visualIndex(), "one accepted vanilla dispatch confirms one engine step");
		assertFalse(clickGate.dispatchSequenceClick(clickEngine, 30, true,
			ignored -> vanillaClicks[0]++), "a previously current slot becomes stale after confirmation");
		assertEquals(1, vanillaClicks[0], "a stale repeated request is not retried");
		assertFalse(clickGate.dispatchSequenceClick(new ExperimentSolverEngine(), 30, true,
			ignored -> vanillaClicks[0]++), "an unknown or idle engine never dispatches");

		ExperimentSolverEngine repeatedSlotEngine = repeatedChronomatronEngine();
		assertEquals(List.of(17), repeatedSlotEngine.view().sequence().get(0).slotIds(),
			"first repeated Chronomatron step maps to the shared slot");
		assertEquals(List.of(17), repeatedSlotEngine.view().sequence().get(1).slotIds(),
			"second repeated Chronomatron step maps to the same shared slot");

		long millis = 1_000_000L;
		long start = 5_000_000_000L;
		Object screen = new Object();
		Object menu = new Object();
		AutoExperimentAutomation automation = new AutoExperimentAutomation();
		AutoExperimentAutomation.Snapshot firstStep = autoSnapshot(screen, menu,
			ExperimentType.CHRONOMATRON, ExperimentTier.HIGH, ExperimentPhase.SOLVE,
			"Timer: 4.0s", 0, 2, 17, 8, false);
		assertSame(AutoExperimentAutomation.Action.WAIT,
			automation.tick(firstStep, start, 360 * millis, 190 * millis).action(),
			"automation arms a solve phase before its first-click delay");
		assertSame(AutoExperimentAutomation.Action.WAIT,
			automation.tick(firstStep, start + 359 * millis, 360 * millis, 190 * millis).action(),
			"first-click delay does not fire early");
		var firstClick = automation.tick(firstStep, start + 360 * millis, 360 * millis, 190 * millis);
		assertSame(AutoExperimentAutomation.Action.CLICK, firstClick.action(),
			"the first step becomes due exactly at the configured delay");
		assertEquals(0, firstClick.sequenceIndex(), "the pending click carries its sequence index");
		assertEquals(17, firstClick.slotId(), "automation requests the current expected slot");
		assertSame(AutoExperimentAutomation.Action.WAIT,
			automation.tick(firstStep, start + 360 * millis, 360 * millis, 190 * millis).action(),
			"a pending click cannot be emitted twice before its dispatch result");
		AutoExperimentAutomation.Snapshot secondStep = autoSnapshot(screen, menu,
			ExperimentType.CHRONOMATRON, ExperimentTier.HIGH, ExperimentPhase.SOLVE,
			"Correct!", 1, 2, 18, 8, false);
		automation.clickResult(true, secondStep, start + 360 * millis, 190 * millis);
		assertSame(AutoExperimentAutomation.Action.WAIT,
			automation.tick(secondStep, start + 549 * millis, 360 * millis, 190 * millis).action(),
			"between-click delay does not fire early, including during transient Correct feedback");
		var secondClick = automation.tick(secondStep, start + 550 * millis, 360 * millis, 190 * millis);
		assertSame(AutoExperimentAutomation.Action.CLICK, secondClick.action(),
			"the next step becomes due exactly at the between-click delay");
		assertEquals(18, secondClick.slotId(), "the next request follows the current shared solver step");

		AutoExperimentAutomation repeatedSlotAutomation = new AutoExperimentAutomation();
		AutoExperimentAutomation.Snapshot repeatedFirstStep = autoSnapshot(screen, menu,
			ExperimentType.CHRONOMATRON, ExperimentTier.HIGH, ExperimentPhase.SOLVE,
			"Timer: 4.0s", 0, 2, 17, 0, false);
		repeatedSlotAutomation.tick(repeatedFirstStep, start, 360 * millis);
		var repeatedFirstClick = repeatedSlotAutomation.tick(repeatedFirstStep,
			start + 360 * millis, 360 * millis);
		assertSame(AutoExperimentAutomation.Action.CLICK, repeatedFirstClick.action(),
			"the first click for two repeated slots is queued");
		assertSame(AutoExperimentAutomation.Action.WAIT,
			repeatedSlotAutomation.tick(repeatedFirstStep, start + 360 * millis, 360 * millis).action(),
			"the same sequence index cannot emit a duplicate while its click is pending");
		ExperimentClickGate repeatedSlotGate = new ExperimentClickGate();
		int[] repeatedVanillaClicks = {0};
		assertTrue(repeatedSlotGate.dispatchSequenceClick(repeatedSlotEngine,
			repeatedFirstClick.sequenceIndex(), repeatedFirstClick.slotId(), true,
			ignored -> repeatedVanillaClicks[0]++),
			"the first expected Chronomatron sequence index dispatches its slot");
		assertFalse(repeatedSlotGate.dispatchSequenceClick(repeatedSlotEngine,
			repeatedFirstClick.sequenceIndex(), repeatedFirstClick.slotId(), true,
			ignored -> repeatedVanillaClicks[0]++),
			"the same slot is rejected when requested again at its stale sequence index");
		AutoExperimentAutomation.Snapshot repeatedSecondStep = autoSnapshot(screen, menu,
			ExperimentType.CHRONOMATRON, ExperimentTier.HIGH, ExperimentPhase.SOLVE,
			"Correct!", 1, 2, 17, 0, false);
		repeatedSlotAutomation.clickResult(true, repeatedSecondStep,
			start + 360 * millis, 190 * millis);
		assertSame(AutoExperimentAutomation.Action.WAIT,
			repeatedSlotAutomation.tick(repeatedSecondStep, start + 549 * millis, 360 * millis).action(),
			"the same slot still observes the selected between-click delay");
		var repeatedSecondClick = repeatedSlotAutomation.tick(repeatedSecondStep,
			start + 550 * millis, 360 * millis);
		assertSame(AutoExperimentAutomation.Action.CLICK, repeatedSecondClick.action(),
			"the same slot is eligible after the sequence index advances");
		assertEquals(1, repeatedSecondClick.sequenceIndex(), "the second click carries the advanced sequence index");
		assertEquals(17, repeatedSecondClick.slotId(), "the second click can reuse the first slot id");
		assertTrue(repeatedSlotGate.dispatchSequenceClick(repeatedSlotEngine,
			repeatedSecondClick.sequenceIndex(), repeatedSecondClick.slotId(), true,
			ignored -> repeatedVanillaClicks[0]++),
			"the same Chronomatron slot dispatches again for the next sequence index");
		assertEquals(2, repeatedVanillaClicks[0], "repeated slot flow performs exactly two vanilla dispatches");
		assertFalse(repeatedSlotGate.dispatchSequenceClick(repeatedSlotEngine,
			repeatedSecondClick.sequenceIndex(), repeatedSecondClick.slotId(), true,
			ignored -> repeatedVanillaClicks[0]++),
			"the second sequence index also cannot be dispatched twice");
		AutoExperimentAutomation.Snapshot repeatedRoundComplete = autoSnapshot(screen, menu,
			ExperimentType.CHRONOMATRON, ExperimentTier.HIGH, ExperimentPhase.ROUND_COMPLETE,
			"Round Complete", 2, 2, -1, 0, false);
		repeatedSlotAutomation.clickResult(true, repeatedRoundComplete,
			start + 550 * millis, 190 * millis);

		AutoExperimentAutomation finalUltraClick = new AutoExperimentAutomation();
		AutoExperimentAutomation.Snapshot lastUltraStep = autoSnapshot(new Object(), new Object(),
			ExperimentType.ULTRASEQUENCER, ExperimentTier.HIGH, ExperimentPhase.SOLVE,
			"Timer: 0.0s", 1, 2, 31, 0, false);
		finalUltraClick.tick(lastUltraStep, start, 0L, 190 * millis);
		var lastUltraRequest = finalUltraClick.tick(lastUltraStep, start, 0L, 190 * millis);
		assertSame(AutoExperimentAutomation.Action.CLICK, lastUltraRequest.action(),
			"Ultrasequencer reaches its final remembered slot");
		finalUltraClick.clickResult(true, lastUltraStep, start, 190 * millis);
		assertSame(AutoExperimentAutomation.Action.WAIT,
			finalUltraClick.tick(lastUltraStep, start + 190 * millis, 0L, 190 * millis).action(),
			"the final Ultrasequencer slot is not resent while its pane transition is pending");
		AutoExperimentAutomation.Snapshot completedRound = autoSnapshot(screen, menu,
			ExperimentType.CHRONOMATRON, ExperimentTier.HIGH, ExperimentPhase.ROUND_COMPLETE,
			"Round Complete", 2, 2, -1, 8, false);
		automation.clickResult(true, completedRound, start + 550 * millis, 190 * millis);
		assertSame(AutoExperimentAutomation.Action.WAIT,
			automation.tick(completedRound, start + 3_549 * millis, 360 * millis, 190 * millis).action(),
			"stage watchdog allows the full three-second transition window");
		var timedOut = automation.tick(completedRound, start + 3_550 * millis,
			360 * millis, 190 * millis);
		assertSame(AutoExperimentAutomation.Action.PAUSED, timedOut.action(),
			"a stalled stage pauses instead of retrying the final click");
		assertTrue(timedOut.explanation().contains("no click was retried"),
			"watchdog explains that no click was retried");
		assertSame(AutoExperimentAutomation.Action.WAIT,
			automation.tick(completedRound, start + 3_600 * millis, 360 * millis, 190 * millis).action(),
			"a watchdog pause reports only once and stays paused");

		AutoExperimentAutomation transition = new AutoExperimentAutomation();
		long transitionStart = 20_000_000_000L;
		Object transitionScreen = new Object();
		Object transitionMenu = new Object();
		AutoExperimentAutomation.Snapshot oneStep = autoSnapshot(transitionScreen, transitionMenu,
			ExperimentType.ULTRASEQUENCER, ExperimentTier.HIGH, ExperimentPhase.SOLVE,
			"Timer: 2.0s", 0, 1, 30, 0, false);
		transition.tick(oneStep, transitionStart, 360 * millis, 190 * millis);
		var oneClick = transition.tick(oneStep, transitionStart + 360 * millis,
			360 * millis, 190 * millis);
		assertSame(AutoExperimentAutomation.Action.CLICK, oneClick.action(),
			"Ultrasequencer uses the same first-click timing");
		AutoExperimentAutomation.Snapshot memorize = autoSnapshot(transitionScreen, transitionMenu,
			ExperimentType.ULTRASEQUENCER, ExperimentTier.HIGH, ExperimentPhase.MEMORIZE,
			"Remember the pattern!", 1, 1, -1, 0, false);
		transition.clickResult(true, memorize, transitionStart + 360 * millis, 190 * millis);
		assertSame(AutoExperimentAutomation.Action.WAIT,
			transition.tick(memorize, transitionStart + 10_000 * millis, 360 * millis, 190 * millis).action(),
			"normal memorize phases do not trip the stage watchdog");
		AutoExperimentAutomation.Snapshot nextUltraStage = autoSnapshot(transitionScreen, transitionMenu,
			ExperimentType.ULTRASEQUENCER, ExperimentTier.HIGH, ExperimentPhase.SOLVE,
			"Timer: 2.0s", 0, 2, 31, 1, false);
		assertSame(AutoExperimentAutomation.Action.WAIT,
			transition.tick(nextUltraStage, transitionStart + 20_000 * millis,
				360 * millis, 190 * millis).action(),
			"each new solve phase receives a fresh first-click delay");
		assertSame(AutoExperimentAutomation.Action.CLICK,
			transition.tick(nextUltraStage, transitionStart + 20_360 * millis,
				360 * millis, 190 * millis).action(),
			"a valid next Ultrasequencer stage is replayed after that delay");

		AutoExperimentAutomation transitionedWhileModelWaits = new AutoExperimentAutomation();
		var roundCompleteStatus = autoSnapshot(new Object(), new Object(),
			ExperimentType.ULTRASEQUENCER, ExperimentTier.HIGH, ExperimentPhase.ROUND_COMPLETE,
			"Round Complete", 1, 1, -1, 0, false);
		transitionedWhileModelWaits.tick(roundCompleteStatus, transitionStart, 0L, 0L);
		var timerStatusWithOldModel = autoSnapshot(roundCompleteStatus.screenIdentity(),
			roundCompleteStatus.menuIdentity(), ExperimentType.ULTRASEQUENCER, ExperimentTier.HIGH,
			ExperimentPhase.ROUND_COMPLETE, "Timer: 1.0s", 1, 1, -1, 0, false);
		assertSame(AutoExperimentAutomation.Action.WAIT,
			transitionedWhileModelWaits.tick(timerStatusWithOldModel, transitionStart + 2_500 * millis,
				0L, 0L).action(),
			"a known timer proves the stage is moving even while the menu model lags");
		assertSame(AutoExperimentAutomation.Action.WAIT,
			transitionedWhileModelWaits.tick(timerStatusWithOldModel,
				transitionStart + 2_999 * millis, 0L, 0L).action(),
			"a solve timer without the next sequence gets the full watchdog window");
		assertSame(AutoExperimentAutomation.Action.PAUSED,
			transitionedWhileModelWaits.tick(timerStatusWithOldModel,
				transitionStart + 3_000 * millis, 0L, 0L).action(),
			"the watchdog pauses when the next solve sequence never becomes available");
		AutoExperimentAutomation normalWaitTransition = new AutoExperimentAutomation();
		normalWaitTransition.tick(roundCompleteStatus, transitionStart, 0L, 0L);
		AutoExperimentAutomation.Snapshot memorizeWhileModelWaits = autoSnapshot(
			roundCompleteStatus.screenIdentity(), roundCompleteStatus.menuIdentity(),
			ExperimentType.ULTRASEQUENCER, ExperimentTier.HIGH, ExperimentPhase.ROUND_COMPLETE,
			"Remember the pattern!", 1, 1, -1, 0, false);
		assertSame(AutoExperimentAutomation.Action.WAIT,
			normalWaitTransition.tick(memorizeWhileModelWaits, transitionStart + 2_500 * millis,
				0L, 0L).action(),
			"a known memorize status cancels the stage watchdog while the model catches up");
		assertSame(AutoExperimentAutomation.Action.WAIT,
			normalWaitTransition.tick(memorizeWhileModelWaits,
				transitionStart + 10_000 * millis, 0L, 0L).action(),
			"normal memorize/wait phases can take longer than the watchdog window");

		AutoExperimentAutomation manualTakeover = new AutoExperimentAutomation();
		manualTakeover.tick(firstStep, start, 360 * millis, 190 * millis);
		manualTakeover.tick(firstStep, start + 360 * millis, 360 * millis, 190 * millis);
		assertTrue(!manualTakeover.pauseForManualInput().isBlank(), "manual takeover explains the pause once");
		assertSame(AutoExperimentAutomation.Action.WAIT,
			manualTakeover.tick(firstStep, start + 500 * millis, 360 * millis, 190 * millis).action(),
			"manual input cancels a pending Auto click");
		assertTrue(manualTakeover.pauseForManualInput().isBlank(), "manual takeover notification is not repeated");
		manualTakeover.reset();
		manualTakeover.tick(firstStep, start + 1_000 * millis, 360 * millis, 190 * millis);
		assertSame(AutoExperimentAutomation.Action.CLICK,
			manualTakeover.tick(firstStep, start + 1_360 * millis, 360 * millis, 190 * millis).action(),
			"toggling off and on clears the pause and arms a fresh delay");
		AutoExperimentAutomation disabled = new AutoExperimentAutomation();
		disabled.tick(firstStep, start, 360 * millis, 190 * millis);
		AutoExperimentAutomation.Snapshot switchedOff = new AutoExperimentAutomation.Snapshot(false, true,
			true, screen, menu, ExperimentType.CHRONOMATRON, ExperimentTier.HIGH,
			ExperimentPhase.SOLVE, "Timer: 4.0s", true, 0, 2, 17, 8, false);
		assertSame(AutoExperimentAutomation.Action.WAIT,
			disabled.tick(switchedOff, start + 350 * millis, 360 * millis, 190 * millis).action(),
			"disabling Auto cancels the queued step");
		disabled.tick(firstStep, start + 500 * millis, 360 * millis, 190 * millis);
		assertSame(AutoExperimentAutomation.Action.WAIT,
			disabled.tick(firstStep, start + 859 * millis, 360 * millis, 190 * millis).action(),
			"re-enabling Auto starts a fresh delay instead of using stale queued work");
		assertSame(AutoExperimentAutomation.Action.CLICK,
			disabled.tick(firstStep, start + 860 * millis, 360 * millis, 190 * millis).action(),
			"re-enabled automation clicks only after its new first delay");
		AutoExperimentAutomation rejectedDispatch = new AutoExperimentAutomation();
		rejectedDispatch.tick(firstStep, start, 0L, 190 * millis);
		var rejectedClick = rejectedDispatch.tick(firstStep, start, 0L, 190 * millis);
		assertSame(AutoExperimentAutomation.Action.CLICK, rejectedClick.action(),
			"a zero-delay click still passes through the one-action queue");
		assertSame(AutoExperimentAutomation.Action.PAUSED,
			rejectedDispatch.clickResult(false, firstStep, start, 190 * millis).action(),
			"a rejected by-slot dispatch pauses without retrying");

		AutoExperimentAutomation replacement = new AutoExperimentAutomation();
		replacement.tick(firstStep, start, 360 * millis, 190 * millis);
		Object replacementScreen = new Object();
		AutoExperimentAutomation.Snapshot replacedContext = autoSnapshot(replacementScreen, new Object(),
			ExperimentType.CHRONOMATRON, ExperimentTier.HIGH, ExperimentPhase.SOLVE,
			"Timer: 4.0s", 0, 2, 17, 8, false);
		assertSame(AutoExperimentAutomation.Action.WAIT,
			replacement.tick(replacedContext, start + 350 * millis, 360 * millis, 190 * millis).action(),
			"screen or menu replacement cancels a stale scheduled slot");
		assertSame(AutoExperimentAutomation.Action.WAIT,
			replacement.tick(replacedContext, start + 709 * millis, 360 * millis, 190 * millis).action(),
			"replacement context receives its own full first delay");
		assertSame(AutoExperimentAutomation.Action.CLICK,
			replacement.tick(replacedContext, start + 710 * millis, 360 * millis, 190 * millis).action(),
			"only the expected slot from the current menu becomes eligible");
		AutoExperimentAutomation changedSlot = new AutoExperimentAutomation();
		changedSlot.tick(firstStep, start, 360 * millis);
		AutoExperimentAutomation.Snapshot unexpectedSlot = autoSnapshot(screen, menu,
			ExperimentType.CHRONOMATRON, ExperimentTier.HIGH, ExperimentPhase.SOLVE,
			"Timer: 4.0s", 0, 2, 18, 8, false);
		assertSame(AutoExperimentAutomation.Action.PAUSED,
			changedSlot.tick(unexpectedSlot, start + 360 * millis, 360 * millis).action(),
			"a changed expected slot during a delay pauses without dispatching or retrying");

		AutoExperimentAutomation unknownTier = new AutoExperimentAutomation();
		AutoExperimentAutomation.Snapshot unknown = autoSnapshot(new Object(), new Object(),
			ExperimentType.CHRONOMATRON, ExperimentTier.UNKNOWN, ExperimentPhase.SOLVE,
			"Timer: 4.0s", 0, 2, 17, 0, false);
		assertSame(AutoExperimentAutomation.Action.PAUSED,
			unknownTier.tick(unknown, start, 360 * millis, 190 * millis).action(),
			"an unknown tier pauses before any automatic click");
		assertSame(AutoExperimentAutomation.Action.WAIT,
			unknownTier.tick(unknown, start + 500 * millis, 360 * millis, 190 * millis).action(),
			"an unknown-tier pause is not repeatedly announced or retried");
		unknownTier.reset();
		AutoExperimentAutomation.Snapshot unknownState = autoSnapshot(new Object(), new Object(),
			ExperimentType.CHRONOMATRON, ExperimentTier.HIGH, ExperimentPhase.IDLE,
			"server is syncing", 0, 0, -1, 0, false);
		assertSame(AutoExperimentAutomation.Action.PAUSED,
			unknownTier.tick(unknownState, start, 360 * millis, 190 * millis).action(),
			"an unavailable sequence state pauses without clicking");
		unknownTier.reset();
		AutoExperimentAutomation.Snapshot unknownSolveStatus = autoSnapshot(new Object(), new Object(),
			ExperimentType.CHRONOMATRON, ExperimentTier.HIGH, ExperimentPhase.SOLVE,
			"server is syncing", 0, 2, 17, 0, false);
		assertSame(AutoExperimentAutomation.Action.PAUSED,
			unknownTier.tick(unknownSolveStatus, start, 360 * millis, 190 * millis).action(),
			"an unknown server state cannot authorize a click using stale solver context");

		AutoExperimentAutomation milestoneStop = new AutoExperimentAutomation();
		AutoExperimentAutomation.Snapshot milestone = autoSnapshot(new Object(), new Object(),
			ExperimentType.CHRONOMATRON, ExperimentTier.HIGH, ExperimentPhase.ROUND_COMPLETE,
			"Round Complete", 9, 9, -1, 8, true);
		assertSame(AutoExperimentAutomation.Action.STOPPED,
			milestoneStop.tick(milestone, start, 0L, 0L).action(),
			"a milestone reached without an accepted Auto click stops without closing a menu");
		AutoExperimentAutomation milestoneClose = new AutoExperimentAutomation();
		AutoExperimentAutomation.Snapshot lastAutoStep = autoSnapshot(screen, menu,
			ExperimentType.CHRONOMATRON, ExperimentTier.HIGH, ExperimentPhase.SOLVE,
			"Timer: 1.0s", 8, 9, 17, 8, false);
		milestoneClose.tick(lastAutoStep, start, 0L);
		var lastAutoClick = milestoneClose.tick(lastAutoStep, start, 0L);
		assertSame(AutoExperimentAutomation.Action.CLICK, lastAutoClick.action(),
			"the final requested milestone click enters the one-click queue");
		AutoExperimentAutomation.Snapshot reachedAfterAcceptedClick = autoSnapshot(screen, menu,
			ExperimentType.CHRONOMATRON, ExperimentTier.HIGH, ExperimentPhase.ROUND_COMPLETE,
			"Round Complete", 9, 9, -1, 8, true);
		assertSame(AutoExperimentAutomation.Action.CLOSE_MENU,
			milestoneClose.clickResult(true, reachedAfterAcceptedClick, start, 190 * millis).action(),
			"an accepted click that reaches the shared milestone requests normal menu closure");
		assertSame(AutoExperimentAutomation.Action.STOPPED,
			milestoneClose.tick(reachedAfterAcceptedClick, start + 1 * millis, 0L).action(),
			"milestone menu closure is emitted once even if the screen has not left yet");
		AutoExperimentAutomation beginnerCompletion = new AutoExperimentAutomation();
		AutoExperimentAutomation.Snapshot noBonusThreshold = autoSnapshot(new Object(), new Object(),
			ExperimentType.ULTRASEQUENCER, ExperimentTier.BEGINNER, ExperimentPhase.SOLVE,
			"Timer: 1.0s", 0, 1, 30, 0, false);
		assertSame(AutoExperimentAutomation.Action.WAIT,
			beginnerCompletion.tick(noBonusThreshold, start, 0L, 0L).action(),
			"a recognized tier without a bonus-click milestone remains solvable");
		assertSame(AutoExperimentAutomation.Action.CLICK,
			beginnerCompletion.tick(noBonusThreshold, start, 0L, 0L).action(),
			"a no-milestone tier continues while a valid step remains");
		AutoExperimentAutomation.Snapshot beginnerComplete = autoSnapshot(
			noBonusThreshold.screenIdentity(), noBonusThreshold.menuIdentity(),
			ExperimentType.ULTRASEQUENCER, ExperimentTier.BEGINNER, ExperimentPhase.COMPLETE,
			"Experiment complete", 1, 1, -1, 0, false);
		assertSame(AutoExperimentAutomation.Action.STOPPED,
			beginnerCompletion.tick(beginnerComplete, start + 1_000 * millis, 0L, 0L).action(),
			"a tier without a milestone stops on completion without requesting menu closure");
		AutoExperimentAutomation.Snapshot superpairs = autoSnapshot(new Object(), new Object(),
			ExperimentType.SUPERPAIRS, ExperimentTier.HIGH, ExperimentPhase.SOLVE,
			"Next button", 0, 1, 10, -1, false);
		assertSame(AutoExperimentAutomation.Action.WAIT,
			milestoneStop.tick(superpairs, start + 1_000 * millis, 0L, 0L).action(),
			"Auto Experiments ignores Superpairs completely");
	}

	private static AutoExperimentAutomation.Snapshot autoSnapshot(Object screen, Object menu,
		ExperimentType type, ExperimentTier tier, ExperimentPhase phase, String status,
		int currentIndex, int sequenceLength, int expectedSlot, int completedRounds,
		boolean milestoneReached) {
		return new AutoExperimentAutomation.Snapshot(true, true, true, screen, menu, type, tier,
			phase, status, sequenceLength > 0, currentIndex, sequenceLength, expectedSlot,
			completedRounds, milestoneReached);
	}

	private static ExperimentSolverEngine repeatedChronomatronEngine() {
		ExperimentSolverEngine engine = new ExperimentSolverEngine();
		String title = "Chronomatron (High)";
		observeRepeatedSlotChronomatron(engine, title, 1, ChronomatronEvent.board(17, "red", true));
		observeRepeatedSlotChronomatron(engine, title, 2, ChronomatronEvent.status("Timer: 4.0s"));
		assertTrue(engine.onClick(17).expected(), "the first one-step round can be completed to build a repeated trace");
		assertTrue(engine.confirmClick(17).visualStateChanged(), "the first one-step round advances locally");
		observeRepeatedSlotChronomatron(engine, title, 3, ChronomatronEvent.status("Remember the pattern!"));
		observeRepeatedSlotChronomatron(engine, title, 4, ChronomatronEvent.board(17, "red", false));
		observeRepeatedSlotChronomatron(engine, title, 5, ChronomatronEvent.board(17, "red", true));
		observeRepeatedSlotChronomatron(engine, title, 6, ChronomatronEvent.board(17, "red", false));
		observeRepeatedSlotChronomatron(engine, title, 7, ChronomatronEvent.board(17, "red", true));
		observeRepeatedSlotChronomatron(engine, title, 8, ChronomatronEvent.status("Timer: 4.0s"));
		assertSame(ExperimentPhase.SOLVE, engine.view().phase(),
			"repeated Chronomatron trace reaches the solve phase");
		assertEquals(2, engine.view().sequence().size(), "repeated Chronomatron trace has two steps");
		return engine;
	}

	private static void observeRepeatedSlotChronomatron(ExperimentSolverEngine engine, String title,
		long revision, ChronomatronEvent event) {
		String status = event.kind() == ChronomatronEvent.Kind.STATUS
			? event.status() : "Remember the pattern!";
		boolean highlighted = event.kind() == ChronomatronEvent.Kind.BOARD && event.highlighted();
		engine.observe(new ExperimentSnapshot(title, status,
			List.of(ExperimentCell.token(17, "red", highlighted)), null, revision,
			event.kind() == ChronomatronEvent.Kind.STATUS), event);
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
