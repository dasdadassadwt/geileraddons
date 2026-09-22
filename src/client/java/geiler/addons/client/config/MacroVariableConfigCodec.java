package geiler.addons.client.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import geiler.addons.client.macro.MacroValue;
import geiler.addons.client.macro.MacroVariableStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Bounded local-config codec for global variable definitions and opted-in values. */
public final class MacroVariableConfigCodec {
	private MacroVariableConfigCodec() { }

	public static JsonArray encode(List<MacroVariableStore.SavedVariable> variables) {
		JsonArray result = new JsonArray();
		if (variables == null) return result;
		for (MacroVariableStore.SavedVariable saved : variables) {
			if (result.size() >= MacroVariableStore.MAX_VARIABLES) break;
			if (saved == null || saved.definition() == null) continue;
			MacroVariableStore.Definition definition = saved.definition();
			JsonObject object = new JsonObject();
			object.addProperty("id", definition.id());
			object.addProperty("name", definition.name());
			object.addProperty("type", definition.type().name());
			object.addProperty("persistValue", definition.persistValue());
			if (definition.persistValue() && saved.savedValue() != null) object.addProperty("savedValue", saved.savedValue());
			result.add(object);
		}
		return result;
	}

	public static List<MacroVariableStore.SavedVariable> decode(JsonArray source) {
		List<MacroVariableStore.SavedVariable> result = new ArrayList<>();
		if (source == null) return result;
		for (JsonElement entry : source) {
			if (result.size() >= MacroVariableStore.MAX_VARIABLES) break;
			if (entry == null || !entry.isJsonObject()) continue;
			JsonObject object = entry.getAsJsonObject();
			String id = string(object, "id", "");
			String name = string(object, "name", "");
			if (id.isBlank() || id.length() > 64 || name.isBlank()) continue;
			MacroVariableStore.Definition definition = new MacroVariableStore.Definition(id, name,
				parseType(string(object, "type", "TEXT")), bool(object, "persistValue", false));
			String value = definition.persistValue() && object.has("savedValue")
				? string(object, "savedValue", "") : null;
			result.add(new MacroVariableStore.SavedVariable(definition, value));
		}
		return result;
	}

	private static MacroValue.Type parseType(String value) {
		try { return MacroValue.Type.valueOf(value.toUpperCase(Locale.ROOT)); }
		catch (RuntimeException ignored) { return MacroValue.Type.TEXT; }
	}

	private static String string(JsonObject object, String name, String fallback) {
		JsonElement value = object.get(name);
		if (value == null || !value.isJsonPrimitive()) return fallback;
		try { return value.getAsString(); } catch (RuntimeException ignored) { return fallback; }
	}

	private static boolean bool(JsonObject object, String name, boolean fallback) {
		JsonElement value = object.get(name);
		if (value == null || !value.isJsonPrimitive()) return fallback;
		try { return value.getAsBoolean(); } catch (RuntimeException ignored) { return fallback; }
	}
}
