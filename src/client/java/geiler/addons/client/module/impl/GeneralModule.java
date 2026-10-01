package geiler.addons.client.module.impl;

import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.SettingGroup;

/** Mod-wide switches that belong to no single overlay. */
public final class GeneralModule extends Module {
	public static final GeneralModule INSTANCE = new GeneralModule();

	private final BooleanSetting chatTriggersInTextScreens;
	private final BooleanSetting checkForUpdates;
	private final BooleanSetting islandDetectionApi;
	private final BooleanSetting availabilityNotices;

	private GeneralModule() {
		this(new Settings());
	}

	private GeneralModule(Settings s) {
		super("General", "Mod-wide switches for macro chat behaviour, island detection, and whether the mod checks GitHub for a newer "
			+ "release.", Category.MISCELLANEOUS,
			s.chatTriggersInTextScreens, s.checkForUpdates, s.islandDetectionApi,
			s.availabilityNotices);
		this.chatTriggersInTextScreens = s.chatTriggersInTextScreens;
		this.checkForUpdates = s.checkForUpdates;
		this.islandDetectionApi = s.islandDetectionApi;
		this.availabilityNotices = s.availabilityNotices;
		group(
			new SettingGroup("Macros", s.chatTriggersInTextScreens),
			new SettingGroup("Island Detection", s.islandDetectionApi),
			new SettingGroup("Updates", s.checkForUpdates),
			new SettingGroup("Click GUI", s.availabilityNotices)
		);
	}

	/** Carrier so the constructor can build the settings and keep references to the same instances. */
	private static final class Settings {
		/**
		 * Off by default: a macro firing from chat while the player is typing would inject inputs
		 * into whatever has the keyboard. Turning it on is the player's explicit choice.
		 */
		final BooleanSetting chatTriggersInTextScreens =
			new BooleanSetting("Chat Triggers In Text Screens", false);
		final BooleanSetting checkForUpdates = new BooleanSetting("Check for Updates", true);
		/**
		 * On by default. Every island-gated module depends on it, so turning it off makes those
		 * modules inert; they now say so in chat rather than doing nothing in silence.
		 */
		final BooleanSetting islandDetectionApi = new BooleanSetting("Island Detection API", true);
		final BooleanSetting availabilityNotices = new BooleanSetting("Show Availability Notices", true);
	}

	public BooleanSetting islandDetectionApi() {
		return islandDetectionApi;
	}

	public BooleanSetting chatTriggersInTextScreens() {
		return chatTriggersInTextScreens;
	}

	public BooleanSetting checkForUpdates() {
		return checkForUpdates;
	}

	public BooleanSetting availabilityNotices() {
		return availabilityNotices;
	}

	@Override public boolean showsToggleControl() { return false; }
	@Override public boolean showsKeybindControl() { return false; }
}
