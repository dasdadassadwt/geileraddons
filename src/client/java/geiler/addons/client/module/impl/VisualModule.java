package geiler.addons.client.module.impl;

import geiler.addons.client.config.ModConfig;
import geiler.addons.client.gui.GuiTheme;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ChoiceSetting;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.ModuleAction;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.module.BooleanSetting;

/**
 * The mod's one theme, shared by the click GUI and every HUD panel.
 *
 * <p>Deliberately a single theme rather than one per module: the point of the settings living
 * here is that changing a colour changes it everywhere at once. Modules keep their own colours
 * only for things that carry meaning in the world - waypoint states, solver directions - which
 * are signals, not decoration, and would stop being readable if a theme could repaint them.
 */
public final class VisualModule extends Module {
	public static final VisualModule INSTANCE = new VisualModule();

	private final ColorSetting background;
	private final ColorSetting border;
	private final ColorSetting accent;
	private final ColorSetting text;
	private final ColorSetting muted;
	private final ColorSetting iconColor;
	private final ChoiceSetting clickGuiMotion;
	private final BooleanSetting themeSurfaces;

	private int lastBackground;
	private int lastBorder;
	private int lastAccent;
	private int lastText;
	private int lastMuted;
	private boolean applied;

	private VisualModule() {
		this(new Settings());
	}

	private VisualModule(Settings s) {
		super("Theme", "Colours for the menu and every HUD panel. Pick a preset or build your own.",
			Category.VISUAL,
			s.background, s.border, s.accent, s.text, s.muted,
			s.iconColor, s.tracker, s.amethyst, s.midnight, s.forest, s.aurora, s.ember, s.orchid,
			s.iceGlass, s.roseGlass, s.clickGuiMotion, s.themeSurfaces);
		this.background = s.background;
		this.border = s.border;
		this.accent = s.accent;
		this.text = s.text;
		this.muted = s.muted;
		this.iconColor = s.iconColor;
		this.clickGuiMotion = s.clickGuiMotion;
		this.themeSurfaces = s.themeSurfaces;
		group(
			new SettingGroup("Colours", s.background, s.border, s.accent, s.text, s.muted, s.iconColor),
			new SettingGroup("Presets", s.tracker, s.amethyst, s.midnight, s.forest,
				s.aurora, s.ember, s.orchid, s.iceGlass, s.roseGlass),
			new SettingGroup("Click GUI", s.clickGuiMotion),
			new SettingGroup("Surfaces", s.themeSurfaces)
		);
	}

	/** Just a carrier so the constructor can both build the settings and keep references to them. */
	private static final class Settings {
		final ColorSetting background = new ColorSetting("Background", 11, 18, 32, 129);
		final ColorSetting border = new ColorSetting("Border", 25, 29, 36, 255);
		final ColorSetting accent = new ColorSetting("Accent", 59, 130, 246, 255);
		final ColorSetting text = new ColorSetting("Text", 234, 242, 255, 255);
		final ColorSetting muted = new ColorSetting("Muted Text", 126, 143, 168, 255);
		final ColorSetting iconColor = new ColorSetting("Icon color", 234, 242, 255);
		final ChoiceSetting clickGuiMotion = new ChoiceSetting("Motion", "Expressive", "None", "Reduced", "Expressive");
		/**
		 * Renamed from "Theme Macro Colors", which did not describe what it does.
		 *
		 * <p>The toggle also decides the Inventory Buttons surface, which its old label and its old
		 * "Macro Editor" group did not claim - a described-behaviour mismatch rather than a hidden
		 * feature, so the row is named for both surfaces and grouped under "Surfaces". Renaming moves
		 * the config key, and {@code ModConfig} carries the old value across once.
		 */
		final BooleanSetting themeSurfaces = new BooleanSetting("Theme Macro + Inventory Colors", true);

		final ModuleAction tracker = new ModuleAction("Tracker", () -> preset(Preset.TRACKER));
		final ModuleAction amethyst = new ModuleAction("Amethyst", () -> preset(Preset.AMETHYST));
		final ModuleAction midnight = new ModuleAction("Midnight", () -> preset(Preset.MIDNIGHT));
		final ModuleAction forest = new ModuleAction("Forest", () -> preset(Preset.FOREST));
		final ModuleAction aurora = new ModuleAction("Aurora", () -> preset(Preset.AURORA));
		final ModuleAction ember = new ModuleAction("Ember", () -> preset(Preset.EMBER));
		final ModuleAction orchid = new ModuleAction("Orchid", () -> preset(Preset.ORCHID));
		final ModuleAction iceGlass = new ModuleAction("Ice Glass", () -> preset(Preset.ICE_GLASS));
		final ModuleAction roseGlass = new ModuleAction("Rose Glass", () -> preset(Preset.ROSE_GLASS));
	}

	/** Preset background alpha is reduced to 75% of its former value; other channels stay unchanged. */
	static record Preset(int background, int border, int accent, int text, int iconColor, int muted) {
		static final Preset TRACKER = new Preset(0xA2071925, 0xFF3A8198, 0xFF27CDE5, 0xFFF3FBFF, 0xFFF3FBFF, 0xFF9DB9C6);
		static final Preset AMETHYST = new Preset(0x9E1A1430, 0xFF826BAC, 0xFF9B78F3, 0xFFF8F2FF, 0xFFF8F2FF, 0xFFB1A3C8);
		static final Preset MIDNIGHT = new Preset(0xB40B1220, 0x33A8C7FF, 0xFF3B82F6, 0xFFEAF2FF, 0xFFEAF2FF, 0xFF7E8FA8);
		static final Preset FOREST = new Preset(0xA011211A, 0xFF5B9B76, 0xFF51C987, 0xFFF0FFF4, 0xFFF0FFF4, 0xFFA0C4AA);
		static final Preset AURORA = new Preset(0x971A2635, 0xFF56A99B, 0xFF48DCC2, 0xFFF0FFFC, 0xFFF0FFFC, 0xFF9EC8C0);
		static final Preset EMBER = new Preset(0x9D251718, 0xFFC77F66, 0xFFFF795F, 0xFFFFF5F0, 0xFFFFF5F0, 0xFFD2AAA0);
		static final Preset ORCHID = new Preset(0x9E1C1830, 0xFF967AC2, 0xFFE08EEB, 0xFFFFF4FF, 0xFFFFF4FF, 0xFFC4AACA);
		static final Preset ICE_GLASS = new Preset(0x82142735, 0xFF71A7B6, 0xFF63DDF1, 0xFFF4FCFF, 0xFFF4FCFF, 0xFFB2D3DE);
		static final Preset ROSE_GLASS = new Preset(0x9D161B2B, 0xFFB6819B, 0xFFFF8EB7, 0xFFFFF5FA, 0xFFFFF5FA, 0xFFD2ADB9);
	}

	private static void preset(Preset preset) {
		applyPreset(preset);
		ModConfig.markDirty();
	}

	/** Applies the preset values; the action wrapper separately records the config change. */
	static void applyPreset(Preset preset) {
		VisualModule module = INSTANCE;
		set(module.background, preset.background());
		set(module.border, preset.border());
		set(module.accent, preset.accent());
		set(module.text, preset.text());
		set(module.iconColor, preset.iconColor());
		set(module.muted, preset.muted());
		module.refreshTheme();
	}

	private static void set(ColorSetting setting, int argb) {
		setting.set((argb >>> 16) & 0xFF, (argb >>> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF);
	}

	/**
	 * Pushes the colours into {@link GuiTheme} if any of them moved.
	 *
	 * <p>Pulled at the top of every draw rather than pushed on change: a slider drag mutates the
	 * setting directly with no hook to hang a callback on, and doing it on the tick instead would
	 * make dragging a colour feel a frame or two behind the cursor. Five comparisons per frame is
	 * cheaper than either alternative.
	 */
	public void refreshTheme() {
		int backgroundArgb = background.argb();
		int borderArgb = border.argb();
		int accentArgb = accent.argb();
		int textArgb = text.argb();
		int mutedArgb = muted.argb();
		if (applied && backgroundArgb == lastBackground && borderArgb == lastBorder
			&& accentArgb == lastAccent && textArgb == lastText && mutedArgb == lastMuted) {
			return;
		}
		lastBackground = backgroundArgb;
		lastBorder = borderArgb;
		lastAccent = accentArgb;
		lastText = textArgb;
		lastMuted = mutedArgb;
		applied = true;
		GuiTheme.apply(backgroundArgb, borderArgb, accentArgb, textArgb, mutedArgb);
	}

	public ChoiceSetting clickGuiMotion() {
		return clickGuiMotion;
	}

	/** User-editable RGB tint for monochrome module icons. */
	public ColorSetting iconColorSetting() {
		return iconColor;
	}

	/** Whether themed surfaces keep the accent instead of their own fixed colours. */
	public BooleanSetting themeSurfaces() {
		return themeSurfaces;
	}

	@Override public boolean showsToggleControl() { return false; }
	@Override public boolean showsKeybindControl() { return false; }
}
