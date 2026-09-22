package geiler.addons.client.gui;

import geiler.addons.client.module.impl.VisualModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static geiler.addons.client.gui.GuiTheme.*;

/** Searchable picker backed by the registered block registry, not substring runtime matching. */
public final class BlockPickerScreen extends Screen {
	private static final int ROW_HEIGHT = 20;
	private final Screen parent;
	private final Consumer<String> selected;
	private final List<Option> options = new ArrayList<>();
	private EditBox search;
	private String filter = "";
	private int scroll;
	private Rect panel;

	public BlockPickerScreen(Screen parent, Consumer<String> selected) {
		super(Component.literal("Choose Block"));
		this.parent = parent;
		this.selected = selected;
		for (Block block : BuiltInRegistries.BLOCK) {
			if (block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR || block.asItem() == Items.AIR) continue;
			Identifier id = BuiltInRegistries.BLOCK.getKey(block);
			if (id != null) options.add(new Option(id.toString(), block.getName().getString()));
		}
		options.sort(Comparator.comparing((Option option) -> option.name.toLowerCase(Locale.ROOT))
			.thenComparing(option -> option.id));
	}

	@Override
	public boolean isPauseScreen() { return false; }

	@Override
	protected void init() {
		panel = panel();
		search = new EditBox(font, panel.x + 10, panel.y + 34, panel.w - 20, 18,
			Component.literal("Search blocks by name or ID"));
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
		graphics.text(font, trim(font, "Choose Block", panel.w - 20), panel.x + 10, panel.y + 9, TEXT_PRIMARY);
		graphics.text(font, trim(font, "Filter by localized name or technical identifier.", panel.w - 20),
			panel.x + 10, panel.y + 21, TEXT_MUTED);
		List<Option> visible = visibleOptions();
		Rect view = viewRect();
		int maxScroll = Math.max(0, visible.size() - Math.max(1, view.h / ROW_HEIGHT));
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		graphics.enableScissor(view.x, view.y, view.x + view.w, view.y + view.h);
		for (int i = scroll; i < Math.min(visible.size(), scroll + Math.max(1, view.h / ROW_HEIGHT)); i++) {
			Option option = visible.get(i);
			int y = view.y + (i - scroll) * ROW_HEIGHT;
			boolean hover = mouseX >= view.x && mouseX < view.x + view.w && mouseY >= y && mouseY < y + ROW_HEIGHT;
			if (hover) roundedRect(graphics, view.x, y, view.w - 4, ROW_HEIGHT - 1, RADIUS_SMALL, CATEGORY_HOVER);
			int idWidth = Math.min(140, Math.max(64, view.w * 2 / 5));
			int nameWidth = Math.max(1, view.w - idWidth - 18);
			graphics.text(font, trim(font, option.name, nameWidth), view.x + 7, y + 5,
				hover ? TEXT_ON_ACCENT : TEXT_PRIMARY);
			graphics.text(font, trim(font, option.id, idWidth), view.x + view.w - idWidth - 7, y + 5,
				hover ? TEXT_ON_ACCENT : TEXT_MUTED);
		}
		graphics.disableScissor();
		if (visible.isEmpty()) graphics.text(font, "No matching blocks.", view.x + 8, view.y + 8, TEXT_MUTED);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	private List<Option> visibleOptions() {
		String needle = filter.toLowerCase(Locale.ROOT).trim();
		if (needle.isEmpty()) return options;
		return options.stream().filter(option -> option.name.toLowerCase(Locale.ROOT).contains(needle)
			|| option.id.toLowerCase(Locale.ROOT).contains(needle)).toList();
	}

	private Rect panel() {
		int w = Math.min(620, Math.max(1, width - 20));
		int h = Math.min(430, Math.max(1, height - 20));
		return new Rect((width - w) / 2, (height - h) / 2, w, h);
	}

	private Rect viewRect() { return new Rect(panel.x + 8, panel.y + 58, panel.w - 16, panel.h - 68); }

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int x = (int) event.x();
		int y = (int) event.y();
		Rect view = viewRect();
		if (event.button() == 0 && view.contains(x, y)) {
			List<Option> visible = visibleOptions();
			int index = scroll + (y - view.y) / ROW_HEIGHT;
			if (index >= 0 && index < visible.size()) {
				selected.accept(visible.get(index).id);
				if (minecraft != null) minecraft.setScreen(parent);
			}
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		scroll = Math.max(0, Math.min(Math.max(0, visibleOptions().size() - 1), scroll - (int) Math.signum(scrollY) * 3));
		return true;
	}

	@Override
	public void onClose() { if (minecraft != null) minecraft.setScreen(parent); }

	private static String trim(Font font, String text, int maxWidth) {
		if (font.width(text) <= maxWidth) return text;
		int end = text.length();
		while (end > 0 && font.width(text.substring(0, end) + "…") > maxWidth) end--;
		return end == 0 ? "…" : text.substring(0, end) + "…";
	}

	private record Option(String id, String name) { }
	private record Rect(int x, int y, int w, int h) {
		boolean contains(int px, int py) { return px >= x && py >= y && px < x + w && py < y + h; }
	}
}
