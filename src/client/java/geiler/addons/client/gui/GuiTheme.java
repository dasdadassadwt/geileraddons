package geiler.addons.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Shared palette and panel drawing for the mod's screens, so they look like one GUI. */
public final class GuiTheme {
	public static final int RADIUS = 6;

	public static final int PANEL_TOP = 0xF0261434;
	public static final int PANEL_BOTTOM = 0xF0140A1E;
	public static final int MODULE_PANEL_TOP = 0xF02D1740;
	public static final int MODULE_PANEL_BOTTOM = 0xF0170B24;
	public static final int BORDER = 0x33FFFFFF;
	public static final int CATEGORY_SELECTED = 0xFF6C2BD9;
	public static final int CATEGORY_HOVER = 0x30FFFFFF;
	public static final int MODULE_ENABLED_BG = 0xFF6C2BD9;
	public static final int TEXT_PRIMARY = 0xFFFFFFFF;
	public static final int TEXT_SECONDARY = 0xFFB8AFC7;
	public static final int TEXT_MUTED = 0xFF8A7FA0;
	public static final int TEXT_ERROR = 0xFFFF6B6B;
	public static final int SLIDER_TRACK = 0xA0000000;
	public static final int SLIDER_FILL = 0xFF9B59F6;
	public static final int BUTTON_BG = 0xFF4A2185;
	public static final int BUTTON_HOVER = 0xFF6C2BD9;
	public static final int DANGER_BG = 0xFF7A1F2B;
	public static final int DANGER_HOVER = 0xFFC0303F;
	public static final int SCROLLBAR = 0x60FFFFFF;
	/** Dims the screen behind a modal dialog. */
	public static final int DIALOG_SHADE = 0xB0000000;

	private GuiTheme() {
	}

	public static void roundedRect(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int radius, int colorTop, int colorBottom) {
		if (colorTop == colorBottom) {
			graphics.fill(x, y + radius, x + w, y + h - radius, colorTop);
		} else {
			graphics.fillGradient(x, y + radius, x + w, y + h - radius, colorTop, colorBottom);
		}
		for (int row = 0; row < radius; row++) {
			double dy = radius - row - 0.5;
			int inset = (int) Math.round(radius - Math.sqrt(Math.max(0, radius * (double) radius - dy * dy)));
			int topColor = colorTop == colorBottom ? colorTop : lerpColor(colorTop, colorBottom, row / (float) (h));
			graphics.fill(x + inset, y + row, x + w - inset, y + row + 1, topColor);

			int bottomRowY = y + h - radius + row;
			int bottomColor = colorTop == colorBottom ? colorTop : lerpColor(colorTop, colorBottom, (h - radius + row) / (float) h);
			graphics.fill(x + inset, bottomRowY, x + w - inset, bottomRowY + 1, bottomColor);
		}
	}

	public static void outline(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int radius, int color) {
		graphics.fill(x + radius, y, x + w - radius, y + 1, color);
		graphics.fill(x + radius, y + h - 1, x + w - radius, y + h, color);
		graphics.fill(x, y + radius, x + 1, y + h - radius, color);
		graphics.fill(x + w - 1, y + radius, x + w, y + h - radius, color);
	}

	private static int lerpColor(int from, int to, float fraction) {
		fraction = Math.max(0, Math.min(1, fraction));
		int a1 = (from >> 24) & 0xFF, r1 = (from >> 16) & 0xFF, g1 = (from >> 8) & 0xFF, b1 = from & 0xFF;
		int a2 = (to >> 24) & 0xFF, r2 = (to >> 16) & 0xFF, g2 = (to >> 8) & 0xFF, b2 = to & 0xFF;
		int a = (int) (a1 + (a2 - a1) * fraction);
		int r = (int) (r1 + (r2 - r1) * fraction);
		int g = (int) (g1 + (g2 - g1) * fraction);
		int b = (int) (b1 + (b2 - b1) * fraction);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}
}
