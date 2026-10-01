package geiler.addons.client.macro;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import geiler.addons.client.config.MacroScriptConfigCodec;
import geiler.addons.client.config.MacroStepConfigCodec;
import geiler.addons.client.location.Island;
import geiler.addons.client.module.ModuleKeybind;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

/** Safe, versioned clipboard representation for one or more user-authored macros. */
public final class MacroTransfer {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final String FORMAT = "geileraddons-macros";
	/** 7 changes macro canvas zoom to a 1%-100% range. */
	private static final int VERSION = 7;
	private static final int MAX_PAYLOAD_LENGTH = 512_000;
	private static final int MAX_MACROS = 64;
	public static final int MAX_FUNCTIONS = MacroFunction.MAX_FUNCTIONS;
	private static final int MAX_STEPS = 512;
	private static final int MAX_DEPTH = MacroTreeRules.MAX_DEPTH;
	private static final int MAX_TEXT_LENGTH = 4_096;

	private MacroTransfer() {
	}

	/** Encodes the selected macros without their internal ids, so imports never overwrite existing data. */
	public static String encode(Iterable<MacroDefinition> source) {
		return encode(source, List.of());
	}

	public static String encode(Iterable<MacroDefinition> source, Iterable<MacroFunction> functions) {
		return encode(source, functions, List.of());
	}

	/** Encodes only the referenced global variable definitions, never their runtime values. */
	public static String encode(Iterable<MacroDefinition> source, Iterable<MacroFunction> functions,
		Iterable<MacroVariableStore.Definition> globalVariables) {
		JsonObject root = new JsonObject();
		root.addProperty("format", FORMAT);
		root.addProperty("version", VERSION);
		JsonArray macros = new JsonArray();
		if (source != null) {
			for (MacroDefinition macro : source) {
				if (macro == null) continue;
				if (macros.size() >= MAX_MACROS) throw new IllegalArgumentException(
					"A macro package can contain at most " + MAX_MACROS + " macros.");
				macros.add(writeMacro(macro));
			}
		}
		root.add("macros", macros);
		JsonArray functionEntries = new JsonArray();
		if (functions != null) for (MacroFunction function : functions) {
			if (function == null) continue;
			if (functionEntries.size() >= MAX_FUNCTIONS) throw new IllegalArgumentException(
				"A macro package can contain at most " + MAX_FUNCTIONS + " reusable functions.");
			functionEntries.add(writeFunction(function));
		}
		root.add("functions", functionEntries);
		JsonArray globalEntries = new JsonArray();
		if (globalVariables != null) for (MacroVariableStore.Definition definition : globalVariables) {
			if (globalEntries.size() >= MacroVariableStore.MAX_VARIABLES) break;
			if (definition == null) continue;
			JsonObject value = new JsonObject();
			value.addProperty("id", limited(definition.id(), 64));
			value.addProperty("name", limited(definition.name(), 32));
			value.addProperty("type", definition.type().name());
			globalEntries.add(value);
		}
		root.add("globalVariables", globalEntries);
		return GSON.toJson(root);
	}

	/**
	 * Decodes clipboard data and assigns fresh ids starting at {@code firstId}. Invalid input never
	 * changes module state; callers can show the returned error directly to the user.
	 */
	public static ImportResult decode(String payload, int firstId) {
		if (payload == null || payload.isBlank()) return failure("Clipboard is empty.");
		if (payload.length() > MAX_PAYLOAD_LENGTH) return failure("Clipboard data is too large.");
		try {
			JsonElement parsed = JsonParser.parseString(payload);
			if (!parsed.isJsonObject()) return failure("Clipboard data is not a macro package.");
			JsonObject root = parsed.getAsJsonObject();
			if (!FORMAT.equals(string(root, "format", ""))) return failure("Unknown macro package format.");
			int version = integer(root, "version", -1);
			if (version < 1 || version > VERSION) return failure("Unsupported macro package version.");
			JsonArray entries = array(root, "macros");
			if (entries == null || entries.isEmpty()) return failure("The macro package contains no macros.");

			List<MacroDefinition> result = new ArrayList<>();
			Map<Integer, Integer> macroIds = new HashMap<>();
			for (JsonElement entry : entries) {
				if (result.size() >= MAX_MACROS) break;
				if (!entry.isJsonObject()) continue;
				JsonObject object = entry.getAsJsonObject();
				int newId = firstId + result.size();
				MacroDefinition macro = readMacro(object, newId, version);
				if (macro != null) {
					int oldId = integer(object, "id", -1);
					if (oldId >= 0) macroIds.put(oldId, newId);
					result.add(macro);
				}
			}
			if (result.isEmpty()) return failure("The macro package contains no valid macros.");
			List<MacroFunction> importedFunctions = new ArrayList<>();
			Map<String, String> functionIds = new HashMap<>();
			JsonArray functionSource = array(root, "functions");
			if (functionSource != null) {
				if (functionSource.size() > MAX_FUNCTIONS) {
					return failure("The macro package has more than " + MAX_FUNCTIONS + " reusable functions.");
				}
				for (JsonElement entry : functionSource) {
					if (entry == null || !entry.isJsonObject()) continue;
					FunctionImport functionImport = readFunction(entry.getAsJsonObject());
					if (functionImport == null) continue;
					importedFunctions.add(functionImport.function());
					functionIds.put(functionImport.sourceId(), functionImport.function().id());
				}
			}
			for (MacroDefinition macro : result) for (MacroScript script : macro.scripts()) {
				remapReferences(script.steps(), macroIds, functionIds, 0);
			}
			for (MacroDefinition macro : result) remapReferences(macro.detachedBlocks(), macroIds, functionIds, 0);
			for (MacroFunction function : importedFunctions) remapReferences(function.steps(), macroIds, functionIds, 0);
			List<MacroVariableStore.Definition> importedGlobals = readGlobalVariables(
				version >= 4 ? array(root, "globalVariables") : null);
			return new ImportResult(List.copyOf(result), List.copyOf(importedFunctions),
				List.copyOf(importedGlobals), null);
		} catch (RuntimeException invalid) {
			return failure("The clipboard does not contain valid macro data.");
		}
	}

	private static ImportResult failure(String message) {
		return new ImportResult(List.of(), List.of(), List.of(), message);
	}

	private static List<MacroVariableStore.Definition> readGlobalVariables(JsonArray source) {
		List<MacroVariableStore.Definition> result = new ArrayList<>();
		if (source == null) return result;
		for (JsonElement entry : source) {
			if (result.size() >= MacroVariableStore.MAX_VARIABLES) break;
			if (entry == null || !entry.isJsonObject()) continue;
			JsonObject object = entry.getAsJsonObject();
			String id = string(object, "id", "");
			String name = limited(string(object, "name", ""), 32);
			if (id.isBlank() || id.length() > 64 || name.isBlank()) continue;
			result.add(new MacroVariableStore.Definition(id, name,
				valueType(string(object, "type", "TEXT")), false));
		}
		return result;
	}

	private static JsonObject writeMacro(MacroDefinition macro) {
		JsonObject result = new JsonObject();
		result.addProperty("id", macro.id());
		result.addProperty("name", macro.name());
		result.addProperty("enabled", macro.enabled());
		if (macro.keybind().isBound()) {
			result.addProperty("key", macro.keybind().key().getName());
			result.addProperty("modifiers", macro.keybind().modifiers());
		}
		result.addProperty("triggerContext", macro.triggerContext().name());
		result.addProperty("islandRestricted", macro.islandRestricted());
		JsonArray islands = new JsonArray();
		for (Island island : macro.islands()) islands.add(island.name());
		result.add("islands", islands);
		result.add("steps", MacroStepConfigCodec.encode(macro.steps()));
		result.add("scripts", MacroScriptConfigCodec.encode(macro.scripts()));
		result.add("detachedBlocks", MacroStepConfigCodec.encode(macro.detachedBlocks()));
		result.addProperty("canvasPanX", macro.canvasPanX());
		result.addProperty("canvasPanY", macro.canvasPanY());
		result.addProperty("canvasZoom", macro.canvasZoom());
		result.addProperty("canvasZoomVersion", 2);
		return result;
	}

	private static JsonObject writeFunction(MacroFunction function) {
		JsonObject result = new JsonObject();
		result.addProperty("id", function.id());
		result.addProperty("name", function.name());
		result.addProperty("canvasX", function.canvasX());
		result.addProperty("canvasY", function.canvasY());
		JsonArray parameters = new JsonArray();
		for (MacroFunction.Parameter parameter : function.parameters()) {
			if (parameters.size() >= 32) break;
			JsonObject value = new JsonObject();
			value.addProperty("name", parameter.name());
			value.addProperty("type", parameter.type().name());
			value.addProperty("defaultValue", parameter.defaultValue());
			parameters.add(value);
		}
		result.add("parameters", parameters);
		result.add("steps", MacroStepConfigCodec.encode(function.steps()));
		return result;
	}

	private static JsonObject writeStep(MacroStep step, int depth) {
		if (step instanceof MacroStep.Unknown unknown) return unknown.serializedData();
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
			result.addProperty("mode", value.mode().name());
			if (value.mode() == MacroStep.Wait.Mode.CONDITION) {
				result.add("condition", writeCondition(value.condition(), depth + 1));
			}
		}
		if (step instanceof MacroStep.Comment value) result.addProperty("text", limited(value.text(), MAX_TEXT_LENGTH));
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
		if (step instanceof MacroStep.Switch value) {
			result.addProperty("name", value.name());
			if (value.globalVariableId() != null) result.addProperty("globalVariableId", value.globalVariableId());
			JsonArray cases = new JsonArray();
			for (MacroStep.SwitchCase branch : value.cases()) {
				if (cases.size() >= 16) break;
				JsonObject item = new JsonObject();
				item.addProperty("value", limited(branch.value(), MAX_TEXT_LENGTH));
				item.add("steps", writeSteps(branch.steps(), depth + 1));
				cases.add(item);
			}
			result.add("cases", cases);
			result.add("defaultSteps", writeSteps(value.defaultSteps(), depth + 1));
		}
		return result;
	}

	private static JsonArray writeSteps(List<MacroStep> source, int depth) {
		JsonArray result = new JsonArray();
		if (source == null) return result;
		for (MacroStep step : source) {
			if (step == null) continue;
			if (result.size() >= MAX_STEPS) throw new IllegalStateException("Macro package contains too many steps");
			if (depth > MAX_DEPTH && !(step instanceof MacroStep.Unknown)) {
				throw new IllegalStateException("Macro package contains a step beyond the supported nesting depth");
			}
			result.add(writeStep(step, depth));
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
			result.addProperty("title", limited(value.title(), MAX_TEXT_LENGTH));
			result.addProperty("mustBeOpen", value.mustBeOpen());
			result.addProperty("contains", value.contains());
		} else if (condition instanceof MacroCondition.Slot value) {
			result.addProperty("type", "slot");
			result.addProperty("slotId", value.slotId());
			result.addProperty("mustExist", value.mustExist());
		} else if (condition instanceof MacroCondition.Item value) {
			result.addProperty("type", "item");
			result.addProperty("name", limited(value.name(), MAX_TEXT_LENGTH));
			result.addProperty("mustExist", value.mustExist());
			result.addProperty("contains", value.contains());
			result.addProperty("scope", value.scope().serializedName());
		} else if (condition instanceof MacroCondition.Chat value) {
			result.addProperty("type", "chat");
			result.addProperty("text", limited(value.text(), MAX_TEXT_LENGTH));
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

	private static MacroDefinition readMacro(JsonObject object, int id, int version) {
		MacroDefinition result = new MacroDefinition(id);
		result.setName(limited(string(object, "name", result.name()), 48));
		result.setEnabled(booleanValue(object, "enabled", true));
		result.setTriggerContext(triggerContext(string(object, "triggerContext", MacroTriggerContext.ANY_NON_TEXT_SCREEN.name())));
		result.setIslandRestricted(booleanValue(object, "islandRestricted", false));
		result.setIslands(readIslands(array(object, "islands")));
		String key = string(object, "key", "");
		if (!key.isBlank()) {
			try {
				result.setKeybind(new ModuleKeybind(InputConstants.getKey(key), integer(object, "modifiers", 0)));
			} catch (RuntimeException ignored) {
				// A bad shared key is safer as unbound than as an import failure.
			}
		}
		if (version >= 3 && array(object, "scripts") != null) {
			result.restoreScripts(MacroScriptConfigCodec.decode(array(object, "scripts")));
		} else {
			JsonArray steps = array(object, "steps");
			if (steps != null) {
				for (JsonElement entry : steps) {
					if (result.steps().size() >= MAX_STEPS) {
						throw new IllegalArgumentException("Macro package contains too many steps");
					}
					if (entry.isJsonObject()) {
						MacroStep step = readStep(entry.getAsJsonObject(), 0);
						if (step != null) result.steps().add(step);
					}
				}
			}
		}
		result.restoreDetachedBlocks(MacroStepConfigCodec.decode(array(object, "detachedBlocks")));
		float canvasZoom = (float) decimal(object, "canvasZoom", 1);
		int canvasZoomVersion = integer(object, "canvasZoomVersion", 0);
		if (canvasZoomVersion < 1) canvasZoom *= 100.0f;
		if (canvasZoomVersion < 2) canvasZoom *= 100.0f;
		result.setCanvasView((float) decimal(object, "canvasPanX", 0),
			(float) decimal(object, "canvasPanY", 0), canvasZoom);
		return result;
	}

	private static FunctionImport readFunction(JsonObject object) {
		String sourceId = string(object, "id", "");
		MacroFunction function = new MacroFunction();
		function.setName(limited(string(object, "name", "My Block"), 48));
		function.setCanvasPosition((float) decimal(object, "canvasX", 0), (float) decimal(object, "canvasY", 0));
		JsonArray parameters = array(object, "parameters");
		if (parameters != null) for (JsonElement entry : parameters) {
			if (function.parameters().size() >= 32) break;
			if (entry == null || !entry.isJsonObject()) continue;
			JsonObject value = entry.getAsJsonObject();
			function.parameters().add(new MacroFunction.Parameter(string(value, "name", "value"),
				valueType(string(value, "type", "TEXT")), limited(string(value, "defaultValue", ""), 128)));
		}
		function.steps().addAll(MacroStepConfigCodec.decode(array(object, "steps")));
		return new FunctionImport(sourceId, function);
	}

	private static void remapReferences(List<MacroStep> steps, Map<Integer, Integer> macroIds,
		Map<String, String> functionIds, int depth) {
		if (steps == null || depth > MAX_DEPTH) return;
		for (MacroStep step : steps) {
			if (step instanceof MacroStep.MacroCall call) call.setMacroId(macroIds.getOrDefault(call.macroId(), -1));
			else if (step instanceof MacroStep.FunctionCall call) call.setFunctionId(functionIds.getOrDefault(call.functionId(), ""));
			else if (step instanceof MacroStep.IfElse branch) {
				remapReferences(branch.thenSteps(), macroIds, functionIds, depth + 1);
				remapReferences(branch.elseSteps(), macroIds, functionIds, depth + 1);
			} else if (step instanceof MacroStep.Repeat repeat) {
				remapReferences(repeat.steps(), macroIds, functionIds, depth + 1);
			} else if (step instanceof MacroStep.RepeatUntil repeatUntil) {
				remapReferences(repeatUntil.steps(), macroIds, functionIds, depth + 1);
			} else if (step instanceof MacroStep.Switch value) {
				for (MacroStep.SwitchCase branch : value.cases()) {
					remapReferences(branch.steps(), macroIds, functionIds, depth + 1);
				}
				remapReferences(value.defaultSteps(), macroIds, functionIds, depth + 1);
			}
		}
	}

	private static MacroValue.Type valueType(String raw) {
		try { return MacroValue.Type.valueOf(raw == null ? "TEXT" : raw.toUpperCase(java.util.Locale.ROOT)); }
		catch (IllegalArgumentException ignored) { return MacroValue.Type.TEXT; }
	}

	private static JsonObject writeValue(MacroValue value) {
		if (value == null) value = MacroValue.literal(MacroValue.Type.TEXT, "");
		JsonObject result = new JsonObject();
		result.addProperty("type", value.type().name());
		result.addProperty("variable", value.variableReference());
		result.addProperty("scope", value.scope().name());
		result.addProperty("value", limited(value.value(), MAX_TEXT_LENGTH));
		return result;
	}

	private static MacroValue readValue(JsonElement element) {
		if (element == null || !element.isJsonObject()) return MacroValue.literal(MacroValue.Type.TEXT, "");
		JsonObject object = element.getAsJsonObject();
		boolean variable = booleanValue(object, "variable", false);
		MacroValue.Scope scope;
		try {
			scope = MacroValue.Scope.valueOf(string(object, "scope", variable ? "LOCAL" : "NONE"));
		} catch (IllegalArgumentException ignored) {
			scope = variable ? MacroValue.Scope.LOCAL : MacroValue.Scope.NONE;
		}
		return new MacroValue(valueType(string(object, "type", "TEXT")), variable,
			limited(string(object, "value", ""), MAX_TEXT_LENGTH), scope);
	}

	private static MacroStep readStep(JsonObject object, int depth) {
		if (depth > MAX_DEPTH) return new MacroStep.Unknown(object, true);
		String type = string(object, "type", "");
		MacroStep result = switch (type) {
			case "command" -> readCommandAsMessage(object);
			case "chat" -> new MacroStep.Chat(limited(string(object, "message", ""), MAX_TEXT_LENGTH));
			case "title" -> readTitle(object);
			case "sound" -> new MacroStep.Sound(string(object, "soundId", "minecraft:entity.player.levelup"));
			case "wait" -> readWait(object, depth);
			case "comment" -> new MacroStep.Comment(limited(string(object, "text", "Comment"), MAX_TEXT_LENGTH));
			case "stop_run" -> new MacroStep.StopRun();
			case "scroll" -> readScroll(object);
			case "inventory_click" -> readInventoryClick(object);
			case "update_variable" -> readUpdateVariable(object);
			case "key" -> {
				int legacy = integer(object, "holdMillis", 250);
				int minimum = integer(object, "holdMinMillis", legacy);
				int maximum = integer(object, "holdMaxMillis", minimum);
				yield new MacroStep.Key(string(object, "key", "key.keyboard.space"),
					booleanValue(object, "hold", false), minimum, maximum);
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

	private static MacroStep.Chat readCommandAsMessage(JsonObject object) {
		String command = string(object, "command", "").strip();
		if (!command.isEmpty() && !command.startsWith("/")) command = "/" + command;
		return new MacroStep.Chat(command);
	}

	private static MacroStep.Key readHotbarAsInput(JsonObject object) {
		MacroStep.Key key = new MacroStep.Key("", false, 250);
		key.setInputMode(MacroStep.Key.InputMode.HOTBAR);
		key.setHotbarSlot(integer(object, "slot", 1));
		return key;
	}

	private static MacroStep.Key readMouseAsInput(JsonObject object) {
		MacroStep.MouseButton.Button button;
		try { button = MacroStep.MouseButton.Button.valueOf(string(object, "button", "LEFT")); }
		catch (IllegalArgumentException ignored) { button = MacroStep.MouseButton.Button.LEFT; }
		int millis = integer(object, "holdMillis", 250);
		MacroStep.Key key = new MacroStep.Key("", booleanValue(object, "hold", false), millis);
		key.setInputMode(MacroStep.Key.InputMode.MOUSE);
		key.setMouseButton(button);
		return key;
	}

	private static MacroStep.InventoryClick readLegacyClickSlot(JsonObject object) {
		MacroStep.InventoryClick result = new MacroStep.InventoryClick();
		result.setTarget(MacroStep.InventoryClick.Target.SLOT);
		result.setSlotId(integer(object, "slotId", 0));
		result.setButton(integer(object, "button", 0));
		result.setShift(booleanValue(object, "shift", false));
		return result;
	}

	private static MacroStep.InventoryClick readLegacyClickItem(JsonObject object) {
		MacroStep.InventoryClick result = new MacroStep.InventoryClick();
		result.setTarget(MacroStep.InventoryClick.Target.ITEM);
		result.setName(string(object, "name", ""));
		result.setContains(booleanValue(object, "contains", true));
		result.setScope(string(object, "scope", "container"));
		result.setOccurrence(integer(object, "occurrence", 0));
		result.setButton(integer(object, "button", 0));
		result.setShift(booleanValue(object, "shift", false));
		return result;
	}

	private static MacroStep.UpdateVariable readLegacySetVariable(JsonObject object) {
		MacroValue value = readValue(object.get("value"));
		if (!object.has("value") || !object.get("value").isJsonObject()) {
			value = MacroValue.literal(valueType(string(object, "valueType", "TEXT")), "");
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

	private static MacroStep.FunctionCall readFunctionCall(JsonObject object) {
		MacroStep.FunctionCall result = new MacroStep.FunctionCall(string(object, "functionId", ""));
		JsonArray arguments = array(object, "arguments");
		if (arguments != null) for (JsonElement value : arguments) {
			if (result.arguments().size() >= 32) break;
			result.arguments().add(readValue(value));
		}
		return result;
	}

	private static MacroStep.MacroCall readMacroCall(JsonObject object, int depth) {
		MacroStep.MacroCall result = new MacroStep.MacroCall(integer(object, "macroId", 0));
		if (object.has("condition")) result.setCondition(readCondition(object.get("condition"), depth));
		return result;
	}

	private static MacroStep.Title readTitle(JsonObject object) {
		MacroStep.Title title = new MacroStep.Title();
		title.setText(limited(string(object, "text", "TITLE"), MAX_TEXT_LENGTH));
		title.setFont(string(object, "font", "minecraft:default"));
		title.setScale(decimal(object, "scale", 2.0f));
		title.setTextColor(integer(object, "textColor", 0xFFFFFFFF));
		title.setShowBackground(booleanValue(object, "showBackground", false));
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
		MacroStep.Repeat result = new MacroStep.Repeat(booleanValue(object, "forever", false), integer(object, "count", 1));
		addSteps(result.steps(), array(object, "steps"), depth);
		return result;
	}

	private static MacroStep.Repeat readRepeatUntil(JsonObject object, int depth) {
		MacroStep.Repeat result = new MacroStep.Repeat(false, 1);
		result.setMode(MacroStep.Repeat.Mode.UNTIL);
		result.setCondition(readCondition(object.get("condition"), depth));
		JsonArray steps = array(object, "untilSteps");
		if (steps == null) steps = array(object, "steps");
		addSteps(result.steps(), steps, depth);
		return result;
	}

	private static MacroStep.Wait readWait(JsonObject object, int depth) {
		MacroStep.Wait result = new MacroStep.Wait(integer(object, "minMillis", 0), integer(object, "maxMillis", 0));
		if ("CONDITION".equalsIgnoreCase(string(object, "mode", "DURATION"))) {
			result.setMode(MacroStep.Wait.Mode.CONDITION);
			result.setCondition(readCondition(object.get("condition"), depth + 1));
		}
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
			MacroStep.SwitchCase branch = new MacroStep.SwitchCase(
				limited(string(item, "value", ""), MAX_TEXT_LENGTH));
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
		result.setContains(booleanValue(object, "contains", false));
		result.setScope(string(object, "scope", "container"));
		result.setOccurrence(integer(object, "occurrence", 0));
		result.setButton(integer(object, "button", 0));
		result.setShift(booleanValue(object, "shift", false));
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
			if (target.size() >= MAX_STEPS) throw new IllegalArgumentException("Macro package contains too many nested steps");
			if (entry.isJsonObject()) {
				MacroStep step = readStep(entry.getAsJsonObject(), depth);
				if (step != null) target.add(step);
			}
		}
	}

	private static MacroCondition readCondition(JsonElement element, int depth) {
		if (depth > MAX_DEPTH || element == null || !element.isJsonObject()) return new MacroCondition.Always(true);
		JsonObject object = element.getAsJsonObject();
		return switch (string(object, "type", "always")) {
			case "always" -> new MacroCondition.Always(booleanValue(object, "expected", true));
			case "screen" -> new MacroCondition.Screen(limited(string(object, "title", ""), MAX_TEXT_LENGTH),
				booleanValue(object, "mustBeOpen", true), booleanValue(object, "contains", false));
			case "slot" -> new MacroCondition.Slot(integer(object, "slotId", 0), booleanValue(object, "mustExist", true));
			case "item" -> new MacroCondition.Item(limited(string(object, "name", ""), MAX_TEXT_LENGTH),
				booleanValue(object, "mustExist", true), booleanValue(object, "contains", false),
				readItemScope(object, true));
			case "chat" -> new MacroCondition.Chat(limited(string(object, "text", ""), MAX_TEXT_LENGTH),
				booleanValue(object, "contains", false));
			case "world" -> new MacroCondition.World(booleanValue(object, "mustBeInWorld", true));
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

	private static MacroCondition.ItemScope readItemScope(JsonObject object, boolean missingLegacyDefault) {
		if (object.has("scope")) {
			MacroCondition.ItemScope parsed = MacroCondition.ItemScope.fromSerialized(
				string(object, "scope", ""), null);
			if (parsed != null) return parsed;
		}
		return MacroCondition.ItemScope.fromLegacy(booleanValue(object, "includePlayerInventory", missingLegacyDefault));
	}

	private static MacroCondition.Hypixel readHypixelCondition(JsonObject object) {
		MacroCondition.Hypixel.Field field;
		MacroCondition.Hypixel.Operator operator;
		try { field = MacroCondition.Hypixel.Field.valueOf(string(object, "field", "ISLAND")); }
		catch (IllegalArgumentException ignored) { field = MacroCondition.Hypixel.Field.ISLAND; }
		try { operator = MacroCondition.Hypixel.Operator.valueOf(string(object, "operator", "EQUALS")); }
		catch (IllegalArgumentException ignored) { operator = MacroCondition.Hypixel.Operator.EQUALS; }
		return new MacroCondition.Hypixel(field, operator, limited(string(object, "expected", ""), 128),
			limited(string(object, "argument", ""), 32), integer(object, "plotId", -1));
	}

	private static MacroCondition.Variable.Operator variableOperator(String raw) {
		try { return MacroCondition.Variable.Operator.valueOf(raw == null ? "EQUALS" : raw.toUpperCase(java.util.Locale.ROOT)); }
		catch (IllegalArgumentException ignored) { return MacroCondition.Variable.Operator.EQUALS; }
	}

	private static List<MacroCondition> readConditions(JsonArray source, int depth) {
		if (source == null || depth > MAX_DEPTH) return List.of();
		List<MacroCondition> result = new ArrayList<>();
		for (JsonElement entry : source) {
			if (result.size() >= MAX_STEPS) break;
			result.add(readCondition(entry, depth));
		}
		return result;
	}

	private static List<Island> readIslands(JsonArray source) {
		if (source == null) return List.of();
		EnumSet<Island> result = EnumSet.noneOf(Island.class);
		for (JsonElement entry : source) {
			try {
				Island island = Island.valueOf(entry.getAsString());
				if (island.selectable()) result.add(island);
			} catch (RuntimeException ignored) {
				// Unknown islands are ignored so imports survive newer/older island lists.
			}
		}
		return List.copyOf(result);
	}

	private static MacroTriggerContext triggerContext(String value) {
		try {
			return MacroTriggerContext.valueOf(value);
		} catch (IllegalArgumentException ignored) {
			return MacroTriggerContext.ANY_NON_TEXT_SCREEN;
		}
	}

	private static Island selectableIsland(String value) {
		try {
			Island island = Island.valueOf(value);
			return island.selectable() ? island : Island.HUB;
		} catch (IllegalArgumentException ignored) {
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
		try {
			return value.getAsString();
		} catch (RuntimeException ignored) {
			return fallback;
		}
	}

	private static int integer(JsonObject object, String name, int fallback) {
		JsonElement value = object.get(name);
		if (value == null || !value.isJsonPrimitive()) return fallback;
		try {
			return value.getAsInt();
		} catch (RuntimeException ignored) {
			return fallback;
		}
	}

	private static float decimal(JsonObject object, String name, float fallback) {
		JsonElement value = object.get(name);
		if (value == null || !value.isJsonPrimitive()) return fallback;
		try { return value.getAsFloat(); } catch (RuntimeException ignored) { return fallback; }
	}

	private static boolean booleanValue(JsonObject object, String name, boolean fallback) {
		JsonElement value = object.get(name);
		if (value == null || !value.isJsonPrimitive()) return fallback;
		try {
			return value.getAsBoolean();
		} catch (RuntimeException ignored) {
			return fallback;
		}
	}

	private static String limited(String value, int max) {
		if (value == null) return "";
		return value.length() <= max ? value : value.substring(0, max);
	}

	private record FunctionImport(String sourceId, MacroFunction function) { }

	public record ImportResult(List<MacroDefinition> macros, List<MacroFunction> functions,
		List<MacroVariableStore.Definition> globalVariables, String error) {
		public ImportResult {
			macros = List.copyOf(macros == null ? List.of() : macros);
			functions = List.copyOf(functions == null ? List.of() : functions);
			globalVariables = List.copyOf(globalVariables == null ? List.of() : globalVariables);
		}
		public ImportResult(List<MacroDefinition> macros, List<MacroFunction> functions, String error) {
			this(macros, functions, List.of(), error);
		}
		public ImportResult(List<MacroDefinition> macros, String error) { this(macros, List.of(), List.of(), error); }

		public boolean success() {
			return error == null && !macros.isEmpty();
		}
	}
}
