package geiler.addons.client.gui;

import geiler.addons.client.dungeon.DungeonContextTracker;
import geiler.addons.client.dungeon.DungeonFloor;
import geiler.addons.client.dungeon.DungeonGuideCustomSegment;
import geiler.addons.client.dungeon.DungeonGuideNode;
import geiler.addons.client.dungeon.DungeonGuideSegments;
import geiler.addons.client.dungeon.DungeonGuideStore;
import geiler.addons.client.module.impl.DungeonHelperModule;
import geiler.addons.client.module.impl.MacrosModule;
import geiler.addons.client.module.impl.VisualModule;
import geiler.addons.client.macro.MacroDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

import static geiler.addons.client.gui.GuiTheme.*;

/** Compact one-route-per-floor editor with a phase-grouped list and selected-step inspector. */
public final class DungeonGuideEditorScreen extends Screen {
	private final Screen parent;
	private DungeonFloor editingFloor;
	private String selectedId;
	private String searchText = "";
	private String macroSearchText = "";
	private String notice = "";
	private boolean advancedOpen;
	private boolean shapePickerOpen;
	private String simulatedPhase;
	private boolean rebuilding;
	private EditBox search;
	private EditBox routeName;
	private EditBox macroSearch;
	private int listScroll;
	private int detailScroll;
	private int macroScroll;
	private long cachedRowsRevision = Long.MIN_VALUE;
	private DungeonFloor cachedRowsFloor;
	private String cachedRowsSearch;
	private String cachedRowsSimulation;
	private List<ListRow> cachedRows = List.of();

	public DungeonGuideEditorScreen(Screen parent) {
		super(Component.literal("Dungeon Guide"));
		this.parent = parent;
		this.editingFloor = DungeonContextTracker.currentFloor();
		DungeonGuideStore.load();
	}

	@Override public boolean isPauseScreen() { return false; }

	@Override protected void init() { rebuildEditorWidgets(); }

	private void rebuildEditorWidgets() {
		rebuilding = true;
		clearWidgets();
		macroSearch = null;
		Layout layout = layout();
		search = addEdit(layout.leftX + 8, layout.y + 60, Math.max(1, layout.leftWidth - 16), 19,
			"Search steps", searchText, value -> { searchText = value; listScroll = 0; });
		String floorName = editingFloor == null ? null : editingFloor.displayName();
		routeName = addEdit(layout.rightX + 8, layout.y + 52, Math.max(1, layout.rightWidth - 16), 19,
			floorName == null ? "Choose a floor before naming a route" : "Route name for " + floorName,
			floorName == null ? "" : DungeonGuideStore.routeName(floorName), value -> {
				if (rebuilding) return;
				if (editingFloor == null) {
					notice = "Choose a floor before naming a route";
					return;
				}
				if (!DungeonGuideStore.setRouteName(editingFloor.displayName(), value))
					notice = "Route name must be 1–48 characters";
			});
		DungeonGuideNode node = selected();
		if (node != null) {
			int contentY = layout.bodyTop - detailScroll;
			int contentBottom = layout.footerTop;
			int x = layout.rightX + 8;
			int w = Math.max(1, layout.rightWidth - 16);
			int row = 28;
			final int labelY = contentY;
			addIfVisible(labelY, contentBottom, () -> addEdit(x, labelY, w, 19, "Step label", node.label,
				value -> updateSelected(item -> item.label = value)));
			contentY += row;
			int gap = 4;
			int posWidth = Math.max(1, (w - gap * 2) / 3);
			final int py = contentY;
			addIfVisible(py, contentBottom, () -> addEdit(x, py, posWidth, 19, "X", String.valueOf(node.x),
				value -> updateSelected(item -> item.x = parseInt(value, item.x))));
			addIfVisible(py, contentBottom, () -> addEdit(x + posWidth + gap, py, posWidth, 19, "Y", String.valueOf(node.y),
				value -> updateSelected(item -> item.y = parseInt(value, item.y))));
			addIfVisible(py, contentBottom, () -> addEdit(x + (posWidth + gap) * 2, py, posWidth, 19, "Z", String.valueOf(node.z),
				value -> updateSelected(item -> item.z = parseInt(value, item.z))));
			contentY += row;
			contentY += row; // phase selector
			contentY += row; // condition rule
			contentY += row; // condition row one
			contentY += 22;  // condition row two
			final int paramY = contentY;
			int half = Math.max(1, (w - gap) / 2);
			addIfVisible(paramY, contentBottom, () -> addEdit(x, paramY, half, 19, "Radius", String.format(Locale.ROOT, "%.2f", node.triggerRadius),
				value -> updateSelected(item -> item.triggerRadius = parseFloat(value, item.triggerRadius))));
			addIfVisible(paramY, contentBottom, () -> addEdit(x + half + gap, paramY, half, 19, "Delay seconds", String.format(Locale.ROOT, "%.1f", node.triggerSeconds),
				value -> updateSelected(item -> item.triggerSeconds = parseFloat(value, item.triggerSeconds))));
			contentY += row;
			final int eventY = contentY;
			addIfVisible(eventY, contentBottom, () -> addEdit(x, eventY, w, 19, "Exact chat event", node.eventText,
				value -> updateSelected(item -> item.eventText = value)));
			contentY += row; // exact chat input
			contentY += row; // advanced toggle
			if (advancedOpen) {
				final int sizeY = contentY;
				addIfVisible(sizeY, contentBottom, () -> addEdit(x, sizeY, half, 19, "Marker size", String.format(Locale.ROOT, "%.2f", node.size),
					value -> updateSelected(item -> item.size = parseFloat(value, item.size))));
				addIfVisible(sizeY, contentBottom, () -> addEdit(x + half + gap, sizeY, half, 19, "Rotation", String.format(Locale.ROOT, "%.1f", node.rotation),
					value -> updateSelected(item -> item.rotation = parseFloat(value, item.rotation))));
				contentY += row;
				// The custom color swatches below open the same hue/saturation/alpha picker as Click GUI.
				contentY += row;
				final int textY = contentY;
				addIfVisible(textY, contentBottom, () -> addEdit(x + half + gap, textY, half, 19, "Text scale", String.format(Locale.ROOT, "%.2f", node.labelScale),
					value -> updateSelected(item -> item.labelScale = parseFloat(value, item.labelScale))));
				contentY += row;
				final int rangeY = contentY;
				addIfVisible(rangeY, contentBottom, () -> addEdit(x, rangeY, half, 19, "Visibility range (0 = default)", String.valueOf(node.visibilityDistance),
					value -> updateSelected(item -> item.visibilityDistance = parseInt(value, item.visibilityDistance))));
				contentY += row;
				if (node.shape == DungeonGuideNode.Shape.RING) {
					contentY += 20; // compact group label before this ring's appearance controls
					final int ringRowOne = contentY;
					addIfVisible(ringRowOne, contentBottom, () -> addEdit(x, ringRowOne, half, 19, "Ring count (1–8)", String.valueOf(node.ringCountValue()),
						value -> updateSelected(item -> item.ringCount = parseInt(value, item.ringCountValue()))));
					addIfVisible(ringRowOne, contentBottom, () -> addEdit(x + half + gap, ringRowOne, half, 19, "Ring height (0.25–8)", String.format(Locale.ROOT, "%.2f", node.ringHeightValue()),
						value -> updateSelected(item -> item.ringHeight = parseFloat(value, item.ringHeightValue()))));
					contentY += row;
					final int ringRowTwo = contentY;
					addIfVisible(ringRowTwo, contentBottom, () -> addEdit(x, ringRowTwo, half, 19, "Ring radius (0.25–8)", String.format(Locale.ROOT, "%.2f", node.ringRadiusValue()),
						value -> updateSelected(item -> item.ringRadius = parseFloat(value, item.ringRadiusValue()))));
					addIfVisible(ringRowTwo, contentBottom, () -> addEdit(x + half + gap, ringRowTwo, half, 19, "Ring speed (0.05–2)", String.format(Locale.ROOT, "%.2f", node.ringSpeedValue()),
						value -> updateSelected(item -> item.ringSpeed = parseFloat(value, item.ringSpeedValue()))));
					contentY += row;
					final int ringRowThree = contentY;
					addIfVisible(ringRowThree, contentBottom, () -> addEdit(x, ringRowThree, half, 19, "Ring line width (0.5–5)", String.format(Locale.ROOT, "%.2f", node.ringWidthValue()),
						value -> updateSelected(item -> item.ringWidth = parseFloat(value, item.ringWidthValue()))));
					contentY += row;
				}
			}
			if (advancedOpen) {
				final int macroY = contentY;
				if (visible(macroY, contentBottom)) {
					macroSearch = addEdit(x, macroY, w, 19, "Search macros", macroSearchText, value -> {
						macroSearchText = value; macroScroll = 0;
					});
				}
			}
		}
		rebuilding = false;
	}

	private void addIfVisible(int y, int bottom, Runnable add) { if (visible(y, bottom)) add.run(); }
	private boolean visible(int y, int bottom) { return y >= layout().bodyTop && y + 19 <= bottom; }
	private EditBox addEdit(int x, int y, int w, int h, String hint, String value, Consumer<String> responder) {
		EditBox edit = new EditBox(font, x, y, Math.max(1, w), h, Component.literal(hint));
		edit.setMaxLength(128);
		edit.setValue(value == null ? "" : value);
		edit.setTooltip(Tooltip.create(Component.literal(editTooltip(hint))));
		edit.setResponder(responder);
		return addRenderableWidget(edit);
	}

	private static String editTooltip(String hint) {
		if (hint != null && hint.startsWith("Route name for "))
			return "Name shared by this floor's guide route. Keep it between 1 and 48 characters.";
		return switch (hint) {
			case "Search steps" -> "Filter this floor's step list by step label, phase name, or exact chat text.";
			case "Step label" -> "Short name shown above this step's marker and in the guide status.";
			case "X" -> "World X block coordinate for the step marker. Use Add at Feet or Add at Aim to fill coordinates automatically.";
			case "Y" -> "World Y block coordinate for the step marker. Use Add at Feet or Add at Aim to fill coordinates automatically.";
			case "Z" -> "World Z block coordinate for the step marker. Use Add at Feet or Add at Aim to fill coordinates automatically.";
			case "Radius" -> "Distance in blocks that advances this step when the player enters its radius.";
			case "Delay seconds" -> "Seconds to wait after the previous step before advancing.";
			case "Exact chat event" -> "Exact phrase to watch for. Select Exact chat in the condition buttons to use it.";
			case "Marker size" -> "Size of this step's world marker in blocks.";
			case "Rotation" -> "Rotation in degrees for this step's 3D box.";
			case "Text scale" -> "Scale of the floating step label.";
			case "Visibility range (0 = default)" -> "Maximum render distance in blocks. Zero uses the module's Render Range setting.";
			case "Ring count (1–8)" -> "Number of moving rings for this step.";
			case "Ring height (0.25–8)" -> "Vertical distance covered by this step's rings, scaled by Marker size.";
			case "Ring radius (0.25–8)" -> "Base radius in blocks for this step's rings, scaled by Marker size.";
			case "Ring speed (0.05–2)" -> "Movement speed of this step's rings.";
			case "Ring line width (0.5–5)" -> "Line thickness of this step's rings.";
			case "Search macros" -> "Filter the macro list below, then click a result to link it to this step.";
		default -> "Edit this value. Coordinates use world blocks; numeric values accept decimal points.";
		};
	}

	@Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		VisualModule.INSTANCE.refreshTheme();
		graphics.fill(0, 0, width, height, DIALOG_SHADE);
		Layout l = layout();
		roundedRectBordered(graphics, l.x, l.y, l.width, l.height, RADIUS, PANEL_TOP, PANEL_BOTTOM, BORDER);
		graphics.text(font, fit("Dungeon Guide · Route Editor", l.width - 24), l.x + 12, l.y + 8, TEXT_PRIMARY);
		graphics.text(font, fit("One named route per floor · steps advance in phase order", l.width - 24),
			l.x + 12, l.y + 22, TEXT_MUTED);
		drawButton(graphics, new Rect(l.x + 12, l.y + 38, Math.max(1, l.leftWidth - 24), 19),
			"Floor: " + (editingFloor == null ? "Unknown" : editingFloor.displayName()), mouseX, mouseY);
		graphics.fill(l.rightX, l.y + 34, l.rightX + 1, l.y + l.height - 59, BORDER);
		Rect simulation = simulationButton(l);
		if (simulation.x > l.rightX + 62)
			graphics.text(font, "Floor route", l.rightX + 8, l.y + 40, TEXT_MUTED);
		drawButton(graphics, simulation, simulationLabel(), mouseX, mouseY);
		renderStepList(graphics, l, mouseX, mouseY);
		renderInspector(graphics, l, mouseX, mouseY);
		renderFooter(graphics, l, mouseX, mouseY);
		if (!notice.isBlank()) graphics.text(font, fit(notice, l.width - 24), l.x + 12, l.y + l.height - 11, TEXT_MUTED);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	private void renderStepList(GuiGraphicsExtractor graphics, Layout l, int mouseX, int mouseY) {
		List<ListRow> rows = rows();
		int rowHeight = 22;
		int visible = Math.max(0, (l.footerTop - l.bodyTop) / rowHeight);
		listScroll = Math.max(0, Math.min(listScroll, Math.max(0, rows.size() - visible)));
		graphics.enableScissor(l.leftX + 5, l.bodyTop, l.leftX + l.leftWidth - 3, l.footerTop);
		if (editingFloor == null) {
			graphics.text(font, fit("Choose a floor above to view its route.", l.leftWidth - 18),
				l.leftX + 10, l.bodyTop + 8, TEXT_MUTED);
		} else if (rows.isEmpty()) {
			graphics.text(font, simulatedPhase == null ? "No steps yet. Add one below."
				: "No steps in this simulated phase.", l.leftX + 10, l.bodyTop + 8, TEXT_MUTED);
		}
		for (int i = listScroll; i < Math.min(rows.size(), listScroll + visible); i++) {
			ListRow row = rows.get(i);
			int y = l.bodyTop + (i - listScroll) * rowHeight;
			if (row.node == null) {
				graphics.text(font, row.phaseLabel, l.leftX + 9, y + 5, TEXT_MUTED);
				continue;
			}
			boolean selected = row.node.id.equals(selectedId);
			boolean hovered = mouseX >= l.leftX + 5 && mouseX < l.leftX + l.leftWidth - 3 && mouseY >= y && mouseY < y + rowHeight;
			if (selected || hovered) roundedRect(graphics, l.leftX + 5, y, l.leftWidth - 9, rowHeight - 1,
				RADIUS_SMALL, selected ? CATEGORY_SELECTED : CATEGORY_HOVER);
			graphics.fill(l.leftX + 10, y + 8, l.leftX + 15, y + 13, row.node.color);
			graphics.text(font, fit(row.node.label, l.leftWidth - 36), l.leftX + 20, y + 4, TEXT_PRIMARY);
		}
		graphics.disableScissor();
	}

	private void renderInspector(GuiGraphicsExtractor graphics, Layout l, int mouseX, int mouseY) {
		graphics.enableScissor(l.rightX + 5, l.bodyTop, l.rightX + l.rightWidth - 3, l.footerTop);
		DungeonGuideNode node = selected();
		if (node == null) {
			String emptyState = editingFloor == null
				? "Choose a floor before adding or editing route steps."
				: "Select a step or add one at your position.";
			graphics.text(font, fit(emptyState, l.rightWidth - 18), l.rightX + 10, l.bodyTop + 8, TEXT_MUTED);
			graphics.disableScissor();
			return;
		}
		int x = l.rightX + 8;
		int w = Math.max(1, l.rightWidth - 16);
		int row = 28;
		int y = l.bodyTop - detailScroll;
		y += row * 2;
		drawButton(graphics, new Rect(x, y, (w - 4) / 2, 19), "Phase: " + phaseLabel(node), mouseX, mouseY);
		drawButton(graphics, new Rect(x + (w + 4) / 2, y, (w - 4) / 2, 19), "Shape: " + shapeLabel(node.shape), mouseX, mouseY);
		y += row;
		drawButton(graphics, new Rect(x, y, Math.max(1, w), 19), "Condition rule: " + node.conditionRule, mouseX, mouseY);
		y += row;
		String[] c1 = {"Manual", "Radius", "Delay"};
		String[] c2 = {"Exact chat", "Detected phase"};
		drawConditionRow(graphics, node, c1, x, y, w, mouseX, mouseY);
		y += 22;
		drawConditionRow(graphics, node, c2, x, y, w, mouseX, mouseY);
		y += row;
		if (node.hasCondition(DungeonGuideNode.Condition.ENTER_RADIUS)
			|| node.hasCondition(DungeonGuideNode.Condition.AFTER_SECONDS)) {
			// Parameter fields at this row are self-labeled by their placeholders.
		}
		if (node.hasCondition(DungeonGuideNode.Condition.CHAT_EVENT)) {
			// Exact chat input is available on the following row.
		}
		y += row * 2;
		drawButton(graphics, new Rect(x, y, w, 19), advancedOpen ? "Appearance and macro ▾" : "Appearance and macro ▸", mouseX, mouseY);
		if (advancedOpen) {
			int half = Math.max(1, (w - 4) / 2);
			int gap = 4;
			int colorY = y + 56;
			drawColorPickerButton(graphics, new Rect(x, colorY, half, 19), "Outline color", node.color, mouseX, mouseY);
			drawColorPickerButton(graphics, new Rect(x + half + gap, colorY, half, 19), "Fill color", node.fillColor, mouseX, mouseY);
			int textY = y + 84;
			drawColorPickerButton(graphics, new Rect(x, textY, half, 19), "Text color", node.labelColor, mouseX, mouseY);
			if (macroSearch != null && macroSearch.getY() >= l.bodyTop)
				renderMacroResults(graphics, l, node, mouseX, mouseY);
			if (node.shape == DungeonGuideNode.Shape.RING) {
				int ringHeaderY = l.bodyTop - detailScroll + 358;
				graphics.text(font, "Ring appearance · this step", x, ringHeaderY + 5, TEXT_MUTED);
				drawButton(graphics, ringFillBounds(l), "Fill interior: " + (node.ringFillEnabled() ? "On" : "Off"), mouseX, mouseY,
					node.ringFillEnabled() ? CATEGORY_SELECTED : CARD_BG);
			}
		}
		if (shapePickerOpen) renderShapePicker(graphics, l, node, mouseX, mouseY);
		graphics.disableScissor();
	}

	private void drawColorPickerButton(GuiGraphicsExtractor graphics, Rect bounds, String label,
		int argb, int mouseX, int mouseY) {
		boolean hovered = inside(mouseX, mouseY, bounds.x, bounds.y, bounds.w, bounds.h);
		int fill = hovered ? CARD_BG_HOVER : CARD_BG;
		roundedRectBordered(graphics, bounds.x, bounds.y, bounds.w, bounds.h, RADIUS_SMALL, fill, fill, BORDER);
		int size = Math.min(12, Math.max(7, bounds.h - 6));
		int x = bounds.x + 4;
		int y = bounds.y + (bounds.h - size) / 2;
		graphics.fill(x, y, x + size, y + size, 0xFF777777);
		graphics.fill(x + 2, y + 2, x + size - 1, y + size - 1, argb);
		graphics.centeredText(font, fit(label, Math.max(1, bounds.w - size - 12)),
			bounds.x + size + 8 + Math.max(1, (bounds.w - size - 12) / 2),
			bounds.y + (bounds.h - font.lineHeight) / 2, TEXT_PRIMARY);
	}

	private Rect ringFillBounds(Layout l) {
		int w = Math.max(1, l.rightWidth - 16);
		int half = Math.max(1, (w - 4) / 2);
		int x = l.rightX + 8;
		int ringRowThree = l.bodyTop - detailScroll + 378 + 2 * 28;
		return new Rect(x + half + 4, ringRowThree, half, 19);
	}

	private void renderShapePicker(GuiGraphicsExtractor graphics, Layout l, DungeonGuideNode node,
		int mouseX, int mouseY) {
		Rect[] options = shapeOptions(l);
		Rect panel = new Rect(options[0].x - 3, options[0].y - 3,
			options[1].x + options[1].w - options[0].x + 6,
			options[2].y + options[2].h - options[0].y + 6);
		roundedRectBordered(graphics, panel.x, panel.y, panel.w, panel.h, RADIUS_SMALL, PANEL_TOP, PANEL_BOTTOM, BORDER);
		String[] labels = {"3D Box", "Beacon", "Ring", "Text"};
		for (int i = 0; i < options.length; i++) {
			int fill = node.shape.ordinal() == i ? CATEGORY_SELECTED : CARD_BG;
			drawButton(graphics, options[i], labels[i], mouseX, mouseY, fill);
		}
	}

	private Rect[] shapeOptions(Layout l) {
		int w = Math.max(1, l.rightWidth - 16);
		int half = Math.max(1, (w - 4) / 2);
		int x = l.rightX + 8 + (w + 4) / 2;
		int y = l.bodyTop - detailScroll + 2 * 28 + 22;
		int gap = 3;
		int cellWidth = Math.max(1, (half - gap) / 2);
		return new Rect[]{
			new Rect(x, y, cellWidth, 19), new Rect(x + cellWidth + gap, y, cellWidth, 19),
			new Rect(x, y + 22, cellWidth, 19), new Rect(x + cellWidth + gap, y + 22, cellWidth, 19)
		};
	}

	private void drawConditionRow(GuiGraphicsExtractor graphics, DungeonGuideNode node, String[] labels,
		int x, int y, int width, int mouseX, int mouseY) {
		int gap = 3;
		int buttonWidth = (width - gap * (labels.length - 1)) / labels.length;
		for (int i = 0; i < labels.length; i++) {
			DungeonGuideNode.Condition condition = conditionFor(labels[i]);
			boolean active = node.hasCondition(condition);
			int left = x + i * (buttonWidth + gap);
			drawButton(graphics, new Rect(left, y, buttonWidth, 19), (active ? "✓ " : "□ ") + labels[i], mouseX, mouseY,
				active ? CATEGORY_SELECTED : CARD_BG);
		}
	}

	private void renderMacroResults(GuiGraphicsExtractor graphics, Layout l, DungeonGuideNode node, int mouseX, int mouseY) {
		int x = l.rightX + 8;
		int y = macroSearch.getY() + 21;
		int row = 19;
		int visible = Math.max(0, (l.footerTop - y) / row);
		List<MacroDefinition> macros = filteredMacros();
		macroScroll = Math.max(0, Math.min(macroScroll, Math.max(0, macros.size() - visible)));
		if (visible > 0) graphics.enableScissor(x, y, l.rightX + l.rightWidth - 8, l.footerTop);
		for (int i = macroScroll; i < Math.min(macros.size(), macroScroll + visible); i++) {
			MacroDefinition macro = macros.get(i);
			int rowY = y + (i - macroScroll) * row;
			String value = (node.macroId == macro.id() ? "✓ " : "") + macro.name();
			drawButton(graphics, new Rect(x, rowY, l.rightWidth - 16, row - 1), fit(value, l.rightWidth - 28), mouseX, mouseY);
		}
		if (visible > 0) graphics.disableScissor();
	}

	private void renderFooter(GuiGraphicsExtractor graphics, Layout l, int mouseX, int mouseY) {
		String[] actions = {"Add at Feet", "Add at Aim", "Copy Route", "Paste Route", "Delete Step", "Done"};
		int columns = 3;
		int gap = 4;
		int buttonWidth = (l.width - 16 - gap * (columns - 1)) / columns;
		for (int i = 0; i < actions.length; i++) {
			int x = l.x + 8 + (i % columns) * (buttonWidth + gap);
			int y = l.footerTop + (i / columns) * 21;
			drawButton(graphics, new Rect(x, y, buttonWidth, 19), actions[i], mouseX, mouseY);
		}
	}

	@Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		Layout l = layout();
		if (shapePickerOpen && event.button() == 0) {
			Rect[] options = shapeOptions(l);
			for (int i = 0; i < options.length; i++) {
				Rect option = options[i];
				if (inside(event.x(), event.y(), option.x, option.y, option.w, option.h)) {
					DungeonGuideNode node = selected();
					if (node != null) {
						node.shape = DungeonGuideNode.Shape.values()[i];
						if (node.shape == DungeonGuideNode.Shape.RING) node.useDefaultRingAppearanceIfUnset();
						DungeonGuideStore.changed();
					}
					shapePickerOpen = false;
					rebuildEditorWidgets();
					return true;
				}
			}
			shapePickerOpen = false;
			rebuildEditorWidgets();
		}
		if (super.mouseClicked(event, doubleClick)) return true;
		if (event.button() != 0) return false;
		Rect simulation = simulationButton(l);
		if (inside(event.x(), event.y(), simulation.x, simulation.y, simulation.w, simulation.h)) {
			cycleSimulationPhase();
			return true;
		}
		if (inside(event.x(), event.y(), l.x + 12, l.y + 38, Math.max(1, l.leftWidth - 24), 19)) {
			DungeonFloor[] floors = DungeonFloor.values();
			editingFloor = editingFloor == null ? floors[0] : floors[(editingFloor.ordinal() + 1) % floors.length];
			simulatedPhase = null;
			selectedId = null; listScroll = detailScroll = macroScroll = 0;
			rebuildEditorWidgets(); return true;
		}
		int action = actionIndex(event.x(), event.y(), l);
		if (action >= 0) {
			switch (action) {
				case 0 -> addAtPosition(false);
				case 1 -> addAtPosition(true);
				case 2 -> copyRoute();
				case 3 -> pasteRoute();
				case 4 -> removeSelected();
				case 5 -> onClose();
			}
			return true;
		}
		DungeonGuideNode node = selected();
		if (node != null) {
			int x = l.rightX + 8;
			int w = Math.max(1, l.rightWidth - 16);
			int y = l.bodyTop - detailScroll + 2 * 28;
			int half = (w - 4) / 2;
			if (inside(event.x(), event.y(), x, y, half, 19)) { cyclePhase(node); return true; }
			if (inside(event.x(), event.y(), x + half + 4, y, half, 19)) {
				shapePickerOpen = !shapePickerOpen;
				rebuildEditorWidgets(); return true;
			}
			y += 28;
			if (inside(event.x(), event.y(), x, y, w, 19)) {
				node.conditionRule = node.conditionRule == DungeonGuideNode.ConditionRule.ALL
					? DungeonGuideNode.ConditionRule.ANY : DungeonGuideNode.ConditionRule.ALL;
				DungeonGuideStore.changed(); rebuildEditorWidgets(); return true;
			}
			y += 28;
			if (hitConditionRow(event.x(), event.y(), node, new String[]{"Manual", "Radius", "Delay"}, x, y, w)) return true;
			y += 22;
			if (hitConditionRow(event.x(), event.y(), node, new String[]{"Exact chat", "Detected phase"}, x, y, w)) return true;
			y += 84;
			if (inside(event.x(), event.y(), x, y, w, 19)) { advancedOpen = !advancedOpen; detailScroll = 0; rebuildEditorWidgets(); return true; }
			if (advancedOpen) {
				int colorHalf = Math.max(1, (w - 4) / 2);
				int colorY = y + 56;
				if (inside(event.x(), event.y(), x, colorY, colorHalf, 19)) {
					openColorPicker("Outline color", node.color, 0);
					return true;
				}
				if (inside(event.x(), event.y(), x + colorHalf + 4, colorY, colorHalf, 19)) {
					openColorPicker("Fill color", node.fillColor, 1);
					return true;
				}
				if (inside(event.x(), event.y(), x, y + 84, colorHalf, 19)) {
					openColorPicker("Text color", node.labelColor, 2);
					return true;
				}
				if (node.shape == DungeonGuideNode.Shape.RING) {
					Rect fillBounds = ringFillBounds(l);
					if (inside(event.x(), event.y(), fillBounds.x, fillBounds.y, fillBounds.w, fillBounds.h)) {
						node.ringFill = !node.ringFillEnabled();
						DungeonGuideStore.changed();
						rebuildEditorWidgets();
						return true;
					}
				}
			}
			if (advancedOpen && macroSearch != null) {
				int macroY = macroSearch.getY() + 21;
				if (event.x() >= x && event.x() < x + w && event.y() >= macroY && event.y() < l.footerTop) {
					int index = macroScroll + (int) ((event.y() - macroY) / 19);
					List<MacroDefinition> macros = filteredMacros();
					if (index >= 0 && index < macros.size()) updateSelected(item -> item.macroId = macros.get(index).id());
					return true;
				}
			}
		}
		if (event.x() >= l.leftX + 5 && event.x() < l.leftX + l.leftWidth - 3
			&& event.y() >= l.bodyTop && event.y() < l.footerTop) {
			int index = listScroll + (int) ((event.y() - l.bodyTop) / 22);
			List<ListRow> rows = rows();
			if (index >= 0 && index < rows.size() && rows.get(index).node != null) {
				selectedId = rows.get(index).node.id; detailScroll = macroScroll = 0; rebuildEditorWidgets();
			}
			return true;
		}
		return false;
	}

	private boolean hitConditionRow(double mx, double my, DungeonGuideNode node, String[] labels, int x, int y, int width) {
		int gap = 3;
		int buttonWidth = (width - gap * (labels.length - 1)) / labels.length;
		if (my < y || my >= y + 19) return false;
		for (int i = 0; i < labels.length; i++) {
			int left = x + i * (buttonWidth + gap);
			if (mx < left || mx >= left + buttonWidth) continue;
			DungeonGuideNode.Condition condition = conditionFor(labels[i]);
			Set<DungeonGuideNode.Condition> updated = node.conditions == null
				? EnumSet.noneOf(DungeonGuideNode.Condition.class) : EnumSet.copyOf(node.conditions);
			if (updated.contains(condition)) updated.remove(condition); else updated.add(condition);
			if (updated.isEmpty()) updated.add(DungeonGuideNode.Condition.MANUAL);
			node.conditions = updated;
			node.updateLegacyTrigger();
			DungeonGuideStore.changed(); rebuildEditorWidgets(); return true;
		}
		return false;
	}

	private void cyclePhase(DungeonGuideNode node) {
		List<String> phases = phaseIds();
		int current = phases.indexOf(node.phase);
		node.phase = phases.get((current + 1 + phases.size()) % phases.size());
		DungeonGuideStore.changed(); rebuildEditorWidgets();
	}

	private void cycleSimulationPhase() {
		List<String> phases = phaseIds();
		if (phases.isEmpty()) {
			simulatedPhase = null;
			notice = "Choose a floor before simulating a phase";
			return;
		}
		int index = phases.indexOf(simulatedPhase);
		simulatedPhase = index < 0 ? phases.get(0) : index + 1 >= phases.size() ? null : phases.get(index + 1);
		listScroll = 0;
		if (simulatedPhase == null) {
			notice = "Phase simulation off; the live route was not changed";
		} else {
			List<ListRow> previewRows = rows();
			selectedId = previewRows.stream().filter(row -> row.node != null)
				.map(row -> row.node.id).findFirst().orElse(null);
			notice = "Previewing " + phaseLabelFor(simulatedPhase) + " steps; the live route was not changed";
		}
		detailScroll = macroScroll = 0;
		rebuildEditorWidgets();
	}

	private String phaseLabelFor(String phaseId) {
		for (DungeonGuideSegments.Segment segment : DungeonGuideSegments.builtIn(editingFloor))
			if (segment.id().equals(phaseId)) return segment.label();
		for (DungeonGuideCustomSegment segment : DungeonGuideStore.customSegments(editingFloor))
			if (segment.id.equals(phaseId)) return segment.label;
		return phaseId == null ? "Off" : phaseId.replace('_', ' ');
	}

	private Rect simulationButton(Layout l) {
		int w = Math.min(132, Math.max(94, l.rightWidth / 2));
		return new Rect(l.rightX + l.rightWidth - w - 8, l.y + 36, w, 19);
	}

	private String simulationLabel() {
		return simulatedPhase == null ? "Simulate: Off" : "Simulate: " + phaseLabelFor(simulatedPhase);
	}

	private List<String> phaseIds() {
		if (editingFloor == null) return List.of();
		List<String> phases = new ArrayList<>();
		for (DungeonGuideSegments.Segment segment : DungeonGuideSegments.builtIn(editingFloor)) phases.add(segment.id());
		for (DungeonGuideCustomSegment segment : DungeonGuideStore.customSegments(editingFloor)) phases.add(segment.id);
		return phases.isEmpty() ? List.of("ENTRY") : phases;
	}

	private String phaseLabel(DungeonGuideNode node) {
		for (DungeonGuideSegments.Segment segment : DungeonGuideSegments.builtIn(editingFloor))
			if (segment.id().equals(node.phase)) return segment.label();
		for (DungeonGuideCustomSegment segment : DungeonGuideStore.customSegments(editingFloor))
			if (segment.id.equals(node.phase)) return segment.label;
		return node.phase.replace('_', ' ');
	}

	private static String shapeLabel(DungeonGuideNode.Shape shape) {
		return switch (shape) { case BOX -> "3D Box"; case BEACON -> "Beacon"; case RING -> "Ring"; case TEXT_ONLY -> "Text only"; };
	}

	private static DungeonGuideNode.Condition conditionFor(String label) {
		return switch (label) {
			case "Radius" -> DungeonGuideNode.Condition.ENTER_RADIUS;
			case "Delay" -> DungeonGuideNode.Condition.AFTER_SECONDS;
			case "Exact chat" -> DungeonGuideNode.Condition.CHAT_EVENT;
			case "Detected phase" -> DungeonGuideNode.Condition.DETECTED_PHASE;
			default -> DungeonGuideNode.Condition.MANUAL;
		};
	}

	private List<ListRow> rows() {
		long revision = DungeonGuideStore.guideRevision();
		if (cachedRowsRevision == revision && cachedRowsFloor == editingFloor
			&& java.util.Objects.equals(cachedRowsSearch, searchText)
			&& java.util.Objects.equals(cachedRowsSimulation, simulatedPhase)) return cachedRows;
		cachedRowsRevision = revision;
		cachedRowsFloor = editingFloor;
		cachedRowsSearch = searchText;
		cachedRowsSimulation = simulatedPhase;
		if (editingFloor == null) return cachedRows = List.of();
		String needle = searchText.trim().toLowerCase(Locale.ROOT);
		List<DungeonGuideNode> nodes = DungeonGuideStore.all().stream()
			.filter(node -> node.floor.equals(editingFloor.displayName()))
			.filter(node -> simulatedPhase == null || simulatedPhase.equals(node.phase))
			.filter(node -> needle.isEmpty() || node.label.toLowerCase(Locale.ROOT).contains(needle)
				|| node.phase.toLowerCase(Locale.ROOT).contains(needle) || node.eventText.toLowerCase(Locale.ROOT).contains(needle))
			.sorted(Comparator.comparingInt((DungeonGuideNode node) -> DungeonGuideStore.phaseOrder(node.floor, node.phase))
				.thenComparing(node -> node.phase).thenComparingInt(node -> node.order)).toList();
		List<ListRow> rows = new ArrayList<>();
		String previous = null;
		for (DungeonGuideNode node : nodes) {
			if (!node.phase.equals(previous)) {
				rows.add(new ListRow(null, phaseLabel(node)));
				previous = node.phase;
			}
			rows.add(new ListRow(node, ""));
		}
		return cachedRows = List.copyOf(rows);
	}

	private List<MacroDefinition> filteredMacros() {
		String needle = macroSearchText.trim().toLowerCase(Locale.ROOT);
		return MacrosModule.INSTANCE.macros().stream()
			.filter(macro -> needle.isEmpty() || macro.name().toLowerCase(Locale.ROOT).contains(needle))
			.sorted(Comparator.comparing(macro -> macro.name().toLowerCase(Locale.ROOT))).toList();
	}

	private void addAtPosition(boolean aim) {
		if (editingFloor == null) {
			notice = "Choose a floor before adding a route step";
			return;
		}
		Minecraft client = Minecraft.getInstance();
		BlockPos pos = client.player == null ? BlockPos.ZERO : client.player.blockPosition();
		if (aim && client.player != null) {
			HitResult hit = client.player.pick(6.0, 0, false);
			if (hit.getType() == HitResult.Type.BLOCK) pos = ((BlockHitResult) hit).getBlockPos();
		}
		List<DungeonGuideNode> floorNodes = DungeonGuideStore.all().stream()
			.filter(node -> node.floor.equals(editingFloor.displayName())).toList();
		String phase = selected() == null ? "ENTRY" : selected().phase;
		DungeonGuideNode node = new DungeonGuideNode(editingFloor, phase, floorNodes.size(), pos.getX(), pos.getY(), pos.getZ());
		node.routeName = DungeonGuideStore.routeName(editingFloor.displayName());
		node.label = "Step " + (floorNodes.size() + 1);
		DungeonGuideStore.add(node);
		selectedId = node.id;
		notice = "Added step to " + node.routeName;
		detailScroll = macroScroll = 0;
		rebuildEditorWidgets();
	}

	private void copyRoute() {
		String encoded = DungeonGuideStore.exportPackage();
		if (encoded == null) notice = "Route package exceeds the size limit";
		else { Minecraft.getInstance().keyboardHandler.setClipboard(encoded); notice = "Copied guide route"; }
	}
	private void pasteRoute() {
		if (editingFloor == null) {
			notice = "Choose a floor before importing route steps";
			return;
		}
		DungeonGuideStore.ImportResult result = DungeonGuideStore.importPackage(Minecraft.getInstance().keyboardHandler.getClipboard());
		if (result.success()) DungeonHelperModule.INSTANCE.migrateLegacyRingAppearance();
		notice = result.success() ? "Imported " + result.imported() + " step(s)" : result.error();
		rebuildEditorWidgets();
	}
	private void removeSelected() {
		DungeonGuideNode node = selected();
		if (node != null) { DungeonGuideStore.remove(node.id); selectedId = null; notice = "Step deleted"; rebuildEditorWidgets(); }
	}
	private void updateSelected(Consumer<DungeonGuideNode> change) {
		DungeonGuideNode node = selected();
		if (node != null) { change.accept(node); DungeonGuideStore.changed(); }
	}
	private DungeonGuideNode selected() {
		for (DungeonGuideNode node : DungeonGuideStore.all()) if (node.id.equals(selectedId)) return node;
		return null;
	}
	private int parseInt(String value, int fallback) { try { return Integer.parseInt(value.trim()); } catch (NumberFormatException ignored) { return fallback; } }
	private float parseFloat(String value, float fallback) { try { float number = Float.parseFloat(value.trim()); return Float.isFinite(number) ? number : fallback; } catch (NumberFormatException ignored) { return fallback; } }
	private void openColorPicker(String title, int color, int which) {
		DungeonGuideNode target = selected();
		if (target == null) return;
		minecraft.setScreen(new GuiColorPickerScreen(this, title, color, argb -> {
			if (which == 0) target.color = argb;
			else if (which == 1) target.fillColor = argb;
			else target.labelColor = argb;
			DungeonGuideStore.changed();
			rebuildEditorWidgets();
		}));
	}
	private boolean inside(double mx, double my, int x, int y, int w, int h) { return mx >= x && mx < x + w && my >= y && my < y + h; }
	private String fit(String value, int width) { return font.width(value) <= width ? value : font.plainSubstrByWidth(value, Math.max(1, width - font.width("…"))) + "…"; }
	private void drawButton(GuiGraphicsExtractor graphics, Rect bounds, String text, int mouseX, int mouseY) {
		drawButton(graphics, bounds, text, mouseX, mouseY, mouseX >= bounds.x && mouseX < bounds.x + bounds.w
			&& mouseY >= bounds.y && mouseY < bounds.y + bounds.h ? CARD_BG_HOVER : CARD_BG);
	}
	private void drawButton(GuiGraphicsExtractor graphics, Rect bounds, String text, int mouseX, int mouseY, int fill) {
		roundedRect(graphics, bounds.x, bounds.y, bounds.w, bounds.h, RADIUS_SMALL, fill);
		graphics.centeredText(font, fit(text, bounds.w - 8), bounds.x + bounds.w / 2,
			bounds.y + (bounds.h - font.lineHeight) / 2, TEXT_PRIMARY);
	}
	private int actionIndex(double x, double y, Layout l) {
		int gap = 4, columns = 3, bw = (l.width - 16 - gap * 2) / columns;
		if (y < l.footerTop || y >= l.y + l.height - 8) return -1;
		int row = (int) ((y - l.footerTop) / 21);
		int column = (int) ((x - l.x - 8) / (bw + gap));
		if (row < 0 || row > 1 || column < 0 || column >= columns) return -1;
		int left = l.x + 8 + column * (bw + gap);
		if (x < left || x >= left + bw) return -1;
		return row * columns + column;
	}

	@Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		Layout l = layout();
		if (mouseX >= l.leftX && mouseX < l.leftX + l.leftWidth && mouseY >= l.bodyTop && mouseY < l.footerTop) {
			listScroll = Math.max(0, listScroll - (int) Math.round(scrollY * 3)); return true;
		}
		if (mouseX >= l.rightX && mouseX < l.rightX + l.rightWidth && mouseY >= l.bodyTop && mouseY < l.footerTop) {
			if (advancedOpen && macroSearch != null && mouseY >= macroSearch.getY()) macroScroll = Math.max(0, macroScroll - (int) Math.round(scrollY * 2));
			else detailScroll = Math.max(0, Math.min(600, detailScroll - (int) Math.round(scrollY * 3)));
			rebuildEditorWidgets(); return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override public boolean keyPressed(KeyEvent event) {
		if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && (search == null || !search.isFocused())
			&& (routeName == null || !routeName.isFocused()) && (macroSearch == null || !macroSearch.isFocused())) {
			onClose(); return true;
		}
		return super.keyPressed(event);
	}
	@Override public boolean charTyped(CharacterEvent event) { return super.charTyped(event); }
	@Override public void onClose() { minecraft.setScreen(parent); }

	private Layout layout() {
		int w = Math.max(240, Math.min(780, width - 12));
		int h = Math.max(260, Math.min(560, height - 12));
		int x = (width - w) / 2;
		int y = (height - h) / 2;
		int leftWidth = Math.max(115, Math.min(245, w * 34 / 100));
		int leftX = x + 8;
		int rightX = leftX + leftWidth;
		int rightWidth = Math.max(1, w - leftWidth - 16);
		int bodyTop = y + 82;
		int footerTop = y + h - 54;
		return new Layout(x, y, w, h, leftX, leftWidth, rightX, rightWidth, bodyTop, footerTop);
	}

	private record Layout(int x, int y, int width, int height, int leftX, int leftWidth, int rightX,
		int rightWidth, int bodyTop, int footerTop) { }
	private record Rect(int x, int y, int w, int h) { }
	private record ListRow(DungeonGuideNode node, String phaseLabel) { }
}
