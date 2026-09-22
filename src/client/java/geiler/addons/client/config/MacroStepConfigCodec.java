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
			if (result.size() >= MAX_STEPS) break;
			if (entry != null && entry.isJsonObject()) {
				MacroStep step = readStep(entry.getAsJsonObject(), 0);
				if (step != null) result.add(step);
			}
		}
		return result;
	}

	private static JsonArray writeSteps(List<MacroStep> source, int depth) {
		JsonArray result = new JsonArray();
		if (source == null || depth > MAX_DEPTH) return result;
		for (MacroStep step : source) {
			if (result.size() >= MAX_STEPS) break;
			if (step != null) result.add(writeStep(step, depth));
		}
		return result;
	}

	private static JsonObject writeStep(MacroStep step, int depth) {
		JsonObject result = new JsonObject();
		result.addProperty("type", step.type());
		result.addProperty("delayMin", step.delayMin());
		result.addProperty("delayMax", step.delayMax());
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
		}
		if (step instanceof MacroStep.Key value) {
			result.addProperty("key", value.key());
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
		if (step instanceof MacroStep.MacroCall value) result.addProperty("macroId", value.macroId());
		if (step instanceof MacroStep.WorldSwitch value) result.addProperty("island", value.target().name());
		if (step instanceof MacroStep.WaitUntil value) result.add("condition", writeCondition(value.condition(), depth + 1));
		if (step instanceof MacroStep.IfElse value) {
			result.add("condition", writeCondition(value.condition(), depth + 1));
			result.add("thenSteps", writeSteps(value.thenSteps(), depth + 1));
			result.add("elseSteps", writeSteps(value.elseSteps(), depth + 1));
		}
		if (step instanceof MacroStep.Repeat value) {
			result.addProperty("forever", value.forever());
			result.addProperty("count", value.count());
			result.add("steps", writeSteps(value.steps(), depth + 1));
		}
		if (step instanceof MacroStep.RepeatUntil value) {
			result.add("condition", writeCondition(value.condition(), depth + 1));
			result.add("untilSteps", writeSteps(value.steps(), depth + 1));
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
		if (depth > MAX_DEPTH) return null;
		MacroStep result = switch (string(object, "type", "")) {
			case "command" -> new MacroStep.Command(string(object, "command", ""));
			case "chat" -> new MacroStep.Chat(string(object, "message", ""));
			case "title" -> readTitle(object);
			case "sound" -> new MacroStep.Sound(string(object, "soundId", "minecraft:entity.player.levelup"));
			case "wait" -> {
				int min = integer(object, "minMillis", 0);
				yield new MacroStep.Wait(min, integer(object, "maxMillis", min));
			}
			case "key" -> {
				int legacy = integer(object, "holdMillis", 250);
				int minimum = integer(object, "holdMinMillis", legacy);
				int maximum = integer(object, "holdMaxMillis", minimum);
				yield new MacroStep.Key(string(object, "key", ""),
					bool(object, "hold", false), minimum, maximum);
			}
			case "click_slot" -> new MacroStep.ClickSlot(integer(object, "slotId", 0),
				integer(object, "button", 0), bool(object, "shift", false));
			case "click_item" -> new MacroStep.ClickItem(string(object, "name", ""),
				bool(object, "contains", false), string(object, "scope", "container"),
				integer(object, "occurrence", 0), integer(object, "button", 0), bool(object, "shift", false));
			case "close_screen" -> new MacroStep.CloseScreen();
			case "select_hotbar_slot" -> new MacroStep.SelectHotbarSlot(integer(object, "slot", 1));
			case "mouse_button" -> readMouseButton(object);
			case "block_player_input" -> new MacroStep.BlockPlayerInput(integer(object, "durationMillis", 1_000));
			case "start_block_player_input" -> new MacroStep.StartBlockPlayerInput();
			case "stop_block_player_input" -> new MacroStep.StopBlockPlayerInput();
			case "set_variable" -> readSetVariable(object);
			case "change_variable" -> new MacroStep.ChangeVariable(string(object, "name", "value"),
				decimal(object, "amount", 1), string(object, "globalVariableId", null));
			case "function_call" -> readFunctionCall(object);
			case "macro_call" -> new MacroStep.MacroCall(integer(object, "macroId", 0));
			case "world_switch" -> new MacroStep.WorldSwitch(selectableIsland(string(object, "island", Island.HUB.name())));
			case "wait_until" -> new MacroStep.WaitUntil(readCondition(object.get("condition"), depth + 1));
			case "if" -> readIf(object, depth + 1);
			case "repeat" -> readRepeat(object, depth + 1);
			case "repeat_until" -> readRepeatUntil(object, depth + 1);
			default -> null;
		};
		if (result != null) result.setDelay(integer(object, "delayMin", 0), integer(object, "delayMax", 0));
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
		return result;
	}

	private static MacroStep.Repeat readRepeat(JsonObject object, int depth) {
		MacroStep.Repeat result = new MacroStep.Repeat(bool(object, "forever", false), integer(object, "count", 1));
		addSteps(result.steps(), array(object, "steps"), depth);
		return result;
	}

	private static MacroStep.RepeatUntil readRepeatUntil(JsonObject object, int depth) {
		MacroStep.RepeatUntil result = new MacroStep.RepeatUntil(readCondition(object.get("condition"), depth));
		JsonArray children = array(object, "untilSteps");
		if (children == null) children = array(object, "steps");
		addSteps(result.steps(), children, depth);
		return result;
	}

	private static void addSteps(List<MacroStep> target, JsonArray source, int depth) {
		if (source == null || depth > MAX_DEPTH) return;
		for (JsonElement entry : source) {
			if (target.size() >= MAX_STEPS) break;
			if (entry != null && entry.isJsonObject()) {
				MacroStep step = readStep(entry.getAsJsonObject(), depth);
				if (step != null) target.add(step);
			}
		}
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
}
