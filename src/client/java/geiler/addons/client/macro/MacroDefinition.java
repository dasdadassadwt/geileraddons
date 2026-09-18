package geiler.addons.client.macro;

import geiler.addons.client.location.Island;
import geiler.addons.client.module.ModuleKeybind;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/** User-owned macro metadata and workflow. */
public final class MacroDefinition {
	private final int id;
	private String name;
	private boolean enabled = true;
	private ModuleKeybind keybind = ModuleKeybind.NONE;
	/** New macros are useful in both the world and ordinary container screens by default. */
	private MacroTriggerContext triggerContext = MacroTriggerContext.ANY_NON_TEXT_SCREEN;
	private boolean islandRestricted;
	private final EnumSet<Island> islands = EnumSet.noneOf(Island.class);
	private final List<MacroStep> steps = new ArrayList<>();

	public MacroDefinition(int id) {
		this.id = Math.max(0, id);
		this.name = "Macro " + this.id;
	}

	public int id() { return id; }
	public String name() { return name; }
	public void setName(String value) { name = value == null || value.isBlank() ? "Macro " + id : value.substring(0, Math.min(48, value.length())); }
	public boolean enabled() { return enabled; }
	public void setEnabled(boolean value) { enabled = value; }
	public ModuleKeybind keybind() { return keybind; }
	public void setKeybind(ModuleKeybind value) { keybind = value == null ? ModuleKeybind.NONE : value; }
	public MacroTriggerContext triggerContext() { return triggerContext; }
	public void setTriggerContext(MacroTriggerContext value) { triggerContext = value == null ? MacroTriggerContext.WORLD_ONLY : value; }
	public boolean islandRestricted() { return islandRestricted; }
	public void setIslandRestricted(boolean value) { islandRestricted = value; }
	public EnumSet<Island> islands() { return EnumSet.copyOf(islands); }
	public void setIslands(Iterable<Island> values) { islands.clear(); if (values != null) for (Island island : values) if (island != null && island.selectable()) islands.add(island); }
	public List<MacroStep> steps() { return steps; }

	public boolean allowsIsland(Island island) {
		return !islandRestricted || (island != null && islands.contains(island));
	}

}
