package geiler.addons.client.gui;

import geiler.addons.client.config.ModConfig;
import geiler.addons.client.hud.HudElement;
import geiler.addons.client.hud.HudManager;
import geiler.addons.client.module.impl.VisualModule;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

import static geiler.addons.client.gui.GuiTheme.*;

/** Drag-to-place editor for the mod's HUD panels. */
public class MoveUiScreen extends Screen {
	private static final int OUTLINE_PADDING = 2;

	private final Screen parent;

	private HudElement dragging;
	/** Where inside the panel it was grabbed, so it doesn't jump to the cursor on the first move. */
	private int grabX;
	private int grabY;

	public MoveUiScreen(Screen parent) {
		super(Component.literal("Move Elements"));
		this.parent = parent;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		VisualModule.INSTANCE.refreshTheme();
		graphics.fill(0, 0, this.width, this.height, DIALOG_SHADE);
		Font font = this.font;

		List<HudElement> elements = HudManager.elements();
		if (elements.isEmpty()) {
			graphics.centeredText(font, "No movable elements yet.", this.width / 2, this.height / 2 - 4, TEXT_MUTED);
		}

		for (HudElement element : elements) {
			int x = HudManager.x(element, font, this.width);
			int y = HudManager.y(element, font, this.height);
			int w = element.width(font);
			int h = element.height(font);

			boolean hovered = dragging == element
				|| (dragging == null && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h);
			roundedRectBordered(graphics, x - OUTLINE_PADDING, y - OUTLINE_PADDING,
				w + OUTLINE_PADDING * 2, h + OUTLINE_PADDING * 2, RADIUS_SMALL,
				hovered ? CARD_BG_HOVER : CARD_BG, hovered ? CARD_BG_HOVER : CARD_BG,
				hovered ? CARD_BORDER_ENABLED : CARD_BORDER);

			// A module that is switched off draws nothing of its own, so name the empty box -
			// otherwise there is no way to tell which panel you are placing.
			if (element.visible()) {
				element.render(graphics, font, x, y);
			} else {
				graphics.text(font, element.displayName(), x, y + (h - 8) / 2, TEXT_MUTED);
			}
		}

		graphics.centeredText(font, "Drag a panel to move it. Esc when you're done.",
			this.width / 2, 8, TEXT_SECONDARY);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != 0) return super.mouseClicked(event, doubleClick);
		int mouseX = (int) event.x();
		int mouseY = (int) event.y();
		Font font = this.font;

		// Back to front, so the panel drawn on top is the one that gets picked up.
		List<HudElement> elements = HudManager.elements();
		for (int i = elements.size() - 1; i >= 0; i--) {
			HudElement element = elements.get(i);
			int x = HudManager.x(element, font, this.width);
			int y = HudManager.y(element, font, this.height);
			if (mouseX < x || mouseX >= x + element.width(font) || mouseY < y || mouseY >= y + element.height(font)) {
				continue;
			}
			dragging = element;
			grabX = mouseX - x;
			grabY = mouseY - y;
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (dragging == null) return super.mouseDragged(event, dragX, dragY);
		HudManager.setPosition(dragging, (int) event.x() - grabX, (int) event.y() - grabY,
			this.font, this.width, this.height);
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (dragging == null) return super.mouseReleased(event);
		dragging = null;
		ModConfig.save();
		return true;
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(parent);
	}
}
