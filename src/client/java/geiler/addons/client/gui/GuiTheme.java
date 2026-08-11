package geiler.addons.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Shared palette and panel drawing for the mod's screens, so they look like one GUI. */
public final class GuiTheme {
	public static final int RADIUS = 8;
	/** Corner radius for the small stuff - rows, cards, swatches, buttons. */
	public static final int RADIUS_SMALL = 5;

	public static final int PANEL_TOP = 0xF0261434;
	public static final int PANEL_BOTTOM = 0xF0140A1E;
	public static final int MODULE_PANEL_TOP = 0xF02D1740;
	public static final int MODULE_PANEL_BOTTOM = 0xF0170B24;
	public static final int BORDER = 0x33FFFFFF;
	public static final int CATEGORY_SELECTED = 0xFF6C2BD9;
	public static final int CATEGORY_HOVER = 0x30FFFFFF;
	public static final int MODULE_ENABLED_BG = 0xFF6C2BD9;
	/** Card background: raised just enough off the panel to read as a separate surface. */
	public static final int CARD_BG = 0x38FFFFFF;
	public static final int CARD_BG_HOVER = 0x50FFFFFF;
	public static final int CARD_BG_ENABLED = 0x666C2BD9;
	public static final int CARD_BORDER = 0x22FFFFFF;
	public static final int CARD_BORDER_ENABLED = 0xAA9B59F6;
	public static final int GROUP_HEADER = 0x24FFFFFF;
	public static final int TEXT_PRIMARY = 0xFFFFFFFF;
	public static final int TEXT_SECONDARY = 0xFFB8AFC7;
	public static final int TEXT_MUTED = 0xFF8A7FA0;
	public static final int TEXT_ERROR = 0xFFFF6B6B;
	public static final int TEXT_WARN = 0xFFFFC55C;
	public static final int SLIDER_TRACK = 0xA0000000;
	public static final int SLIDER_FILL = 0xFF9B59F6;
	public static final int SWITCH_OFF = 0xFF3A3145;
	public static final int SWITCH_ON = 0xFFB44FD6;
	public static final int SWITCH_KNOB = 0xFFFFFFFF;
	public static final int BUTTON_BG = 0xFF4A2185;
	public static final int BUTTON_HOVER = 0xFF6C2BD9;
	public static final int DANGER_BG = 0xFF7A1F2B;
	public static final int DANGER_HOVER = 0xFFC0303F;
	public static final int SCROLLBAR = 0x60FFFFFF;
	/** Dims the screen behind a modal dialog. */
	public static final int DIALOG_SHADE = 0xB0000000;

	private GuiTheme() {
	}

	public static void roundedRect(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int radius, int color) {
		roundedRect(graphics, x, y, w, h, radius, color, color);
	}

	/**
	 * A rounded rectangle with anti-aliased corners.
	 *
	 * <p>The corners are the whole point. Insetting each row by a whole number of pixels - the
	 * obvious way to do this - leaves a visible staircase, so instead every pixel the arc passes
	 * through is drawn at partial alpha proportional to how much of it the shape actually covers.
	 * Only those boundary pixels are handled individually; the solid interior of each row still
	 * goes out as a single fill, which keeps the cost close to the naive version.
	 */
	public static void roundedRect(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int radius, int colorTop, int colorBottom) {
		if (w <= 0 || h <= 0) return;
		radius = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
		if (radius == 0) {
			fillRect(graphics, x, y, w, h, colorTop, colorBottom);
			return;
		}

		// Straight middle band: no arc crosses it, so it is one fill regardless of radius.
		fillRect(graphics, x, y + radius, w, h - 2 * radius,
			rowColor(colorTop, colorBottom, radius, h), rowColor(colorTop, colorBottom, h - radius, h));

		for (int row = 0; row < radius; row++) {
			int topRowY = y + row;
			int bottomRowY = y + h - 1 - row;
			int topColor = rowColor(colorTop, colorBottom, row, h);
			int bottomColor = rowColor(colorTop, colorBottom, h - 1 - row, h);

			// How far this row's arc reaches in from the edge, as an exact (fractional) position.
			double dy = radius - row - 0.5;
			double halfExtent = Math.sqrt(Math.max(0, radius * (double) radius - dy * dy));
			double edge = radius - halfExtent;
			int firstSolid = (int) Math.ceil(edge);

			// Solid span between the two arcs of this row.
			graphics.fill(x + firstSolid, topRowY, x + w - firstSolid, topRowY + 1, topColor);
			graphics.fill(x + firstSolid, bottomRowY, x + w - firstSolid, bottomRowY + 1, bottomColor);

			// The handful of pixels the arc actually passes through, at partial alpha.
			for (int px = 0; px < firstSolid; px++) {
				double coverage = coverage(px, row, radius);
				if (coverage <= 0) continue;
				int left = x + px;
				int right = x + w - 1 - px;
				int top = withAlphaScale(topColor, coverage);
				int bottom = withAlphaScale(bottomColor, coverage);
				graphics.fill(left, topRowY, left + 1, topRowY + 1, top);
				graphics.fill(right, topRowY, right + 1, topRowY + 1, top);
				graphics.fill(left, bottomRowY, left + 1, bottomRowY + 1, bottom);
				graphics.fill(right, bottomRowY, right + 1, bottomRowY + 1, bottom);
			}
		}
	}

	/** Rounded rectangle with a one-pixel border, both anti-aliased. */
	public static void roundedRectBordered(
		GuiGraphicsExtractor graphics, int x, int y, int w, int h, int radius,
		int colorTop, int colorBottom, int borderColor
	) {
		// Border first, fill inset over it: the ring left showing is exactly one pixel wide and
		// inherits the outer shape's smooth corners for free.
		roundedRect(graphics, x, y, w, h, radius, borderColor, borderColor);
		roundedRect(graphics, x + 1, y + 1, w - 2, h - 2, radius - 1, colorTop, colorBottom);
	}

	/** A pill-shaped on/off switch, the control that enables a module. */
	public static void toggleSwitch(GuiGraphicsExtractor graphics, int x, int y, int w, int h, boolean on) {
		int radius = h / 2;
		int track = on ? SWITCH_ON : SWITCH_OFF;
		roundedRect(graphics, x, y, w, h, radius, track, track);
		int knobSize = h - 4;
		int knobX = on ? x + w - knobSize - 2 : x + 2;
		roundedRect(graphics, knobX, y + 2, knobSize, knobSize, knobSize / 2, SWITCH_KNOB, SWITCH_KNOB);
	}

	/**
	 * How much of the pixel at (px, row) inside a corner box of the given radius the disc covers.
	 * Distance to the arc converted to coverage - exact enough at these sizes and cheap.
	 */
	private static double coverage(int px, int row, int radius) {
		if (radius <= 0) return 0;
		double dx = radius - px - 0.5;
		double dy = radius - row - 0.5;
		double distance = Math.sqrt(dx * dx + dy * dy);
		return Math.max(0, Math.min(1, radius - distance + 0.5));
	}

	private static void fillRect(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int colorTop, int colorBottom) {
		if (w <= 0 || h <= 0) return;
		if (colorTop == colorBottom) {
			graphics.fill(x, y, x + w, y + h, colorTop);
		} else {
			graphics.fillGradient(x, y, x + w, y + h, colorTop, colorBottom);
		}
	}

	private static int rowColor(int colorTop, int colorBottom, int row, int height) {
		return colorTop == colorBottom ? colorTop : lerpColor(colorTop, colorBottom, row / (float) height);
	}

	private static int withAlphaScale(int color, double scale) {
		int alpha = (int) Math.round(((color >>> 24) & 0xFF) * scale);
		return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
	}

	public static int lerpColor(int from, int to, float fraction) {
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
