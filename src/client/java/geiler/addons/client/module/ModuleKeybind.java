package geiler.addons.client.module;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;

/** A keyboard key and the modifier mask required to activate it. */
public record ModuleKeybind(InputConstants.Key key, int modifiers) {
	private static final int MATCHED_MODIFIERS = InputConstants.MOD_SHIFT
		| InputConstants.MOD_CONTROL | InputConstants.MOD_ALT | InputConstants.MOD_SUPER;
	public static final ModuleKeybind NONE = new ModuleKeybind(InputConstants.UNKNOWN, 0);

	public ModuleKeybind {
		if (key == null || key.equals(InputConstants.UNKNOWN) || key.equals(keyForEscape())) {
			key = InputConstants.UNKNOWN;
			modifiers = 0;
		} else {
			modifiers &= MATCHED_MODIFIERS;
		}
	}

	public static ModuleKeybind from(KeyEvent event) {
		if (event == null) return NONE;
		return new ModuleKeybind(InputConstants.getKey(event), effectiveModifiers(event.key(), event.modifiers()));
	}

	public static ModuleKeybind fromMouse(int button, int modifiers) {
		if (button < 0 || button > 7) return NONE;
		return new ModuleKeybind(InputConstants.Type.MOUSE.getOrCreate(button), modifiers);
	}

	public boolean isBound() {
		return !key.equals(InputConstants.UNKNOWN);
	}

	public boolean matches(KeyEvent event) {
		if (event == null) return false;
		return isBound() && key.equals(InputConstants.getKey(event))
			&& modifiers == effectiveModifiers(event.key(), event.modifiers());
	}

	public boolean matchesMouse(int button, int eventModifiers) {
		return button >= 0 && button <= 7 && isBound()
			&& key.equals(InputConstants.Type.MOUSE.getOrCreate(button))
			&& modifiers == (eventModifiers & MATCHED_MODIFIERS);
	}

	/**
	 * Whether another bind would fire on exactly the same press.
	 *
	 * <p>Both the key and the modifier mask have to agree: binding G and binding Ctrl+G are two
	 * different hotkeys, and only the second press is ambiguous with the first.
	 */
	public boolean conflictsWith(ModuleKeybind other) {
		return other != null && isBound() && other.isBound()
			&& key.equals(other.key()) && modifiers == other.modifiers();
	}

	public String displayName() {
		if (!isBound()) return "None";
		StringBuilder result = new StringBuilder();
		if ((modifiers & InputConstants.MOD_CONTROL) != 0) result.append("Ctrl + ");
		if ((modifiers & InputConstants.MOD_SHIFT) != 0) result.append("Shift + ");
		if ((modifiers & InputConstants.MOD_ALT) != 0) result.append("Alt + ");
		if ((modifiers & InputConstants.MOD_SUPER) != 0) result.append("Super + ");
		result.append(key.getDisplayName().getString());
		return result.toString();
	}

	private static InputConstants.Key keyForEscape() {
		return InputConstants.getKey(new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0));
	}

	private static int effectiveModifiers(int key, int modifiers) {
		int ownModifier = switch (key) {
			case InputConstants.KEY_LSHIFT, InputConstants.KEY_RSHIFT -> InputConstants.MOD_SHIFT;
			case InputConstants.KEY_LCONTROL, InputConstants.KEY_RCONTROL -> InputConstants.MOD_CONTROL;
			case InputConstants.KEY_LALT, InputConstants.KEY_RALT -> InputConstants.MOD_ALT;
			case InputConstants.KEY_LSUPER, InputConstants.KEY_RSUPER -> InputConstants.MOD_SUPER;
			default -> 0;
		};
		return modifiers & MATCHED_MODIFIERS & ~ownModifier;
	}
}
