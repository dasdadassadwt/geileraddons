package geiler.addons.client.module.impl;

import geiler.addons.client.config.ModConfig;
import geiler.addons.client.gui.MacroEditorScreen;
import geiler.addons.client.location.Island;
import geiler.addons.client.macro.MacroDefinition;
import geiler.addons.client.macro.MacroRunner;
import geiler.addons.client.macro.MacroTriggerContext;
import geiler.addons.client.macro.MacroTransfer;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ChoiceSetting;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.ModuleAction;
import geiler.addons.client.module.ModuleKeybindManager;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.module.TextSetting;
import geiler.addons.client.gui.MacroTransferScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Stores and exposes user-authored keyboard workflows. */
public final class MacrosModule extends Module {
	public static final MacrosModule INSTANCE = new MacrosModule();

	private static final String[] CONTEXT_CHOICES = {
		"World only (no screen)", "Container/inventory screens", "World or container", "Any non-text screen"
	};
	private final List<MacroDefinition> macros = new ArrayList<>();
	private final Map<Integer, Controls> controls = new HashMap<>();
	private final BooleanSetting systemEnabled;
	private final ModuleAction create;
	private final ModuleAction transfer;
	private int nextId;

	private MacrosModule() {
		this(new BooleanSetting("Enable Macro System", true),
			new ModuleAction("Create Macro", "Create a new macro workflow.", () -> INSTANCE.create()),
			new ModuleAction("Share / Paste Macros",
				"Select several macros, copy them to the clipboard, or append a shared package.",
				() -> INSTANCE.openTransfer()));
	}

	private MacrosModule(BooleanSetting systemEnabled, ModuleAction create, ModuleAction transfer) {
		super("Macros", "Build workflows from Minecraft inputs. Each node has its own optional delay and start context.",
			Category.MISCELLANEOUS, systemEnabled, create, transfer);
		this.systemEnabled = systemEnabled;
		this.create = create;
		this.transfer = transfer;
	}

	public List<MacroDefinition> macros() {
		syncSettings();
		return List.copyOf(macros);
	}

	public MacroDefinition macro(int id) {
		for (MacroDefinition macro : macros) if (macro.id() == id) return macro;
		return null;
	}

	private void create() {
		MacroDefinition macro = new MacroDefinition(nextId++);
		macros.add(macro);
		controls.put(macro.id(), new Controls(macro));
		ModConfig.markDirty();
	}

	private void remove(MacroDefinition macro) {
		if (MacroRunner.activeMacro() == macro) MacroRunner.cancel("macro deleted");
		macros.remove(macro);
		controls.remove(macro.id());
		ModConfig.markDirty();
	}

	public void restore(List<MacroDefinition> restored) {
		macros.clear();
		controls.clear();
		if (restored != null) macros.addAll(restored);
		nextId = 0;
		for (MacroDefinition macro : macros) {
			nextId = Math.max(nextId, macro.id() + 1);
			controls.put(macro.id(), new Controls(macro));
		}
	}

	@Override
	public List<SettingGroup> groups() {
		syncSettings();
		List<SettingGroup> groups = new ArrayList<>(macros.size() + 1);
		groups.add(new SettingGroup(null, systemEnabled, create, transfer));
		for (MacroDefinition macro : macros) {
			Controls c = controls.computeIfAbsent(macro.id(), ignored -> new Controls(macro));
			String captureLabel = ModuleKeybindManager.bindingMacro() == macro
				? "Listening… click to cancel" : "Hotkey: " + macro.keybind().displayName();
			ModuleAction capture = new ModuleAction(captureLabel,
				"Click, then press and release a key or key combination. Press Escape to clear it; click again to cancel listening.",
				() -> {
					if (ModuleKeybindManager.bindingMacro() == macro) ModuleKeybindManager.cancelBinding();
					else ModuleKeybindManager.beginMacroBinding(macro);
				});
			ModuleAction edit = new ModuleAction("Edit Workflow",
				"Open the node editor to add inputs, set node delays, edit conditions, and build examples.",
				() -> openEditor(macro));
			ModuleAction delete = new ModuleAction("Delete Macro",
				"Remove this macro and its workflow. A running instance is stopped first.",
				() -> remove(macro));
			SettingGroup macroGroup = new SettingGroup("Macro " + macro.id() + " • " + macro.name(),
				c.enabled, c.name, c.context,
				c.islandRestricted, capture, edit, delete);
			SettingGroup islands = SettingGroup.folded("Islands",
				c.islands.values().toArray(new Setting[0]));
			groups.add(macroGroup.containing(islands));
		}
		return groups;
	}

	private void openEditor(MacroDefinition macro) {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.setScreen(new MacroEditorScreen(minecraft.screen, macro));
	}

	private void openTransfer() {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.setScreen(new MacroTransferScreen(minecraft.screen));
	}

	/** Appends imported macros with fresh ids and leaves all existing macros untouched. */
	public MacroTransfer.ImportResult importEncoded(String payload) {
		syncSettings();
		MacroTransfer.ImportResult result = MacroTransfer.decode(payload, nextId);
		if (!result.success()) return result;
		for (MacroDefinition macro : result.macros()) {
			macros.add(macro);
			controls.put(macro.id(), new Controls(macro));
		}
		nextId += result.macros().size();
		ModConfig.markDirty();
		return result;
	}

	/** Copies the flat settings rows back to their richer macro objects before a config snapshot. */
	public void syncSettings() {
		for (MacroDefinition macro : macros) {
			Controls c = controls.get(macro.id());
			if (c == null) continue;
			macro.setEnabled(c.enabled.value());
			macro.setName(c.name.value());
			macro.setTriggerContext(context(c.context.value()));
			macro.setIslandRestricted(c.islandRestricted.value());
			EnumSet<Island> selected = EnumSet.noneOf(Island.class);
			for (Map.Entry<Island, BooleanSetting> island : c.islands.entrySet()) {
				if (island.getValue().value()) selected.add(island.getKey());
			}
			macro.setIslands(selected);
		}
	}

	private static MacroTriggerContext context(String value) {
		return switch (value == null ? "" : value) {
			case "World only", "World only (no screen)" -> MacroTriggerContext.WORLD_ONLY;
			case "Container only", "Container/inventory screens" -> MacroTriggerContext.CONTAINER_ONLY;
			case "World + containers", "World or container" -> MacroTriggerContext.WORLD_AND_CONTAINER;
			default -> MacroTriggerContext.ANY_NON_TEXT_SCREEN;
		};
	}

	private static String contextLabel(MacroTriggerContext context) {
		return context.label();
	}

	@Override
	protected void onDisable() {
		MacroRunner.cancel("module disabled");
	}

	@Override
	public boolean isActive() {
		return isEnabled() && systemEnabled.value();
	}

	@Override
	public String inactiveReason() {
		return isEnabled() && !systemEnabled.value() ? "Macro System is disabled." : null;
	}

	public void tick() {
		syncSettings();
		if (!isActive()) {
			if (MacroRunner.isRunning()) MacroRunner.cancel("macro system disabled");
			return;
		}
		MacroRunner.tick();
	}

	public boolean handleKey(KeyEvent event, int action) {
		if (!isActive()) return false;
		syncSettings();
		return MacroRunner.handleKeyEvent(Minecraft.getInstance(), action, event);
	}

	private static final class Controls {
		private final BooleanSetting enabled;
		private final TextSetting name;
		private final ChoiceSetting context;
		private final BooleanSetting islandRestricted;
		private final EnumMap<Island, BooleanSetting> islands = new EnumMap<>(Island.class);

		private Controls(MacroDefinition macro) {
			enabled = new BooleanSetting("Macro Enabled", macro.enabled());
			name = new TextSetting("Macro Name", "Rename Macro", macro.name(), 48);
			context = new ChoiceSetting("Trigger Context", contextLabel(macro.triggerContext()), CONTEXT_CHOICES);
			islandRestricted = new BooleanSetting("Island Filter", macro.islandRestricted());
			for (Island island : Island.values()) {
				if (island.selectable()) {
					islands.put(island, new BooleanSetting(island.label(), macro.islands().contains(island)));
				}
			}
		}
	}
}
