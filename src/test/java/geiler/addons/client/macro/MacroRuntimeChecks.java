package geiler.addons.client.macro;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import geiler.addons.client.config.MacroStepConfigCodec;
import geiler.addons.client.config.MacroVariableConfigCodec;
import geiler.addons.client.module.impl.MacrosModule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Offline checks for the expanded macro runtime and versioned transfer model. */
public final class MacroRuntimeChecks {
	private MacroRuntimeChecks() {
	}

	public static void run() {
		checkWorldGeometryAndScheduling();
		checkRuntimeGuards();
		checkFunctionLocals();
		checkGlobalVariables();
		checkNewStepCodec();
		checkUnknownStepsAndLegacyMigrations();
		checkDependenciesAndLegacyMigration();
	}

	private static void checkWorldGeometryAndScheduling() {
		MacroWorldRegion region = new MacroWorldRegion();
		assertEquals(3.0, region.yTolerance(), "world triggers default to three-block vertical tolerance");
		region.place("server|overworld", 10, 64, 10);
		region.setShape(MacroWorldRegion.Shape.SQUARE);
		region.setSize(8);
		assertTrue(region.contains("server|overworld", 18, 64, 2), "square includes its half-width boundary");
		assertFalse(region.contains("server|overworld", 18.01, 64, 10), "square excludes points past its edge");
		region.setShape(MacroWorldRegion.Shape.CIRCLE);
		assertTrue(region.contains("server|overworld", 18, 64, 10), "circle includes its radius boundary");
		assertFalse(region.contains("server|overworld", 18, 64, 18), "circle excludes its square corners");
		region.setShape(MacroWorldRegion.Shape.RING);
		region.setInnerSize(4);
		assertFalse(region.contains("server|overworld", 10, 64, 10), "ring excludes its center hole");
		assertTrue(region.contains("server|overworld", 14, 64, 10), "ring includes its inner boundary");
		assertFalse(region.contains("another-server|overworld", 14, 64, 10), "placement is bound to its world context");
		assertFalse(region.contains("server|overworld", 14, 67.01, 10), "vertical tolerance excludes distant floors");
		region.setSize(3);
		assertTrue(region.innerSize() < region.size(), "shrinking a ring keeps its inner radius valid");

		assertTrue(MacroRuntimeRules.shouldStartWorldRun(true, true, false, false, false, 0, 100, 500),
			"a repeat-enabled world stack fires on entry");
		assertFalse(MacroRuntimeRules.shouldStartWorldRun(true, false, false, false, false, 100, 599, 500),
			"a world stack waits for its post-completion delay");
		assertTrue(MacroRuntimeRules.shouldStartWorldRun(true, false, false, false, false, 100, 600, 500),
			"a world stack repeats after the delay from completion");
		assertFalse(MacroRuntimeRules.shouldStartWorldRun(true, true, true, false, false, 0, 100, 0),
			"the same world-trigger stack never overlaps itself");
		assertFalse(MacroRuntimeRules.shouldStartWorldRun(true, false, false, true, true, 100, 600, 500),
			"once-per-world suppresses repeat runs after the first firing");
		assertTrue(MacroRuntimeRules.shouldStartWorldRun(true, true, false, true, false, 0, 100, 0),
			"once-per-world still fires on the first entry in a loaded-world session");
	}

	private static void checkRuntimeGuards() {
		assertTrue(MacroRuntimeRules.canStart(false, 0, 64), "a free stack can start");
		assertFalse(MacroRuntimeRules.canStart(true, 0, 64), "a running stack ignores re-entry");
		assertFalse(MacroRuntimeRules.canStart(false, 64, 64), "the global active-run limit is enforced");
		assertFalse(MacroRuntimeRules.canSelectHotbar(true), "hotbar selection is guarded in containers");
		assertTrue(MacroRuntimeRules.canSelectHotbar(false), "hotbar selection remains available in the world");
		assertEquals(0x2A123456, MacroRuntimeRules.colorWithOpacity(0xFF123456, 42), "region fill opacity overrides only the color alpha");
		assertFalse(MacroRuntimeRules.shouldReleaseHeldMouse(false, 500, 1_000), "held mouse stays down before its deadline");
		assertTrue(MacroRuntimeRules.shouldReleaseHeldMouse(false, 1_000, 1_000), "held mouse releases at its deadline");
		assertTrue(MacroRuntimeRules.shouldReleaseHeldMouse(true, 500, 1_000), "opening a screen releases held mouse input");

		int depth = 0;
		while (MacroRuntimeRules.canEnterCall(depth, 16, false)) depth++;
		assertEquals(16, depth, "mixed macro/function calls share the same maximum runtime depth");
		assertFalse(MacroRuntimeRules.canEnterCall(depth, 16, false), "calls cannot exceed the shared depth limit");
		assertFalse(MacroRuntimeRules.canEnterCall(0, 16, true), "a repeated macro or function id is rejected as a cycle");
	}

	private static void checkFunctionLocals() {
		MacroFunction function = new MacroFunction("function-binding-check");
		function.parameters().add(new MacroFunction.Parameter("count", MacroValue.Type.NUMBER, "2.5"));
		Map<String, Object> caller = new HashMap<>();
		caller.put("source", "7.25");
		List<MacroValue> args = List.of(MacroValue.variable(MacroValue.Type.TEXT, "source"));
		Map<String, Object> firstCall = function.bindArguments(args, caller);
		Map<String, Object> secondCall = function.bindArguments(args, caller);
		assertEquals(7.25d, firstCall.get("count"), "typed function arguments coerce to their declared type");
		firstCall.put("count", 99.0d);
		assertEquals(7.25d, secondCall.get("count"), "function locals are isolated between calls");
		assertEquals("7.25", caller.get("source"), "function locals do not mutate their caller's variables");
		assertEquals(2.5d, function.bindArguments(List.of(), caller).get("count"), "function defaults bind when omitted");
	}

	private static void checkGlobalVariables() {
		MacroVariableStore globals = new MacroVariableStore();
		MacroVariableStore.Definition count = globals.create("shared count", MacroValue.Type.NUMBER);
		assertTrue(count != null, "global variables can be created with stable definitions");
		assertFalse(count.persistValue(), "global values default to session-only persistence");
		globals.setValue(count.id(), 17.5);
		assertEquals(17.5d, MacroValue.globalVariable(MacroValue.Type.NUMBER, count.id())
			.resolve(Map.of(), globals), "global references resolve across macro frames");

		MacroFunction function = new MacroFunction("global-reader");
		function.parameters().add(new MacroFunction.Parameter("amount", MacroValue.Type.NUMBER, "0"));
		Map<String, Object> first = function.bindArguments(
			List.of(MacroValue.globalVariable(MacroValue.Type.NUMBER, count.id())), Map.of(), globals);
		Map<String, Object> second = function.bindArguments(
			List.of(MacroValue.globalVariable(MacroValue.Type.NUMBER, count.id())), Map.of(), globals);
		assertEquals(17.5d, first.get("amount"), "function arguments can read shared globals");
		first.put("amount", 100.0d);
		assertEquals(17.5d, second.get("amount"), "function parameters remain local when their inputs are global");
		assertEquals(17.5d, globals.value(count.id()), "function locals do not mutate the global source value");

		MacroStep.SetVariable setGlobal = new MacroStep.SetVariable("shared count", MacroValue.Type.NUMBER,
			MacroValue.literal(MacroValue.Type.NUMBER, "24"), count.id());
		MacroStep.ChangeVariable changeGlobal = new MacroStep.ChangeVariable("shared count", 3, count.id());
		MacroCondition.Variable compareGlobal = new MacroCondition.Variable("shared count",
			MacroCondition.Variable.Operator.GREATER_THAN, MacroValue.literal(MacroValue.Type.NUMBER, "10"), count.id());
		List<MacroStep> globalSteps = List.of(setGlobal, changeGlobal, new MacroStep.WaitUntil(compareGlobal));
		List<MacroStep> restored = MacroStepConfigCodec.decode(JsonParser.parseString(
			MacroStepConfigCodec.encode(globalSteps).toString()).getAsJsonArray());
		assertEquals(count.id(), ((MacroStep.UpdateVariable) restored.get(0)).globalVariableId(),
			"global assignment targets survive config round trips by stable id");
		assertEquals(count.id(), ((MacroStep.UpdateVariable) restored.get(1)).globalVariableId(),
			"global change targets survive config round trips by stable id");
		MacroCondition.Variable restoredCondition = (MacroCondition.Variable)
			((MacroStep.WaitUntil) restored.get(2)).condition();
		assertEquals(count.id(), restoredCondition.globalVariableId(),
			"global comparison targets survive config round trips by stable id");

		String transientConfig = MacroVariableConfigCodec.encode(globals.savedVariables()).toString();
		assertFalse(transientConfig.contains("17.5"), "session-only values are omitted from local config");
		MacroVariableStore restoredGlobals = new MacroVariableStore();
		restoredGlobals.restore(MacroVariableConfigCodec.decode(JsonParser.parseString(transientConfig).getAsJsonArray()));
		assertEquals(0.0d, restoredGlobals.value(count.id()), "session-only values reset to their type defaults on load");
		assertTrue(globals.setPersistValue(count.id(), true), "an existing global can opt into local value persistence");
		globals.setValue(count.id(), 42.5d);
		String persistentConfig = MacroVariableConfigCodec.encode(globals.savedVariables()).toString();
		assertTrue(persistentConfig.contains("42.5"), "opted-in live values are saved locally");
		restoredGlobals.restore(MacroVariableConfigCodec.decode(JsonParser.parseString(persistentConfig).getAsJsonArray()));
		assertEquals(42.5d, restoredGlobals.value(count.id()), "opted-in values restore with their definitions");

		MacroDefinition portable = new MacroDefinition(900);
		portable.steps().addAll(globalSteps);
		List<MacroVariableStore.SavedVariable> previousGlobals =
			MacrosModule.INSTANCE.globalVariables().savedVariables();
		MacrosModule.INSTANCE.restore(List.of(portable));
		MacrosModule.INSTANCE.restoreFunctions(List.of());
		MacrosModule.INSTANCE.globalVariables().restore(globals.savedVariables());
		String packageText = MacrosModule.INSTANCE.exportEncoded(List.of(portable));
		assertFalse(packageText.contains("savedValue"), "portable macro data never contains a global runtime value");
		MacroTransfer.ImportResult imported = MacroTransfer.decode(packageText, 901);
		assertTrue(imported.success(), "portable packages with global references remain importable");
		assertEquals(1, imported.globalVariables().size(), "portable package includes the referenced global definition");
		assertEquals(count.id(), imported.globalVariables().getFirst().id(), "portable package retains the variable's stable id");
		assertEquals(count.id(), ((MacroStep.UpdateVariable) imported.macros().getFirst().steps().get(0)).globalVariableId(),
			"portable import retains global variable references");
		assertFalse(imported.globalVariables().getFirst().persistValue(),
			"portable packages never transfer the local persistence preference");

		// Downgrade by editing the JSON rather than string-replacing a version literal, so this
		// keeps testing "a v3 package has no globalVariables section" as the format advances.
		JsonObject olderPackage = JsonParser.parseString(packageText).getAsJsonObject();
		olderPackage.addProperty("version", 3);
		olderPackage.remove("globalVariables");
		MacroTransfer.ImportResult legacy = MacroTransfer.decode(olderPackage.toString(), 902);
		assertTrue(legacy.success(), "version 3 packages remain readable after the transfer version advances");
		assertTrue(legacy.globalVariables().isEmpty(), "older packages without global definitions remain valid");
		MacrosModule.INSTANCE.globalVariables().restore(previousGlobals);
	}

	private static void checkNewStepCodec() {
		MacroStep.FunctionCall functionCall = new MacroStep.FunctionCall("codec-function");
		functionCall.arguments().add(MacroValue.literal(MacroValue.Type.NUMBER, "4"));
		functionCall.arguments().add(MacroValue.variable(MacroValue.Type.TEXT, "label"));
		List<MacroStep> source = List.of(
			new MacroStep.SelectHotbarSlot(8),
			new MacroStep.MouseButton(MacroStep.MouseButton.Button.MIDDLE, true, 321),
			new MacroStep.BlockPlayerInput(450),
			new MacroStep.StartBlockPlayerInput(),
			new MacroStep.StopBlockPlayerInput(),
			new MacroStep.SetVariable("flag", MacroValue.Type.BOOLEAN, MacroValue.literal(MacroValue.Type.BOOLEAN, "true")),
			new MacroStep.ChangeVariable("count", 1.25),
			functionCall,
			new MacroStep.MacroCall(72));
		List<MacroStep> restored = MacroStepConfigCodec.decode(
			JsonParser.parseString(MacroStepConfigCodec.encode(source).toString()).getAsJsonArray());
		assertEquals(source.size(), restored.size(), "all newly added action nodes survive a config round trip");
		MacroStep.Key hotbar = (MacroStep.Key) restored.get(0);
		assertEquals(MacroStep.Key.InputMode.HOTBAR, hotbar.inputMode(), "legacy hotbar node migrates to unified key input");
		assertEquals(8, hotbar.hotbarSlot(), "hotbar slot is preserved");
		MacroStep.Key mouse = (MacroStep.Key) restored.get(1);
		assertEquals(MacroStep.Key.InputMode.MOUSE, mouse.inputMode(), "legacy mouse node migrates to unified key input");
		assertEquals(MacroStep.MouseButton.Button.MIDDLE, mouse.mouseButton(), "mouse button is preserved");
		assertTrue(mouse.hold(), "mouse hold mode is preserved");
		assertEquals(321, mouse.holdMinMillis(), "mouse hold duration minimum is preserved");
		assertEquals(321, mouse.holdMaxMillis(), "mouse hold duration maximum is preserved");
		assertEquals(450, ((MacroStep.BlockPlayerInput) restored.get(2)).durationMillis(), "timed input block is preserved");
		assertTrue(restored.get(3) instanceof MacroStep.StartBlockPlayerInput, "start input-block node is preserved");
		assertTrue(restored.get(4) instanceof MacroStep.StopBlockPlayerInput, "stop input-block node is preserved");
		assertEquals("true", ((MacroStep.UpdateVariable) restored.get(5)).value().value(), "typed variable assignment is preserved");
		assertEquals(1.25, ((MacroStep.UpdateVariable) restored.get(6)).amount(), "numeric variable change is preserved");
		MacroStep.FunctionCall restoredCall = (MacroStep.FunctionCall) restored.get(7);
		assertEquals(2, restoredCall.arguments().size(), "function arguments are preserved");
		assertTrue(restoredCall.arguments().get(1).variableReference(), "variable argument references are preserved");
		assertEquals(72, ((MacroStep.MacroCall) restored.get(8)).macroId(), "macro-call target is preserved before import remapping");
		assertTrue(((MacroStep.MacroCall) restored.get(8)).condition() == null,
			"a call written without a condition stays unconditional");
		checkConditionalMacroCall();
	}

	private static void checkUnknownStepsAndLegacyMigrations() {
		JsonArray legacy = JsonParser.parseString("["
			+ "{\"type\":\"command\",\"command\":\"warp home\",\"delayMin\":12,\"delayMax\":34,\"editorX\":21.5,\"editorY\":-8},"
			+ "{\"type\":\"select_hotbar_slot\",\"slot\":8},"
			+ "{\"type\":\"mouse_button\",\"button\":\"MIDDLE\",\"hold\":true,\"holdMillis\":321},"
			+ "{\"type\":\"click_slot\",\"slotId\":31,\"button\":1,\"shift\":true},"
			+ "{\"type\":\"click_item\",\"name\":\"Confirm, Claim\",\"contains\":true,\"scope\":\"player\",\"occurrence\":2,\"button\":2,\"shift\":true},"
			+ "{\"type\":\"set_variable\",\"name\":\"amount\",\"valueType\":\"NUMBER\",\"globalVariableId\":\"global-1\",\"value\":{\"type\":\"NUMBER\",\"variable\":false,\"scope\":\"NONE\",\"value\":\"4.5\"}},"
			+ "{\"type\":\"change_variable\",\"name\":\"amount\",\"amount\":2.75,\"globalVariableId\":\"global-1\"},"
			+ "{\"type\":\"repeat_until\",\"condition\":{\"type\":\"world\",\"mustBeInWorld\":true},\"untilSteps\":[{\"type\":\"chat\",\"message\":\"continue\"}]}"
			+ "]").getAsJsonArray();
		List<MacroStep> migrated = MacroStepConfigCodec.decode(legacy);
		assertEquals(8, migrated.size(), "recognized legacy block types remain visible after migration");
		MacroStep.Chat command = (MacroStep.Chat) migrated.get(0);
		assertEquals("/warp home", command.message(), "legacy commands migrate without changing command behavior");
		assertEquals(12, command.delayMin(), "legacy command keeps its delay minimum");
		assertEquals(34, command.delayMax(), "legacy command keeps its delay maximum");
		assertEquals(21.5f, ((MacroStep.Base) command).editorX(), "legacy command keeps its canvas position");
		MacroStep.Key hotbar = (MacroStep.Key) migrated.get(1);
		assertEquals(MacroStep.Key.InputMode.HOTBAR, hotbar.inputMode(), "legacy hotbar becomes unified key input");
		assertEquals(8, hotbar.hotbarSlot(), "legacy hotbar selection keeps its slot");
		MacroStep.Key mouse = (MacroStep.Key) migrated.get(2);
		assertEquals(MacroStep.Key.InputMode.MOUSE, mouse.inputMode(), "legacy mouse action becomes unified key input");
		assertEquals(MacroStep.MouseButton.Button.MIDDLE, mouse.mouseButton(), "legacy mouse action keeps its button");
		assertTrue(mouse.hold(), "legacy mouse action keeps its hold mode");
		assertEquals(321, mouse.holdMinMillis(), "legacy mouse action keeps its fixed hold duration");
		assertEquals(321, mouse.holdMaxMillis(), "legacy mouse action keeps its fixed hold duration");
		MacroStep.InventoryClick slot = (MacroStep.InventoryClick) migrated.get(3);
		assertEquals(MacroStep.InventoryClick.Target.SLOT, slot.target(), "legacy slot click becomes a unified inventory click");
		assertEquals(31, slot.slotId(), "legacy slot click keeps its slot");
		assertEquals(1, slot.button(), "legacy slot click keeps its mouse button");
		assertTrue(slot.shift(), "legacy slot click keeps its shift modifier");
		MacroStep.InventoryClick item = (MacroStep.InventoryClick) migrated.get(4);
		assertEquals(MacroStep.InventoryClick.Target.ITEM, item.target(), "legacy item click becomes a unified inventory click");
		assertEquals("Confirm, Claim", item.name(), "legacy item click keeps its alternatives");
		assertEquals("player", item.scope(), "legacy item click keeps its search scope");
		assertEquals(2, item.occurrence(), "legacy item click keeps its occurrence");
		assertEquals(2, item.button(), "legacy item click keeps its mouse button");
		assertTrue(item.shift(), "legacy item click keeps its shift modifier");
		MacroStep.UpdateVariable set = (MacroStep.UpdateVariable) migrated.get(5);
		assertEquals(MacroStep.UpdateVariable.Operation.SET, set.operation(), "legacy set variable becomes unified SET");
		assertEquals("global-1", set.globalVariableId(), "legacy set variable keeps its global target");
		assertEquals(MacroValue.Type.NUMBER, set.value().type(), "legacy set variable keeps its value type");
		assertEquals("4.5", set.value().value(), "legacy set variable keeps its value");
		MacroStep.UpdateVariable explicitLegacyType = (MacroStep.UpdateVariable) MacroStepConfigCodec.decode(
			JsonParser.parseString("[{\"type\":\"set_variable\",\"valueType\":\"NUMBER\","
				+ "\"value\":{\"type\":\"TEXT\",\"variable\":false,\"value\":\"3\"}}]")
			.getAsJsonArray()).getFirst();
		assertEquals(MacroValue.Type.NUMBER, explicitLegacyType.value().type(),
			"legacy set variable's explicit type setting migrates to the unified value");
		MacroStep.UpdateVariable change = (MacroStep.UpdateVariable) migrated.get(6);
		assertEquals(MacroStep.UpdateVariable.Operation.ADD, change.operation(), "legacy change variable becomes unified ADD");
		assertEquals(2.75, change.amount(), "legacy variable change keeps its amount");
		assertEquals("global-1", change.globalVariableId(), "legacy variable change keeps its global target");
		MacroStep.Repeat until = (MacroStep.Repeat) migrated.get(7);
		assertEquals(MacroStep.Repeat.Mode.UNTIL, until.mode(), "legacy Repeat Until becomes the unified repeat node");
		assertEquals(1, until.steps().size(), "legacy Repeat Until keeps its child stack");
		assertTrue(until.condition() instanceof MacroCondition.World, "legacy Repeat Until keeps its condition");

		JsonObject futureStep = JsonParser.parseString("{\"type\":\"future_action\",\"delayMin\":7,"
			+ "\"editorX\":1.25,\"editorY\":-2.5,\"futureSettings\":{\"targets\":[\"a\",\"b\"],\"enabled\":true}}")
			.getAsJsonObject();
		JsonArray futureSteps = new JsonArray();
		futureSteps.add(futureStep);
		MacroStep.Unknown unknown = (MacroStep.Unknown) MacroStepConfigCodec.decode(futureSteps).getFirst();
		assertEquals("future_action", unknown.originalType(), "unknown steps retain their original type label");
		assertEquals(futureStep, unknown.serializedData(), "unknown step data survives a config decode unchanged");
		JsonObject externalCopy = unknown.serializedData();
		externalCopy.addProperty("futureMutation", true);
		assertFalse(unknown.serializedData().has("futureMutation"), "unknown raw data is exposed defensively");
		unknown.setEditorPosition(10, 20);
		JsonObject movedUnknown = MacroStepConfigCodec.encode(List.of(unknown)).get(0).getAsJsonObject();
		assertEquals(futureStep.get("futureSettings"), movedUnknown.get("futureSettings"),
			"moving an unknown canvas block leaves every unknown setting intact");
		assertEquals(10.0f, movedUnknown.get("editorX").getAsFloat(), "moving an unknown canvas block persists its new x position");
		assertEquals(20.0f, movedUnknown.get("editorY").getAsFloat(), "moving an unknown canvas block persists its new y position");
		List<MacroStep> activeUnknownStack = new ArrayList<>();
		activeUnknownStack.add(unknown);
		assertFalse(MacroRunner.wasPausedUnknownStepResolved(activeUnknownStack, unknown),
			"a paused run cannot resume while its unsupported block remains in the active stack");
		activeUnknownStack.set(0, new MacroStep.Chat("replacement"));
		assertTrue(MacroRunner.wasPausedUnknownStepResolved(activeUnknownStack, unknown),
			"a paused run may resume after the unsupported block is replaced");
		activeUnknownStack.set(0, unknown);
		activeUnknownStack.clear();
		assertTrue(MacroRunner.wasPausedUnknownStepResolved(activeUnknownStack, unknown),
			"a paused run may resume after the unsupported block is removed");
		assertFalse(MacroRunner.wasPausedUnknownStepResolved(null, unknown),
			"a paused run does not resume when its active stack cannot be verified");

		MacroDefinition portable = new MacroDefinition(933);
		portable.steps().add(unknown);
		MacroTransfer.ImportResult imported = MacroTransfer.decode(MacroTransfer.encode(List.of(portable)), 934);
		assertTrue(imported.success(), "portable packages accept and preserve unknown macro blocks");
		assertEquals(movedUnknown, ((MacroStep.Unknown) imported.macros().getFirst().steps().getFirst()).serializedData(),
			"unknown raw fields survive a portable export and import");

		JsonObject legacyPackage = new JsonObject();
		legacyPackage.addProperty("format", "geileraddons-macros");
		legacyPackage.addProperty("version", 1);
		JsonObject legacyMacro = new JsonObject();
		legacyMacro.addProperty("id", 72);
		legacyMacro.addProperty("name", "Legacy unknown");
		JsonArray legacyUnknownSteps = new JsonArray();
		legacyUnknownSteps.add(futureStep);
		legacyMacro.add("steps", legacyUnknownSteps);
		JsonArray legacyMacros = new JsonArray();
		legacyMacros.add(legacyMacro);
		legacyPackage.add("macros", legacyMacros);
		MacroTransfer.ImportResult oldImport = MacroTransfer.decode(legacyPackage.toString(), 935);
		assertTrue(oldImport.success(), "legacy clipboard packages retain unknown step placeholders");
		assertEquals(futureStep, ((MacroStep.Unknown) oldImport.macros().getFirst().steps().getFirst()).serializedData(),
			"legacy clipboard import keeps every raw unknown field");

		JsonObject deepLeaf = JsonParser.parseString("{\"type\":\"future_nested_action\",\"custom\":{\"kept\":true}}")
			.getAsJsonObject();
		JsonElement nested = deepLeaf;
		for (int i = 0; i <= MacroTreeRules.MAX_DEPTH; i++) {
			JsonObject repeat = new JsonObject();
			repeat.addProperty("type", "repeat");
			JsonArray body = new JsonArray();
			body.add(nested);
			repeat.add("steps", body);
			nested = repeat;
		}
		JsonArray deeplyNested = new JsonArray();
		deeplyNested.add(nested);
		List<MacroStep> deepDecoded = MacroStepConfigCodec.decode(deeplyNested);
		MacroStep cursor = deepDecoded.getFirst();
		for (int i = 0; i <= MacroTreeRules.MAX_DEPTH; i++) {
			assertTrue(cursor instanceof MacroStep.Repeat, "all levels above the nesting boundary stay editable");
			cursor = ((MacroStep.Repeat) cursor).steps().getFirst();
		}
		assertTrue(cursor instanceof MacroStep.Unknown && ((MacroStep.Unknown) cursor).depthLimited(),
			"a block beyond the supported depth becomes a visible raw-data placeholder");
		assertEquals(deepLeaf, ((MacroStep.Unknown) cursor).serializedData(),
			"an over-depth node keeps its raw data");
		JsonElement savedDeep = MacroStepConfigCodec.encode(deepDecoded).get(0);
		for (int i = 0; i <= MacroTreeRules.MAX_DEPTH; i++) {
			savedDeep = savedDeep.getAsJsonObject().getAsJsonArray("steps").get(0);
		}
		assertEquals(deepLeaf, savedDeep, "a deep placeholder survives a save/reload round trip");

		MacroDefinition maxDepth = new MacroDefinition(936);
		List<MacroStep> target = maxDepth.steps();
		for (int i = 0; i < MacroTreeRules.MAX_DEPTH; i++) {
			MacroStep.Repeat repeat = new MacroStep.Repeat(false, 1);
			target.add(repeat);
			target = repeat.steps();
		}
		target.add(new MacroStep.Chat("bottom"));
		List<MacroStep> maxDepthReload = MacroStepConfigCodec.decode(
			MacroStepConfigCodec.encode(maxDepth.steps()));
		MacroStep atBottom = maxDepthReload.getFirst();
		for (int i = 0; i < MacroTreeRules.MAX_DEPTH; i++) {
			atBottom = ((MacroStep.Repeat) atBottom).steps().getFirst();
		}
		assertEquals("bottom", ((MacroStep.Chat) atBottom).message(),
			"the editor's maximum nesting depth now persists through config reload");
	}

	/**
	 * A gated Macro Call has to survive both codecs, stay unconditional when it was written that
	 * way, and carry a global variable its condition reads - otherwise an imported copy would refer
	 * to a variable the receiving client does not have.
	 */
	private static void checkConditionalMacroCall() {
		MacroStep.MacroCall gated = new MacroStep.MacroCall(73);
		gated.setCondition(new MacroCondition.Not(new MacroCondition.Variable("ready",
			MacroCondition.Variable.Operator.EQUALS, MacroValue.literal(MacroValue.Type.BOOLEAN, "true"))));
		List<MacroStep> restored = MacroStepConfigCodec.decode(JsonParser.parseString(
			MacroStepConfigCodec.encode(List.of(gated)).toString()).getAsJsonArray());
		MacroStep.MacroCall restoredCall = (MacroStep.MacroCall) restored.getFirst();
		assertEquals(73, restoredCall.macroId(), "a gated call keeps its target");
		assertTrue(restoredCall.condition() instanceof MacroCondition.Not,
			"a compound condition survives the config round trip");
		assertTrue(((MacroCondition.Not) restoredCall.condition()).child() instanceof MacroCondition.Variable,
			"the nested condition survives the config round trip");

		MacroStep.MacroCall legacy = (MacroStep.MacroCall) MacroStepConfigCodec.decode(
			JsonParser.parseString("[{\"type\":\"macro_call\",\"macroId\":9}]").getAsJsonArray()).getFirst();
		assertTrue(legacy.condition() == null,
			"a call written before conditions existed decodes as unconditional");

		MacroVariableStore.SavedVariable definition = new MacroVariableStore.SavedVariable(
			new MacroVariableStore.Definition("variable-id", "ready", MacroValue.Type.BOOLEAN, false), null);
		List<MacroVariableStore.SavedVariable> previousDefinitions =
			MacrosModule.INSTANCE.globalVariables().savedVariables();
		MacroDefinition caller = new MacroDefinition(42);
		MacroStep.MacroCall referencing = new MacroStep.MacroCall(43);
		referencing.setCondition(new MacroCondition.Variable("x", MacroCondition.Variable.Operator.EQUALS,
			MacroValue.literal(MacroValue.Type.TEXT, ""), "variable-id"));
		caller.steps().add(referencing);
		MacroDefinition target = new MacroDefinition(43);
		target.addScript(MacroScript.Trigger.ON_CALL).steps().add(new MacroStep.Chat("ran"));
		MacrosModule.INSTANCE.restore(List.of(caller, target));
		MacrosModule.INSTANCE.globalVariables().restore(List.of(definition));
		try {
			MacroTransfer.ImportResult imported = MacroTransfer.decode(
				MacrosModule.INSTANCE.exportEncoded(List.of(caller)), 700);
			assertTrue(imported.success(), "a macro whose call condition reads a global exports cleanly");
			assertEquals(1, imported.globalVariables().size(),
				"a global variable referenced only by a call condition is exported with its macro");
		} finally {
			MacrosModule.INSTANCE.globalVariables().restore(previousDefinitions);
		}
	}

	private static void checkDependenciesAndLegacyMigration() {
		MacroDefinition root = new MacroDefinition(40);
		root.setName("Root routine");
		MacroScript keyStack = root.primaryKeyScript();
		keyStack.setCanvasPosition(140, 90);
		keyStack.steps().add(new MacroStep.SelectHotbarSlot(4));
		MacroFunction shared = new MacroFunction("source-function-shared");
		shared.setName("Shared values");
		shared.parameters().add(new MacroFunction.Parameter("amount", MacroValue.Type.NUMBER, "3"));
		MacroFunction nested = new MacroFunction("source-function-nested");
		MacroStep.FunctionCall nestedCall = new MacroStep.FunctionCall(nested.id());
		shared.steps().add(nestedCall);
		keyStack.steps().add(new MacroStep.FunctionCall(shared.id()));
		keyStack.steps().add(new MacroStep.MacroCall(41));

		MacroScript onCall = root.addScript(MacroScript.Trigger.ON_CALL);
		onCall.steps().add(new MacroStep.Chat("called"));
		MacroScript worldStack = root.addScript(MacroScript.Trigger.WORLD_REGION);
		worldStack.setCanvasPosition(340, 160);
		worldStack.worldRegion().place("world-key|dimension", 12, 64, -5);
		worldStack.worldRegion().setShape(MacroWorldRegion.Shape.RING);
		worldStack.worldRegion().setInnerSize(2);
		worldStack.worldRegion().setOncePerWorld(true);
		worldStack.worldRegion().setColor(0xFF20A0D0);

		MacroDefinition dependency = new MacroDefinition(41);
		dependency.setName("Called macro");
		dependency.addScript(MacroScript.Trigger.ON_CALL).steps().add(new MacroStep.Chat("dependency ran"));
		MacrosModule.INSTANCE.restore(List.of(root, dependency));
		MacrosModule.INSTANCE.restoreFunctions(List.of(shared, nested));
		MacroTransfer.ImportResult imported = MacroTransfer.decode(
			MacrosModule.INSTANCE.exportEncoded(List.of(root)), 500);
		assertTrue(imported.success(), "transitive macro/function dependency export imports cleanly");
		assertEquals(2, imported.macros().size(), "macro-call dependency is included transitively");
		assertEquals(2, imported.functions().size(), "nested function dependency is included transitively");
		MacroDefinition restoredRoot = imported.macros().getFirst();
		assertEquals(500, restoredRoot.id(), "import remaps selected macro id");
		MacroStep.FunctionCall restoredFunctionCall = (MacroStep.FunctionCall) restoredRoot.steps().get(1);
		assertEquals(imported.functions().getFirst().id(), restoredFunctionCall.functionId(),
			"import remaps function call ids");
		assertEquals(imported.macros().get(1).id(), ((MacroStep.MacroCall) restoredRoot.steps().get(2)).macroId(),
			"import remaps macro call ids");
		MacroStep.FunctionCall restoredNestedCall = (MacroStep.FunctionCall) imported.functions().getFirst().steps().getFirst();
		assertEquals(imported.functions().get(1).id(), restoredNestedCall.functionId(),
			"import remaps nested function dependencies");
		MacroScript restoredWorld = restoredRoot.scripts().stream()
			.filter(script -> script.trigger() == MacroScript.Trigger.WORLD_REGION).findFirst().orElseThrow();
		assertEquals(140.0f, restoredRoot.primaryKeyScript().canvasX(), "key-stack canvas layout is preserved");
		assertEquals(340.0f, restoredWorld.canvasX(), "world-stack canvas layout is preserved");
		assertEquals("world-key|dimension", restoredWorld.worldRegion().worldKey(), "region world binding is preserved");
		assertEquals(MacroWorldRegion.Shape.RING, restoredWorld.worldRegion().shape(), "region shape is preserved");
		assertTrue(restoredWorld.worldRegion().oncePerWorld(), "once-per-world behavior is persisted");

		String legacyV2 = "{\"format\":\"geileraddons-macros\",\"version\":2,\"macros\":["
			+ "{\"id\":3,\"name\":\"Legacy\",\"steps\":[{\"type\":\"command\",\"command\":\"/say old\"}]}]}";
		MacroTransfer.ImportResult legacy = MacroTransfer.decode(legacyV2, 700);
		assertTrue(legacy.success(), "version 2 clipboard data remains readable");
		assertEquals(1, legacy.macros().getFirst().scripts().size(), "legacy macro migrates into its key event stack");
		assertTrue(legacy.macros().getFirst().steps().getFirst() instanceof MacroStep.Chat,
			"legacy command steps migrate to the unified chat-or-command node");
		assertEquals("/say old", ((MacroStep.Chat) legacy.macros().getFirst().steps().getFirst()).message(),
			"legacy command keeps its executable slash command");
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
