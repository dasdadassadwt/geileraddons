package geiler.addons.client.module.impl;

import geiler.addons.client.module.ModuleKeybind;

/** Resolves the two retired Dungeon Mob ESP hotkeys into their combined module setting. */
public final class DungeonMobEspKeybindMigration {
	private DungeonMobEspKeybindMigration() { }

	public record Resolution(ModuleKeybind keybind, boolean migrated, boolean conflict) { }

	public static Resolution resolve(boolean currentBindPresent, ModuleKeybind current,
		ModuleKeybind starredLegacy, ModuleKeybind minibossLegacy) {
		if (currentBindPresent) {
			return new Resolution(current == null ? ModuleKeybind.NONE : current, false, false);
		}

		boolean starredBound = starredLegacy != null && starredLegacy.isBound();
		boolean minibossBound = minibossLegacy != null && minibossLegacy.isBound();
		if (starredBound && minibossBound && !starredLegacy.conflictsWith(minibossLegacy)) {
			return new Resolution(ModuleKeybind.NONE, true, true);
		}
		if (!starredBound && !minibossBound) {
			return new Resolution(ModuleKeybind.NONE, false, false);
		}
		return new Resolution(starredBound ? starredLegacy : minibossLegacy, true, false);
	}
}
