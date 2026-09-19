package geiler.addons.client.macro;

import geiler.addons.client.location.Island;

import java.util.ArrayList;
import java.util.List;

/** Mutable workflow nodes; configuration persistence is handled explicitly by ModConfig. */
public sealed interface MacroStep permits MacroStep.Base {
	/** Delay in milliseconds before this node runs. Both values are explicit per-node settings. */
	int delayMin();
	int delayMax();
	void setDelay(int min, int max);
	String type();

	non-sealed abstract class Base implements MacroStep {
		private int delayMin;
		private int delayMax;

		@Override
		public int delayMin() {
			return delayMin;
		}

		@Override
		public int delayMax() {
			return delayMax;
		}

		@Override
		public void setDelay(int min, int max) {
			if (min < 0 || max < 0) {
				delayMin = 0;
				delayMax = 0;
				return;
			}
			delayMin = Math.min(60_000, min);
			delayMax = Math.min(60_000, Math.max(delayMin, max));
		}
	}

	final class Command extends Base {
		private String command;
		public Command(String command) { this.command = command == null ? "" : command; }
		public String command() { return command; }
		public void setCommand(String value) { command = value == null ? "" : value; }
		@Override public String type() { return "command"; }
	}

	final class Chat extends Base {
		private String message;
		public Chat(String message) { this.message = message == null ? "" : message; }
		public String message() { return message; }
		public void setMessage(String value) { message = value == null ? "" : value; }
		@Override public String type() { return "chat"; }
	}

	final class Wait extends Base {
		private int minMillis;
		private int maxMillis;
		public Wait(int minMillis, int maxMillis) { setRange(minMillis, maxMillis); }
		public int minMillis() { return minMillis; }
		public int maxMillis() { return maxMillis; }
		public void setRange(int min, int max) { minMillis = clamp(min, 0, 300_000); maxMillis = clamp(Math.max(min, max), minMillis, 300_000); }
		@Override public String type() { return "wait"; }
	}

	final class Key extends Base {
		private String key;
		private boolean hold;
		private int holdMinMillis;
		private int holdMaxMillis;
		public Key(String key, boolean hold, int holdMillis) { this(key, hold, holdMillis, holdMillis); }
		public Key(String key, boolean hold, int holdMinMillis, int holdMaxMillis) {
			this.key = key == null ? "" : key;
			this.hold = hold;
			setHoldRange(holdMinMillis, holdMaxMillis);
		}
		public String key() { return key; }
		public boolean hold() { return hold; }
		public int holdMinMillis() { return holdMinMillis; }
		public int holdMaxMillis() { return holdMaxMillis; }
		/** Legacy accessor retained for callers that treated hold time as a fixed duration. */
		public int holdMillis() { return holdMinMillis; }
		public void setKey(String value) { key = value == null ? "" : value; }
		public void setHold(boolean value) { hold = value; }
		public void setHoldMillis(int value) { setHoldRange(value, value); }
		public void setHoldRange(int min, int max) {
			holdMinMillis = clamp(min, 1, 60_000);
			holdMaxMillis = clamp(Math.max(holdMinMillis, max), holdMinMillis, 60_000);
		}
		@Override public String type() { return "key"; }
	}

	final class ClickSlot extends Base {
		private int slotId;
		private int button;
		private boolean shift;
		public ClickSlot(int slotId, int button, boolean shift) { this.slotId = Math.max(0, slotId); this.button = clamp(button, 0, 2); this.shift = shift; }
		public int slotId() { return slotId; }
		public int button() { return button; }
		public boolean shift() { return shift; }
		public void setSlotId(int value) { slotId = Math.max(0, value); }
		public void setButton(int value) { button = clamp(value, 0, 2); }
		public void setShift(boolean value) { shift = value; }
		@Override public String type() { return "click_slot"; }
	}

	final class ClickItem extends Base {
		private String name;
		private boolean contains;
		private String scope;
		private int occurrence;
		private int button;
		private boolean shift;
		public ClickItem(String name, boolean contains, String scope, int occurrence, int button, boolean shift) {
			this.name = name == null ? "" : name; this.contains = contains; this.scope = scope == null ? "container" : scope;
			this.occurrence = Math.max(0, occurrence); this.button = clamp(button, 0, 2); this.shift = shift;
		}
		public String name() { return name; }
		public boolean contains() { return contains; }
		public String scope() { return scope; }
		public int occurrence() { return occurrence; }
		public int button() { return button; }
		public boolean shift() { return shift; }
		public void setName(String value) { name = value == null ? "" : value; }
		public void setContains(boolean value) { contains = value; }
		public void setScope(String value) { scope = value == null ? "container" : value; }
		public void setOccurrence(int value) { occurrence = Math.max(0, value); }
		public void setButton(int value) { button = clamp(value, 0, 2); }
		public void setShift(boolean value) { shift = value; }
		@Override public String type() { return "click_item"; }
	}

	final class CloseScreen extends Base {
		@Override public String type() { return "close_screen"; }
	}

	/**
	 * Pauses a workflow until the client has changed levels and the location API confirms the
	 * selected destination. A world switch without this node remains a safety boundary and stops
	 * the workflow instead of replaying actions in an unrelated screen.
	 */
	final class WorldSwitch extends Base {
		private Island target;

		public WorldSwitch(Island target) {
			setTarget(target);
		}

		public Island target() {
			return target;
		}

		public void setTarget(Island value) {
			target = value != null && value.selectable() ? value : Island.HUB;
		}

		@Override public String type() { return "world_switch"; }
	}

	final class WaitUntil extends Base {
		private MacroCondition condition;
		public WaitUntil(MacroCondition condition) { this.condition = condition == null ? new MacroCondition.Always(true) : condition; }
		public MacroCondition condition() { return condition; }
		public void setCondition(MacroCondition value) { condition = value == null ? new MacroCondition.Always(true) : value; }
		@Override public String type() { return "wait_until"; }
	}

	final class IfElse extends Base {
		private MacroCondition condition;
		private final List<MacroStep> thenSteps = new ArrayList<>();
		private final List<MacroStep> elseSteps = new ArrayList<>();
		public IfElse(MacroCondition condition) { this.condition = condition == null ? new MacroCondition.Always(true) : condition; }
		public MacroCondition condition() { return condition; }
		public void setCondition(MacroCondition value) { condition = value == null ? new MacroCondition.Always(true) : value; }
		public List<MacroStep> thenSteps() { return thenSteps; }
		public List<MacroStep> elseSteps() { return elseSteps; }
		@Override public String type() { return "if"; }
	}

	final class Repeat extends Base {
		private boolean forever;
		private int count;
		private final List<MacroStep> steps = new ArrayList<>();
		public Repeat(boolean forever, int count) { this.forever = forever; this.count = Math.max(1, count); }
		public boolean forever() { return forever; }
		public int count() { return count; }
		public List<MacroStep> steps() { return steps; }
		public void setForever(boolean value) { forever = value; }
		public void setCount(int value) { count = Math.max(1, value); }
		@Override public String type() { return "repeat"; }
	}

	final class RepeatUntil extends Base {
		private MacroCondition condition;
		private final List<MacroStep> steps = new ArrayList<>();

		public RepeatUntil(MacroCondition condition) {
			this.condition = condition == null ? new MacroCondition.Always(true) : condition;
		}

		public MacroCondition condition() { return condition; }
		public void setCondition(MacroCondition value) {
			condition = value == null ? new MacroCondition.Always(true) : value;
		}
		public List<MacroStep> steps() { return steps; }
		@Override public String type() { return "repeat_until"; }
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
