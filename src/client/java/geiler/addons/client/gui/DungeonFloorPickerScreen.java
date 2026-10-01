package geiler.addons.client.gui;

import geiler.addons.client.dungeon.DungeonContextTracker;
import geiler.addons.client.dungeon.DungeonFloor;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import static geiler.addons.client.gui.GuiTheme.*;

/** Manual floor override picker used by Dungeon Guide. */
public final class DungeonFloorPickerScreen extends Screen {
	private final Screen parent;
	private String notice = "";

	public DungeonFloorPickerScreen(Screen parent) {
		super(Component.literal("Dungeon Floor"));
		this.parent = parent;
	}

	@Override public boolean isPauseScreen() { return false; }

	@Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, width, height, DIALOG_SHADE);
		Layout l = layout();
		roundedRectBordered(graphics, l.x, l.y, l.w, l.h, RADIUS, PANEL_TOP, PANEL_BOTTOM, BORDER);
		graphics.centeredText(font, "Dungeon Floor", l.x + l.w / 2, l.y + 12, TEXT_PRIMARY);
		String status = DungeonContextTracker.inDungeon()
			? "Detected dungeon · " + (DungeonContextTracker.manualFloor() == null
				? "automatic" : "manual " + DungeonContextTracker.manualFloor().displayName())
			: "Enter a detected dungeon before choosing a manual floor";
		graphics.centeredText(font, status, l.x + l.w / 2, l.y + 28, TEXT_MUTED);
		DungeonFloor[] floors = DungeonFloor.values();
		int gap = 5, columns = 4, buttonWidth = (l.w - 20 - gap * (columns - 1)) / columns;
		int buttonHeight = 24, top = l.y + 52;
		for (int i = 0; i < floors.length; i++) {
			int row = i / columns, column = i % columns;
			int x = l.x + 10 + column * (buttonWidth + gap), y = top + row * (buttonHeight + gap);
			button(graphics, x, y, buttonWidth, buttonHeight, floors[i].displayName(), mouseX, mouseY);
		}
		int rowCount = (floors.length + columns - 1) / columns;
		int actionY = top + rowCount * (buttonHeight + gap) + 4;
		int half = (l.w - 25) / 2;
		button(graphics, l.x + 10, actionY, half, 21, "Automatic floor", mouseX, mouseY);
		button(graphics, l.x + 15 + half, actionY, half, 21, "Back", mouseX, mouseY);
		if (!notice.isBlank()) graphics.centeredText(font, notice, l.x + l.w / 2, l.y + l.h - 13, TEXT_MUTED);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	private void button(GuiGraphicsExtractor graphics, int x, int y, int w, int h,
		String label, int mouseX, int mouseY) {
		boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		roundedRect(graphics, x, y, w, h, RADIUS_SMALL, hover ? CARD_BG_HOVER : CARD_BG);
		graphics.centeredText(font, label, x + w / 2, y + (h - font.lineHeight) / 2, TEXT_PRIMARY);
	}

	@Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) return true;
		if (event.button() != 0) return false;
		Layout l = layout();
		DungeonFloor[] floors = DungeonFloor.values();
		int gap = 5, columns = 4, buttonWidth = (l.w - 20 - gap * (columns - 1)) / columns;
		int buttonHeight = 24, top = l.y + 52;
		for (int i = 0; i < floors.length; i++) {
			int row = i / columns, column = i % columns;
			int x = l.x + 10 + column * (buttonWidth + gap), y = top + row * (buttonHeight + gap);
			if (inside(event.x(), event.y(), x, y, buttonWidth, buttonHeight)) {
				if (DungeonContextTracker.setManualFloor(floors[i])) onClose();
				else notice = "A dungeon must be detected first";
				return true;
			}
		}
		int rowCount = (floors.length + columns - 1) / columns;
		int actionY = top + rowCount * (buttonHeight + gap) + 4;
		int half = (l.w - 25) / 2;
		if (inside(event.x(), event.y(), l.x + 10, actionY, half, 21)) {
			DungeonContextTracker.clearManualFloor();
			onClose();
			return true;
		}
		if (inside(event.x(), event.y(), l.x + 15 + half, actionY, half, 21)) { onClose(); return true; }
		return false;
	}

	@Override public boolean keyPressed(KeyEvent event) {
		if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
		return super.keyPressed(event);
	}

	@Override public void onClose() { minecraft.setScreen(parent); }

	private Layout layout() {
		int w = Math.max(1, Math.min(520, width - 16));
		int h = 52 + 4 * 29 + 21 + 28;
		int x = (width - w) / 2, y = (height - h) / 2;
		return new Layout(x, y, w, h);
	}

	private boolean inside(double x, double y, int bx, int by, int bw, int bh) {
		return x >= bx && x < bx + bw && y >= by && y < by + bh;
	}
	private record Layout(int x, int y, int w, int h) { }
}
