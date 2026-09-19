package geiler.addons.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import geiler.addons.client.config.ModConfig;
import geiler.addons.client.location.Island;
import geiler.addons.client.macro.MacroCondition;
import geiler.addons.client.macro.MacroDefinition;
import geiler.addons.client.macro.MacroStep;
import geiler.addons.client.module.ModuleKeybindManager;
import geiler.addons.client.module.impl.VisualModule;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static geiler.addons.client.gui.GuiTheme.*;

/** A categorized, nested workflow editor for one macro. */
public final class MacroEditorScreen extends Screen {
	private static final int PANEL_MARGIN = 10;
	private static final int HEADER_HEIGHT = 48;
	private static final int FOOTER_HEIGHT = 30;
	private static final int TREE_ROW_HEIGHT = 23;
	private static final int PALETTE_ROW_HEIGHT = 34;
	private static final int CONTROL_HEIGHT = 18;
	private static final int COMPACT_WIDTH = 900;
	private static final int MAX_CONDITION_DEPTH = 6;
	private static final int MAX_COMPOUND_TERMS = 12;

	private final Screen parent;
	private final MacroDefinition macro;
	private Pane compactPane = Pane.TREE;
	private NodeCategory category = NodeCategory.ACTIONS;
	private MacroStep selectedStep;
	private List<MacroStep> selectedOwner;
	private List<MacroStep> insertionList;
	private List<MacroStep> selectedBranch;
	private int insertionIndex;
	private int paletteScroll;
	private int treeScroll;
	private int propertiesScroll;
	private int popupScroll;
	private int helpScroll;
	private int helpMaxScroll;
	private boolean showHelp;
	private Rect helpCloseBounds;
	private Rect helpPanelBounds;
	private String focusedField;
	private String fieldText = "";
	private boolean fieldSelectAll;
	private Consumer<String> focusedSetter;
	private Layout lastLayout;
	private PopupState popup;
	private final List<HitTarget> hitTargets = new ArrayList<>();

	public MacroEditorScreen(Screen parent, MacroDefinition macro) {
		super(Component.literal("Macro Workflow"));
		this.parent = parent;
		this.macro = macro;
		this.insertionList = macro.steps();
		this.insertionIndex = macro.steps().size();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		VisualModule.INSTANCE.refreshTheme();
		currentMouseX = lastMouseX = mouseX;
		currentMouseY = lastMouseY = mouseY;
		hitTargets.clear();
		graphics.fill(0, 0, width, height, DIALOG_SHADE);

		Layout layout = layout();
		lastLayout = layout;
		roundedRectBordered(graphics, layout.panel.x, layout.panel.y, layout.panel.w, layout.panel.h,
			RADIUS, PANEL_TOP, PANEL_BOTTOM, BORDER);
		renderHeader(graphics, layout, mouseX, mouseY);

		if (layout.compact) renderCompactTabs(graphics, layout, mouseX, mouseY);
		if (layout.palette != null) renderPalette(graphics, layout.palette, mouseX, mouseY);
		if (layout.tree != null) renderTree(graphics, layout.tree, mouseX, mouseY);
		if (layout.properties != null) renderProperties(graphics, layout.properties, mouseX, mouseY);
		renderFooter(graphics, layout, mouseX, mouseY);
		if (popup != null) renderPopup(graphics, layout, mouseX, mouseY);
		if (showHelp) renderHelp(graphics, layout, mouseX, mouseY);
	}

	private Layout layout() {
		int panelX = PANEL_MARGIN;
		int panelY = PANEL_MARGIN;
		int panelW = Math.max(220, width - PANEL_MARGIN * 2);
		int panelH = Math.max(160, height - PANEL_MARGIN * 2);
		Rect panel = new Rect(panelX, panelY, panelW, panelH);
		boolean compact = panelW < COMPACT_WIDTH;
		int headerHeight = panelW < 520 ? 64 : HEADER_HEIGHT;
		int footerY = panelY + panelH - FOOTER_HEIGHT;
		int contentY = panelY + headerHeight;
		if (compact) contentY += 25;
		int contentBottom = Math.max(contentY + 20, footerY - 4);
		int contentH = contentBottom - contentY;
		if (compact) {
			Rect full = new Rect(panelX + 8, contentY, panelW - 16, contentH);
			return new Layout(panel, null, null, null, full, compact,
				new Rect(panelX + 8, panelY + headerHeight, panelW - 16, 20), footerY);
		}

		int innerX = panelX + 8;
		int innerW = panelW - 16;
		int gap = 6;
		int paletteW = 196;
		int treeW = Math.max(310, Math.min(390, Math.round(innerW * 0.39f)));
		int propertiesW = innerW - paletteW - treeW - gap * 2;
		if (propertiesW < 255) {
			treeW = Math.max(290, treeW - (255 - propertiesW));
			propertiesW = innerW - paletteW - treeW - gap * 2;
		}
		Rect palette = new Rect(innerX, contentY, paletteW, contentH);
		Rect tree = new Rect(palette.x + palette.w + gap, contentY, treeW, contentH);
		Rect properties = new Rect(tree.x + tree.w + gap, contentY, propertiesW, contentH);
		return new Layout(panel, palette, tree, properties, null, false, null, footerY);
	}

	private void renderHeader(GuiGraphicsExtractor graphics, Layout layout, int mouseX, int mouseY) {
		Font font = this.font;
		Rect panel = layout.panel;
		graphics.text(font, "Macro Workflow", panel.x + 12, panel.y + 8, TEXT_PRIMARY);
		boolean binding = ModuleKeybindManager.bindingMacro() == macro;
		if (panel.w < 520) {
			graphics.text(font, trimToWidth(font, macro.name(), panel.w - 24), panel.x + 12, panel.y + 20, TEXT_MUTED);
			int x = panel.x + 10;
			Rect hotkey = new Rect(x, panel.y + 33, 76, CONTROL_HEIGHT);
			Rect help = new Rect(hotkey.x + 80, hotkey.y, 46, CONTROL_HEIGHT);
			Rect done = new Rect(help.x + 50, hotkey.y, 58, CONTROL_HEIGHT);
			button(graphics, binding ? "Listening…" : "Set hotkey", hotkey, mouseX, mouseY, true,
				this::toggleMacroBinding);
			button(graphics, "Help", help, mouseX, mouseY, true, this::openHelp);
			button(graphics, "Done", done, mouseX, mouseY, true, this::closeToParent);
			graphics.text(font, trimToWidth(font, binding ? "Press and release a key · Escape clears · click again cancels."
				: "Select a node to edit its labeled settings.", panel.w - 24), panel.x + 12, panel.y + 53,
				binding ? TEXT_WARN : TEXT_MUTED);
			return;
		}
		String subtitle = macro.name() + "  •  Hotkey: " + macro.keybind().displayName();
		graphics.text(font, trimToWidth(font, subtitle, Math.max(90, panel.w - 250)),
			panel.x + 12, panel.y + 22, TEXT_MUTED);
		int right = panel.x + panel.w - 10;
		Rect done = new Rect(right - 74, panel.y + 7, 64, CONTROL_HEIGHT);
		Rect help = new Rect(done.x - 54, panel.y + 7, 46, CONTROL_HEIGHT);
		Rect hotkey = new Rect(help.x - 92, panel.y + 7, 84, CONTROL_HEIGHT);
		button(graphics, binding ? "Listening…" : "Set hotkey", hotkey, mouseX, mouseY, true,
			() -> toggleMacroBinding());
		button(graphics, "Help", help, mouseX, mouseY, true, this::openHelp);
		button(graphics, "Done", done, mouseX, mouseY, true, this::closeToParent);
		String hint = binding
			? "Listening: press and release a key · Escape clears · click Set hotkey to cancel."
			: "Select a node in the tree, then edit its labeled settings in Properties.";
		graphics.text(font, trimToWidth(font, hint, panel.w - 24), panel.x + 12, panel.y + 35,
			binding ? TEXT_WARN : TEXT_MUTED);
	}

	private void renderCompactTabs(GuiGraphicsExtractor graphics, Layout layout, int mouseX, int mouseY) {
		Rect tabs = layout.tabs;
		int gap = 4;
		int buttonW = (tabs.w - gap * 2) / 3;
		Pane[] panes = Pane.values();
		for (int i = 0; i < panes.length; i++) {
			Pane pane = panes[i];
			Rect bounds = new Rect(tabs.x + i * (buttonW + gap), tabs.y, buttonW, tabs.h);
			button(graphics, pane.label, bounds, mouseX, mouseY, true,
				() -> compactPane = pane, compactPane == pane);
		}
	}

	private void renderPalette(GuiGraphicsExtractor graphics, Rect pane, int mouseX, int mouseY) {
		renderPane(graphics, pane, "Node Palette", "Choose a node to add it to the selected position.");
		Rect categoryButton = new Rect(pane.x + 8, pane.y + 38, pane.w - 16, 19);
		button(graphics, "Category: " + category.label + "  ▾", categoryButton, mouseX, mouseY,
			true, () -> openPopup(categoryButton, categoryOptions()), false, BUTTON_BG, BUTTON_HOVER, pane);
		int listY = pane.y + 64;
		int listBottom = pane.y + pane.h - 5;
		Rect viewport = new Rect(pane.x + 6, listY, pane.w - 12, Math.max(8, listBottom - listY));
		List<NodeType> nodes = nodesIn(category);
		int contentHeight = nodes.size() * PALETTE_ROW_HEIGHT;
		paletteScroll = clamp(paletteScroll, 0, Math.max(0, contentHeight - viewport.h));
		graphics.enableScissor(viewport.x, viewport.y, viewport.x + viewport.w, viewport.y + viewport.h);
		for (int index = 0; index < nodes.size(); index++) {
			NodeType node = nodes.get(index);
			int y = viewport.y + index * PALETTE_ROW_HEIGHT - paletteScroll;
			Rect bounds = new Rect(viewport.x, y + 1, viewport.w - 4, PALETTE_ROW_HEIGHT - 4);
			boolean hovered = bounds.contains(mouseX, mouseY);
			roundedRectBordered(graphics, bounds.x, bounds.y, bounds.w, bounds.h, RADIUS_SMALL,
				hovered ? CARD_BG_HOVER : CARD_BG, hovered ? CARD_BG_HOVER : CARD_BG, CARD_BORDER);
			graphics.text(font, node.label, bounds.x + 7, bounds.y + 5, TEXT_PRIMARY);
			graphics.text(font, trimToWidth(font, node.hint, bounds.w - 14), bounds.x + 7,
				bounds.y + 17, TEXT_MUTED);
			registerHit(bounds, () -> addNode(node), viewport, null);
		}
		graphics.disableScissor();
		renderScrollbar(graphics, viewport, contentHeight, paletteScroll);
	}

	private void renderTree(GuiGraphicsExtractor graphics, Rect pane, int mouseX, int mouseY) {
		renderPane(graphics, pane, "Workflow Tree", "Branches and loop bodies appear beneath their parent node.");
		Rect viewport = paneViewport(pane);
		List<TreeRow> rows = treeRows();
		int contentHeight = rows.size() * TREE_ROW_HEIGHT;
		treeScroll = clamp(treeScroll, 0, Math.max(0, contentHeight - viewport.h));
		graphics.enableScissor(viewport.x, viewport.y, viewport.x + viewport.w, viewport.y + viewport.h);
		for (int index = 0; index < rows.size(); index++) {
			TreeRow row = rows.get(index);
			int y = viewport.y + index * TREE_ROW_HEIGHT - treeScroll;
			Rect bounds = new Rect(viewport.x + 2, y + 1, viewport.w - 7, TREE_ROW_HEIGHT - 2);
			boolean selected = row.step == selectedStep
				&& (row.branch ? row.insertionTarget == selectedBranch : selectedBranch == null);
			boolean hovered = bounds.contains(mouseX, mouseY);
			if (selected || hovered) {
				int bg = selected ? CARD_BG_ENABLED : CARD_BG_HOVER;
				roundedRectBordered(graphics, bounds.x, bounds.y, bounds.w, bounds.h, RADIUS_SMALL,
					bg, bg, selected ? CARD_BORDER_ENABLED : CARD_BORDER);
			}
			int indent = Math.min(120, row.depth * 13);
			int textX = bounds.x + 6 + indent;
			String text = row.branch ? row.label : row.number + "  " + stepSummary(row.step);
			graphics.text(font, trimToWidth(font, text, bounds.x + bounds.w - textX - 5), textX,
				bounds.y + 6, row.branch ? TEXT_WARN : (selected ? TEXT_ON_ACCENT : TEXT_PRIMARY));
			registerHit(bounds, () -> selectTreeRow(row), viewport, null);
		}
		graphics.disableScissor();
		renderScrollbar(graphics, viewport, contentHeight, treeScroll);
		if (rows.isEmpty()) {
			graphics.centeredText(font, "Choose a node from the palette to begin.",
				pane.x + pane.w / 2, viewport.y + 12, TEXT_MUTED);
		}
	}

	private void renderProperties(GuiGraphicsExtractor graphics, Rect pane, int mouseX, int mouseY) {
		renderPane(graphics, pane, "Properties", selectedStep == null
			? "Select a node to see its settings." : trimToWidth(font, stepSummary(selectedStep), pane.w - 16));
		Rect viewport = paneViewport(pane);
		if (selectedStep == null) {
			graphics.text(font, "Node values use labeled controls. Long lists and conditions scroll here.",
				viewport.x + 6, viewport.y + 8, TEXT_SECONDARY);
			return;
		}

		graphics.enableScissor(viewport.x, viewport.y, viewport.x + viewport.w, viewport.y + viewport.h);
		int startY = viewport.y - propertiesScroll;
		int[] y = {startY};
		drawDescription(graphics, viewport, y, "Delay before this node. The minimum and maximum are milliseconds; the editor chooses a random value in this range.");
		drawNumberPair(graphics, viewport, y, "Minimum delay (ms)", "Maximum delay (ms)",
			"node-delay-min", () -> Integer.toString(selectedStep.delayMin()),
			value -> updateNodeDelay(true, value), "node-delay-max", () -> Integer.toString(selectedStep.delayMax()),
			value -> updateNodeDelay(false, value));
		drawDivider(graphics, viewport, y);

		MacroStep step = selectedStep;
		if (step instanceof MacroStep.Command command) {
			drawTextField(graphics, viewport, y, "Command", "command", command::command,
				command::setCommand, "Example: /warp garden");
		} else if (step instanceof MacroStep.Chat chat) {
			drawTextField(graphics, viewport, y, "Chat message", "chat", chat::message,
				chat::setMessage, "Message sent through normal chat input");
		} else if (step instanceof MacroStep.Wait wait) {
			drawDescription(graphics, viewport, y, "Wait pauses the workflow for this randomized duration, then continues to the next node.");
			drawNumberPair(graphics, viewport, y, "Minimum wait (ms)", "Maximum wait (ms)",
				"wait-min", () -> Integer.toString(wait.minMillis()),
				value -> updateWaitRange(wait, true, value), "wait-max", () -> Integer.toString(wait.maxMillis()),
				value -> updateWaitRange(wait, false, value));
		} else if (step instanceof MacroStep.Key key) {
			drawDescription(graphics, viewport, y, "Capture any keyboard key, including Shift, Control, Alt or Super. Tap and Hold are separate modes.");
			drawKeyCapture(graphics, viewport, y, key, mouseX, mouseY);
			drawActionButton(graphics, viewport, y, "Input mode: " + (key.hold() ? "Hold" : "Tap"),
				() -> { key.setHold(!key.hold()); dirty(); });
			if (key.hold()) {
				drawDescription(graphics, viewport, y, "Each run samples a hold duration between these limits. The node's pre-delay is configured above.");
				drawNumberPair(graphics, viewport, y, "Minimum hold (ms)", "Maximum hold (ms)",
					"key-hold-min", () -> Integer.toString(key.holdMinMillis()),
					value -> updateKeyHoldRange(key, true, value), "key-hold-max",
					() -> Integer.toString(key.holdMaxMillis()),
					value -> updateKeyHoldRange(key, false, value));
			}
		} else if (step instanceof MacroStep.ClickSlot click) {
			drawNumberField(graphics, viewport, y, "Container slot ID", "slot-id",
				() -> Integer.toString(click.slotId()), value -> parseInt(value, number -> click.setSlotId(Math.max(0, number))),
				"Shown by Dev → Slot IDs");
			drawActionButton(graphics, viewport, y, "Mouse button: " + mouseButtonName(click.button()),
				() -> { click.setButton((click.button() + 1) % 3); dirty(); });
			drawActionButton(graphics, viewport, y, "Shift-click: " + onOff(click.shift()),
				() -> { click.setShift(!click.shift()); dirty(); });
		} else if (step instanceof MacroStep.ClickItem click) {
			drawTextField(graphics, viewport, y, "Item names (comma-separated)", "item-name", click::name,
				click::setName, "Click the first item matching any name");
			drawActionButton(graphics, viewport, y, "Name match: " + (click.contains() ? "Partial" : "Exact"),
				() -> { click.setContains(!click.contains()); dirty(); });
			drawActionButton(graphics, viewport, y, "Search area: " + scopeName(click.scope()),
				() -> { click.setScope(nextScope(click.scope())); dirty(); });
			drawNumberField(graphics, viewport, y, "Matching item number (0 is first)", "item-occurrence",
				() -> Integer.toString(click.occurrence()), value -> parseInt(value, number -> click.setOccurrence(Math.max(0, number))),
				"Choose which match to click");
			drawActionButton(graphics, viewport, y, "Mouse button: " + mouseButtonName(click.button()),
				() -> { click.setButton((click.button() + 1) % 3); dirty(); });
			drawActionButton(graphics, viewport, y, "Shift-click: " + onOff(click.shift()),
				() -> { click.setShift(!click.shift()); dirty(); });
		} else if (step instanceof MacroStep.WorldSwitch worldSwitch) {
			drawDescription(graphics, viewport, y, "Wait for a level change and confirm that the destination is this island. Choose from the same named island list used by macro island settings.");
			drawDropdownButton(graphics, viewport, y, "Destination island", worldSwitch.target().label(),
				worldOptions());
		} else if (step instanceof MacroStep.WaitUntil waitUntil) {
			drawDescription(graphics, viewport, y, "Wait Until continues when the condition is true. If it stays false for five seconds, the workflow stops.");
			drawConditionEditor(graphics, viewport, y, "Wait Until condition", waitUntil.condition(), waitUntil::setCondition,
				"wait-until-condition", 0);
		} else if (step instanceof MacroStep.IfElse branch) {
			drawDescription(graphics, viewport, y, "If true, run Then. If false, run Else. Choose a branch here or click its branch header in the tree before adding nodes.");
			drawActionButton(graphics, viewport, y, "Add nodes to Then branch", () -> targetBranch(branch.thenSteps()));
			drawActionButton(graphics, viewport, y, "Add nodes to Else branch", () -> targetBranch(branch.elseSteps()));
			drawConditionEditor(graphics, viewport, y, "Branch condition", branch.condition(), branch::setCondition,
				"if-condition", 0);
		} else if (step instanceof MacroStep.Repeat repeat) {
			drawDescription(graphics, viewport, y, repeat.forever()
				? "Repeat Forever runs its body until the macro is cancelled or a blocked step times out."
				: "Repeat Count runs its body the selected number of times, then continues.");
			drawActionButton(graphics, viewport, y, "Repeat Forever: " + onOff(repeat.forever()),
				() -> { repeat.setForever(!repeat.forever()); dirty(); });
			if (!repeat.forever()) {
				drawNumberField(graphics, viewport, y, "Repeat count", "repeat-count",
					() -> Integer.toString(repeat.count()), value -> parseInt(value, repeat::setCount), "At least one iteration");
			}
			drawActionButton(graphics, viewport, y, "Add nodes to repeat body", () -> targetBranch(repeat.steps()));
		} else if (step instanceof MacroStep.RepeatUntil repeatUntil) {
			drawDescription(graphics, viewport, y, "Repeat Until checks its condition before each iteration. It runs the body while false and exits as soon as true. An empty body stops safely.");
			drawActionButton(graphics, viewport, y, "Add nodes to repeat body", () -> targetBranch(repeatUntil.steps()));
			drawConditionEditor(graphics, viewport, y, "Stop when this condition is true", repeatUntil.condition(),
				repeatUntil::setCondition, "repeat-until-condition", 0);
		} else if (step instanceof MacroStep.CloseScreen) {
			drawDescription(graphics, viewport, y, "Sends Escape through the active Minecraft screen. If no screen is open, this step simply continues.");
		}

		propertiesContentHeight = Math.max(0, y[0] - startY);
		propertiesScroll = clamp(propertiesScroll, 0, Math.max(0, propertiesContentHeight - viewport.h));
		graphics.disableScissor();
		renderScrollbar(graphics, viewport, propertiesContentHeight, propertiesScroll);
	}

	private int propertiesContentHeight;

	private void renderFooter(GuiGraphicsExtractor graphics, Layout layout, int mouseX, int mouseY) {
		Rect panel = layout.panel;
		int y = layout.footerY + 5;
		int left = panel.x + 10;
		boolean selected = selectedStep != null && selectedOwner != null && selectedOwner.contains(selectedStep);
		boolean narrow = panel.w < 400;
		int copyW = narrow ? 40 : 62;
		int moveW = narrow ? 27 : 48;
		int gap = 4;
		button(graphics, narrow ? "Copy" : "Duplicate", new Rect(left, y, copyW, 18), mouseX, mouseY, selected,
			this::duplicateSelected);
		button(graphics, narrow ? "↑" : "Move ↑", new Rect(left + copyW + gap, y, moveW, 18), mouseX, mouseY, selected,
			() -> moveSelected(-1));
		button(graphics, narrow ? "↓" : "Move ↓", new Rect(left + copyW + gap * 2 + moveW, y, moveW, 18), mouseX, mouseY, selected,
			() -> moveSelected(1));
		int actionsEnd = left + copyW + gap * 2 + moveW * 2;
		int deleteW = narrow ? 76 : 88;
		Rect delete = new Rect(panel.x + panel.w - deleteW - 10, y, deleteW, 18);
		button(graphics, "Delete Node", delete, mouseX, mouseY, selected,
			this::deleteSelected, false, selected ? DANGER_BG : BUTTON_BG,
			selected && delete.contains(mouseX, mouseY) ? DANGER_HOVER : DANGER_BG);
		int hintX = actionsEnd + 6;
		graphics.text(font, trimToWidth(font, insertionDescription(), Math.max(0, delete.x - hintX - 6)),
			hintX, y + 5, TEXT_MUTED);
	}

	private void renderPane(GuiGraphicsExtractor graphics, Rect pane, String title, String subtitle) {
		roundedRectBordered(graphics, pane.x, pane.y, pane.w, pane.h, RADIUS_SMALL,
			MODULE_PANEL_TOP, MODULE_PANEL_BOTTOM, BORDER);
		graphics.text(font, title, pane.x + 8, pane.y + 6, TEXT_PRIMARY);
		graphics.text(font, trimToWidth(font, subtitle, pane.w - 16), pane.x + 8, pane.y + 18, TEXT_MUTED);
	}

	private Rect paneViewport(Rect pane) {
		return new Rect(pane.x + 5, pane.y + 32, Math.max(20, pane.w - 10), Math.max(20, pane.h - 37));
	}

	private void drawDescription(GuiGraphicsExtractor graphics, Rect viewport, int[] y, String text) {
		int available = Math.max(20, viewport.w - 14);
		for (var line : font.split(Component.literal(text), available)) {
			graphics.text(font, line, viewport.x + 5, y[0], TEXT_SECONDARY);
			y[0] += 10;
		}
		y[0] += 3;
	}

	private void drawDivider(GuiGraphicsExtractor graphics, Rect viewport, int[] y) {
		graphics.fill(viewport.x + 5, y[0] + 2, viewport.x + viewport.w - 5, y[0] + 3, BORDER);
		y[0] += 9;
	}

	private void drawNumberPair(GuiGraphicsExtractor graphics, Rect viewport, int[] y,
		String firstLabel, String secondLabel, String firstId, java.util.function.Supplier<String> firstValue,
		Consumer<String> firstSetter, String secondId, java.util.function.Supplier<String> secondValue,
		Consumer<String> secondSetter) {
		int gap = 6;
		int colW = Math.max(40, (viewport.w - 16 - gap) / 2);
		int x1 = viewport.x + 5;
		int x2 = x1 + colW + gap;
		drawTextFieldAt(graphics, viewport, firstLabel, firstId, firstValue, firstSetter,
			"Milliseconds", x1, y[0], colW);
		drawTextFieldAt(graphics, viewport, secondLabel, secondId, secondValue, secondSetter,
			"Milliseconds", x2, y[0], colW);
		y[0] += 34;
	}

	private void drawNumberField(GuiGraphicsExtractor graphics, Rect viewport, int[] y, String label,
		String id, java.util.function.Supplier<String> value, Consumer<String> setter, String hint) {
		drawTextField(graphics, viewport, y, label, id, value, setter, hint);
	}

	private void drawTextField(GuiGraphicsExtractor graphics, Rect viewport, int[] y, String label,
		String id, java.util.function.Supplier<String> value, Consumer<String> setter, String hint) {
		drawTextFieldAt(graphics, viewport, label, id, value, setter, hint, viewport.x + 5, y[0], viewport.w - 10);
		y[0] += 34;
	}

	private void drawTextFieldAt(GuiGraphicsExtractor graphics, Rect viewport, String label,
		String id, java.util.function.Supplier<String> value, Consumer<String> setter, String hint,
		int x, int y, int w) {
		graphics.text(font, trimToWidth(font, label, Math.max(12, w)), x, y, TEXT_MUTED);
		Rect field = new Rect(x, y + 10, Math.max(24, w), 18);
		boolean focused = id.equals(focusedField);
		roundedRectBordered(graphics, field.x, field.y, field.w, field.h, RADIUS_SMALL,
			CARD_BG, CARD_BG, focused ? CARD_BORDER_ENABLED : CARD_BORDER);
		String current = focused ? fieldText : value.get();
		String shown = current == null || current.isEmpty() ? hint : current;
		int color = current == null || current.isEmpty() ? TEXT_MUTED : TEXT_PRIMARY;
		graphics.text(font, trimToWidth(font, shown, field.w - 12), field.x + 6, field.y + 5, color);
		FieldBinding binding = new FieldBinding(id, current == null ? "" : current, setter);
		registerHit(field, () -> { }, viewport, binding);
	}

	private void drawActionButton(GuiGraphicsExtractor graphics, Rect viewport, int[] y,
		String label, Runnable action) {
		Rect bounds = new Rect(viewport.x + 5, y[0], viewport.w - 10, 19);
		button(graphics, label, bounds, currentMouseX, currentMouseY, true, action, false, BUTTON_BG, BUTTON_HOVER, viewport);
		y[0] += 23;
	}

	private int currentMouseX;
	private int currentMouseY;

	private void drawDropdownButton(GuiGraphicsExtractor graphics, Rect viewport, int[] y,
		String label, String selected, List<PopupEntry> entries) {
		int x = viewport.x + 5;
		graphics.text(font, label, x, y[0], TEXT_MUTED);
		Rect bounds = new Rect(x, y[0] + 10, viewport.w - 10, 19);
		button(graphics, selected + "  ▾", bounds, currentMouseX, currentMouseY, true,
			() -> openPopup(bounds, entries), false, BUTTON_BG, BUTTON_HOVER, viewport);
		y[0] += 34;
	}

	private void drawKeyCapture(GuiGraphicsExtractor graphics, Rect viewport, int[] y, MacroStep.Key key,
		int mouseX, int mouseY) {
		boolean binding = ModuleKeybindManager.bindingKeyStep() == key;
		String label = binding ? "Listening…" : "Capture key: " + readableKey(key.key());
		Rect bounds = new Rect(viewport.x + 5, y[0], viewport.w - 10, 20);
		button(graphics, label, bounds, mouseX, mouseY, true, () -> {
			if (ModuleKeybindManager.bindingKeyStep() == key) ModuleKeybindManager.cancelBinding();
			else ModuleKeybindManager.beginKeyStepBinding(key);
		}, false, binding ? BUTTON_HOVER : BUTTON_BG, BUTTON_HOVER, viewport);
		y[0] += 23;
		graphics.text(font, "Press and release a key; modifiers work too. Escape clears it.", viewport.x + 5, y[0], TEXT_MUTED);
		y[0] += 12;
	}

	private void drawActionButtonAt(GuiGraphicsExtractor graphics, Rect viewport, int[] y,
		int x, int w, String label, boolean enabled, Runnable action) {
		Rect bounds = new Rect(x, y[0], Math.max(24, w), 19);
		button(graphics, label, bounds, currentMouseX, currentMouseY, enabled,
			action, false, BUTTON_BG, BUTTON_HOVER, viewport);
		y[0] += 23;
	}

	private void renderPopup(GuiGraphicsExtractor graphics, Layout layout, int mouseX, int mouseY) {
		PopupState state = popup;
		if (state == null) return;
		int maxTextWidth = 0;
		for (PopupEntry entry : state.entries) maxTextWidth = Math.max(maxTextWidth, font.width(entry.label));
		int w = Math.min(Math.max(130, maxTextWidth + 20), Math.max(130, layout.panel.w - 20));
		int rowH = 20;
		int h = Math.min(180, state.entries.size() * rowH + 8);
		int x = clamp(state.anchor.x, layout.panel.x + 6, layout.panel.x + layout.panel.w - w - 6);
		int y = state.anchor.y + state.anchor.h;
		if (y + h > layout.panel.y + layout.panel.h - 8) y = state.anchor.y - h;
		y = clamp(y, layout.panel.y + 6, layout.panel.y + layout.panel.h - h - 6);
		Rect popupRect = new Rect(x, y, w, h);
		state.bounds = popupRect;
		roundedRectBordered(graphics, x, y, w, h, RADIUS_SMALL, PANEL_TOP, PANEL_BOTTOM, TEXT_PRIMARY);
		Rect list = new Rect(x + 4, y + 4, w - 8, h - 8);
		int visibleRows = Math.max(1, list.h / rowH);
		int maxScroll = Math.max(0, state.entries.size() - visibleRows);
		popupScroll = clamp(popupScroll, 0, maxScroll);
		graphics.enableScissor(list.x, list.y, list.x + list.w, list.y + list.h);
		for (int i = 0; i < state.entries.size(); i++) {
			int rowY = list.y + (i - popupScroll) * rowH;
			Rect row = new Rect(list.x, rowY, list.w, rowH);
			if (rowY + rowH <= list.y || rowY >= list.y + list.h) continue;
			boolean hover = row.contains(mouseX, mouseY);
			if (hover) roundedRect(graphics, row.x, row.y, row.w, row.h, RADIUS_SMALL, BUTTON_HOVER);
			graphics.text(font, trimToWidth(font, state.entries.get(i).label, row.w - 12), row.x + 6,
				row.y + 5, hover ? TEXT_ON_ACCENT : TEXT_PRIMARY);
			PopupEntry entry = state.entries.get(i);
			registerHit(row, () -> { popup = null; entry.action.run(); }, list, null);
		}
		graphics.disableScissor();
		if (state.entries.size() > visibleRows) {
			graphics.text(font, "Scroll list", popupRect.x + popupRect.w - 54,
				popupRect.y + popupRect.h - 11, TEXT_MUTED);
		}
	}

	private void openHelp() {
		showHelp = true;
		helpScroll = 0;
	}

	private void renderHelp(GuiGraphicsExtractor graphics, Layout layout, int mouseX, int mouseY) {
		Rect panel = new Rect(layout.panel.x + 12, layout.panel.y + 12,
			Math.max(190, layout.panel.w - 24), Math.max(130, layout.panel.h - 24));
		helpPanelBounds = panel;
		roundedRectBordered(graphics, panel.x, panel.y, panel.w, panel.h, RADIUS,
			PANEL_TOP, PANEL_BOTTOM, TEXT_PRIMARY);
		graphics.text(font, "Workflow editor help", panel.x + 12, panel.y + 10, TEXT_PRIMARY);
		Rect close = new Rect(panel.x + panel.w - 60, panel.y + 6, 48, 18);
		helpCloseBounds = close;
		button(graphics, "Close", close, mouseX, mouseY, true, () -> showHelp = false);
		HelpRecipe[] recipes = {
			new HelpRecipe("Click every matching item, with a pause",
				"1. Add Repeat Until and choose Item → Missing as its stop condition.",
				"2. Enter names separated by commas, for example Confirm, Claim, Collect. Partial match checks each name.",
				"3. Add Click Item to the loop body and set its randomized node delay to pace clicks. The first match is clicked each pass."),
			new HelpRecipe("Choose between two actions",
				"1. Add If / Else and build its condition. Use AND, OR or NOT to combine checks.",
				"2. Select Then or Else in Properties, or click that branch in the Workflow tree, then add nodes there."),
			new HelpRecipe("Repeat a fixed number of times",
				"Choose Repeat Count and enter the number of iterations, or turn on Repeat Forever. A blocked step times out after five seconds."),
			new HelpRecipe("Wait for a screen or item",
				"Add Wait Until and choose the condition that must become true. It stops the workflow if the condition stays false for five seconds."),
			new HelpRecipe("Hold a key for a random duration",
				"Capture a key, choose Hold, then set minimum and maximum hold times. Shift, Control, Alt and Super can be captured as the key itself."),
			new HelpRecipe("Switch islands",
				"World Switch destinations are dropdown-only. Select an island using the same labels as the macro's island settings; island names cannot be typed.")
		};
		int contentTop = panel.y + 31;
		int contentBottom = panel.y + panel.h - 10;
		int contentWidth = Math.max(80, panel.w - 26);
		int contentHeight = 0;
		for (HelpRecipe recipe : recipes) {
			contentHeight += Math.max(1, font.split(Component.literal(recipe.title), contentWidth).size()) * 10 + 4;
			for (String step : recipe.steps) {
				contentHeight += Math.max(1, font.split(Component.literal(step), contentWidth).size()) * 10 + 2;
			}
		}
		helpMaxScroll = Math.max(0, contentHeight - (contentBottom - contentTop));
		helpScroll = clamp(helpScroll, 0, helpMaxScroll);
		int y = contentTop - helpScroll;
		graphics.enableScissor(panel.x + 8, contentTop, panel.x + panel.w - 8, contentBottom);
		for (HelpRecipe recipe : recipes) {
			for (var line : font.split(Component.literal(recipe.title), contentWidth)) {
				graphics.text(font, line, panel.x + 12, y, TEXT_PRIMARY);
				y += 10;
			}
			y += 4;
			for (String step : recipe.steps) {
				for (var line : font.split(Component.literal(step), contentWidth)) {
					graphics.text(font, line, panel.x + 16, y, TEXT_SECONDARY);
					y += 10;
				}
				y += 2;
			}
			y += 4;
		}
		graphics.disableScissor();
		renderScrollbar(graphics, new Rect(panel.x + panel.w - 8, contentTop, 4, contentBottom - contentTop),
			contentHeight, helpScroll);
	}

	private void renderScrollbar(GuiGraphicsExtractor graphics, Rect viewport, int contentHeight, int scroll) {
		if (contentHeight <= viewport.h || viewport.h <= 0) return;
		int trackX = viewport.x + viewport.w - 3;
		graphics.fill(trackX, viewport.y + 2, trackX + 2, viewport.y + viewport.h - 2, withOpacity(SCROLLBAR, 0.35f));
		int thumbHeight = Math.max(14, viewport.h * viewport.h / contentHeight);
		int travel = viewport.h - thumbHeight;
		int maxScroll = contentHeight - viewport.h;
		int thumbY = viewport.y + (maxScroll <= 0 ? 0 : travel * scroll / maxScroll);
		roundedRect(graphics, trackX - 1, thumbY, 4, thumbHeight, RADIUS_SMALL, SCROLLBAR);
	}

	private void button(GuiGraphicsExtractor graphics, String label, Rect bounds, int mouseX, int mouseY,
		boolean enabled, Runnable action) {
		button(graphics, label, bounds, mouseX, mouseY, enabled, action, false,
			BUTTON_BG, BUTTON_HOVER, null);
	}

	private void button(GuiGraphicsExtractor graphics, String label, Rect bounds, int mouseX, int mouseY,
		boolean enabled, Runnable action, boolean selected) {
		button(graphics, label, bounds, mouseX, mouseY, enabled, action, selected,
			selected ? CATEGORY_SELECTED : BUTTON_BG, BUTTON_HOVER, null);
	}

	private void button(GuiGraphicsExtractor graphics, String label, Rect bounds, int mouseX, int mouseY,
		boolean enabled, Runnable action, boolean selected, Rect clip) {
		button(graphics, label, bounds, mouseX, mouseY, enabled, action, selected,
			selected ? CATEGORY_SELECTED : BUTTON_BG, BUTTON_HOVER, clip);
	}

	private void button(GuiGraphicsExtractor graphics, String label, Rect bounds, int mouseX, int mouseY,
		boolean enabled, Runnable action, boolean selected, int background, int hoverBackground) {
		button(graphics, label, bounds, mouseX, mouseY, enabled, action, selected,
			background, hoverBackground, null);
	}

	private void button(GuiGraphicsExtractor graphics, String label, Rect bounds, int mouseX, int mouseY,
		boolean enabled, Runnable action, boolean selected, int background, int hoverBackground, Rect clip) {
		boolean hovered = enabled && bounds.contains(mouseX, mouseY);
		int color = !enabled ? withOpacity(background, 0.45f) : hovered ? hoverBackground : background;
		roundedRect(graphics, bounds.x, bounds.y, bounds.w, bounds.h, RADIUS_SMALL, color);
		String shown = trimToWidth(font, label, Math.max(10, bounds.w - 8));
		graphics.centeredText(font, shown, bounds.x + bounds.w / 2,
			bounds.y + Math.max(3, (bounds.h - 8) / 2), !enabled ? TEXT_MUTED : (hovered && hoverBackground == BUTTON_HOVER ? TEXT_ON_ACCENT : TEXT_PRIMARY));
		if (enabled && action != null) registerHit(bounds, action, clip, null);
	}

	private void registerHit(Rect bounds, Runnable action, Rect clip, FieldBinding field) {
		Rect hit = clip == null ? bounds : bounds.intersection(clip);
		if (hit == null) return;
		hitTargets.add(new HitTarget(hit, action, field));
	}

	private void openPopup(Rect anchor, List<PopupEntry> entries) {
		popup = new PopupState(anchor, entries);
		popupScroll = 0;
	}

	private List<PopupEntry> conditionOptions(Consumer<MacroCondition> setter) {
		List<PopupEntry> options = new ArrayList<>();
		for (ConditionType type : ConditionType.values()) {
			options.add(new PopupEntry(type.label, () -> { setter.accept(conditionTemplate(type)); dirty(); }));
		}
		return options;
	}

	private List<PopupEntry> categoryOptions() {
		List<PopupEntry> options = new ArrayList<>();
		for (NodeCategory value : NodeCategory.values()) {
			options.add(new PopupEntry(value.label, () -> { category = value; paletteScroll = 0; }));
		}
		return options;
	}

	private List<PopupEntry> worldOptions() {
		List<PopupEntry> options = new ArrayList<>();
		for (Island island : Island.values()) {
			if (island.selectable()) options.add(new PopupEntry(island.label(), () -> {
				if (selectedStep instanceof MacroStep.WorldSwitch worldSwitch) {
					worldSwitch.setTarget(island);
					dirty();
				}
			}));
		}
		return options;
	}

	private void selectTreeRow(TreeRow row) {
		commitFocusedField();
		selectedStep = row.step;
		selectedOwner = row.owner;
		selectedBranch = row.branch ? row.insertionTarget : null;
		if (row.branch) {
			insertionList = row.insertionTarget;
			insertionIndex = insertionList.size();
		} else {
			insertionList = row.owner;
			insertionIndex = Math.max(0, row.owner.indexOf(row.step) + 1);
		}
		propertiesScroll = 0;
	}

	private void targetBranch(List<MacroStep> branch) {
		if (branch == null) return;
		insertionList = branch;
		insertionIndex = branch.size();
		selectedBranch = branch;
		propertiesScroll = 0;
	}

	private void addNode(NodeType type) {
		commitFocusedField();
		List<MacroStep> target = insertionList == null ? macro.steps() : insertionList;
		int index = clamp(insertionIndex, 0, target.size());
		MacroStep step = newStep(type);
		target.add(index, step);
		selectedStep = step;
		selectedOwner = target;
		selectedBranch = null;
		insertionList = target;
		insertionIndex = index + 1;
		propertiesScroll = 0;
		treeScroll = Integer.MAX_VALUE;
		dirty();
	}

	private void duplicateSelected() {
		if (!hasSelection()) return;
		commitFocusedField();
		int index = selectedOwner.indexOf(selectedStep);
		if (index < 0) return;
		MacroStep copy = copy(selectedStep);
		selectedOwner.add(index + 1, copy);
		selectedStep = copy;
		selectedBranch = null;
		insertionList = selectedOwner;
		insertionIndex = index + 2;
		dirty();
	}

	private void deleteSelected() {
		if (!hasSelection()) return;
		commitFocusedField();
		int index = selectedOwner.indexOf(selectedStep);
		if (index < 0) return;
		selectedOwner.remove(index);
		if (selectedOwner.isEmpty()) {
			selectedStep = null;
			selectedOwner = null;
			selectedBranch = null;
			insertionList = macro.steps();
			insertionIndex = macro.steps().size();
		} else {
			int nextIndex = Math.min(index, selectedOwner.size() - 1);
			selectedStep = selectedOwner.get(nextIndex);
			selectedBranch = null;
			insertionList = selectedOwner;
			insertionIndex = nextIndex + 1;
		}
		propertiesScroll = 0;
		dirty();
	}

	private void moveSelected(int direction) {
		if (!hasSelection()) return;
		commitFocusedField();
		int from = selectedOwner.indexOf(selectedStep);
		int to = from + direction;
		if (from < 0 || to < 0 || to >= selectedOwner.size()) return;
		selectedOwner.remove(from);
		selectedOwner.add(to, selectedStep);
		insertionList = selectedOwner;
		insertionIndex = to + 1;
		treeScroll = Math.max(0, treeScroll + direction * TREE_ROW_HEIGHT);
		dirty();
	}

	private boolean hasSelection() {
		return selectedStep != null && selectedOwner != null && selectedOwner.contains(selectedStep);
	}

	private List<TreeRow> treeRows() {
		List<TreeRow> rows = new ArrayList<>();
		appendTreeRows(macro.steps(), 0, "", rows, 0);
		return rows;
	}

	private void appendTreeRows(List<MacroStep> steps, int depth, String prefix, List<TreeRow> rows, int nesting) {
		if (nesting > 8 || steps == null) return;
		for (int i = 0; i < steps.size(); i++) {
			MacroStep step = steps.get(i);
			String number = prefix.isEmpty() ? Integer.toString(i + 1) : prefix + "." + (i + 1);
			rows.add(new TreeRow(number, depth, step, steps, null, false, ""));
			if (step instanceof MacroStep.IfElse branch) {
				rows.add(new TreeRow("", depth + 1, step, steps, branch.thenSteps(), true, "Then — when condition is true"));
				appendTreeRows(branch.thenSteps(), depth + 2, number + ".T", rows, nesting + 1);
				rows.add(new TreeRow("", depth + 1, step, steps, branch.elseSteps(), true, "Else — when condition is false"));
				appendTreeRows(branch.elseSteps(), depth + 2, number + ".E", rows, nesting + 1);
			} else if (step instanceof MacroStep.Repeat repeat) {
				rows.add(new TreeRow("", depth + 1, step, steps, repeat.steps(), true,
					"Body — " + (repeat.forever() ? "repeat forever" : "repeat " + repeat.count() + " times")));
				appendTreeRows(repeat.steps(), depth + 2, number + ".R", rows, nesting + 1);
			} else if (step instanceof MacroStep.RepeatUntil repeatUntil) {
				rows.add(new TreeRow("", depth + 1, step, steps, repeatUntil.steps(), true,
					"Body — run while stop condition is false"));
				appendTreeRows(repeatUntil.steps(), depth + 2, number + ".U", rows, nesting + 1);
			}
		}
	}

	private String insertionDescription() {
		if (insertionList == null) return "Add after the selected node";
		if (selectedBranch != null) {
			if (selectedStep instanceof MacroStep.IfElse branch) {
				return insertionList == branch.thenSteps() ? "Adding to Then branch" : "Adding to Else branch";
			}
			if (selectedStep instanceof MacroStep.Repeat) return "Adding to Repeat body";
			if (selectedStep instanceof MacroStep.RepeatUntil) return "Adding to Repeat Until body";
		}
		return selectedStep == null ? "Adding to workflow end" : "Adding after selected node";
	}

	private void updateNodeDelay(boolean minimum, String value) {
		parseInt(value, number -> {
			if (minimum) selectedStep.setDelay(number, selectedStep.delayMax());
			else selectedStep.setDelay(selectedStep.delayMin(), number);
			dirty();
		});
	}

	private void updateWaitRange(MacroStep.Wait wait, boolean minimum, String value) {
		parseInt(value, number -> {
			if (minimum) wait.setRange(number, wait.maxMillis());
			else wait.setRange(wait.minMillis(), number);
			dirty();
		});
	}

	private void updateKeyHoldRange(MacroStep.Key key, boolean minimum, String value) {
		parseInt(value, number -> {
			if (minimum) key.setHoldRange(number, key.holdMaxMillis());
			else key.setHoldRange(key.holdMinMillis(), number);
			dirty();
		});
	}

	private void drawConditionEditor(GuiGraphicsExtractor graphics, Rect viewport, int[] y,
		String title, MacroCondition condition, Consumer<MacroCondition> setter, String path, int depth) {
		currentMouseX = lastMouseX;
		currentMouseY = lastMouseY;
		int indent = Math.min(54, depth * 8);
		int x = viewport.x + 5 + indent;
		int w = Math.max(40, viewport.w - 10 - indent);
		graphics.text(font, trimToWidth(font, title, w), x, y[0], TEXT_PRIMARY);
		y[0] += 11;
		MacroCondition current = condition == null ? new MacroCondition.Always(true) : condition;
		Rect typeButton = new Rect(x, y[0], w, 18);
		button(graphics, "Condition type: " + conditionName(current) + "  ▾", typeButton,
			currentMouseX, currentMouseY, true,
			() -> openPopup(typeButton, conditionOptions(setter)), false, BUTTON_BG, BUTTON_HOVER, viewport);
		y[0] += 21;

		if (depth >= MAX_CONDITION_DEPTH && (current instanceof MacroCondition.All || current instanceof MacroCondition.Any
			|| current instanceof MacroCondition.Not)) {
			drawDescription(graphics, viewport, y, "Maximum condition nesting reached. Select a basic condition type to simplify this branch.");
			return;
		}
		if (current instanceof MacroCondition.Always always) {
			drawConditionToggle(graphics, viewport, y, x, w,
				"Result: " + (always.expected() ? "Always true" : "Always false"),
				() -> setter.accept(new MacroCondition.Always(!always.expected())));
		} else if (current instanceof MacroCondition.Item item) {
			drawConditionToggle(graphics, viewport, y, x, w,
				"Item must be: " + (item.mustExist() ? "Present" : "Missing"),
				() -> setter.accept(new MacroCondition.Item(item.name(), !item.mustExist(), item.contains(), item.includePlayerInventory())));
			drawTextFieldAt(graphics, viewport, "Item names (comma-separated alternatives)", path + "-item-name", item::name,
				value -> { setter.accept(new MacroCondition.Item(value, item.mustExist(), item.contains(), item.includePlayerInventory())); dirty(); },
				"Example: Confirm, Claim, Collect", x, y[0], w);
			y[0] += 34;
			drawConditionToggle(graphics, viewport, y, x, w,
				"Match: " + (item.contains() ? "Partial name" : "Exact name"),
				() -> setter.accept(new MacroCondition.Item(item.name(), item.mustExist(), !item.contains(), item.includePlayerInventory())));
			drawConditionToggle(graphics, viewport, y, x, w,
				"Search player inventory too: " + onOff(item.includePlayerInventory()),
				() -> setter.accept(new MacroCondition.Item(item.name(), item.mustExist(), item.contains(), !item.includePlayerInventory())));
		} else if (current instanceof MacroCondition.Screen screen) {
			drawConditionToggle(graphics, viewport, y, x, w,
				"Screen must be: " + (screen.mustBeOpen() ? "Open" : "Closed"),
				() -> setter.accept(new MacroCondition.Screen(screen.title(), !screen.mustBeOpen(), screen.contains())));
			drawTextFieldAt(graphics, viewport, "Screen title (blank means any)", path + "-screen-title", screen::title,
				value -> { setter.accept(new MacroCondition.Screen(value, screen.mustBeOpen(), screen.contains())); dirty(); },
				"Example: Trades", x, y[0], w);
			y[0] += 34;
			drawConditionToggle(graphics, viewport, y, x, w,
				"Title match: " + (screen.contains() ? "Contains" : "Exact"),
				() -> setter.accept(new MacroCondition.Screen(screen.title(), screen.mustBeOpen(), !screen.contains())));
		} else if (current instanceof MacroCondition.Slot slot) {
			drawConditionToggle(graphics, viewport, y, x, w,
				"Slot must be: " + (slot.mustExist() ? "Present" : "Missing"),
				() -> setter.accept(new MacroCondition.Slot(slot.slotId(), !slot.mustExist())));
			drawTextFieldAt(graphics, viewport, "Container slot ID", path + "-slot-id",
				() -> Integer.toString(slot.slotId()), value -> parseInt(value, number -> {
					setter.accept(new MacroCondition.Slot(Math.max(0, number), slot.mustExist())); dirty();
				}), "Example: 13", x, y[0], w);
			y[0] += 34;
		} else if (current instanceof MacroCondition.Chat chat) {
			drawTextFieldAt(graphics, viewport, "New chat text", path + "-chat-text", chat::text,
				value -> { setter.accept(new MacroCondition.Chat(value, chat.contains())); dirty(); },
				"Received after this workflow starts", x, y[0], w);
			y[0] += 34;
			drawConditionToggle(graphics, viewport, y, x, w,
				"Text match: " + (chat.contains() ? "Contains" : "Exact"),
				() -> setter.accept(new MacroCondition.Chat(chat.text(), !chat.contains())));
		} else if (current instanceof MacroCondition.World world) {
			drawConditionToggle(graphics, viewport, y, x, w,
				"Player must be in a world: " + onOff(world.mustBeInWorld()),
				() -> setter.accept(new MacroCondition.World(!world.mustBeInWorld())));
		} else if (current instanceof MacroCondition.All all) {
			drawActionButtonAt(graphics, viewport, y, x, w, "Add AND requirement",
				all.children().size() < MAX_COMPOUND_TERMS,
				() -> { setter.accept(withConditionAdded(all, new MacroCondition.Always(true))); dirty(); });
			List<MacroCondition> children = all.children();
			for (int i = 0; i < children.size(); i++) {
				final int childIndex = i;
				drawConditionEditor(graphics, viewport, y, "AND requirement " + (i + 1), children.get(i),
					child -> { setter.accept(withConditionReplaced(all, childIndex, child)); dirty(); }, path + "-and-" + i, depth + 1);
				drawActionButtonAt(graphics, viewport, y, x + 6, Math.max(32, w - 6),
					"Remove AND requirement " + (i + 1), true,
					() -> { setter.accept(withConditionRemoved(all, childIndex)); dirty(); });
			}
		} else if (current instanceof MacroCondition.Any any) {
			drawActionButtonAt(graphics, viewport, y, x, w, "Add OR option",
				any.children().size() < MAX_COMPOUND_TERMS,
				() -> { setter.accept(withConditionAdded(any, new MacroCondition.Always(true))); dirty(); });
			List<MacroCondition> children = any.children();
			for (int i = 0; i < children.size(); i++) {
				final int childIndex = i;
				drawConditionEditor(graphics, viewport, y, "OR option " + (i + 1), children.get(i),
					child -> { setter.accept(withConditionReplaced(any, childIndex, child)); dirty(); }, path + "-or-" + i, depth + 1);
				drawActionButtonAt(graphics, viewport, y, x + 6, Math.max(32, w - 6),
					"Remove OR option " + (i + 1), true,
					() -> { setter.accept(withConditionRemoved(any, childIndex)); dirty(); });
			}
		} else if (current instanceof MacroCondition.Not not) {
			drawConditionEditor(graphics, viewport, y, "Inverted requirement (NOT)", not.child(),
				child -> { setter.accept(new MacroCondition.Not(child)); dirty(); }, path + "-not", depth + 1);
		}
	}

	private void drawConditionToggle(GuiGraphicsExtractor graphics, Rect viewport, int[] y,
		int x, int w, String label, Runnable action) {
		drawActionButtonAt(graphics, viewport, y, x, w, label, true, () -> { action.run(); dirty(); });
	}

	private List<NodeType> nodesIn(NodeCategory category) {
		List<NodeType> result = new ArrayList<>();
		for (NodeType type : NodeType.values()) if (type.category == category) result.add(type);
		return result;
	}

	private MacroStep newStep(NodeType type) {
		return switch (type) {
			case COMMAND -> new MacroStep.Command("");
			case CHAT -> new MacroStep.Chat("");
			case KEY -> new MacroStep.Key("", false, 250);
			case CLICK_SLOT -> new MacroStep.ClickSlot(0, 0, false);
			case CLICK_ITEM -> new MacroStep.ClickItem("", true, "container", 0, 0, false);
			case CLOSE_SCREEN -> new MacroStep.CloseScreen();
			case IF_ELSE -> new MacroStep.IfElse(new MacroCondition.Always(true));
			case REPEAT -> new MacroStep.Repeat(false, 2);
			case REPEAT_UNTIL -> new MacroStep.RepeatUntil(new MacroCondition.Item("", false, true, true));
			case WAIT -> new MacroStep.Wait(300, 600);
			case WAIT_UNTIL -> new MacroStep.WaitUntil(new MacroCondition.Always(true));
			case WORLD_SWITCH -> new MacroStep.WorldSwitch(Island.HUB);
		};
	}

	private static MacroCondition conditionTemplate(ConditionType type) {
		return switch (type) {
			case ALWAYS -> new MacroCondition.Always(true);
			case ITEM -> new MacroCondition.Item("", true, true, false);
			case SCREEN -> new MacroCondition.Screen("", true, true);
			case SLOT -> new MacroCondition.Slot(0, true);
			case CHAT -> new MacroCondition.Chat("", true);
			case WORLD -> new MacroCondition.World(true);
			case AND -> new MacroCondition.All(List.of(new MacroCondition.Always(true), new MacroCondition.Always(true)));
			case OR -> new MacroCondition.Any(List.of(new MacroCondition.Always(true), new MacroCondition.Always(false)));
			case NOT -> new MacroCondition.Not(new MacroCondition.Always(true));
		};
	}

	private static MacroCondition withConditionAdded(MacroCondition condition, MacroCondition child) {
		List<MacroCondition> children = new ArrayList<>();
		if (condition instanceof MacroCondition.All all) children.addAll(all.children());
		else if (condition instanceof MacroCondition.Any any) children.addAll(any.children());
		children.add(child);
		return condition instanceof MacroCondition.Any ? new MacroCondition.Any(children) : new MacroCondition.All(children);
	}

	private static MacroCondition withConditionReplaced(MacroCondition condition, int index, MacroCondition child) {
		List<MacroCondition> children = new ArrayList<>(condition instanceof MacroCondition.Any any ? any.children()
			: condition instanceof MacroCondition.All all ? all.children() : List.of());
		if (index < 0 || index >= children.size()) return condition;
		children.set(index, child);
		return condition instanceof MacroCondition.Any ? new MacroCondition.Any(children) : new MacroCondition.All(children);
	}

	private static MacroCondition withConditionRemoved(MacroCondition condition, int index) {
		List<MacroCondition> children = new ArrayList<>(condition instanceof MacroCondition.Any any ? any.children()
			: condition instanceof MacroCondition.All all ? all.children() : List.of());
		if (index >= 0 && index < children.size()) children.remove(index);
		return condition instanceof MacroCondition.Any ? new MacroCondition.Any(children) : new MacroCondition.All(children);
	}

	private static MacroStep copy(MacroStep step) {
		MacroStep result;
		if (step instanceof MacroStep.Command command) result = new MacroStep.Command(command.command());
		else if (step instanceof MacroStep.Chat chat) result = new MacroStep.Chat(chat.message());
		else if (step instanceof MacroStep.Wait wait) result = new MacroStep.Wait(wait.minMillis(), wait.maxMillis());
		else if (step instanceof MacroStep.Key key) result = new MacroStep.Key(key.key(), key.hold(),
			key.holdMinMillis(), key.holdMaxMillis());
		else if (step instanceof MacroStep.ClickSlot click) result = new MacroStep.ClickSlot(click.slotId(), click.button(), click.shift());
		else if (step instanceof MacroStep.ClickItem click) result = new MacroStep.ClickItem(click.name(), click.contains(), click.scope(), click.occurrence(), click.button(), click.shift());
		else if (step instanceof MacroStep.CloseScreen) result = new MacroStep.CloseScreen();
		else if (step instanceof MacroStep.WorldSwitch worldSwitch) result = new MacroStep.WorldSwitch(worldSwitch.target());
		else if (step instanceof MacroStep.WaitUntil wait) result = new MacroStep.WaitUntil(wait.condition());
		else if (step instanceof MacroStep.IfElse branch) {
			MacroStep.IfElse duplicate = new MacroStep.IfElse(branch.condition());
			for (MacroStep child : branch.thenSteps()) duplicate.thenSteps().add(copy(child));
			for (MacroStep child : branch.elseSteps()) duplicate.elseSteps().add(copy(child));
			result = duplicate;
		} else if (step instanceof MacroStep.Repeat repeat) {
			MacroStep.Repeat duplicate = new MacroStep.Repeat(repeat.forever(), repeat.count());
			for (MacroStep child : repeat.steps()) duplicate.steps().add(copy(child));
			result = duplicate;
		} else if (step instanceof MacroStep.RepeatUntil repeatUntil) {
			MacroStep.RepeatUntil duplicate = new MacroStep.RepeatUntil(repeatUntil.condition());
			for (MacroStep child : repeatUntil.steps()) duplicate.steps().add(copy(child));
			result = duplicate;
		} else result = new MacroStep.Wait(300, 600);
		result.setDelay(step.delayMin(), step.delayMax());
		return result;
	}

	private static String stepSummary(MacroStep step) {
		if (step == null) return "";
		if (step instanceof MacroStep.Command command) return "Command · " + emptyLabel(command.command(), "command text");
		if (step instanceof MacroStep.Chat chat) return "Send Chat · " + emptyLabel(chat.message(), "message");
		if (step instanceof MacroStep.Wait wait) return "Wait · " + wait.minMillis() + "–" + wait.maxMillis() + " ms";
		if (step instanceof MacroStep.Key key) return "Key · " + readableKey(key.key())
			+ (key.hold() ? " (hold " + key.holdMinMillis() + "–" + key.holdMaxMillis() + " ms)" : " (tap)");
		if (step instanceof MacroStep.ClickSlot click) return "Click Slot · " + click.slotId();
		if (step instanceof MacroStep.ClickItem click) return "Click Item · " + emptyLabel(click.name(), "item name")
			+ (click.contains() ? " (partial)" : " (exact)");
		if (step instanceof MacroStep.CloseScreen) return "Close Screen · Escape";
		if (step instanceof MacroStep.WorldSwitch worldSwitch) return "World Switch · " + worldSwitch.target().label();
		if (step instanceof MacroStep.WaitUntil waitUntil) return "Wait Until · " + conditionBrief(waitUntil.condition());
		if (step instanceof MacroStep.IfElse branch) return "If / Else · " + conditionBrief(branch.condition());
		if (step instanceof MacroStep.Repeat repeat) return repeat.forever()
			? "Repeat Forever" : "Repeat " + repeat.count() + " times";
		if (step instanceof MacroStep.RepeatUntil repeatUntil) return "Repeat Until · " + conditionBrief(repeatUntil.condition());
		return step.type();
	}

	private static String conditionBrief(MacroCondition condition) {
		if (condition instanceof MacroCondition.Item item) return "item " + (item.mustExist() ? "present" : "missing")
			+ " [" + emptyLabel(item.name(), "name") + "]" + (item.contains() ? " (partial)" : " (exact)");
		if (condition instanceof MacroCondition.Screen screen) return "screen " + (screen.mustBeOpen() ? "open" : "closed");
		if (condition instanceof MacroCondition.Slot slot) return "slot " + (slot.mustExist() ? "present" : "missing");
		if (condition instanceof MacroCondition.Chat) return "chat text";
		if (condition instanceof MacroCondition.World world) return world.mustBeInWorld() ? "in world" : "not in world";
		if (condition instanceof MacroCondition.All all) return "all " + all.children().size() + " requirements";
		if (condition instanceof MacroCondition.Any any) return "any of " + any.children().size() + " requirements";
		if (condition instanceof MacroCondition.Not) return "NOT condition";
		if (condition instanceof MacroCondition.Always always) return always.expected() ? "always true" : "always false";
		return "condition";
	}

	private static String conditionName(MacroCondition condition) {
		if (condition instanceof MacroCondition.Always) return "Always";
		if (condition instanceof MacroCondition.Item) return "Item";
		if (condition instanceof MacroCondition.Screen) return "Screen";
		if (condition instanceof MacroCondition.Slot) return "Slot";
		if (condition instanceof MacroCondition.Chat) return "Chat";
		if (condition instanceof MacroCondition.World) return "World";
		if (condition instanceof MacroCondition.All) return "AND (all true)";
		if (condition instanceof MacroCondition.Any) return "OR (any true)";
		if (condition instanceof MacroCondition.Not) return "NOT (invert)";
		return "Condition";
	}

	private static String readableKey(String keyName) {
		if (keyName == null || keyName.isBlank()) return "Not bound";
		try {
			InputConstants.Key key = InputConstants.getKey(keyName);
			if (key != null && !key.equals(InputConstants.UNKNOWN)) return key.getDisplayName().getString();
		} catch (RuntimeException ignored) {
		}
		return keyName;
	}

	private static String mouseButtonName(int button) {
		return switch (button) { case 1 -> "Right"; case 2 -> "Middle"; default -> "Left"; };
	}

	private static String scopeName(String scope) {
		return switch (scope == null ? "container" : scope.toLowerCase(Locale.ROOT)) {
			case "player" -> "Player inventory";
			case "all" -> "Container + player";
			default -> "Container only";
		};
	}

	private static String nextScope(String scope) {
		return switch (scope == null ? "container" : scope.toLowerCase(Locale.ROOT)) {
			case "container" -> "player";
			case "player" -> "all";
			default -> "container";
		};
	}

	private static String onOff(boolean value) {
		return value ? "On" : "Off";
	}

	private static String emptyLabel(String value, String empty) {
		return value == null || value.isBlank() ? "(set " + empty + ")" : value;
	}

	private static void parseInt(String value, Consumer<Integer> action) {
		try {
			action.accept(Integer.parseInt(value == null ? "" : value.trim()));
		} catch (NumberFormatException ignored) {
			// Preserve the last valid value while the user is editing an incomplete number.
		}
	}

	private static String trimToWidth(Font font, String text, int maxWidth) {
		if (text == null || maxWidth <= 0) return "";
		if (font.width(text) <= maxWidth) return text;
		String ellipsis = "…";
		int end = text.length();
		while (end > 0 && font.width(text.substring(0, end) + ellipsis) > maxWidth) end--;
		return end <= 0 ? ellipsis : text.substring(0, end) + ellipsis;
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private void toggleMacroBinding() {
		if (ModuleKeybindManager.bindingMacro() == macro) ModuleKeybindManager.cancelBinding();
		else ModuleKeybindManager.beginMacroBinding(macro);
	}

	private void focusField(FieldBinding binding) {
		focusedField = binding.id;
		fieldText = binding.value;
		focusedSetter = binding.setter;
		fieldSelectAll = true;
	}

	private void commitFocusedField() {
		if (focusedField == null || focusedSetter == null) return;
		focusedSetter.accept(fieldText);
		focusedField = null;
		focusedSetter = null;
		fieldSelectAll = false;
		dirty();
	}

	private void cancelFocusedField() {
		focusedField = null;
		focusedSetter = null;
		fieldSelectAll = false;
	}

	private void dirty() {
		ModConfig.markDirty();
	}

	private int lastMouseX;
	private int lastMouseY;

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != 0) return super.mouseClicked(event, doubleClick);
		int x = (int) event.x();
		int y = (int) event.y();
		lastMouseX = currentMouseX = x;
		lastMouseY = currentMouseY = y;
		if (showHelp) {
			if (helpCloseBounds != null && helpCloseBounds.contains(x, y)) showHelp = false;
			return true;
		}
		if (popup != null) {
			if (popup.bounds != null && popup.bounds.contains(x, y)) {
				for (int i = hitTargets.size() - 1; i >= 0; i--) {
					HitTarget target = hitTargets.get(i);
					if (target.bounds.contains(x, y)) { target.action.run(); return true; }
				}
				return true;
			}
			popup = null;
			return true;
		}
		for (int i = hitTargets.size() - 1; i >= 0; i--) {
			HitTarget target = hitTargets.get(i);
			if (!target.bounds.contains(x, y)) continue;
			if (target.field != null) {
				commitFocusedField();
				focusField(target.field);
			} else {
				commitFocusedField();
				target.action.run();
			}
			return true;
		}
		commitFocusedField();
		if (lastLayout != null && lastLayout.panel.contains(x, y)) return true;
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (showHelp) {
			if (helpPanelBounds != null && helpPanelBounds.contains((int) mouseX, (int) mouseY)) {
				helpScroll = clamp(helpScroll - (int) Math.signum(scrollY) * 28, 0, helpMaxScroll);
			}
			return true;
		}
		if (popup != null && popup.bounds != null && popup.bounds.contains((int) mouseX, (int) mouseY)) {
			int visibleRows = Math.max(1, (popup.bounds.h - 8) / 20);
			popupScroll = clamp(popupScroll - (int) Math.signum(scrollY), 0,
				Math.max(0, popup.entries.size() - visibleRows));
			return true;
		}
		if (lastLayout == null) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
		int delta = (int) Math.signum(scrollY) * 28;
		if (lastLayout.compact) {
			Rect pane = lastLayout.compactPane;
			if (!pane.contains((int) mouseX, (int) mouseY)) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
			switch (compactPane) {
				case PALETTE -> paletteScroll = Math.max(0, paletteScroll - delta);
				case TREE -> treeScroll = Math.max(0, treeScroll - delta);
				case PROPERTIES -> propertiesScroll = Math.max(0, propertiesScroll - delta);
			}
			return true;
		}
		if (lastLayout.palette.contains((int) mouseX, (int) mouseY)) paletteScroll = Math.max(0, paletteScroll - delta);
		else if (lastLayout.tree.contains((int) mouseX, (int) mouseY)) treeScroll = Math.max(0, treeScroll - delta);
		else if (lastLayout.properties.contains((int) mouseX, (int) mouseY)) propertiesScroll = Math.max(0, propertiesScroll - delta);
		else return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (showHelp || popup != null) return true;
		if (focusedField == null || !event.isAllowedChatCharacter()) return super.charTyped(event);
		if (fieldSelectAll) {
			fieldText = "";
			fieldSelectAll = false;
		}
		if (fieldText.length() < 256) {
			fieldText += event.codepointAsString();
			if (focusedSetter != null) focusedSetter.accept(fieldText);
			dirty();
		}
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (showHelp) {
			if (event.key() == InputConstants.KEY_ESCAPE) showHelp = false;
			return true;
		}
		if (popup != null) {
			if (event.key() == InputConstants.KEY_ESCAPE) popup = null;
			return true;
		}
		if (focusedField != null) {
			if (event.key() == GLFW.GLFW_KEY_A && (event.modifiers() & InputConstants.MOD_CONTROL) != 0) {
				fieldSelectAll = true;
				return true;
			}
			if (event.key() == InputConstants.KEY_BACKSPACE) {
				if (fieldSelectAll) { fieldText = ""; fieldSelectAll = false; }
				else if (!fieldText.isEmpty()) fieldText = fieldText.substring(0, fieldText.length() - 1);
				if (focusedSetter != null) focusedSetter.accept(fieldText);
				dirty();
				return true;
			}
			if (event.key() == InputConstants.KEY_RETURN) { commitFocusedField(); return true; }
			if (event.key() == InputConstants.KEY_ESCAPE) { cancelFocusedField(); return true; }
		}
		if (event.key() == InputConstants.KEY_ESCAPE) {
			if (ModuleKeybindManager.bindingKeyStep() != null || ModuleKeybindManager.bindingMacro() == macro) {
				ModuleKeybindManager.cancelBinding();
				return true;
			}
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		commitFocusedField();
		if (ModuleKeybindManager.bindingMacro() == macro || ModuleKeybindManager.bindingKeyStep() != null) {
			ModuleKeybindManager.cancelBinding();
		}
		closeToParent();
	}

	private void closeToParent() {
		commitFocusedField();
		if (ModuleKeybindManager.bindingMacro() == macro || ModuleKeybindManager.bindingKeyStep() != null) {
			ModuleKeybindManager.cancelBinding();
		}
		dirty();
		if (minecraft != null) minecraft.setScreen(parent);
	}

	@Override
	public void removed() {
		if (ModuleKeybindManager.bindingMacro() == macro || ModuleKeybindManager.bindingKeyStep() != null) {
			ModuleKeybindManager.cancelBinding();
		}
		super.removed();
	}

	private enum Pane {
		PALETTE("Nodes"), TREE("Workflow"), PROPERTIES("Properties");
		private final String label;
		Pane(String label) { this.label = label; }
	}

	private enum NodeCategory {
		ACTIONS("Actions"), FLOW("Flow"), TIMING("Timing & Conditions"), WORLD("World");
		private final String label;
		NodeCategory(String label) { this.label = label; }
	}

	private enum NodeType {
		COMMAND(NodeCategory.ACTIONS, "Command", "Run a client command"),
		CHAT(NodeCategory.ACTIONS, "Send Chat", "Send a chat message"),
		KEY(NodeCategory.ACTIONS, "Press Key", "Capture a readable key"),
		CLICK_SLOT(NodeCategory.ACTIONS, "Click Slot", "Click a container slot ID"),
		CLICK_ITEM(NodeCategory.ACTIONS, "Click Item", "Find by exact or partial name"),
		CLOSE_SCREEN(NodeCategory.ACTIONS, "Close Screen", "Send Escape to the screen"),
		IF_ELSE(NodeCategory.FLOW, "If / Else", "Choose Then or Else branch"),
		REPEAT(NodeCategory.FLOW, "Repeat", "Repeat a body N times or forever"),
		REPEAT_UNTIL(NodeCategory.FLOW, "Repeat Until", "Run body until condition is true"),
		WAIT(NodeCategory.TIMING, "Wait", "Wait a randomized duration"),
		WAIT_UNTIL(NodeCategory.TIMING, "Wait Until", "Wait for a condition"),
		WORLD_SWITCH(NodeCategory.WORLD, "World Switch", "Wait for a selected island");

		private final NodeCategory category;
		private final String label;
		private final String hint;
		NodeType(NodeCategory category, String label, String hint) {
			this.category = category;
			this.label = label;
			this.hint = hint;
		}
	}

	private enum ConditionType {
		ALWAYS("Always"), ITEM("Item"), SCREEN("Screen"), SLOT("Slot"), CHAT("Chat"), WORLD("World"),
		AND("AND — all requirements"), OR("OR — any requirement"), NOT("NOT — invert condition");
		private final String label;
		ConditionType(String label) { this.label = label; }
	}

	private record Layout(Rect panel, Rect palette, Rect tree, Rect properties, Rect compactPane,
		boolean compact, Rect tabs, int footerY) {
	}

	private record TreeRow(String number, int depth, MacroStep step, List<MacroStep> owner,
		List<MacroStep> insertionTarget, boolean branch, String label) {
	}

	private record FieldBinding(String id, String value, Consumer<String> setter) {
	}

	private record HitTarget(Rect bounds, Runnable action, FieldBinding field) {
	}

	private static final class HelpRecipe {
		private final String title;
		private final String[] steps;

		private HelpRecipe(String title, String... steps) {
			this.title = title;
			this.steps = steps;
		}
	}

	private record PopupEntry(String label, Runnable action) {
	}

	private static final class PopupState {
		private final Rect anchor;
		private final List<PopupEntry> entries;
		private Rect bounds;

		private PopupState(Rect anchor, List<PopupEntry> entries) {
			this.anchor = anchor;
			this.entries = entries;
		}
	}

	private record Rect(int x, int y, int w, int h) {
		private boolean contains(int mouseX, int mouseY) {
			return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		}

		private Rect intersection(Rect other) {
			int left = Math.max(x, other.x);
			int top = Math.max(y, other.y);
			int right = Math.min(x + w, other.x + other.w);
			int bottom = Math.min(y + h, other.y + other.h);
			return right <= left || bottom <= top ? null : new Rect(left, top, right - left, bottom - top);
		}
	}
}
