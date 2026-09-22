package geiler.addons.client.module.impl;

import geiler.addons.client.config.ModConfig;
import geiler.addons.client.gui.InventoryButtonPickerScreen;
import geiler.addons.client.gui.InventoryButtonOverlay;
import geiler.addons.client.gui.InventoryButtonTextScreen;
import geiler.addons.client.macro.MacroDefinition;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.ModuleAction;
import geiler.addons.client.module.SettingGroup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.fabricmc.loader.api.FabricLoader;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;

/** Local, player-inventory-only macro buttons. Layout is deliberately not part of macro transfer. */
public final class InventoryButtonsModule extends Module {
	public static final InventoryButtonsModule INSTANCE = new InventoryButtonsModule();

	private final List<InventoryButtonPlacement> placements = new ArrayList<>();
	private final ModuleAction editLayout;
	private int nextId;

	private InventoryButtonsModule() {
		this(new ModuleAction("Edit Inventory Layout", "Open the player inventory and place or move 18×18 macro buttons.",
			() -> INSTANCE.openEditor()));
	}

	private InventoryButtonsModule(ModuleAction editLayout) {
		super("Inventory Buttons", "Place local macro buttons on the standard player inventory screen.",
			Category.VISUAL, editLayout);
		this.editLayout = editLayout;
	}

	public List<InventoryButtonPlacement> placements() { return List.copyOf(placements); }

	public InventoryButtonPlacement placement(int id) {
		for (InventoryButtonPlacement placement : placements) if (placement.id() == id) return placement;
		return null;
	}

	public InventoryButtonPlacement create(int macroId, int gridX, int gridY,
		InventoryButtonPlacement.Appearance appearance, String value) {
		InventoryButtonPlacement placement = new InventoryButtonPlacement(nextId++);
		placement.setMacroId(macroId);
		placement.setGrid(gridX, gridY);
		placement.setAppearance(appearance, value);
		placements.add(placement);
		ModConfig.markDirty();
		return placement;
	}

	public void restore(List<InventoryButtonPlacement> restored) {
		placements.clear();
		if (restored != null) {
			java.util.HashSet<Integer> ids = new java.util.HashSet<>();
			for (InventoryButtonPlacement placement : restored) {
				if (placement == null || !ids.add(placement.id())) continue;
				placements.add(new InventoryButtonPlacement(placement));
			}
		}
		nextId = 0;
		for (InventoryButtonPlacement placement : placements) nextId = Math.max(nextId, placement.id() + 1);
	}

	public void remove(int id) {
		if (placements.removeIf(placement -> placement.id() == id)) ModConfig.markDirty();
	}

	public void unassignMacro(int macroId) {
		boolean changed = false;
		for (InventoryButtonPlacement placement : placements) {
			if (placement.macroId() == macroId) {
				placement.setMacroId(-1);
				changed = true;
			}
		}
		if (changed) ModConfig.markDirty();
	}

	/** Accepts only a single PNG filename within the dedicated user icon directory. */
	public boolean isPngIconAllowed(String fileName) {
		if (fileName == null || fileName.isBlank()) return false;
		Path name;
		try { name = Path.of(fileName); }
		catch (RuntimeException ignored) { return false; }
		if (name.isAbsolute() || name.getNameCount() != 1 || !name.getFileName().toString().equals(fileName)) return false;
		return fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".png");
	}

	public Path iconDirectory() {
		return FabricLoader.getInstance().getConfigDir().resolve("geileraddons").resolve("inventory-button-icons");
	}

	@Override
	public List<SettingGroup> groups() {
		List<SettingGroup> groups = new ArrayList<>(placements.size() + 1);
		groups.add(new SettingGroup(null, new ModuleAction("Edit Layout · " + placements.size() + " buttons",
			editLayout.description(), editLayout.onClick())));
		for (InventoryButtonPlacement placement : placements) {
			MacroDefinition macro = MacrosModule.INSTANCE.macro(placement.macroId());
			String target = macro == null ? "Unassigned" : macro.name();
			ModuleAction edit = new ModuleAction("Edit in Inventory", "Open the layout editor with this button selected.",
				() -> openEditor(placement.id()));
			ModuleAction assign = new ModuleAction("Choose Macro", "Bind this button to an existing macro.",
				() -> {
					Screen parent = Minecraft.getInstance().screen;
					Minecraft.getInstance().setScreen(new InventoryButtonPickerScreen(parent,
						InventoryButtonPickerScreen.Mode.MACRO, value -> {
							try { placement.setMacroId(Integer.parseInt(value)); }
							catch (NumberFormatException ignored) { placement.setMacroId(-1); }
							ModConfig.markDirty();
						}));
				});
			ModuleAction tooltip = new ModuleAction("Set Hover Tooltip", "Edit the custom hover text shown with macro status.",
				() -> openTooltipEditor(placement));
			ModuleAction unassign = new ModuleAction("Unassign Macro", "Keep this placement but clear its macro binding.",
				() -> {
					placement.setMacroId(-1);
					ModConfig.markDirty();
				});
			ModuleAction delete = new ModuleAction("Delete Button", "Remove this local placement.", () -> remove(placement.id()));
			groups.add(new SettingGroup("Button " + placement.id() + " · " + target,
				null, true, List.of(edit, assign, tooltip, unassign, delete), List.of(), false,
				"inventory-button:" + placement.id()));
		}
		return groups;
	}

	private void openEditor() {
		openEditor(-1);
	}

	private void openEditor(int placementId) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) return;
		net.minecraft.client.gui.screens.inventory.InventoryScreen editorScreen =
			new net.minecraft.client.gui.screens.inventory.InventoryScreen(minecraft.player);
		InventoryButtonOverlay.beginEditing(placementId, minecraft.screen, editorScreen);
		minecraft.setScreen(editorScreen);
	}

	private static void openTooltipEditor(InventoryButtonPlacement placement) {
		Minecraft minecraft = Minecraft.getInstance();
		Screen parent = minecraft.screen;
		minecraft.setScreen(new InventoryButtonTextScreen(parent, "Inventory Button Tooltip",
			"Shown above the macro name and eligibility when the button is hovered.",
			placement.hoverTooltip(), value -> {
				placement.setHoverTooltip(value);
				ModConfig.markDirty();
			}));
	}

	public void tick() { InventoryButtonOverlay.tick(); }
}
