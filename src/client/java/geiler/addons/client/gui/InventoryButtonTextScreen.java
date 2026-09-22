package geiler.addons.client.gui;

import geiler.addons.client.module.impl.VisualModule;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

import static geiler.addons.client.gui.GuiTheme.*;

/** Small text editor for the label appearance; input is never treated as a texture identifier. */
public final class InventoryButtonTextScreen extends Screen {
	private final Screen parent;
	private final Consumer<String> selected;
	private final String initial;
	private final String dialogTitle;
	private final String hint;
	private EditBox text;
	private Rect panel;

	public InventoryButtonTextScreen(Screen parent, String initial, Consumer<String> selected) {
		this(parent, "Inventory Button Text", "Text appears inside an 18×18 button; hover shows its full label.",
			initial, selected);
	}

	public InventoryButtonTextScreen(Screen parent, String dialogTitle, String hint,
		String initial, Consumer<String> selected) {
		super(Component.literal(dialogTitle));
		this.parent = parent;
		this.dialogTitle = dialogTitle;
		this.hint = hint;
		this.initial = initial == null ? "" : initial;
		this.selected = selected;
	}

	@Override public boolean isPauseScreen() { return false; }

	@Override
	protected void init() {
		panel = panel();
		text = new EditBox(font, panel.x + 12, panel.y + 48, panel.w - 24, 20, Component.literal("Button label"));
		text.setMaxLength(256);
		text.setValue(initial);
		addRenderableWidget(text);
		setInitialFocus(text);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		VisualModule.INSTANCE.refreshTheme();
		panel = panel();
		graphics.fill(0, 0, width, height, DIALOG_SHADE);
		roundedRectBordered(graphics, panel.x, panel.y, panel.w, panel.h, RADIUS,
			PANEL_TOP, PANEL_BOTTOM, BORDER);
		graphics.text(font, trim(dialogTitle, panel.w - 24), panel.x + 12, panel.y + 12, TEXT_PRIMARY);
		graphics.text(font, trim(hint, panel.w - 24), panel.x + 12, panel.y + 27, TEXT_MUTED);
		int buttonWidth = Math.min(70, Math.max(48, (panel.w - 36) / 2));
		button(graphics, "Save", panel.x + 12, panel.y + 82, buttonWidth, 20, mouseX, mouseY);
		button(graphics, "Cancel", panel.x + panel.w - 12 - buttonWidth, panel.y + 82, buttonWidth, 20, mouseX, mouseY);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int x = (int) event.x();
		int y = (int) event.y();
		if (event.button() == 0 && y >= panel.y + 82 && y < panel.y + 102) {
			int buttonWidth = Math.min(70, Math.max(48, (panel.w - 36) / 2));
			if (x >= panel.x + 12 && x < panel.x + 12 + buttonWidth) {
				if (minecraft != null) minecraft.setScreen(parent);
				selected.accept(text.getValue());
				return true;
			}
			if (x >= panel.x + panel.w - 12 - buttonWidth && x < panel.x + panel.w - 12) {
				if (minecraft != null) minecraft.setScreen(parent);
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override public void onClose() { if (minecraft != null) minecraft.setScreen(parent); }

	private Rect panel() {
		int panelWidth = Math.min(300, Math.max(1, width - 20));
		int panelHeight = Math.min(124, Math.max(1, height - 20));
		return new Rect((width - panelWidth) / 2, (height - panelHeight) / 2, panelWidth, panelHeight);
	}
	private String trim(String value, int maxWidth) {
		if (font.width(value) <= maxWidth) return value;
		String ellipsis = "…";
		int prefixWidth = Math.max(0, maxWidth - font.width(ellipsis));
		return font.plainSubstrByWidth(value, prefixWidth) + ellipsis;
	}
	private void button(GuiGraphicsExtractor graphics, String label, int x, int y, int w, int h,
		int mouseX, int mouseY) {
		boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		roundedRect(graphics, x, y, w, h, RADIUS_SMALL, hovered ? BUTTON_HOVER : BUTTON_BG);
		graphics.centeredText(font, label, x + w / 2, y + 6, hovered ? TEXT_ON_ACCENT : TEXT_PRIMARY);
	}
	private record Rect(int x, int y, int w, int h) { }
}
