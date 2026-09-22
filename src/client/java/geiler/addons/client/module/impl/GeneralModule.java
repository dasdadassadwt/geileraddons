package geiler.addons.client.module.impl;

import geiler.addons.client.macro.MacroRunner;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.ModuleAction;
import geiler.addons.client.module.SettingGroup;

/**
 * Mod-wide switches that belong to no single overlay: the Cheats master gate, the macro chat
 * behaviour, and the update preference that used to live in the config file.
 *
 * <p>This module draws nothing, and its own switch deliberately does not gate what it holds. The
 * Cheats value is read straight from the setting, so an install that never enables this card still
 * cannot end up with cheat behaviour nobody asked for, while a player who turns Cheats on keeps it
 * on across a restart like any other setting.
 */
public final class GeneralModule extends Module {
	public static final GeneralModule INSTANCE = new GeneralModule();

	private final BooleanSetting cheats;
	private final BooleanSetting chatTriggersInTextScreens;
	private final BooleanSetting checkForUpdates;

	private GeneralModule() {
		this(new Settings());
	}

	private GeneralModule(Settings s) {
		super("General", "Mod-wide switches: the Cheats gate that forces cheat-style options safe, the macro "
			+ "chat behaviour, and whether the mod checks GitHub for a newer release.", Category.MISCELLANEOUS,
			s.cheats, s.chatTriggersInTextScreens, s.replayBlocked, s.checkForUpdates);
		this.cheats = s.cheats;
		this.chatTriggersInTextScreens = s.chatTriggersInTextScreens;
		this.checkForUpdates = s.checkForUpdates;
		group(
			new SettingGroup("Safety", s.cheats),
			new SettingGroup("Macros", s.chatTriggersInTextScreens, s.replayBlocked),
			new SettingGroup("Updates", s.checkForUpdates)
		);
	}

	/** Carrier so the constructor can build the settings and keep references to the same instances. */
	private static final class Settings {
		/**
		 * Off by default. Everything it covers is forced to its safe behaviour while this is off,
		 * so a fresh install never sees through terrain and never runs an automated workflow.
		 */
		final BooleanSetting cheats = new BooleanSetting("Cheats", false);
		/**
		 * Off by default: a macro firing from chat while the player is typing would inject inputs
		 * into whatever has the keyboard. Turning it on is the player's explicit choice.
		 */
		final BooleanSetting chatTriggersInTextScreens =
			new BooleanSetting("Chat Triggers In Text Screens", false);
		final ModuleAction replayBlocked = new ModuleAction("Replay Last Blocked Macro",
			"Starts the chat-triggered stack that was most recently refused. The module hotkey does the same.",
			MacroRunner::replayLastBlocked);
		final BooleanSetting checkForUpdates = new BooleanSetting("Check for Updates", true);
	}

	public BooleanSetting cheats() {
		return cheats;
	}

	public boolean cheatsEnabled() {
		return cheats.value();
	}

	public BooleanSetting chatTriggersInTextScreens() {
		return chatTriggersInTextScreens;
	}

	public BooleanSetting checkForUpdates() {
		return checkForUpdates;
	}
}
