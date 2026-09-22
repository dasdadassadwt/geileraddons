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
	public static final int PLAYER_INVENTORY_WIDTH = 176;
	public static final int PLAYER_INVENTORY_HEIGHT = 166;
	private static AbstractContainerMenu cachedMenu;
	private static int cachedLeftPos;
	private static int cachedTopPos;
	private static Font cachedFont;
	private static List<SlotIdBadgeLayout.Badge> cachedBadges = List.of();

	private SlotIdOverlay() {
	}

	public static void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics,
		int leftPos, int topPos) {
		renderMenu(screen.getMenu(), graphics, Minecraft.getInstance().font, leftPos, topPos);
	}

	/** Draws the same badges for any menu, including the stable player-inventory HUD preview. */
	public static void renderMenu(AbstractContainerMenu menu, GuiGraphicsExtractor graphics,
		Font font, int leftPos, int topPos) {
		for (SlotIdBadgeLayout.Badge badge : badges(menu, leftPos, topPos, font)) {
			graphics.fill(badge.x(), badge.y(), badge.x() + badge.width(),
				badge.y() + SlotIdBadgeLayout.BADGE_HEIGHT, SlotIdBadgeLayout.BADGE_COLOR);
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

	private static List<SlotIdBadgeLayout.Badge> badges(AbstractContainerMenu menu, int leftPos,
		int topPos, Font font) {
		if (menu == cachedMenu && leftPos == cachedLeftPos && topPos == cachedTopPos && font == cachedFont) {
			return cachedBadges;
		}

		List<SlotIdBadgeLayout.SlotPosition> positions = new ArrayList<>(menu.slots.size());
		for (Slot slot : menu.slots) {
			positions.add(new SlotIdBadgeLayout.SlotPosition(slot.index, slot.x, slot.y));
		}
		List<SlotIdBadgeLayout.Badge> result = SlotIdBadgeLayout.layout(positions,
			leftPos, topPos, font::width);
		cachedMenu = menu;
		cachedLeftPos = leftPos;
		cachedTopPos = topPos;
		cachedFont = font;
		cachedBadges = result;
		return cachedBadges;
	}
}
