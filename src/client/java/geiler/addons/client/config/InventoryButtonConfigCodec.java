package geiler.addons.client.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import geiler.addons.client.module.impl.InventoryButtonPlacement;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

/** Small local-layout codec. A missing array is the migration path for pre-button configs. */
public final class InventoryButtonConfigCodec {
	private InventoryButtonConfigCodec() { }

	public static JsonArray encode(List<InventoryButtonPlacement> placements) {
		JsonArray result = new JsonArray();
		if (placements == null) return result;
		for (InventoryButtonPlacement placement : placements) {
			if (placement == null) continue;
			JsonObject item = new JsonObject();
			item.addProperty("id", placement.id());
			item.addProperty("macroId", placement.macroId());
			item.addProperty("gridX", placement.gridX());
			item.addProperty("gridY", placement.gridY());
			item.addProperty("slotAligned", placement.slotAligned());
			item.addProperty("appearance", placement.appearance().name());
			item.addProperty("value", placement.value());
			item.addProperty("hoverTooltip", placement.hoverTooltip());
			result.add(item);
		}
		return result;
	}

	/** Unknown/deleted macro ids survive as unassigned buttons; unsafe PNG paths fall back to a registered item. */
	public static List<InventoryButtonPlacement> decode(JsonArray data, IntPredicate macroExists,
		Predicate<String> pngNameAllowed) {
		if (data == null || data.isEmpty()) return List.of();
		Set<Integer> ids = new HashSet<>();
		List<InventoryButtonPlacement> result = new ArrayList<>();
		for (JsonElement element : data) {
			if (!element.isJsonObject()) continue;
			JsonObject saved = element.getAsJsonObject();
			int id = integer(saved, "id", -1);
			if (id < 0 || !ids.add(id)) continue;
			InventoryButtonPlacement placement = new InventoryButtonPlacement(id);
			int macroId = integer(saved, "macroId", -1);
			if (macroId >= 0 && (macroExists == null || !macroExists.test(macroId))) macroId = -1;
			placement.setMacroId(macroId);
			placement.setGrid(integer(saved, "gridX", 0), integer(saved, "gridY", 0));
			// Pre-lattice layouts used the inventory origin itself as cell zero; retain those pixel positions.
			placement.setSlotAligned(bool(saved, "slotAligned", false));
			InventoryButtonPlacement.Appearance appearance = appearance(saved);
			String value = string(saved, "value", appearance == InventoryButtonPlacement.Appearance.ITEM
				? "minecraft:stone" : "");
			if (appearance == InventoryButtonPlacement.Appearance.PNG
				&& (pngNameAllowed == null || !pngNameAllowed.test(value))) {
				appearance = InventoryButtonPlacement.Appearance.ITEM;
				value = "minecraft:stone";
			}
			placement.setAppearance(appearance, value);
			placement.setHoverTooltip(string(saved, "hoverTooltip", ""));
			result.add(placement);
		}
		return List.copyOf(result);
	}

	private static InventoryButtonPlacement.Appearance appearance(JsonObject saved) {
		try {
			return InventoryButtonPlacement.Appearance.valueOf(string(saved, "appearance", "ITEM"));
		} catch (IllegalArgumentException ignored) {
			return InventoryButtonPlacement.Appearance.ITEM;
		}
	}

	private static int integer(JsonObject object, String name, int fallback) {
		try {
			JsonElement value = object.get(name);
			return value == null || !value.isJsonPrimitive() ? fallback : value.getAsInt();
		} catch (RuntimeException ignored) {
			return fallback;
		}
	}

	private static String string(JsonObject object, String name, String fallback) {
		try {
			JsonElement value = object.get(name);
			return value == null || !value.isJsonPrimitive() ? fallback : value.getAsString();
		} catch (RuntimeException ignored) {
			return fallback;
		}
	}

	private static boolean bool(JsonObject object, String name, boolean fallback) {
		try {
			JsonElement value = object.get(name);
			return value == null || !value.isJsonPrimitive() ? fallback : value.getAsBoolean();
		} catch (RuntimeException ignored) {
			return fallback;
		}
	}
}
