package geiler.addons.client.module;

import com.mojang.blaze3d.platform.InputConstants;
import geiler.addons.client.config.ModConfig;
import geiler.addons.client.macro.MacroDefinition;
import geiler.addons.client.macro.MacroRunner;
import geiler.addons.client.macro.MacroStep;
import geiler.addons.client.macro.MacroScript;
import geiler.addons.client.module.impl.DungeonHelperModule;
import geiler.addons.client.module.impl.MacrosModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** Client-thread owner of module keybind capture and activation. */
public final class ModuleKeybindManager {
	private static Module bindingModule;
	private static MacroDefinition bindingMacro;
	private static MacroStep.Key bindingKeyStep;
	private static MacroScript bindingScript;
	private static ModuleKeybind pendingBind;

	private ModuleKeybindManager() {
	}

	public static Module bindingModule() {
		return bindingModule;
	}

	public static MacroDefinition bindingMacro() {
		return bindingMacro;
	}

	public static MacroStep.Key bindingKeyStep() {
		return bindingKeyStep;
	}

	public static MacroScript bindingScript() { return bindingScript; }

	public static boolean isBinding(Module module) {
		return bindingModule == module;
	}

	public static void beginBinding(Module module) {
		if (module == null || !ModuleManager.modules().contains(module)) return;
		bindingMacro = null;
		bindingKeyStep = null;
		bindingScript = null;
		bindingModule = module;
		pendingBind = null;
	}

	public static void cancelBinding() {
		bindingModule = null;
		bindingMacro = null;
		bindingKeyStep = null;
		bindingScript = null;
		pendingBind = null;
	}

	public static void beginMacroBinding(MacroDefinition macro) {
		if (macro == null) return;
		bindingModule = null;
		bindingKeyStep = null;
		bindingScript = null;
		bindingMacro = macro;
		pendingBind = null;
	}

	public static void beginScriptBinding(MacroScript script) {
		// A chat stack's hotkey is its manual replay key, so both event kinds are bindable.
		if (script == null || (script.trigger() != MacroScript.Trigger.KEY_PRESS
			&& script.trigger() != MacroScript.Trigger.CHAT)) return;
		bindingModule = null;
		bindingMacro = null;
		bindingKeyStep = null;
		bindingScript = script;
		pendingBind = null;
	}

	public static void beginKeyStepBinding(MacroStep.Key keyStep) {
		if (keyStep == null) return;
		bindingModule = null;
		bindingMacro = null;
		bindingScript = null;
		bindingKeyStep = keyStep;
		pendingBind = null;
	}

	/** Handles raw keyboard actions before Minecraft forwards them to a screen or KeyMapping. */
	public static boolean handleKeyEvent(Minecraft minecraft, int action, KeyEvent event) {
		if (bindingKeyStep != null) {
			if (action == InputConstants.PRESS && isEscape(event)) {
				bindingKeyStep.setKey("");
				ModConfig.markDirty();
				cancelBinding();
				return true;
			}
			if (action == InputConstants.PRESS && isUsableMacroStepKey(event)) {
				pendingBind = ModuleKeybind.from(event);
				return true;
			}
			if (action == InputConstants.RELEASE && pendingBind != null
				&& InputConstants.getKey(event).equals(pendingBind.key())) {
				bindingKeyStep.setKey(pendingBind.key().getName());
				ModConfig.markDirty();
				cancelBinding();
				return true;
			}
			return true;
		}
		if (bindingScript != null) {
			if (action == InputConstants.PRESS && isEscape(event)) {
				bindingScript.setKeybind(ModuleKeybind.NONE);
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
				bindingScript.setKeybind(pendingBind);
				reportConflicts(pendingBind);
				ModConfig.markDirty();
				cancelBinding();
				return true;
			}
			return true;
		}
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
				reportConflicts(pendingBind);
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
				reportConflicts(pendingBind);
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
			if (!module.showsKeybindControl()) continue;
			if (!module.keybind().matches(event)) continue;
			if (module == DungeonHelperModule.INSTANCE) DungeonHelperModule.INSTANCE.openEditorFromKeybind();
			else {
				module.toggle();
				reportModuleToggle(module);
			}
			matched = true;
		}
		if (matched) ModConfig.markDirty();
		return matched;
	}

	/** Handles mouse binds before Minecraft forwards the click, including buttons 4 through 8. */
	public static boolean handleMouseButtonEvent(Minecraft minecraft, int action, MouseButtonInfo event) {
		if (event == null) return false;
		if (isCapturing()) {
			if (action != InputConstants.PRESS) return true;
			int button = event.button();
			if (button < 0 || button > 7) return true;
			if (bindingKeyStep != null) {
				bindingKeyStep.setKey(InputConstants.Type.MOUSE.getOrCreate(button).getName());
				ModConfig.markDirty();
				cancelBinding();
				return true;
			}
			ModuleKeybind bound = ModuleKeybind.fromMouse(button, event.modifiers());
			if (bindingModule != null) bindingModule.setKeybind(bound);
			else if (bindingMacro != null) bindingMacro.setKeybind(bound);
			else if (bindingScript != null) bindingScript.setKeybind(bound);
			else return true;
			reportConflicts(bound);
			ModConfig.markDirty();
			cancelBinding();
			return true;
		}
		if (action != InputConstants.PRESS || minecraft == null || minecraft.screen != null) return false;
		if (MacroRunner.handleMouseButtonEvent(minecraft, action, event)) return true;
		boolean matched = false;
		for (Module module : ModuleManager.modules()) {
			if (!module.showsKeybindControl()) continue;
			if (!module.keybind().matchesMouse(event.button(), event.modifiers())) continue;
			if (module == DungeonHelperModule.INSTANCE) DungeonHelperModule.INSTANCE.openEditorFromKeybind();
			else {
				module.toggle();
				reportModuleToggle(module);
			}
			matched = true;
		}
		if (matched) ModConfig.markDirty();
		return matched;
	}

	private static boolean isCapturing() {
		return bindingModule != null || bindingMacro != null || bindingKeyStep != null || bindingScript != null;
	}

	private static void reportModuleToggle(Module module) {
		Minecraft minecraft = Minecraft.getInstance();
		if (module == null || minecraft.gui == null) return;
		String state = module.isEnabled() ? "enabled" : "disabled";
		minecraft.gui.getChat().addClientSystemMessage(Component.literal(
			"[GeilerAddons] " + module.name() + " is now " + state + "."));
	}

	/**
	 * Names the other owner of a freshly bound key, so a shadowed hotkey is never a silent loss.
	 *
	 * <p>Macro hotkeys are consulted before module keybinds and every matching module is toggled, so a
	 * collision used to mean one of two things happened invisibly: the macro swallowed the module's
	 * key, or one press flipped several modules at once. Reported rather than resolved - which of the
	 * two the player meant is their choice.
	 */
	private static void reportConflicts(ModuleKeybind bound) {
		if (bound == null || !bound.isBound()) return;
		List<String> others = new ArrayList<>();
		for (Module module : ModuleManager.modules()) {
			if (module.showsKeybindControl() && module != bindingModule && module.keybind().conflictsWith(bound)) {
				others.add(module.name());
			}
		}
		for (MacroDefinition macro : MacrosModule.INSTANCE.macros()) {
			if (macro != bindingMacro && macro.keybind().conflictsWith(bound)) {
				others.add("the macro \"" + macro.name() + "\"");
			}
		}
		if (others.isEmpty()) return;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.gui == null) return;
		String owner = bindingModule != null ? bindingModule.name()
			: bindingMacro != null ? "the macro \"" + bindingMacro.name() + "\"" : "the new hotkey";
		minecraft.gui.getChat().addClientSystemMessage(Component.literal(
			"[GeilerAddons] " + bound.displayName() + " is also bound to "
				+ String.join(", ", others) + ", so " + owner + " will not be the only thing it triggers."));
	}

	private static boolean isEscape(KeyEvent event) {
		return event.key() == InputConstants.KEY_ESCAPE;
	}

	private static boolean isUsable(KeyEvent event) {
		return event.key() != GLFW.GLFW_KEY_UNKNOWN
			&& event.key() != InputConstants.KEY_ESCAPE;
	}

	/** Macro Key nodes may press a modifier by itself; activation hotkeys still require a main key. */
	private static boolean isUsableMacroStepKey(KeyEvent event) {
		return canBindMacroStepKey(event.key());
	}

	public static boolean canBindMacroStepKey(int keyCode) {
		return keyCode != GLFW.GLFW_KEY_UNKNOWN && keyCode != InputConstants.KEY_ESCAPE;
	}
}
