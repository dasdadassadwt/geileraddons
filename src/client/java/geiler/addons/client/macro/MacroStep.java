package geiler.addons.client.macro;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
		private float editorX;
		private float editorY;

		/** Canvas position used only while the node is stored outside an executable stack. */
		public float editorX() { return editorX; }
		public float editorY() { return editorY; }
		public void setEditorPosition(float x, float y) {
			editorX = Float.isFinite(x) ? Math.max(-100_000, Math.min(100_000, x)) : 0;
			editorY = Float.isFinite(y) ? Math.max(-100_000, Math.min(100_000, y)) : 0;
		}

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

	final class Title extends Base {
		private String text = "TITLE";
		private String font = "minecraft:default";
		private float scale = 2.0f;
		private int textColor = 0xFFFFFFFF;
		private boolean showBackground;
		private int backgroundColor = 0xFF000000;
		private int backgroundOpacity = 160;
		private int fadeInMillis = 200;
		private int holdMillis = 2_000;
		private int fadeOutMillis = 300;

		public Title() { }
		public String text() { return text; }
		public String font() { return font; }
		public float scale() { return scale; }
		public int textColor() { return textColor; }
		public boolean showBackground() { return showBackground; }
		public int backgroundColor() { return backgroundColor; }
		public int backgroundOpacity() { return backgroundOpacity; }
		public int fadeInMillis() { return fadeInMillis; }
		public int holdMillis() { return holdMillis; }
		public int fadeOutMillis() { return fadeOutMillis; }
		public void setText(String value) { text = clampText(value, 256); }
		public void setFont(String value) { font = value == null || value.isBlank() ? "minecraft:default" : value.substring(0, Math.min(128, value.length())); }
		public void setScale(float value) { scale = Float.isFinite(value) ? clamp(value, 0.5f, 6.0f) : 2.0f; }
		public void setTextColor(int value) { textColor = value | 0xFF000000; }
		public void setShowBackground(boolean value) { showBackground = value; }
		public void setBackgroundColor(int value) { backgroundColor = value | 0xFF000000; }
		public void setBackgroundOpacity(int value) { backgroundOpacity = clamp(value, 0, 255); }
		public void setFadeInMillis(int value) { fadeInMillis = clamp(value, 0, 10_000); }
		public void setHoldMillis(int value) { holdMillis = clamp(value, 0, 60_000); }
		public void setFadeOutMillis(int value) { fadeOutMillis = clamp(value, 0, 10_000); }
		@Override public String type() { return "title"; }
}

	final class Sound extends Base {
		private String soundId = "minecraft:entity.player.levelup";
		public Sound() { }
		public Sound(String soundId) { setSoundId(soundId); }
		public String soundId() { return soundId; }
		public void setSoundId(String value) { soundId = value == null ? "" : value.substring(0, Math.min(128, value.length())); }
		@Override public String type() { return "sound"; }
	}

	final class Wait extends Base {
		public enum Mode { DURATION, CONDITION }
		private int minMillis;
		private int maxMillis;
		private Mode mode = Mode.DURATION;
		private MacroCondition condition = new MacroCondition.Always(true);
		public Wait(int minMillis, int maxMillis) { setRange(minMillis, maxMillis); }
		public int minMillis() { return minMillis; }
		public int maxMillis() { return maxMillis; }
		public Mode mode() { return mode; }
		public MacroCondition condition() { return condition; }
		public void setMode(Mode value) { mode = value == null ? Mode.DURATION : value; }
		public void setCondition(MacroCondition value) { condition = value == null ? new MacroCondition.Always(true) : value; }
		public void setRange(int min, int max) { minMillis = clamp(min, 0, 300_000); maxMillis = clamp(Math.max(min, max), minMillis, 300_000); }
		@Override public String type() { return "wait"; }
	}

	/** A visual separator that never executes or consumes a delay in the runner. */
	final class Comment extends Base {
		private String text = "Comment";
		public Comment() { }
		public Comment(String text) { setText(text); }
		public String text() { return text; }
		public void setText(String value) { text = clampText(value, 256); }
		@Override public String type() { return "comment"; }
	}

	/**
	 * A persisted step whose type this build cannot safely execute or edit.
	 *
	 * <p>The raw object is retained so loading and saving a macro with a step introduced by another
	 * build cannot erase that step's settings. Only the common editor-position and delay fields are
	 * overlaid when the user changes them; all other raw fields stay untouched.
	 */
	final class Unknown extends Base {
		private final JsonObject rawData;
		private final String originalType;
		private final boolean depthLimited;
		private final int initialDelayMin;
		private final int initialDelayMax;
		private final float initialEditorX;
		private final float initialEditorY;
		private String rawJson;

		public Unknown(JsonObject rawData) {
			this(rawData, false);
		}

		public Unknown(JsonObject rawData, boolean depthLimited) {
			this.rawData = rawData == null ? new JsonObject() : rawData.deepCopy();
			this.originalType = typeName(this.rawData);
			this.depthLimited = depthLimited;
			setDelay(integer(this.rawData, "delayMin", 0), integer(this.rawData, "delayMax", 0));
			setEditorPosition(decimal(this.rawData, "editorX", 0), decimal(this.rawData, "editorY", 0));
			initialDelayMin = delayMin();
			initialDelayMax = delayMax();
			initialEditorX = editorX();
			initialEditorY = editorY();
		}

		public String originalType() { return originalType; }
		public boolean depthLimited() { return depthLimited; }

		/** Returns a defensive copy with any deliberately edited common fields applied. */
		public JsonObject serializedData() {
			JsonObject result = rawData.deepCopy();
			if (delayMin() != initialDelayMin) result.addProperty("delayMin", delayMin());
			if (delayMax() != initialDelayMax) result.addProperty("delayMax", delayMax());
			if (Float.compare(editorX(), initialEditorX) != 0) result.addProperty("editorX", editorX());
			if (Float.compare(editorY(), initialEditorY) != 0) result.addProperty("editorY", editorY());
			return result;
		}

		/** Compact read-only preview for the editor's raw-data panel. */
		public String rawJson() {
			if (rawJson == null) rawJson = rawData.toString();
			return rawJson;
		}

		@Override public String type() { return originalType; }

		private static String typeName(JsonObject object) {
			JsonElement value = object.get("type");
			if (value == null || !value.isJsonPrimitive()) return "(missing type)";
			try {
				String type = value.getAsString();
				return type.isBlank() ? "(blank type)" : type;
			} catch (RuntimeException ignored) {
				return "(invalid type)";
			}
		}

		private static int integer(JsonObject object, String name, int fallback) {
			JsonElement value = object.get(name);
			if (value == null || !value.isJsonPrimitive()) return fallback;
			try { return value.getAsInt(); } catch (RuntimeException ignored) { return fallback; }
		}

		private static float decimal(JsonObject object, String name, float fallback) {
			JsonElement value = object.get(name);
			if (value == null || !value.isJsonPrimitive()) return fallback;
			try { return value.getAsFloat(); } catch (RuntimeException ignored) { return fallback; }
		}
	}

	/** Explicitly ends this macro run and releases held inputs. */
	final class StopRun extends Base {
		@Override public String type() { return "stop_run"; }
	}

	/** Scrolls an open screen or the world hotbar by a bounded number of wheel steps. */
	final class Scroll extends Base {
		public enum Direction { UP, DOWN }
		private Direction direction = Direction.DOWN;
		private int amount = 1;
		public Scroll() { }
		public Scroll(Direction direction, int amount) { setDirection(direction); setAmount(amount); }
		public Direction direction() { return direction; }
		public int amount() { return amount; }
		public void setDirection(Direction value) { direction = value == null ? Direction.DOWN : value; }
		public void setAmount(int value) { amount = clamp(value, 1, 32); }
		@Override public String type() { return "scroll"; }
	}

	/** New combined inventory action; legacy slot/item nodes remain readable and executable. */
	final class InventoryClick extends Base {
		public enum Target { SLOT, ITEM }
		private Target target = Target.SLOT;
		private int slotId;
		private String name = "";
		private boolean contains;
		private String scope = "container";
		private int occurrence;
		private int button;
		private boolean shift;
		public Target target() { return target; }
		public int slotId() { return slotId; }
		public String name() { return name; }
		public boolean contains() { return contains; }
		public String scope() { return scope; }
		public int occurrence() { return occurrence; }
		public int button() { return button; }
		public boolean shift() { return shift; }
		public void setTarget(Target value) { target = value == null ? Target.SLOT : value; }
		public void setSlotId(int value) { slotId = Math.max(0, value); }
		public void setName(String value) { name = value == null ? "" : value; }
		public void setContains(boolean value) { contains = value; }
		public void setScope(String value) { scope = value == null ? "container" : value; }
		public void setOccurrence(int value) { occurrence = Math.max(0, value); }
		public void setButton(int value) { button = clamp(value, 0, 2); }
		public void setShift(boolean value) { shift = value; }
		@Override public String type() { return "inventory_click"; }
	}

	/** New combined local/global variable action; legacy set/change nodes remain compatible. */
	final class UpdateVariable extends Base {
		public enum Operation { SET, ADD, SUBTRACT }
		private Operation operation = Operation.SET;
		private String name = "value";
		private String globalVariableId;
		private MacroValue value = MacroValue.literal(MacroValue.Type.TEXT, "");
		private double amount = 1;
		public Operation operation() { return operation; }
		public String name() { return name; }
		public String globalVariableId() { return globalVariableId; }
		public boolean targetsGlobal() { return globalVariableId != null; }
		public MacroValue value() { return value; }
		public double amount() { return amount; }
		public void setOperation(Operation value) { operation = value == null ? Operation.SET : value; }
		public void setName(String value) { name = value == null || value.isBlank() ? "value" : value.strip().substring(0, Math.min(32, value.strip().length())); }
		public void setGlobalVariableId(String value) { globalVariableId = value == null || value.isBlank() ? null : value.substring(0, Math.min(64, value.length())); }
		public void setValue(MacroValue value) { this.value = value == null ? MacroValue.literal(MacroValue.Type.TEXT, "") : value; }
		public void setAmount(double value) { amount = Double.isFinite(value) ? Math.max(-1_000_000, Math.min(1_000_000, value)) : 0; }
		@Override public String type() { return "update_variable"; }
	}

	final class Key extends Base {
		public enum InputMode { KEYBOARD, MOUSE, HOTBAR }
		private String key;
		private InputMode inputMode = InputMode.KEYBOARD;
		private MouseButton.Button mouseButton = MouseButton.Button.LEFT;
		private int hotbarSlot = 1;
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
		public InputMode inputMode() { return inputMode; }
		public MouseButton.Button mouseButton() { return mouseButton; }
		public int hotbarSlot() { return hotbarSlot; }
		public boolean hold() { return hold; }
		public int holdMinMillis() { return holdMinMillis; }
		public int holdMaxMillis() { return holdMaxMillis; }
		/** Legacy accessor retained for callers that treated hold time as a fixed duration. */
		public int holdMillis() { return holdMinMillis; }
		public void setKey(String value) { key = value == null ? "" : value; }
		public void setInputMode(InputMode value) { inputMode = value == null ? InputMode.KEYBOARD : value; }
		public void setMouseButton(MouseButton.Button value) { mouseButton = value == null ? MouseButton.Button.LEFT : value; }
		public void setHotbarSlot(int value) { hotbarSlot = clamp(value, 1, 9); }
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

	final class SelectHotbarSlot extends Base {
		private int slot;
		public SelectHotbarSlot(int slot) { setSlot(slot); }
		public int slot() { return slot; }
		public void setSlot(int value) { slot = clamp(value, 1, 9); }
		@Override public String type() { return "select_hotbar_slot"; }
	}

	final class MouseButton extends Base {
		public enum Button { LEFT, RIGHT, MIDDLE, BUTTON_4, BUTTON_5, BUTTON_6, BUTTON_7, BUTTON_8 }
		private Button button;
		private boolean hold;
		private int holdMillis;
		public MouseButton(Button button, boolean hold, int holdMillis) {
			this.button = button == null ? Button.LEFT : button;
			this.hold = hold;
			setHoldMillis(holdMillis);
		}
		public Button button() { return button; }
		public boolean hold() { return hold; }
		public int holdMillis() { return holdMillis; }
		public void setButton(Button value) { button = value == null ? Button.LEFT : value; }
		public void setHold(boolean value) { hold = value; }
		public void setHoldMillis(int value) { holdMillis = clamp(value, 1, 60_000); }
		@Override public String type() { return "mouse_button"; }
	}

	final class BlockPlayerInput extends Base {
		private int durationMillis;
		public BlockPlayerInput(int durationMillis) { setDurationMillis(durationMillis); }
		public int durationMillis() { return durationMillis; }
		public void setDurationMillis(int value) { durationMillis = clamp(value, 1, 60_000); }
		@Override public String type() { return "block_player_input"; }
	}

	final class StartBlockPlayerInput extends Base {
		@Override public String type() { return "start_block_player_input"; }
	}

	final class StopBlockPlayerInput extends Base {
		@Override public String type() { return "stop_block_player_input"; }
	}

	final class SetVariable extends Base {
		private String name;
		private MacroValue.Type valueType;
		private MacroValue value;
		private String globalVariableId;
		public SetVariable(String name, MacroValue.Type valueType, MacroValue value) {
			this(name, valueType, value, null);
		}
		public SetVariable(String name, MacroValue.Type valueType, MacroValue value, String globalVariableId) {
			setName(name);
			this.valueType = valueType == null ? MacroValue.Type.TEXT : valueType;
			this.value = value == null ? MacroValue.literal(this.valueType, "") : value;
			setGlobalVariableId(globalVariableId);
		}
		public String name() { return name; }
		public MacroValue.Type valueType() { return valueType; }
		public MacroValue value() { return value; }
		public String globalVariableId() { return globalVariableId; }
		public boolean targetsGlobal() { return globalVariableId != null; }
		public void setName(String value) { name = value == null ? "value" : value.strip().substring(0, Math.min(32, value.strip().length())); if (name.isEmpty()) name = "value"; }
		public void setValueType(MacroValue.Type value) { valueType = value == null ? MacroValue.Type.TEXT : value; }
		public void setValue(MacroValue value) { this.value = value == null ? MacroValue.literal(valueType, "") : value; }
		public void setGlobalVariableId(String value) {
			globalVariableId = value == null || value.isBlank() ? null : value.substring(0, Math.min(64, value.length()));
		}
		@Override public String type() { return "set_variable"; }
	}

	final class ChangeVariable extends Base {
		private String name;
		private double amount;
		private String globalVariableId;
		public ChangeVariable(String name, double amount) { this(name, amount, null); }
		public ChangeVariable(String name, double amount, String globalVariableId) {
			setName(name);
			setAmount(amount);
			setGlobalVariableId(globalVariableId);
		}
		public String name() { return name; }
		public double amount() { return amount; }
		public String globalVariableId() { return globalVariableId; }
		public boolean targetsGlobal() { return globalVariableId != null; }
		public void setName(String value) { name = value == null ? "value" : value.strip().substring(0, Math.min(32, value.strip().length())); if (name.isEmpty()) name = "value"; }
		public void setAmount(double value) { amount = Double.isFinite(value) ? Math.max(-1_000_000, Math.min(1_000_000, value)) : 0; }
		public void setGlobalVariableId(String value) {
			globalVariableId = value == null || value.isBlank() ? null : value.substring(0, Math.min(64, value.length()));
		}
		@Override public String type() { return "change_variable"; }
	}

	final class FunctionCall extends Base {
		private String functionId;
		private final List<MacroValue> arguments = new ArrayList<>();
		public FunctionCall(String functionId) { this.functionId = functionId == null ? "" : functionId; }
		public String functionId() { return functionId; }
		public List<MacroValue> arguments() { return arguments; }
		public void setFunctionId(String value) { functionId = value == null ? "" : value; }
		@Override public String type() { return "function_call"; }
	}

	final class MacroCall extends Base {
		private int macroId;
		/**
		 * Optional gate. Null means the call is unconditional, which is what every macro written
		 * before this field existed decodes to - so adding a condition never changes an old workflow.
		 */
		private MacroCondition condition;
		public MacroCall(int macroId) { setMacroId(macroId); }
		public int macroId() { return macroId; }
		public void setMacroId(int value) { macroId = value < 0 ? -1 : value; }
		public MacroCondition condition() { return condition; }
		public void setCondition(MacroCondition value) { condition = value; }
		@Override public String type() { return "macro_call"; }
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
		private boolean elseEnabled;
		private final List<MacroStep> thenSteps = new ArrayList<>();
		private final List<MacroStep> elseSteps = new ArrayList<>();
		public IfElse(MacroCondition condition) { this.condition = condition == null ? new MacroCondition.Always(true) : condition; }
		public MacroCondition condition() { return condition; }
		public boolean elseEnabled() { return elseEnabled; }
		public void setCondition(MacroCondition value) { condition = value == null ? new MacroCondition.Always(true) : value; }
		public void setElseEnabled(boolean value) { elseEnabled = value; }
		public List<MacroStep> thenSteps() { return thenSteps; }
		public List<MacroStep> elseSteps() { return elseSteps; }
		@Override public String type() { return "if"; }
	}

	final class Repeat extends Base {
		public enum Mode { COUNT, FOREVER, UNTIL }
		private Mode mode;
		private int count;
		private MacroCondition condition = new MacroCondition.Always(false);
		private final List<MacroStep> steps = new ArrayList<>();
		public Repeat(boolean forever, int count) { this.mode = forever ? Mode.FOREVER : Mode.COUNT; setCount(count); }
		public Mode mode() { return mode; }
		public boolean forever() { return mode == Mode.FOREVER; }
		public int count() { return count; }
		public MacroCondition condition() { return condition; }
		public List<MacroStep> steps() { return steps; }
		public void setMode(Mode value) { mode = value == null ? Mode.COUNT : value; }
		public void setForever(boolean value) { mode = value ? Mode.FOREVER : Mode.COUNT; }
		public void setCount(int value) { count = clamp(value, 1, 1_000_000); }
		public void setCondition(MacroCondition value) { condition = value == null ? new MacroCondition.Always(false) : value; }
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

	/** Multi-case value branch. Every case and the fallback own an independent nested stack. */
	final class Switch extends Base {
		private String name = "value";
		private String globalVariableId;
		private final List<SwitchCase> cases = new ArrayList<>();
		private final List<MacroStep> defaultSteps = new ArrayList<>();
		public Switch() {
			cases.add(new SwitchCase("case 1"));
			cases.add(new SwitchCase("case 2"));
		}
		public String name() { return name; }
		public String globalVariableId() { return globalVariableId; }
		public boolean targetsGlobal() { return globalVariableId != null; }
		public List<SwitchCase> cases() { return cases; }
		public List<MacroStep> defaultSteps() { return defaultSteps; }
		public void setName(String value) { name = value == null || value.isBlank() ? "value" : value.strip().substring(0, Math.min(32, value.strip().length())); }
		public void setGlobalVariableId(String value) { globalVariableId = value == null || value.isBlank() ? null : value.substring(0, Math.min(64, value.length())); }
		public SwitchCase addCase() {
			if (cases.size() >= 16) return null;
			SwitchCase value = new SwitchCase("case " + (cases.size() + 1));
			cases.add(value);
			return value;
		}
		@Override public String type() { return "switch"; }
	}

	final class SwitchCase {
		private String value;
		private final List<MacroStep> steps = new ArrayList<>();
		public SwitchCase(String value) { setValue(value); }
		public String value() { return value; }
		public List<MacroStep> steps() { return steps; }
		public void setValue(String value) { this.value = clampText(value, 128); }
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}

	private static String clampText(String value, int maximum) {
		return value == null ? "" : value.substring(0, Math.min(maximum, value.length()));
	}
}
