package geiler.addons.client.macro;

import geiler.addons.client.location.Island;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/** User-owned macro metadata and workflow. */
public final class MacroDefinition {
	private final int id;
	private String name;
	private String folderId;
	private boolean enabled = true;
	/** New macros are useful in both the world and ordinary container screens by default. */
	private MacroTriggerContext triggerContext = MacroTriggerContext.ANY_NON_TEXT_SCREEN;
	private boolean islandRestricted;
	private final EnumSet<Island> islands = EnumSet.noneOf(Island.class);
	private final List<MacroScript> scripts = new ArrayList<>();
	private float canvasPanX;
	private float canvasPanY;
	private float canvasZoom = 1.0f;

	public MacroDefinition(int id) {
		this.id = Math.max(0, id);
		this.name = "Macro " + this.id;
		scripts.add(new MacroScript(MacroScript.Trigger.KEY_PRESS));
	}

	public int id() { return id; }
	public String name() { return name; }
	public void setName(String value) { name = value == null || value.isBlank() ? "Macro " + id : value.substring(0, Math.min(48, value.length())); }
	public String folderId() { return folderId; }
	public void setFolderId(String value) { folderId = value == null || value.isBlank() ? null : value; }
	public boolean enabled() { return enabled; }
	public void setEnabled(boolean value) { enabled = value; }
	public geiler.addons.client.module.ModuleKeybind keybind() { return primaryKeyScript().keybind(); }
	public void setKeybind(geiler.addons.client.module.ModuleKeybind value) { primaryKeyScript().setKeybind(value); }
	public MacroTriggerContext triggerContext() { return triggerContext; }
	public void setTriggerContext(MacroTriggerContext value) { triggerContext = value == null ? MacroTriggerContext.WORLD_ONLY : value; }
	public boolean islandRestricted() { return islandRestricted; }
	public void setIslandRestricted(boolean value) { islandRestricted = value; }
	public EnumSet<Island> islands() { return EnumSet.copyOf(islands); }
	public void setIslands(Iterable<Island> values) { islands.clear(); if (values != null) for (Island island : values) if (island != null && island.selectable()) islands.add(island); }
	/** Backward-compatible view of the original macro workflow. */
	public List<MacroStep> steps() { return primaryKeyScript().steps(); }
	public List<MacroScript> scripts() { return scripts; }
	public MacroScript primaryKeyScript() {
		for (MacroScript script : scripts) if (script.trigger() == MacroScript.Trigger.KEY_PRESS) return script;
		MacroScript script = new MacroScript(MacroScript.Trigger.KEY_PRESS);
		scripts.add(0, script);
		return script;
	}
	public MacroScript addScript(MacroScript.Trigger trigger) {
		MacroScript script = new MacroScript(trigger);
		int index = scripts.size();
		script.setCanvasPosition((index % 3) * 310.0f, (index / 3) * 90.0f);
		scripts.add(script);
		return script;
	}
	public void restoreScripts(Iterable<MacroScript> restored) {
		scripts.clear();
		if (restored != null) for (MacroScript script : restored) if (script != null) scripts.add(script);
		if (scripts.isEmpty()) scripts.add(new MacroScript(MacroScript.Trigger.KEY_PRESS));
		if (scripts.size() > 1 && scripts.stream().allMatch(script -> script.canvasX() == 0 && script.canvasY() == 0)) {
			for (int index = 0; index < scripts.size(); index++) {
				scripts.get(index).setCanvasPosition((index % 3) * 310.0f, (index / 3) * 90.0f);
			}
		}
		primaryKeyScript();
	}
	public float canvasPanX() { return canvasPanX; }
	public float canvasPanY() { return canvasPanY; }
	public float canvasZoom() { return canvasZoom; }
	public void setCanvasView(float panX, float panY, float zoom) {
		canvasPanX = Float.isFinite(panX) ? Math.max(-100_000, Math.min(100_000, panX)) : 0;
		canvasPanY = Float.isFinite(panY) ? Math.max(-100_000, Math.min(100_000, panY)) : 0;
		canvasZoom = Float.isFinite(zoom) ? Math.max(0.45f, Math.min(2.0f, zoom)) : 1.0f;
	}

	public boolean allowsIsland(Island island) {
		return !islandRestricted || (island != null && islands.contains(island));
	}

}
