package geiler.addons.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Shared palette and panel drawing for the mod's screens, so they look like one GUI.
 *
 * <p>The palette is derived from five colours rather than stored as thirty. Every hover tint,
 * card fill and slider track is a function of those five, so a theme cannot be half-applied and a
 * new colour can never be left behind on the old scheme. {@code VisualModule} owns the five and
 * calls {@link #apply}; nothing else should write these fields.
 */
public final class GuiTheme {
	public static final int RADIUS = 8;
	/** Corner radius for the small stuff - rows, cards, swatches, buttons. */
	public static final int RADIUS_SMALL = 5;

	/** Meaning, not decoration: a warning stops reading as one if the theme can recolour it. */
	public static final int TEXT_ERROR = 0xFFFF6B6B;
	public static final int TEXT_WARN = 0xFFFFC55C;
	public static final int DANGER_BG = 0xFF7A1F2B;
	public static final int DANGER_HOVER = 0xFFC0303F;
	/** Dims the screen behind a modal dialog. */
	public static final int DIALOG_SHADE = 0xB0000000;
	public static final int SLIDER_TRACK = 0xA0000000;

	public static int PANEL_TOP;
	public static int PANEL_BOTTOM;
	public static int MODULE_PANEL_TOP;
	public static int MODULE_PANEL_BOTTOM;
	public static int BORDER;
	public static int CATEGORY_SELECTED;
	public static int CATEGORY_HOVER;
	/** Card background: raised just enough off the panel to read as a separate surface. */
	public static int CARD_BG;
	public static int CARD_BG_HOVER;
	public static int CARD_BG_ENABLED;
	public static int CARD_BORDER;
	public static int CARD_BORDER_ENABLED;
	public static int GROUP_HEADER;
	public static int TEXT_PRIMARY;
	public static int TEXT_SECONDARY;
	public static int TEXT_MUTED;
	/** Readable on top of the accent, whichever way the accent's brightness went. */
	public static int TEXT_ON_ACCENT;
	public static int SLIDER_FILL;
	public static int SWITCH_OFF;
	public static int SWITCH_ON;
	/**
	 * Ring round the switch.
	 *
	 * <p>Both the switch track and an enabled card's background are derived from the accent, so on
	 * a card that is switched on the control was very nearly the colour it sat on. Drawn from the
	 * text colour instead, which is the one thing guaranteed to read against both.
	 */
	public static int SWITCH_BORDER;
	public static int SWITCH_KNOB;
	public static int BUTTON_BG;
	public static int BUTTON_HOVER;
	public static int SCROLLBAR;

	static {
		// Something has to be on the palette before the config is read, since a screen could be
		// drawn first. VisualModule overwrites this the moment its settings load.
		apply(0xE60E0E12, 0x33FFFFFF, 0xFFCFCFD6, 0xFFFFFFFF, 0xFF8C8C99);
	}

	private GuiTheme() {
	}

	/**
	 * Rebuilds the palette from the five colours a theme is made of.
	 *
	 * @param background panel fill, alpha included - this is what makes the GUI see-through
	 * @param border     panel and card outlines
	 * @param accent     selection, toggles, sliders and buttons
	 * @param text       primary text, and the tint every translucent overlay is mixed from
	 * @param muted      secondary text
	 */
	public static void apply(int background, int border, int accent, int text, int muted) {
		PANEL_TOP = background;
		PANEL_BOTTOM = shade(background, -0.22f);
		MODULE_PANEL_TOP = shade(background, 0.10f);
		MODULE_PANEL_BOTTOM = shade(background, -0.10f);
		BORDER = border;

		CATEGORY_SELECTED = opaque(accent);
		CATEGORY_HOVER = withAlpha(text, 48);
		// Overlays are tinted from the text colour rather than hardcoded white, so a light theme
		// gets darker cards instead of invisible ones.
		CARD_BG = withAlpha(text, 40);
		CARD_BG_HOVER = withAlpha(text, 64);
		CARD_BG_ENABLED = withAlpha(accent, 110);
		CARD_BORDER = withAlpha(border, alpha(border) / 2);
		CARD_BORDER_ENABLED = opaque(accent);
		GROUP_HEADER = withAlpha(text, 28);

		TEXT_PRIMARY = opaque(text);
		TEXT_SECONDARY = lerpColor(opaque(text), opaque(muted), 0.5f);
		TEXT_MUTED = opaque(muted);
		TEXT_ON_ACCENT = luminance(accent) > 0.55f ? 0xFF101014 : 0xFFFFFFFF;

		SLIDER_FILL = opaque(accent);
		SWITCH_OFF = opaque(shade(background, 0.45f));
		SWITCH_ON = opaque(accent);
		SWITCH_BORDER = withAlpha(text, 150);
		SWITCH_KNOB = opaque(text);
		BUTTON_BG = opaque(shade(accent, -0.38f));
		BUTTON_HOVER = opaque(accent);
		SCROLLBAR = withAlpha(text, 96);
	}

	/** Toward white for a positive amount, toward black for a negative one; alpha is kept. */
	private static int shade(int color, float amount) {
		int r = channel(color, 16);
		int g = channel(color, 8);
		int b = channel(color, 0);
		if (amount >= 0) {
			r += Math.round((255 - r) * amount);
			g += Math.round((255 - g) * amount);
			b += Math.round((255 - b) * amount);
		} else {
			r += Math.round(r * amount);
			g += Math.round(g * amount);
			b += Math.round(b * amount);
		}
		return (color & 0xFF000000) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
	}

	private static int withAlpha(int color, int newAlpha) {
		return (clamp(newAlpha) << 24) | (color & 0x00FFFFFF);
	}

	private static int opaque(int color) {
		return 0xFF000000 | (color & 0x00FFFFFF);
	}

	private static int alpha(int color) {
		return (color >>> 24) & 0xFF;
	}

	private static int channel(int color, int shift) {
		return (color >>> shift) & 0xFF;
	}

	/** Perceptual, so a yellow accent counts as light even though its blue channel is nothing. */
	private static float luminance(int color) {
		return (0.2126f * channel(color, 16) + 0.7152f * channel(color, 8) + 0.0722f * channel(color, 0)) / 255.0f;
	}

	private static int clamp(int value) {
		return Math.max(0, Math.min(255, value));
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
		roundedRectBordered(graphics, x, y, w, h, radius, track, track, SWITCH_BORDER);
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
