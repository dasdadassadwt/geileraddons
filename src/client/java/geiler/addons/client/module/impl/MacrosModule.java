package geiler.addons.client.module.impl;

import geiler.addons.client.collections.FolderTree;
import geiler.addons.client.config.ModConfig;
import geiler.addons.client.gui.FolderManagerScreen;
import geiler.addons.client.gui.ScratchMacroEditorScreen;
import geiler.addons.client.location.Island;
import geiler.addons.client.macro.MacroDefinition;
import geiler.addons.client.macro.MacroFunction;
import geiler.addons.client.macro.MacroScript;
import geiler.addons.client.macro.MacroStep;
import geiler.addons.client.macro.MacroRunner;
import geiler.addons.client.macro.MacroTriggerContext;
import geiler.addons.client.macro.MacroTransfer;
import geiler.addons.client.macro.MacroVariableStore;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ChoiceSetting;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.ModuleAction;
import geiler.addons.client.module.impl.InventoryButtonsModule;
import geiler.addons.client.module.ModuleKeybindManager;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.module.TextSetting;
import geiler.addons.client.gui.MacroTransferScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HashMap;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Stores and exposes user-authored keyboard workflows. */
public final class MacrosModule extends Module {
	public static final MacrosModule INSTANCE = new MacrosModule();

	private static final String[] CONTEXT_CHOICES = {
		"World only (no screen)", "Container/inventory screens", "World or container", "Any non-text screen"
	};
	private final List<MacroDefinition> macros = new ArrayList<>();
	private final List<MacroFunction> functions = new ArrayList<>();
	private final MacroVariableStore globalVariables = new MacroVariableStore(ModConfig::markDirty);
	private final Map<Integer, Controls> controls = new HashMap<>();
	private final BooleanSetting systemEnabled;
	private final ModuleAction create;
	private final ModuleAction transfer;
	private final ModuleAction foldersAction;
	private final FolderTree folders = new FolderTree();
	private int nextId;

	private MacrosModule() {
		this(new BooleanSetting("Enable Macro System", true),
			new ModuleAction("Create Macro", "Create a new macro workflow.", () -> INSTANCE.create()),
			new ModuleAction("Share / Paste Macros",
				"Select several macros, copy them to the clipboard, or append a shared package.",
				() -> INSTANCE.openTransfer()),
			new ModuleAction("Manage Folders", "Organize macros into nested folders.", () -> INSTANCE.openFolders()));
	}

	private MacrosModule(BooleanSetting systemEnabled, ModuleAction create, ModuleAction transfer,
		ModuleAction foldersAction) {
		super("Macros", "Build workflows from Minecraft inputs. Each node has its own optional delay and start context.",
			Category.MISCELLANEOUS, systemEnabled, create, transfer, foldersAction);
		this.systemEnabled = systemEnabled;
		this.create = create;
		this.transfer = transfer;
		this.foldersAction = foldersAction;
	}

	public List<MacroDefinition> macros() {
		syncSettings();
		return List.copyOf(macros);
	}

	public MacroDefinition macro(int id) {
		for (MacroDefinition macro : macros) if (macro.id() == id) return macro;
		return null;
	}

	public List<MacroFunction> functions() { return List.copyOf(functions); }
	/** Shared variable definitions and values, available to all macros and function calls. */
	public MacroVariableStore globalVariables() { return globalVariables; }
	public MacroFunction function(String id) {
		if (id == null) return null;
		for (MacroFunction function : functions) if (function.id().equals(id)) return function;
		return null;
	}
	public MacroFunction createFunction() {
		MacroFunction function = new MacroFunction();
		function.setName("My Block " + (functions.size() + 1));
		functions.add(function);
		ModConfig.markDirty();
		return function;
	}
	public void restoreFunctions(List<MacroFunction> restored) {
		functions.clear();
		if (restored != null) functions.addAll(restored);
	}

	public FolderTree folders() { return folders; }

	private void create() {
		createMacro();
	}

	/** Creates and registers a blank macro so another editor can immediately open its workflow. */
	public MacroDefinition createMacro() {
		MacroDefinition macro = new MacroDefinition(nextId++);
		macros.add(macro);
		controls.put(macro.id(), new Controls(macro));
		ModConfig.markDirty();
		return macro;
	}

	private void remove(MacroDefinition macro) {
		MacroRunner.cancelMacro(macro, "macro deleted");
		macros.remove(macro);
		controls.remove(macro.id());
		InventoryButtonsModule.INSTANCE.unassignMacro(macro.id());
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
		List<SettingGroup> groups = new ArrayList<>(macros.size() + folders.folders().size() + 1);
		groups.add(new SettingGroup(null, systemEnabled, create, transfer, foldersAction));
		for (FolderTree.Folder folder : folders.childrenOf(null)) groups.add(folderGroup(folder));
		for (MacroDefinition macro : macros) if (!folders.contains(macro.folderId())) groups.add(macroGroup(macro));
		return groups;
	}

	private SettingGroup folderGroup(FolderTree.Folder folder) {
		List<SettingGroup> children = new ArrayList<>();
		for (FolderTree.Folder child : folders.childrenOf(folder.id())) children.add(folderGroup(child));
		for (MacroDefinition macro : macros) if (folder.id().equals(macro.folderId())) children.add(macroGroup(macro));
		return new SettingGroup(folder.name(), null, false, List.of(), children, false,
			"macro-folder:" + folder.id());
	}

	private SettingGroup macroGroup(MacroDefinition macro) {
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
			return macroGroup.containing(islands).keyed("macro-entry:" + macro.id());
	}

	private void openEditor(MacroDefinition macro) {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.setScreen(new ScratchMacroEditorScreen(minecraft.screen, macro));
	}

	private void openTransfer() {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.setScreen(new MacroTransferScreen(minecraft.screen));
	}

	private void openFolders() {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.setScreen(new FolderManagerScreen(minecraft.screen, "Macros", folders,
			() -> macros.stream().map(macro -> new FolderManagerScreen.Entry(
				Integer.toString(macro.id()), "Macro " + macro.id() + " • " + macro.name(), macro.folderId())).toList(),
			(entryId, folderId) -> {
				try {
					MacroDefinition macro = macro(Integer.parseInt(entryId));
					if (macro != null) macro.setFolderId(folderId);
				} catch (NumberFormatException ignored) { }
			}, ModConfig::markDirty));
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
		for (MacroFunction function : result.functions()) if (this.function(function.id()) == null) functions.add(function);
		globalVariables.importDefinitions(result.globalVariables());
		nextId += result.macros().size();
		ModConfig.markDirty();
		return result;
	}

	/** Exports selected macros together with every referenced macro and reusable function. */
	public String exportEncoded(Iterable<MacroDefinition> selected) {
		Map<Integer, MacroDefinition> knownMacros = new HashMap<>();
		for (MacroDefinition macro : macros()) knownMacros.put(macro.id(), macro);
		Map<String, MacroFunction> knownFunctions = new HashMap<>();
		for (MacroFunction function : functions()) knownFunctions.put(function.id(), function);
		LinkedHashMap<Integer, MacroDefinition> includedMacros = new LinkedHashMap<>();
		LinkedHashMap<String, MacroFunction> includedFunctions = new LinkedHashMap<>();
		java.util.LinkedHashSet<String> globalVariableIds = new java.util.LinkedHashSet<>();
		ArrayDeque<Integer> macroQueue = new ArrayDeque<>();
		ArrayDeque<String> functionQueue = new ArrayDeque<>();
		if (selected != null) for (MacroDefinition macro : selected) if (macro != null) macroQueue.add(macro.id());
		while ((!macroQueue.isEmpty() || !functionQueue.isEmpty())
			&& includedMacros.size() < 64 && includedFunctions.size() < 256) {
			if (!macroQueue.isEmpty()) {
				int id = macroQueue.removeFirst();
				MacroDefinition macro = knownMacros.get(id);
				if (macro == null || includedMacros.putIfAbsent(id, macro) != null) continue;
				for (MacroScript script : macro.scripts()) collectReferences(script.steps(), macroQueue, functionQueue, globalVariableIds, 0);
			} else {
				String id = functionQueue.removeFirst();
				MacroFunction function = knownFunctions.get(id);
				if (function == null || includedFunctions.putIfAbsent(id, function) != null) continue;
				collectReferences(function.steps(), macroQueue, functionQueue, globalVariableIds, 0);
			}
		}
		List<MacroVariableStore.Definition> includedVariables = new ArrayList<>();
		for (String id : globalVariableIds) {
			MacroVariableStore.Definition definition = globalVariables.definition(id);
			if (definition != null) includedVariables.add(definition);
		}
		return MacroTransfer.encode(includedMacros.values(), includedFunctions.values(), includedVariables);
	}

	private static void collectReferences(List<MacroStep> steps, ArrayDeque<Integer> macroQueue,
		ArrayDeque<String> functionQueue, Set<String> globalVariableIds, int depth) {
		if (steps == null || depth > 32) return;
		for (MacroStep step : steps) {
			if (step instanceof MacroStep.Command command) {
				collectGlobalTemplateReferences(command.command(), globalVariableIds);
			} else if (step instanceof MacroStep.Chat chat) {
				collectGlobalTemplateReferences(chat.message(), globalVariableIds);
			} else if (step instanceof MacroStep.SetVariable set) {
				if (set.globalVariableId() != null) globalVariableIds.add(set.globalVariableId());
				collectValueReference(set.value(), globalVariableIds);
			} else if (step instanceof MacroStep.ChangeVariable change) {
				if (change.globalVariableId() != null) globalVariableIds.add(change.globalVariableId());
			} else if (step instanceof MacroStep.FunctionCall call) {
				if (!call.functionId().isBlank()) functionQueue.add(call.functionId());
				for (geiler.addons.client.macro.MacroValue argument : call.arguments()) collectValueReference(argument, globalVariableIds);
			} else if (step instanceof MacroStep.MacroCall call && call.macroId() >= 0) macroQueue.add(call.macroId());
			else if (step instanceof MacroStep.IfElse branch) {
				collectConditionReference(branch.condition(), globalVariableIds);
				collectReferences(branch.thenSteps(), macroQueue, functionQueue, globalVariableIds, depth + 1);
				collectReferences(branch.elseSteps(), macroQueue, functionQueue, globalVariableIds, depth + 1);
			} else if (step instanceof MacroStep.Repeat repeat) {
				collectReferences(repeat.steps(), macroQueue, functionQueue, globalVariableIds, depth + 1);
			} else if (step instanceof MacroStep.RepeatUntil repeatUntil) {
				collectConditionReference(repeatUntil.condition(), globalVariableIds);
				collectReferences(repeatUntil.steps(), macroQueue, functionQueue, globalVariableIds, depth + 1);
			} else if (step instanceof MacroStep.WaitUntil waitUntil) {
				collectConditionReference(waitUntil.condition(), globalVariableIds);
			}
		}
	}

	private static void collectConditionReference(geiler.addons.client.macro.MacroCondition condition,
		Set<String> globalVariableIds) {
		if (condition instanceof geiler.addons.client.macro.MacroCondition.Variable variable) {
			if (variable.globalVariableId() != null) globalVariableIds.add(variable.globalVariableId());
			collectValueReference(variable.value(), globalVariableIds);
		} else if (condition instanceof geiler.addons.client.macro.MacroCondition.All all) {
			for (geiler.addons.client.macro.MacroCondition child : all.children()) collectConditionReference(child, globalVariableIds);
		} else if (condition instanceof geiler.addons.client.macro.MacroCondition.Any any) {
			for (geiler.addons.client.macro.MacroCondition child : any.children()) collectConditionReference(child, globalVariableIds);
		} else if (condition instanceof geiler.addons.client.macro.MacroCondition.Not not) {
			collectConditionReference(not.child(), globalVariableIds);
		}
	}

	private static void collectValueReference(geiler.addons.client.macro.MacroValue value, Set<String> globalVariableIds) {
		if (value != null && value.variableReference() && value.scope() == geiler.addons.client.macro.MacroValue.Scope.GLOBAL) {
			globalVariableIds.add(value.value());
		}
	}

	private static void collectGlobalTemplateReferences(String text, Set<String> globalVariableIds) {
		if (text == null) return;
		for (int start = 0; (start = text.indexOf("${global:", start)) >= 0;) {
			int valueStart = start + "${global:".length();
			int end = text.indexOf('}', valueStart);
			if (end < 0) return;
			String id = text.substring(valueStart, end);
			if (!id.isBlank() && id.length() <= 64) globalVariableIds.add(id);
			start = end + 1;
		}
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
