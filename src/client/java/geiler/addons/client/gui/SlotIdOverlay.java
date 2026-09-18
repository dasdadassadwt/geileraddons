package geiler.addons.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

import java.util.ArrayList;
import java.util.List;

/** Draws the runtime menu slot id directly on the corresponding slot. */
public final class SlotIdOverlay {
	private static final int BADGE_HEIGHT = 9;
	private static final int BADGE_PADDING = 2;
	private static final int BADGE_COLOR = 0xB0000000;
	private static final float SMALL_LABEL_SCALE = 0.66f;
	private static AbstractContainerMenu cachedMenu;
	private static int cachedLeftPos;
	private static int cachedTopPos;
	private static List<Badge> cachedBadges = List.of();

	private SlotIdOverlay() {
	}

	public static void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics,
		int leftPos, int topPos) {
		Minecraft minecraft = Minecraft.getInstance();
		Font font = minecraft.font;
		for (Badge badge : badges(screen.getMenu(), leftPos, topPos, font)) {
			graphics.fill(badge.x(), badge.y(), badge.x() + badge.width(),
				badge.y() + BADGE_HEIGHT, BADGE_COLOR);
			if (badge.scale() == 1.0f) {
				graphics.text(font, badge.label(), badge.textX(), badge.textY(), GuiTheme.TEXT_PRIMARY);
				continue;
			}
			// Three-digit menu ids are uncommon, so only those labels pay for a transform. The common
			// one- and two-digit path stays a pair of cheap GUI draw calls per slot.
			graphics.pose().pushMatrix();
			graphics.pose().translate(badge.textX(), badge.textY());
			graphics.pose().scale(badge.scale(), badge.scale());
			graphics.text(font, badge.label(), 0, 0, GuiTheme.TEXT_PRIMARY);
			graphics.pose().popMatrix();
		}
	}

	private static List<Badge> badges(AbstractContainerMenu menu, int leftPos, int topPos, Font font) {
		if (menu == cachedMenu && leftPos == cachedLeftPos && topPos == cachedTopPos) return cachedBadges;

		List<Badge> result = new ArrayList<>(menu.slots.size());
		for (Slot slot : menu.slots) {
			String label = Integer.toString(slot.index);
			float scale = label.length() > 2 ? SMALL_LABEL_SCALE : 1.0f;
			int textWidth = Math.round(font.width(label) * scale);
			int width = Math.max(8, Math.min(15, textWidth + BADGE_PADDING));
			int x = leftPos + slot.x + 1;
			int y = topPos + slot.y + 2;
			result.add(new Badge(x, y, width, label, scale, x + 1, y));
		}
		cachedMenu = menu;
		cachedLeftPos = leftPos;
		cachedTopPos = topPos;
		cachedBadges = List.copyOf(result);
		return cachedBadges;
	}

	private record Badge(int x, int y, int width, String label, float scale, int textX, int textY) {
	}
}
