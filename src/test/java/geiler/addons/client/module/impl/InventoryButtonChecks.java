package geiler.addons.client.module.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import geiler.addons.client.config.InventoryButtonConfigCodec;

import java.util.ArrayList;
import java.util.List;

/** Offline checks for inventory placement migration, bounds, reflow, and macro eligibility. */
public final class InventoryButtonChecks {
	private InventoryButtonChecks() { }

	public static void run() {
		check(InventoryButtonConfigCodec.decode(null, id -> true, name -> true).isEmpty(),
			"configs predating Inventory Buttons migrate to an empty local layout");
		InventoryButtonPlacement first = new InventoryButtonPlacement(4);
		first.setMacroId(17);
		first.setGrid(11, 2);
		first.setAppearance(InventoryButtonPlacement.Appearance.TEXT, "Craft");
		first.setHoverTooltip("Crafting utility");
		List<InventoryButtonPlacement> original = List.of(first);
		JsonArray encoded = InventoryButtonConfigCodec.encode(original);
		List<InventoryButtonPlacement> restored = InventoryButtonConfigCodec.decode(encoded,
			id -> id == 17, name -> name.equals("icon.png"));
		check(restored.size() == 1, "button config round-trips one placement");
		check(restored.getFirst().macroId() == 17 && restored.getFirst().gridX() == 11
			&& restored.getFirst().gridY() == 2, "button stores stable macro id and inventory-relative grid cell");
		check(restored.getFirst().appearance() == InventoryButtonPlacement.Appearance.TEXT
			&& restored.getFirst().value().equals("Craft"), "button config round-trips text appearance");
		check(restored.getFirst().hoverTooltip().equals("Crafting utility"),
			"button config round-trips its optional custom hover tooltip");
		check(restored.getFirst().slotAligned(), "new placements keep the vanilla-slot grid anchor across saves");

		JsonObject stale = new JsonObject();
		stale.addProperty("id", 5);
		stale.addProperty("macroId", 99);
		stale.addProperty("gridX", 0);
		stale.addProperty("gridY", 0);
		stale.addProperty("appearance", "ITEM");
		stale.addProperty("value", "minecraft:stone");
		JsonArray staleConfig = new JsonArray();
		staleConfig.add(stale);
		List<InventoryButtonPlacement> staleResult = InventoryButtonConfigCodec.decode(staleConfig,
			id -> false, name -> false);
		check(staleResult.getFirst().macroId() == -1,
			"deleted macro ids restore as rebindable unassigned buttons");
		check(staleResult.getFirst().hoverTooltip().isEmpty(),
			"older button configs without a tooltip default to no custom tooltip");
		check(!staleResult.getFirst().slotAligned()
			&& InventoryButtonLayout.placementPixelX(staleResult.getFirst(), 100) == 100
			&& InventoryButtonLayout.placementPixelY(staleResult.getFirst(), 64) == 64,
			"legacy grid positions remain pixel-identical until explicitly moved or reflowed");

		JsonObject unsafe = new JsonObject();
		unsafe.addProperty("id", 6);
		unsafe.addProperty("macroId", -1);
		unsafe.addProperty("appearance", "PNG");
		unsafe.addProperty("value", "../not-an-icon.png");
		JsonArray unsafeConfig = new JsonArray();
		unsafeConfig.add(unsafe);
		InventoryButtonPlacement safeFallback = InventoryButtonConfigCodec.decode(unsafeConfig,
			id -> true, name -> name.equals("icon.png")).getFirst();
		check(safeFallback.appearance() == InventoryButtonPlacement.Appearance.ITEM,
			"invalid PNG paths cannot become arbitrary texture inputs");

		check(InventoryButtonLayout.pixelX(0, 100) == 108 && InventoryButtonLayout.pixelY(0, 64) == 82,
			"cell zero aligns with the first vanilla inventory slot lattice point");
		check(InventoryButtonLayout.gridX(117, 100) == 0 && InventoryButtonLayout.gridY(91, 64) == 0,
			"cell centers map back to their slot-aligned grid coordinates");
		check(InventoryButtonLayout.gridX(127, 100) == 1,
			"cursor snapping advances at the boundary between adjacent 18px cells");

		int screenWidth = 400;
		int screenHeight = 240;
		int inventoryLeft = 100;
		int inventoryTop = 64;
		InventoryButtonLayout.Bounds inventory = new InventoryButtonLayout.Bounds(100, 64, 176, 166);
		InventoryButtonLayout.Bounds recipeBook = new InventoryButtonLayout.Bounds(16, 64, 80, 120);
		InventoryButtonLayout.Bounds editorPanel = new InventoryButtonLayout.Bounds(350, 20, 44, 200);
		List<InventoryButtonLayout.Bounds> occupiedBounds = List.of(inventory, recipeBook, editorPanel);
		check(InventoryButtonLayout.fitsViewportAndBounds(11, 2, screenWidth, screenHeight,
			inventoryLeft, inventoryTop, occupiedBounds), "fully exterior grid cells remain available");
		check(!InventoryButtonLayout.fitsViewportAndBounds(0, 0, screenWidth, screenHeight,
			inventoryLeft, inventoryTop, occupiedBounds), "the active inventory rectangle excludes its slot cells");
		check(!InventoryButtonLayout.fitsViewportAndBounds(-2, 2, screenWidth, screenHeight,
			inventoryLeft, inventoryTop, occupiedBounds), "the visible recipe-book panel is excluded from placement");
		check(!InventoryButtonLayout.fitsViewportAndBounds(18, 2, screenWidth, screenHeight,
			inventoryLeft, inventoryTop, occupiedBounds), "cells extending beyond the GUI viewport are rejected");

		InventoryButtonPlacement placement = new InventoryButtonPlacement(4);
		placement.setGrid(11, 2);
		List<InventoryButtonPlacement> placements = new ArrayList<>(List.of(placement));
		check(!InventoryButtonLayout.canPlace(placements, -1, 11, 2, screenWidth, screenHeight,
			inventoryLeft, inventoryTop, occupiedBounds), "a second button cannot overlap an existing placement");
		check(InventoryButtonLayout.canPlace(placements, 4, 11, 2, screenWidth, screenHeight,
			inventoryLeft, inventoryTop, occupiedBounds), "a button can remain in its own cell while moving");
		InventoryButtonPlacement legacy = InventoryButtonConfigCodec.decode(staleConfig,
			id -> false, name -> false).getFirst();
		check(!InventoryButtonLayout.canPlace(List.of(legacy), -1, -1, -1, screenWidth, screenHeight,
			inventoryLeft, inventoryTop, List.of()),
			"overlap detection uses actual pixels across old and new grid anchors");
		check(!InventoryButtonLayout.canPlace(placements, 4, -12, 2, screenWidth, screenHeight,
			inventoryLeft, inventoryTop, occupiedBounds), "button cells remain fully inside the viewport");
		check(InventoryButtonLayout.hit(placements, 315, 127, inventoryLeft, inventoryTop,
			screenWidth, screenHeight, occupiedBounds) == placement,
			"hit testing follows the same inventory-relative snapped cell");
		check(InventoryButtonLayout.pixelX(11, 100) - 100 == InventoryButtonLayout.pixelX(11, 72) - 72,
			"a cell remains anchored to the inventory rectangle across GUI positions");

		InventoryButtonPlacement invalid = new InventoryButtonPlacement(1);
		invalid.setGrid(0, 0);
		List<InventoryButtonPlacement> needsReflow = new ArrayList<>(List.of(invalid, placement));
		check(InventoryButtonLayout.invalidCount(needsReflow, screenWidth, screenHeight,
			inventoryLeft, inventoryTop, List.of(inventory)) == 1,
			"saved placements are detected as invalid without being silently moved");
		check(invalid.gridX() == 0 && invalid.gridY() == 0,
			"invalid saved coordinates remain unchanged until explicit reflow");
		check(InventoryButtonLayout.hit(needsReflow, 117, 91, inventoryLeft, inventoryTop,
			screenWidth, screenHeight, List.of(inventory)) == null,
			"normal hit testing ignores placements in the inventory surface");
		check(InventoryButtonLayout.hitSaved(needsReflow, 117, 91, inventoryLeft, inventoryTop,
			screenWidth, screenHeight) == invalid,
			"edit hit testing can select a visible invalid placement for repair");
		InventoryButtonLayout.ReflowResult reflow = InventoryButtonLayout.reflowInvalid(needsReflow,
			screenWidth, screenHeight, inventoryLeft, inventoryTop, List.of(inventory));
		check(reflow.moved() == 1 && reflow.remaining() == 0,
			"explicit reflow moves invalid placements into nearest available exterior cells");
		check(InventoryButtonLayout.isPlacementValid(needsReflow, invalid, screenWidth, screenHeight,
			inventoryLeft, inventoryTop, List.of(inventory)), "reflowed placements satisfy all exterior bounds");
		check(invalid.gridX() == 0 && invalid.gridY() == -2,
			"reflow chooses the nearest available exterior cell on the vanilla slot lattice");
		check(placement.gridX() == 11 && placement.gridY() == 2,
			"reflow preserves placements that were already valid");

		InventoryButtonPlacement stranded = new InventoryButtonPlacement(8);
		stranded.setGrid(0, 0);
		List<InventoryButtonPlacement> noSpace = new ArrayList<>(List.of(stranded));
		InventoryButtonLayout.ReflowResult incomplete = InventoryButtonLayout.reflowInvalid(noSpace,
			40, 40, 0, 0, List.of(new InventoryButtonLayout.Bounds(0, 0, 40, 40)));
		check(incomplete.moved() == 0 && incomplete.remaining() == 1,
			"reflow retains placements and reports them when no valid cells exist");

		check(!InventoryButtonRules.evaluate(false, true, true, true, true, true).eligible(),
			"disabled macro system blocks an inventory button");
		check(!InventoryButtonRules.evaluate(true, false, true, true, true, true).eligible(),
			"a deleted macro is ineligible");
		check(!InventoryButtonRules.evaluate(true, true, false, true, true, true).eligible(),
			"disabled macros cannot run from a button");
		check(!InventoryButtonRules.evaluate(true, true, true, false, true, true).eligible(),
			"empty macros cannot run from a button");
		check(!InventoryButtonRules.evaluate(true, true, true, true, false, true).eligible(),
			"button obeys the macro trigger context");
		check(!InventoryButtonRules.evaluate(true, true, true, true, true, false).eligible(),
			"button obeys the macro island allowlist");
		check(InventoryButtonRules.evaluate(true, true, true, true, true, true).eligible(),
			"eligible assigned macro can run from its inventory button");
	}

	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}
}
