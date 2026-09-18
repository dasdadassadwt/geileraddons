package geiler.addons.client.macro;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import geiler.addons.client.location.Island;
import geiler.addons.client.module.ModuleKeybind;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/** Safe, versioned clipboard representation for one or more user-authored macros. */
public final class MacroTransfer {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final String FORMAT = "geileraddons-macros";
	private static final int VERSION = 1;
	private static final int MAX_PAYLOAD_LENGTH = 512_000;
	private static final int MAX_MACROS = 64;
	private static final int MAX_STEPS = 512;
	private static final int MAX_DEPTH = 8;
	private static final int MAX_TEXT_LENGTH = 4_096;

	private MacroTransfer() {
	}

	/** Encodes the selected macros without their internal ids, so imports never overwrite existing data. */
	public static String encode(Iterable<MacroDefinition> source) {
		JsonObject root = new JsonObject();
		root.addProperty("format", FORMAT);
		root.addProperty("version", VERSION);
		JsonArray macros = new JsonArray();
		if (source != null) {
			for (MacroDefinition macro : source) {
				if (macro == null || macros.size() >= MAX_MACROS) break;
				macros.add(writeMacro(macro));
			}
		}
		root.add("macros", macros);
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
			if (integer(root, "version", -1) != VERSION) return failure("Unsupported macro package version.");
			JsonArray entries = array(root, "macros");
			if (entries == null || entries.isEmpty()) return failure("The macro package contains no macros.");

			List<MacroDefinition> result = new ArrayList<>();
			for (JsonElement entry : entries) {
				if (result.size() >= MAX_MACROS) break;
				if (!entry.isJsonObject()) continue;
				MacroDefinition macro = readMacro(entry.getAsJsonObject(), firstId + result.size());
				if (macro != null) result.add(macro);
			}
			if (result.isEmpty()) return failure("The macro package contains no valid macros.");
			return new ImportResult(List.copyOf(result), null);
		} catch (RuntimeException invalid) {
			return failure("The clipboard does not contain valid macro data.");
		}
	}

	private static ImportResult failure(String message) {
		return new ImportResult(List.of(), message);
	}

	private static JsonObject writeMacro(MacroDefinition macro) {
		JsonObject result = new JsonObject();
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
		JsonArray steps = new JsonArray();
		for (MacroStep step : macro.steps()) {
			if (steps.size() >= MAX_STEPS) break;
			if (step != null) steps.add(writeStep(step, 0));
		}
		result.add("steps", steps);
		return result;
	}

	private static JsonObject writeStep(MacroStep step, int depth) {
		JsonObject result = new JsonObject();
		result.addProperty("type", step.type());
		result.addProperty("delayMin", step.delayMin());
		result.addProperty("delayMax", step.delayMax());
		if (step instanceof MacroStep.Command value) result.addProperty("command", value.command());
		if (step instanceof MacroStep.Chat value) result.addProperty("message", value.message());
		if (step instanceof MacroStep.Wait value) {
			result.addProperty("minMillis", value.minMillis());
			result.addProperty("maxMillis", value.maxMillis());
		}
		if (step instanceof MacroStep.Key value) {
			result.addProperty("key", value.key());
			result.addProperty("hold", value.hold());
			result.addProperty("holdMillis", value.holdMillis());
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
		return result;
	}

	private static JsonArray writeSteps(List<MacroStep> source, int depth) {
		JsonArray result = new JsonArray();
		if (depth > MAX_DEPTH || source == null) return result;
		for (MacroStep step : source) {
			if (result.size() >= MAX_STEPS) break;
			if (step != null) result.add(writeStep(step, depth));
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
			result.addProperty("includePlayerInventory", value.includePlayerInventory());
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

	private static MacroDefinition readMacro(JsonObject object, int id) {
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
		JsonArray steps = array(object, "steps");
		if (steps != null) {
			for (JsonElement entry : steps) {
				if (result.steps().size() >= MAX_STEPS) break;
				if (entry.isJsonObject()) {
					MacroStep step = readStep(entry.getAsJsonObject(), 0);
					if (step != null) result.steps().add(step);
				}
			}
		}
		return result;
	}

	private static MacroStep readStep(JsonObject object, int depth) {
		if (depth > MAX_DEPTH) return null;
		String type = string(object, "type", "");
		MacroStep result = switch (type) {
			case "command" -> new MacroStep.Command(limited(string(object, "command", ""), MAX_TEXT_LENGTH));
			case "chat" -> new MacroStep.Chat(limited(string(object, "message", ""), MAX_TEXT_LENGTH));
			case "wait" -> new MacroStep.Wait(integer(object, "minMillis", 0), integer(object, "maxMillis", 0));
			case "key" -> new MacroStep.Key(string(object, "key", "key.keyboard.space"),
				booleanValue(object, "hold", false), integer(object, "holdMillis", 250));
			case "click_slot" -> new MacroStep.ClickSlot(integer(object, "slotId", 0),
				integer(object, "button", 0), booleanValue(object, "shift", false));
			case "click_item" -> new MacroStep.ClickItem(limited(string(object, "name", ""), MAX_TEXT_LENGTH),
				booleanValue(object, "contains", true), string(object, "scope", "container"),
				integer(object, "occurrence", 0), integer(object, "button", 0),
				booleanValue(object, "shift", false));
			case "close_screen" -> new MacroStep.CloseScreen();
			case "world_switch" -> new MacroStep.WorldSwitch(selectableIsland(string(object, "island", Island.HUB.name())));
			case "wait_until" -> new MacroStep.WaitUntil(readCondition(object.get("condition"), depth + 1));
			case "if" -> readIf(object, depth + 1);
			case "repeat" -> readRepeat(object, depth + 1);
			default -> null;
		};
		if (result != null) result.setDelay(integer(object, "delayMin", 0), integer(object, "delayMax", 0));
		return result;
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

	private static void addSteps(List<MacroStep> target, JsonArray source, int depth) {
		if (source == null || depth > MAX_DEPTH) return;
		for (JsonElement entry : source) {
			if (target.size() >= MAX_STEPS) break;
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
				booleanValue(object, "includePlayerInventory", true));
			case "chat" -> new MacroCondition.Chat(limited(string(object, "text", ""), MAX_TEXT_LENGTH),
				booleanValue(object, "contains", false));
			case "world" -> new MacroCondition.World(booleanValue(object, "mustBeInWorld", true));
			case "all" -> new MacroCondition.All(readConditions(array(object, "children"), depth + 1));
			case "any" -> new MacroCondition.Any(readConditions(array(object, "children"), depth + 1));
			case "not" -> new MacroCondition.Not(readCondition(object.get("child"), depth + 1));
			default -> new MacroCondition.Always(true);
		};
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

	public record ImportResult(List<MacroDefinition> macros, String error) {
		public ImportResult {
			macros = List.copyOf(macros == null ? List.of() : macros);
		}

		public boolean success() {
			return error == null && !macros.isEmpty();
		}
	}
}
