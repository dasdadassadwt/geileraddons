package geiler.addons.client.gui;

import geiler.addons.client.macro.MacroDefinition;
import geiler.addons.client.module.impl.InventoryButtonsModule;
import geiler.addons.client.module.impl.MacrosModule;
import geiler.addons.client.module.impl.VisualModule;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static geiler.addons.client.gui.GuiTheme.*;

/** Registry/file-backed selector for an inventory button's macro, icon, or PNG. */
public final class InventoryButtonPickerScreen extends Screen {
	public enum Mode { MACRO, ITEM, PNG }
	private static final int ROW_HEIGHT = 22;
	private final Screen parent;
	private final Mode mode;
	private final Consumer<String> selected;
	private final Runnable createNew;
	private final Runnable cancelled;
	private final List<Option> options = new ArrayList<>();
	private EditBox search;
	private String filter = "";
	private int scroll;
	private Rect panel;

	public InventoryButtonPickerScreen(Screen parent, Mode mode, Consumer<String> selected) {
		this(parent, mode, selected, null, null);
	}

	public InventoryButtonPickerScreen(Screen parent, Mode mode, Consumer<String> selected,
		Runnable createNew, Runnable cancelled) {
		super(Component.literal(switch (mode) {
			case MACRO -> "Choose Macro";
			case ITEM -> "Choose Registered Item or Block Icon";
			case PNG -> "Choose Inventory Button PNG";
		}));
		this.parent = parent;
		this.mode = mode;
		this.selected = selected;
		this.createNew = createNew;
		this.cancelled = cancelled;
		loadOptions();
	}

	@Override public boolean isPauseScreen() { return false; }

	private void loadOptions() {
		switch (mode) {
			case MACRO -> {
				for (MacroDefinition macro : MacrosModule.INSTANCE.macros()) {
					options.add(new Option(Integer.toString(macro.id()), macro.name(),
						"id " + macro.id() + " · " + (macro.enabled() ? "enabled" : "disabled")));
				}
			}
			case ITEM -> {
				for (Item item : BuiltInRegistries.ITEM) {
					if (item == Items.AIR) continue;
					Identifier id = BuiltInRegistries.ITEM.getKey(item);
					if (id != null) options.add(new Option(id.toString(), item.getName(new ItemStack(item)).getString(), id.toString()));
				}
				options.sort(Comparator.comparing((Option option) -> option.name.toLowerCase(Locale.ROOT))
					.thenComparing(option -> option.id));
			}
			case PNG -> {
				for (String file : InventoryButtonOverlay.listPngIcons()) {
					options.add(new Option(file, file, "<Fabric config>/geileraddons/inventory-button-icons/" + file));
				}
			}
		}
	}

	@Override
	protected void init() {
		panel = panel();
		int createWidth = createNew == null ? 0 : createButtonWidth();
		int searchWidth = Math.max(40, panel.w - 20 - (createNew == null ? 0 : createWidth + 10));
		search = new EditBox(font, panel.x + 10, panel.y + 34, searchWidth, 18,
			Component.literal("Search by name or ID"));
		search.setMaxLength(128);
		search.setValue(filter);
		search.setResponder(value -> { filter = value; scroll = 0; });
		addRenderableWidget(search);
		setInitialFocus(search);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		VisualModule.INSTANCE.refreshTheme();
		panel = panel();
		graphics.fill(0, 0, width, height, DIALOG_SHADE);
		roundedRectBordered(graphics, panel.x, panel.y, panel.w, panel.h, RADIUS,
			PANEL_TOP, PANEL_BOTTOM, BORDER);
		graphics.text(font, trim(font, titleForMode(), panel.w - 20), panel.x + 10, panel.y + 9, TEXT_PRIMARY);
		graphics.text(font, trim(font, hintForMode(), panel.w - 20), panel.x + 10, panel.y + 21, TEXT_MUTED);
		if (createNew != null) {
			Rect create = createRect();
			boolean hover = create.contains(mouseX, mouseY);
			roundedRect(graphics, create.x, create.y, create.w, create.h, RADIUS_SMALL,
				hover ? BUTTON_HOVER : BUTTON_BG);
			graphics.centeredText(font, "Create Macro", create.x + create.w / 2,
				create.y + 5, hover ? TEXT_ON_ACCENT : TEXT_PRIMARY);
		}
		List<Option> visible = visibleOptions();
		Rect view = viewRect();
		int visibleRows = Math.max(1, view.h / ROW_HEIGHT);
		int maxScroll = Math.max(0, visible.size() - visibleRows);
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		graphics.enableScissor(view.x, view.y, view.x + view.w, view.y + view.h);
		for (int i = scroll; i < Math.min(visible.size(), scroll + visibleRows); i++) {
			Option option = visible.get(i);
			int y = view.y + (i - scroll) * ROW_HEIGHT;
			boolean hover = mouseX >= view.x && mouseX < view.x + view.w && mouseY >= y && mouseY < y + ROW_HEIGHT;
			if (hover) roundedRect(graphics, view.x, y, view.w - 4, ROW_HEIGHT - 1, RADIUS_SMALL, CATEGORY_HOVER);
			int detailWidth = Math.min(140, Math.max(56, view.w * 2 / 5));
			int nameWidth = Math.max(1, view.w - detailWidth - 20);
			graphics.text(font, trim(font, option.name, nameWidth), view.x + 7, y + 3,
				hover ? TEXT_ON_ACCENT : TEXT_PRIMARY);
			graphics.text(font, trim(font, option.detail, detailWidth), view.x + view.w - detailWidth - 7, y + 3,
				hover ? TEXT_ON_ACCENT : TEXT_MUTED);
			if (mode == Mode.MACRO && i < visible.size()) {
				graphics.text(font, option.detail.endsWith("disabled") ? "Not runnable" : "Macro button",
					view.x + 7, y + 12, TEXT_MUTED);
			}
		}
		graphics.disableScissor();
		if (visible.isEmpty()) graphics.text(font, emptyMessage(), view.x + 8, view.y + 8, TEXT_MUTED);
		if (mode == Mode.PNG) graphics.text(font, "PNG files only; texture IDs cannot be entered here.",
			panel.x + 10, panel.y + panel.h - 14, TEXT_MUTED);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	private List<Option> visibleOptions() {
		String needle = filter.toLowerCase(Locale.ROOT).trim();
		if (needle.isEmpty()) return options;
		return options.stream().filter(option -> option.name.toLowerCase(Locale.ROOT).contains(needle)
			|| option.id.toLowerCase(Locale.ROOT).contains(needle)
			|| option.detail.toLowerCase(Locale.ROOT).contains(needle)).toList();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int x = (int) event.x();
		int y = (int) event.y();
		Rect view = viewRect();
		if (event.button() == 0 && createNew != null && createRect().contains(x, y)) {
			createNew.run();
			return true;
		}
		if (event.button() == 0 && view.contains(x, y)) {
			List<Option> visible = visibleOptions();
			int index = scroll + (y - view.y) / ROW_HEIGHT;
			if (index >= 0 && index < visible.size()) {
				String value = visible.get(index).id;
				if (minecraft != null) minecraft.setScreen(parent);
				selected.accept(value);
			}
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		int visible = Math.max(1, viewRect().h / ROW_HEIGHT);
		scroll = Math.max(0, Math.min(Math.max(0, visibleOptions().size() - visible),
			scroll - (int) Math.signum(scrollY) * 3));
		return true;
	}

	@Override public void onClose() {
		if (cancelled != null) cancelled.run();
		if (minecraft != null) minecraft.setScreen(parent);
	}

	private Rect panel() {
		int w = Math.min(620, Math.max(1, width - 20));
		int h = Math.min(430, Math.max(1, height - 20));
		return new Rect((width - w) / 2, (height - h) / 2, w, h);
	}
	private Rect viewRect() { return new Rect(panel.x + 8, panel.y + 58, panel.w - 16, panel.h - (mode == Mode.PNG ? 83 : 68)); }
	private Rect createRect() { int w = createButtonWidth(); return new Rect(panel.x + panel.w - w - 10, panel.y + 34, w, 18); }
	private int createButtonWidth() { return Math.min(102, Math.max(52, panel.w / 3)); }
	private String titleForMode() {
		return switch (mode) {
			case MACRO -> "Choose Macro";
			case ITEM -> "Choose Registered Item or Block Icon";
			case PNG -> "Choose PNG Icon";
		};
	}
	private String hintForMode() {
		return switch (mode) {
			case MACRO -> "The macro's stable numeric ID will be stored in this local layout.";
			case ITEM -> "Search localized item names or technical registry identifiers.";
			case PNG -> "Choose an image file from the GeilerAddons config icon folder.";
		};
	}
	private String emptyMessage() {
		return mode == Mode.PNG && options.isEmpty() ? "No PNG files yet. Add one to the config icon folder." : "No matching entries.";
	}
	private static String trim(Font font, String text, int maxWidth) {
		if (font.width(text) <= maxWidth) return text;
		int end = text.length();
		while (end > 0 && font.width(text.substring(0, end) + "…") > maxWidth) end--;
		return end == 0 ? "…" : text.substring(0, end) + "…";
	}

	private record Option(String id, String name, String detail) { }
	private record Rect(int x, int y, int w, int h) {
		boolean contains(int px, int py) { return px >= x && px < x + w && py >= y && py < y + h; }
	}
}
