package geiler.addons.client.gui;

import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.impl.VisualModule;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.function.IntConsumer;

import static geiler.addons.client.gui.GuiTheme.*;

/** Shared HSV and alpha color picker for screens that edit colors outside Click GUI settings. */
public final class GuiColorPickerScreen extends Screen {
	private static final int PANEL_WIDTH = 304;
	private static final int PANEL_HEIGHT = 202;
	private final Screen parent;
	private final String label;
	private final ColorSetting color;
	private final IntConsumer onApply;
	private EditBox hex;
	private PickerPart dragging;

	public GuiColorPickerScreen(Screen parent, String label, int argb, IntConsumer onApply) {
		super(Component.literal("Color Picker"));
		this.parent = parent;
		this.label = label == null || label.isBlank() ? "Color" : label;
		this.color = new ColorSetting("picker", "picker", (argb >>> 16) & 0xFF,
			(argb >>> 8) & 0xFF, argb & 0xFF, argb >>> 24);
		this.onApply = onApply == null ? ignored -> { } : onApply;
	}

	@Override public boolean isPauseScreen() { return false; }

	@Override protected void init() {
		Rect bounds = pickerBounds();
		hex = addRenderableWidget(new EditBox(font, bounds.x + 43, bounds.hexY, 82, 17,
			Component.literal("RRGGBBAA")));
		hex.setMaxLength(8);
		hex.setValue(color.hex());
		hex.setTooltip(Tooltip.create(Component.literal("Hex color in RRGGBBAA order, including alpha.")));
		hex.setResponder(color::setHex);
	}

	@Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		VisualModule.INSTANCE.refreshTheme();
		graphics.fill(0, 0, width, height, DIALOG_SHADE);
		Rect b = pickerBounds();
		roundedRectBordered(graphics, b.x, b.y, b.w, b.h, RADIUS, PANEL_TOP, PANEL_BOTTOM, BORDER);
		graphics.centeredText(font, label, width / 2, b.y + 10, TEXT_PRIMARY);
		graphics.centeredText(font, "Choose a color · hue · opacity", width / 2, b.y + 23, TEXT_MUTED);
		renderSaturationSquare(graphics, b.square);
		renderHueBar(graphics, b.hue);
		renderAlphaBar(graphics, b.alpha);
		graphics.text(font, "Hex", b.x + 18, b.hexY + 5, TEXT_MUTED);
		Rect preview = new Rect(b.x + 139, b.hexY, 34, 17);
		ColorPickerPalette.checkerboard(graphics, preview.x, preview.y, preview.w, preview.h);
		roundedRectBordered(graphics, preview.x, preview.y, preview.w, preview.h, 3,
			color.argb(), color.argb(), BORDER);
		drawButton(graphics, b.cancel, "Cancel", mouseX, mouseY);
		drawButton(graphics, b.done, "Apply", mouseX, mouseY);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	@Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0) {
			Rect b = pickerBounds();
			if (b.square.contains(event.x(), event.y())) {
				dragging = PickerPart.SQUARE;
				applyPicker(event.x(), event.y());
				return true;
			}
			if (b.hue.contains(event.x(), event.y())) {
				dragging = PickerPart.HUE;
				applyPicker(event.x(), event.y());
				return true;
			}
			if (b.alpha.contains(event.x(), event.y())) {
				dragging = PickerPart.ALPHA;
				applyPicker(event.x(), event.y());
				return true;
			}
			if (b.done.contains(event.x(), event.y())) {
				onApply.accept(color.argb());
				minecraft.setScreen(parent);
				return true;
			}
			if (b.cancel.contains(event.x(), event.y())) {
				minecraft.setScreen(parent);
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (dragging != null && event.button() == 0) {
			applyPicker(event.x(), event.y());
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override public boolean mouseReleased(MouseButtonEvent event) {
		if (dragging != null) {
			dragging = null;
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override public boolean keyPressed(KeyEvent event) {
		if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
			minecraft.setScreen(parent);
			return true;
		}
		return super.keyPressed(event);
	}

	@Override public void onClose() { minecraft.setScreen(parent); }

	private void applyPicker(double mouseX, double mouseY) {
		Rect b = pickerBounds();
		switch (dragging) {
			case SQUARE -> ColorPickerPalette.applySquare(color, mouseX, mouseY,
				b.square.x, b.square.y, b.square.w, b.square.h);
			case HUE -> ColorPickerPalette.applyHue(color, mouseX, b.hue.x, b.hue.w);
			case ALPHA -> ColorPickerPalette.applyAlpha(color, mouseX, b.alpha.x, b.alpha.w);
		}
		if (hex != null) hex.setValue(color.hex());
	}

	private void renderSaturationSquare(GuiGraphicsExtractor graphics, Rect square) {
		ColorPickerPalette.saturationSquare(graphics, square.x, square.y, square.w, square.h, color);
	}

	private void renderHueBar(GuiGraphicsExtractor graphics, Rect bar) {
		ColorPickerPalette.hueBar(graphics, bar.x, bar.y, bar.w, bar.h, color);
	}

	private void renderAlphaBar(GuiGraphicsExtractor graphics, Rect bar) {
		ColorPickerPalette.alphaBar(graphics, bar.x, bar.y, bar.w, bar.h, color);
	}

	private void drawButton(GuiGraphicsExtractor graphics, Rect rect, String text, int mouseX, int mouseY) {
		int fill = rect.contains(mouseX, mouseY) ? BUTTON_HOVER : BUTTON_BG;
		roundedRectBordered(graphics, rect.x, rect.y, rect.w, rect.h, RADIUS_SMALL, fill, fill, BORDER);
		graphics.centeredText(font, text, rect.x + rect.w / 2,
			rect.y + (rect.h - font.lineHeight) / 2, TEXT_ON_ACCENT);
	}

	private Rect pickerBounds() {
		int x = (width - PANEL_WIDTH) / 2;
		int y = (height - PANEL_HEIGHT) / 2;
		int innerX = x + 20;
		int innerW = PANEL_WIDTH - 40;
		Rect square = new Rect(innerX, y + 40, innerW, 64);
		Rect hue = new Rect(innerX, y + 109, innerW, 8);
		Rect alpha = new Rect(innerX, y + 123, innerW, 8);
		int hexY = y + 140;
		Rect cancel = new Rect(x + 20, y + 169, (PANEL_WIDTH - 48) / 2, 21);
		Rect done = new Rect(x + 28 + (PANEL_WIDTH - 48) / 2, y + 169, (PANEL_WIDTH - 48) / 2, 21);
		return new Rect(x, y, PANEL_WIDTH, PANEL_HEIGHT, square, hue, alpha, hexY, cancel, done);
	}

	private enum PickerPart { SQUARE, HUE, ALPHA }
	private record Rect(int x, int y, int w, int h, Rect square, Rect hue, Rect alpha, int hexY,
		Rect cancel, Rect done) {
		private Rect(int x, int y, int w, int h) { this(x, y, w, h, null, null, null, 0, null, null); }
		boolean contains(double px, double py) { return px >= x && px < x + w && py >= y && py < y + h; }
	}
}
