package geiler.addons.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import geiler.addons.GeilerAddons;
import geiler.addons.client.config.ModConfig;
import geiler.addons.client.macro.MacroDefinition;
import geiler.addons.client.macro.MacroRunner;
import geiler.addons.client.module.impl.InventoryButtonLayout;
import geiler.addons.client.module.impl.InventoryButtonPlacement;
import geiler.addons.client.module.impl.InventoryButtonsModule;
import geiler.addons.client.module.impl.InventoryButtonRules;
import geiler.addons.client.module.impl.MacrosModule;
import geiler.addons.client.module.impl.VisualModule;
import geiler.addons.client.mixin.AbstractContainerScreenInvoker;
import geiler.addons.client.mixin.AbstractRecipeBookScreenAccessor;
import geiler.addons.client.mixin.RecipeBookComponentInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static geiler.addons.client.gui.GuiTheme.*;

/** Render and input bridge for the standard player InventoryScreen only. */
public final class InventoryButtonOverlay {
	private static final Map<String, IconTexture> PNG_TEXTURES = new HashMap<>();
	private static final Set<String> FAILED_PNGS = new HashSet<>();
	private static final int PANEL_WIDTH = 204;
	private static final int PANEL_HEIGHT = 198;
	private static final int PANEL_ROW_TOP = 37;
	private static final int PANEL_ROW_HEIGHT = 17;
	private static final int EDIT_GRID_COLOR = 0x20FFFFFF;
	private static final int NO_PENDING_CELL = Integer.MIN_VALUE;
	private static boolean editing;
	private static int selectedMacroId = -1;
	private static int selectedPlacementId = -1;
	private static int draggingId = -1;
	private static boolean moveMode;
	private static int pendingGridX = NO_PENDING_CELL;
	private static int pendingGridY = NO_PENDING_CELL;
	private static int pendingMacroId = -1;
	private static int pendingMacroTargetPlacementId = -1;
	private static InventoryButtonPlacement.Appearance appearance = InventoryButtonPlacement.Appearance.ITEM;
	private static String appearanceValue = "minecraft:stone";
	private static String hoverTooltipValue = "";
	private static String status = "";
	private static Screen editorReturnScreen;
	private static InventoryScreen editorInventoryScreen;

	private InventoryButtonOverlay() { }

	public static boolean isEditing() { return editing; }

	public static void beginEditing() {
		beginEditing(-1, null, null);
	}

	public static void beginEditing(int placementId) {
		beginEditing(placementId, null, null);
	}

	public static void beginEditing(int placementId, Screen returnScreen, InventoryScreen inventoryScreen) {
		editing = true;
		editorReturnScreen = returnScreen;
		editorInventoryScreen = inventoryScreen;
		selectedMacroId = -1;
		selectedPlacementId = -1;
		draggingId = -1;
		moveMode = false;
		pendingGridX = NO_PENDING_CELL;
		pendingGridY = NO_PENDING_CELL;
		pendingMacroId = -1;
		pendingMacroTargetPlacementId = -1;
		appearance = InventoryButtonPlacement.Appearance.ITEM;
		appearanceValue = "minecraft:stone";
		hoverTooltipValue = "";
		InventoryButtonPlacement selected = InventoryButtonsModule.INSTANCE.placement(placementId);
		if (selected != null) {
			selectedPlacementId = selected.id();
			appearance = selected.appearance();
			appearanceValue = selected.value();
			hoverTooltipValue = selected.hoverTooltip();
			status = "Selected button " + selected.id() + ". Drag it, choose Move, or edit its settings.";
		} else {
			status = "Click any faint exterior grid cell to choose or create a macro button.";
		}
	}

	public static void tick() {
		if (!editing) return;
		Screen screen = Minecraft.getInstance().screen;
		if (screen instanceof InventoryScreen || screen instanceof InventoryButtonPickerScreen
			|| screen instanceof InventoryButtonTextScreen || screen instanceof MacroEditorScreen
			|| screen instanceof ScratchMacroEditorScreen) {
			if (screen instanceof InventoryScreen && pendingMacroId >= 0) {
				int macroId = pendingMacroId;
				pendingMacroId = -1;
				if (hasPendingCell()) {
					placeMacroAtPending((InventoryScreen) screen, macroId);
				} else if (pendingMacroTargetPlacementId >= 0) {
					InventoryButtonPlacement target = InventoryButtonsModule.INSTANCE
						.placement(pendingMacroTargetPlacementId);
					pendingMacroTargetPlacementId = -1;
					if (target != null) {
						target.setMacroId(macroId);
						ModConfig.markDirty();
						status = "Button assigned to " + macroName(macroId) + ".";
					}
				} else {
					selectedMacroId = macroId;
					status = "Macro created. Click an open exterior grid cell to place its button.";
				}
			}
			return;
		}
		stopEditing();
		editorReturnScreen = null;
		editorInventoryScreen = null;
	}

	/** Consumes only the inventory screen instance opened by the layout editor. */
	public static Screen consumeEditorReturnScreen(InventoryScreen screen) {
		if (screen != editorInventoryScreen) return null;
		Screen parent = editorReturnScreen;
		editorReturnScreen = null;
		editorInventoryScreen = null;
		return parent;
	}

	public static void stopEditing() {
		editing = false;
		selectedMacroId = -1;
		selectedPlacementId = -1;
		draggingId = -1;
		moveMode = false;
		pendingGridX = NO_PENDING_CELL;
		pendingGridY = NO_PENDING_CELL;
		pendingMacroId = -1;
		pendingMacroTargetPlacementId = -1;
		status = "";
	}

	public static List<String> listPngIcons() {
		Path directory = InventoryButtonsModule.INSTANCE.iconDirectory();
		try {
			Files.createDirectories(directory);
			try (var paths = Files.list(directory)) {
				return paths.filter(Files::isRegularFile)
					.map(path -> path.getFileName().toString())
					.filter(InventoryButtonsModule.INSTANCE::isPngIconAllowed)
					.sorted(String.CASE_INSENSITIVE_ORDER).toList();
			}
		} catch (IOException | SecurityException ignored) {
			return List.of();
		}
	}

	public static void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics,
		int mouseX, int mouseY) {
		if (!(screen instanceof InventoryScreen)) return;
		VisualModule.INSTANCE.refreshTheme();
		boolean editSurface = editing;
		InventoryButtonsModule module = InventoryButtonsModule.INSTANCE;
		if (!editSurface && !module.isEnabled()) return;
		int left = left(screen);
		int top = top(screen);
		int windowWidth = imageWidth(screen);
		int screenWidth = graphics.guiWidth();
		int screenHeight = graphics.guiHeight();
		List<InventoryButtonPlacement> placements = module.placements();
		Minecraft minecraft = Minecraft.getInstance();
		MacrosModule.INSTANCE.macros();
		List<InventoryButtonLayout.Bounds> vanillaBounds = vanillaBounds(screen);
		Panel panel = panel(left, top, windowWidth, screenWidth, screenHeight, vanillaBounds);
		List<InventoryButtonLayout.Bounds> editBounds = editingBounds(vanillaBounds, panel);
		if (editSurface) drawGrid(graphics, left, top, screenWidth, screenHeight, editBounds);

		for (InventoryButtonPlacement placement : placements) {
			List<InventoryButtonLayout.Bounds> bounds = editSurface ? editBounds : vanillaBounds;
			boolean valid = InventoryButtonLayout.isPlacementValid(placements, placement,
				screenWidth, screenHeight, left, top, bounds);
			if (!valid) {
				if (editSurface) drawInvalidPlacement(graphics, placement, mouseX, mouseY, left, top,
					screenWidth, screenHeight);
				continue;
			}
			if (!editSurface && (placement.macroId() < 0 || MacrosModule.INSTANCE.macro(placement.macroId()) == null)) continue;
			int x = InventoryButtonLayout.placementPixelX(placement, left);
			int y = InventoryButtonLayout.placementPixelY(placement, top);
			MacroDefinition macro = placement.macroId() < 0 ? null : MacrosModule.INSTANCE.macro(placement.macroId());
			InventoryButtonRules.Eligibility eligibility = MacroRunner.inventoryButtonEligibility(macro, minecraft, screen);
			boolean hovered = mouseX >= x && mouseX < x + InventoryButtonLayout.CELL_SIZE
				&& mouseY >= y && mouseY < y + InventoryButtonLayout.CELL_SIZE;
			drawButton(graphics, placement, macro, x, y, editSurface,
				eligibility == null || eligibility.eligible(), placement.id() == selectedPlacementId, hovered, 255);
			if (hovered) graphics.setTooltipForNextFrame(minecraft.font,
				buttonTooltip(placement, macro, eligibility, editSurface), mouseX, mouseY);
		}

		if (editSurface) {
			int ghostGridX = InventoryButtonLayout.gridX(mouseX, left);
			int ghostGridY = InventoryButtonLayout.gridY(mouseY, top);
			int movingId = draggingId >= 0 ? draggingId : (moveMode ? selectedPlacementId : -1);
			InventoryButtonPlacement moving = movingId < 0 ? null : module.placement(movingId);
			boolean showingGhost = moving != null || selectedMacroId >= 0;
			if (showingGhost && InventoryButtonLayout.fitsViewportAndBounds(ghostGridX, ghostGridY,
				screenWidth, screenHeight, left, top, editBounds)) {
				boolean valid = InventoryButtonLayout.canPlace(placements, movingId, ghostGridX, ghostGridY,
					screenWidth, screenHeight, left, top, editBounds);
				InventoryButtonPlacement ghost;
				MacroDefinition macro;
				if (moving != null) {
					ghost = new InventoryButtonPlacement(moving);
					ghost.setGrid(ghostGridX, ghostGridY);
					macro = moving.macroId() < 0 ? null : MacrosModule.INSTANCE.macro(moving.macroId());
				} else {
					ghost = new InventoryButtonPlacement(-1);
					ghost.setMacroId(selectedMacroId);
					ghost.setGrid(ghostGridX, ghostGridY);
					ghost.setAppearance(appearance, appearanceValue);
					ghost.setHoverTooltip(hoverTooltipValue);
					macro = MacrosModule.INSTANCE.macro(selectedMacroId);
				}
				drawButton(graphics, ghost, macro, InventoryButtonLayout.pixelX(ghostGridX, left),
					InventoryButtonLayout.pixelY(ghostGridY, top), true, valid, false, false, 145);
			}
			drawEditorPanel(graphics, panel, placements, left, top, screenWidth, screenHeight,
				editBounds, mouseX, mouseY);
		}
	}

	public static boolean mouseClicked(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
		if (!(screen instanceof InventoryScreen)) return false;
		InventoryButtonsModule module = InventoryButtonsModule.INSTANCE;
		int left = left(screen);
		int top = top(screen);
		int screenWidth = screenWidth();
		int screenHeight = screenHeight();
		int mouseX = (int) event.x();
		int mouseY = (int) event.y();
		List<InventoryButtonPlacement> placements = module.placements();
		List<InventoryButtonLayout.Bounds> vanillaBounds = vanillaBounds(screen);
		Panel panel = panel(left, top, imageWidth(screen), screenWidth, screenHeight, vanillaBounds);
		List<InventoryButtonLayout.Bounds> editBounds = editingBounds(vanillaBounds, panel);

		if (editing) {
			if (panel.contains(mouseX, mouseY)) {
				if (event.button() == 0) handlePanelClick(screen, panel, mouseX, mouseY, editBounds,
					placements, left, top, screenWidth, screenHeight);
				return true;
			}
			InventoryButtonPlacement hit = InventoryButtonLayout.hit(placements, event.x(), event.y(), left, top,
				screenWidth, screenHeight, editBounds);
			if (hit == null) {
				hit = InventoryButtonLayout.hitSaved(placements, event.x(), event.y(), left, top,
					screenWidth, screenHeight);
				if (hit != null) {
					selectedPlacementId = hit.id();
					selectedMacroId = -1;
					appearance = hit.appearance();
					appearanceValue = hit.value();
					hoverTooltipValue = hit.hoverTooltip();
					draggingId = event.button() == 0 ? hit.id() : -1;
					status = "Invalid saved position. Drag this button or use Reflow to move it outside the inventory.";
					return true;
				}
			}
			if (hit != null) {
				selectedPlacementId = hit.id();
				selectedMacroId = -1;
				appearance = hit.appearance();
				appearanceValue = hit.value();
				hoverTooltipValue = hit.hoverTooltip();
				if (event.button() == 0 && moveMode) {
					status = "Choose an open grid cell for the selected button.";
				} else {
					draggingId = event.button() == 0 ? hit.id() : -1;
					status = "Selected button " + hit.id() + ". Drag it or use Move / Delete in the editor panel.";
				}
				return true;
			}
			if (event.button() != 0) {
				if (event.button() == 1 && selectedMacroId >= 0) {
					selectedMacroId = -1;
					status = "Macro placement cancelled.";
					return true;
				}
				return false;
			}
			int gridX = InventoryButtonLayout.gridX(event.x(), left);
			int gridY = InventoryButtonLayout.gridY(event.y(), top);
			if (!InventoryButtonLayout.fitsViewportAndBounds(gridX, gridY, screenWidth, screenHeight,
				left, top, editBounds)) return false;
			if (moveMode && selectedPlacementId >= 0) {
				if (InventoryButtonLayout.canPlace(placements, selectedPlacementId, gridX, gridY,
					screenWidth, screenHeight, left, top, editBounds)) {
					InventoryButtonPlacement selected = module.placement(selectedPlacementId);
					if (selected != null) {
						selected.setGrid(gridX, gridY);
						ModConfig.markDirty();
					}
					moveMode = false;
					status = "Button moved.";
				} else {
					status = "That exterior cell is occupied.";
				}
				return true;
			}
			if (selectedMacroId >= 0) {
				if (InventoryButtonLayout.canPlace(placements, -1, gridX, gridY,
					screenWidth, screenHeight, left, top, editBounds)) {
					module.create(selectedMacroId, gridX, gridY, appearance, appearanceValue);
					InventoryButtonPlacement created = module.placements().getLast();
					created.setHoverTooltip(hoverTooltipValue);
					ModConfig.markDirty();
					selectedPlacementId = created.id();
					status = "Button placed. Click another open cell or select a different macro.";
				} else {
					status = "That exterior cell is occupied.";
				}
				return true;
			}
			if (!InventoryButtonLayout.canPlace(placements, -1, gridX, gridY,
				screenWidth, screenHeight, left, top, editBounds)) {
				status = "That exterior cell is occupied.";
				return true;
			}
			pendingGridX = gridX;
			pendingGridY = gridY;
			openMacroPicker(screen);
			return true;
		}

		if (!module.isEnabled()) return false;
		InventoryButtonPlacement hit = InventoryButtonLayout.hit(placements, event.x(), event.y(), left, top,
			screenWidth, screenHeight, vanillaBounds);
		if (hit == null) return false;
		// Consume every click on a valid button so the inventory slot beneath it stays inert.
		if (event.button() == 0 && hit.macroId() >= 0) {
			InventoryButtonRules.Eligibility eligibility = MacroRunner.activateInventoryButton(hit.macroId());
			if (!eligibility.eligible()) status = eligibility.reason();
		}
		return true;
	}

	public static boolean mouseDragged(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
		if (!editing || !(screen instanceof InventoryScreen) || draggingId < 0 || event.button() != 0) return false;
		int left = left(screen);
		int top = top(screen);
		List<InventoryButtonLayout.Bounds> vanillaBounds = vanillaBounds(screen);
		Panel panel = panel(left, top, imageWidth(screen), screenWidth(), screenHeight(), vanillaBounds);
		List<InventoryButtonLayout.Bounds> blocked = editingBounds(vanillaBounds, panel);
		InventoryButtonPlacement placement = InventoryButtonsModule.INSTANCE.placement(draggingId);
		if (placement == null) return true;
		int gridX = InventoryButtonLayout.gridX(event.x(), left);
		int gridY = InventoryButtonLayout.gridY(event.y(), top);
		if (InventoryButtonLayout.canPlace(InventoryButtonsModule.INSTANCE.placements(), draggingId,
			gridX, gridY, screenWidth(), screenHeight(), left, top, blocked)
			&& (placement.gridX() != gridX || placement.gridY() != gridY)) {
			placement.setGrid(gridX, gridY);
			ModConfig.markDirty();
		}
		return true;
	}

	public static boolean keyPressed(AbstractContainerScreen<?> screen, KeyEvent event) {
		if (!editing || !(screen instanceof InventoryScreen)) return false;
		if (event.key() == InputConstants.KEY_ESCAPE && moveMode) {
			moveMode = false;
			status = "Move cancelled.";
			return true;
		}
		if (selectedPlacementId >= 0 && (event.key() == InputConstants.KEY_DELETE
			|| event.key() == InputConstants.KEY_BACKSPACE)) {
			InventoryButtonsModule.INSTANCE.remove(selectedPlacementId);
			selectedPlacementId = -1;
			moveMode = false;
			status = "Button deleted.";
			return true;
		}
		return false;
	}

	public static void mouseReleased(AbstractContainerScreen<?> screen) {
		if (screen instanceof InventoryScreen) draggingId = -1;
	}

	private static void handlePanelClick(Screen screen, Panel panel, int mouseX, int mouseY,
		List<InventoryButtonLayout.Bounds> blocked, List<InventoryButtonPlacement> placements,
		int left, int top, int screenWidth, int screenHeight) {
		int row = (mouseY - panel.y - PANEL_ROW_TOP) / panel.rowHeight;
		if (row < 0 || row >= 8 || mouseY < panel.y + PANEL_ROW_TOP
			|| mouseX < panel.x + 4 || mouseX >= panel.x + panel.width - 4) return;
		switch (row) {
			case 0 -> openMacroPicker(screen);
			case 1 -> cycleAppearance();
			case 2 -> openAppearancePicker(screen);
			case 3 -> openTooltipEditor(screen);
			case 4 -> {
				if (selectedPlacementId < 0) status = "Select a button before moving it.";
				else {
					moveMode = !moveMode;
					status = moveMode ? "Click an open exterior grid cell to move the selected button."
						: "Move mode cancelled.";
				}
			}
			case 5 -> {
				if (selectedPlacementId < 0) status = "Select a button before deleting it.";
				else {
					InventoryButtonsModule.INSTANCE.remove(selectedPlacementId);
					selectedPlacementId = -1;
					moveMode = false;
					status = "Button deleted.";
				}
			}
			case 6 -> reflow(placements, left, top, screenWidth, screenHeight, blocked);
			case 7 -> stopEditing();
			default -> { }
		}
	}

	private static void openMacroPicker(Screen parent) {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.setScreen(new InventoryButtonPickerScreen(parent, InventoryButtonPickerScreen.Mode.MACRO,
			InventoryButtonOverlay::selectMacro, () -> createMacroAndOpenEditor(parent),
			InventoryButtonOverlay::cancelPendingCell));
	}

	private static void selectMacro(String value) {
		int macroId;
		try { macroId = Integer.parseInt(value); }
		catch (NumberFormatException ignored) { return; }
		InventoryButtonPlacement selected = selectedPlacement();
		if (selected != null && pendingGridX == NO_PENDING_CELL) {
			selected.setMacroId(macroId);
			ModConfig.markDirty();
			selectedMacroId = -1;
			status = "Button assigned to " + macroName(macroId) + ".";
		} else if (hasPendingCell()) {
			placeMacroAtPending((InventoryScreen) Minecraft.getInstance().screen, macroId);
		} else {
			selectedPlacementId = -1;
			selectedMacroId = macroId;
			status = "Macro selected. Click an open exterior grid cell to place it.";
		}
	}

	private static void createMacroAndOpenEditor(Screen parent) {
		MacroDefinition macro = MacrosModule.INSTANCE.createMacro();
		pendingMacroId = macro.id();
		pendingMacroTargetPlacementId = hasPendingCell() ? -1 : selectedPlacementId;
		Minecraft.getInstance().setScreen(new ScratchMacroEditorScreen(parent, macro));
	}

	private static void placeMacroAtPending(InventoryScreen screen, int macroId) {
		if (screen == null || !hasPendingCell()) return;
		int left = left(screen);
		int top = top(screen);
		List<InventoryButtonLayout.Bounds> vanillaBounds = vanillaBounds(screen);
		Panel panel = panel(left, top, imageWidth(screen), screenWidth(), screenHeight(), vanillaBounds);
		List<InventoryButtonLayout.Bounds> blocked = editingBounds(vanillaBounds, panel);
		List<InventoryButtonPlacement> placements = InventoryButtonsModule.INSTANCE.placements();
		int x = pendingGridX;
		int y = pendingGridY;
		pendingGridX = NO_PENDING_CELL;
		pendingGridY = NO_PENDING_CELL;
		if (!InventoryButtonLayout.canPlace(placements, -1, x, y, screenWidth(), screenHeight(),
			left, top, blocked)) {
			selectedMacroId = macroId;
			status = "The original cell is no longer available. Choose another exterior cell.";
			return;
		}
		InventoryButtonPlacement created = InventoryButtonsModule.INSTANCE.create(macroId, x, y, appearance, appearanceValue);
		created.setHoverTooltip(hoverTooltipValue);
		ModConfig.markDirty();
		selectedPlacementId = created.id();
		selectedMacroId = -1;
		status = "Button placed for " + macroName(macroId) + ". Hover it to preview its tooltip.";
	}

	private static void cancelPendingCell() {
		pendingGridX = NO_PENDING_CELL;
		pendingGridY = NO_PENDING_CELL;
		status = "Macro selection cancelled.";
	}

	private static boolean hasPendingCell() {
		return pendingGridX != NO_PENDING_CELL && pendingGridY != NO_PENDING_CELL;
	}

	private static void cycleAppearance() {
		appearance = switch (appearance) {
			case ITEM -> InventoryButtonPlacement.Appearance.TEXT;
			case TEXT -> InventoryButtonPlacement.Appearance.PNG;
			case PNG -> InventoryButtonPlacement.Appearance.ITEM;
		};
		if (appearance == InventoryButtonPlacement.Appearance.ITEM) appearanceValue = "minecraft:stone";
		if (appearance == InventoryButtonPlacement.Appearance.TEXT) appearanceValue = "Go";
		if (appearance == InventoryButtonPlacement.Appearance.PNG) appearanceValue = "";
		applyAppearanceToSelected();
		status = "Appearance style: " + appearance.name() + ". Choose its content on the next row.";
	}

	private static void openAppearancePicker(Screen parent) {
		switch (appearance) {
			case ITEM -> Minecraft.getInstance().setScreen(new InventoryButtonPickerScreen(parent,
				InventoryButtonPickerScreen.Mode.ITEM, InventoryButtonOverlay::setAppearanceValue));
			case PNG -> Minecraft.getInstance().setScreen(new InventoryButtonPickerScreen(parent,
				InventoryButtonPickerScreen.Mode.PNG, InventoryButtonOverlay::setAppearanceValue));
			case TEXT -> Minecraft.getInstance().setScreen(new InventoryButtonTextScreen(parent, currentAppearanceValue(),
				InventoryButtonOverlay::setAppearanceValue));
		}
	}

	private static void openTooltipEditor(Screen parent) {
		InventoryButtonPlacement selected = selectedPlacement();
		String initial = selected == null ? hoverTooltipValue : selected.hoverTooltip();
		Minecraft.getInstance().setScreen(new InventoryButtonTextScreen(parent, "Inventory Button Tooltip",
			"Shown with the macro name and eligibility state when hovered.", initial, value -> {
				hoverTooltipValue = value == null ? "" : value;
				if (selected != null) {
					selected.setHoverTooltip(hoverTooltipValue);
					ModConfig.markDirty();
				}
				status = "Hover tooltip updated.";
			}));
	}

	private static String currentAppearanceValue() {
		InventoryButtonPlacement selected = selectedPlacement();
		return selected == null ? appearanceValue : selected.value();
	}

	private static void setAppearanceValue(String value) {
		appearanceValue = value == null ? "" : value;
		applyAppearanceToSelected();
		status = "Button appearance updated.";
	}

	private static void applyAppearanceToSelected() {
		InventoryButtonPlacement selected = selectedPlacement();
		if (selected != null) {
			selected.setAppearance(appearance, appearanceValue);
			ModConfig.markDirty();
		}
	}

	private static InventoryButtonPlacement selectedPlacement() {
		return selectedPlacementId < 0 ? null : InventoryButtonsModule.INSTANCE.placement(selectedPlacementId);
	}

	private static void reflow(List<InventoryButtonPlacement> placements, int left, int top,
		int screenWidth, int screenHeight, List<InventoryButtonLayout.Bounds> blocked) {
		InventoryButtonLayout.ReflowResult result = InventoryButtonLayout.reflowInvalid(placements,
			screenWidth, screenHeight, left, top, blocked);
		if (result.moved() > 0) ModConfig.markDirty();
		status = result.remaining() == 0
			? "Reflow moved " + result.moved() + " button(s) into valid exterior cells."
			: "Reflow moved " + result.moved() + "; " + result.remaining()
				+ " button(s) still need space. Increase GUI room or remove a placement.";
	}

	private static void drawGrid(GuiGraphicsExtractor graphics, int left, int top,
		int screenWidth, int screenHeight, List<InventoryButtonLayout.Bounds> blocked) {
		int originX = left + InventoryButtonLayout.FIRST_SLOT_X;
		int originY = top + InventoryButtonLayout.FIRST_SLOT_Y;
		int cellSize = InventoryButtonLayout.CELL_SIZE;
		if (screenWidth <= 0 || screenHeight <= 0) return;

		// Draw the lattice once as thin strokes instead of filling and outlining every cell. The
		// old per-cell path emitted several thousand GUI operations on a typical screen each frame.
		int minGridX = (int) Math.ceil(-originX / (double) cellSize);
		int minGridY = (int) Math.ceil(-originY / (double) cellSize);
		int maxGridX = (int) Math.floor((screenWidth - 1 - originX) / (double) cellSize);
		int maxGridY = (int) Math.floor((screenHeight - 1 - originY) / (double) cellSize);
		for (int gridX = minGridX; gridX <= maxGridX; gridX++) {
			drawVerticalGridLine(graphics, originX + gridX * cellSize, screenHeight, blocked);
		}
		for (int gridY = minGridY; gridY <= maxGridY; gridY++) {
			drawHorizontalGridLine(graphics, originY + gridY * cellSize, screenWidth, blocked);
		}
	}

	private static void drawVerticalGridLine(GuiGraphicsExtractor graphics, int x, int screenHeight,
		List<InventoryButtonLayout.Bounds> blocked) {
		int cursor = 0;
		while (cursor < screenHeight) {
			int nextBlocked = screenHeight;
			int coveredUntil = cursor;
			for (InventoryButtonLayout.Bounds bounds : blocked) {
				if (bounds == null || x < bounds.x() || x >= bounds.x() + bounds.width()) continue;
				int start = bounds.y();
				int end = Math.min(screenHeight, bounds.y() + bounds.height());
				if (end <= cursor || start >= screenHeight) continue;
				if (start <= cursor) coveredUntil = Math.max(coveredUntil, end);
				else nextBlocked = Math.min(nextBlocked, start);
			}
			if (coveredUntil > cursor) {
				cursor = coveredUntil;
			} else if (nextBlocked > cursor) {
				graphics.fill(x, cursor, x + 1, nextBlocked, EDIT_GRID_COLOR);
				cursor = nextBlocked;
			} else {
				graphics.fill(x, cursor, x + 1, screenHeight, EDIT_GRID_COLOR);
				break;
			}
		}
	}

	private static void drawHorizontalGridLine(GuiGraphicsExtractor graphics, int y, int screenWidth,
		List<InventoryButtonLayout.Bounds> blocked) {
		int cursor = 0;
		while (cursor < screenWidth) {
			int nextBlocked = screenWidth;
			int coveredUntil = cursor;
			for (InventoryButtonLayout.Bounds bounds : blocked) {
				if (bounds == null || y < bounds.y() || y >= bounds.y() + bounds.height()) continue;
				int start = bounds.x();
				int end = Math.min(screenWidth, bounds.x() + bounds.width());
				if (end <= cursor || start >= screenWidth) continue;
				if (start <= cursor) coveredUntil = Math.max(coveredUntil, end);
				else nextBlocked = Math.min(nextBlocked, start);
			}
			if (coveredUntil > cursor) {
				cursor = coveredUntil;
			} else if (nextBlocked > cursor) {
				graphics.fill(cursor, y, nextBlocked, y + 1, EDIT_GRID_COLOR);
				cursor = nextBlocked;
			} else {
				graphics.fill(cursor, y, screenWidth, y + 1, EDIT_GRID_COLOR);
				break;
			}
		}
	}

	private static void drawEditorPanel(GuiGraphicsExtractor graphics, Panel panel,
		List<InventoryButtonPlacement> placements, int left, int top, int screenWidth, int screenHeight,
		List<InventoryButtonLayout.Bounds> blocked, int mouseX, int mouseY) {
		roundedRectBordered(graphics, panel.x, panel.y, panel.width, panel.height, RADIUS_SMALL,
			PANEL_TOP, PANEL_BOTTOM, BORDER);
		graphics.text(Minecraft.getInstance().font, "Inventory Buttons · Edit", panel.x + 7, panel.y + 6, TEXT_PRIMARY);
		InventoryButtonPlacement selected = selectedPlacement();
		MacroDefinition macro = selected == null && selectedMacroId >= 0
			? MacrosModule.INSTANCE.macro(selectedMacroId)
			: selected == null ? null : MacrosModule.INSTANCE.macro(selected.macroId());
		int previewX = panel.x + panel.width - 27;
		int previewY = panel.y + 4;
		InventoryButtonPlacement preview = selected;
		if (preview == null && selectedMacroId >= 0) {
			preview = new InventoryButtonPlacement(-1);
			preview.setMacroId(selectedMacroId);
			preview.setAppearance(appearance, appearanceValue);
			preview.setHoverTooltip(hoverTooltipValue);
		}
		if (preview != null) {
			drawButton(graphics, preview, macro, previewX, previewY, true, true,
				selected != null, mouseX >= previewX && mouseX < previewX + 18
					&& mouseY >= previewY && mouseY < previewY + 18, 255);
			if (mouseX >= previewX && mouseX < previewX + 18 && mouseY >= previewY && mouseY < previewY + 18) {
				graphics.setTooltipForNextFrame(Minecraft.getInstance().font,
					buttonTooltip(preview, macro, null, true), mouseX, mouseY);
			}
		}
		String target = selected == null ? (macro == null ? "No button selected" : "New · " + macro.name())
			: (macro == null ? "Unassigned button " + selected.id() : macro.name());
		graphics.text(Minecraft.getInstance().font, trim(target, panel.width - 18),
			panel.x + 7, panel.y + 24, TEXT_MUTED);
		int invalid = InventoryButtonLayout.invalidCount(placements, screenWidth, screenHeight, left, top, blocked);
		drawPanelRow(graphics, panel, 0, selected == null ? "Choose macro / create new" : "Change assigned macro", mouseX, mouseY);
		drawPanelRow(graphics, panel, 1, "Style: " + appearance.name(), mouseX, mouseY);
		drawPanelRow(graphics, panel, 2, switch (appearance) {
			case ITEM -> "Choose registered icon";
			case TEXT -> "Edit button text";
			case PNG -> "Choose PNG file";
		}, mouseX, mouseY);
		drawPanelRow(graphics, panel, 3, "Hover tooltip: " + (hoverTooltipValue.isBlank() ? "none" : "edit"), mouseX, mouseY);
		drawPanelRow(graphics, panel, 4, moveMode ? "Click a cell to move…" : "Move selected button", mouseX, mouseY);
		drawPanelRow(graphics, panel, 5, "Delete selected button", mouseX, mouseY);
		drawPanelRow(graphics, panel, 6, "Reflow invalid (" + invalid + ")", mouseX, mouseY);
		drawPanelRow(graphics, panel, 7, "Done editing", mouseX, mouseY);
		if (!status.isBlank()) graphics.text(Minecraft.getInstance().font,
			trim(status, panel.width - 14), panel.x + 7, panel.y + panel.height - 12, TEXT_MUTED);
	}

	private static void drawInvalidPlacement(GuiGraphicsExtractor graphics, InventoryButtonPlacement placement,
		int mouseX, int mouseY, int left, int top, int screenWidth, int screenHeight) {
		int x = InventoryButtonLayout.placementPixelX(placement, left);
		int y = InventoryButtonLayout.placementPixelY(placement, top);
		if (x < 0 || y < 0 || x + InventoryButtonLayout.CELL_SIZE > screenWidth
			|| y + InventoryButtonLayout.CELL_SIZE > screenHeight) return;
		graphics.fill(x, y, x + InventoryButtonLayout.CELL_SIZE, y + InventoryButtonLayout.CELL_SIZE, 0x55D02030);
		graphics.outline(x, y, InventoryButtonLayout.CELL_SIZE, InventoryButtonLayout.CELL_SIZE,
			placement.id() == selectedPlacementId ? 0xFFFFD65A : 0xFFFF5555);
		graphics.fill(x + 5, y + 4, x + 13, y + 6, 0xFFFF5555);
		graphics.fill(x + 5, y + 11, x + 13, y + 13, 0xFFFF5555);
		graphics.fill(x + 8, y + 6, x + 10, y + 11, 0xFFFF5555);
		if (mouseX >= x && mouseX < x + InventoryButtonLayout.CELL_SIZE
			&& mouseY >= y && mouseY < y + InventoryButtonLayout.CELL_SIZE) {
			MacroDefinition macro = placement.macroId() < 0 ? null : MacrosModule.INSTANCE.macro(placement.macroId());
			List<String> lines = new ArrayList<>();
			if (!placement.hoverTooltip().isBlank()) lines.add(placement.hoverTooltip());
			lines.add(macro == null ? "Unassigned button" : macro.name());
			lines.add("Invalid saved position · drag or use Reflow");
			graphics.setTooltipForNextFrame(Minecraft.getInstance().font,
				Component.literal(String.join("\n", lines)), mouseX, mouseY);
		}
	}

	private static void drawPanelRow(GuiGraphicsExtractor graphics, Panel panel, int row,
		String label, int mouseX, int mouseY) {
		int y = panel.y + PANEL_ROW_TOP + row * panel.rowHeight;
		int rowBoxHeight = Math.max(9, panel.rowHeight - 2);
		boolean hover = mouseX >= panel.x + 4 && mouseX < panel.x + panel.width - 4
			&& mouseY >= y && mouseY < y + rowBoxHeight;
		roundedRect(graphics, panel.x + 4, y, panel.width - 8, rowBoxHeight, RADIUS_SMALL,
			hover ? BUTTON_HOVER : BUTTON_BG);
		graphics.centeredText(Minecraft.getInstance().font, trim(label, panel.width - 18),
			panel.x + panel.width / 2, y + Math.max(0, (rowBoxHeight - 9) / 2),
			hover ? TEXT_ON_ACCENT : TEXT_PRIMARY);
	}

	private static void drawButton(GuiGraphicsExtractor graphics, InventoryButtonPlacement placement,
		MacroDefinition macro, int x, int y, boolean edit, boolean eligible, boolean selected,
		boolean hovered, int alpha) {
		boolean themed = VisualModule.INSTANCE.themeMacroColors().value();
		int backgroundAlpha = Math.min(alpha, 208);
		int defaultRgb = edit ? (eligible ? 0x00181B23 : 0x003A1C1C) :
			(eligible ? 0x00181B23 : 0x003C2525);
		int surfaceRgb = themed ? BUTTON_BG & 0x00FFFFFF : defaultRgb;
		graphics.fill(x, y, x + 18, y + 18, (backgroundAlpha << 24) | surfaceRgb);
		int border = selected ? 0xFFFFD65A : (hovered ? 0xFFFFFFFF
			: eligible ? (themed ? withOpacity(CATEGORY_SELECTED, 0.85f) : 0xFFC5C7D2) : 0xFFE07070);
		graphics.outline(x, y, InventoryButtonLayout.CELL_SIZE, InventoryButtonLayout.CELL_SIZE, border);
		if (placement.macroId() < 0) {
			graphics.centeredText(Minecraft.getInstance().font, "?", x + 9, y + 5, 0xFFFFD65A);
		} else {
			drawAppearance(graphics, placement, x, y);
		}
		if (edit && macro == null && placement.macroId() >= 0) {
			graphics.fill(x, y, x + 18, y + 18, 0x88000000);
		}
	}

	private static void drawAppearance(GuiGraphicsExtractor graphics, InventoryButtonPlacement placement, int x, int y) {
		switch (placement.appearance()) {
			case ITEM -> {
				Item item = registeredItem(placement.value());
				if (item != Items.AIR) graphics.item(new ItemStack(item), x + 1, y + 1);
				else graphics.centeredText(Minecraft.getInstance().font, "?", x + 9, y + 5, 0xFFFF7777);
			}
			case TEXT -> {
				String label = placement.value();
				if (label.isBlank()) label = "?";
				graphics.pose().pushMatrix();
				graphics.pose().translate(x + 1, y + 4);
				graphics.pose().scale(0.55f, 0.55f);
				graphics.centeredText(Minecraft.getInstance().font, trim(label, 26), 14, 8, 0xFFFFFFFF);
				graphics.pose().popMatrix();
			}
			case PNG -> drawPng(graphics, placement.value(), x + 1, y + 1);
		}
	}

	private static Item registeredItem(String value) {
		Identifier id = Identifier.tryParse(value == null ? "" : value);
		if (id == null || !BuiltInRegistries.ITEM.keySet().contains(id)) return Items.AIR;
		Item item = BuiltInRegistries.ITEM.getValue(id);
		return item == null ? Items.AIR : item;
	}

	private static void drawPng(GuiGraphicsExtractor graphics, String fileName, int x, int y) {
		if (!InventoryButtonsModule.INSTANCE.isPngIconAllowed(fileName)) return;
		IconTexture texture = pngTexture(fileName);
		if (texture == null) {
			graphics.centeredText(Minecraft.getInstance().font, "?", x + 8, y + 4, 0xFFFF7777);
			return;
		}
		graphics.blit(RenderPipelines.GUI_TEXTURED, texture.id, x, y, 0, 0, 16, 16,
			texture.width, texture.height);
	}

	private static IconTexture pngTexture(String fileName) {
		IconTexture cached = PNG_TEXTURES.get(fileName);
		if (cached != null) return cached;
		if (FAILED_PNGS.contains(fileName) || PNG_TEXTURES.size() >= 128) return null;
		Path directory = InventoryButtonsModule.INSTANCE.iconDirectory().toAbsolutePath().normalize();
		Path path = directory.resolve(fileName).normalize();
		if (!path.startsWith(directory)) return null;
		try {
			long size = Files.size(path);
			if (size <= 0 || size > 4 * 1024 * 1024) throw new IOException("PNG exceeds the 4 MiB icon limit");
			NativeImage image;
			try (InputStream input = Files.newInputStream(path)) { image = NativeImage.read(input); }
			if (image.getWidth() < 1 || image.getHeight() < 1 || image.getWidth() > 1024 || image.getHeight() > 1024
				|| (long) image.getWidth() * image.getHeight() > 1_048_576) {
				image.close();
				throw new IOException("PNG dimensions exceed the 1024×1024 icon limit");
			}
			Identifier id = GeilerAddons.id("inventory_button_icons/" + textureKey(fileName));
			Minecraft.getInstance().getTextureManager().register(id,
				new DynamicTexture(() -> "GeilerAddons inventory icon " + fileName, image));
			IconTexture created = new IconTexture(id, image.getWidth(), image.getHeight());
			PNG_TEXTURES.put(fileName, created);
			return created;
		} catch (IOException | RuntimeException error) {
			FAILED_PNGS.add(fileName);
			return null;
		}
	}

	private static String textureKey(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(24);
			for (int index = 0; index < 12; index++) hex.append(String.format(Locale.ROOT, "%02x", digest[index]));
			return hex.toString();
		} catch (NoSuchAlgorithmException impossible) {
			return Integer.toUnsignedString(value.hashCode(), 16);
		}
	}

	private static Panel panel(int left, int top, int inventoryWidth, int screenWidth, int screenHeight,
		List<InventoryButtonLayout.Bounds> occupied) {
		int panelWidth = Math.max(1, Math.min(PANEL_WIDTH, screenWidth - 8));
		int panelHeight = Math.max(1, Math.min(PANEL_HEIGHT, screenHeight - 8));
		int rowHeight = Math.max(10, Math.min(PANEL_ROW_HEIGHT,
			(panelHeight - PANEL_ROW_TOP - 12) / 8));
		int sideMargin = Math.max(0, Math.min(8, (screenWidth - panelWidth) / 2));
		int leftSideX = sideMargin;
		int rightSideX = screenWidth - panelWidth - sideMargin;
		int y = Math.max(4, Math.min(screenHeight - panelHeight - 4, top));
		boolean leftSideClear = panelLocationClear(leftSideX, y, panelWidth, panelHeight, occupied);
		boolean rightSideClear = panelLocationClear(rightSideX, y, panelWidth, panelHeight, occupied);
		int x;
		if (rightSideClear) x = rightSideX;
		else if (leftSideClear) x = leftSideX;
		else x = Math.max(4, Math.min(screenWidth - panelWidth - 4, left + inventoryWidth + 6));
		return new Panel(x, y, panelWidth, panelHeight, rowHeight);
	}

	private static boolean panelLocationClear(int x, int y, int width, int height,
		List<InventoryButtonLayout.Bounds> occupied) {
		for (InventoryButtonLayout.Bounds bounds : occupied) {
			if (bounds != null && bounds.intersects(x, y, width, height)) return false;
		}
		return true;
	}

	private static List<InventoryButtonLayout.Bounds> vanillaBounds(AbstractContainerScreen<?> screen) {
		int left = left(screen);
		int top = top(screen);
		List<InventoryButtonLayout.Bounds> result = new ArrayList<>();
		result.add(InventoryButtonLayout.inventoryBounds(left, top, imageWidth(screen), imageHeight(screen)));
		if (screen instanceof AbstractRecipeBookScreen<?> recipeBookScreen) {
			RecipeBookComponent<?> recipeBook = ((AbstractRecipeBookScreenAccessor) recipeBookScreen)
				.geileraddons$getRecipeBookComponent();
			if (recipeBook.isVisible()) {
				RecipeBookComponentInvoker invoker = (RecipeBookComponentInvoker) recipeBook;
				result.add(InventoryButtonLayout.inventoryBounds(invoker.geileraddons$getXOrigin(),
					invoker.geileraddons$getYOrigin(), RecipeBookComponent.IMAGE_WIDTH, RecipeBookComponent.IMAGE_HEIGHT));
			}
		}
		return List.copyOf(result);
	}

	private static List<InventoryButtonLayout.Bounds> editingBounds(
		List<InventoryButtonLayout.Bounds> vanillaBounds, Panel panel) {
		List<InventoryButtonLayout.Bounds> result = new ArrayList<>(vanillaBounds);
		result.add(panel.bounds());
		return List.copyOf(result);
	}

	private static int left(AbstractContainerScreen<?> screen) {
		return ((AbstractContainerScreenInvoker) (Object) screen).geileraddons$getLeftPos();
	}

	private static int top(AbstractContainerScreen<?> screen) {
		return ((AbstractContainerScreenInvoker) (Object) screen).geileraddons$getTopPos();
	}

	private static int imageWidth(AbstractContainerScreen<?> screen) {
		return ((AbstractContainerScreenInvoker) (Object) screen).geileraddons$getImageWidth();
	}

	private static int imageHeight(AbstractContainerScreen<?> screen) {
		return ((AbstractContainerScreenInvoker) (Object) screen).geileraddons$getImageHeight();
	}

	private static Component buttonTooltip(InventoryButtonPlacement placement, MacroDefinition macro,
		InventoryButtonRules.Eligibility eligibility, boolean editSurface) {
		List<String> lines = new ArrayList<>();
		if (!placement.hoverTooltip().isBlank()) lines.add(placement.hoverTooltip());
		lines.add(macro == null ? "Unassigned button" : macro.name());
		if (eligibility != null && !eligibility.eligible()) lines.add(eligibility.reason());
		else if (editSurface) lines.add("Click to select · drag or use Move to reposition");
		else lines.add("Click to run");
		return Component.literal(String.join("\n", lines));
	}

	private static String macroName(int id) {
		MacroDefinition macro = MacrosModule.INSTANCE.macro(id);
		return macro == null ? "Macro " + id : macro.name();
	}

	private static int screenWidth() { return Minecraft.getInstance().getWindow().getGuiScaledWidth(); }
	private static int screenHeight() { return Minecraft.getInstance().getWindow().getGuiScaledHeight(); }
	private static String trim(String value, int maxWidth) {
		String text = value == null ? "" : value;
		var font = Minecraft.getInstance().font;
		if (font.width(text) <= maxWidth) return text;
		int end = text.length();
		while (end > 0 && font.width(text.substring(0, end) + "…") > maxWidth) end--;
		return end == 0 ? "…" : text.substring(0, end) + "…";
	}

	private record Panel(int x, int y, int width, int height, int rowHeight) {
		InventoryButtonLayout.Bounds bounds() {
			return new InventoryButtonLayout.Bounds(x, y, width, height);
		}

		boolean contains(int pointX, int pointY) {
			return pointX >= x && pointX < x + width && pointY >= y && pointY < y + height;
		}
	}
	private record IconTexture(Identifier id, int width, int height) { }
}
