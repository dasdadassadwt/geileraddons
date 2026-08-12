package geiler.addons.client.module.impl;

import geiler.addons.client.config.ModConfig;
import geiler.addons.client.gui.GuiTheme;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.ModuleAction;
import geiler.addons.client.module.SettingGroup;

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
			s.tracker, s.amethyst, s.midnight, s.forest);
		this.background = s.background;
		this.border = s.border;
		this.accent = s.accent;
		this.text = s.text;
		this.muted = s.muted;
		group(
			new SettingGroup("Colours", s.background, s.border, s.accent, s.text, s.muted),
			new SettingGroup("Presets", s.tracker, s.amethyst, s.midnight, s.forest)
		);
	}

	/** Just a carrier so the constructor can both build the settings and keep references to them. */
	private static final class Settings {
		final ColorSetting background = new ColorSetting("Background", 14, 14, 18, 230);
		final ColorSetting border = new ColorSetting("Border", 255, 255, 255, 51);
		final ColorSetting accent = new ColorSetting("Accent", 207, 207, 214, 255);
		final ColorSetting text = new ColorSetting("Text", 255, 255, 255, 255);
		final ColorSetting muted = new ColorSetting("Muted Text", 140, 140, 153, 255);

		final ModuleAction tracker = new ModuleAction("Tracker", () -> preset(Preset.TRACKER));
		final ModuleAction amethyst = new ModuleAction("Amethyst", () -> preset(Preset.AMETHYST));
		final ModuleAction midnight = new ModuleAction("Midnight", () -> preset(Preset.MIDNIGHT));
		final ModuleAction forest = new ModuleAction("Forest", () -> preset(Preset.FOREST));
	}

	/** @param values background, border, accent, text and muted, each as 0xAARRGGBB */
	private record Preset(int background, int border, int accent, int text, int muted) {
		/** The flat translucent look of the gift tracker panel, which is what the menu now matches. */
		static final Preset TRACKER = new Preset(0xE60E0E12, 0x33FFFFFF, 0xFFCFCFD6, 0xFFFFFFFF, 0xFF8C8C99);
		/** The purple scheme the menu shipped with before themes existed. */
		static final Preset AMETHYST = new Preset(0xF0261434, 0x33FFFFFF, 0xFF6C2BD9, 0xFFFFFFFF, 0xFF8A7FA0);
		static final Preset MIDNIGHT = new Preset(0xF00B1220, 0x33A8C7FF, 0xFF3B82F6, 0xFFEAF2FF, 0xFF7E8FA8);
		static final Preset FOREST = new Preset(0xEB0D1710, 0x33A8E6B0, 0xFF3FBF5F, 0xFFEDFBF0, 0xFF7E9B85);
	}

	private static void preset(Preset preset) {
		VisualModule module = INSTANCE;
		set(module.background, preset.background());
		set(module.border, preset.border());
		set(module.accent, preset.accent());
		set(module.text, preset.text());
		set(module.muted, preset.muted());
		module.refreshTheme();
		ModConfig.save();
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
}
