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
		return new ModuleKeybind(InputConstants.getKey(event), event.modifiers());
	}

	public boolean isBound() {
		return !key.equals(InputConstants.UNKNOWN);
	}

	public boolean matches(KeyEvent event) {
		return isBound() && key.equals(InputConstants.getKey(event))
			&& modifiers == (event.modifiers() & MATCHED_MODIFIERS);
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
}
