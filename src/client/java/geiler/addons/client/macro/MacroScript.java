package geiler.addons.client.macro;

import geiler.addons.client.module.ModuleKeybind;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One independently-triggered Scratch-style stack inside a macro. */
public final class MacroScript {
	public enum Trigger {
		KEY_PRESS,
		WORLD_REGION,
		ON_CALL,
		/** Fires from a received chat line; the pattern below is its whole configuration. */
		CHAT
	}

	private final String id;
	private Trigger trigger;
	private ModuleKeybind keybind = ModuleKeybind.NONE;
	private final List<MacroStep> steps = new ArrayList<>();
	private MacroWorldRegion worldRegion = new MacroWorldRegion();
	/**
	 * Chat trigger configuration. Blank means inert, so a new stack cannot fire on every line
	 * before the player has said what it should look for.
	 */
	private String chatPattern = "";
	private boolean chatContains = true;
	private int chatCooldownMillis = 500;
	private float canvasX;
	private float canvasY;

	public MacroScript(Trigger trigger) {
		this(UUID.randomUUID().toString(), trigger);
	}

	public MacroScript(String id, Trigger trigger) {
		this.id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
		this.trigger = trigger == null ? Trigger.KEY_PRESS : trigger;
	}

	public String id() { return id; }
	public Trigger trigger() { return trigger; }
	public void setTrigger(Trigger value) { trigger = value == null ? Trigger.KEY_PRESS : value; }
	public ModuleKeybind keybind() { return keybind; }
	public void setKeybind(ModuleKeybind value) { keybind = value == null ? ModuleKeybind.NONE : value; }
	public String chatPattern() { return chatPattern; }
	public void setChatPattern(String value) {
		chatPattern = value == null ? "" : value.substring(0, Math.min(MacroChatTrigger.MAX_PATTERN_LENGTH, value.length()));
	}
	/** Whether the pattern may match anywhere in the line (default) or has to be the whole line. */
	public boolean chatContains() { return chatContains; }
	public void setChatContains(boolean value) { chatContains = value; }
	public int chatCooldownMillis() { return chatCooldownMillis; }
	public void setChatCooldownMillis(int value) { chatCooldownMillis = Math.max(0, Math.min(60_000, value)); }
	public List<MacroStep> steps() { return steps; }
	public MacroWorldRegion worldRegion() { return worldRegion; }
	public void setWorldRegion(MacroWorldRegion value) { worldRegion = value == null ? new MacroWorldRegion() : value; }
	public float canvasX() { return canvasX; }
	public float canvasY() { return canvasY; }
	public void setCanvasPosition(float x, float y) {
		canvasX = Float.isFinite(x) ? Math.max(-100_000, Math.min(100_000, x)) : 0;
		canvasY = Float.isFinite(y) ? Math.max(-100_000, Math.min(100_000, y)) : 0;
	}
}
