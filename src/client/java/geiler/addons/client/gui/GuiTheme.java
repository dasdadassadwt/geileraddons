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
	/**
	 * The ring around a selected node, row or field.
	 *
	 * <p>Not the accent: the thing being outlined is usually filled with the accent too, and two
	 * identical colours read as no outline at all. Only safe to draw over an opaque fill - it is a
	 * solid value, so a translucent surface would show it through rather than through it.
	 */
	public static int SELECTION_OUTLINE;
	public static int BUTTON_BG;
	public static int BUTTON_HOVER;
	public static int SCROLLBAR;
	/** A quiet accent line used to give the two panels a deliberate top edge. */
	public static int PANEL_HIGHLIGHT;

	/** How far a selection outline's brightness must sit from the shape it outlines, as a 0-1 luminance. */
	private static final float MIN_OUTLINE_CONTRAST = 0.35f;

	/**
	 * How far a label's brightness must sit from the surface behind it, as a 0-1 luminance.
	 *
	 * <p>Lower than the outline threshold because a glyph carries its own shape, while an outline that
	 * is close in brightness simply stops reading as a separate edge.
	 */
	private static final float MIN_LABEL_CONTRAST = 0.25f;

	static {
		// Something has to be on the palette before the config is read, since a screen could be
		// drawn first. VisualModule overwrites this the moment its settings load.
		apply(0x810B1220, 0xFF191D24, 0xFF3B82F6, 0xFFEAF2FF, 0xFF7E8FA8);
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
		// Preserve the alpha selected in the theme settings. Several presets intentionally use a
		// glassy, low-opacity outline; forcing it opaque makes every panel edge much brighter than
		// the configured theme, especially across the many surfaces in the macro editor.
		BORDER = border;

		CATEGORY_SELECTED = opaque(accent);
		CATEGORY_HOVER = withAlpha(accent, 18);
		// Overlays are tinted from the text colour rather than hardcoded white, so a light theme
		// gets darker cards instead of invisible ones.
		CARD_BG = withAlpha(text, 11);
		CARD_BG_HOVER = withAlpha(text, 22);
		CARD_BG_ENABLED = withAlpha(accent, 22);
		CARD_BORDER = withAlpha(border, alpha(border) / 2);
		CARD_BORDER_ENABLED = opaque(accent);
		GROUP_HEADER = withAlpha(text, 12);

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
		PANEL_HIGHLIGHT = withAlpha(accent, 150);
		// Derived rather than transparent: it has to be drawn as a ring over a filled node, so a
		// translucent colour would just tint whatever it lands on.
		SELECTION_OUTLINE = selectionOutline(accent);
	}

	/**
	 * A selection outline that cannot disappear into the thing it selects.
	 *
	 * <p>Filling a shape with a colour and then outlining it in the same colour hides the outline
	 * completely, which is what happened whenever a node's body colour came from the theme accent.
	 * Rather than picking one colour that happens to work, this keeps the two apart by construction:
	 * a light body gets the dark halo, a dark body gets the light one, and the chosen value is either
	 * fully black or fully white, so it also stands out against the panel behind it.
	 */
	public static int selectionOutline(int reference) {
		return luminance(reference) > 0.5f ? 0xFF000000 : 0xFFFFFFFF;
	}

	/** Whether the outline a shape should draw is far enough from its body colour to be seen. */
	public static boolean outlinesReadably(int body, int outline) {
		return Math.abs(luminance(body) - luminance(outline)) >= MIN_OUTLINE_CONTRAST;
	}

	/**
	 * Whether a label drawn on a filled surface is far enough from it to be read.
	 *
	 * <p>States the rule the accent-safe label exists for: a row filled with the accent needs
	 * {@link #TEXT_ON_ACCENT}, not the primary text colour, or on a light accent the two are nearly
	 * the same shade.
	 */
	public static boolean labelsReadably(int text, int fill) {
		return Math.abs(luminance(text) - luminance(fill)) >= MIN_LABEL_CONTRAST;
	}

	/** A tint for the search glyph that remains distinct from the search field on every theme. */
	public static int searchIconColor() {
		int surface = compositeOver(GROUP_HEADER, compositeOver(PANEL_TOP, 0xFF000000));
		if (labelsReadably(TEXT_PRIMARY, surface)) return TEXT_PRIMARY;
		if (labelsReadably(TEXT_SECONDARY, surface)) return TEXT_SECONDARY;
		return luminance(surface) > 0.5f ? 0xFF000000 : 0xFFFFFFFF;
	}

	private static int compositeOver(int foreground, int background) {
		int a = alpha(foreground);
		int inverse = 255 - a;
		int r = (channel(foreground, 16) * a + channel(background, 16) * inverse + 127) / 255;
		int g = (channel(foreground, 8) * a + channel(background, 8) * inverse + 127) / 255;
		int b = (channel(foreground, 0) * a + channel(background, 0) * inverse + 127) / 255;
		return 0xFF000000 | (r << 16) | (g << 8) | b;
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

	/** Applies an animation opacity without changing the hue or accidentally brightening a color. */
	public static int withOpacity(int color, float opacity) {
		int oldAlpha = alpha(color);
		int newAlpha = Math.round(oldAlpha * Math.max(0.0f, Math.min(1.0f, opacity)));
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
		roundedRectBordered(graphics, x, y, w, h, radius, colorTop, colorBottom, borderColor, 1);
	}

	/**
	 * Rounded rectangle with a border of a chosen width, both anti-aliased.
	 *
	 * <p>A selection ring is only obvious if it is thick enough to catch the eye, which is why the
	 * width is a parameter rather than always one pixel.
	 */
	public static void roundedRectBordered(
		GuiGraphicsExtractor graphics, int x, int y, int w, int h, int radius,
		int colorTop, int colorBottom, int borderColor, int borderWidth
	) {
		int width = Math.max(1, borderWidth);
		// Border first, fill inset over it: the ring left showing is exactly as wide as requested and
		// inherits the outer shape's smooth corners for free.
		roundedRect(graphics, x, y, w, h, radius, borderColor, borderColor);
		roundedRect(graphics, x + width, y + width, w - width * 2, h - width * 2,
			Math.max(0, radius - width), colorTop, colorBottom);
	}

	/** Draws only the antialiased one-pixel perimeter of a rounded rectangle. */
	public static void roundedRectOutline(GuiGraphicsExtractor graphics, int x, int y, int w, int h,
		int radius, int color) {
		if (w <= 0 || h <= 0) return;
		if (w <= 2 || h <= 2) {
			roundedRect(graphics, x, y, w, h, radius, color);
			return;
		}
		radius = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
		if (radius == 0) {
			graphics.fill(x, y, x + w, y + 1, color);
			graphics.fill(x, y + h - 1, x + w, y + h, color);
			graphics.fill(x, y + 1, x + 1, y + h - 1, color);
			graphics.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
			return;
		}

		graphics.fill(x + radius, y, x + w - radius, y + 1, color);
		graphics.fill(x + radius, y + h - 1, x + w - radius, y + h, color);
		graphics.fill(x, y + radius, x + 1, y + h - radius, color);
		graphics.fill(x + w - 1, y + radius, x + w, y + h - radius, color);
		for (int row = 0; row < radius; row++) {
			for (int px = 0; px < radius; px++) {
				double coverage = Math.max(0.0, coverage(px, row, radius)
					- coverage(px - 1, row - 1, radius - 1));
				if (coverage <= 0.0) continue;
				int edge = withAlphaScale(color, coverage);
				int leftX = x + px;
				int rightX = x + w - 1 - px;
				int topY = y + row;
				int bottomY = y + h - 1 - row;
				graphics.fill(leftX, topY, leftX + 1, topY + 1, edge);
				graphics.fill(rightX, topY, rightX + 1, topY + 1, edge);
				graphics.fill(leftX, bottomY, leftX + 1, bottomY + 1, edge);
				graphics.fill(rightX, bottomY, rightX + 1, bottomY + 1, edge);
			}
		}
	}

	/** A pill-shaped on/off switch, the control that enables a module. */
	public static void toggleSwitch(GuiGraphicsExtractor graphics, int x, int y, int w, int h, boolean on) {
		toggleSwitch(graphics, x, y, w, h, on ? 1.0f : 0.0f);
	}

	/**
	 * Animated switch variant. The track colour and knob position use the same fraction so a
	 * setting feels like it physically travels to its new state instead of teleporting there.
	 */
	public static void toggleSwitch(GuiGraphicsExtractor graphics, int x, int y, int w, int h, float position) {
		position = Math.max(0.0f, Math.min(1.0f, position));
		int radius = h / 2;
		int track = lerpColor(SWITCH_OFF, SWITCH_ON, position);
		roundedRectBordered(graphics, x, y, w, h, radius, track, track, SWITCH_BORDER);
		int knobSize = h - 4;
		int knobX = Math.round(lerp(x + 2, x + w - knobSize - 2, position));
		// The knob gets its own ring. Without one it can be the same colour as the track it sits on -
		// setting Text to the accent made an ON switch show no knob at all - and a ring derived from
		// the track underneath it stays visible whatever the two colours are.
		roundedRectBordered(graphics, knobX, y + 2, knobSize, knobSize, knobSize / 2,
			SWITCH_KNOB, SWITCH_KNOB, selectionOutline(track));
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

	private static float lerp(float from, float to, float fraction) {
		return from + (to - from) * fraction;
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
