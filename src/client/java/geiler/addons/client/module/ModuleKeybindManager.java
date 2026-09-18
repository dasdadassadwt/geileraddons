package geiler.addons.client.module;

import com.mojang.blaze3d.platform.InputConstants;
import geiler.addons.client.config.ModConfig;
import geiler.addons.client.macro.MacroDefinition;
import geiler.addons.client.macro.MacroRunner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/** Client-thread owner of module keybind capture and activation. */
public final class ModuleKeybindManager {
	private static Module bindingModule;
	private static MacroDefinition bindingMacro;
	private static ModuleKeybind pendingBind;

	private ModuleKeybindManager() {
	}

	public static Module bindingModule() {
		return bindingModule;
	}

	public static MacroDefinition bindingMacro() {
		return bindingMacro;
	}

	public static boolean isBinding(Module module) {
		return bindingModule == module;
	}

	public static void beginBinding(Module module) {
		if (module == null || !ModuleManager.modules().contains(module)) return;
		bindingMacro = null;
		bindingModule = module;
		pendingBind = null;
	}

	public static void cancelBinding() {
		bindingModule = null;
		bindingMacro = null;
		pendingBind = null;
	}

	public static void beginMacroBinding(MacroDefinition macro) {
		if (macro == null) return;
		bindingModule = null;
		bindingMacro = macro;
		pendingBind = null;
	}

	/** Handles raw keyboard actions before Minecraft forwards them to a screen or KeyMapping. */
	public static boolean handleKeyEvent(Minecraft minecraft, int action, KeyEvent event) {
		if (bindingMacro != null) {
			if (action == InputConstants.PRESS && isEscape(event)) {
				bindingMacro.setKeybind(ModuleKeybind.NONE);
				ModConfig.markDirty();
				cancelBinding();
				return true;
			}
			if (action == InputConstants.PRESS && isUsable(event)) {
				pendingBind = ModuleKeybind.from(event);
				return true;
			}
			if (action == InputConstants.RELEASE && pendingBind != null
				&& InputConstants.getKey(event).equals(pendingBind.key())) {
				bindingMacro.setKeybind(pendingBind);
				ModConfig.markDirty();
				cancelBinding();
				return true;
			}
			return true;
		}
		if (bindingModule != null) {
			if (action == InputConstants.PRESS && isEscape(event)) {
				bindingModule.setKeybind(ModuleKeybind.NONE);
				ModConfig.markDirty();
				cancelBinding();
				return true;
			}
			if (action == InputConstants.PRESS && isUsable(event)) {
				pendingBind = ModuleKeybind.from(event);
				return true;
			}
			if (action == InputConstants.RELEASE && pendingBind != null
				&& InputConstants.getKey(event).equals(pendingBind.key())) {
				bindingModule.setKeybind(pendingBind);
				ModConfig.markDirty();
				cancelBinding();
				return true;
			}
			return true;
		}

		if (action == InputConstants.PRESS && MacroRunner.handleKeyEvent(minecraft, action, event)) return true;
		if (action != InputConstants.PRESS || minecraft.screen != null) return false;
		List<Module> modules = ModuleManager.modules();
		boolean matched = false;
		for (Module module : modules) {
			if (module.keybind().matches(event)) {
				module.toggle();
				matched = true;
			}
		}
		if (matched) ModConfig.markDirty();
		return matched;
	}

	private static boolean isEscape(KeyEvent event) {
		return event.key() == InputConstants.KEY_ESCAPE;
	}

	private static boolean isUsable(KeyEvent event) {
		return event.key() != GLFW.GLFW_KEY_UNKNOWN
			&& event.key() != InputConstants.KEY_ESCAPE
			&& event.key() != InputConstants.KEY_LSHIFT
			&& event.key() != InputConstants.KEY_RSHIFT
			&& event.key() != InputConstants.KEY_LCONTROL
			&& event.key() != InputConstants.KEY_RCONTROL
			&& event.key() != InputConstants.KEY_LALT
			&& event.key() != InputConstants.KEY_RALT
			&& event.key() != InputConstants.KEY_LSUPER
			&& event.key() != InputConstants.KEY_RSUPER;
	}
}
