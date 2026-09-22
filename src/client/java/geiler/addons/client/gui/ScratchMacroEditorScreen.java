package geiler.addons.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import geiler.addons.client.config.ModConfig;
import geiler.addons.client.location.Island;
import geiler.addons.client.macro.MacroCondition;
import geiler.addons.client.macro.MacroDefinition;
import geiler.addons.client.macro.MacroFunction;
import geiler.addons.client.macro.MacroRunner;
import geiler.addons.client.macro.MacroScript;
import geiler.addons.client.macro.MacroStep;
import geiler.addons.client.macro.MacroValue;
import geiler.addons.client.macro.MacroVariableStore;
import geiler.addons.client.macro.MacroWorldRegion;
import geiler.addons.client.module.ModuleKeybindManager;
import geiler.addons.client.module.impl.MacrosModule;
import geiler.addons.client.module.impl.VisualModule;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

import static geiler.addons.client.gui.GuiTheme.*;

/** A self-contained Scratch-inspired editor for the existing macro and function models. */
public final class ScratchMacroEditorScreen extends Screen {
	private static final int MARGIN = 8;
	private static final int HEADER_HEIGHT = 70;
	private static final int FOOTER_HEIGHT = 22;
	private static final int COMPACT_WIDTH = 940;
	private static final int GRID_STEP = 26;
	private static final int MAX_TREE_DEPTH = 12;
	private static final int MAX_CONDITION_DEPTH = 6;
	private static final int MAX_COMPOUND_TERMS = 12;
	private static final int BLOCK_HEIGHT = 27;
	private static final int HAT_HEIGHT = 34;
	private static final int DRAG_THRESHOLD_SQUARED = 25;

	private static final int COLOR_MOTION = 0xFF4C97FF;
	private static final int COLOR_EVENT = 0xFFFFBF00;
	private static final int COLOR_CONTROL = 0xFFFFAB19;
	private static final int COLOR_INPUT = 0xFF0FBD8C;
	private static final int COLOR_VARIABLE = 0xFFFF8C1A;
	private static final int COLOR_FUNCTION = 0xFFFF6680;
	private static final int COLOR_REGION = 0xFF45C9B0;
	private static final List<PaletteBlock> PALETTE_BLOCKS = createPaletteBlocks();

	private final Screen parent;
	private final MacroDefinition macro;
	private final List<HitTarget> hitTargets = new ArrayList<>();
	private final List<DropSlot> dropSlots = new ArrayList<>();
	private final List<HitTarget> cachedCanvasHitTargets = new ArrayList<>();
	private final List<DropSlot> cachedCanvasDropSlots = new ArrayList<>();
	private List<CanvasScriptPlacement> visibleScriptPlacements = List.of();
	private final IdentityHashMap<MacroStep, Integer> measuredStepHeights = new IdentityHashMap<>();
	private final IdentityHashMap<List<MacroStep>, ListLayout> measuredListLayouts = new IdentityHashMap<>();

	private Tab tab = Tab.CODE;
	private CompactPane compactPane = CompactPane.PALETTE;
	private Category category = Category.ACTIONS;
	private MacroScript selectedScript;
	private MacroFunction selectedFunction;
	private MacroStep selectedStep;
	private List<MacroStep> selectedOwner;
	private int paletteScroll;
	private String paletteSearch = "";
	private int inspectorScroll;
	private int inspectorMaxScroll;
	private String focusedField;
	private String fieldText = "";
	private int fieldCursor;
	private boolean selectAll;
	private Consumer<String> focusedSetter;
	private DragState drag;
	private Layout lastLayout;
	private int currentMouseX;
	private int currentMouseY;
	private Runnable undoAction;
	private String undoLabel = "";
	private PickerState picker;
	private boolean globalManagerOpen;
	private String globalNameDraft = "";
	private MacroValue.Type globalNewType = MacroValue.Type.TEXT;
	private int globalScroll;
	private String notice = "";
	private long noticeUntil;
	private boolean replayingCanvasTargets;
	private boolean canvasTargetCacheValid;
	private int workspaceRevision;
	private int cachedWorkspaceRevision = -1;
	private Rect cachedCanvasViewport;
	private Tab cachedCanvasTab;
	private MacroFunction cachedCanvasFunction;
	private float cachedCanvasPanX;
	private float cachedCanvasPanY;
	private float cachedCanvasZoom;
	private int visibleScriptsRevision = -1;
	private Rect visibleScriptsViewport;
	private float visibleScriptsPanX;
	private float visibleScriptsPanY;
	private float visibleScriptsZoom;

	public ScratchMacroEditorScreen(Screen parent, MacroDefinition macro) {
		super(Component.literal("Scratch Macro Editor"));
		this.parent = parent;
		this.macro = macro;
		this.selectedScript = macro == null ? null : macro.primaryKeyScript();
		this.selectedFunction = firstFunction();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		VisualModule.INSTANCE.refreshTheme();
		currentMouseX = mouseX;
		currentMouseY = mouseY;
		hitTargets.clear();
		dropSlots.clear();
		graphics.fill(0, 0, width, height, DIALOG_SHADE);

		Layout layout = layout();
		lastLayout = layout;
		roundedRectBordered(graphics, layout.panel.x, layout.panel.y, layout.panel.w, layout.panel.h,
			RADIUS, PANEL_TOP, PANEL_BOTTOM, BORDER);
		graphics.fill(layout.panel.x + 1, layout.panel.y + 1, layout.panel.x + layout.panel.w - 1,
			layout.panel.y + 3, PANEL_HIGHLIGHT);
		renderHeader(graphics, layout, mouseX, mouseY);

		if (layout.compact) {
			renderCompactTabs(graphics, layout, mouseX, mouseY);
			renderCanvas(graphics, layout.canvas, mouseX, mouseY);
			Rect pane = layout.compactContent;
			switch (compactPane) {
				case PALETTE -> renderPalette(graphics, pane, mouseX, mouseY);
				case INSPECTOR -> renderInspector(graphics, pane, mouseX, mouseY);
				case CANVAS -> { }
			}
		} else {
			renderPalette(graphics, layout.palette, mouseX, mouseY);
			renderCanvas(graphics, layout.canvas, mouseX, mouseY);
			renderInspector(graphics, layout.inspector, mouseX, mouseY);
		}
		renderFooter(graphics, layout);
		renderDragPreview(graphics, mouseX, mouseY);
		renderPicker(graphics, mouseX, mouseY);
		renderGlobalManager(graphics, mouseX, mouseY);
	}

	private Layout layout() {
		int panelW = Math.max(280, width - MARGIN * 2);
		int panelH = Math.max(180, height - MARGIN * 2);
		Rect panel = new Rect(MARGIN, MARGIN, panelW, panelH);
		boolean compact = panelW < COMPACT_WIDTH || panelH < 520;
		int footerY = panel.y + panel.h - FOOTER_HEIGHT;
		if (compact) {
			int tabsY = panel.y + HEADER_HEIGHT + 2;
			int contentH = Math.max(80, footerY - tabsY - 4);
			int innerX = panel.x + 7;
			int innerW = panel.w - 14;
			int gap = 6;
			int sidebarW = clamp(Math.round(innerW * 0.24f), 118, 190);
			if (innerW - sidebarW - gap < 150) sidebarW = Math.max(84, innerW - gap - 150);
			Rect sidebar = new Rect(innerX, tabsY + 25, sidebarW, Math.max(52, contentH - 25));
			Rect canvas = new Rect(innerX + sidebarW + gap, tabsY, Math.max(1, innerW - sidebarW - gap), contentH);
			return new Layout(panel, sidebar, canvas, sidebar, true, footerY,
				new Rect(innerX, tabsY, sidebarW, 21), sidebar);
		}
		int contentY = panel.y + HEADER_HEIGHT + 2;
		int contentH = Math.max(100, footerY - contentY - 4);
		int innerX = panel.x + 7;
		int innerW = panel.w - 14;
		int gap = 6;
		int paletteW = Math.max(166, Math.min(194, Math.round(innerW * 0.20f)));
		int inspectorW = Math.max(246, Math.min(310, Math.round(innerW * 0.27f)));
		int canvasW = innerW - paletteW - inspectorW - gap * 2;
		if (canvasW < 280) {
			compact = true;
			int tabsY = panel.y + HEADER_HEIGHT + 2;
			int compactHeight = Math.max(80, footerY - tabsY - 4);
			int compactX = panel.x + 7;
			int compactWidth = panel.w - 14;
			int compactGap = 6;
			int sidebarW = clamp(Math.round(compactWidth * 0.24f), 118, 190);
			if (compactWidth - sidebarW - compactGap < 150) sidebarW = Math.max(84, compactWidth - compactGap - 150);
			Rect sidebar = new Rect(compactX, tabsY + 25, sidebarW, Math.max(52, compactHeight - 25));
			Rect canvas = new Rect(compactX + sidebarW + compactGap, tabsY,
				Math.max(1, compactWidth - sidebarW - compactGap), compactHeight);
			return new Layout(panel, sidebar, canvas, sidebar, true, footerY,
				new Rect(compactX, tabsY, sidebarW, 21), sidebar);
		}
		Rect palette = new Rect(innerX, contentY, paletteW, contentH);
		Rect canvas = new Rect(palette.x + palette.w + gap, contentY, canvasW, contentH);
		Rect inspector = new Rect(canvas.x + canvas.w + gap, contentY, inspectorW, contentH);
		return new Layout(panel, palette, canvas, inspector, false, footerY, null);
	}

	private void renderHeader(GuiGraphicsExtractor graphics, Layout layout, int mouseX, int mouseY) {
		Rect panel = layout.panel;
		graphics.text(font, "Scratch Studio", panel.x + 13, panel.y + 8, TEXT_PRIMARY);
		String subtitle = macro == null ? "No macro selected" : "Macro " + macro.id() + "  ·  " + macro.name();
		graphics.text(font, trim(subtitle, panel.w - 170), panel.x + 13, panel.y + 22, TEXT_MUTED);

		int doneW = Math.min(68, Math.max(50, panel.w / 7));
		Rect done = new Rect(panel.x + panel.w - doneW - 9, panel.y + 7, doneW, 20);
		button(graphics, "Done", done, mouseX, mouseY, null, this::closeToParent, false, 0);

		int x = panel.x + 12;
		int y = panel.y + 42;
		Rect code = new Rect(x, y, 65, 20);
		button(graphics, "Code", code, mouseX, mouseY, null,
			() -> { commitFocusedField(); tab = Tab.CODE; clearSelection(); }, tab == Tab.CODE, categoryColor(Category.ACTIONS));
		x += code.w + 4;
		Rect functions = new Rect(x, y, 87, 20);
		button(graphics, "My Blocks", functions, mouseX, mouseY, null,
			() -> { commitFocusedField(); tab = Tab.FUNCTIONS; clearSelection(); ensureFunction(); },
			tab == Tab.FUNCTIONS, categoryColor(Category.FUNCTIONS));
		int globalsX = functions.x + functions.w + 4;
		Rect globals = new Rect(globalsX, y, 72, 20);
		button(graphics, "Globals", globals, mouseX, mouseY, null,
			() -> { commitFocusedField(); globalManagerOpen = true; }, false, categoryColor(Category.VARIABLES));
		String right = ModuleKeybindManager.bindingScript() != null
			&& macro != null && macro.scripts().contains(ModuleKeybindManager.bindingScript())
			? "Listening for a key…" : "Drag stacks · snap blocks · live run status";
		int textX = globals.x + globals.w + 7;
		int available = done.x - textX - 7;
		if (available > 30) graphics.text(font, trim(right, available), textX, y + 6,
			right.startsWith("Listening") ? TEXT_WARN : TEXT_MUTED);
	}

	private void renderCompactTabs(GuiGraphicsExtractor graphics, Layout layout, int mouseX, int mouseY) {
		String[] labels = {"Blocks", "Inspect"};
		CompactPane[] panes = {CompactPane.PALETTE, CompactPane.INSPECTOR};
		Rect row = layout.compactTabs;
		int gap = 3;
		int w = (row.w - gap) / 2;
		for (int i = 0; i < panes.length; i++) {
			Rect bounds = new Rect(row.x + i * (w + gap), row.y, w, row.h);
			CompactPane pane = panes[i];
			button(graphics, labels[i], bounds, mouseX, mouseY, row,
				() -> compactPane = pane, compactPane == pane,
				pane == CompactPane.PALETTE ? categoryColor(Category.ACTIONS)
					: pane == CompactPane.INSPECTOR ? categoryColor(Category.VARIABLES) : categoryColor(Category.WORLD));
		}
	}

	private void renderPalette(GuiGraphicsExtractor graphics, Rect pane, int mouseX, int mouseY) {
		drawPanel(graphics, pane, "Blocks", tab == Tab.FUNCTIONS
			? "Search nodes for this reusable block" : "Search a category, then click or drag");
		Rect viewport = new Rect(pane.x + 6, pane.y + 39, pane.w - 12, Math.max(18, pane.h - 46));
		graphics.enableScissor(viewport.x, viewport.y, viewport.x + viewport.w, viewport.y + viewport.h);
		renderBlockPalette(graphics, viewport, mouseX, mouseY);
		graphics.disableScissor();
	}

	private void renderBlockPalette(GuiGraphicsExtractor graphics, Rect viewport, int mouseX, int mouseY) {
		Rect search = new Rect(viewport.x + 3, viewport.y + 1, viewport.w - 6, 20);
		boolean searchFocused = "palette-search".equals(focusedField);
		roundedRectBordered(graphics, search.x, search.y, search.w, search.h, RADIUS_SMALL,
			searchFocused ? CARD_BG_HOVER : CARD_BG, searchFocused ? CARD_BG_HOVER : CARD_BG,
			searchFocused ? CARD_BORDER_ENABLED : CARD_BORDER);
		String query = searchFocused ? fieldText : paletteSearch;
		String searchLabel = query.isBlank() && !searchFocused ? "Search nodes…" : query + (searchFocused ? "|" : "");
		graphics.text(font, trim(searchLabel, search.w - 12), search.x + 5, search.y + 6,
			query.isBlank() && !searchFocused ? TEXT_MUTED : TEXT_PRIMARY);
		registerHit(search, viewport, null, null, null, null, null, null,
			new FieldBinding("palette-search", () -> paletteSearch, value -> {
				paletteSearch = safe(value);
				paletteScroll = 0;
			}));

		int columns = viewport.w >= 330 ? 4 : viewport.w >= 190 ? 3 : 2;
		int categoryH = 18;
		int chipGap = 2;
		int chipW = Math.max(25, (viewport.w - 6 - chipGap * (columns - 1)) / columns);
		int categoryTop = search.y + search.h + 3;
		for (int i = 0; i < Category.values().length; i++) {
			Category value = Category.values()[i];
			int row = i / columns;
			int column = i % columns;
			int x = viewport.x + 3 + column * (chipW + chipGap);
			int w = column == columns - 1 || i == Category.values().length - 1
				? viewport.x + viewport.w - 3 - x : chipW;
			Rect bounds = new Rect(x, categoryTop + row * (categoryH + 2), w, categoryH);
			button(graphics, value.shortLabel, bounds, mouseX, mouseY, viewport,
				() -> { category = value; paletteSearch = ""; paletteScroll = 0; },
				category == value && query.isBlank(), categoryColor(value));
		}

		int categoryRows = (Category.values().length + columns - 1) / columns;
		int listTop = categoryTop + categoryRows * (categoryH + 2) + 3;
		Rect list = new Rect(viewport.x + 1, listTop, viewport.w - 2,
			Math.max(8, viewport.y + viewport.h - 2 - listTop));
		List<PaletteRow> rows = paletteRows(query);
		int contentHeight = 0;
		for (PaletteRow row : rows) contentHeight += row.header ? 21 : 39;
		paletteScroll = clamp(paletteScroll, 0, Math.max(0, contentHeight - list.h));
		int y = list.y - paletteScroll;
		for (PaletteRow row : rows) {
			if (row.header) {
				if (y + 18 > list.y && y < list.y + list.h) {
					graphics.fill(list.x + 2, y + 16, list.x + list.w - 2, y + 17,
						withOpacity(categoryColor(row.category), 0.48f));
					graphics.text(font, row.category.label, list.x + 4, y + 4,
						categoryColor(row.category));
				}
				y += 21;
				continue;
			}
			PaletteBlock block = row.block;
			if (y + 39 <= list.y || y >= list.y + list.h) {
				y += 39;
				continue;
			}
			Rect bounds = new Rect(list.x + 1, y + 1, list.w - 4, 35);
			boolean hovered = bounds.contains(mouseX, mouseY);
			roundedRectBordered(graphics, bounds.x, bounds.y, bounds.w, bounds.h, RADIUS_SMALL,
				hovered ? CARD_BG_HOVER : CARD_BG, hovered ? CARD_BG_HOVER : CARD_BG,
				CARD_BORDER);
			roundedRect(graphics, bounds.x + 4, bounds.y + 6, 5, bounds.h - 12, 2, categoryColor(block.category));
			graphics.text(font, trim(block.label, bounds.w - 18), bounds.x + 14, bounds.y + 5, TEXT_PRIMARY);
			graphics.text(font, trim(block.hint, bounds.w - 18), bounds.x + 14, bounds.y + 18, TEXT_MUTED);
			registerHit(bounds, list, null, null, null, block, null, null);
			y += 39;
		}
		if (rows.isEmpty()) graphics.centeredText(font, "No nodes match this search.",
			list.x + list.w / 2, list.y + 18, TEXT_MUTED);
	}

	private void renderCanvas(GuiGraphicsExtractor graphics, Rect pane, int mouseX, int mouseY) {
		drawPanel(graphics, pane, "Workspace", tab == Tab.CODE
			? "Move event hats freely · blocks snap into stacks and sockets"
			: "Define a reusable block and edit its local inputs");
		renderCanvasToolbar(graphics, pane, mouseX, mouseY);
		Rect viewport = new Rect(pane.x + 5, pane.y + 63, pane.w - 10, Math.max(16, pane.h - 70));
		renderGrid(graphics, viewport);
		int hitStart = hitTargets.size();
		int dropStart = dropSlots.size();
		if (canReuseCanvasTargets(viewport)) {
			hitTargets.addAll(cachedCanvasHitTargets);
			dropSlots.addAll(cachedCanvasDropSlots);
			replayingCanvasTargets = true;
		} else {
			replayingCanvasTargets = false;
		}
		graphics.enableScissor(viewport.x, viewport.y, viewport.x + viewport.w, viewport.y + viewport.h);
		if (macro == null) {
			graphics.centeredText(font, "No macro to edit.", viewport.x + viewport.w / 2,
				viewport.y + viewport.h / 2, TEXT_MUTED);
		} else if (tab == Tab.CODE) {
			renderScripts(graphics, viewport);
		} else {
			renderFunctionStack(graphics, viewport);
		}
		graphics.disableScissor();
		if (!replayingCanvasTargets) cacheCanvasTargets(viewport, hitStart, dropStart);
		replayingCanvasTargets = false;
		renderZoomControls(graphics, viewport, mouseX, mouseY);
	}

	private boolean canReuseCanvasTargets(Rect viewport) {
		if (!canvasTargetCacheValid || cachedWorkspaceRevision != workspaceRevision
			|| !viewport.equals(cachedCanvasViewport) || cachedCanvasTab != tab
			|| cachedCanvasFunction != selectedFunction) return false;
		if (macro == null) return cachedCanvasPanX == 0 && cachedCanvasPanY == 0 && cachedCanvasZoom == 1;
		return cachedCanvasPanX == macro.canvasPanX() && cachedCanvasPanY == macro.canvasPanY()
			&& cachedCanvasZoom == macro.canvasZoom();
	}

	private void cacheCanvasTargets(Rect viewport, int hitStart, int dropStart) {
		cachedCanvasHitTargets.clear();
		cachedCanvasDropSlots.clear();
		cachedCanvasHitTargets.addAll(hitTargets.subList(hitStart, hitTargets.size()));
		cachedCanvasDropSlots.addAll(dropSlots.subList(dropStart, dropSlots.size()));
		cachedCanvasViewport = viewport;
		cachedCanvasTab = tab;
		cachedCanvasFunction = selectedFunction;
		cachedWorkspaceRevision = workspaceRevision;
		cachedCanvasPanX = macro == null ? 0 : macro.canvasPanX();
		cachedCanvasPanY = macro == null ? 0 : macro.canvasPanY();
		cachedCanvasZoom = macro == null ? 1 : macro.canvasZoom();
		canvasTargetCacheValid = true;
	}

	private void renderCanvasToolbar(GuiGraphicsExtractor graphics, Rect pane, int mouseX, int mouseY) {
		int y = pane.y + 39;
		int x = pane.x + 8;
		int controlY = y;
		if (tab == Tab.CODE) {
			int buttonW = pane.w < 430 ? Math.max(44, (pane.w - 36) / 3) : 57;
			Rect addKey = new Rect(pane.x + pane.w - 8 - buttonW * 3 - 6, controlY, buttonW, 19);
			Rect addRegion = new Rect(addKey.x + buttonW + 3, controlY, buttonW, 19);
			Rect addCall = new Rect(addRegion.x + buttonW + 3, controlY, buttonW, 19);
			button(graphics, "+ Key", addKey, mouseX, mouseY, pane, () -> addScript(MacroScript.Trigger.KEY_PRESS), false, categoryColor(Category.ACTIONS));
			button(graphics, "+ Area", addRegion, mouseX, mouseY, pane, () -> addScript(MacroScript.Trigger.WORLD_REGION), false, categoryColor(Category.WORLD));
			button(graphics, "+ Call", addCall, mouseX, mouseY, pane, () -> addScript(MacroScript.Trigger.ON_CALL), false, categoryColor(Category.FUNCTIONS));
			if (pane.w >= 350 && selectedScript != null && selectedScript.trigger() == MacroScript.Trigger.KEY_PRESS) {
				int captureW = Math.min(102, Math.max(72, pane.w / 5));
				Rect capture = new Rect(x, controlY, captureW, 19);
				boolean binding = ModuleKeybindManager.bindingScript() == selectedScript;
				button(graphics, binding ? "Listening…" : "Set key", capture, mouseX, mouseY, pane,
					this::toggleScriptBinding, false, binding ? categoryColor(Category.ACTIONS) : 0);
			}
			if (pane.w >= 350 && selectedScript != null && macro.scripts().size() > 1) {
				Rect remove = new Rect(addKey.x - 34, controlY, 30, 19);
				button(graphics, "×", remove, mouseX, mouseY, pane, this::removeSelectedScript, false, TEXT_ERROR);
			}
		} else {
			int createW = pane.w < 360 ? Math.max(72, pane.w / 3) : 96;
			int selectorX = x;
			int selectorW = Math.max(72, pane.x + pane.w - 16 - selectorX - createW - 4);
			Rect selector = new Rect(selectorX, controlY, selectorW, 19);
			String name = selectedFunction == null ? "Function · choose" : "Function · " + selectedFunction.name();
			button(graphics, name, selector, mouseX, mouseY, pane, this::openFunctionSelector,
				false, categoryColor(Category.FUNCTIONS));
			Rect create = new Rect(pane.x + pane.w - 8 - createW, controlY, createW, 19);
			button(graphics, "+ New block", create, mouseX, mouseY, pane, this::createFunction, false, categoryColor(Category.FUNCTIONS));
		}
	}

	private void openFunctionSelector() {
		List<PickerOption> options = new ArrayList<>();
		for (MacroFunction function : MacrosModule.INSTANCE.functions()) {
			options.add(new PickerOption(function.name(), () -> selectFunction(function)));
		}
		options.add(new PickerOption("+ Create a function", this::createFunction));
		openPicker("Select a function to edit", options);
	}

	private void renderScripts(GuiGraphicsExtractor graphics, Rect viewport) {
		if (visibleScriptsRevision != workspaceRevision || !viewport.equals(visibleScriptsViewport)
			|| visibleScriptsPanX != macro.canvasPanX() || visibleScriptsPanY != macro.canvasPanY()
			|| visibleScriptsZoom != macro.canvasZoom()) {
			List<CanvasScriptPlacement> visible = new ArrayList<>();
			float zoom = macro.canvasZoom();
			int stackW = clamp(Math.round(270 * zoom), 156, 340);
			int hatHeight = Math.max(28, Math.round(HAT_HEIGHT * Math.min(1.25f, zoom)));
			for (MacroScript script : macro.scripts()) {
				int x = Math.round(viewport.x + 16 + macro.canvasPanX() + script.canvasX() * zoom);
				int y = Math.round(viewport.y + 14 + macro.canvasPanY() + script.canvasY() * zoom);
				int stackH = hatHeight + 3 + measureList(script.steps(), 0);
				if (intersectsViewport(x, y, stackW, stackH, viewport)) {
					visible.add(new CanvasScriptPlacement(script, x, y, stackW, zoom));
				}
			}
			visibleScriptPlacements = List.copyOf(visible);
			visibleScriptsRevision = workspaceRevision;
			visibleScriptsViewport = viewport;
			visibleScriptsPanX = macro.canvasPanX();
			visibleScriptsPanY = macro.canvasPanY();
			visibleScriptsZoom = macro.canvasZoom();
		}
		for (CanvasScriptPlacement placement : visibleScriptPlacements) {
			renderScriptStack(graphics, viewport, placement.script, placement.x, placement.y,
				placement.width, placement.zoom);
		}
	}

	private static boolean intersectsViewport(int x, int y, int w, int h, Rect viewport) {
		return x + w >= viewport.x && x <= viewport.x + viewport.w
			&& y + h >= viewport.y && y <= viewport.y + viewport.h;
	}

	private void renderScriptStack(GuiGraphicsExtractor graphics, Rect viewport, MacroScript script,
		int x, int y, int stackW, float zoom) {
		int hatH = Math.max(28, Math.round(HAT_HEIGHT * Math.min(1.25f, zoom)));
		Rect hat = new Rect(x, y, stackW, hatH);
		int color = script.trigger() == MacroScript.Trigger.KEY_PRESS ? categoryColor(Category.ACTIONS)
			: script.trigger() == MacroScript.Trigger.WORLD_REGION ? categoryColor(Category.WORLD)
			: categoryColor(Category.FUNCTIONS);
		boolean selected = script == selectedScript;
		roundedRectBordered(graphics, hat.x, hat.y, hat.w, hat.h, 11,
			color, color, selected ? TEXT_PRIMARY : withOpacity(color, 0.72f));
		String label = switch (script.trigger()) {
			case KEY_PRESS -> "when  " + script.keybind().displayName() + "  pressed";
			case WORLD_REGION -> regionHatLabel(script.worldRegion());
			case ON_CALL -> "when this macro is called";
		};
		graphics.text(font, trim(label, hat.w - 58), hat.x + 10, hat.y + 7,
			macroAccentText(0xFF1C2028));
		MacroRunner.RunState state = MacroRunner.scriptState(script.id());
		Rect stateRect = new Rect(hat.x + hat.w - 52, hat.y + 8, 43, 17);
		int stateColor = stateColor(state);
		roundedRect(graphics, stateRect.x, stateRect.y, stateRect.w, stateRect.h, 7, withOpacity(stateColor, 0.26f));
		graphics.centeredText(font, stateLabel(state), stateRect.x + stateRect.w / 2,
			stateRect.y + 5, stateColor);
		registerHit(hat, viewport, () -> selectScript(script), null, null, null, script, null);

		int contentY = hat.y + hat.h + 3;
		int blockX = x + 8;
		int blockW = stackW - 16;
		List<MacroStep> steps = script.steps();
		if (steps.isEmpty()) {
			Rect socket = new Rect(blockX, contentY, blockW, 22);
			paintSocket(graphics, socket, color, "drop a block here");
			addDropSlot(socket, steps, 0, false, script, null, 0);
			return;
		}
		drawStepList(graphics, viewport, steps, blockX, contentY, blockW, 0, script, null, 0);
	}

	private void renderFunctionStack(GuiGraphicsExtractor graphics, Rect viewport) {
		ensureFunction();
		if (selectedFunction == null) {
			graphics.centeredText(font, "Create a reusable block from the left panel.",
				viewport.x + viewport.w / 2, viewport.y + viewport.h / 2, TEXT_MUTED);
			return;
		}
		float zoom = macro.canvasZoom();
		int x = Math.round(viewport.x + 16 + macro.canvasPanX() + selectedFunction.canvasX() * zoom);
		int y = Math.round(viewport.y + 14 + macro.canvasPanY() + selectedFunction.canvasY() * zoom);
		int w = clamp(Math.round(270 * zoom), 156, 340);
		int hatH = Math.max(28, Math.round(HAT_HEIGHT * Math.min(1.25f, zoom)));
		Rect hat = new Rect(x, y, w, hatH);
		int functionColor = categoryColor(Category.FUNCTIONS);
		roundedRectBordered(graphics, hat.x, hat.y, hat.w, hat.h, 11, functionColor,
			functionColor, CARD_BORDER_ENABLED);
		graphics.text(font, trim("define  " + selectedFunction.name(), hat.w - 18), hat.x + 11, hat.y + 7,
			macroAccentText(0xFFFFFFFF));
		registerHit(hat, viewport, () -> { selectedStep = null; selectedOwner = null; }, null, null, null, null, selectedFunction);
		int contentY = hat.y + hat.h + 3;
		if (selectedFunction.steps().isEmpty()) {
			Rect socket = new Rect(x + 8, contentY, w - 16, 22);
			paintSocket(graphics, socket, functionColor, "drop a block here");
			addDropSlot(socket, selectedFunction.steps(), 0, true, null, selectedFunction, 0);
			return;
		}
		drawStepList(graphics, viewport, selectedFunction.steps(), x + 8, contentY, w - 16,
			0, null, selectedFunction, 0);
	}

	private int drawStepList(GuiGraphicsExtractor graphics, Rect viewport, List<MacroStep> steps,
		int x, int y, int w, int depth, MacroScript script, MacroFunction function, int nesting) {
		if (steps == null || steps.isEmpty() || nesting > MAX_TREE_DEPTH) return y;
		ListLayout layout = listLayout(steps, nesting);
		int first = layout.firstIntersecting(Math.max(0, viewport.y - y));
		for (int i = first; i < steps.size(); i++) {
			MacroStep step = steps.get(i);
			int nodeY = y + layout.starts[i];
			int nodeH = layout.heights[i];
			if (nodeY > viewport.y + viewport.h) break;
			int indent = Math.min(depth * 7, 49);
			int nodeX = x + indent;
			int nodeW = Math.max(60, w - indent);
			Rect outer = new Rect(nodeX, nodeY, nodeW, nodeH);
			if (intersectsViewport(outer.x, outer.y, outer.w, outer.h, viewport)) {
				drawStepNode(graphics, viewport, step, steps, i, outer, script, function, nesting);
			}
		}
		return y + layout.totalHeight;
	}

	private void drawStepNode(GuiGraphicsExtractor graphics, Rect viewport, MacroStep step,
		List<MacroStep> owner, int index, Rect bounds, MacroScript script, MacroFunction function, int nesting) {
		int color = blockColor(step);
		boolean selected = step == selectedStep;
		boolean active = script != null && MacroRunner.activeStep(script.id()) == step;
		boolean control = step instanceof MacroStep.IfElse || step instanceof MacroStep.Repeat
			|| step instanceof MacroStep.RepeatUntil;
		int headH = Math.min(BLOCK_HEIGHT, bounds.h);
		int outline = active ? 0xFFFFFFFF : selected ? CARD_BORDER_ENABLED : withOpacity(color, 0.65f);
		roundedRectBordered(graphics, bounds.x, bounds.y, bounds.w, headH, RADIUS_SMALL,
			color, color, outline);
		// Small connector nubs make adjacent blocks read as one snapped stack.
		roundedRect(graphics, bounds.x + 12, bounds.y - 2, Math.min(19, bounds.w / 4), 5, 2, color);
		if (!control) roundedRect(graphics, bounds.x + 12, bounds.y + headH - 3, Math.min(19, bounds.w / 4), 5, 2, color);
		drawInlineBlockPreview(graphics, bounds, headH, step,
			macroAccentText(active ? 0xFFFFFFFF : 0xFFFAFCFF));
		registerHit(new Rect(bounds.x, bounds.y, bounds.w, headH), viewport, null,
			step, owner, null, script, function);
		addDropSlot(new Rect(bounds.x, bounds.y, bounds.w, Math.max(1, headH / 2)), owner, index,
			function != null, script, function, nesting);
		addDropSlot(new Rect(bounds.x, bounds.y + headH / 2, bounds.w, Math.max(1, headH - headH / 2)),
			owner, index + 1, function != null, script, function, nesting);

		if (!control) return;
		int bodyY = bounds.y + headH - 1;
		int bodyH = Math.max(16, bounds.h - headH + 1);
		int tint = withOpacity(color, 0.18f);
		graphics.fill(bounds.x + 1, bodyY, bounds.x + bounds.w - 1, bodyY + bodyH, tint);
		graphics.fill(bounds.x + 1, bodyY, bounds.x + 6, bodyY + bodyH, color);
		graphics.fill(bounds.x + 6, bodyY + bodyH - 4, bounds.x + bounds.w - 10, bodyY + bodyH, color);
		int childX = bounds.x + 12;
		int childW = Math.max(42, bounds.w - 23);
		int childY = bodyY + 7;
		if (step instanceof MacroStep.IfElse branch) {
			graphics.text(font, "then", childX, childY, TEXT_SECONDARY);
			childY += 13;
			childY = drawNestedList(graphics, viewport, branch.thenSteps(), childX, childY, childW,
				step, script, function, nesting + 1, color);
			graphics.text(font, "else", childX, childY + 1, TEXT_SECONDARY);
			childY += 14;
			drawNestedList(graphics, viewport, branch.elseSteps(), childX, childY, childW,
				step, script, function, nesting + 1, color);
		} else if (step instanceof MacroStep.Repeat repeat) {
			graphics.text(font, "do", childX, childY, TEXT_SECONDARY);
			childY += 13;
			drawNestedList(graphics, viewport, repeat.steps(), childX, childY, childW,
				step, script, function, nesting + 1, color);
		} else if (step instanceof MacroStep.RepeatUntil repeatUntil) {
			graphics.text(font, "do", childX, childY, TEXT_SECONDARY);
			childY += 13;
			drawNestedList(graphics, viewport, repeatUntil.steps(), childX, childY, childW,
				step, script, function, nesting + 1, color);
		}
	}

	private int drawNestedList(GuiGraphicsExtractor graphics, Rect viewport, List<MacroStep> children,
		int x, int y, int w, MacroStep parentStep, MacroScript script, MacroFunction function,
		int nesting, int color) {
		int available = Math.max(14, measureList(children, nesting));
		if (children.isEmpty()) {
			Rect socket = new Rect(x, y, w, 17);
			paintSocket(graphics, socket, color, "drop blocks here");
			addDropSlot(socket, children, 0, function != null, script, function, nesting);
			return y + socket.h + 3;
		}
		int after = drawStepList(graphics, viewport, children, x, y, w, 1, script, function, nesting);
		addDropSlot(new Rect(x, Math.max(y, after - 3), w, 6), children, children.size(),
			function != null, script, function, nesting);
		return Math.max(after, y + available);
	}

	private int measureStep(MacroStep step, int nesting) {
		if (nesting > MAX_TREE_DEPTH) return 31;
		Integer cached = measuredStepHeights.get(step);
		if (cached != null) return cached;
		int height;
		if (step instanceof MacroStep.IfElse branch) {
			height = BLOCK_HEIGHT + 7 + 13 + measureList(branch.thenSteps(), nesting + 1)
				+ 15 + measureList(branch.elseSteps(), nesting + 1) + 7;
		} else if (step instanceof MacroStep.Repeat repeat) {
			height = BLOCK_HEIGHT + 7 + 13 + measureList(repeat.steps(), nesting + 1) + 7;
		} else if (step instanceof MacroStep.RepeatUntil repeatUntil) {
			height = BLOCK_HEIGHT + 7 + 13 + measureList(repeatUntil.steps(), nesting + 1) + 7;
		} else {
			height = BLOCK_HEIGHT;
		}
		measuredStepHeights.put(step, height);
		return height;
	}

	private int measureList(List<MacroStep> list, int nesting) {
		if (list == null || list.isEmpty()) return 20;
		return listLayout(list, nesting).totalHeight;
	}

	private ListLayout listLayout(List<MacroStep> list, int nesting) {
		ListLayout cached = measuredListLayouts.get(list);
		if (cached != null) return cached;
		int[] starts = new int[list.size()];
		int[] heights = new int[list.size()];
		long total = 0;
		for (int index = 0; index < list.size(); index++) {
			starts[index] = (int) Math.min(Integer.MAX_VALUE / 4L, total);
			heights[index] = measureStep(list.get(index), nesting);
			total = Math.min(Integer.MAX_VALUE / 4L, total + heights[index] + 3L);
		}
		ListLayout layout = new ListLayout(starts, heights, (int) total);
		measuredListLayouts.put(list, layout);
		return layout;
	}

	private void paintSocket(GuiGraphicsExtractor graphics, Rect socket, int color, String label) {
		roundedRectBordered(graphics, socket.x, socket.y, socket.w, socket.h, RADIUS_SMALL,
			withOpacity(color, 0.10f), withOpacity(color, 0.10f), withOpacity(color, 0.62f));
		graphics.text(font, trim(label, socket.w - 12), socket.x + 7, socket.y + Math.max(3, (socket.h - 8) / 2),
			TEXT_MUTED);
	}

	private void addDropSlot(Rect bounds, List<MacroStep> owner, int index, boolean functionScope,
		MacroScript script, MacroFunction function, int depth) {
		if (replayingCanvasTargets || owner == null) return;
		Rect clipped = lastLayout == null ? bounds : bounds.intersection(canvasViewport(lastLayout));
		if (clipped == null) return;
		dropSlots.add(new DropSlot(clipped, owner, index, functionScope, script, function, depth));
	}

	private void renderGrid(GuiGraphicsExtractor graphics, Rect viewport) {
		graphics.fill(viewport.x, viewport.y, viewport.x + viewport.w, viewport.y + viewport.h, withOpacity(TEXT_PRIMARY, 0.025f));
		float panX = macro == null ? 0 : macro.canvasPanX();
		float panY = macro == null ? 0 : macro.canvasPanY();
		int startX = viewport.x + Math.floorMod(Math.round(16 + panX), GRID_STEP);
		int startY = viewport.y + Math.floorMod(Math.round(14 + panY), GRID_STEP);
		int dot = withOpacity(TEXT_MUTED, 0.25f);
		for (int x = startX; x < viewport.x + viewport.w; x += GRID_STEP) {
			for (int y = startY; y < viewport.y + viewport.h; y += GRID_STEP) {
				graphics.fill(x, y, x + 2, y + 2, dot);
			}
		}
	}

	private void renderZoomControls(GuiGraphicsExtractor graphics, Rect viewport, int mouseX, int mouseY) {
		if (macro == null) return;
		int y = viewport.y + viewport.h - 25;
		Rect zoomOut = new Rect(viewport.x + 8, y, 25, 20);
		Rect zoomLabel = new Rect(zoomOut.x + 28, y, 55, 20);
		Rect zoomIn = new Rect(zoomLabel.x + 58, y, 25, 20);
		Rect reset = new Rect(zoomIn.x + 29, y, 49, 20);
		button(graphics, "−", zoomOut, mouseX, mouseY, viewport, () -> setZoomAround(0.9f,
			viewport.x + viewport.w / 2.0, viewport.y + viewport.h / 2.0), false, 0);
		roundedRect(graphics, zoomLabel.x, zoomLabel.y, zoomLabel.w, zoomLabel.h, RADIUS_SMALL, CARD_BG);
		graphics.centeredText(font, Math.round(macro.canvasZoom() * 100) + "%", zoomLabel.x + zoomLabel.w / 2,
			zoomLabel.y + 6, TEXT_SECONDARY);
		button(graphics, "+", zoomIn, mouseX, mouseY, viewport, () -> setZoomAround(1.1f,
			viewport.x + viewport.w / 2.0, viewport.y + viewport.h / 2.0), false, 0);
		button(graphics, "Reset", reset, mouseX, mouseY, viewport,
			() -> { macro.setCanvasView(0, 0, 1); dirty(); }, false, 0);
	}

	private void renderInspector(GuiGraphicsExtractor graphics, Rect pane, int mouseX, int mouseY) {
		drawPanel(graphics, pane, "Inspector", "Edit the selected block or event");
		Rect viewport = new Rect(pane.x + 6, pane.y + 39, pane.w - 12, Math.max(18, pane.h - 46));
		graphics.enableScissor(viewport.x, viewport.y, viewport.x + viewport.w, viewport.y + viewport.h);
		int startY = viewport.y + 6;
		int[] y = {startY - inspectorScroll};
		if (tab == Tab.FUNCTIONS && selectedFunction != null && selectedStep == null) {
			drawFunctionProperties(graphics, viewport, y, selectedFunction, mouseX, mouseY);
		} else if (selectedStep != null && selectedOwner != null && selectedOwner.contains(selectedStep)) {
			drawStepProperties(graphics, viewport, y, selectedStep, mouseX, mouseY);
		} else if (tab == Tab.CODE && selectedScript != null) {
			drawScriptProperties(graphics, viewport, y, selectedScript, mouseX, mouseY);
		} else {
			graphics.text(font, "Select a block, event hat, or", viewport.x + 6, y[0] + 4, TEXT_SECONDARY);
			graphics.text(font, "function to edit its settings.", viewport.x + 6, y[0] + 17, TEXT_MUTED);
		}
		graphics.disableScissor();
		int contentHeight = Math.max(0, y[0] + inspectorScroll - startY);
		inspectorMaxScroll = Math.max(0, contentHeight - Math.max(0, viewport.h - 12));
		inspectorScroll = clamp(inspectorScroll, 0, inspectorMaxScroll);
	}

	private void drawScriptProperties(GuiGraphicsExtractor graphics, Rect viewport, int[] y,
		MacroScript script, int mouseX, int mouseY) {
		sectionTitle(graphics, viewport, y, "Event stack");
		lineText(graphics, viewport, y, "Trigger", triggerName(script.trigger()));
		lineText(graphics, viewport, y, "Run state", stateLabel(MacroRunner.scriptState(script.id())));
		if (script.trigger() == MacroScript.Trigger.KEY_PRESS) {
			lineText(graphics, viewport, y, "Hotkey", script.keybind().displayName());
			button(graphics, ModuleKeybindManager.bindingScript() == script ? "Listening for key…" : "Capture hotkey",
				rowRect(viewport, y[0], 23), mouseX, mouseY, viewport, this::toggleScriptBinding,
				false, categoryColor(Category.ACTIONS));
			y[0] += 28;
		} else if (script.trigger() == MacroScript.Trigger.WORLD_REGION) {
			drawRegionProperties(graphics, viewport, y, script.worldRegion(), script, mouseX, mouseY);
		}
		button(graphics, "Remove this event stack", rowRect(viewport, y[0], 23), mouseX, mouseY,
			viewport, this::removeSelectedScript, false, TEXT_ERROR);
		y[0] += 28;
	}

	private void drawRegionProperties(GuiGraphicsExtractor graphics, Rect viewport, int[] y,
		MacroWorldRegion region, MacroScript script, int mouseX, int mouseY) {
		sectionTitle(graphics, viewport, y, "World area");
		button(graphics, region.placed() ? "Recapture current position" : "Capture current position",
			rowRect(viewport, y[0], 23), mouseX, mouseY, viewport, () -> {
				if (MacroRunner.captureWorldRegion(script)) dirty();
				else flash("Join a world before capturing an area.");
			}, false, categoryColor(Category.WORLD));
		y[0] += 28;
		if (region.placed()) {
			button(graphics, "Clear placement", rowRect(viewport, y[0], 21), mouseX, mouseY, viewport,
				() -> { region.clearPlacement(); dirty(); }, false, TEXT_ERROR);
			y[0] += 25;
			lineText(graphics, viewport, y, "Center", String.format(Locale.ROOT, "%.1f, %.1f, %.1f", region.x(), region.y(), region.z()));
			lineText(graphics, viewport, y, "World", trim(region.worldKey(), viewport.w - 68));
		} else {
			lineText(graphics, viewport, y, "Placement", "Not set");
		}
		button(graphics, "Shape  ·  " + title(region.shape().name()), rowRect(viewport, y[0], 22),
			mouseX, mouseY, viewport, () -> { region.setShape(next(region.shape())); dirty(); }, false, categoryColor(Category.WORLD));
		y[0] += 26;
		drawDoubleField(graphics, viewport, y, "Size / radius", "region-size", region::size, region::setSize);
		if (region.shape() == MacroWorldRegion.Shape.RING) {
			drawDoubleField(graphics, viewport, y, "Inner radius", "region-inner", region::innerSize, region::setInnerSize);
		}
		drawDoubleField(graphics, viewport, y, "Vertical tolerance", "region-y", region::yTolerance, region::setYTolerance);
		drawIntField(graphics, viewport, y, "Repeat delay (ms)", "region-delay", region::repeatDelayMillis,
			region::setRepeatDelayMillis);
		drawFloatField(graphics, viewport, y, "Outline width", "region-width", region::lineWidth, region::setLineWidth);
		drawIntField(graphics, viewport, y, "Fill opacity", "region-fill", region::fillOpacity, region::setFillOpacity);
		drawTextField(graphics, viewport, y, "Color (#RRGGBB)", "region-color",
			() -> String.format(Locale.ROOT, "%06X", region.color() & 0xFFFFFF), value -> {
				try { region.setColor(Integer.parseInt(stripHash(value), 16)); dirty(); }
				catch (NumberFormatException ignored) { }
			});
		toggleRow(graphics, viewport, y, "Once per loaded world", region.oncePerWorld(),
			() -> { region.setOncePerWorld(!region.oncePerWorld()); dirty(); }, mouseX, mouseY);
	}

	private void drawFunctionProperties(GuiGraphicsExtractor graphics, Rect viewport, int[] y,
		MacroFunction function, int mouseX, int mouseY) {
		sectionTitle(graphics, viewport, y, "Reusable block");
		drawTextField(graphics, viewport, y, "Name", "function-name-" + function.id(), function::name,
			function::setName);
		lineText(graphics, viewport, y, "Body", function.steps().size() + " blocks");
		sectionTitle(graphics, viewport, y, "Inputs");
		button(graphics, "+  Add input", rowRect(viewport, y[0], 22), mouseX, mouseY, viewport,
			() -> addFunctionParameter(function), false, categoryColor(Category.FUNCTIONS));
		y[0] += 26;
		int count = Math.min(function.parameters().size(), 24);
		for (int i = 0; i < count; i++) {
			final int index = i;
			MacroFunction.Parameter parameter = function.parameters().get(i);
			graphics.text(font, "Input " + (i + 1), viewport.x + 4, y[0] + 2, TEXT_MUTED);
			y[0] += 13;
			int removeW = 25;
			int typeW = Math.min(68, Math.max(52, viewport.w / 3));
			int nameW = Math.max(48, viewport.w - typeW - removeW - 16);
			drawCompactField(graphics, viewport, y, "Name", "param-name-" + function.id() + "-" + i,
				parameter::name, value -> updateParameter(function, index, value, null, null), nameW);
			Rect type = new Rect(viewport.x + 4, y[0], typeW, 19);
			button(graphics, parameter.type().name(), type, mouseX, mouseY, viewport,
				() -> updateParameter(function, index, null, next(parameter.type()), null), false, categoryColor(Category.VARIABLES));
			Rect remove = new Rect(type.x + type.w + 3, y[0], removeW, 19);
			button(graphics, "×", remove, mouseX, mouseY, viewport,
				() -> removeFunctionParameter(function, index), false, TEXT_ERROR);
			y[0] += 23;
			drawTextField(graphics, viewport, y, "Default value", "param-default-" + function.id() + "-" + i,
				parameter::defaultValue, value -> updateParameter(function, index, null, null, value));
			y[0] += 2;
		}
		if (function.parameters().size() > count) lineText(graphics, viewport, y, "More inputs", "Scroll to edit");
	}

	private void drawStepProperties(GuiGraphicsExtractor graphics, Rect viewport, int[] y,
		MacroStep step, int mouseX, int mouseY) {
		sectionTitle(graphics, viewport, y, "Block settings");
		lineText(graphics, viewport, y, "Block", blockLabel(step));
		Rect actions = rowRect(viewport, y[0], 21);
		int actionW = Math.max(1, (actions.w - 3) / 2);
		button(graphics, "Delete", new Rect(actions.x, actions.y, actionW, actions.h),
			mouseX, mouseY, viewport, this::removeSelectedStep, false, TEXT_ERROR);
		button(graphics, "Undo", new Rect(actions.x + actionW + 3, actions.y, actions.w - actionW - 3, actions.h),
			mouseX, mouseY, viewport, this::undoLastEdit, false,
			undoAction == null ? 0 : categoryColor(Category.FUNCTIONS));
		y[0] += 25;
		drawIntField(graphics, viewport, y, "Delay before (ms)", "delay-min", step::delayMin,
			value -> step.setDelay(value, Math.max(value, step.delayMax())));
		drawIntField(graphics, viewport, y, "Delay maximum (ms)", "delay-max", step::delayMax,
			value -> step.setDelay(Math.min(step.delayMin(), value), value));

		if (step instanceof MacroStep.Command command) {
			drawTextField(graphics, viewport, y, "Command", "command", command::command, command::setCommand);
		} else if (step instanceof MacroStep.Chat chat) {
			drawTextField(graphics, viewport, y, "Chat message", "chat", chat::message, chat::setMessage);
		} else if (step instanceof MacroStep.Wait wait) {
			drawIntField(graphics, viewport, y, "Minimum wait (ms)", "wait-min", wait::minMillis,
				value -> wait.setRange(value, Math.max(value, wait.maxMillis())));
			drawIntField(graphics, viewport, y, "Maximum wait (ms)", "wait-max", wait::maxMillis,
				value -> wait.setRange(Math.min(wait.minMillis(), value), value));
		} else if (step instanceof MacroStep.Key key) {
			lineText(graphics, viewport, y, "Key", key.key().isBlank() ? "Not set" : key.key());
			button(graphics, ModuleKeybindManager.bindingKeyStep() == key ? "Listening for key…" : "Capture key",
				rowRect(viewport, y[0], 22), mouseX, mouseY, viewport, () -> {
					if (ModuleKeybindManager.bindingKeyStep() == key) ModuleKeybindManager.cancelBinding();
					else ModuleKeybindManager.beginKeyStepBinding(key);
				}, false, categoryColor(Category.INPUT));
			y[0] += 26;
			toggleRow(graphics, viewport, y, "Hold key", key.hold(), () -> { key.setHold(!key.hold()); dirty(); }, mouseX, mouseY);
			if (key.hold()) {
				drawIntField(graphics, viewport, y, "Minimum hold (ms)", "key-hold-min", key::holdMinMillis,
					value -> key.setHoldRange(value, Math.max(value, key.holdMaxMillis())));
				drawIntField(graphics, viewport, y, "Maximum hold (ms)", "key-hold-max", key::holdMaxMillis,
					value -> key.setHoldRange(Math.min(key.holdMinMillis(), value), value));
			}
		} else if (step instanceof MacroStep.SelectHotbarSlot hotbar) {
			drawIntField(graphics, viewport, y, "Hotbar slot (1–9)", "hotbar-slot", hotbar::slot, hotbar::setSlot);
			lineText(graphics, viewport, y, "Safety", "Only outside inventories");
		} else if (step instanceof MacroStep.MouseButton mouse) {
			button(graphics, "Button  ·  " + title(mouse.button().name()), rowRect(viewport, y[0], 22),
				mouseX, mouseY, viewport, () -> { mouse.setButton(next(mouse.button())); dirty(); }, false, categoryColor(Category.INPUT));
			y[0] += 26;
			toggleRow(graphics, viewport, y, "Hold instead of click", mouse.hold(),
				() -> { mouse.setHold(!mouse.hold()); dirty(); }, mouseX, mouseY);
			if (mouse.hold()) drawIntField(graphics, viewport, y, "Hold duration (ms)", "mouse-hold",
				mouse::holdMillis, mouse::setHoldMillis);
		} else if (step instanceof MacroStep.BlockPlayerInput block) {
			drawIntField(graphics, viewport, y, "Block duration (ms)", "input-block-duration",
				block::durationMillis, block::setDurationMillis);
		} else if (step instanceof MacroStep.SetVariable set) {
			drawVariableTarget(graphics, viewport, y, "Target", set.name(), set.targetsGlobal(), set.globalVariableId(),
				definition -> { set.setGlobalVariableId(definition.id()); set.setName(definition.name()); set.setValueType(definition.type());
					set.setValue(withType(set.value(), definition.type())); dirty(); },
				() -> { set.setGlobalVariableId(null); dirty(); }, mouseX, mouseY);
			if (!set.targetsGlobal()) {
				drawTextField(graphics, viewport, y, "Local variable name", "set-var-name", set::name, set::setName);
				button(graphics, "Type  ·  " + set.valueType().name(), rowRect(viewport, y[0], 22),
					mouseX, mouseY, viewport, () -> {
						MacroValue.Type type = next(set.valueType());
						set.setValueType(type);
						set.setValue(withType(set.value(), type));
						dirty();
					}, false, categoryColor(Category.VARIABLES));
				y[0] += 26;
			} else {
				lineText(graphics, viewport, y, "Type", set.valueType().name());
			}
			drawMacroValueEditor(graphics, viewport, y, "Value", "set-var-value", set.value(), set.valueType(),
				set::setValue, mouseX, mouseY);
		} else if (step instanceof MacroStep.ChangeVariable change) {
			drawVariableTarget(graphics, viewport, y, "Target", change.name(), change.targetsGlobal(), change.globalVariableId(),
				definition -> { change.setGlobalVariableId(definition.id()); change.setName(definition.name()); dirty(); },
				() -> { change.setGlobalVariableId(null); dirty(); }, mouseX, mouseY);
			if (!change.targetsGlobal()) drawTextField(graphics, viewport, y, "Local variable name",
				"change-var-name", change::name, change::setName);
			drawDoubleField(graphics, viewport, y, "Change by", "change-var-amount", change::amount, change::setAmount);
		} else if (step instanceof MacroStep.FunctionCall call) {
			drawFunctionCallProperties(graphics, viewport, y, call, mouseX, mouseY);
		} else if (step instanceof MacroStep.MacroCall call) {
			drawMacroCallProperties(graphics, viewport, y, call, mouseX, mouseY);
		} else if (step instanceof MacroStep.IfElse branch) {
			drawConditionButton(graphics, viewport, y, "Condition", branch.condition(),
				branch::setCondition, mouseX, mouseY, 0);
			drawConditionProperties(graphics, viewport, y, branch.condition(), branch::setCondition, mouseX, mouseY, 0);
		} else if (step instanceof MacroStep.Repeat repeat) {
			toggleRow(graphics, viewport, y, "Repeat forever", repeat.forever(),
				() -> { repeat.setForever(!repeat.forever()); dirty(); }, mouseX, mouseY);
			if (!repeat.forever()) drawIntField(graphics, viewport, y, "Repeat count", "repeat-count", repeat::count, repeat::setCount);
		} else if (step instanceof MacroStep.RepeatUntil repeat) {
			drawConditionButton(graphics, viewport, y, "Stop condition", repeat.condition(),
				repeat::setCondition, mouseX, mouseY, 0);
			drawConditionProperties(graphics, viewport, y, repeat.condition(), repeat::setCondition, mouseX, mouseY, 0);
		} else if (step instanceof MacroStep.WaitUntil wait) {
			drawConditionButton(graphics, viewport, y, "Wait condition", wait.condition(),
				wait::setCondition, mouseX, mouseY, 0);
			drawConditionProperties(graphics, viewport, y, wait.condition(), wait::setCondition, mouseX, mouseY, 0);
		} else if (step instanceof MacroStep.ClickSlot slot) {
			drawIntField(graphics, viewport, y, "Slot id", "click-slot", slot::slotId, slot::setSlotId);
			button(graphics, "Mouse button · " + mouseButtonLabel(slot.button()), rowRect(viewport, y[0], 22),
				mouseX, mouseY, viewport, () -> { slot.setButton((slot.button() + 1) % 3); dirty(); },
				false, categoryColor(Category.INVENTORY));
			y[0] += 26;
			toggleRow(graphics, viewport, y, "Shift click", slot.shift(), () -> { slot.setShift(!slot.shift()); dirty(); }, mouseX, mouseY);
			lineText(graphics, viewport, y, "Slot IDs", "Enable Dev → Slot IDs while a menu is open");
		} else if (step instanceof MacroStep.ClickItem item) {
			drawTextField(graphics, viewport, y, "Item name", "click-item-name", item::name, item::setName);
			toggleRow(graphics, viewport, y, "Partial name match", item.contains(), () -> { item.setContains(!item.contains()); dirty(); }, mouseX, mouseY);
			drawIntField(graphics, viewport, y, "Occurrence", "click-item-occurrence", item::occurrence, item::setOccurrence);
			button(graphics, "Search area · " + itemScopeLabel(item.scope()), rowRect(viewport, y[0], 22),
				mouseX, mouseY, viewport, () -> { item.setScope(nextItemScope(item.scope())); dirty(); },
				false, categoryColor(Category.INVENTORY));
			y[0] += 26;
			button(graphics, "Mouse button · " + mouseButtonLabel(item.button()), rowRect(viewport, y[0], 22),
				mouseX, mouseY, viewport, () -> { item.setButton((item.button() + 1) % 3); dirty(); },
				false, categoryColor(Category.INVENTORY));
			y[0] += 26;
			toggleRow(graphics, viewport, y, "Shift click", item.shift(), () -> { item.setShift(!item.shift()); dirty(); }, mouseX, mouseY);
		} else if (step instanceof MacroStep.Title title) {
			drawTextField(graphics, viewport, y, "Title text", "title-text", title::text, title::setText);
			button(graphics, "Font · " + titleFontLabel(title.font()), rowRect(viewport, y[0], 22),
				mouseX, mouseY, viewport, () -> openTitleFontPicker(title), false, categoryColor(Category.DISPLAY));
			y[0] += 26;
			drawFloatField(graphics, viewport, y, "Text scale", "title-scale", title::scale, title::setScale);
			drawTextField(graphics, viewport, y, "Text color (#RRGGBB)", "title-text-color",
				() -> colorText(title.textColor()), value -> setHexColor(value, title::setTextColor));
			toggleRow(graphics, viewport, y, "Show background", title.showBackground(),
				() -> { title.setShowBackground(!title.showBackground()); dirty(); }, mouseX, mouseY);
			if (title.showBackground()) {
				drawTextField(graphics, viewport, y, "Background color (#RRGGBB)", "title-background-color",
					() -> colorText(title.backgroundColor()), value -> setHexColor(value, title::setBackgroundColor));
				drawIntField(graphics, viewport, y, "Background opacity (0–255)", "title-background-opacity",
					title::backgroundOpacity, title::setBackgroundOpacity);
			}
			drawIntField(graphics, viewport, y, "Fade in (ms)", "title-fade-in", title::fadeInMillis, title::setFadeInMillis);
			drawIntField(graphics, viewport, y, "Visible (ms)", "title-hold", title::holdMillis, title::setHoldMillis);
			drawIntField(graphics, viewport, y, "Fade out (ms)", "title-fade-out", title::fadeOutMillis, title::setFadeOutMillis);
		} else if (step instanceof MacroStep.Sound sound) {
			drawTextField(graphics, viewport, y, "Sound id", "sound-id", sound::soundId, sound::setSoundId);
			button(graphics, "Browse registered sounds", rowRect(viewport, y[0], 22), mouseX, mouseY,
				viewport, () -> openSoundPicker(sound), false, categoryColor(Category.DISPLAY));
			y[0] += 26;
		} else if (step instanceof MacroStep.WorldSwitch worldSwitch) {
			button(graphics, "Destination · " + worldSwitch.target().label(), rowRect(viewport, y[0], 22),
				mouseX, mouseY, viewport, () -> openWorldSwitchPicker(worldSwitch),
				false, categoryColor(Category.WORLD));
			y[0] += 26;
		} else if (step instanceof MacroStep.StartBlockPlayerInput) {
			lineText(graphics, viewport, y, "Input", "Starts blocking gameplay controls");
			lineText(graphics, viewport, y, "Safety", "Released on every run exit");
		} else if (step instanceof MacroStep.StopBlockPlayerInput) {
			lineText(graphics, viewport, y, "Input", "Stops the current input block");
		} else if (step instanceof MacroStep.CloseScreen) {
			lineText(graphics, viewport, y, "Action", "Closes the current screen");
		} else {
			lineText(graphics, viewport, y, "Type", step.type());
		}
		if (selectedScript != null && selectedScript.trigger() == MacroScript.Trigger.WORLD_REGION) {
			sectionTitle(graphics, viewport, y, "Event area");
			drawRegionProperties(graphics, viewport, y, selectedScript.worldRegion(), selectedScript, mouseX, mouseY);
		}
	}

	private void drawFunctionCallProperties(GuiGraphicsExtractor graphics, Rect viewport, int[] y,
		MacroStep.FunctionCall call, int mouseX, int mouseY) {
		MacroFunction current = MacrosModule.INSTANCE.function(call.functionId());
		button(graphics, "Function  ·  " + (current == null ? "Choose" : trim(current.name(), 100)),
			rowRect(viewport, y[0], 22), mouseX, mouseY, viewport, () -> {
				List<PickerOption> options = new ArrayList<>();
				for (MacroFunction function : MacrosModule.INSTANCE.functions()) {
					options.add(new PickerOption(function.name(), () -> {
						call.setFunctionId(function.id());
						call.arguments().clear();
						dirty();
					}));
				}
				if (options.isEmpty()) { flash("Create a function in My Blocks first."); return; }
				openPicker("Choose a function", options);
			}, false, categoryColor(Category.FUNCTIONS));
		y[0] += 26;
		if (current == null) {
			lineText(graphics, viewport, y, "Inputs", "Unresolved function reference");
			return;
		}
		lineText(graphics, viewport, y, "Inputs", current.parameters().size() + " named values");
		int count = current.parameters().size();
		for (int i = 0; i < count; i++) {
			MacroFunction.Parameter parameter = current.parameters().get(i);
			final int index = i;
			while (call.arguments().size() <= index) {
				call.arguments().add(MacroValue.literal(parameter.type(), parameter.defaultValue()));
			}
			MacroValue value = call.arguments().get(i);
			drawMacroValueEditor(graphics, viewport, y, parameter.name() + "  ·  " + parameter.type().name(),
				"call-arg-" + call.functionId() + "-" + i,
				value, parameter.type(), input -> {
					if (index < call.arguments().size()) call.arguments().set(index, input);
				}, mouseX, mouseY);
		}
	}

	private void openTitleFontPicker(MacroStep.Title title) {
		List<PickerOption> options = List.of(
			new PickerOption("Minecraft Default", () -> { title.setFont("minecraft:default"); dirty(); }),
			new PickerOption("Uniform Unicode", () -> { title.setFont("minecraft:uniform"); dirty(); }));
		openPicker("Choose title font", options);
	}

	private void openSoundPicker(MacroStep.Sound sound) {
		List<Identifier> ids = new ArrayList<>(BuiltInRegistries.SOUND_EVENT.keySet());
		ids.sort(Identifier::compareTo);
		List<PickerOption> options = new ArrayList<>(ids.size());
		for (Identifier id : ids) {
			options.add(new PickerOption(id.toString(), () -> { sound.setSoundId(id.toString()); dirty(); }));
		}
		openPicker("Choose sound event · type to search", options, true);
	}

	private void openWorldSwitchPicker(MacroStep.WorldSwitch worldSwitch) {
		List<PickerOption> options = new ArrayList<>();
		for (Island island : Island.values()) {
			if (island.selectable()) options.add(new PickerOption(island.label(), () -> {
				worldSwitch.setTarget(island);
				dirty();
			}));
		}
		openPicker("Choose destination", options);
	}

	private static String titleFontLabel(String font) {
		return "minecraft:uniform".equals(font) ? "Uniform Unicode" : "Minecraft Default";
	}

	private static String colorText(int color) {
		return String.format(Locale.ROOT, "%06X", color & 0xFFFFFF);
	}

	private static void setHexColor(String value, IntConsumer setter) {
		try {
			setter.accept(Integer.parseInt(stripHash(value), 16));
		} catch (NumberFormatException ignored) {
		}
	}

	private void drawMacroCallProperties(GuiGraphicsExtractor graphics, Rect viewport, int[] y,
		MacroStep.MacroCall call, int mouseX, int mouseY) {
		List<MacroDefinition> macros = MacrosModule.INSTANCE.macros();
		List<MacroDefinition> targets = new ArrayList<>();
		for (MacroDefinition candidate : macros) if (candidate != macro) targets.add(candidate);
		MacroDefinition current = MacrosModule.INSTANCE.macro(call.macroId());
		button(graphics, "Target  ·  " + (current == null ? "Choose macro" : trim(current.name(), 100)),
			rowRect(viewport, y[0], 22), mouseX, mouseY, viewport, () -> {
				List<PickerOption> options = new ArrayList<>();
				for (MacroDefinition candidate : MacrosModule.INSTANCE.macros()) {
					if (candidate == macro) continue;
					options.add(new PickerOption(candidate.name(), () -> {
						call.setMacroId(candidate.id());
						dirty();
					}));
				}
				if (options.isEmpty()) { flash("Create another macro before adding a call."); return; }
				openPicker("Choose a macro", options);
			}, false, categoryColor(Category.FUNCTIONS));
		y[0] += 27;
		lineText(graphics, viewport, y, "Behavior", "Waits for the target’s On Call stack");
		lineText(graphics, viewport, y, "Available", targets.size() + " other macros");
	}

	private void drawVariableTarget(GuiGraphicsExtractor graphics, Rect viewport, int[] y, String label,
		String localName, boolean global, String globalId,
		Consumer<MacroVariableStore.Definition> selectGlobal, Runnable selectLocal,
		int mouseX, int mouseY) {
		String target = global ? globalVariableName(globalId) : "Local · " + localName;
		button(graphics, label + "  ·  " + target, rowRect(viewport, y[0], 22), mouseX, mouseY, viewport, () -> {
			List<PickerOption> options = new ArrayList<>();
			options.add(new PickerOption("Use a local variable", () -> { selectLocal.run(); dirty(); }));
			for (MacroVariableStore.Definition definition : MacrosModule.INSTANCE.globalVariables().definitions()) {
				options.add(new PickerOption("Global · " + definition.name() + " · " + definition.type().name(),
					() -> { selectGlobal.accept(definition); dirty(); }));
			}
			if (options.size() == 1) {
				flash("Create a global variable with the Globals button first.");
				return;
			}
			openPicker("Choose the variable scope", options);
		}, false, global ? categoryColor(Category.VARIABLES) : 0);
		y[0] += 26;
	}

	private void drawMacroValueEditor(GuiGraphicsExtractor graphics, Rect viewport, int[] y, String fieldLabel,
		String fieldId, MacroValue value, MacroValue.Type expectedType,
		Consumer<MacroValue> setter, int mouseX, int mouseY) {
		MacroValue.Type type = expectedType == null ? MacroValue.Type.TEXT : expectedType;
		String mode = switch (value.scope()) {
			case GLOBAL -> "Global · " + globalVariableName(value.value());
			case LOCAL -> "Local variable";
			case NONE -> "Literal value";
		};
		button(graphics, "Source  ·  " + mode, rowRect(viewport, y[0], 22), mouseX, mouseY, viewport, () -> {
			List<PickerOption> options = new ArrayList<>();
			options.add(new PickerOption("Literal value", () -> { setter.accept(MacroValue.literal(type, "")); dirty(); }));
			options.add(new PickerOption("Local variable", () -> { setter.accept(MacroValue.variable(type, "value")); dirty(); }));
			for (MacroVariableStore.Definition definition : MacrosModule.INSTANCE.globalVariables().definitions()) {
				options.add(new PickerOption("Global · " + definition.name(),
					() -> { setter.accept(MacroValue.globalVariable(type, definition.id())); dirty(); }));
			}
			openPicker("Choose the input source", options);
		}, false, value.scope() == MacroValue.Scope.GLOBAL ? categoryColor(Category.VARIABLES) : 0);
		y[0] += 26;
		if (value.scope() == MacroValue.Scope.GLOBAL) {
			lineText(graphics, viewport, y, fieldLabel,
				globalVariableName(value.value()) + " · shared value");
			return;
		}
		String label = value.scope() == MacroValue.Scope.LOCAL ? fieldLabel + " · local name" : fieldLabel;
		drawTextField(graphics, viewport, y, label, fieldId, value::value, input ->
			setter.accept(value.scope() == MacroValue.Scope.LOCAL
				? MacroValue.variable(type, input) : MacroValue.literal(type, input)));
	}

	private static MacroValue withType(MacroValue value, MacroValue.Type type) {
		if (value == null || !value.variableReference()) return MacroValue.literal(type, value == null ? "" : value.value());
		return value.scope() == MacroValue.Scope.GLOBAL
			? MacroValue.globalVariable(type, value.value()) : MacroValue.variable(type, value.value());
	}

	private static String globalVariableName(String id) {
		MacroVariableStore.Definition definition = MacrosModule.INSTANCE.globalVariables().definition(id);
		return definition == null ? "Missing global" : definition.name();
	}

	private void drawConditionButton(GuiGraphicsExtractor graphics, Rect viewport, int[] y, String label,
		MacroCondition condition, Consumer<MacroCondition> setter, int mouseX, int mouseY, int depth) {
		String current = conditionLabel(condition);
		button(graphics, label + "  ·  " + current, rowRect(viewport, y[0], 22), mouseX, mouseY, viewport,
			() -> openConditionPicker(label, setter, depth), false, categoryColor(Category.CONTROL));
		y[0] += 26;
	}

	private void drawConditionProperties(GuiGraphicsExtractor graphics, Rect viewport, int[] y,
		MacroCondition condition, Consumer<MacroCondition> setter, int mouseX, int mouseY, int depth) {
		if (depth >= MAX_CONDITION_DEPTH && isCompoundCondition(condition)) {
			lineText(graphics, viewport, y, "Nesting limit", "Choose a simpler condition above");
			return;
		}
		if (condition instanceof MacroCondition.Always always) {
			toggleRow(graphics, viewport, y, "Result · " + (always.expected() ? "Always true" : "Always false"), always.expected(),
				() -> { setter.accept(new MacroCondition.Always(!always.expected())); dirty(); }, mouseX, mouseY);
		} else if (condition instanceof MacroCondition.World world) {
			toggleRow(graphics, viewport, y, "Player is in a world", world.mustBeInWorld(),
				() -> { setter.accept(new MacroCondition.World(!world.mustBeInWorld())); dirty(); }, mouseX, mouseY);
		} else if (condition instanceof MacroCondition.Screen screen) {
			drawTextField(graphics, viewport, y, "Screen title (blank = any)", "condition-screen-title", screen::title,
				value -> setter.accept(new MacroCondition.Screen(value, screen.mustBeOpen(), screen.contains())));
			toggleRow(graphics, viewport, y, "Screen must be open", screen.mustBeOpen(),
				() -> { setter.accept(new MacroCondition.Screen(screen.title(), !screen.mustBeOpen(), screen.contains())); dirty(); }, mouseX, mouseY);
			toggleRow(graphics, viewport, y, "Match title contains", screen.contains(),
				() -> { setter.accept(new MacroCondition.Screen(screen.title(), screen.mustBeOpen(), !screen.contains())); dirty(); }, mouseX, mouseY);
		} else if (condition instanceof MacroCondition.Slot slot) {
			drawIntField(graphics, viewport, y, "Slot id", "condition-slot-id", slot::slotId,
				value -> setter.accept(new MacroCondition.Slot(Math.max(0, value), slot.mustExist())));
			toggleRow(graphics, viewport, y, "Slot must exist", slot.mustExist(),
				() -> { setter.accept(new MacroCondition.Slot(slot.slotId(), !slot.mustExist())); dirty(); }, mouseX, mouseY);
		} else if (condition instanceof MacroCondition.Item item) {
			drawTextField(graphics, viewport, y, "Item name", "condition-item-name", item::name,
				value -> setter.accept(new MacroCondition.Item(value, item.mustExist(), item.contains(), item.scope())));
			toggleRow(graphics, viewport, y, "Item must exist", item.mustExist(),
				() -> { setter.accept(new MacroCondition.Item(item.name(), !item.mustExist(), item.contains(), item.scope())); dirty(); }, mouseX, mouseY);
			toggleRow(graphics, viewport, y, "Partial name match", item.contains(),
				() -> { setter.accept(new MacroCondition.Item(item.name(), item.mustExist(), !item.contains(), item.scope())); dirty(); }, mouseX, mouseY);
			button(graphics, "Search area · " + itemScopeLabel(item.scope().serializedName()), rowRect(viewport, y[0], 22),
				mouseX, mouseY, viewport, () -> {
					MacroCondition.ItemScope[] scopes = MacroCondition.ItemScope.values();
					int next = (item.scope().ordinal() + 1) % scopes.length;
					setter.accept(new MacroCondition.Item(item.name(), item.mustExist(), item.contains(), scopes[next]));
					dirty();
				}, false, categoryColor(Category.INPUT));
			y[0] += 26;
		} else if (condition instanceof MacroCondition.Chat chat) {
			drawTextField(graphics, viewport, y, "Chat text", "condition-chat-text", chat::text,
				value -> setter.accept(new MacroCondition.Chat(value, chat.contains())));
			toggleRow(graphics, viewport, y, "Match contains", chat.contains(),
				() -> { setter.accept(new MacroCondition.Chat(chat.text(), !chat.contains())); dirty(); }, mouseX, mouseY);
		} else if (condition instanceof MacroCondition.All all) {
			drawCompoundConditionChildren(graphics, viewport, y, "AND requirement", all.children(),
				children -> setter.accept(new MacroCondition.All(children)), true, mouseX, mouseY, depth);
		} else if (condition instanceof MacroCondition.Any any) {
			drawCompoundConditionChildren(graphics, viewport, y, "OR option", any.children(),
				children -> setter.accept(new MacroCondition.Any(children)), false, mouseX, mouseY, depth);
		} else if (condition instanceof MacroCondition.Not not) {
			drawConditionButton(graphics, viewport, y, "Inverted condition", not.child(),
				child -> { setter.accept(new MacroCondition.Not(child)); dirty(); }, mouseX, mouseY, depth + 1);
			drawConditionProperties(graphics, viewport, y, not.child(),
				child -> { setter.accept(new MacroCondition.Not(child)); dirty(); }, mouseX, mouseY, depth + 1);
		} else if (condition instanceof MacroCondition.Variable variable) {
			drawVariableTarget(graphics, viewport, y, "Compare variable", variable.name(), variable.comparesGlobal(),
				variable.globalVariableId(), definition -> setter.accept(new MacroCondition.Variable(
					definition.name(), variable.operator(), variable.value(), definition.id())),
				() -> setter.accept(new MacroCondition.Variable(variable.name(), variable.operator(), variable.value(), null)),
				mouseX, mouseY);
			if (!variable.comparesGlobal()) drawTextField(graphics, viewport, y, "Local variable",
				"condition-variable-name", variable::name,
				value -> setter.accept(new MacroCondition.Variable(value, variable.operator(), variable.value(), null)));
			button(graphics, "Compare  ·  " + title(variable.operator().name().replace('_', ' ')),
				rowRect(viewport, y[0], 22), mouseX, mouseY, viewport, () -> {
					MacroCondition.Variable.Operator[] operators = MacroCondition.Variable.Operator.values();
					List<PickerOption> options = new ArrayList<>();
					for (MacroCondition.Variable.Operator operator : operators) {
						options.add(new PickerOption(title(operator.name().replace('_', ' ')), () -> {
							setter.accept(new MacroCondition.Variable(variable.name(), operator, variable.value(), variable.globalVariableId()));
							dirty();
						}));
					}
					openPicker("Choose comparison", options);
				}, false, categoryColor(Category.CONTROL));
			y[0] += 26;
			drawMacroValueEditor(graphics, viewport, y, "Compare to", "condition-variable-value",
				variable.value(), variable.value().type(),
				value -> setter.accept(new MacroCondition.Variable(variable.name(), variable.operator(), value,
					variable.globalVariableId())), mouseX, mouseY);
		} else if (condition != null && !(condition instanceof MacroCondition.Always)) {
			lineText(graphics, viewport, y, "Logic", "Choose a condition type above");
		}
	}

	private void drawCompoundConditionChildren(GuiGraphicsExtractor graphics, Rect viewport, int[] y,
		String childLabel, List<MacroCondition> children, Consumer<List<MacroCondition>> setter,
		boolean conjunction, int mouseX, int mouseY, int depth) {
		String kind = conjunction ? "AND" : "OR";
		button(graphics, "Add " + kind + " requirement", rowRect(viewport, y[0], 22),
			mouseX, mouseY, viewport, () -> {
				if (children.size() >= MAX_COMPOUND_TERMS) {
					flash("A condition group can contain at most " + MAX_COMPOUND_TERMS + " entries.");
					return;
				}
				List<MacroCondition> updated = new ArrayList<>(children);
				updated.add(new MacroCondition.Always(true));
				setter.accept(updated);
				dirty();
			}, false, categoryColor(Category.CONTROL));
		y[0] += 26;
		for (int i = 0; i < children.size(); i++) {
			final int childIndex = i;
			MacroCondition child = children.get(i);
			drawConditionButton(graphics, viewport, y, childLabel + " " + (i + 1), child,
				updatedChild -> {
					List<MacroCondition> updated = new ArrayList<>(children);
					updated.set(childIndex, updatedChild);
					setter.accept(updated);
					dirty();
				}, mouseX, mouseY, depth + 1);
			drawConditionProperties(graphics, viewport, y, child,
				updatedChild -> {
					List<MacroCondition> updated = new ArrayList<>(children);
					updated.set(childIndex, updatedChild);
					setter.accept(updated);
					dirty();
				}, mouseX, mouseY, depth + 1);
			button(graphics, "Remove " + kind + " requirement " + (i + 1), rowRect(viewport, y[0], 22),
				mouseX, mouseY, viewport, () -> {
					List<MacroCondition> updated = new ArrayList<>(children);
					updated.remove(childIndex);
					setter.accept(updated);
					dirty();
				}, false, TEXT_ERROR);
			y[0] += 26;
		}
	}

	private static boolean isCompoundCondition(MacroCondition condition) {
		return condition instanceof MacroCondition.All || condition instanceof MacroCondition.Any
			|| condition instanceof MacroCondition.Not;
	}

	private void openConditionPicker(String label, Consumer<MacroCondition> setter, int depth) {
		List<PickerOption> options = new ArrayList<>();
		addConditionOption(options, "Always true", new MacroCondition.Always(true), setter);
		addConditionOption(options, "Always false", new MacroCondition.Always(false), setter);
		addConditionOption(options, "In world", new MacroCondition.World(true), setter);
		addConditionOption(options, "Screen title", new MacroCondition.Screen("", true, false), setter);
		addConditionOption(options, "Slot exists", new MacroCondition.Slot(0, true), setter);
		addConditionOption(options, "Item exists", new MacroCondition.Item("", true, false,
			MacroCondition.ItemScope.CONTAINER), setter);
		addConditionOption(options, "Chat contains", new MacroCondition.Chat("", true), setter);
		addConditionOption(options, "Variable comparison", new MacroCondition.Variable("value",
			MacroCondition.Variable.Operator.EQUALS, MacroValue.literal(MacroValue.Type.TEXT, "")), setter);
		if (depth < MAX_CONDITION_DEPTH) {
			addConditionOption(options, "AND · all requirements", new MacroCondition.All(List.of(
				new MacroCondition.Always(true), new MacroCondition.Always(true))), setter);
			addConditionOption(options, "OR · any requirement", new MacroCondition.Any(List.of(
				new MacroCondition.Always(true), new MacroCondition.Always(false))), setter);
			addConditionOption(options, "NOT · invert condition", new MacroCondition.Not(new MacroCondition.Always(true)), setter);
		}
		openPicker("Choose a " + label.toLowerCase(Locale.ROOT), options);
	}

	private void addConditionOption(List<PickerOption> options, String label, MacroCondition condition,
		Consumer<MacroCondition> setter) {
		options.add(new PickerOption(label, () -> { setter.accept(condition); dirty(); }));
	}

	private void openPicker(String title, List<PickerOption> options) {
		openPicker(title, options, false);
	}

	private void openPicker(String title, List<PickerOption> options, boolean searchable) {
		if (options == null || options.isEmpty()) return;
		picker = new PickerState(title, options, searchable);
	}

	private static String mouseButtonLabel(int button) {
		return switch (button) { case 1 -> "Right"; case 2 -> "Middle"; default -> "Left"; };
	}

	private static String itemScopeLabel(String scope) {
		return switch (scope == null ? "container" : scope.toLowerCase(Locale.ROOT)) {
			case "player" -> "Player inventory";
			case "all" -> "Container + player";
			default -> "Container only";
		};
	}

	private static String nextItemScope(String scope) {
		return switch (scope == null ? "container" : scope.toLowerCase(Locale.ROOT)) {
			case "container" -> "player";
			case "player" -> "all";
			default -> "container";
		};
	}

	private void drawIntField(GuiGraphicsExtractor graphics, Rect viewport, int[] y, String label,
		String id, Supplier<Integer> getter, IntConsumer setter) {
		drawTextField(graphics, viewport, y, label, id,
			() -> Integer.toString(getter.get()), value -> {
				try { setter.accept(Integer.parseInt(value.trim())); dirty(); }
				catch (NumberFormatException ignored) { }
			});
	}

	private void drawDoubleField(GuiGraphicsExtractor graphics, Rect viewport, int[] y, String label,
		String id, Supplier<Double> getter, Consumer<Double> setter) {
		drawTextField(graphics, viewport, y, label, id,
			() -> String.format(Locale.ROOT, "%.2f", getter.get()), value -> {
				try { setter.accept(Double.parseDouble(value.trim())); dirty(); }
				catch (NumberFormatException ignored) { }
			});
	}

	private void drawFloatField(GuiGraphicsExtractor graphics, Rect viewport, int[] y, String label,
		String id, Supplier<Float> getter, Consumer<Float> setter) {
		drawTextField(graphics, viewport, y, label, id,
			() -> String.format(Locale.ROOT, "%.2f", getter.get()), value -> {
				try { setter.accept(Float.parseFloat(value.trim())); dirty(); }
				catch (NumberFormatException ignored) { }
			});
	}

	private void drawTextField(GuiGraphicsExtractor graphics, Rect viewport, int[] y, String label,
		String id, Supplier<String> getter, Consumer<String> setter) {
		int x = viewport.x + 4;
		int w = Math.max(48, viewport.w - 8);
		graphics.text(font, trim(label, w), x, y[0], TEXT_MUTED);
		y[0] += 11;
		Rect bounds = new Rect(x, y[0], w, 20);
		boolean focused = id.equals(focusedField);
		roundedRectBordered(graphics, bounds.x, bounds.y, bounds.w, bounds.h, RADIUS_SMALL,
			focused ? CARD_BG_HOVER : CARD_BG, focused ? CARD_BG_HOVER : CARD_BG,
			focused ? CARD_BORDER_ENABLED : CARD_BORDER);
		String value = focused ? fieldText : safe(getter.get());
		String displayed = value + (focused ? "|" : "");
		graphics.text(font, trim(displayed, bounds.w - 12), bounds.x + 6, bounds.y + 6, TEXT_PRIMARY);
		FieldBinding binding = new FieldBinding(id, getter, setter);
		registerHit(bounds, viewport, null, null, null, null, null, null, binding);
		y[0] += 25;
	}

	private void drawCompactField(GuiGraphicsExtractor graphics, Rect viewport, int[] y, String label,
		String id, Supplier<String> getter, Consumer<String> setter, int width) {
		int x = viewport.x + 4;
		graphics.text(font, label, x, y[0] + 5, TEXT_MUTED);
		Rect bounds = new Rect(x + 37, y[0], Math.max(40, width - 37), 19);
		boolean focused = id.equals(focusedField);
		roundedRectBordered(graphics, bounds.x, bounds.y, bounds.w, bounds.h, RADIUS_SMALL,
			focused ? CARD_BG_HOVER : CARD_BG, focused ? CARD_BG_HOVER : CARD_BG,
			focused ? CARD_BORDER_ENABLED : CARD_BORDER);
		graphics.text(font, trim(focused ? fieldText + "|" : safe(getter.get()), bounds.w - 8),
			bounds.x + 4, bounds.y + 5, TEXT_PRIMARY);
		registerHit(bounds, viewport, null, null, null, null, null, null,
			new FieldBinding(id, getter, setter));
		y[0] += 22;
	}

	private void toggleRow(GuiGraphicsExtractor graphics, Rect viewport, int[] y, String label,
		boolean value, Runnable action, int mouseX, int mouseY) {
		int x = viewport.x + 4;
		int rowW = Math.max(50, viewport.w - 8);
		Rect bounds = new Rect(x, y[0], rowW, 21);
		graphics.text(font, trim(label, rowW - 47), bounds.x + 1, bounds.y + 6, TEXT_SECONDARY);
		toggleSwitch(graphics, bounds.x + bounds.w - 37, bounds.y + 3, 34, 15, value);
		registerHit(bounds, viewport, action, null, null, null, null, null);
		y[0] += 24;
	}

	private void sectionTitle(GuiGraphicsExtractor graphics, Rect viewport, int[] y, String text) {
		graphics.fill(viewport.x + 3, y[0] + 1, viewport.x + viewport.w - 3, y[0] + 2, PANEL_HIGHLIGHT);
		graphics.text(font, text, viewport.x + 4, y[0] + 6, TEXT_PRIMARY);
		y[0] += 19;
	}

	private void lineText(GuiGraphicsExtractor graphics, Rect viewport, int[] y, String label, String value) {
		int x = viewport.x + 4;
		int labelW = Math.min(62, Math.max(42, viewport.w / 3));
		graphics.text(font, trim(label, labelW), x, y[0] + 4, TEXT_MUTED);
		graphics.text(font, trim(value, viewport.w - labelW - 15), x + labelW, y[0] + 4, TEXT_SECONDARY);
		y[0] += 17;
	}

	private Rect rowRect(Rect viewport, int y, int h) {
		return new Rect(viewport.x + 4, y, Math.max(48, viewport.w - 8), h);
	}

	private void drawPanel(GuiGraphicsExtractor graphics, Rect pane, String title, String subtitle) {
		roundedRectBordered(graphics, pane.x, pane.y, pane.w, pane.h, RADIUS_SMALL,
			CARD_BG, CARD_BG, CARD_BORDER);
		graphics.fill(pane.x + 1, pane.y + 1, pane.x + pane.w - 1, pane.y + 3, PANEL_HIGHLIGHT);
		graphics.text(font, title, pane.x + 8, pane.y + 8, TEXT_PRIMARY);
		graphics.text(font, trim(subtitle, pane.w - 16), pane.x + 8, pane.y + 21, TEXT_MUTED);
	}

	private void button(GuiGraphicsExtractor graphics, String label, Rect bounds, int mouseX, int mouseY,
		Rect clip, Runnable action, boolean selected, int accent) {
		if (bounds.w < 1 || bounds.h < 1) return;
		boolean hovered = bounds.contains(mouseX, mouseY);
		int base = selected ? CARD_BG_ENABLED : hovered ? CARD_BG_HOVER : BUTTON_BG;
		int border = selected ? CARD_BORDER_ENABLED : accent != 0 ? withOpacity(accent, 0.8f) : CARD_BORDER;
		roundedRectBordered(graphics, bounds.x, bounds.y, bounds.w, bounds.h, RADIUS_SMALL,
			base, base, border);
		if (accent != 0) graphics.fill(bounds.x + 1, bounds.y + 3, bounds.x + 3, bounds.y + bounds.h - 3, accent);
		graphics.centeredText(font, trim(label, bounds.w - 9), bounds.x + bounds.w / 2,
			bounds.y + Math.max(4, (bounds.h - 8) / 2), selected ? TEXT_PRIMARY : TEXT_SECONDARY);
		registerHit(bounds, clip, action, null, null, null, null, null);
	}

	private void registerHit(Rect bounds, Rect clip, Runnable action, MacroStep step,
		List<MacroStep> owner, PaletteBlock paletteBlock, MacroScript script,
		MacroFunction function) {
		registerHit(bounds, clip, action, step, owner, paletteBlock, script, function, null);
	}

	private void registerHit(Rect bounds, Rect clip, Runnable action, MacroStep step,
		List<MacroStep> owner, PaletteBlock paletteBlock, MacroScript script,
		MacroFunction function, FieldBinding field) {
		if (replayingCanvasTargets) return;
		Rect visible = clip == null ? bounds : bounds.intersection(clip);
		if (visible == null) return;
		hitTargets.add(new HitTarget(visible, action, step, owner, paletteBlock, script, function, field));
	}

	private void renderFooter(GuiGraphicsExtractor graphics, Layout layout) {
		String hint = noticeUntil > System.currentTimeMillis() ? notice
			: (undoAction == null ? "" : "Ctrl+Z undo " + undoLabel + "  ·  ")
				+ "Drag empty canvas to pan  ·  Scroll to zoom  ·  Drag blocks to snap  ·  Esc to close";
		graphics.text(font, trim(hint, layout.panel.w - 20), layout.panel.x + 11,
			layout.panel.y + layout.panel.h - 16, noticeUntil > System.currentTimeMillis() ? TEXT_WARN : TEXT_MUTED);
	}

	private void renderDragPreview(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (drag == null || !drag.moved || drag.kind == DragKind.PAN) return;
		String label = drag.kind == DragKind.PALETTE ? drag.palette.label
			: drag.kind == DragKind.STEP ? blockLabel(drag.step) : triggerName(drag.script.trigger());
		int w = Math.min(230, Math.max(92, font.width(label) + 20));
		int x = clamp(mouseX + 12, 3, Math.max(3, width - w - 3));
		int y = clamp(mouseY + 12, 3, Math.max(3, height - 24));
		int color = drag.kind == DragKind.PALETTE ? categoryColor(drag.palette.category)
			: drag.kind == DragKind.STEP ? blockColor(drag.step) : categoryColor(Category.ACTIONS);
		roundedRectBordered(graphics, x, y, w, 21, RADIUS_SMALL,
			withOpacity(color, 0.94f), withOpacity(color, 0.94f), TEXT_PRIMARY);
		graphics.text(font, trim(label, w - 12), x + 6, y + 6, 0xFFFFFFFF);
	}

	private void renderPicker(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (picker == null) return;
		graphics.fill(0, 0, width, height, withOpacity(0xFF000000, 0.52f));
		List<PickerOption> options = picker.visibleOptions();
		int maxHeight = Math.max(90, height - 24);
		int headerHeight = picker.searchable ? 88 : 64;
		int maxRows = Math.max(1, Math.min(8, (maxHeight - headerHeight) / 24));
		picker.visibleRows = Math.min(Math.max(1, options.size()), maxRows);
		picker.maxScroll = Math.max(0, options.size() - picker.visibleRows);
		picker.scroll = clamp(picker.scroll, 0, picker.maxScroll);
		picker.selected = options.isEmpty() ? 0 : clamp(picker.selected, 0, options.size() - 1);
		if (picker.selected < picker.scroll) picker.scroll = picker.selected;
		if (picker.selected >= picker.scroll + picker.visibleRows) picker.scroll = picker.selected - picker.visibleRows + 1;
		int modalW = Math.min(420, Math.max(230, width - 24));
		int modalH = Math.min(maxHeight, headerHeight + picker.visibleRows * 24);
		int x = Math.max(8, (width - modalW) / 2);
		int y = Math.max(8, (height - modalH) / 2);
		Rect modal = new Rect(x, y, Math.min(modalW, width - 16), Math.min(modalH, height - 16));
		roundedRectBordered(graphics, modal.x, modal.y, modal.w, modal.h, RADIUS,
			PANEL_TOP, PANEL_BOTTOM, BORDER);
		graphics.text(font, trim(picker.title, modal.w - 20), modal.x + 10, modal.y + 9, TEXT_PRIMARY);
		registerHit(new Rect(0, 0, width, height), null, () -> picker = null,
			null, null, null, null, null);
		int rowsTop = modal.y + 31;
		if (picker.searchable) {
			Rect search = new Rect(modal.x + 8, modal.y + 28, modal.w - 16, 20);
			roundedRectBordered(graphics, search.x, search.y, search.w, search.h, RADIUS_SMALL,
				CARD_BG, CARD_BG, CARD_BORDER_ENABLED);
			String searchLabel = picker.search.isBlank() ? "Type to filter sound events" : "Search · " + picker.search;
			graphics.text(font, trim(searchLabel, search.w - 12), search.x + 6, search.y + 6, TEXT_SECONDARY);
			registerHit(search, null, () -> { }, null, null, null, null, null);
			rowsTop = modal.y + 55;
		}
		Rect rows = new Rect(modal.x + 7, rowsTop, modal.w - 14, picker.visibleRows * 24);
		graphics.enableScissor(rows.x, rows.y, rows.x + rows.w, rows.y + rows.h);
		if (options.isEmpty()) {
			graphics.text(font, "No matching sound events", rows.x + 7, rows.y + 7, TEXT_MUTED);
		} else {
			for (int visible = 0; visible < picker.visibleRows; visible++) {
				int index = picker.scroll + visible;
				if (index >= options.size()) break;
				PickerOption option = options.get(index);
				Rect row = new Rect(rows.x, rows.y + visible * 24, rows.w, 21);
				button(graphics, option.label, row, mouseX, mouseY, rows, () -> {
					option.action.run();
					picker = null;
				}, picker.selected == index, categoryColor(Category.FUNCTIONS));
			}
		}
		graphics.disableScissor();
		String hint = picker.searchable
			? "Type to search  ·  Backspace delete  ·  ↑ / ↓  ·  Enter select  ·  Esc close"
			: "↑ / ↓ select  ·  Enter confirm  ·  Esc close  ·  Scroll for more";
		graphics.centeredText(font, hint,
			modal.x + modal.w / 2, modal.y + modal.h - 17, TEXT_MUTED);
	}

	private void renderGlobalManager(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (!globalManagerOpen || picker != null) return;
		Rect modal = globalModalRect();
		graphics.fill(0, 0, width, height, withOpacity(0xFF000000, 0.68f));
		roundedRectBordered(graphics, modal.x, modal.y, modal.w, modal.h, RADIUS,
			PANEL_TOP, PANEL_BOTTOM, BORDER);
		graphics.fill(modal.x + 1, modal.y + 1, modal.x + modal.w - 1, modal.y + 3, PANEL_HIGHLIGHT);
		graphics.text(font, "Global variables", modal.x + 12, modal.y + 10, TEXT_PRIMARY);
		graphics.text(font, "Shared across macros and function calls · values stay local to this client",
			modal.x + 12, modal.y + 25, TEXT_MUTED);
		registerHit(modal, null, null, null, null, null, null, null);
		Rect close = new Rect(modal.x + modal.w - 54, modal.y + 7, 42, 20);
		button(graphics, "Done", close, mouseX, mouseY, modal,
			() -> { commitFocusedField(); globalManagerOpen = false; }, false, 0);

		int[] y = {modal.y + 43};
		Rect inputPane = new Rect(modal.x + 8, y[0], modal.w - 16, 69);
		drawTextField(graphics, inputPane, y, "New variable name", "global-create-name",
			() -> globalNameDraft, value -> globalNameDraft = value);
		int controlsY = y[0] - 2;
		Rect type = new Rect(inputPane.x + 4, controlsY, 94, 21);
		button(graphics, "Type · " + globalNewType.name(), type, mouseX, mouseY, inputPane,
			() -> globalNewType = next(globalNewType), false, categoryColor(Category.VARIABLES));
		Rect create = new Rect(inputPane.x + inputPane.w - 100, controlsY, 96, 21);
		button(graphics, "Create global", create, mouseX, mouseY, inputPane,
			() -> createGlobalVariable(), false, categoryColor(Category.VARIABLES));
		y[0] = inputPane.y + inputPane.h + 7;
		MacroVariableStore store = MacrosModule.INSTANCE.globalVariables();
		List<MacroVariableStore.Definition> definitions = store.definitions();
		graphics.text(font, definitions.size() + " / " + MacroVariableStore.MAX_VARIABLES + " variables",
			modal.x + 12, y[0], TEXT_MUTED);
		y[0] += 13;
		Rect rows = new Rect(modal.x + 8, y[0], modal.w - 16,
			Math.max(24, modal.y + modal.h - y[0] - 27));
		int rowHeight = 51;
		int visibleRows = Math.max(1, rows.h / rowHeight);
		int maxScroll = Math.max(0, definitions.size() - visibleRows);
		globalScroll = clamp(globalScroll, 0, maxScroll);
		graphics.enableScissor(rows.x, rows.y, rows.x + rows.w, rows.y + rows.h);
		for (int visible = 0; visible < visibleRows; visible++) {
			int index = globalScroll + visible;
			if (index >= definitions.size()) break;
			MacroVariableStore.Definition definition = definitions.get(index);
			Rect row = new Rect(rows.x, rows.y + visible * rowHeight, rows.w, rowHeight - 3);
			roundedRectBordered(graphics, row.x, row.y, row.w, row.h, RADIUS_SMALL,
				CARD_BG, CARD_BG, CARD_BORDER);
			drawGlobalVariableRow(graphics, row, definition, mouseX, mouseY);
		}
		graphics.disableScissor();
		if (definitions.isEmpty()) graphics.centeredText(font, "Create a global variable above to share a value across macros.",
			rows.x + rows.w / 2, rows.y + 8, TEXT_MUTED);
		graphics.centeredText(font, "Unsaved live values reset on restart. Enable Save value per variable to keep it locally.",
			modal.x + modal.w / 2, modal.y + modal.h - 17, TEXT_MUTED);
	}

	private void drawGlobalVariableRow(GuiGraphicsExtractor graphics, Rect row,
		MacroVariableStore.Definition definition, int mouseX, int mouseY) {
		MacroVariableStore store = MacrosModule.INSTANCE.globalVariables();
		int x = row.x + 4;
		Rect name = new Rect(x, row.y + 3, row.w - 8, 18);
		drawInlineField(graphics, name, row,
			"global-name-" + definition.id(),
			() -> {
				MacroVariableStore.Definition current = store.definition(definition.id());
				return current == null ? "" : current.name();
			}, value -> { store.rename(definition.id(), value); dirty(); });
		int buttonY = row.y + 25;
		int gap = 3;
		int controlsW = Math.max(1, row.w - 8 - gap * 3);
		int deleteW = Math.min(48, Math.max(32, controlsW / 5));
		int saveW = Math.min(61, Math.max(44, controlsW / 4));
		int typeW = Math.min(67, Math.max(46, controlsW / 3));
		int valueW = Math.max(40, controlsW - deleteW - saveW - typeW);
		Rect value = new Rect(x, buttonY, valueW, 19);
		drawInlineField(graphics, value, row,
			"global-value-" + definition.id(),
			() -> String.valueOf(store.value(definition.id())),
			input -> { store.setValue(definition.id(), input); dirty(); });
		int typeX = value.x + value.w + gap;
		Rect type = new Rect(typeX, buttonY, typeW, 19);
		button(graphics, definition.type().name(), type, mouseX, mouseY, row,
			() -> { store.setType(definition.id(), next(definition.type())); dirty(); }, false, categoryColor(Category.VARIABLES));
		int saveX = typeX + typeW + gap;
		Rect save = new Rect(saveX, buttonY, saveW, 19);
		button(graphics, definition.persistValue() ? "Saved" : "Save value", save, mouseX, mouseY, row,
			() -> { store.setPersistValue(definition.id(), !definition.persistValue()); dirty(); },
			definition.persistValue(), definition.persistValue() ? categoryColor(Category.VARIABLES) : 0);
		int deleteX = saveX + saveW + gap;
		Rect delete = new Rect(deleteX, buttonY, deleteW, 19);
		button(graphics, "Remove", delete, mouseX, mouseY, row,
			() -> { store.remove(definition.id()); globalScroll = Math.max(0, globalScroll - 1); dirty(); },
			false, TEXT_ERROR);
	}

	private void drawInlineField(GuiGraphicsExtractor graphics, Rect bounds, Rect clip,
		String id, Supplier<String> getter, Consumer<String> setter) {
		boolean focused = id.equals(focusedField);
		roundedRectBordered(graphics, bounds.x, bounds.y, bounds.w, bounds.h, RADIUS_SMALL,
			focused ? CARD_BG_HOVER : BUTTON_BG, focused ? CARD_BG_HOVER : BUTTON_BG,
			focused ? CARD_BORDER_ENABLED : CARD_BORDER);
		String shown = focused ? fieldText + "|" : safe(getter.get());
		graphics.text(font, trim(shown, bounds.w - 8), bounds.x + 4, bounds.y + 5, TEXT_PRIMARY);
		registerHit(bounds, clip, null, null, null, null, null, null,
			new FieldBinding(id, getter, setter));
	}

	private void createGlobalVariable() {
		MacroVariableStore store = MacrosModule.INSTANCE.globalVariables();
		String name = globalNameDraft.strip();
		if (name.isEmpty()) name = "Global " + (store.definitions().size() + 1);
		MacroVariableStore.Definition definition = store.create(name, globalNewType);
		if (definition == null) {
			flash("Choose a unique name and make room under the 256 variable limit.");
			return;
		}
		globalNameDraft = "";
		globalScroll = Integer.MAX_VALUE;
		dirty();
		flash("Created " + definition.name());
	}

	private Rect globalModalRect() {
		int modalW = Math.min(620, Math.max(230, width - 24));
		int modalH = Math.min(Math.max(170, height - 24), 500);
		return new Rect(Math.max(8, (width - modalW) / 2), Math.max(8, (height - modalH) / 2),
			Math.min(modalW, width - 16), Math.min(modalH, height - 16));
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int x = (int) event.x();
		int y = (int) event.y();
		currentMouseX = x;
		currentMouseY = y;
		if (globalManagerOpen) {
			if (event.button() != 0) return true;
			if (!globalModalRect().contains(x, y)) {
				commitFocusedField();
				globalManagerOpen = false;
				return true;
			}
		}
		if (picker != null && event.button() != 0) return true;
		if (event.button() == 2 && canvasViewportContains(x, y)) {
			drag = DragState.pan(x, y, macro.canvasPanX(), macro.canvasPanY());
			return true;
		}
		if (event.button() != 0) return super.mouseClicked(event, doubleClick);
		for (int i = hitTargets.size() - 1; i >= 0; i--) {
			HitTarget target = hitTargets.get(i);
			if (!target.bounds.contains(x, y)) continue;
			if (target.field != null) {
				if (focusedField != null && !focusedField.equals(target.field.id)) commitFocusedField();
				focusField(target.field);
				return true;
			}
			if (focusedField != null) commitFocusedField();
			if (target.paletteBlock != null) {
				drag = DragState.palette(target.paletteBlock, x, y);
				return true;
			}
			if (target.step != null) {
				selectStep(target.step, target.owner, target.script, target.function);
				drag = DragState.step(target.step, target.owner, target.script, target.function, x, y);
				return true;
			}
			if (target.script != null) {
				selectScript(target.script);
				drag = DragState.script(target.script, x, y);
				return true;
			}
			if (target.function != null) {
				selectFunction(target.function);
				drag = DragState.function(target.function, x, y);
				return true;
			}
			if (target.action != null) target.action.run();
			return true;
		}
		if (focusedField != null) commitFocusedField();
		if (canvasViewportContains(x, y)) {
			drag = DragState.pan(x, y, macro.canvasPanX(), macro.canvasPanY());
			return true;
		}
		if (lastLayout != null && lastLayout.panel.contains(x, y)) return true;
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (drag == null) return super.mouseDragged(event, dragX, dragY);
		if (event.button() != 0 && event.button() != 2) return super.mouseDragged(event, dragX, dragY);
		int x = (int) event.x();
		int y = (int) event.y();
		currentMouseX = x;
		currentMouseY = y;
		int dx = x - drag.startX;
		int dy = y - drag.startY;
		if (!drag.moved && dx * dx + dy * dy >= DRAG_THRESHOLD_SQUARED) drag.moved = true;
		if (!drag.moved) return true;
		if (drag.kind == DragKind.PAN && macro != null) {
			macro.setCanvasView(drag.startPanX + dx, drag.startPanY + dy, macro.canvasZoom());
			viewChanged();
		} else if (drag.kind == DragKind.SCRIPT && drag.script != null) {
			float zoom = Math.max(0.45f, macro.canvasZoom());
			drag.script.setCanvasPosition(drag.startCanvasX + dx / zoom, drag.startCanvasY + dy / zoom);
			viewChanged();
		} else if (drag.kind == DragKind.FUNCTION && drag.function != null) {
			float zoom = Math.max(0.45f, macro.canvasZoom());
			drag.function.setCanvasPosition(drag.startCanvasX + dx / zoom, drag.startCanvasY + dy / zoom);
			viewChanged();
		}
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (drag == null || (event.button() != 0 && event.button() != 2)) return super.mouseReleased(event);
		DragState released = drag;
		int x = (int) event.x();
		int y = (int) event.y();
		currentMouseX = x;
		currentMouseY = y;
		drag = null;
		if (released.kind == DragKind.PALETTE) {
			if (!released.moved) insertPalette(released.palette, null);
			else {
				DropSlot target = findDropSlot(x, y, false);
				if (target != null) insertPalette(released.palette, target);
			}
			return true;
		}
		if (released.kind == DragKind.STEP && released.moved) {
			DropSlot target = findDropSlot(x, y, false);
			if (target != null) moveStep(released, target);
			return true;
		}
		if (released.kind == DragKind.PAN || released.kind == DragKind.SCRIPT || released.kind == DragKind.FUNCTION) {
			viewChanged();
			return true;
		}
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (lastLayout == null) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
		int x = (int) mouseX;
		int y = (int) mouseY;
		if (globalManagerOpen && globalModalRect().contains(x, y)) {
			List<MacroVariableStore.Definition> definitions = MacrosModule.INSTANCE.globalVariables().definitions();
			int visibleRows = Math.max(1, (globalModalRect().h - 166) / 51);
			int maxScroll = Math.max(0, definitions.size() - visibleRows);
			globalScroll = clamp(globalScroll - (int) Math.signum(scrollY) * 2, 0, maxScroll);
			return true;
		}
		if (picker != null) {
			picker.scroll = clamp(picker.scroll - (int) Math.signum(scrollY) * 3, 0, picker.maxScroll);
			return true;
		}
		if (canvasViewportContains(x, y)) {
			setZoomAround(scrollY > 0 ? 1.1f : 0.9f, mouseX, mouseY);
			return true;
		}
		if (lastLayout.compact) {
			if (!lastLayout.compactContent.contains(x, y)) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
				switch (compactPane) {
				case PALETTE -> paletteScroll = Math.max(0, paletteScroll - (int) Math.signum(scrollY) * 30);
				case INSPECTOR -> inspectorScroll = clamp(inspectorScroll - (int) Math.signum(scrollY) * 28,
					0, inspectorMaxScroll);
				case CANVAS -> { return true; }
			}
			return true;
		}
		if (lastLayout.palette.contains(x, y)) paletteScroll = Math.max(0, paletteScroll - (int) Math.signum(scrollY) * 30);
		else if (lastLayout.inspector.contains(x, y)) inspectorScroll = clamp(
			inspectorScroll - (int) Math.signum(scrollY) * 28, 0, inspectorMaxScroll);
		else return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (picker != null) {
			if (picker.searchable && event.isAllowedChatCharacter()) {
				String typed = event.codepointAsString();
				if (picker.search.length() + typed.length() <= 128) picker.setSearch(picker.search + typed);
			}
			return true;
		}
		if (focusedField == null || !event.isAllowedChatCharacter()) return super.charTyped(event);
		if (selectAll) {
			fieldText = "";
			fieldCursor = 0;
			selectAll = false;
		}
		String typed = event.codepointAsString();
		if (fieldText.length() + typed.length() <= 256) {
			fieldText = fieldText.substring(0, fieldCursor) + typed + fieldText.substring(fieldCursor);
			fieldCursor += typed.length();
			if (focusedSetter != null) focusedSetter.accept(fieldText);
			if (!"palette-search".equals(focusedField)) dirty();
		}
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (picker != null) {
			if (event.key() == InputConstants.KEY_ESCAPE) { picker = null; return true; }
			if (picker.searchable && event.key() == InputConstants.KEY_BACKSPACE && !picker.search.isEmpty()) {
				int previous = picker.search.length() - Character.charCount(picker.search.codePointBefore(picker.search.length()));
				picker.setSearch(picker.search.substring(0, previous));
				return true;
			}
			List<PickerOption> options = picker.visibleOptions();
			if (event.key() == GLFW.GLFW_KEY_UP || event.key() == GLFW.GLFW_KEY_DOWN) {
				if (!options.isEmpty()) {
					int direction = event.key() == GLFW.GLFW_KEY_UP ? -1 : 1;
					picker.selected = clamp(picker.selected + direction, 0, options.size() - 1);
					if (picker.selected < picker.scroll) picker.scroll = picker.selected;
					if (picker.selected >= picker.scroll + picker.visibleRows) {
						picker.scroll = picker.selected - picker.visibleRows + 1;
					}
				}
				return true;
			}
			if (event.key() == InputConstants.KEY_RETURN && !options.isEmpty()) {
				options.get(picker.selected).action.run();
				picker = null;
				return true;
			}
			return true;
		}
		if (focusedField != null) {
			if (event.key() == GLFW.GLFW_KEY_A && (event.modifiers() & InputConstants.MOD_CONTROL) != 0) {
				selectAll = true;
				return true;
			}
			if (event.key() == InputConstants.KEY_BACKSPACE || event.key() == GLFW.GLFW_KEY_DELETE) {
				if (selectAll) {
					fieldText = "";
					fieldCursor = 0;
					selectAll = false;
				} else if (event.key() == InputConstants.KEY_BACKSPACE && fieldCursor > 0) {
					int previous = fieldCursor - Character.charCount(fieldText.codePointBefore(fieldCursor));
					fieldText = fieldText.substring(0, previous) + fieldText.substring(fieldCursor);
					fieldCursor = previous;
				} else if (event.key() == GLFW.GLFW_KEY_DELETE && fieldCursor < fieldText.length()) {
					int next = fieldCursor + Character.charCount(fieldText.codePointAt(fieldCursor));
					fieldText = fieldText.substring(0, fieldCursor) + fieldText.substring(next);
				}
				applyFocusedField();
				return true;
			}
			if (event.key() == GLFW.GLFW_KEY_LEFT) {
				fieldCursor = Math.max(0, fieldCursor - 1);
				selectAll = false;
				return true;
			}
			if (event.key() == GLFW.GLFW_KEY_RIGHT) {
				fieldCursor = Math.min(fieldText.length(), fieldCursor + 1);
				selectAll = false;
				return true;
			}
			if (event.key() == InputConstants.KEY_RETURN) { commitFocusedField(); return true; }
			if (event.key() == InputConstants.KEY_ESCAPE) { cancelFocusedField(); return true; }
		}
		if (globalManagerOpen && event.key() == InputConstants.KEY_ESCAPE) {
			globalManagerOpen = false;
			return true;
		}
		if (event.key() == InputConstants.KEY_ESCAPE) {
			if (isBindingInThisEditor()) ModuleKeybindManager.cancelBinding();
			else onClose();
			return true;
		}
		if (focusedField == null && (event.modifiers() & InputConstants.MOD_CONTROL) != 0
			&& event.key() == GLFW.GLFW_KEY_Z) {
			undoLastEdit();
			return true;
		}
		if (event.key() == GLFW.GLFW_KEY_DELETE || event.key() == InputConstants.KEY_BACKSPACE) {
			removeSelectedStep();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		commitFocusedField();
		if (isBindingInThisEditor()) ModuleKeybindManager.cancelBinding();
		closeToParent();
	}

	@Override
	public void removed() {
		drag = null;
		if (isBindingInThisEditor()) ModuleKeybindManager.cancelBinding();
		super.removed();
	}

	private void closeToParent() {
		drag = null;
		commitFocusedField();
		if (isBindingInThisEditor()) ModuleKeybindManager.cancelBinding();
		dirty();
		if (minecraft != null) minecraft.setScreen(parent);
	}

	private boolean isBindingInThisEditor() {
		MacroScript script = ModuleKeybindManager.bindingScript();
		MacroStep.Key key = ModuleKeybindManager.bindingKeyStep();
		return macro != null && ((script != null && macro.scripts().contains(script))
			|| ModuleKeybindManager.bindingMacro() == macro)
			|| key != null && selectedStep == key;
	}

	private void renderCanvasToolbarUnused() { }

	private void setZoomAround(float factor, double mouseX, double mouseY) {
		if (macro == null || lastLayout == null) return;
		Rect viewport = canvasViewport(lastLayout);
		float oldZoom = macro.canvasZoom();
		float newZoom = clamp(oldZoom * factor, 0.45f, 2.0f);
		if (newZoom == oldZoom) return;
		double worldX = (mouseX - (viewport.x + 16 + macro.canvasPanX())) / oldZoom;
		double worldY = (mouseY - (viewport.y + 14 + macro.canvasPanY())) / oldZoom;
		float panX = (float) (mouseX - viewport.x - 16 - worldX * newZoom);
		float panY = (float) (mouseY - viewport.y - 14 - worldY * newZoom);
		macro.setCanvasView(panX, panY, newZoom);
		viewChanged();
	}

	private boolean canvasViewportContains(int x, int y) {
		return lastLayout != null && canvasViewport(lastLayout).contains(x, y);
	}

	private Rect canvasViewport(Layout layout) {
		Rect pane = layout.canvas;
		if (pane == null) return new Rect(0, 0, 0, 0);
		return new Rect(pane.x + 5, pane.y + 63, Math.max(1, pane.w - 10), Math.max(1, pane.h - 70));
	}

	private DropSlot findDropSlot(int x, int y, boolean includeOutOfBounds) {
		for (int i = dropSlots.size() - 1; i >= 0; i--) {
			DropSlot slot = dropSlots.get(i);
			if (!slot.bounds.contains(x, y)) continue;
			if (drag != null && drag.kind == DragKind.STEP) {
				if ((drag.function != null) != slot.functionScope) continue;
				if (isDescendantOwner(drag.step, slot.owner)) continue;
				if (slot.depth + subtreeDepth(drag.step) > MAX_TREE_DEPTH) continue;
			}
			return slot;
		}
		return null;
	}

	private void insertPalette(PaletteBlock block, DropSlot target) {
		if (block == null) return;
		List<MacroStep> owner;
		int index;
		if (target != null) {
			owner = target.owner;
			index = target.index;
		} else {
			owner = insertionList();
			index = insertionIndex(owner);
		}
		if (owner == null) return;
		MacroStep step = block.factory.get();
		if (step == null) return;
		owner.add(clamp(index, 0, owner.size()), step);
		registerUndo("block add", () -> {
			if (owner.remove(step)) {
				clearSelection();
				dirty();
			}
		});
		selectedStep = step;
		selectedOwner = owner;
		if (target != null) selectDropContext(target);
		else if (tab == Tab.CODE && selectedScript == null) selectedScript = macro.primaryKeyScript();
		inspectorScroll = 0;
		dirty();
	}

	private void moveStep(DragState source, DropSlot target) {
		if (source == null || target == null || source.owner == null || source.step == null) return;
		if ((source.function != null) != target.functionScope || isDescendantOwner(source.step, target.owner)
			|| target.depth + subtreeDepth(source.step) > MAX_TREE_DEPTH) return;
		int sourceIndex = source.owner.indexOf(source.step);
		if (sourceIndex < 0) return;
		List<MacroStep> originalOwner = source.owner;
		int originalIndex = sourceIndex;
		MacroStep movedStep = source.step;
		MacroScript originalScript = source.script;
		MacroFunction originalFunction = source.function;
		int destination = clamp(target.index, 0, target.owner.size());
		if (source.owner == target.owner && (destination == sourceIndex || destination == sourceIndex + 1)) return;
		source.owner.remove(sourceIndex);
		if (source.owner == target.owner && sourceIndex < destination) destination--;
		target.owner.add(clamp(destination, 0, target.owner.size()), source.step);
		List<MacroStep> destinationOwner = target.owner;
		registerUndo("block move", () -> {
			int currentIndex = destinationOwner.indexOf(movedStep);
			if (currentIndex < 0) return;
			destinationOwner.remove(currentIndex);
			originalOwner.add(clamp(originalIndex, 0, originalOwner.size()), movedStep);
			selectStep(movedStep, originalOwner, originalScript, originalFunction);
			dirty();
		});
		selectedStep = source.step;
		selectedOwner = target.owner;
		selectDropContext(target);
		dirty();
	}

	private void selectDropContext(DropSlot target) {
		if (target.functionScope) {
			tab = Tab.FUNCTIONS;
			if (target.function != null) selectedFunction = target.function;
		} else {
			tab = Tab.CODE;
			if (target.script != null) selectedScript = target.script;
		}
	}

	private boolean isDescendantOwner(MacroStep step, List<MacroStep> owner) {
		if (step instanceof MacroStep.IfElse branch) {
			return branch.thenSteps() == owner || branch.elseSteps() == owner
				|| containsDescendant(branch.thenSteps(), owner) || containsDescendant(branch.elseSteps(), owner);
		}
		if (step instanceof MacroStep.Repeat repeat) return repeat.steps() == owner || containsDescendant(repeat.steps(), owner);
		if (step instanceof MacroStep.RepeatUntil repeat) return repeat.steps() == owner || containsDescendant(repeat.steps(), owner);
		return false;
	}

	private boolean containsDescendant(List<MacroStep> steps, List<MacroStep> owner) {
		for (MacroStep nested : steps) if (isDescendantOwner(nested, owner)) return true;
		return false;
	}

	private int subtreeDepth(MacroStep step) {
		if (step instanceof MacroStep.IfElse branch) return 1 + Math.max(listDepth(branch.thenSteps()), listDepth(branch.elseSteps()));
		if (step instanceof MacroStep.Repeat repeat) return 1 + listDepth(repeat.steps());
		if (step instanceof MacroStep.RepeatUntil repeat) return 1 + listDepth(repeat.steps());
		return 1;
	}

	private int listDepth(List<MacroStep> list) {
		int depth = 0;
		for (MacroStep step : list) depth = Math.max(depth, subtreeDepth(step));
		return depth;
	}

	private void removeSelectedStep() {
		if (selectedStep == null || selectedOwner == null) return;
		int index = selectedOwner.indexOf(selectedStep);
		if (index < 0) return;
		List<MacroStep> owner = selectedOwner;
		MacroStep removed = selectedStep;
		MacroScript script = selectedScript;
		MacroFunction function = selectedFunction;
		owner.remove(index);
		registerUndo("block delete", () -> {
			owner.add(clamp(index, 0, owner.size()), removed);
			selectStep(removed, owner, script, function);
			dirty();
		});
		selectedStep = null;
		selectedOwner = null;
		inspectorScroll = 0;
		flash("Block deleted · Ctrl+Z restores it");
		dirty();
	}

	private void registerUndo(String label, Runnable action) {
		undoLabel = label;
		undoAction = action;
	}

	private void undoLastEdit() {
		if (undoAction == null) return;
		Runnable action = undoAction;
		String label = undoLabel;
		undoAction = null;
		undoLabel = "";
		action.run();
		flash("Undid " + label);
	}

	private List<MacroStep> insertionList() {
		if (selectedStep != null && selectedOwner != null && selectedOwner.contains(selectedStep)) return selectedOwner;
		if (tab == Tab.FUNCTIONS) {
			ensureFunction();
			return selectedFunction == null ? null : selectedFunction.steps();
		}
		if (selectedScript == null) selectedScript = macro.primaryKeyScript();
		return selectedScript.steps();
	}

	private int insertionIndex(List<MacroStep> owner) {
		if (selectedStep != null && selectedOwner == owner) {
			int i = owner.indexOf(selectedStep);
			if (i >= 0) return i + 1;
		}
		return owner.size();
	}

	private void addScript(MacroScript.Trigger trigger) {
		if (macro == null) return;
		commitFocusedField();
		MacroScript script = macro.addScript(trigger);
		int n = macro.scripts().size() - 1;
		script.setCanvasPosition(280 + (n % 3) * 300, (n / 3) * 210);
		selectScript(script);
		tab = Tab.CODE;
		dirty();
	}

	private void removeSelectedScript() {
		if (macro == null || selectedScript == null) return;
		long keyScripts = macro.scripts().stream().filter(script -> script.trigger() == MacroScript.Trigger.KEY_PRESS).count();
		if (selectedScript.trigger() == MacroScript.Trigger.KEY_PRESS && keyScripts <= 1) {
			flash("Keep at least one key event stack in each macro.");
			return;
		}
		if (ModuleKeybindManager.bindingScript() == selectedScript) ModuleKeybindManager.cancelBinding();
		macro.scripts().remove(selectedScript);
		selectedScript = macro.primaryKeyScript();
		clearSelection();
		dirty();
	}

	private void toggleScriptBinding() {
		if (selectedScript == null || selectedScript.trigger() != MacroScript.Trigger.KEY_PRESS) return;
		if (ModuleKeybindManager.bindingScript() == selectedScript) ModuleKeybindManager.cancelBinding();
		else ModuleKeybindManager.beginScriptBinding(selectedScript);
	}

	private void createFunction() {
		MacroFunction function = MacrosModule.INSTANCE.createFunction();
		selectFunction(function);
		tab = Tab.FUNCTIONS;
		compactPane = CompactPane.INSPECTOR;
		dirty();
	}

	private void ensureFunction() {
		if (selectedFunction == null || MacrosModule.INSTANCE.function(selectedFunction.id()) == null) selectedFunction = firstFunction();
	}

	private MacroFunction firstFunction() {
		List<MacroFunction> functions = MacrosModule.INSTANCE.functions();
		return functions.isEmpty() ? null : functions.getFirst();
	}

	private void addFunctionParameter(MacroFunction function) {
		if (function == null) return;
		int number = function.parameters().size() + 1;
		function.parameters().add(new MacroFunction.Parameter("input" + number, MacroValue.Type.TEXT, ""));
		dirty();
	}

	private void updateParameter(MacroFunction function, int index, String name,
		MacroValue.Type type, String defaultValue) {
		if (function == null || index < 0 || index >= function.parameters().size()) return;
		MacroFunction.Parameter old = function.parameters().get(index);
		function.parameters().set(index, new MacroFunction.Parameter(name == null ? old.name() : name,
			type == null ? old.type() : type, defaultValue == null ? old.defaultValue() : defaultValue));
		dirty();
	}

	private void removeFunctionParameter(MacroFunction function, int index) {
		if (function != null && index >= 0 && index < function.parameters().size()) {
			function.parameters().remove(index);
			dirty();
		}
	}

	private void selectScript(MacroScript script) {
		if (script == null) return;
		selectedScript = script;
		selectedStep = null;
		selectedOwner = null;
		inspectorScroll = 0;
	}

	private void selectFunction(MacroFunction function) {
		if (function == null) return;
		selectedFunction = function;
		selectedStep = null;
		selectedOwner = null;
		inspectorScroll = 0;
	}

	private void selectStep(MacroStep step, List<MacroStep> owner, MacroScript script, MacroFunction function) {
		selectedStep = step;
		selectedOwner = owner;
		if (function != null) {
			tab = Tab.FUNCTIONS;
			selectedFunction = function;
		} else {
			tab = Tab.CODE;
			if (script != null) selectedScript = script;
		}
		inspectorScroll = 0;
	}

	private void clearSelection() {
		selectedStep = null;
		selectedOwner = null;
		inspectorScroll = 0;
	}

	private void focusField(FieldBinding binding) {
		if (binding == null) return;
		focusedField = binding.id;
		focusedSetter = binding.setter;
		fieldText = safe(binding.getter.get());
		fieldCursor = fieldText.length();
		selectAll = false;
	}

	private void applyFocusedField() {
		if (focusedSetter != null) focusedSetter.accept(fieldText);
		if (!"palette-search".equals(focusedField)) dirty();
	}

	private void commitFocusedField() {
		if (focusedField == null) return;
		applyFocusedField();
		focusedField = null;
		focusedSetter = null;
		fieldCursor = 0;
		selectAll = false;
	}

	private void cancelFocusedField() {
		focusedField = null;
		focusedSetter = null;
		fieldCursor = 0;
		selectAll = false;
	}

	private void dirty() {
		workspaceRevision++;
		canvasTargetCacheValid = false;
		measuredStepHeights.clear();
		measuredListLayouts.clear();
		visibleScriptsRevision = -1;
		ModConfig.markDirty();
	}

	private void viewChanged() {
		workspaceRevision++;
		canvasTargetCacheValid = false;
		visibleScriptsRevision = -1;
		ModConfig.markDirty();
	}

	private void flash(String text) {
		notice = text == null ? "" : text;
		noticeUntil = System.currentTimeMillis() + 2_400;
	}

	private List<PaletteRow> paletteRows(String search) {
		String query = safe(search).strip().toLowerCase(Locale.ROOT);
		List<PaletteRow> rows = new ArrayList<>();
		if (query.isEmpty()) {
			for (PaletteBlock block : PALETTE_BLOCKS) {
				if (block.category == category) rows.add(new PaletteRow(category, block, false));
			}
			return rows;
		}
		for (Category group : Category.values()) {
			List<PaletteBlock> matches = new ArrayList<>();
			for (PaletteBlock block : PALETTE_BLOCKS) {
				if (block.category != group) continue;
				if (block.label.toLowerCase(Locale.ROOT).contains(query)
					|| block.hint.toLowerCase(Locale.ROOT).contains(query)
					|| group.label.toLowerCase(Locale.ROOT).contains(query)) matches.add(block);
			}
			if (matches.isEmpty()) continue;
			rows.add(new PaletteRow(group, null, true));
			for (PaletteBlock block : matches) rows.add(new PaletteRow(group, block, false));
		}
		return rows;
	}

	private static List<PaletteBlock> createPaletteBlocks() {
		return List.of(
			new PaletteBlock("Run command", "Send a slash command to chat", Category.ACTIONS,
				() -> new MacroStep.Command("/")),
			new PaletteBlock("Send chat", "Send a normal chat message", Category.ACTIONS,
				() -> new MacroStep.Chat("Hello!")),
			new PaletteBlock("Wait", "Pause for a fixed or random duration", Category.CONTROL,
				() -> new MacroStep.Wait(250, 250)),
			new PaletteBlock("If / else", "Branch into then and else stacks", Category.CONTROL,
				() -> new MacroStep.IfElse(new MacroCondition.Always(true))),
			new PaletteBlock("Repeat", "Repeat a nested stack a set number of times", Category.CONTROL,
				() -> new MacroStep.Repeat(false, 3)),
			new PaletteBlock("Repeat until", "Loop while a condition is false", Category.CONTROL,
				() -> new MacroStep.RepeatUntil(new MacroCondition.Always(false))),
			new PaletteBlock("Wait until", "Pause until a condition becomes true", Category.CONTROL,
				() -> new MacroStep.WaitUntil(new MacroCondition.Always(true))),
			new PaletteBlock("Call another macro", "Run that macro’s On Call stack", Category.CONTROL,
				() -> new MacroStep.MacroCall(-1)),
			new PaletteBlock("Press or hold key", "Capture a keyboard key in the inspector", Category.INPUT,
				() -> new MacroStep.Key("", false, 250)),
			new PaletteBlock("Mouse button", "Click or hold left, right, or middle", Category.INPUT,
				() -> new MacroStep.MouseButton(MacroStep.MouseButton.Button.LEFT, false, 250)),
			new PaletteBlock("Block player input", "Block controls for a duration", Category.INPUT,
				() -> new MacroStep.BlockPlayerInput(500)),
			new PaletteBlock("Start input block", "Block controls until stopped", Category.INPUT,
				MacroStep.StartBlockPlayerInput::new),
			new PaletteBlock("Stop input block", "Release a started input block", Category.INPUT,
				MacroStep.StopBlockPlayerInput::new),
			new PaletteBlock("Select hotbar slot", "Select slot 1–9 outside inventories", Category.INPUT,
				() -> new MacroStep.SelectHotbarSlot(1)),
			new PaletteBlock("Click inventory slot", "Click by slot id and mouse button", Category.INVENTORY,
				() -> new MacroStep.ClickSlot(0, 0, false)),
			new PaletteBlock("Click item by name", "Find and click a matching container or player item", Category.INVENTORY,
				() -> new MacroStep.ClickItem("", false, "container", 0, 0, false)),
			new PaletteBlock("Show title", "Display text over the game view", Category.DISPLAY,
				MacroStep.Title::new),
			new PaletteBlock("Play sound", "Play a sound by registry id", Category.DISPLAY,
				() -> new MacroStep.Sound("minecraft:entity.player.levelup")),
			new PaletteBlock("Close screen", "Close the currently open menu", Category.DISPLAY,
				MacroStep.CloseScreen::new),
			new PaletteBlock("Set variable", "Set a per-run local value", Category.VARIABLES,
				() -> new MacroStep.SetVariable("value", MacroValue.Type.TEXT,
					MacroValue.literal(MacroValue.Type.TEXT, ""))),
			new PaletteBlock("Change number", "Add an amount to a numeric variable", Category.VARIABLES,
				() -> new MacroStep.ChangeVariable("value", 1)),
			new PaletteBlock("Call a function", "Run a reusable My Block", Category.FUNCTIONS,
				() -> new MacroStep.FunctionCall("")),
			new PaletteBlock("Switch world", "Travel and wait for the destination to load", Category.WORLD,
				() -> new MacroStep.WorldSwitch(Island.HUB))
		);
	}

	private int categoryColor(Category category) {
		return VisualModule.INSTANCE.themeMacroColors().value() ? CATEGORY_SELECTED : category.color;
	}

	private int macroAccentText(int fallback) {
		return VisualModule.INSTANCE.themeMacroColors().value() ? TEXT_ON_ACCENT : fallback;
	}

	private String blockLabel(MacroStep step) {
		if (step instanceof MacroStep.Command command) return "run command  " + command.command();
		if (step instanceof MacroStep.Chat chat) return "send chat  " + chat.message();
		if (step instanceof MacroStep.Key key) return (key.hold() ? "hold key  " : "press key  ") + (key.key().isBlank() ? "[capture]" : key.key());
		if (step instanceof MacroStep.Wait wait) return "wait  " + wait.minMillis() + "–" + wait.maxMillis() + " ms";
		if (step instanceof MacroStep.CloseScreen) return "close current screen";
		if (step instanceof MacroStep.SelectHotbarSlot slot) return "select hotbar slot  " + slot.slot();
		if (step instanceof MacroStep.MouseButton mouse) return (mouse.hold() ? "hold " : "click ") + mouse.button().name().toLowerCase(Locale.ROOT) + " mouse";
		if (step instanceof MacroStep.BlockPlayerInput block) return "block player input  " + block.durationMillis() + " ms";
		if (step instanceof MacroStep.StartBlockPlayerInput) return "start blocking player input";
		if (step instanceof MacroStep.StopBlockPlayerInput) return "stop blocking player input";
		if (step instanceof MacroStep.SetVariable set) return "set  " + set.name() + "  to  " + set.value().value();
		if (step instanceof MacroStep.ChangeVariable change) return "change  " + change.name() + "  by  " + change.amount();
		if (step instanceof MacroStep.FunctionCall call) {
			MacroFunction function = MacrosModule.INSTANCE.function(call.functionId());
			return "call function  " + (function == null ? "[choose block]" : function.name());
		}
		if (step instanceof MacroStep.MacroCall call) {
			MacroDefinition target = MacrosModule.INSTANCE.macro(call.macroId());
			return "call macro  " + (target == null ? "[choose macro]" : target.name());
		}
		if (step instanceof MacroStep.IfElse branch) return "if  " + conditionLabel(branch.condition()) + "  then";
		if (step instanceof MacroStep.Repeat repeat) return repeat.forever() ? "repeat forever" : "repeat  " + repeat.count() + "  times";
		if (step instanceof MacroStep.RepeatUntil repeat) return "repeat until  " + conditionLabel(repeat.condition());
		if (step instanceof MacroStep.WaitUntil wait) return "wait until  " + conditionLabel(wait.condition());
		if (step instanceof MacroStep.ClickSlot slot) return "click slot  " + slot.slotId();
		if (step instanceof MacroStep.ClickItem item) return "click item  " + (item.name().isBlank() ? "[name]" : item.name());
		if (step instanceof MacroStep.Title title) return "show title  " + title.text();
		if (step instanceof MacroStep.Sound sound) return "play sound  " + sound.soundId();
		if (step instanceof MacroStep.WorldSwitch world) return "switch world  " + world.target().label();
		return step.type();
	}

	private void drawInlineBlockPreview(GuiGraphicsExtractor graphics, Rect bounds, int headH,
		MacroStep step, int color) {
		BlockPreview preview = inlinePreview(step);
		if (preview == null) {
			graphics.text(font, trim(blockLabel(step), bounds.w - 17), bounds.x + 9, bounds.y + 8, color);
			return;
		}
		int available = bounds.w - 18;
		int prefixWidth = font.width(preview.prefix);
		int suffixWidth = font.width(preview.suffix);
		int chipWidth = Math.min(font.width(preview.value) + 12,
			available - prefixWidth - suffixWidth - 8);
		if (chipWidth < 22) {
			graphics.text(font, trim(blockLabel(step), bounds.w - 17), bounds.x + 9, bounds.y + 8, color);
			return;
		}
		int x = bounds.x + 9;
		int textY = bounds.y + 8;
		graphics.text(font, preview.prefix, x, textY, color);
		x += prefixWidth + 4;
		int chipY = bounds.y + (headH - 17) / 2;
		boolean themed = VisualModule.INSTANCE.themeMacroColors().value();
		int chipSurface = themed ? BUTTON_BG : withOpacity(0xFF17232C, 0.42f);
		roundedRectBordered(graphics, x, chipY, chipWidth, 17, 6,
			chipSurface, chipSurface, themed ? BORDER : withOpacity(0xFFFFFFFF, 0.24f));
		graphics.centeredText(font, trim(preview.value, chipWidth - 8), x + chipWidth / 2,
			chipY + 4, themed ? TEXT_PRIMARY : 0xFFFFFFFF);
		x += chipWidth + 4;
		if (!preview.suffix.isBlank()) graphics.text(font, preview.suffix, x, textY, color);
	}

	private BlockPreview inlinePreview(MacroStep step) {
		if (step instanceof MacroStep.Command command) return new BlockPreview("run command", command.command(), "");
		if (step instanceof MacroStep.Chat chat) return new BlockPreview("send chat", chat.message(), "");
		if (step instanceof MacroStep.Key key) return new BlockPreview(key.hold() ? "hold key" : "press key",
			key.key().isBlank() ? "capture" : key.key(), "");
		if (step instanceof MacroStep.Wait wait) return new BlockPreview("wait",
			wait.minMillis() + "–" + wait.maxMillis() + " ms", "");
		if (step instanceof MacroStep.SelectHotbarSlot slot) return new BlockPreview("select hotbar slot",
			Integer.toString(slot.slot()), "");
		if (step instanceof MacroStep.MouseButton mouse) return new BlockPreview(mouse.hold() ? "hold" : "click",
			mouse.button().name().toLowerCase(Locale.ROOT), "mouse");
		if (step instanceof MacroStep.BlockPlayerInput block) return new BlockPreview("block player input",
			block.durationMillis() + " ms", "");
		if (step instanceof MacroStep.SetVariable set) return new BlockPreview("set " + set.name() + " to",
			(set.value().variableReference() ? "var " : "") + set.value().value(), "");
		if (step instanceof MacroStep.ChangeVariable change) return new BlockPreview("change " + change.name() + " by",
			Double.toString(change.amount()), "");
		if (step instanceof MacroStep.FunctionCall call) {
			MacroFunction function = MacrosModule.INSTANCE.function(call.functionId());
			return new BlockPreview("call function", function == null ? "choose" : function.name(), "");
		}
		if (step instanceof MacroStep.MacroCall call) {
			MacroDefinition target = MacrosModule.INSTANCE.macro(call.macroId());
			return new BlockPreview("call macro", target == null ? "choose" : target.name(), "");
		}
		if (step instanceof MacroStep.IfElse branch) return new BlockPreview("if",
			conditionLabel(branch.condition()), "then");
		if (step instanceof MacroStep.Repeat repeat) return new BlockPreview("repeat",
			repeat.forever() ? "forever" : Integer.toString(repeat.count()), repeat.forever() ? "" : "times");
		if (step instanceof MacroStep.RepeatUntil repeat) return new BlockPreview("repeat until",
			conditionLabel(repeat.condition()), "");
		if (step instanceof MacroStep.WaitUntil wait) return new BlockPreview("wait until",
			conditionLabel(wait.condition()), "");
		if (step instanceof MacroStep.ClickSlot slot) return new BlockPreview("click slot", Integer.toString(slot.slotId()), "");
		if (step instanceof MacroStep.ClickItem item) return new BlockPreview("click item", item.name(), "");
		if (step instanceof MacroStep.Title title) return new BlockPreview("show title", title.text(), "");
		if (step instanceof MacroStep.Sound sound) return new BlockPreview("play sound", sound.soundId(), "");
		return null;
	}

	private int blockColor(MacroStep step) {
		if (step instanceof MacroStep.IfElse || step instanceof MacroStep.Repeat
			|| step instanceof MacroStep.RepeatUntil || step instanceof MacroStep.WaitUntil
			|| step instanceof MacroStep.Wait || step instanceof MacroStep.MacroCall) return categoryColor(Category.CONTROL);
		if (step instanceof MacroStep.SetVariable || step instanceof MacroStep.ChangeVariable) return categoryColor(Category.VARIABLES);
		if (step instanceof MacroStep.FunctionCall) return categoryColor(Category.FUNCTIONS);
		if (step instanceof MacroStep.WorldSwitch) return categoryColor(Category.WORLD);
		if (step instanceof MacroStep.ClickSlot || step instanceof MacroStep.ClickItem) return categoryColor(Category.INVENTORY);
		if (step instanceof MacroStep.Title || step instanceof MacroStep.Sound || step instanceof MacroStep.CloseScreen) {
			return categoryColor(Category.DISPLAY);
		}
		if (step instanceof MacroStep.Key || step instanceof MacroStep.MouseButton || step instanceof MacroStep.SelectHotbarSlot
			|| step instanceof MacroStep.BlockPlayerInput || step instanceof MacroStep.StartBlockPlayerInput
			|| step instanceof MacroStep.StopBlockPlayerInput) return categoryColor(Category.INPUT);
		return categoryColor(Category.ACTIONS);
	}

	private String regionHatLabel(MacroWorldRegion region) {
		if (region == null || !region.placed()) return "when inside  [area not placed]";
		return "when inside  " + region.shape().name().toLowerCase(Locale.ROOT)
			+ (region.oncePerWorld() ? "  · once" : "  · repeat " + region.repeatDelayMillis() + "ms");
	}

	private String triggerName(MacroScript.Trigger trigger) {
		return switch (trigger) {
			case KEY_PRESS -> "Key pressed";
			case WORLD_REGION -> "World area";
			case ON_CALL -> "On Call";
		};
	}

	private int stateColor(MacroRunner.RunState state) {
		return switch (state) {
			case RUNNING -> 0xFF37D997;
			case WAITING -> 0xFFFFC55C;
			case FINISHED -> 0xFF8CB7FF;
			case IDLE -> 0xFF394653;
		};
	}

	private String stateLabel(MacroRunner.RunState state) {
		return switch (state) {
			case RUNNING -> "RUN";
			case WAITING -> "WAIT";
			case FINISHED -> "DONE";
			case IDLE -> "IDLE";
		};
	}

	private String conditionLabel(MacroCondition condition) {
		if (condition instanceof MacroCondition.Always always) return always.expected() ? "always" : "never";
		if (condition instanceof MacroCondition.World world) return world.mustBeInWorld() ? "in world" : "not in world";
		if (condition instanceof MacroCondition.Screen) return "screen title";
		if (condition instanceof MacroCondition.Slot slot) return "slot " + slot.slotId();
		if (condition instanceof MacroCondition.Item item) return "item " + (item.name().isBlank() ? "exists" : item.name());
		if (condition instanceof MacroCondition.Chat chat) return "chat " + (chat.text().isBlank() ? "matches" : chat.text());
		if (condition instanceof MacroCondition.Variable variable) return "variable " + variable.operator().name().toLowerCase(Locale.ROOT);
		if (condition instanceof MacroCondition.All all) return "all (" + all.children().size() + ")";
		if (condition instanceof MacroCondition.Any any) return "any (" + any.children().size() + ")";
		if (condition instanceof MacroCondition.Not) return "not";
		return condition == null ? "always" : condition.getClass().getSimpleName().toLowerCase(Locale.ROOT);
	}

	private MacroCondition nextCondition(MacroCondition condition) {
		if (condition instanceof MacroCondition.Always always) {
			return always.expected() ? new MacroCondition.Always(false) : new MacroCondition.World(true);
		}
		if (condition instanceof MacroCondition.World world && world.mustBeInWorld()) return new MacroCondition.World(false);
		return new MacroCondition.Always(true);
	}

	private static MacroWorldRegion.Shape next(MacroWorldRegion.Shape value) {
		MacroWorldRegion.Shape[] values = MacroWorldRegion.Shape.values();
		return values[(value.ordinal() + 1) % values.length];
	}

	private static MacroStep.MouseButton.Button next(MacroStep.MouseButton.Button value) {
		MacroStep.MouseButton.Button[] values = MacroStep.MouseButton.Button.values();
		return values[(value.ordinal() + 1) % values.length];
	}

	private static MacroValue.Type next(MacroValue.Type value) {
		MacroValue.Type[] values = MacroValue.Type.values();
		return values[(value.ordinal() + 1) % values.length];
	}

	private String trim(String value, int width) {
		if (value == null) return "";
		if (font.width(value) <= Math.max(0, width)) return value;
		String dots = "…";
		int limit = Math.max(0, width - font.width(dots));
		int end = Math.min(value.length(), font.plainSubstrByWidth(value, limit).length());
		return end <= 0 ? dots : value.substring(0, end) + dots;
	}

	private static String title(String value) {
		String lower = value.toLowerCase(Locale.ROOT);
		return lower.isEmpty() ? lower : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
	}

	private static String stripHash(String value) {
		return value != null && value.startsWith("#") ? value.substring(1) : value;
	}

	private static String safe(String value) {
		return value == null ? "" : value;
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}

	private record Layout(Rect panel, Rect palette, Rect canvas, Rect inspector,
		boolean compact, int footerY, Rect compactTabs, Rect compactContent) {
		private Layout(Rect panel, Rect palette, Rect canvas, Rect inspector, boolean compact,
			int footerY, Rect compactTabs) {
			this(panel, palette, canvas, inspector, compact, footerY, compactTabs, canvas);
		}
	}

	private record Rect(int x, int y, int w, int h) {
		private boolean contains(int px, int py) { return px >= x && py >= y && px < x + w && py < y + h; }
		private Rect intersection(Rect other) {
			if (other == null) return this;
			int left = Math.max(x, other.x), top = Math.max(y, other.y);
			int right = Math.min(x + w, other.x + other.w), bottom = Math.min(y + h, other.y + other.h);
			return right <= left || bottom <= top ? null : new Rect(left, top, right - left, bottom - top);
		}
	}

	private enum Tab { CODE, FUNCTIONS }
	private enum CompactPane { PALETTE, CANVAS, INSPECTOR }
	private enum Category {
		ACTIONS("Actions", "Act", COLOR_MOTION),
		CONTROL("Flow", "Flow", COLOR_CONTROL),
		INPUT("Inputs", "Keys", COLOR_INPUT),
		INVENTORY("Inventory", "Inv", COLOR_INPUT),
		DISPLAY("Feedback", "UI", COLOR_REGION),
		VARIABLES("Variables", "Vars", COLOR_VARIABLE),
		FUNCTIONS("Functions", "Fn", COLOR_FUNCTION),
		WORLD("World", "Wld", COLOR_REGION);
		private final String label;
		private final String shortLabel;
		private final int color;
		Category(String label, String shortLabel, int color) { this.label = label; this.shortLabel = shortLabel; this.color = color; }
	}

	private enum DragKind { PAN, SCRIPT, FUNCTION, STEP, PALETTE }

	private record PaletteBlock(String label, String hint, Category category, Supplier<MacroStep> factory) { }
	private record PaletteRow(Category category, PaletteBlock block, boolean header) { }
	private record BlockPreview(String prefix, String value, String suffix) { }
	private record PickerOption(String label, Runnable action) { }
	private record CanvasScriptPlacement(MacroScript script, int x, int y, int width, float zoom) { }
	private record ListLayout(int[] starts, int[] heights, int totalHeight) {
		private int firstIntersecting(int y) {
			int low = 0;
			int high = heights.length;
			while (low < high) {
				int middle = (low + high) >>> 1;
				if (starts[middle] + heights[middle] < y) low = middle + 1;
				else high = middle;
			}
			return low;
		}
	}

	private static final class PickerState {
		private final String title;
		private final List<PickerOption> options;
		private final boolean searchable;
		private String search = "";
		private String cachedSearch;
		private List<PickerOption> cachedVisibleOptions;
		private int scroll;
		private int maxScroll;
		private int visibleRows = 1;
		private int selected;

		private PickerState(String title, List<PickerOption> options, boolean searchable) {
			this.title = title == null ? "Choose an option" : title;
			this.options = List.copyOf(options);
			this.searchable = searchable;
		}

		private void setSearch(String value) {
			search = value == null ? "" : value;
			selected = 0;
			scroll = 0;
			cachedSearch = null;
			cachedVisibleOptions = null;
		}

		private List<PickerOption> visibleOptions() {
			if (!searchable || search.isBlank()) return options;
			if (!search.equals(cachedSearch)) {
				String query = search.toLowerCase(Locale.ROOT);
				cachedVisibleOptions = options.stream()
					.filter(option -> option.label.toLowerCase(Locale.ROOT).contains(query))
					.toList();
				cachedSearch = search;
			}
			return cachedVisibleOptions;
		}
	}
	private record FieldBinding(String id, Supplier<String> getter, Consumer<String> setter) { }
	private record HitTarget(Rect bounds, Runnable action, MacroStep step, List<MacroStep> owner,
		PaletteBlock paletteBlock, MacroScript script, MacroFunction function, FieldBinding field) { }
	private record DropSlot(Rect bounds, List<MacroStep> owner, int index, boolean functionScope,
		MacroScript script, MacroFunction function, int depth) { }

	private static final class DragState {
		private final DragKind kind;
		private final int startX;
		private final int startY;
		private final float startPanX;
		private final float startPanY;
		private final float startCanvasX;
		private final float startCanvasY;
		private final MacroScript script;
		private final MacroFunction function;
		private final MacroStep step;
		private final List<MacroStep> owner;
		private final PaletteBlock palette;
		private boolean moved;

		private DragState(DragKind kind, int startX, int startY, float startPanX, float startPanY,
			float startCanvasX, float startCanvasY, MacroScript script, MacroFunction function,
			MacroStep step, List<MacroStep> owner, PaletteBlock palette) {
			this.kind = kind;
			this.startX = startX;
			this.startY = startY;
			this.startPanX = startPanX;
			this.startPanY = startPanY;
			this.startCanvasX = startCanvasX;
			this.startCanvasY = startCanvasY;
			this.script = script;
			this.function = function;
			this.step = step;
			this.owner = owner;
			this.palette = palette;
		}

		private static DragState pan(int x, int y, float panX, float panY) {
			return new DragState(DragKind.PAN, x, y, panX, panY, 0, 0, null, null, null, null, null);
		}
		private static DragState palette(PaletteBlock block, int x, int y) {
			return new DragState(DragKind.PALETTE, x, y, 0, 0, 0, 0, null, null, null, null, block);
		}
		private static DragState script(MacroScript script, int x, int y) {
			return new DragState(DragKind.SCRIPT, x, y, 0, 0, script.canvasX(), script.canvasY(), script, null, null, null, null);
		}
		private static DragState function(MacroFunction function, int x, int y) {
			return new DragState(DragKind.FUNCTION, x, y, 0, 0, function.canvasX(), function.canvasY(), null, function, null, null, null);
		}
		private static DragState step(MacroStep step, List<MacroStep> owner, MacroScript script,
			MacroFunction function, int x, int y) {
			return new DragState(DragKind.STEP, x, y, 0, 0, 0, 0, script, function, step, owner, null);
		}
	}
}
