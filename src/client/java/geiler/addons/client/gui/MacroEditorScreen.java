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

import static geiler.addons.client.gui.GuiTheme.*;

/** A compact, drag-and-drop workflow editor for one macro. */
public final class MacroEditorScreen extends Screen {
	private static final int PANEL_MARGIN = 22;
	private static final int HEADER_HEIGHT = 30;
	private static final int ROW_HEIGHT = 27;
	private static final int BUTTON_HEIGHT = 18;
	private static final int MAX_VISIBLE_ROWS = 13;
	private static final int FIELD_HEIGHT = 18;
	private static final int EDIT_AREA_HEIGHT = 62;
	private static final int HANDLE_WIDTH = 34;

	private final Screen parent;
	private final MacroDefinition macro;
	private int selectedIndex = -1;
	private int draggingIndex = -1;
	private int scrollRows;
	private boolean editingField;
	private String fieldText = "";
	private boolean fieldSelectAll;
	private boolean editingDelay;
	private String delayText = "0-0";
	private boolean delaySelectAll;
	private boolean showHelp;

	public MacroEditorScreen(Screen parent, MacroDefinition macro) {
		super(Component.literal("Macro Workflow"));
		this.parent = parent;
		this.macro = macro;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		VisualModule.INSTANCE.refreshTheme();
		graphics.fill(0, 0, width, height, DIALOG_SHADE);
		int panelX = PANEL_MARGIN;
		int panelY = PANEL_MARGIN;
		int panelW = Math.max(240, width - PANEL_MARGIN * 2);
		int panelH = Math.max(180, height - PANEL_MARGIN * 2);
		roundedRectBordered(graphics, panelX, panelY, panelW, panelH, RADIUS,
			PANEL_TOP, PANEL_BOTTOM, BORDER);
		Font font = this.font;
		graphics.text(font, "Macro Workflow", panelX + 12, panelY + 8, TEXT_PRIMARY);
		graphics.text(font, macro.name() + "  •  " + macro.keybind().displayName(), panelX + 12,
			panelY + 18, TEXT_MUTED);
		boolean binding = ModuleKeybindManager.bindingMacro() == macro;
		button(graphics, binding ? "Listening…" : "Set hotkey",
			panelX + panelW - 92, panelY + 7, 80, BUTTON_HEIGHT, mouseX, mouseY);
		if (binding) {
			roundedRectBordered(graphics, panelX + 10, panelY + 31, panelW - 20, 17, RADIUS_SMALL,
				BUTTON_HOVER, BUTTON_HOVER, TEXT_PRIMARY);
			graphics.text(font, "LISTENING: press and release a key · Escape clears · click again cancels",
				panelX + 16, panelY + 36, TEXT_ON_ACCENT);
		} else {
			graphics.text(font, "Hotkey: click Set hotkey, then press and release a key combination.",
				panelX + 12, panelY + 36, TEXT_MUTED);
		}

		int listX = panelX + 10;
		int listY = panelY + HEADER_HEIGHT;
		int listW = panelW - 20;
		int controlsY = panelY + panelH - 134;
		List<MacroStep> steps = macro.steps();
		int visible = visibleRows(steps.size(), controlsY - listY - EDIT_AREA_HEIGHT);
		int first = Math.min(scrollRows, Math.max(0, steps.size() - visible));
		if (steps.isEmpty()) {
			graphics.centeredText(font, "No steps yet - add one below.", listX + listW / 2,
				listY + 12, TEXT_MUTED);
		}
		for (int row = 0; row < visible; row++) {
			int index = first + row;
			int y = listY + row * ROW_HEIGHT;
			MacroStep step = steps.get(index);
			boolean hovered = mouseX >= listX && mouseX < listX + listW
				&& mouseY >= y && mouseY < y + ROW_HEIGHT - 3;
			boolean selected = index == selectedIndex;
			int bg = selected ? CARD_BG_ENABLED : (hovered ? CARD_BG_HOVER : CARD_BG);
			roundedRectBordered(graphics, listX, y, listW, ROW_HEIGHT - 4, RADIUS_SMALL,
				bg, bg, selected ? CARD_BORDER_ENABLED : CARD_BORDER);
			int summaryWidth = listW - HANDLE_WIDTH - 54;
			graphics.text(font, (index + 1) + ". " + trim(summary(step), summaryWidth), listX + 8, y + 4,
				selected ? TEXT_ON_ACCENT : TEXT_PRIMARY);
			graphics.text(font, "delay " + delayValue(step) + " ms", listX + 8, y + 14,
				selected ? TEXT_ON_ACCENT : TEXT_MUTED);
			graphics.text(font, "⋮⋮", listX + listW - HANDLE_WIDTH - 2, y + 5,
				selected ? TEXT_ON_ACCENT : TEXT_MUTED);
			button(graphics, "↑", listX + listW - 44, y + 4, 14, 15, mouseX, mouseY);
			button(graphics, "↓", listX + listW - 27, y + 4, 14, 15, mouseX, mouseY);
		}
		if (steps.size() > visible) {
			graphics.text(font, "Scroll to reach all top-level steps (" + (first + 1) + "–"
				+ Math.min(steps.size(), first + visible) + ").", listX, listY + visible * ROW_HEIGHT + 2,
				TEXT_WARN);
		}

		if (selectedIndex >= 0 && selectedIndex < steps.size()) renderEditors(graphics, font,
			listX, listW, controlsY, steps.get(selectedIndex));

		int y = controlsY;
		graphics.text(font, "Add node:", listX, y - 10, TEXT_MUTED);
		button(graphics, "Cmd", listX, y, 32, BUTTON_HEIGHT, mouseX, mouseY);
		button(graphics, "Chat", listX + 35, y, 36, BUTTON_HEIGHT, mouseX, mouseY);
		button(graphics, "Wait", listX + 74, y, 36, BUTTON_HEIGHT, mouseX, mouseY);
		button(graphics, "Key", listX + 113, y, 32, BUTTON_HEIGHT, mouseX, mouseY);
		button(graphics, "Slot", listX + 148, y, 36, BUTTON_HEIGHT, mouseX, mouseY);
		int second = y + BUTTON_HEIGHT + 3;
		button(graphics, "Item", listX, second, 32, BUTTON_HEIGHT, mouseX, mouseY);
		button(graphics, "Until", listX + 35, second, 34, BUTTON_HEIGHT, mouseX, mouseY);
		button(graphics, "Esc", listX + 72, second, 28, BUTTON_HEIGHT, mouseX, mouseY);
		button(graphics, "If", listX + 103, second, 22, BUTTON_HEIGHT, mouseX, mouseY);
		button(graphics, "Rep", listX + 128, second, 38, BUTTON_HEIGHT, mouseX, mouseY);
		button(graphics, "World", listX + 169, second, 46, BUTTON_HEIGHT, mouseX, mouseY);
		int third = second + BUTTON_HEIGHT + 3;
		button(graphics, "Del", listX, third, 28, BUTTON_HEIGHT, mouseX, mouseY);
		button(graphics, "Dup", listX + 31, third, 32, BUTTON_HEIGHT, mouseX, mouseY);
		boolean childEnabled = selectedIndex >= 0 && selectedIndex < steps.size()
			&& canAddChild(steps.get(selectedIndex));
		button(graphics, "Child", listX + 66, third, 36, BUTTON_HEIGHT, mouseX, mouseY, childEnabled);
		String selectedControl = selectedIndex >= 0 && selectedIndex < steps.size()
			&& hasCondition(steps.get(selectedIndex)) ? "Cond" : "Delay";
		button(graphics, selectedControl, listX + 105, third, 40, BUTTON_HEIGHT, mouseX, mouseY);
		button(graphics, "Done", listX + listW - 38, third, 38, BUTTON_HEIGHT, mouseX, mouseY);
		button(graphics, "Help", listX + listW - 38, third + BUTTON_HEIGHT + 3, 38, BUTTON_HEIGHT, mouseX, mouseY);
		graphics.text(font, "Select a node, then use the fields above to edit it.",
			listX + 142, third + 5, TEXT_MUTED);

		if (showHelp) renderHelp(graphics, font, mouseX, mouseY);
		else renderTooltip(graphics, mouseX, mouseY, panelX, panelY, panelW, panelH, listX, listY,
			listW, controlsY, steps, visible, first);
	}

	private void button(GuiGraphicsExtractor graphics, String label, int x, int y, int w, int h,
		int mouseX, int mouseY) {
		button(graphics, label, x, y, w, h, mouseX, mouseY, true);
	}

	private void button(GuiGraphicsExtractor graphics, String label, int x, int y, int w, int h,
		int mouseX, int mouseY, boolean enabled) {
		boolean hover = enabled && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		roundedRect(graphics, x, y, w, h, RADIUS_SMALL, enabled
			? (hover ? BUTTON_HOVER : BUTTON_BG) : withOpacity(BUTTON_BG, 0.45f));
		graphics.centeredText(font, label, x + w / 2, y + 5,
			enabled ? (hover ? TEXT_ON_ACCENT : TEXT_PRIMARY) : TEXT_MUTED);
	}

	private void renderEditors(GuiGraphicsExtractor graphics, Font font, int listX, int listW,
		int controlsY, MacroStep selected) {
		int delayY = controlsY - FIELD_HEIGHT - 5;
		graphics.text(font, "Delay before this node (min-max ms)", listX, delayY - 10, TEXT_MUTED);
		renderEditorField(graphics, font, listX, delayY, listW,
			editingDelay ? delayText : delayValue(selected), editingDelay,
			"Type 250-500 for a randomized delay; 0-0 means immediate.");

		if (!isEditable(selected)) return;
		int fieldY = delayY - FIELD_HEIGHT - 16;
		graphics.text(font, fieldLabel(selected), listX, fieldY - 10, TEXT_MUTED);
		renderEditorField(graphics, font, listX, fieldY, listW,
			editingField ? fieldText : textValue(selected), editingField, fieldPlaceholder(selected));
	}

	private void renderEditorField(GuiGraphicsExtractor graphics, Font font, int x, int y, int w,
		String value, boolean focused, String placeholder) {
		roundedRectBordered(graphics, x, y, w, FIELD_HEIGHT, RADIUS_SMALL,
			CARD_BG, CARD_BG, focused ? CARD_BORDER_ENABLED : CARD_BORDER);
		String shown = value == null || value.isEmpty() ? placeholder : value;
		int color = value == null || value.isEmpty() ? TEXT_MUTED : (focused ? TEXT_PRIMARY : TEXT_SECONDARY);
		graphics.text(font, trim(shown, w - 14), x + 6, y + 5, color);
	}

	private void renderTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY, int panelX,
		int panelY, int panelW, int panelH, int listX, int listY, int listW, int controlsY,
		List<MacroStep> steps, int visible, int first) {
		String tooltip = tooltipAt(mouseX, mouseY, panelX, panelY, panelW, panelH, listX, listY,
			listW, controlsY, steps, visible, first);
		if (tooltip != null) graphics.setTooltipForNextFrame(Component.literal(tooltip), mouseX, mouseY);
	}

	private String tooltipAt(int x, int y, int panelX, int panelY, int panelW, int panelH,
		int listX, int listY, int listW, int controlsY, List<MacroStep> steps, int visible, int first) {
		if (x >= panelX + panelW - 92 && x < panelX + panelW - 12 && y >= panelY + 5 && y < panelY + 27) {
			return ModuleKeybindManager.bindingMacro() == macro
				? "Listening: press and release a key; Escape clears the binding; click again cancels."
				: "Set the macro hotkey. The key is saved when you release it.";
		}
		for (int row = 0; row < visible; row++) {
			int index = first + row;
			int rowY = listY + row * ROW_HEIGHT;
			if (x < listX || x >= listX + listW || y < rowY || y >= rowY + ROW_HEIGHT - 3) continue;
			if (x >= listX + listW - 47 && x < listX + listW - 30) return "Move this node one place up.";
			if (x >= listX + listW - 30 && x < listX + listW - 12) return "Move this node one place down.";
			if (x >= listX + listW - 82 && x < listX + listW - 47) {
				return "Drag the dotted handle to reorder nodes. The arrows move one step at a time.";
			}
			return "Select this node to edit it below.";
		}
		int delayY = controlsY - FIELD_HEIGHT - 5;
		int fieldY = delayY - FIELD_HEIGHT - 16;
		if (x >= listX && x < listX + listW && y >= delayY && y < delayY + FIELD_HEIGHT
			&& selectedIndex >= 0 && selectedIndex < steps.size()) {
			return "Delay before this node in milliseconds. Enter one value or a min-max range.";
		}
		if (x >= listX && x < listX + listW && y >= fieldY && y < fieldY + FIELD_HEIGHT
			&& selectedIndex >= 0 && selectedIndex < steps.size() && isEditable(steps.get(selectedIndex))) {
			return fieldPlaceholder(steps.get(selectedIndex));
		}
		if (y >= controlsY && y < controlsY + BUTTON_HEIGHT) {
			return switch (firstTypeAt(x - listX)) {
				case 0 -> "Add a command step, then type the command directly in the highlighted field.";
				case 1 -> "Add a chat message step and type the message to send.";
				case 2 -> "Add an explicit randomized wait (min-max milliseconds).";
				case 3 -> "Add a key press or hold step; edit key | tap/hold | hold milliseconds.";
				case 4 -> "Add a click-by-slot-id step. Use the Dev > Slot IDs module to find ids.";
				default -> null;
			};
		}
		int second = controlsY + BUTTON_HEIGHT + 3;
		if (y >= second && y < second + BUTTON_HEIGHT) {
			return switch (secondTypeAt(x - listX)) {
				case 5 -> "Add an item-name click; edit name | exact/contains | scope | occurrence.";
				case 7 -> "Wait until a condition becomes true before continuing.";
				case 6 -> "Close the current Minecraft screen with Escape.";
				case 8 -> "Branch into then/else children using the Child button.";
				case 9 -> "Repeat child nodes a fixed number of times or forever.";
				case 10 -> "Wait for a selected island/world switch and verify the destination.";
				default -> null;
			};
		}
		int third = second + BUTTON_HEIGHT + 3;
		if (y >= third && y < third + BUTTON_HEIGHT) {
			if (x >= listX && x < listX + 28) return "Delete the selected node.";
			if (x >= listX + 31 && x < listX + 63) return "Duplicate the selected node, including its children and delay.";
			if (x >= listX + 66 && x < listX + 102) {
				return selectedIndex >= 0 && selectedIndex < steps.size() && canAddChild(steps.get(selectedIndex))
					? "Add a default Wait child to the selected If/Else or Repeat node."
					: "Select an If/Else or Repeat node before adding a child.";
			}
			if (x >= listX + 105 && x < listX + 145) {
				return selectedIndex >= 0 && selectedIndex < steps.size() && hasCondition(steps.get(selectedIndex))
					? "Cycle the condition type, then edit its value in the field above."
					: "Open the selected node's delay range for direct text entry.";
			}
			if (x >= listX + listW - 38) return "Save the workflow and return to the Macros settings.";
		}
		if (y >= third + BUTTON_HEIGHT + 3 && y < third + BUTTON_HEIGHT * 2 + 3
			&& x >= listX + listW - 38) return "Open examples and explanations for every workflow node and editor control.";
		return null;
	}

	private void renderHelp(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
		Rect panel = helpPanelRect();
		roundedRectBordered(graphics, panel.x, panel.y, panel.w, panel.h, RADIUS,
			PANEL_TOP, PANEL_BOTTOM, TEXT_PRIMARY);
		graphics.text(font, "Workflow help", panel.x + 12, panel.y + 10, TEXT_PRIMARY);
		int y = panel.y + 27;
		String[] paragraphs = {
			"A workflow runs from top to bottom. Click a node to select it, then edit its value in the highlighted field.",
			"Command example: Command `/warp garden` → Wait `500-800` → Key `key.keyboard.space | tap | 250`.",
			"Inventory example: Wait Until screen → Click item `Confirm | exact | container | 0` → Escape.",
			"Every node has a delay before it. Enter `250-500` for a randomized range; `0-0` runs immediately.",
			"If/Else and Repeat are containers. Select one and press Child to add a starter Wait inside it; Dup copies a complete node.",
			"Hotkeys are saved on key release. Escape clears a hotkey, and Help can be closed with Escape or Close."
		};
		for (String paragraph : paragraphs) {
			for (var line : font.split(Component.literal(paragraph), panel.w - 24)) {
				graphics.text(font, line, panel.x + 12, y, TEXT_SECONDARY);
				y += 10;
			}
			y += 4;
		}
		Rect close = helpCloseRect();
		button(graphics, "Close", close.x, close.y, close.w, close.h, mouseX, mouseY);
	}

	private Rect helpPanelRect() {
		int w = Math.min(520, Math.max(240, width - 40));
		int h = Math.min(320, Math.max(220, height - 40));
		return new Rect((width - w) / 2, (height - h) / 2, w, h);
	}

	private Rect helpCloseRect() {
		Rect panel = helpPanelRect();
		return new Rect(panel.x + panel.w - 56, panel.y + panel.h - 27, 44, BUTTON_HEIGHT);
	}

	private static String fieldLabel(MacroStep step) {
		if (step instanceof MacroStep.Command) return "Command text";
		if (step instanceof MacroStep.Chat) return "Chat message";
		if (step instanceof MacroStep.Wait) return "Wait duration (min-max ms)";
		if (step instanceof MacroStep.Key) return "Key | tap/hold | hold milliseconds";
		if (step instanceof MacroStep.ClickSlot) return "Slot id | mouse button | normal/shift";
		if (step instanceof MacroStep.ClickItem) return "Item name | exact/contains | scope | occurrence";
		if (step instanceof MacroStep.WorldSwitch) return "Destination island";
		if (step instanceof MacroStep.WaitUntil || step instanceof MacroStep.IfElse) return "Condition value";
		return "Node value";
	}

	private static String fieldPlaceholder(MacroStep step) {
		if (step instanceof MacroStep.Command) return "e.g. /warp garden";
		if (step instanceof MacroStep.Chat) return "e.g. hello team";
		if (step instanceof MacroStep.Wait) return "e.g. 500-800";
		if (step instanceof MacroStep.Key) return "e.g. key.keyboard.space | tap | 250";
		if (step instanceof MacroStep.ClickSlot) return "e.g. 13 | 0 | normal";
		if (step instanceof MacroStep.ClickItem) return "e.g. Confirm | exact | container | 0";
		if (step instanceof MacroStep.WorldSwitch) return "e.g. GARDEN";
		if (step instanceof MacroStep.WaitUntil || step instanceof MacroStep.IfElse) return "Type the condition value";
		return "Click to edit";
	}

	private static String delayValue(MacroStep step) {
		return step.delayMin() + "-" + step.delayMax();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != 0) return super.mouseClicked(event, doubleClick);
		int x = (int) event.x();
		int y = (int) event.y();
		if (showHelp) {
			if (helpCloseRect().contains(x, y)) showHelp = false;
			return true;
		}
		applyField();
		applyDelay();
		int panelX = PANEL_MARGIN;
		int panelY = PANEL_MARGIN;
		int panelW = Math.max(240, width - PANEL_MARGIN * 2);
		int panelH = Math.max(180, height - PANEL_MARGIN * 2);
		int listX = panelX + 10;
		int listY = panelY + HEADER_HEIGHT;
		int listW = panelW - 20;
		List<MacroStep> steps = macro.steps();
		if (x >= panelX + panelW - 92 && x < panelX + panelW - 12 && y >= panelY + 5 && y < panelY + 27) {
			if (ModuleKeybindManager.bindingMacro() == macro) ModuleKeybindManager.cancelBinding();
			else ModuleKeybindManager.beginMacroBinding(macro);
			return true;
		}
		int controlsY = panelY + panelH - 134;
		int visible = visibleRows(steps.size(), controlsY - listY - EDIT_AREA_HEIGHT);
		int first = Math.min(scrollRows, Math.max(0, steps.size() - visible));
		for (int row = 0; row < visible; row++) {
			int index = first + row;
			int rowY = listY + row * ROW_HEIGHT;
			if (x < listX || x >= listX + listW || y < rowY || y >= rowY + ROW_HEIGHT - 3) continue;
			if (x >= listX + listW - 47 && x < listX + listW - 30) {
				move(index, index - 1);
				return true;
			}
			if (x >= listX + listW - 30 && x < listX + listW - 12) {
				move(index, index + 1);
				return true;
			}
			selectedIndex = index;
			fieldText = isEditable(steps.get(index)) ? textValue(steps.get(index)) : "";
			delayText = delayValue(steps.get(index));
			editingField = false;
			editingDelay = false;
			if (x >= listX + listW - 82 && x < listX + listW - 47) {
				draggingIndex = index;
			}
			return true;
		}

		int delayY = controlsY - FIELD_HEIGHT - 5;
		int fieldY = delayY - FIELD_HEIGHT - 16;
		if (y >= delayY && y < delayY + FIELD_HEIGHT && selectedIndex >= 0
			&& selectedIndex < steps.size()) {
			editingDelay = true;
			delayText = delayValue(steps.get(selectedIndex));
			delaySelectAll = true;
			return true;
		}
		if (y >= fieldY && y < fieldY + FIELD_HEIGHT && selectedIndex >= 0
			&& selectedIndex < steps.size() && isEditable(steps.get(selectedIndex))) {
			editingField = true;
			fieldText = textValue(steps.get(selectedIndex));
			fieldSelectAll = true;
			return true;
		}
		if (y >= controlsY && y < controlsY + BUTTON_HEIGHT) {
			int addType = firstTypeAt(x - listX);
			if (addType >= 0) {
				addStep(steps, addType);
				return true;
			}
		}
		int second = controlsY + BUTTON_HEIGHT + 3;
		if (y >= second && y < second + BUTTON_HEIGHT) {
			int addType = secondTypeAt(x - listX);
			if (addType >= 5) {
				addStep(steps, addType);
				return true;
			}
		}
		int third = second + BUTTON_HEIGHT + 3;
		if (y >= third && y < third + BUTTON_HEIGHT) {
			if (x >= listX && x < listX + 28 && selectedIndex >= 0 && selectedIndex < steps.size()) {
				steps.remove(selectedIndex);
				selectedIndex = Math.min(selectedIndex, steps.size() - 1);
				ModConfig.markDirty();
				return true;
			}
			if (x >= listX + 31 && x < listX + 63 && selectedIndex >= 0 && selectedIndex < steps.size()) {
				steps.add(selectedIndex + 1, copy(steps.get(selectedIndex)));
				selectedIndex++;
				ModConfig.markDirty();
				return true;
			}
			if (x >= listX + 66 && x < listX + 102 && selectedIndex >= 0 && selectedIndex < steps.size()
				&& canAddChild(steps.get(selectedIndex))) {
				addChild(steps.get(selectedIndex));
				ModConfig.markDirty();
				return true;
			}
			if (x >= listX + 105 && x < listX + 145 && selectedIndex >= 0 && selectedIndex < steps.size()) {
				MacroStep selected = steps.get(selectedIndex);
				if (hasCondition(selected)) {
					cycleCondition(selected);
					fieldText = textValue(selected);
				} else {
					delayText = delayValue(selected);
					editingDelay = true;
					delaySelectAll = true;
				}
				ModConfig.markDirty();
				return true;
			}
			if (x >= listX + listW - 38 && x < listX + listW && y < third + BUTTON_HEIGHT) {
				closeToParent();
				return true;
			}
		}
		if (x >= listX + listW - 38 && x < listX + listW
			&& y >= third + BUTTON_HEIGHT + 3 && y < third + BUTTON_HEIGHT * 2 + 3) {
			showHelp = true;
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (showHelp) return true;
		int panelY = PANEL_MARGIN;
		int panelH = Math.max(180, height - PANEL_MARGIN * 2);
		int listY = panelY + HEADER_HEIGHT;
		int controlsY = panelY + panelH - 134;
		int visible = visibleRows(macro.steps().size(), controlsY - listY - EDIT_AREA_HEIGHT);
		if (mouseX >= PANEL_MARGIN + 10 && mouseX < width - PANEL_MARGIN - 10
			&& mouseY >= listY && mouseY < controlsY) {
			int max = Math.max(0, macro.steps().size() - visible);
			scrollRows = Math.max(0, Math.min(max, scrollRows - (int) Math.signum(scrollY)));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		return draggingIndex >= 0 || super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (draggingIndex < 0) return super.mouseReleased(event);
		int listY = PANEL_MARGIN + HEADER_HEIGHT;
		int target = scrollRows + (int) ((event.y() - listY) / ROW_HEIGHT);
		target = Math.max(0, Math.min(macro.steps().size() - 1, target));
		move(draggingIndex, target);
		draggingIndex = -1;
		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (showHelp) return true;
		if (editingDelay) {
			if (event.isAllowedChatCharacter() && (Character.isDigit(event.codepoint())
				|| event.codepoint() == '-' || event.codepoint() == '–') && delayText.length() < 24) {
				if (delaySelectAll) {
					delayText = "";
					delaySelectAll = false;
				}
				delayText += event.codepointAsString();
			}
			return true;
		}
		if (!editingField || !event.isAllowedChatCharacter()) return super.charTyped(event);
		if (fieldSelectAll) {
			fieldText = "";
			fieldSelectAll = false;
		}
		if (fieldText.length() < 256) fieldText += event.codepointAsString();
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (showHelp) {
			if (event.key() == InputConstants.KEY_ESCAPE) showHelp = false;
			return true;
		}
		if (editingDelay) {
			if (event.key() == InputConstants.KEY_BACKSPACE) {
				if (delaySelectAll) {
					delayText = "";
					delaySelectAll = false;
				} else if (!delayText.isEmpty()) {
					delayText = delayText.substring(0, delayText.length() - 1);
				}
				return true;
			}
			if (event.key() == InputConstants.KEY_RETURN) {
				applyDelay();
				return true;
			}
			if (event.key() == InputConstants.KEY_ESCAPE) {
				editingDelay = false;
				delaySelectAll = false;
				return true;
			}
		}
		if (editingField) {
			if (event.key() == InputConstants.KEY_BACKSPACE) {
				if (fieldSelectAll) {
					fieldText = "";
					fieldSelectAll = false;
				} else if (!fieldText.isEmpty()) {
					fieldText = fieldText.substring(0, fieldText.length() - 1);
				}
				return true;
			}
			if (event.key() == InputConstants.KEY_RETURN) {
				applyField();
				return true;
			}
			if (event.key() == InputConstants.KEY_ESCAPE) {
				editingField = false;
				fieldSelectAll = false;
				return true;
			}
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		ModuleKeybindManager.cancelBinding();
		closeToParent();
	}

	private void closeToParent() {
		applyField();
		applyDelay();
		ModuleKeybindManager.cancelBinding();
		ModConfig.markDirty();
		minecraft.setScreen(parent);
	}

	@Override
	public void removed() {
		if (ModuleKeybindManager.bindingMacro() == macro) ModuleKeybindManager.cancelBinding();
		super.removed();
	}

	private static int visibleRows(int stepCount, int availableHeight) {
		int capacity = Math.max(0, availableHeight / ROW_HEIGHT);
		return Math.min(MAX_VISIBLE_ROWS, Math.min(stepCount, capacity));
	}

	private void applyField() {
		if (!editingField || selectedIndex < 0 || selectedIndex >= macro.steps().size()) return;
		setTextValue(macro.steps().get(selectedIndex), fieldText);
		editingField = false;
		fieldSelectAll = false;
		ModConfig.markDirty();
	}

	private void applyDelay() {
		if (!editingDelay || selectedIndex < 0 || selectedIndex >= macro.steps().size()) return;
		String normalized = delayText == null ? "" : delayText.replace('–', '-').trim();
		String[] parts = normalized.split("-", -1);
		try {
			int min = Integer.parseInt(parts[0].trim());
			int max = parts.length > 1 && !parts[1].isBlank()
				? Integer.parseInt(parts[1].trim()) : min;
			macro.steps().get(selectedIndex).setDelay(min, max);
			ModConfig.markDirty();
		} catch (NumberFormatException ignored) {
			// Keep the previous valid range while the user is midway through typing.
		}
		editingDelay = false;
		delaySelectAll = false;
		delayText = delayValue(macro.steps().get(selectedIndex));
	}

	private void move(int from, int to) {
		List<MacroStep> steps = macro.steps();
		if (from < 0 || from >= steps.size() || to < 0 || to >= steps.size() || from == to) return;
		MacroStep step = steps.remove(from);
		steps.add(to, step);
		selectedIndex = to;
		ModConfig.markDirty();
	}

	private static void addChild(MacroStep step) {
		if (step instanceof MacroStep.IfElse branch) branch.thenSteps().add(new MacroStep.Wait(500, 700));
		if (step instanceof MacroStep.Repeat repeat) repeat.steps().add(new MacroStep.Wait(500, 700));
	}

	private static boolean canAddChild(MacroStep step) {
		return step instanceof MacroStep.IfElse || step instanceof MacroStep.Repeat;
	}

	private static void cycleCondition(MacroStep step) {
		MacroCondition current = conditionOf(step);
		MacroCondition next = switch (current) {
			case MacroCondition.Always ignored -> new MacroCondition.Screen("", true, true);
			case MacroCondition.Screen ignored -> new MacroCondition.Item("", true, true, true);
			case MacroCondition.Item ignored -> new MacroCondition.Chat("", true);
			case MacroCondition.Chat ignored -> new MacroCondition.World(true);
			case MacroCondition.World ignored -> new MacroCondition.All(List.of(new MacroCondition.Always(true)));
			case MacroCondition.All ignored -> new MacroCondition.Any(List.of(new MacroCondition.Always(true)));
			case MacroCondition.Any ignored -> new MacroCondition.Not(new MacroCondition.Always(true));
			case MacroCondition.Not ignored -> new MacroCondition.Always(true);
			default -> new MacroCondition.Always(true);
		};
		setCondition(step, next);
	}

	private static MacroCondition conditionOf(MacroStep step) {
		if (step instanceof MacroStep.WaitUntil wait) return wait.condition();
		if (step instanceof MacroStep.IfElse branch) return branch.condition();
		return new MacroCondition.Always(true);
	}

	private static void setCondition(MacroStep step, MacroCondition condition) {
		if (step instanceof MacroStep.WaitUntil wait) wait.setCondition(condition);
		if (step instanceof MacroStep.IfElse branch) branch.setCondition(condition);
	}

	private static boolean hasCondition(MacroStep step) {
		return step instanceof MacroStep.WaitUntil || step instanceof MacroStep.IfElse;
	}

	private static int firstTypeAt(int x) {
		if (x >= 0 && x < 32) return 0;
		if (x >= 35 && x < 71) return 1;
		if (x >= 74 && x < 110) return 2;
		if (x >= 113 && x < 145) return 3;
		if (x >= 148 && x < 184) return 4;
		return -1;
	}

	private static int secondTypeAt(int x) {
		if (x >= 0 && x < 32) return 5;
		if (x >= 35 && x < 69) return 7;
		if (x >= 72 && x < 100) return 6;
		if (x >= 103 && x < 125) return 8;
		if (x >= 128 && x < 166) return 9;
		if (x >= 169 && x < 215) return 10;
		return -1;
	}

	private void addStep(List<MacroStep> steps, int type) {
		MacroStep step = switch (type) {
			case 0 -> new MacroStep.Command("");
			case 1 -> new MacroStep.Chat("");
			case 2 -> new MacroStep.Wait(300, 600);
			case 3 -> new MacroStep.Key("key.keyboard.space", false, 250);
			case 4 -> new MacroStep.ClickSlot(0, 0, false);
			case 5 -> new MacroStep.ClickItem("", true, "container", 0, 0, false);
			case 6 -> new MacroStep.CloseScreen();
			case 7 -> new MacroStep.WaitUntil(new MacroCondition.Always(true));
			case 8 -> new MacroStep.IfElse(new MacroCondition.Always(true));
			case 9 -> new MacroStep.Repeat(false, 2);
			case 10 -> new MacroStep.WorldSwitch(Island.HUB);
			default -> new MacroStep.Wait(300, 600);
		};
		steps.add(step);
		selectedIndex = steps.size() - 1;
		fieldText = isEditable(step) ? textValue(step) : "";
		delayText = delayValue(step);
		editingField = isEditable(step);
		fieldSelectAll = editingField;
		editingDelay = false;
		delaySelectAll = false;
		ModConfig.markDirty();
	}

	private static MacroStep copy(MacroStep step) {
		MacroStep result;
		if (step instanceof MacroStep.Command command) result = new MacroStep.Command(command.command());
		else if (step instanceof MacroStep.Chat chat) result = new MacroStep.Chat(chat.message());
		else if (step instanceof MacroStep.Wait wait) result = new MacroStep.Wait(wait.minMillis(), wait.maxMillis());
		else if (step instanceof MacroStep.Key key) result = new MacroStep.Key(key.key(), key.hold(), key.holdMillis());
		else if (step instanceof MacroStep.ClickSlot click) result = new MacroStep.ClickSlot(click.slotId(), click.button(), click.shift());
		else if (step instanceof MacroStep.ClickItem click) result = new MacroStep.ClickItem(click.name(), click.contains(), click.scope(), click.occurrence(), click.button(), click.shift());
		else if (step instanceof MacroStep.CloseScreen) result = new MacroStep.CloseScreen();
		else if (step instanceof MacroStep.WorldSwitch worldSwitch) result = new MacroStep.WorldSwitch(worldSwitch.target());
		else if (step instanceof MacroStep.WaitUntil wait) result = new MacroStep.WaitUntil(wait.condition());
		else if (step instanceof MacroStep.IfElse branch) {
			MacroStep.IfElse copy = new MacroStep.IfElse(branch.condition());
			for (MacroStep child : branch.thenSteps()) copy.thenSteps().add(copy(child));
			for (MacroStep child : branch.elseSteps()) copy.elseSteps().add(copy(child));
			result = copy;
		} else if (step instanceof MacroStep.Repeat repeat) {
			MacroStep.Repeat copy = new MacroStep.Repeat(repeat.forever(), repeat.count());
			for (MacroStep child : repeat.steps()) copy.steps().add(copy(child));
			result = copy;
		} else result = new MacroStep.Wait(500, 700);
		result.setDelay(step.delayMin(), step.delayMax());
		return result;
	}

	private static String summary(MacroStep step) {
		return switch (step.type()) {
			case "command" -> "Command: " + trim(((MacroStep.Command) step).command(), 70);
			case "chat" -> "Chat: " + trim(((MacroStep.Chat) step).message(), 70);
			case "wait" -> "Wait: " + ((MacroStep.Wait) step).minMillis() + "–" + ((MacroStep.Wait) step).maxMillis() + " ms";
			case "key" -> "Key: " + trim(((MacroStep.Key) step).key(), 70);
			case "click_slot" -> "Click slot " + ((MacroStep.ClickSlot) step).slotId();
			case "click_item" -> "Click item: " + trim(((MacroStep.ClickItem) step).name(), 70);
			case "close_screen" -> "Press Escape";
			case "world_switch" -> "World switch: " + ((MacroStep.WorldSwitch) step).target().label();
			case "wait_until" -> "Wait until condition";
			case "if" -> "If / Else (nested)";
			case "repeat" -> ((MacroStep.Repeat) step).forever() ? "Repeat forever (nested)" : "Repeat " + ((MacroStep.Repeat) step).count() + "×";
			default -> step.type();
		};
	}

	private static boolean isEditable(MacroStep step) {
		return step instanceof MacroStep.Command || step instanceof MacroStep.Chat
			|| step instanceof MacroStep.Key || step instanceof MacroStep.ClickItem
			|| step instanceof MacroStep.Wait || step instanceof MacroStep.ClickSlot
			|| step instanceof MacroStep.WorldSwitch || hasCondition(step);
	}

	private static String textValue(MacroStep step) {
		if (step instanceof MacroStep.Command command) return command.command();
		if (step instanceof MacroStep.Chat chat) return chat.message();
		if (step instanceof MacroStep.Key key) {
			return key.key() + " | " + (key.hold() ? "hold" : "tap") + " | " + key.holdMillis();
		}
		if (step instanceof MacroStep.ClickItem click) {
			return click.name() + " | " + (click.contains() ? "contains" : "exact") + " | "
				+ click.scope() + " | " + click.occurrence();
		}
		if (step instanceof MacroStep.Wait wait) return wait.minMillis() + "-" + wait.maxMillis();
		if (step instanceof MacroStep.ClickSlot click) {
			return click.slotId() + " | " + click.button() + " | " + (click.shift() ? "shift" : "normal");
		}
		if (step instanceof MacroStep.WorldSwitch worldSwitch) return worldSwitch.target().name();
		if (step instanceof MacroStep.WaitUntil || step instanceof MacroStep.IfElse) {
			MacroCondition condition = conditionOf(step);
			if (condition instanceof MacroCondition.Screen screen) return screen.title();
			if (condition instanceof MacroCondition.Item item) return item.name();
			if (condition instanceof MacroCondition.Chat chat) return chat.text();
		}
		return "";
	}

	private static void setTextValue(MacroStep step, String value) {
		if (step instanceof MacroStep.Command command) command.setCommand(value);
		if (step instanceof MacroStep.Chat chat) chat.setMessage(value);
		if (step instanceof MacroStep.Key key) {
			String[] parts = value.split("\\|", -1);
			key.setKey(parts[0].trim());
			if (parts.length > 1) key.setHold("hold".equalsIgnoreCase(parts[1].trim()));
			if (parts.length > 2) {
				try { key.setHoldMillis(Integer.parseInt(parts[2].trim())); } catch (NumberFormatException ignored) { }
			}
		}
		if (step instanceof MacroStep.ClickItem click) {
			String[] parts = value.split("\\|", -1);
			click.setName(parts[0].trim());
			if (parts.length > 1) click.setContains("contains".equalsIgnoreCase(parts[1].trim()));
			if (parts.length > 2) click.setScope(parts[2].trim().toLowerCase());
			if (parts.length > 3) {
				try { click.setOccurrence(Integer.parseInt(parts[3].trim())); } catch (NumberFormatException ignored) { }
			}
		}
		if (step instanceof MacroStep.Wait wait) {
			String[] parts = value.replace('–', '-').split("-");
			try {
				int min = Integer.parseInt(parts[0].trim());
				int max = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : min;
				wait.setRange(min, max);
			} catch (NumberFormatException ignored) {
				// Keep the previous valid range while the user is midway through typing.
			}
		}
		if (step instanceof MacroStep.ClickSlot click) {
			String[] parts = value.split("\\|", -1);
			try { click.setSlotId(Integer.parseInt(parts[0].trim())); } catch (NumberFormatException ignored) { }
			if (parts.length > 1) {
				try { click.setButton(Integer.parseInt(parts[1].trim())); } catch (NumberFormatException ignored) { }
			}
			if (parts.length > 2) click.setShift("shift".equalsIgnoreCase(parts[2].trim()));
		}
		if (step instanceof MacroStep.WorldSwitch worldSwitch) {
			Island target = parseIsland(value);
			if (target != null) worldSwitch.setTarget(target);
		}
		if (step instanceof MacroStep.WaitUntil || step instanceof MacroStep.IfElse) {
			MacroCondition condition = conditionOf(step);
			if (condition instanceof MacroCondition.Screen screen) setCondition(step,
				new MacroCondition.Screen(value, screen.mustBeOpen(), screen.contains()));
			if (condition instanceof MacroCondition.Item item) setCondition(step,
				new MacroCondition.Item(value, item.mustExist(), item.contains(), item.includePlayerInventory()));
			if (condition instanceof MacroCondition.Chat chat) setCondition(step,
				new MacroCondition.Chat(value, chat.contains()));
		}
	}

	private static Island parseIsland(String value) {
		if (value == null || value.isBlank()) return null;
		String normalized = value.trim();
		for (Island island : Island.values()) {
			if (!island.selectable()) continue;
			if (island.name().equalsIgnoreCase(normalized) || island.label().equalsIgnoreCase(normalized)) return island;
		}
		return null;
	}

	private static String trim(String value, int max) {
		if (value == null) return "";
		return value.length() <= max ? value : value.substring(0, Math.max(0, max - 1)) + "…";
	}

	private record Rect(int x, int y, int w, int h) {
		private boolean contains(int mouseX, int mouseY) {
			return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		}
	}
}
