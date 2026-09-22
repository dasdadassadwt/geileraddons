package geiler.addons.client.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import geiler.addons.client.macro.MacroScript;
import geiler.addons.client.macro.MacroStep;
import geiler.addons.client.macro.MacroWorldRegion;
import geiler.addons.client.module.ModuleKeybind;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Bounded config codec for macro event stacks, workspace positions, and world trigger settings. */
public final class MacroScriptConfigCodec {
	private static final int MAX_SCRIPTS = 32;

	private MacroScriptConfigCodec() { }

	public static JsonArray encode(List<MacroScript> scripts) {
		JsonArray result = new JsonArray();
		if (scripts == null) return result;
		for (MacroScript script : scripts) {
			if (result.size() >= MAX_SCRIPTS) break;
			if (script == null) continue;
			JsonObject object = new JsonObject();
			object.addProperty("id", script.id());
			object.addProperty("trigger", script.trigger().name());
			object.addProperty("key", script.keybind().isBound() ? script.keybind().key().getName() : "");
			object.addProperty("modifiers", script.keybind().modifiers());
			object.addProperty("canvasX", script.canvasX());
			object.addProperty("canvasY", script.canvasY());
			object.add("steps", MacroStepConfigCodec.encode(script.steps()));
			object.add("worldRegion", encodeRegion(script.worldRegion()));
			result.add(object);
		}
		return result;
	}

	public static List<MacroScript> decode(JsonArray source) {
		List<MacroScript> result = new ArrayList<>();
		if (source == null) return result;
		for (JsonElement entry : source) {
			if (result.size() >= MAX_SCRIPTS) break;
			if (entry == null || !entry.isJsonObject()) continue;
			JsonObject object = entry.getAsJsonObject();
			MacroScript script = new MacroScript(string(object, "id", ""), trigger(string(object, "trigger", "KEY_PRESS")));
			String key = string(object, "key", "");
			if (!key.isBlank()) {
				try { script.setKeybind(new ModuleKeybind(InputConstants.getKey(key), integer(object, "modifiers", 0))); }
				catch (RuntimeException ignored) { script.setKeybind(ModuleKeybind.NONE); }
			}
			script.setCanvasPosition((float) decimal(object, "canvasX", 0), (float) decimal(object, "canvasY", 0));
			script.steps().addAll(MacroStepConfigCodec.decode(array(object, "steps")));
			script.setWorldRegion(decodeRegion(object.get("worldRegion")));
			result.add(script);
		}
		return result;
	}

	private static JsonObject encodeRegion(MacroWorldRegion region) {
		JsonObject object = new JsonObject();
		if (region == null) region = new MacroWorldRegion();
		object.addProperty("placed", region.placed());
		object.addProperty("worldKey", region.worldKey());
		object.addProperty("x", region.x());
		object.addProperty("y", region.y());
		object.addProperty("z", region.z());
		object.addProperty("shape", region.shape().name());
		object.addProperty("size", region.size());
		object.addProperty("innerSize", region.innerSize());
		object.addProperty("yTolerance", region.yTolerance());
		object.addProperty("repeatDelayMillis", region.repeatDelayMillis());
		object.addProperty("oncePerWorld", region.oncePerWorld());
		object.addProperty("color", region.color());
		object.addProperty("fillOpacity", region.fillOpacity());
		object.addProperty("lineWidth", region.lineWidth());
		return object;
	}

	private static MacroWorldRegion decodeRegion(JsonElement element) {
		MacroWorldRegion region = new MacroWorldRegion();
		if (element == null || !element.isJsonObject()) return region;
		JsonObject object = element.getAsJsonObject();
		String worldKey = string(object, "worldKey", "");
		region.restorePlacement(bool(object, "placed", false), worldKey,
			decimal(object, "x", 0), decimal(object, "y", 0), decimal(object, "z", 0));
		region.setShape(shape(string(object, "shape", "CIRCLE")));
		region.setSize(decimal(object, "size", 8));
		region.setInnerSize(decimal(object, "innerSize", 4));
		region.setYTolerance(decimal(object, "yTolerance", 3));
		region.setRepeatDelayMillis(integer(object, "repeatDelayMillis", 1_000));
		region.setOncePerWorld(bool(object, "oncePerWorld", false));
		region.setColor(integer(object, "color", 0xFF43D9C4));
		region.setFillOpacity(integer(object, "fillOpacity", 22));
		region.setLineWidth((float) decimal(object, "lineWidth", 2.5));
		return region;
	}

	private static MacroScript.Trigger trigger(String raw) {
		try { return MacroScript.Trigger.valueOf(raw.toUpperCase(Locale.ROOT)); }
		catch (IllegalArgumentException ignored) { return MacroScript.Trigger.KEY_PRESS; }
	}
	private static MacroWorldRegion.Shape shape(String raw) {
		try { return MacroWorldRegion.Shape.valueOf(raw.toUpperCase(Locale.ROOT)); }
		catch (IllegalArgumentException ignored) { return MacroWorldRegion.Shape.CIRCLE; }
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
	private static boolean bool(JsonObject object, String name, boolean fallback) {
		JsonElement value = object.get(name);
		if (value == null || !value.isJsonPrimitive()) return fallback;
		try { return value.getAsBoolean(); } catch (RuntimeException ignored) { return fallback; }
	}
}
