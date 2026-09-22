package geiler.addons.client.macro;

import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import geiler.addons.client.config.MacroStepConfigCodec;
import geiler.addons.client.location.Island;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.ModuleKeybind;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.module.TextSetting;
import geiler.addons.client.module.impl.MacrosModule;

import java.util.List;
import java.util.Random;

/** Pure workflow checks that do not need a running client or server. */
public final class MacroChecks {
	private MacroChecks() {
	}

	public static void run() {
		MacroDefinition macro = new MacroDefinition(7);
		assertSame(MacroTriggerContext.ANY_NON_TEXT_SCREEN, macro.triggerContext(),
			"new macros are allowed in non-text contexts by default");
		MacroStep.Command delayed = new MacroStep.Command("/example");
		assertEquals(0, delayed.delayMin(), "new nodes have no hidden minimum delay");
		assertEquals(0, delayed.delayMax(), "new nodes have no hidden maximum delay");
		delayed.setDelay(250, 90_000);
		assertEquals(250, delayed.delayMin(), "node delay lower bound");
		assertEquals(60_000, delayed.delayMax(), "node delay upper bound");
		delayed.setDelay(-1, -1);
		assertEquals(0, delayed.delayMin(), "clearing a node delay is immediate");
		assertEquals(0, delayed.delayMax(), "clearing a node delay removes the range");

		MacroStep.Repeat repeat = new MacroStep.Repeat(true, 0);
		repeat.steps().add(new MacroStep.Wait(10, 20));
		macro.steps().add(new MacroStep.Command("/example"));
		macro.steps().add(repeat);
		assertTrue(repeat.forever(), "repeat supports an endless loop");
		assertEquals(1, repeat.count(), "finite repeat count clamps to one");
		assertEquals(2, macro.steps().size(), "workflow keeps ordered top-level steps");

		assertSame(MacroFlowRules.UntilDecision.EXIT, MacroFlowRules.repeatUntil(true, true),
			"Repeat Until checks its condition before entering the body");
		assertSame(MacroFlowRules.UntilDecision.RUN_BODY, MacroFlowRules.repeatUntil(false, true),
			"Repeat Until runs the body while its stop condition is false");
		assertSame(MacroFlowRules.UntilDecision.EMPTY_BODY, MacroFlowRules.repeatUntil(false, false),
			"an empty Repeat Until body aborts rather than spinning");
		assertTrue(MacroFlowRules.missingItemEndsUntil(true, true),
			"a missing Click Item can finish the final Repeat Until iteration");
		assertFalse(MacroFlowRules.missingItemEndsUntil(true, false),
			"a missing item does not skip the body while the stop condition is false");
		assertFalse(MacroFlowRules.contextEnded(true, true, false, true),
			"world-switch wait does not cancel just because the old player context ended");
		assertTrue(MacroFlowRules.contextEnded(false, true, true, false),
			"disabling the macro system cancels a running workflow");
		assertTrue(MacroFlowRules.contextEnded(true, false, false, true),
			"losing the level cancels even a workflow waiting for a world switch");
		assertTrue(MacroFlowRules.itemNameMatches("Confirm Entry", "confirm", true),
			"partial item matching ignores case after formatting normalization");
		assertFalse(MacroFlowRules.itemNameMatches("Confirm Entry", "Confirm", false),
			"exact item matching rejects a partial name");
		assertTrue(MacroFlowRules.itemNameMatchesAny("Confirm Entry", "Claim, Confirm", true),
			"comma-separated partial names are alternatives");
		assertTrue(MacroFlowRules.itemNameMatchesAny("Claim", " Confirm , Claim ", false),
			"comma-separated exact names are trimmed alternatives");
		assertFalse(MacroFlowRules.itemNameMatchesAny("Claim", "Confirm, Collect", true),
			"item matching requires at least one matching alternative");

		MacroDefinition movable = new MacroDefinition(14);
		MacroStep.IfElse moveBranch = new MacroStep.IfElse(new MacroCondition.Always(true));
		MacroStep.Command movableNode = new MacroStep.Command("/preserve");
		movableNode.setDelay(45, 90);
		MacroStep.Command siblingNode = new MacroStep.Command("/sibling");
		movable.steps().add(moveBranch);
		movable.steps().add(movableNode);
		movable.steps().add(siblingNode);
		assertTrue(MacroTreeRules.move(movable, movable.steps(), movableNode, moveBranch.thenSteps(), 0),
			"tree rules move a node into a child slot");
		assertSame(movableNode, moveBranch.thenSteps().getFirst(), "tree movement preserves the original node object");
		assertFalse(MacroTreeRules.canMove(movable, movable.steps(), moveBranch, moveBranch.thenSteps(), 0),
			"tree rules reject moving a branch into its own descendants");
		assertTrue(MacroTreeRules.move(movable, moveBranch.thenSteps(), movableNode, movable.steps(), movable.steps().size()),
			"tree rules move a child back to the root list");
		assertTrue(MacroTreeRules.move(movable, movable.steps(), movableNode, movable.steps(), 1),
			"tree rules reorder nodes within one sibling list");
		assertSame(movableNode, movable.steps().get(1), "same-list movement adjusts the insertion index after removal");
		assertEquals(45, movableNode.delayMin(), "tree movement preserves node delay settings");
		assertEquals(90, movableNode.delayMax(), "tree movement preserves node delay settings");
		MacroDefinition tooDeep = new MacroDefinition(15);
		List<MacroStep> deepTarget = tooDeep.steps();
		for (int i = 0; i <= MacroTreeRules.MAX_DEPTH; i++) {
			MacroStep.Repeat nestedRepeat = new MacroStep.Repeat(false, 1);
			deepTarget.add(nestedRepeat);
			deepTarget = nestedRepeat.steps();
		}
		assertFalse(MacroTreeRules.canInsert(tooDeep, new MacroStep.Command("/too-deep"), deepTarget),
			"tree rules reject an insertion beyond the codec nesting limit");
		assertTrue(MacroCondition.ItemScope.CONTAINER.includesPlayerSlot(false),
			"container scope includes non-player slots");
		assertFalse(MacroCondition.ItemScope.CONTAINER.includesPlayerSlot(true),
			"container scope excludes player inventory slots");
		assertFalse(MacroCondition.ItemScope.PLAYER_INVENTORY.includesPlayerSlot(false),
			"player inventory scope excludes container slots");
		assertTrue(MacroCondition.ItemScope.PLAYER_INVENTORY.includesPlayerSlot(true),
			"player inventory scope includes player slots");
		assertTrue(MacroCondition.ItemScope.CONTAINER_AND_PLAYER.includesPlayerSlot(false)
			&& MacroCondition.ItemScope.CONTAINER_AND_PLAYER.includesPlayerSlot(true),
			"combined scope includes both slot groups");
		MacroCondition.Item missingFromContainer = new MacroCondition.Item("Confirm", false, true,
			MacroCondition.ItemScope.CONTAINER);
		assertTrue(missingFromContainer.matches(false), "missing-item conditions pass when the selected area has no match");
		assertFalse(missingFromContainer.matches(true), "missing-item conditions fail when the selected area has a match");
		MacroCondition.Item presentInPlayer = new MacroCondition.Item("Confirm", true, true,
			MacroCondition.ItemScope.PLAYER_INVENTORY);
		assertTrue(presentInPlayer.matches(true), "present-item conditions pass when the selected area has a match");
		assertFalse(presentInPlayer.matches(false), "present-item conditions fail when the selected area has no match");
		Random delayRandom = new Random(17);
		for (int i = 0; i < 100; i++) {
			int delay = MacroFlowRules.randomDelay(30, 50, delayRandom);
			assertTrue(delay >= 30 && delay <= 50, "randomized node delay stays inside its inclusive range");
		}
		for (int i = 0; i < 100; i++) {
			int holdMillis = MacroFlowRules.randomDelay(125, 375, delayRandom);
			assertTrue(holdMillis >= 125 && holdMillis <= 375,
				"randomized hold duration stays inside its inclusive range");
		}
		MacroStep.Key holdRange = new MacroStep.Key("key.keyboard.left.shift", true, 125, 375);
		assertEquals(125, holdRange.holdMinMillis(), "key node retains the random hold minimum");
		assertEquals(375, holdRange.holdMaxMillis(), "key node retains the random hold maximum");
		assertFalse(InputConstants.getKey(holdRange.key()).equals(InputConstants.UNKNOWN),
			"the captured left Shift identifier resolves to a usable game key");
		MacroKeyHoldState syntheticHold = new MacroKeyHoldState();
		syntheticHold.begin(InputConstants.KEY_LSHIFT, 1_000);
		assertTrue(syntheticHold.isDown(InputConstants.KEY_LSHIFT, 999),
			"synthetic Shift remains down through vanilla input polling during its hold");
		assertFalse(syntheticHold.isDown(InputConstants.KEY_RSHIFT, 999),
			"left Shift holds do not report right Shift as pressed");
		assertTrue(syntheticHold.isDue(1_000), "synthetic held keys become due at their configured deadline");
		assertFalse(syntheticHold.isDown(InputConstants.KEY_LSHIFT, 1_000),
			"synthetic key state expires exactly at the hold deadline");
		syntheticHold.clear();
		assertFalse(syntheticHold.isDown(InputConstants.KEY_LSHIFT, 999),
			"cancellation clears synthetic key state immediately");
		for (int modifier : new int[] {InputConstants.KEY_LSHIFT, InputConstants.KEY_RSHIFT,
			InputConstants.KEY_LCONTROL, InputConstants.KEY_RCONTROL, InputConstants.KEY_LALT,
			InputConstants.KEY_RALT, InputConstants.KEY_LSUPER, InputConstants.KEY_RSUPER}) {
			syntheticHold.begin(modifier, 2_000);
			assertTrue(syntheticHold.isDown(modifier, 1_999),
				"synthetic modifier key remains visible while held: " + modifier);
			syntheticHold.clear();
		}

		MacroStep.WorldSwitch switchStep = new MacroStep.WorldSwitch(Island.GARDEN);
		assertSame(Island.GARDEN, switchStep.target(), "world switch keeps its selected destination");
		switchStep.setTarget(Island.NONE);
		assertSame(Island.HUB, switchStep.target(), "world switch fails safe for unknown destinations");
		assertEquals("world_switch", switchStep.type(), "world switch has a stable persisted type");

		macro.setIslands(List.of(Island.GARDEN));
		macro.setIslandRestricted(true);
		assertTrue(macro.allowsIsland(Island.GARDEN), "selected island passes the filter");
		assertFalse(macro.allowsIsland(Island.HUB), "unselected island is blocked");
		macro.setIslandRestricted(false);
		assertTrue(macro.allowsIsland(Island.HUB), "disabled island filter allows every island");
		macro.setIslands(List.of(Island.GARDEN, Island.OTHER, Island.NONE));
		assertEquals(1, macro.islands().size(), "non-selectable islands are ignored");
		macro.setName("Shared routine");
		macro.setTriggerContext(MacroTriggerContext.CONTAINER_ONLY);
		macro.steps().getFirst().setDelay(125, 350);
		String packageText = MacroTransfer.encode(List.of(macro));
		MacroTransfer.ImportResult imported = MacroTransfer.decode(packageText, 100);
		assertTrue(imported.success(), "macro package can be decoded");
		assertEquals(1, imported.macros().size(), "macro package keeps its selection count");
		MacroDefinition copy = imported.macros().getFirst();
		assertEquals(100, copy.id(), "import assigns a fresh macro id");
		assertEquals("Shared routine", copy.name(), "import keeps the macro name");
		assertSame(MacroTriggerContext.CONTAINER_ONLY, copy.triggerContext(), "import keeps trigger context");
		assertEquals(125, copy.steps().getFirst().delayMin(), "import keeps node delay minimum");
		assertEquals(350, copy.steps().getFirst().delayMax(), "import keeps node delay maximum");
		assertFalse(MacroTransfer.decode("not a macro package", 0).success(),
			"invalid clipboard data is rejected");
		assertEquals(0.0f, MacroTitleTiming.opacity(0, 200, 1_000, 300), "macro title begins transparent");
		assertEquals(0.5f, MacroTitleTiming.opacity(100, 200, 1_000, 300), "macro title fades in linearly");
		assertEquals(1.0f, MacroTitleTiming.opacity(700, 200, 1_000, 300), "macro title remains visible during hold");
		assertEquals(0.5f, MacroTitleTiming.opacity(1_350, 200, 1_000, 300), "macro title fades out linearly");
		assertEquals(0.0f, MacroTitleTiming.opacity(1_500, 200, 1_000, 300), "macro title ends transparent");

		MacroDefinition oldFormat = new MacroDefinition(21);
		oldFormat.steps().add(new MacroStep.Command("/legacy"));
		JsonObject legacyPackage = JsonParser.parseString(MacroTransfer.encode(List.of(oldFormat))).getAsJsonObject();
		legacyPackage.addProperty("version", 1);
		MacroTransfer.ImportResult importedV1 = MacroTransfer.decode(legacyPackage.toString(), 120);
		assertTrue(importedV1.success(), "version 1 macro transfer packages remain importable");
		assertEquals("/legacy", ((MacroStep.Command) importedV1.macros().getFirst().steps().getFirst()).command(),
			"version 1 import retains the legacy workflow content");

		MacroDefinition effects = new MacroDefinition(22);
		MacroStep.Title title = new MacroStep.Title();
		title.setText("Dungeon ready");
		title.setFont("minecraft:uniform");
		title.setScale(3.25f);
		title.setTextColor(0xFF33CCAA);
		title.setShowBackground(true);
		title.setBackgroundColor(0xFF182030);
		title.setBackgroundOpacity(180);
		title.setFadeInMillis(450);
		title.setHoldMillis(1_750);
		title.setFadeOutMillis(600);
		MacroStep.Sound sound = new MacroStep.Sound("minecraft:entity.experience_orb.pickup");
		effects.steps().add(title);
		effects.steps().add(sound);
		JsonObject versionThree = JsonParser.parseString(MacroTransfer.encode(List.of(effects))).getAsJsonObject();
		assertEquals(4, versionThree.get("version").getAsInt(), "new macro transfers use format version 4");
		MacroDefinition effectsCopy = MacroTransfer.decode(versionThree.toString(), 121).macros().getFirst();
		MacroStep.Title titleCopy = (MacroStep.Title) effectsCopy.steps().get(0);
		assertEquals(title.text(), titleCopy.text(), "v4 transfer preserves macro title text");
		assertEquals(title.font(), titleCopy.font(), "v4 transfer preserves title font");
		assertEquals(title.scale(), titleCopy.scale(), "v4 transfer preserves title size");
		assertEquals(title.fadeInMillis(), titleCopy.fadeInMillis(), "v4 transfer preserves title fade-in");
		assertEquals(title.holdMillis(), titleCopy.holdMillis(), "v4 transfer preserves title duration");
		assertEquals(title.fadeOutMillis(), titleCopy.fadeOutMillis(), "v4 transfer preserves title fade-out");
		assertEquals(title.backgroundOpacity(), titleCopy.backgroundOpacity(), "v4 transfer preserves title background opacity");
		assertEquals(title.textColor(), titleCopy.textColor(), "v4 transfer preserves title text color");
		assertEquals(title.showBackground(), titleCopy.showBackground(), "v4 transfer preserves optional title background");
		assertEquals(title.backgroundColor(), titleCopy.backgroundColor(), "v4 transfer preserves background color");
		assertEquals(sound.soundId(), ((MacroStep.Sound) effectsCopy.steps().get(1)).soundId(),
			"v4 transfer preserves registered sound event ids");
		var effectsConfig = MacroStepConfigCodec.encode(effects.steps());
		List<MacroStep> effectsConfigCopy = MacroStepConfigCodec.decode(JsonParser.parseString(effectsConfig.toString()).getAsJsonArray());
		assertEquals(title.text(), ((MacroStep.Title) effectsConfigCopy.get(0)).text(),
			"local config round-trip preserves title steps");
		assertEquals(sound.soundId(), ((MacroStep.Sound) effectsConfigCopy.get(1)).soundId(),
			"local config round-trip preserves sound steps");

		MacroDefinition nested = new MacroDefinition(9);
		MacroCondition stopWhenMissing = new MacroCondition.Item("Confirm, Claim", false, true, true);
		MacroStep.RepeatUntil repeatUntil = new MacroStep.RepeatUntil(stopWhenMissing);
		repeatUntil.setDelay(80, 120);
		MacroStep.ClickItem clickMatching = new MacroStep.ClickItem("Confirm, Claim", true, "container", 0, 0, false);
		clickMatching.setDelay(250, 400);
		repeatUntil.steps().add(clickMatching);
		repeatUntil.steps().add(new MacroStep.Wait(40, 60));
		MacroStep.IfElse nestedBranch = new MacroStep.IfElse(new MacroCondition.All(List.of(
			new MacroCondition.Screen("Auction", true, true),
			new MacroCondition.Not(new MacroCondition.World(false)))));
		nestedBranch.thenSteps().add(repeatUntil);
		nestedBranch.elseSteps().add(new MacroStep.Key("key.keyboard.left.shift", true, 125, 375));
		nested.steps().add(nestedBranch);
		MacroTransfer.ImportResult nestedImport = MacroTransfer.decode(MacroTransfer.encode(List.of(nested)), 101);
		assertTrue(nestedImport.success(), "clipboard preserves nested Repeat Until workflows");
		MacroStep.IfElse restoredBranch = (MacroStep.IfElse) nestedImport.macros().getFirst().steps().getFirst();
		MacroStep.RepeatUntil restoredUntil = (MacroStep.RepeatUntil) restoredBranch.thenSteps().getFirst();
		assertEquals(stopWhenMissing, restoredUntil.condition(), "clipboard preserves Repeat Until item condition");
		assertEquals(2, restoredUntil.steps().size(), "clipboard preserves Repeat Until children");
		assertEquals("Confirm, Claim", ((MacroStep.ClickItem) restoredUntil.steps().getFirst()).name(),
			"clipboard preserves comma-separated click alternatives");
		assertEquals(250, restoredUntil.steps().getFirst().delayMin(), "clipboard preserves child node delays");
		assertEquals(400, restoredUntil.steps().getFirst().delayMax(), "clipboard preserves child delay ranges");
		assertEquals(1, restoredBranch.elseSteps().size(), "clipboard preserves the other If/Else branch");
		MacroStep.Key restoredKey = (MacroStep.Key) restoredBranch.elseSteps().getFirst();
		assertEquals("key.keyboard.left.shift", restoredKey.key(), "clipboard preserves the captured modifier key name");
		assertEquals(125, restoredKey.holdMinMillis(), "clipboard preserves randomized hold minimum");
		assertEquals(375, restoredKey.holdMaxMillis(), "clipboard preserves randomized hold maximum");
		var configTree = MacroStepConfigCodec.encode(nested.steps());
		var configRoundTrip = MacroStepConfigCodec.decode(JsonParser.parseString(configTree.toString()).getAsJsonArray());
		MacroStep.IfElse configBranch = (MacroStep.IfElse) configRoundTrip.getFirst();
		MacroStep.RepeatUntil configUntil = (MacroStep.RepeatUntil) configBranch.thenSteps().getFirst();
		assertEquals(stopWhenMissing, configUntil.condition(), "config round trip preserves Repeat Until condition");
		assertEquals(2, configUntil.steps().size(), "config round trip preserves nested loop body");
		assertEquals(80, configUntil.delayMin(), "config round trip preserves loop delay minimum");
		assertEquals(120, configUntil.delayMax(), "config round trip preserves loop delay maximum");
		assertEquals(nestedBranch.condition(), configBranch.condition(), "config round trip preserves composed AND/NOT conditions");
		MacroStep.Key configKey = (MacroStep.Key) configBranch.elseSteps().getFirst();
		assertEquals(125, configKey.holdMinMillis(), "config round trip preserves randomized hold minimum");
		assertEquals(375, configKey.holdMaxMillis(), "config round trip preserves randomized hold maximum");
		assertEquals("Confirm, Claim", ((MacroStep.ClickItem) configUntil.steps().getFirst()).name(),
			"config round trip preserves comma-separated click alternatives");
		assertEquals("Confirm, Claim", ((MacroCondition.Item) configUntil.condition()).name(),
			"config round trip preserves comma-separated condition alternatives");

		MacroDefinition scopedItems = new MacroDefinition(13);
		for (MacroCondition.ItemScope scope : MacroCondition.ItemScope.values()) {
			scopedItems.steps().add(new MacroStep.WaitUntil(new MacroCondition.Item("Confirm", false, true, scope)));
		}
		var scopedConfig = MacroStepConfigCodec.encode(scopedItems.steps());
		List<MacroStep> scopedConfigCopy = MacroStepConfigCodec.decode(JsonParser.parseString(scopedConfig.toString()).getAsJsonArray());
		String[] scopeNames = {"container", "player", "all"};
		for (int i = 0; i < scopeNames.length; i++) {
			assertEquals(scopeNames[i], scopedConfig.get(i).getAsJsonObject().getAsJsonObject("condition")
				.get("scope").getAsString(), "local config writes the canonical Item scope " + scopeNames[i]);
			MacroCondition.Item restored = (MacroCondition.Item) ((MacroStep.WaitUntil) scopedConfigCopy.get(i)).condition();
			assertSame(MacroCondition.ItemScope.values()[i], restored.scope(),
				"local config preserves Item scan scope " + scopeNames[i]);
		}
		JsonObject scopedClipboard = JsonParser.parseString(MacroTransfer.encode(List.of(scopedItems))).getAsJsonObject();
		MacroTransfer.ImportResult scopedClipboardImport = MacroTransfer.decode(scopedClipboard.toString(), 103);
		assertTrue(scopedClipboardImport.success(), "clipboard accepts all Item scan scopes");
		for (int i = 0; i < scopeNames.length; i++) {
			assertEquals(scopeNames[i], scopedClipboard.getAsJsonArray("macros").get(0).getAsJsonObject()
				.getAsJsonArray("steps").get(i).getAsJsonObject().getAsJsonObject("condition")
				.get("scope").getAsString(), "clipboard writes the canonical Item scope " + scopeNames[i]);
			MacroCondition.Item restored = (MacroCondition.Item) ((MacroStep.WaitUntil)
				scopedClipboardImport.macros().getFirst().steps().get(i)).condition();
			assertSame(MacroCondition.ItemScope.values()[i], restored.scope(),
				"clipboard preserves Item scan scope " + scopeNames[i]);
		}

		List<MacroStep> legacyScopeConfig = MacroStepConfigCodec.decode(JsonParser.parseString(
			"[{\"type\":\"wait_until\",\"condition\":{\"type\":\"item\",\"includePlayerInventory\":false}},"
			+ "{\"type\":\"wait_until\",\"condition\":{\"type\":\"item\",\"includePlayerInventory\":true}}]").getAsJsonArray());
		assertSame(MacroCondition.ItemScope.CONTAINER,
			((MacroCondition.Item) ((MacroStep.WaitUntil) legacyScopeConfig.get(0)).condition()).scope(),
			"legacy config false maps to container only");
		assertSame(MacroCondition.ItemScope.CONTAINER_AND_PLAYER,
			((MacroCondition.Item) ((MacroStep.WaitUntil) legacyScopeConfig.get(1)).condition()).scope(),
			"legacy config true maps to both slot groups");
		JsonObject legacyScopeClipboard = JsonParser.parseString(MacroTransfer.encode(List.of(scopedItems))).getAsJsonObject();
		legacyScopeClipboard.addProperty("version", 2);
		for (int i = 0; i < 2; i++) {
			JsonObject condition = legacyScopeClipboard.getAsJsonArray("macros").get(0).getAsJsonObject()
				.getAsJsonArray("steps").get(i).getAsJsonObject().getAsJsonObject("condition");
			condition.remove("scope");
			condition.addProperty("includePlayerInventory", i == 1);
		}
		MacroTransfer.ImportResult legacyScopeImport = MacroTransfer.decode(legacyScopeClipboard.toString(), 104);
		assertTrue(legacyScopeImport.success(), "clipboard reads legacy Item scope booleans");
		assertSame(MacroCondition.ItemScope.CONTAINER,
			((MacroCondition.Item) ((MacroStep.WaitUntil) legacyScopeImport.macros().getFirst().steps().get(0)).condition()).scope(),
			"legacy clipboard false maps to container only");
		assertSame(MacroCondition.ItemScope.CONTAINER_AND_PLAYER,
			((MacroCondition.Item) ((MacroStep.WaitUntil) legacyScopeImport.macros().getFirst().steps().get(1)).condition()).scope(),
			"legacy clipboard true maps to both slot groups");

		List<MacroStep> legacyKeyConfig = MacroStepConfigCodec.decode(JsonParser.parseString(
			"[{\"type\":\"key\",\"key\":\"key.keyboard.space\",\"hold\":true,\"holdMillis\":321}]").getAsJsonArray());
		MacroStep.Key legacyConfigKey = (MacroStep.Key) legacyKeyConfig.getFirst();
		assertEquals(321, legacyConfigKey.holdMinMillis(), "legacy config hold duration becomes the range minimum");
		assertEquals(321, legacyConfigKey.holdMaxMillis(), "legacy config hold duration remains fixed");
		MacroDefinition legacyMacro = new MacroDefinition(12);
		legacyMacro.steps().add(new MacroStep.Key("key.keyboard.space", true, 650));
		JsonObject legacyClipboard = JsonParser.parseString(MacroTransfer.encode(List.of(legacyMacro))).getAsJsonObject();
		JsonObject legacyStep = legacyClipboard.getAsJsonArray("macros").get(0).getAsJsonObject()
			.getAsJsonArray("steps").get(0).getAsJsonObject();
		legacyStep.remove("holdMinMillis");
		legacyStep.remove("holdMaxMillis");
		legacyStep.addProperty("holdMillis", 650);
		MacroStep.Key legacyClipboardKey = (MacroStep.Key) MacroTransfer.decode(legacyClipboard.toString(), 102)
			.macros().getFirst().steps().getFirst();
		assertEquals(650, legacyClipboardKey.holdMinMillis(), "legacy clipboard hold duration becomes the range minimum");
		assertEquals(650, legacyClipboardKey.holdMaxMillis(), "legacy clipboard hold duration remains fixed");

		MacroDefinition selectable = new MacroDefinition(8);
		selectable.setIslands(List.of(Island.GARDEN));
		MacrosModule.INSTANCE.restore(List.of(selectable));
		SettingGroup macroGroup = MacrosModule.INSTANCE.groups().get(1);
		TextSetting rename = null;
		for (Setting setting : macroGroup.settings()) {
			if (setting instanceof TextSetting text && "Rename Macro".equals(text.displayName())) rename = text;
		}
		assertTrue(rename != null, "macro exposes an explicit rename field");
		rename.setValue("Garden Routine");
		MacrosModule.INSTANCE.syncSettings();
		assertEquals("Garden Routine", selectable.name(), "renaming updates the macro");
		assertEquals(1, macroGroup.children().size(), "macro has a nested island group");
		SettingGroup islands = macroGroup.children().getFirst();
		long selectableCount = java.util.Arrays.stream(Island.values()).filter(Island::selectable).count();
		assertEquals((int) selectableCount, islands.settings().size(), "island group exposes every selectable island");
		BooleanSetting garden = null;
		for (Setting setting : islands.settings()) {
			if (setting instanceof BooleanSetting toggle && toggle.name().equals(Island.GARDEN.label())) garden = toggle;
		}
		assertTrue(garden != null && garden.value(), "saved island is checked in the island group");
		garden.setValue(false);
		MacrosModule.INSTANCE.syncSettings();
		assertFalse(selectable.islands().contains(Island.GARDEN), "unchecking an island updates the macro");

		assertEquals("None", ModuleKeybind.NONE.displayName(), "macro keybind uses the shared display format");
		MacroRuntimeChecks.run();
	}

	private static void assertSame(Object expected, Object actual, String label) {
		if (expected != actual) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static void assertFalse(boolean value, String label) {
		if (value) throw new AssertionError(label);
	}
}
