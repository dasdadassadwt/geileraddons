package geiler.addons.client.gui;

/**
 * Offline checks for the theme's selection outline.
 *
 * <p>The outline used to be derived from the accent, which is also what fills a selected macro node,
 * so on several presets the ring and the node were the same colour and the selection read as nothing
 * at all. These checks assert the replacement stays visible against both things it sits between: the
 * accent-filled node body and the fixed macro block colours.
 */
public final class ThemeContrastChecks {
	/** The nine VisualModule presets, including their reduced background alpha values. */
	private static final int[][] PRESETS = {
		{ 0xA2071925, 0xFF3A8198, 0xFF27CDE5, 0xFFF3FBFF, 0xFF9DB9C6 },
		{ 0x9E1A1430, 0xFF826BAC, 0xFF9B78F3, 0xFFF8F2FF, 0xFFB1A3C8 },
		{ 0xB40B1220, 0x33A8C7FF, 0xFF3B82F6, 0xFFEAF2FF, 0xFF7E8FA8 },
		{ 0xA011211A, 0xFF5B9B76, 0xFF51C987, 0xFFF0FFF4, 0xFFA0C4AA },
		{ 0x971A2635, 0xFF56A99B, 0xFF48DCC2, 0xFFF0FFFC, 0xFF9EC8C0 },
		{ 0x9D251718, 0xFFC77F66, 0xFFFF795F, 0xFFFFF5F0, 0xFFD2AAA0 },
		{ 0x9E1C1830, 0xFF967AC2, 0xFFE08EEB, 0xFFFFF4FF, 0xFFC4AACA },
		{ 0x82142735, 0xFF71A7B6, 0xFF63DDF1, 0xFFF4FCFF, 0xFFB2D3DE },
		{ 0x9D161B2B, 0xFFB6819B, 0xFFFF8EB7, 0xFFFFF5FA, 0xFFD2ADB9 }
	};

	private ThemeContrastChecks() { }

	public static void run() {
		checkSelectionOutline();
		checkPresets();
	}

	/** A light body must get the dark ring and a dark body the light one, per the rule. */
	private static void checkSelectionOutline() {
		check(GuiTheme.selectionOutline(0xFFFFFFFF) == 0xFF000000,
			"a white body is outlined in black");
		check(GuiTheme.selectionOutline(0xFF000000) == 0xFFFFFFFF,
			"a black body is outlined in white");
		check(GuiTheme.outlinesReadably(0xFFFFFFFF, GuiTheme.selectionOutline(0xFFFFFFFF)),
			"the outline reads against a white body");
		check(GuiTheme.outlinesReadably(0xFF000000, GuiTheme.selectionOutline(0xFF000000)),
			"the outline reads against a black body");
		check(GuiTheme.outlinesReadably(0xFF3FBF5F, GuiTheme.selectionOutline(0xFF3FBF5F)),
			"the outline reads against a mid-brightness body");
	}

	/** Whatever preset is active, the selected macro node has to be visible. */
	private static void checkPresets() {
		for (int[] preset : PRESETS) {
			GuiTheme.apply(preset[0], preset[1], preset[2], preset[3], preset[4]);
			int accent = preset[2];
			check(GuiTheme.BORDER == preset[1],
				"the panel border preserves the configured ARGB value for preset " + hex(accent));
			int expectedCardBorder = ((alpha(preset[1]) / 2) << 24) | (preset[1] & 0x00FFFFFF);
			check(GuiTheme.CARD_BORDER == expectedCardBorder,
				"the card border derives its opacity from the configured border for preset " + hex(accent));
			check(GuiTheme.PANEL_TOP == preset[0]
				&& alpha(GuiTheme.PANEL_BOTTOM) == alpha(preset[0])
				&& alpha(GuiTheme.MODULE_PANEL_TOP) == alpha(preset[0]),
				"panel surface gradients preserve the selected background opacity for preset " + hex(accent));
			check(alpha(GuiTheme.CARD_BG) == 11 && alpha(GuiTheme.CARD_BG_HOVER) == 22,
				"card surfaces retain their translucent theme overlays for preset " + hex(accent));
			check(maxChannel(GuiTheme.PANEL_TOP) < 80 && maxChannel(GuiTheme.MODULE_PANEL_TOP) < 100,
				"dark panel surfaces stay dark for preset " + hex(accent));
			check(GuiTheme.labelsReadably(GuiTheme.searchIconColor(), GuiTheme.PANEL_TOP),
				"the search glyph stays distinct from the field surface of preset " + hex(accent));
			check(GuiTheme.outlinesReadably(accent, GuiTheme.SELECTION_OUTLINE),
				"the selection outline reads against the accent of preset " + hex(accent));
			check(GuiTheme.outlinesReadably(accent, GuiTheme.selectionOutline(accent)),
				"a derived outline reads against the accent of preset " + hex(accent));
			// With Theme Macro Colors off the node keeps its own Scratch colour, and the ring is
			// derived from that instead, so every block colour has to work too.
			for (int colour : ScratchMacroEditorScreen.MACRO_CATEGORY_COLORS) {
				check(GuiTheme.outlinesReadably(colour,
					GuiTheme.selectionOutline(colour)),
					"a derived outline reads against macro colour " + hex(colour)
						+ " on preset " + hex(accent));
			}
			checkLabels(accent);
		}
		// Restore the configured default so a later check does not inherit the last preset.
		GuiTheme.apply(0x810B1220, 0xFF191D24, 0xFF3B82F6, 0xFFEAF2FF, 0xFF7E8FA8);
	}

	/**
	 * Rows filled with the accent need the accent-safe label, not the primary text colour. The default
	 * Tracker preset is the case that exposed it: a near-white accent under white text.
	 */
	private static void checkLabels(int accent) {
		check(GuiTheme.labelsReadably(GuiTheme.TEXT_ON_ACCENT, GuiTheme.BUTTON_HOVER),
			"the accent-safe label reads on a hovered button of preset " + hex(accent));
		check(GuiTheme.labelsReadably(GuiTheme.TEXT_ON_ACCENT, GuiTheme.CATEGORY_SELECTED),
			"the accent-safe label reads on the selected category of preset " + hex(accent));
		check(GuiTheme.labelsReadably(GuiTheme.TEXT_ON_ACCENT, GuiTheme.BUTTON_BG),
			"the accent-safe label reads on an un-hovered button of preset " + hex(accent));
		// Sanity: the rule must actually be able to fail, or it is not guarding anything.
		check(!GuiTheme.labelsReadably(accent, accent),
			"a label the same colour as its surface is rejected for preset " + hex(accent));
	}

	private static String hex(int colour) {
		return String.format("#%08X", colour);
	}

	private static int alpha(int colour) {
		return colour >>> 24;
	}

	private static int maxChannel(int colour) {
		return Math.max((colour >>> 16) & 0xFF,
			Math.max((colour >>> 8) & 0xFF, colour & 0xFF));
	}

	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}
}
