package geiler.addons.client.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import geiler.addons.client.location.Island;
import geiler.addons.client.macro.MacroCondition;
import geiler.addons.client.macro.MacroValue;
import geiler.addons.client.macro.MacroStep;
import geiler.addons.client.macro.MacroTreeRules;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Converts workflow nodes to and from the macro portion of the user config. */
public final class MacroStepConfigCodec {
	private static final int MAX_DEPTH = MacroTreeRules.MAX_DEPTH;
	private static final int MAX_STEPS = 512;

	private MacroStepConfigCodec() {
	}

	public static JsonArray encode(List<MacroStep> steps) {
		return writeSteps(steps, 0);
	}

	public static List<MacroStep> decode(JsonArray steps) {
		List<MacroStep> result = new ArrayList<>();
		if (steps == null) return result;
		for (JsonElement entry : steps) {
			if (entry != null && entry.isJsonObject()) {
				MacroStep step = readStep(entry.getAsJsonObject(), 0);
				if (step != null) result.add(step);
			}
		}
		return result;
	}

	private static JsonArray writeSteps(List<MacroStep> source, int depth) {
		JsonArray result = new JsonArray();
		if (source == null) return result;
		for (MacroStep step : source) {
			if (step == null) continue;
			if (depth > MAX_DEPTH && !(step instanceof MacroStep.Unknown)) {
				throw new IllegalStateException("Refusing to save a macro step beyond the supported nesting depth");
			}
			result.add(writeStep(step, depth));
		}
		return result;
	}

	private static JsonObject writeStep(MacroStep step, int depth) {
		if (step instanceof MacroStep.Unknown unknown) return unknown.serializedData();
		JsonObject result = new JsonObject();
		result.addProperty("type", step.type());
		result.addProperty("delayMin", step.delayMin());
		result.addProperty("delayMax", step.delayMax());
		if (step instanceof MacroStep.Base base) {
			result.addProperty("editorX", base.editorX());
			result.addProperty("editorY", base.editorY());
		}
		if (step instanceof MacroStep.Command value) result.addProperty("command", value.command());
		if (step instanceof MacroStep.Chat value) result.addProperty("message", value.message());
		if (step instanceof MacroStep.Title value) {
			result.addProperty("text", value.text());
			result.addProperty("font", value.font());
			result.addProperty("scale", value.scale());
			result.addProperty("textColor", value.textColor());
			result.addProperty("showBackground", value.showBackground());
			result.addProperty("backgroundColor", value.backgroundColor());
			result.addProperty("backgroundOpacity", value.backgroundOpacity());
			result.addProperty("fadeInMillis", value.fadeInMillis());
			result.addProperty("holdMillis", value.holdMillis());
			result.addProperty("fadeOutMillis", value.fadeOutMillis());
		}
		if (step instanceof MacroStep.Sound value) result.addProperty("soundId", value.soundId());
		if (step instanceof MacroStep.Wait value) {
			result.addProperty("minMillis", value.minMillis());
			result.addProperty("maxMillis", value.maxMillis());
			result.addProperty("mode", value.mode().name());
			if (value.mode() == MacroStep.Wait.Mode.CONDITION) {
				result.add("condition", writeCondition(value.condition(), depth + 1));
			}
		}
		if (step instanceof MacroStep.Comment value) result.addProperty("text", value.text());
		if (step instanceof MacroStep.Scroll value) {
			result.addProperty("direction", value.direction().name());
			result.addProperty("amount", value.amount());
		}
		if (step instanceof MacroStep.InventoryClick value) {
			result.addProperty("target", value.target().name());
			result.addProperty("slotId", value.slotId());
			result.addProperty("name", value.name());
			result.addProperty("contains", value.contains());
			result.addProperty("scope", value.scope());
			result.addProperty("occurrence", value.occurrence());
			result.addProperty("button", value.button());
			result.addProperty("shift", value.shift());
		}
		if (step instanceof MacroStep.UpdateVariable value) {
			result.addProperty("operation", value.operation().name());
			result.addProperty("name", value.name());
			result.addProperty("amount", value.amount());
			if (value.globalVariableId() != null) result.addProperty("globalVariableId", value.globalVariableId());
			result.add("value", writeValue(value.value()));
		}
		if (step instanceof MacroStep.Key value) {
			result.addProperty("key", value.key());
			result.addProperty("inputMode", value.inputMode().name());
			result.addProperty("mouseButton", value.mouseButton().name());
			result.addProperty("hotbarSlot", value.hotbarSlot());
			result.addProperty("hold", value.hold());
			result.addProperty("holdMinMillis", value.holdMinMillis());
			result.addProperty("holdMaxMillis", value.holdMaxMillis());
		}
		if (step instanceof MacroStep.ClickSlot value) {
			result.addProperty("slotId", value.slotId());
			result.addProperty("button", value.button());
			result.addProperty("shift", value.shift());
		}
		if (step instanceof MacroStep.ClickItem value) {
			result.addProperty("name", value.name());
			result.addProperty("contains", value.contains());
			result.addProperty("scope", value.scope());
			result.addProperty("occurrence", value.occurrence());
			result.addProperty("button", value.button());
			result.addProperty("shift", value.shift());
		}
		if (step instanceof MacroStep.SelectHotbarSlot value) result.addProperty("slot", value.slot());
		if (step instanceof MacroStep.MouseButton value) {
			result.addProperty("button", value.button().name());
			result.addProperty("hold", value.hold());
			result.addProperty("holdMillis", value.holdMillis());
		}
		if (step instanceof MacroStep.BlockPlayerInput value) result.addProperty("durationMillis", value.durationMillis());
		if (step instanceof MacroStep.SetVariable value) {
			result.addProperty("name", value.name());
			result.addProperty("valueType", value.valueType().name());
			if (value.globalVariableId() != null) result.addProperty("globalVariableId", value.globalVariableId());
			result.add("value", writeValue(value.value()));
		}
		if (step instanceof MacroStep.ChangeVariable value) {
			result.addProperty("name", value.name());
			result.addProperty("amount", value.amount());
			if (value.globalVariableId() != null) result.addProperty("globalVariableId", value.globalVariableId());
		}
		if (step instanceof MacroStep.FunctionCall value) {
			result.addProperty("functionId", value.functionId());
			JsonArray arguments = new JsonArray();
			for (MacroValue argument : value.arguments()) arguments.add(writeValue(argument));
			result.add("arguments", arguments);
		}
		if (step instanceof MacroStep.MacroCall value) {
			result.addProperty("macroId", value.macroId());
			// Written only when set: an unconditional call keeps the shape it has always had.
			if (value.condition() != null) result.add("condition", writeCondition(value.condition(), depth + 1));
		}
		if (step instanceof MacroStep.WorldSwitch value) result.addProperty("island", value.target().name());
		if (step instanceof MacroStep.WaitUntil value) result.add("condition", writeCondition(value.condition(), depth + 1));
		if (step instanceof MacroStep.IfElse value) {
			result.add("condition", writeCondition(value.condition(), depth + 1));
			result.addProperty("elseEnabled", value.elseEnabled());
			result.add("thenSteps", writeSteps(value.thenSteps(), depth + 1));
			result.add("elseSteps", writeSteps(value.elseSteps(), depth + 1));
		}
		if (step instanceof MacroStep.Repeat value) {
			result.addProperty("mode", value.mode().name());
			result.addProperty("forever", value.forever());
			result.addProperty("count", value.count());
			if (value.mode() == MacroStep.Repeat.Mode.UNTIL) {
				result.add("condition", writeCondition(value.condition(), depth + 1));
			}
			result.add("steps", writeSteps(value.steps(), depth + 1));
		}
		if (step instanceof MacroStep.RepeatUntil value) {
			result.add("condition", writeCondition(value.condition(), depth + 1));
			result.add("untilSteps", writeSteps(value.steps(), depth + 1));
		}
		if (step instanceof MacroStep.Switch value) {
			result.addProperty("name", value.name());
			if (value.globalVariableId() != null) result.addProperty("globalVariableId", value.globalVariableId());
			JsonArray cases = new JsonArray();
			for (MacroStep.SwitchCase branch : value.cases()) {
				if (cases.size() >= 16) break;
				JsonObject item = new JsonObject();
				item.addProperty("value", branch.value());
				item.add("steps", writeSteps(branch.steps(), depth + 1));
				cases.add(item);
			}
			result.add("cases", cases);
			result.add("defaultSteps", writeSteps(value.defaultSteps(), depth + 1));
		}
		return result;
	}

	private static JsonObject writeCondition(MacroCondition condition, int depth) {
		JsonObject result = new JsonObject();
		if (condition == null || depth > MAX_DEPTH) {
			result.addProperty("type", "always");
			result.addProperty("expected", true);
			return result;
		}
		if (condition instanceof MacroCondition.Always value) {
			result.addProperty("type", "always");
			result.addProperty("expected", value.expected());
		} else if (condition instanceof MacroCondition.Screen value) {
			result.addProperty("type", "screen");
			result.addProperty("title", value.title());
			result.addProperty("mustBeOpen", value.mustBeOpen());
			result.addProperty("contains", value.contains());
		} else if (condition instanceof MacroCondition.Slot value) {
			result.addProperty("type", "slot");
			result.addProperty("slotId", value.slotId());
			result.addProperty("mustExist", value.mustExist());
		} else if (condition instanceof MacroCondition.Item value) {
			result.addProperty("type", "item");
			result.addProperty("name", value.name());
			result.addProperty("mustExist", value.mustExist());
			result.addProperty("contains", value.contains());
			result.addProperty("scope", value.scope().serializedName());
		} else if (condition instanceof MacroCondition.Chat value) {
			result.addProperty("type", "chat");
			result.addProperty("text", value.text());
			result.addProperty("contains", value.contains());
		} else if (condition instanceof MacroCondition.World value) {
			result.addProperty("type", "world");
			result.addProperty("mustBeInWorld", value.mustBeInWorld());
		} else if (condition instanceof MacroCondition.All value) {
			result.addProperty("type", "all");
			result.add("children", writeConditions(value.children(), depth + 1));
		} else if (condition instanceof MacroCondition.Any value) {
			result.addProperty("type", "any");
			result.add("children", writeConditions(value.children(), depth + 1));
		} else if (condition instanceof MacroCondition.Not value) {
			result.addProperty("type", "not");
			result.add("child", writeCondition(value.child(), depth + 1));
		} else if (condition instanceof MacroCondition.Variable value) {
			result.addProperty("type", "variable");
			result.addProperty("name", value.name());
			if (value.globalVariableId() != null) result.addProperty("globalVariableId", value.globalVariableId());
			result.addProperty("operator", value.operator().name());
			result.add("value", writeValue(value.value()));
		} else if (condition instanceof MacroCondition.Hypixel value) {
			result.addProperty("type", "hypixel");
			result.addProperty("field", value.field().name());
			result.addProperty("operator", value.operator().name());
			result.addProperty("expected", value.expected());
			result.addProperty("argument", value.argument());
			result.addProperty("plotId", value.plotId());
		}
		return result;
	}

	private static JsonArray writeConditions(List<MacroCondition> source, int depth) {
		JsonArray result = new JsonArray();
		if (depth > MAX_DEPTH || source == null) return result;
		for (MacroCondition condition : source) {
			if (result.size() >= MAX_STEPS) break;
			result.add(writeCondition(condition, depth));
		}
		return result;
	}

	private static MacroStep readStep(JsonObject object, int depth) {
		if (depth > MAX_DEPTH) return new MacroStep.Unknown(object, true);
		MacroStep result = switch (string(object, "type", "")) {
			case "command" -> readCommandAsMessage(object);
			case "chat" -> new MacroStep.Chat(string(object, "message", ""));
			case "title" -> readTitle(object);
			case "sound" -> new MacroStep.Sound(string(object, "soundId", "minecraft:entity.player.levelup"));
			case "wait" -> {
				int min = integer(object, "minMillis", 0);
				MacroStep.Wait wait = new MacroStep.Wait(min, integer(object, "maxMillis", min));
				if ("CONDITION".equalsIgnoreCase(string(object, "mode", "DURATION"))) {
					wait.setMode(MacroStep.Wait.Mode.CONDITION);
					wait.setCondition(readCondition(object.get("condition"), depth + 1));
				}
				yield wait;
			}
			case "comment" -> new MacroStep.Comment(string(object, "text", "Comment"));
			case "stop_run" -> new MacroStep.StopRun();
			case "scroll" -> readScroll(object);
			case "inventory_click" -> readInventoryClick(object);
			case "update_variable" -> readUpdateVariable(object);
			case "key" -> {
				int legacy = integer(object, "holdMillis", 250);
				int minimum = integer(object, "holdMinMillis", legacy);
				int maximum = integer(object, "holdMaxMillis", minimum);
				MacroStep.Key key = new MacroStep.Key(string(object, "key", ""),
					bool(object, "hold", false), minimum, maximum);
				key.setInputMode(enumValue(MacroStep.Key.InputMode.class,
					string(object, "inputMode", "KEYBOARD"), MacroStep.Key.InputMode.KEYBOARD));
				key.setMouseButton(enumValue(MacroStep.MouseButton.Button.class,
					string(object, "mouseButton", "LEFT"), MacroStep.MouseButton.Button.LEFT));
				key.setHotbarSlot(integer(object, "hotbarSlot", 1));
				yield key;
			}
			case "click_slot" -> readLegacyClickSlot(object);
			case "click_item" -> readLegacyClickItem(object);
			case "close_screen" -> new MacroStep.CloseScreen();
			case "select_hotbar_slot" -> readHotbarAsInput(object);
			case "mouse_button" -> readMouseAsInput(object);
			case "block_player_input" -> new MacroStep.BlockPlayerInput(integer(object, "durationMillis", 1_000));
			case "start_block_player_input" -> new MacroStep.StartBlockPlayerInput();
			case "stop_block_player_input" -> new MacroStep.StopBlockPlayerInput();
			case "set_variable" -> readLegacySetVariable(object);
			case "change_variable" -> readLegacyChangeVariable(object);
			case "function_call" -> readFunctionCall(object);
			case "macro_call" -> readMacroCall(object, depth + 1);
			case "world_switch" -> new MacroStep.WorldSwitch(selectableIsland(string(object, "island", Island.HUB.name())));
			case "wait_until" -> new MacroStep.WaitUntil(readCondition(object.get("condition"), depth + 1));
			case "if" -> readIf(object, depth + 1);
			case "repeat" -> readRepeat(object, depth + 1);
			case "repeat_until" -> readRepeatUntil(object, depth + 1);
			case "switch" -> readSwitch(object, depth + 1);
			default -> new MacroStep.Unknown(object);
		};
		if (result != null) {
			result.setDelay(integer(object, "delayMin", 0), integer(object, "delayMax", 0));
			if (result instanceof MacroStep.Base base) {
				base.setEditorPosition(decimal(object, "editorX", 0), decimal(object, "editorY", 0));
			}
		}
		return result;
	}

	private static MacroStep.Title readTitle(JsonObject object) {
		MacroStep.Title title = new MacroStep.Title();
		title.setText(string(object, "text", "TITLE"));
		title.setFont(string(object, "font", "minecraft:default"));
		title.setScale(decimal(object, "scale", 2.0f));
		title.setTextColor(integer(object, "textColor", 0xFFFFFFFF));
		title.setShowBackground(bool(object, "showBackground", false));
		title.setBackgroundColor(integer(object, "backgroundColor", 0xFF000000));
		title.setBackgroundOpacity(integer(object, "backgroundOpacity", 160));
		title.setFadeInMillis(integer(object, "fadeInMillis", 200));
		title.setHoldMillis(integer(object, "holdMillis", 2_000));
		title.setFadeOutMillis(integer(object, "fadeOutMillis", 300));
		return title;
	}

	private static MacroStep.IfElse readIf(JsonObject object, int depth) {
		MacroStep.IfElse result = new MacroStep.IfElse(readCondition(object.get("condition"), depth));
		addSteps(result.thenSteps(), array(object, "thenSteps"), depth);
		addSteps(result.elseSteps(), array(object, "elseSteps"), depth);
		result.setElseEnabled(object.has("elseEnabled")
			? bool(object, "elseEnabled", false) : !result.elseSteps().isEmpty());
		return result;
	}

	private static MacroStep.Repeat readRepeat(JsonObject object, int depth) {
		MacroStep.Repeat result = new MacroStep.Repeat(bool(object, "forever", false), integer(object, "count", 1));
		String savedMode = string(object, "mode", "");
		if (!savedMode.isBlank()) {
			result.setMode(enumValue(MacroStep.Repeat.Mode.class, savedMode, MacroStep.Repeat.Mode.COUNT));
		} else if (bool(object, "forever", false)) {
			result.setMode(MacroStep.Repeat.Mode.FOREVER);
		}
		if (result.mode() == MacroStep.Repeat.Mode.UNTIL) {
			result.setCondition(readCondition(object.get("condition"), depth + 1));
		}
		addSteps(result.steps(), array(object, "steps"), depth);
		return result;
	}

	private static MacroStep.Repeat readRepeatUntil(JsonObject object, int depth) {
		MacroStep.Repeat result = new MacroStep.Repeat(false, 1);
		result.setMode(MacroStep.Repeat.Mode.UNTIL);
		result.setCondition(readCondition(object.get("condition"), depth));
		JsonArray children = array(object, "untilSteps");
		if (children == null) children = array(object, "steps");
		addSteps(result.steps(), children, depth);
		return result;
	}

	private static MacroStep.Chat readCommandAsMessage(JsonObject object) {
		String command = string(object, "command", "").strip();
		if (!command.isEmpty() && !command.startsWith("/")) command = "/" + command;
		return new MacroStep.Chat(command);
	}

	private static MacroStep.Key readMouseAsInput(JsonObject object) {
		MacroStep.MouseButton.Button button = enumValue(MacroStep.MouseButton.Button.class,
			string(object, "button", "LEFT"), MacroStep.MouseButton.Button.LEFT);
		int millis = integer(object, "holdMillis", 250);
		MacroStep.Key key = new MacroStep.Key("", bool(object, "hold", false), millis);
		key.setInputMode(MacroStep.Key.InputMode.MOUSE);
		key.setMouseButton(button);
		return key;
	}

	private static MacroStep.Key readHotbarAsInput(JsonObject object) {
		MacroStep.Key key = new MacroStep.Key("", false, 250);
		key.setInputMode(MacroStep.Key.InputMode.HOTBAR);
		key.setHotbarSlot(integer(object, "slot", 1));
		return key;
	}

	private static MacroStep.InventoryClick readLegacyClickSlot(JsonObject object) {
		MacroStep.InventoryClick result = new MacroStep.InventoryClick();
		result.setTarget(MacroStep.InventoryClick.Target.SLOT);
		result.setSlotId(integer(object, "slotId", 0));
		result.setButton(integer(object, "button", 0));
		result.setShift(bool(object, "shift", false));
		return result;
	}

	private static MacroStep.InventoryClick readLegacyClickItem(JsonObject object) {
		MacroStep.InventoryClick result = new MacroStep.InventoryClick();
		result.setTarget(MacroStep.InventoryClick.Target.ITEM);
		result.setName(string(object, "name", ""));
		result.setContains(bool(object, "contains", false));
		result.setScope(string(object, "scope", "container"));
		result.setOccurrence(integer(object, "occurrence", 0));
		result.setButton(integer(object, "button", 0));
		result.setShift(bool(object, "shift", false));
		return result;
	}

	private static MacroStep.UpdateVariable readLegacySetVariable(JsonObject object) {
		MacroValue value = readValue(object.get("value"));
		if (!object.has("value") || !object.get("value").isJsonObject()) {
			MacroValue.Type type = valueType(string(object, "valueType", "TEXT"));
			value = MacroValue.literal(type, "");
		} else {
			MacroValue.Type legacyType = valueType(string(object, "valueType", value.type().name()));
			value = new MacroValue(legacyType, value.variableReference(), value.value(), value.scope());
		}
		MacroStep.UpdateVariable result = new MacroStep.UpdateVariable();
		result.setOperation(MacroStep.UpdateVariable.Operation.SET);
		result.setName(string(object, "name", "value"));
		result.setGlobalVariableId(string(object, "globalVariableId", null));
		result.setValue(value);
		return result;
	}

	private static MacroStep.UpdateVariable readLegacyChangeVariable(JsonObject object) {
		MacroStep.UpdateVariable result = new MacroStep.UpdateVariable();
		result.setOperation(MacroStep.UpdateVariable.Operation.ADD);
		result.setName(string(object, "name", "value"));
		result.setGlobalVariableId(string(object, "globalVariableId", null));
		result.setAmount(decimal(object, "amount", 1));
		return result;
	}

	private static MacroStep.Switch readSwitch(JsonObject object, int depth) {
		MacroStep.Switch result = new MacroStep.Switch();
		result.setName(string(object, "name", "value"));
		result.setGlobalVariableId(string(object, "globalVariableId", null));
		result.cases().clear();
		JsonArray cases = array(object, "cases");
		if (cases != null) for (JsonElement entry : cases) {
			if (result.cases().size() >= 16) break;
			if (entry == null || !entry.isJsonObject()) continue;
			JsonObject item = entry.getAsJsonObject();
			MacroStep.SwitchCase branch = new MacroStep.SwitchCase(string(item, "value", ""));
			addSteps(branch.steps(), array(item, "steps"), depth);
			result.cases().add(branch);
		}
		if (result.cases().isEmpty()) result.addCase();
		addSteps(result.defaultSteps(), array(object, "defaultSteps"), depth);
		return result;
	}

	private static MacroStep.Scroll readScroll(JsonObject object) {
		MacroStep.Scroll.Direction direction;
		try { direction = MacroStep.Scroll.Direction.valueOf(string(object, "direction", "DOWN")); }
		catch (IllegalArgumentException ignored) { direction = MacroStep.Scroll.Direction.DOWN; }
		return new MacroStep.Scroll(direction, integer(object, "amount", 1));
	}

	private static MacroStep.InventoryClick readInventoryClick(JsonObject object) {
		MacroStep.InventoryClick result = new MacroStep.InventoryClick();
		try { result.setTarget(MacroStep.InventoryClick.Target.valueOf(string(object, "target", "SLOT"))); }
		catch (IllegalArgumentException ignored) { result.setTarget(MacroStep.InventoryClick.Target.SLOT); }
		result.setSlotId(integer(object, "slotId", 0));
		result.setName(string(object, "name", ""));
		result.setContains(bool(object, "contains", false));
		result.setScope(string(object, "scope", "container"));
		result.setOccurrence(integer(object, "occurrence", 0));
		result.setButton(integer(object, "button", 0));
		result.setShift(bool(object, "shift", false));
		return result;
	}

	private static MacroStep.UpdateVariable readUpdateVariable(JsonObject object) {
		MacroStep.UpdateVariable result = new MacroStep.UpdateVariable();
		try { result.setOperation(MacroStep.UpdateVariable.Operation.valueOf(string(object, "operation", "SET"))); }
		catch (IllegalArgumentException ignored) { result.setOperation(MacroStep.UpdateVariable.Operation.SET); }
		result.setName(string(object, "name", "value"));
		result.setGlobalVariableId(string(object, "globalVariableId", null));
		result.setAmount(decimal(object, "amount", 1));
		result.setValue(readValue(object.get("value")));
		return result;
	}

	private static void addSteps(List<MacroStep> target, JsonArray source, int depth) {
		if (source == null) return;
		for (JsonElement entry : source) {
			if (entry != null && entry.isJsonObject()) {
				MacroStep step = readStep(entry.getAsJsonObject(), depth);
				if (step != null) target.add(step);
			}
		}
	}

	/**
	 * A Macro Call carries a condition only when one was set.
	 *
	 * <p>Kept separate from the other condition-bearing steps: an absent condition means "always
	 * call", not "the default condition", so it has to stay null through a round trip and can never
	 * turn an old unconditional call into a gated one.
	 */
	private static MacroStep.MacroCall readMacroCall(JsonObject object, int depth) {
		MacroStep.MacroCall call = new MacroStep.MacroCall(integer(object, "macroId", 0));
		if (object.has("condition")) call.setCondition(readCondition(object.get("condition"), depth));
		return call;
	}

	private static MacroCondition readCondition(JsonElement element, int depth) {
		if (depth > MAX_DEPTH || element == null || !element.isJsonObject()) return new MacroCondition.Always(true);
		JsonObject object = element.getAsJsonObject();
		return switch (string(object, "type", "always")) {
			case "always" -> new MacroCondition.Always(bool(object, "expected", true));
			case "screen" -> new MacroCondition.Screen(string(object, "title", ""),
				bool(object, "mustBeOpen", true), bool(object, "contains", false));
			case "slot" -> new MacroCondition.Slot(integer(object, "slotId", 0), bool(object, "mustExist", true));
			case "item" -> new MacroCondition.Item(string(object, "name", ""), bool(object, "mustExist", true),
				bool(object, "contains", false), readItemScope(object, false));
			case "chat" -> new MacroCondition.Chat(string(object, "text", ""), bool(object, "contains", false));
			case "world" -> new MacroCondition.World(bool(object, "mustBeInWorld", true));
			case "all" -> new MacroCondition.All(readConditions(array(object, "children"), depth + 1));
			case "any" -> new MacroCondition.Any(readConditions(array(object, "children"), depth + 1));
			case "not" -> new MacroCondition.Not(readCondition(object.get("child"), depth + 1));
			case "variable" -> new MacroCondition.Variable(string(object, "name", "value"),
				variableOperator(string(object, "operator", "EQUALS")), readValue(object.get("value")),
				string(object, "globalVariableId", null));
			case "hypixel" -> readHypixelCondition(object);
			default -> new MacroCondition.Always(true);
		};
	}

	private static JsonObject writeValue(MacroValue value) {
		JsonObject result = new JsonObject();
		if (value == null) value = MacroValue.literal(MacroValue.Type.TEXT, "");
		result.addProperty("type", value.type().name());
		result.addProperty("variable", value.variableReference());
		result.addProperty("scope", value.scope().name());
		result.addProperty("value", value.value());
		return result;
	}

	private static MacroValue readValue(JsonElement element) {
		if (element == null || !element.isJsonObject()) return MacroValue.literal(MacroValue.Type.TEXT, "");
		JsonObject object = element.getAsJsonObject();
		boolean variable = bool(object, "variable", false);
		return new MacroValue(valueType(string(object, "type", "TEXT")), variable,
			string(object, "value", ""), valueScope(string(object, "scope", variable ? "LOCAL" : "NONE")));
	}

	private static MacroCondition.Hypixel readHypixelCondition(JsonObject object) {
		MacroCondition.Hypixel.Field field;
		MacroCondition.Hypixel.Operator operator;
		try { field = MacroCondition.Hypixel.Field.valueOf(string(object, "field", "ISLAND")); }
		catch (IllegalArgumentException ignored) { field = MacroCondition.Hypixel.Field.ISLAND; }
		try { operator = MacroCondition.Hypixel.Operator.valueOf(string(object, "operator", "EQUALS")); }
		catch (IllegalArgumentException ignored) { operator = MacroCondition.Hypixel.Operator.EQUALS; }
		return new MacroCondition.Hypixel(field, operator, string(object, "expected", ""),
			string(object, "argument", ""), integer(object, "plotId", -1));
	}

	private static MacroStep readSetVariable(JsonObject object) {
		MacroValue value = readValue(object.get("value"));
		MacroValue.Type type = valueType(string(object, "valueType", value.type().name()));
		return new MacroStep.SetVariable(string(object, "name", "value"), type, value,
			string(object, "globalVariableId", null));
	}

	private static MacroStep readFunctionCall(JsonObject object) {
		MacroStep.FunctionCall result = new MacroStep.FunctionCall(string(object, "functionId", ""));
		JsonArray arguments = array(object, "arguments");
		if (arguments != null) for (JsonElement value : arguments) {
			if (result.arguments().size() >= 32) break;
			result.arguments().add(readValue(value));
		}
		return result;
	}

	private static MacroStep readMouseButton(JsonObject object) {
		MacroStep.MouseButton.Button button;
		try { button = MacroStep.MouseButton.Button.valueOf(string(object, "button", "LEFT")); }
		catch (IllegalArgumentException ignored) { button = MacroStep.MouseButton.Button.LEFT; }
		return new MacroStep.MouseButton(button, bool(object, "hold", false), integer(object, "holdMillis", 250));
	}

	private static MacroValue.Type valueType(String value) {
		try { return MacroValue.Type.valueOf(value == null ? "TEXT" : value.toUpperCase(Locale.ROOT)); }
		catch (IllegalArgumentException ignored) { return MacroValue.Type.TEXT; }
	}

	private static MacroValue.Scope valueScope(String value) {
		try { return MacroValue.Scope.valueOf(value == null ? "NONE" : value.toUpperCase(Locale.ROOT)); }
		catch (IllegalArgumentException ignored) { return MacroValue.Scope.LOCAL; }
	}

	private static MacroCondition.Variable.Operator variableOperator(String value) {
		try { return MacroCondition.Variable.Operator.valueOf(value == null ? "EQUALS" : value.toUpperCase(Locale.ROOT)); }
		catch (IllegalArgumentException ignored) { return MacroCondition.Variable.Operator.EQUALS; }
	}

	private static MacroCondition.ItemScope readItemScope(JsonObject object, boolean missingLegacyDefault) {
		if (object.has("scope")) {
			MacroCondition.ItemScope parsed = MacroCondition.ItemScope.fromSerialized(
				string(object, "scope", ""), null);
			if (parsed != null) return parsed;
		}
		return MacroCondition.ItemScope.fromLegacy(bool(object, "includePlayerInventory", missingLegacyDefault));
	}

	private static List<MacroCondition> readConditions(JsonArray source, int depth) {
		List<MacroCondition> result = new ArrayList<>();
		if (source == null || depth > MAX_DEPTH) return result;
		for (JsonElement entry : source) {
			if (result.size() >= MAX_STEPS) break;
			result.add(readCondition(entry, depth));
		}
		return result;
	}

	private static Island selectableIsland(String value) {
		if (value == null) return Island.HUB;
		try {
			Island island = Island.valueOf(value.trim().toUpperCase(Locale.ROOT));
			return island.selectable() ? island : Island.HUB;
		} catch (IllegalArgumentException ignored) {
			for (Island island : Island.values()) {
				if (island.selectable() && island.label().equalsIgnoreCase(value.trim())) return island;
			}
			return Island.HUB;
		}
	}

	private static JsonArray array(JsonObject object, String name) {
		JsonElement value = object.get(name);
		return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
	}

	private static String string(JsonObject object, String name, String fallback) {
		JsonElement value = object.get(name);
		if (value == null || !value.isJsonPrimitive()) return fallback;
		try { return value.getAsString(); } catch (RuntimeException ignored) { return fallback; }
	}

	private static int integer(JsonObject object, String name, int fallback) {
		JsonElement value = object.get(name);
		if (value == null || !value.isJsonPrimitive()) return fallback;
		try { return value.getAsInt(); } catch (RuntimeException ignored) { return fallback; }
	}

	private static double decimal(JsonObject object, String name, double fallback) {
		JsonElement value = object.get(name);
		if (value == null || !value.isJsonPrimitive()) return fallback;
		try { return value.getAsDouble(); } catch (RuntimeException ignored) { return fallback; }
	}

	private static float decimal(JsonObject object, String name, float fallback) {
		JsonElement value = object.get(name);
		if (value == null || !value.isJsonPrimitive()) return fallback;
		try { return value.getAsFloat(); } catch (RuntimeException ignored) { return fallback; }
	}

	private static boolean bool(JsonObject object, String name, boolean fallback) {
		JsonElement value = object.get(name);
		if (value == null || !value.isJsonPrimitive()) return fallback;
		try { return value.getAsBoolean(); } catch (RuntimeException ignored) { return fallback; }
	}

	private static <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback) {
		if (value == null) return fallback;
		try { return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT)); }
		catch (IllegalArgumentException ignored) { return fallback; }
	}
}
