package geiler.addons.client.gui;

import geiler.addons.client.config.ModConfig;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.ModuleAction;
import geiler.addons.client.module.ModuleManager;
import geiler.addons.client.module.NumberSetting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

import static geiler.addons.client.gui.GuiTheme.*;

public class ClickGuiScreen extends Screen {
	private static final int CATEGORY_WIDTH = 100;
	private static final int MODULE_WIDTH = 190;
	private static final int PANEL_HEIGHT = 260;
	private static final int ROW_HEIGHT = 26;
	private static final int PADDING = 6;
	private static final int CHANNEL_ROW_HEIGHT = 14;
	private static final int SCROLLBAR_WIDTH = 3;

	private Category selectedCategory = Category.F7;
	private Module openSettingsModule;
	private ColorSetting expandedColorSetting;
	private ColorSetting.Channel draggingChannel;
	private NumberSetting draggingNumberSetting;
	private int settingsScroll;

	public ClickGuiScreen() {
		super(Component.literal("GeilerAddons"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int panelX = panelX();
		int panelY = panelY();
		int rightX = panelX + CATEGORY_WIDTH;
		Font font = this.font;

		roundedRect(graphics, panelX, panelY, CATEGORY_WIDTH, PANEL_HEIGHT, RADIUS, PANEL_TOP, PANEL_BOTTOM);
		outline(graphics, panelX, panelY, CATEGORY_WIDTH, PANEL_HEIGHT, RADIUS, BORDER);

		roundedRect(graphics, rightX, panelY, MODULE_WIDTH, PANEL_HEIGHT, RADIUS, MODULE_PANEL_TOP, MODULE_PANEL_BOTTOM);
		outline(graphics, rightX, panelY, MODULE_WIDTH, PANEL_HEIGHT, RADIUS, BORDER);

		List<Rect> categoryRows = categoryRows(panelX, panelY);
		Category[] categories = Category.values();
		for (int i = 0; i < categories.length; i++) {
			Rect row = categoryRows.get(i);
			Category category = categories[i];
			boolean selected = category == selectedCategory;
			boolean hovered = row.contains(mouseX, mouseY);
			if (selected) {
				roundedRect(graphics, row.x + 4, row.y, row.w - 8, row.h - 2, 4, CATEGORY_SELECTED, CATEGORY_SELECTED);
			} else if (hovered) {
				roundedRect(graphics, row.x + 4, row.y, row.w - 8, row.h - 2, 4, CATEGORY_HOVER, CATEGORY_HOVER);
			}
			int color = selected ? TEXT_PRIMARY : TEXT_SECONDARY;
			graphics.text(font, category.displayName(), row.x + 12, row.y + (row.h - 8) / 2, color);
		}

		List<Module> modules = ModuleManager.modules(selectedCategory);
		if (openSettingsModule != null && modules.contains(openSettingsModule)) {
			renderSettingsView(graphics, font, mouseX, mouseY, rightX, panelY);
		} else {
			openSettingsModule = null;
			renderModuleList(graphics, font, mouseX, mouseY, modules, panelX, panelY);
		}
	}

	private void renderModuleList(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY, List<Module> modules, int panelX, int panelY) {
		List<Rect> moduleRows = moduleRows(panelX, panelY, modules.size());
		boolean anySettings = false;
		for (int i = 0; i < modules.size(); i++) {
			Module module = modules.get(i);
			Rect row = moduleRows.get(i);
			boolean hovered = row.contains(mouseX, mouseY);
			if (module.isEnabled()) {
				roundedRect(graphics, row.x + 4, row.y, row.w - 8, row.h - 2, 4, MODULE_ENABLED_BG, MODULE_ENABLED_BG);
			} else if (hovered) {
				roundedRect(graphics, row.x + 4, row.y, row.w - 8, row.h - 2, 4, CATEGORY_HOVER, CATEGORY_HOVER);
			}
			int color = module.isEnabled() ? TEXT_PRIMARY : TEXT_SECONDARY;
			graphics.text(font, module.name(), row.x + 12, row.y + (row.h - 8) / 2, color);
			if (module.hasSettings()) {
				graphics.text(font, "⚙", row.x + row.w - 18, row.y + (row.h - 8) / 2, TEXT_MUTED);
				anySettings = true;
			}
		}
		if (anySettings) {
			graphics.text(font, "Right-click for settings", panelX + CATEGORY_WIDTH + 10, panelY + PANEL_HEIGHT - 16, TEXT_MUTED);
		}
	}

	private void renderSettingsView(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY, int x, int y) {
		Rect header = headerRow(x, y);
		if (header.contains(mouseX, mouseY)) {
			roundedRect(graphics, header.x + 4, header.y, header.w - 8, header.h - 2, 4, CATEGORY_HOVER, CATEGORY_HOVER);
		}
		graphics.text(font, "< " + openSettingsModule.name(), header.x + 12, header.y + (header.h - 8) / 2, TEXT_PRIMARY);

		Rect viewport = settingsViewport(x, y);
		List<Row> rows = settingsRows(openSettingsModule, x, y);
		boolean hoverable = viewport.contains(mouseX, mouseY);

		graphics.enableScissor(viewport.x, viewport.y, viewport.x + viewport.w, viewport.y + viewport.h);
		for (Row row : rows) {
			switch (row) {
				case ColorRow colorRow -> renderColorRow(graphics, font, mouseX, mouseY, colorRow, hoverable);
				case NumberRow numberRow -> renderNumberRow(graphics, font, numberRow);
				case ToggleRow toggleRow -> renderToggleRow(graphics, font, mouseX, mouseY, toggleRow, hoverable);
				case ActionRow actionRow -> renderActionRow(graphics, font, mouseX, mouseY, actionRow, hoverable);
			}
		}
		graphics.disableScissor();

		renderScrollbar(graphics, viewport, settingsContentHeight(openSettingsModule));
	}

	private void renderColorRow(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY, ColorRow colorRow, boolean hoverable) {
		Rect bounds = colorRow.bounds;
		// Only the label strip is clickable, not the slider area an expanded row adds below it.
		if (hoverable && new Rect(bounds.x, bounds.y, bounds.w, ROW_HEIGHT).contains(mouseX, mouseY)) {
			roundedRect(graphics, bounds.x + 4, bounds.y, bounds.w - 8, ROW_HEIGHT - 2, 4, CATEGORY_HOVER, CATEGORY_HOVER);
		}
		graphics.text(font, colorRow.setting.name(), bounds.x + 10, bounds.y + (ROW_HEIGHT - 8) / 2, TEXT_SECONDARY);

		int swatchSize = 12;
		int swatchX = bounds.x + bounds.w - swatchSize - 10;
		int swatchY = bounds.y + (ROW_HEIGHT - swatchSize) / 2;
		roundedRect(graphics, swatchX, swatchY, swatchSize, swatchSize, 3, colorRow.setting.opaqueArgb(), colorRow.setting.opaqueArgb());
		outline(graphics, swatchX, swatchY, swatchSize, swatchSize, 3, BORDER);

		if (colorRow.sliders == null) return;
		for (SliderRect slider : colorRow.sliders) {
			int value = colorRow.setting.channel(slider.channel);
			graphics.text(font, channelLabel(slider.channel), slider.rect.x - 12, slider.rect.y + 1, TEXT_MUTED);
			graphics.fill(slider.rect.x, slider.rect.y, slider.rect.x + slider.rect.w, slider.rect.y + slider.rect.h, SLIDER_TRACK);
			int fillWidth = Math.round(slider.rect.w * (value / 255.0f));
			if (fillWidth > 0) {
				graphics.fill(slider.rect.x, slider.rect.y, slider.rect.x + fillWidth, slider.rect.y + slider.rect.h, SLIDER_FILL);
			}
			graphics.text(font, String.valueOf(value), slider.rect.x + slider.rect.w + 6, slider.rect.y + 1, TEXT_MUTED);
		}
	}

	private void renderNumberRow(GuiGraphicsExtractor graphics, Font font, NumberRow numberRow) {
		graphics.text(font, numberRow.setting.name(), numberRow.bounds.x + 10, numberRow.bounds.y, TEXT_SECONDARY);
		Rect slider = numberRow.slider;
		graphics.fill(slider.x, slider.y, slider.x + slider.w, slider.y + slider.h, SLIDER_TRACK);
		int fillWidth = Math.round(slider.w * numberRow.setting.fraction());
		if (fillWidth > 0) {
			graphics.fill(slider.x, slider.y, slider.x + fillWidth, slider.y + slider.h, SLIDER_FILL);
		}
		graphics.text(font, numberRow.setting.display(), slider.x + slider.w + 6, slider.y - 2, TEXT_MUTED);
	}

	private void renderToggleRow(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY, ToggleRow toggleRow, boolean hoverable) {
		Rect bounds = toggleRow.bounds;
		if (hoverable && bounds.contains(mouseX, mouseY)) {
			roundedRect(graphics, bounds.x + 4, bounds.y, bounds.w - 8, bounds.h - 2, 4, CATEGORY_HOVER, CATEGORY_HOVER);
		}
		graphics.text(font, toggleRow.setting.name(), bounds.x + 10, bounds.y + (bounds.h - 8) / 2, TEXT_SECONDARY);

		boolean on = toggleRow.setting.value();
		int pillWidth = 22;
		int pillHeight = 12;
		int pillX = bounds.x + bounds.w - pillWidth - 10;
		int pillY = bounds.y + (bounds.h - pillHeight) / 2;
		int track = on ? MODULE_ENABLED_BG : SLIDER_TRACK;
		roundedRect(graphics, pillX, pillY, pillWidth, pillHeight, 4, track, track);
		int knobX = on ? pillX + pillWidth - 10 : pillX + 2;
		roundedRect(graphics, knobX, pillY + 2, 8, 8, 3, TEXT_PRIMARY, TEXT_PRIMARY);
	}

	private void renderActionRow(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY, ActionRow actionRow, boolean hoverable) {
		Rect bounds = actionRow.bounds;
		boolean hovered = hoverable && bounds.contains(mouseX, mouseY);
		int background = hovered ? BUTTON_HOVER : BUTTON_BG;
		roundedRect(graphics, bounds.x + 8, bounds.y + 3, bounds.w - 16, bounds.h - 8, 4, background, background);
		graphics.centeredText(font, actionRow.action.label(), bounds.x + bounds.w / 2, bounds.y + (bounds.h - 8) / 2, TEXT_PRIMARY);
	}

	private void renderScrollbar(GuiGraphicsExtractor graphics, Rect viewport, int contentHeight) {
		if (contentHeight <= viewport.h) return;
		int trackX = viewport.x + viewport.w - SCROLLBAR_WIDTH - 2;
		int thumbHeight = Math.max(16, viewport.h * viewport.h / contentHeight);
		int travel = viewport.h - thumbHeight;
		int maxScroll = contentHeight - viewport.h;
		int thumbY = viewport.y + (maxScroll == 0 ? 0 : travel * settingsScroll / maxScroll);
		graphics.fill(trackX, thumbY, trackX + SCROLLBAR_WIDTH, thumbY + thumbHeight, SCROLLBAR);
	}

	private Rect headerRow(int x, int y) {
		return new Rect(x, y + PADDING, MODULE_WIDTH, ROW_HEIGHT);
	}

	/** The clipped, scrollable area below the back header that the setting rows live in. */
	private Rect settingsViewport(int x, int panelY) {
		Rect header = headerRow(x, panelY);
		int top = header.y + header.h;
		return new Rect(x, top, MODULE_WIDTH, panelY + PANEL_HEIGHT - PADDING - top);
	}

	/** Setting rows with the current scroll already applied, so callers can hit-test them directly. */
	private List<Row> settingsRows(Module module, int x, int panelY) {
		Rect viewport = settingsViewport(x, panelY);
		int maxScroll = Math.max(0, settingsContentHeight(module) - viewport.h);
		settingsScroll = Math.max(0, Math.min(maxScroll, settingsScroll));
		return layoutRows(module, x, viewport.y - settingsScroll);
	}

	private int settingsContentHeight(Module module) {
		int height = 0;
		for (ColorSetting setting : module.colorSettings()) {
			height += colorRowHeight(setting);
		}
		height += module.numberSettings().size() * ROW_HEIGHT;
		height += module.booleanSettings().size() * ROW_HEIGHT;
		height += module.actions().size() * ROW_HEIGHT;
		return height;
	}

	private int colorRowHeight(ColorSetting setting) {
		return ROW_HEIGHT + (setting == expandedColorSetting ? 4 * CHANNEL_ROW_HEIGHT + PADDING : 0);
	}

	/** @param startY where the first row begins; already offset by the scroll position */
	private List<Row> layoutRows(Module module, int x, int startY) {
		List<Row> rows = new ArrayList<>();
		int cursorY = startY;

		for (ColorSetting setting : module.colorSettings()) {
			int rowHeight = colorRowHeight(setting);
			List<SliderRect> sliders = null;
			if (setting == expandedColorSetting) {
				sliders = new ArrayList<>();
				ColorSetting.Channel[] channels = {ColorSetting.Channel.RED, ColorSetting.Channel.GREEN, ColorSetting.Channel.BLUE, ColorSetting.Channel.ALPHA};
				int sliderY = cursorY + ROW_HEIGHT + 2;
				int sliderX = x + 22;
				int sliderWidth = MODULE_WIDTH - 22 - 34;
				for (ColorSetting.Channel channel : channels) {
					sliders.add(new SliderRect(channel, new Rect(sliderX, sliderY, sliderWidth, 6)));
					sliderY += CHANNEL_ROW_HEIGHT;
				}
			}
			rows.add(new ColorRow(setting, new Rect(x, cursorY, MODULE_WIDTH, rowHeight), sliders));
			cursorY += rowHeight;
		}

		for (NumberSetting setting : module.numberSettings()) {
			Rect slider = new Rect(x + 10, cursorY + 15, MODULE_WIDTH - 20 - 34, 6);
			rows.add(new NumberRow(setting, new Rect(x, cursorY, MODULE_WIDTH, ROW_HEIGHT), slider));
			cursorY += ROW_HEIGHT;
		}

		for (BooleanSetting setting : module.booleanSettings()) {
			rows.add(new ToggleRow(setting, new Rect(x, cursorY, MODULE_WIDTH, ROW_HEIGHT)));
			cursorY += ROW_HEIGHT;
		}

		for (ModuleAction action : module.actions()) {
			rows.add(new ActionRow(action, new Rect(x, cursorY, MODULE_WIDTH, ROW_HEIGHT)));
			cursorY += ROW_HEIGHT;
		}

		return rows;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int mouseX = (int) event.x();
		int mouseY = (int) event.y();
		int panelX = panelX();
		int panelY = panelY();
		int rightX = panelX + CATEGORY_WIDTH;
		boolean left = event.button() == 0;
		boolean right = event.button() == 1;

		List<Rect> categoryRows = categoryRows(panelX, panelY);
		Category[] categories = Category.values();
		for (int i = 0; i < categories.length; i++) {
			if (left && categoryRows.get(i).contains(mouseX, mouseY)) {
				selectedCategory = categories[i];
				closeSettings();
				return true;
			}
		}

		List<Module> modules = ModuleManager.modules(selectedCategory);

		if (openSettingsModule != null && modules.contains(openSettingsModule)) {
			return settingsClicked(openSettingsModule, mouseX, mouseY, rightX, panelY, left);
		}

		List<Rect> moduleRows = moduleRows(panelX, panelY, modules.size());
		for (int i = 0; i < modules.size(); i++) {
			Rect row = moduleRows.get(i);
			if (!row.contains(mouseX, mouseY)) continue;
			Module module = modules.get(i);
			if (left) {
				module.toggle();
				ModConfig.save();
			} else if (right && module.hasSettings()) {
				openSettingsModule = module;
				expandedColorSetting = null;
				settingsScroll = 0;
			}
			return true;
		}

		return super.mouseClicked(event, doubleClick);
	}

	private boolean settingsClicked(Module module, int mouseX, int mouseY, int x, int panelY, boolean left) {
		Rect header = headerRow(x, panelY);
		if (left && header.contains(mouseX, mouseY)) {
			closeSettings();
			return true;
		}

		Rect viewport = settingsViewport(x, panelY);
		if (!left || !viewport.contains(mouseX, mouseY)) {
			// Swallow anything else inside the panel so clicks don't fall through to the world.
			return true;
		}

		for (Row row : settingsRows(module, x, panelY)) {
			switch (row) {
				case ColorRow colorRow -> {
					if (colorRow.sliders != null) {
						for (SliderRect slider : colorRow.sliders) {
							if (slider.rect.contains(mouseX, mouseY)) {
								draggingChannel = slider.channel;
								applySlider(colorRow.setting, slider, mouseX);
								return true;
							}
						}
					}
					if (new Rect(colorRow.bounds.x, colorRow.bounds.y, colorRow.bounds.w, ROW_HEIGHT).contains(mouseX, mouseY)) {
						expandedColorSetting = expandedColorSetting == colorRow.setting ? null : colorRow.setting;
						return true;
					}
				}
				case NumberRow numberRow -> {
					if (numberRow.slider.contains(mouseX, mouseY)) {
						draggingNumberSetting = numberRow.setting;
						applyNumberSlider(numberRow.setting, numberRow.slider, mouseX);
						return true;
					}
				}
				case ToggleRow toggleRow -> {
					if (toggleRow.bounds.contains(mouseX, mouseY)) {
						toggleRow.setting.toggle();
						ModConfig.save();
						return true;
					}
				}
				case ActionRow actionRow -> {
					if (actionRow.bounds.contains(mouseX, mouseY)) {
						actionRow.action.onClick().run();
						return true;
					}
				}
			}
		}

		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (openSettingsModule == null) {
			return super.mouseDragged(event, dragX, dragY);
		}
		int rightX = panelX() + CATEGORY_WIDTH;
		int mouseX = (int) event.x();

		if (draggingChannel != null || draggingNumberSetting != null) {
			for (Row row : settingsRows(openSettingsModule, rightX, panelY())) {
				if (draggingChannel != null && row instanceof ColorRow colorRow && colorRow.setting == expandedColorSetting && colorRow.sliders != null) {
					for (SliderRect slider : colorRow.sliders) {
						if (slider.channel == draggingChannel) {
							applySlider(colorRow.setting, slider, mouseX);
							return true;
						}
					}
				}
				if (row instanceof NumberRow numberRow && numberRow.setting == draggingNumberSetting) {
					applyNumberSlider(numberRow.setting, numberRow.slider, mouseX);
					return true;
				}
			}
		}

		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (draggingChannel != null || draggingNumberSetting != null) {
			draggingChannel = null;
			draggingNumberSetting = null;
			ModConfig.save();
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (openSettingsModule != null) {
			Rect viewport = settingsViewport(panelX() + CATEGORY_WIDTH, panelY());
			if (viewport.contains(mouseX, mouseY)) {
				int maxScroll = Math.max(0, settingsContentHeight(openSettingsModule) - viewport.h);
				settingsScroll = Math.max(0, Math.min(maxScroll, settingsScroll - (int) Math.round(scrollY * CHANNEL_ROW_HEIGHT)));
				return true;
			}
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	private void closeSettings() {
		openSettingsModule = null;
		expandedColorSetting = null;
		settingsScroll = 0;
	}

	private void applySlider(ColorSetting setting, SliderRect slider, int mouseX) {
		float fraction = (mouseX - slider.rect.x) / (float) slider.rect.w;
		int value = Math.round(fraction * 255);
		setting.setChannel(slider.channel, value);
	}

	private void applyNumberSlider(NumberSetting setting, Rect slider, int mouseX) {
		float fraction = (mouseX - slider.x) / (float) slider.w;
		setting.setFraction(fraction);
	}

	private int panelX() {
		return (this.width - CATEGORY_WIDTH - MODULE_WIDTH) / 2;
	}

	private int panelY() {
		return (this.height - PANEL_HEIGHT) / 2;
	}

	private List<Rect> categoryRows(int panelX, int panelY) {
		List<Rect> rows = new ArrayList<>();
		Category[] categories = Category.values();
		for (int i = 0; i < categories.length; i++) {
			rows.add(new Rect(panelX, panelY + PADDING + i * ROW_HEIGHT, CATEGORY_WIDTH, ROW_HEIGHT));
		}
		return rows;
	}

	private List<Rect> moduleRows(int panelX, int panelY, int count) {
		List<Rect> rows = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			rows.add(new Rect(panelX + CATEGORY_WIDTH, panelY + PADDING + i * ROW_HEIGHT, MODULE_WIDTH, ROW_HEIGHT));
		}
		return rows;
	}

	private static String channelLabel(ColorSetting.Channel channel) {
		return switch (channel) {
			case RED -> "R";
			case GREEN -> "G";
			case BLUE -> "B";
			case ALPHA -> "A";
		};
	}

	private record Rect(int x, int y, int w, int h) {
		boolean contains(double px, double py) {
			return px >= x && px < x + w && py >= y && py < y + h;
		}
	}

	private record SliderRect(ColorSetting.Channel channel, Rect rect) {
	}

	/** One laid-out line in the settings panel. */
	private sealed interface Row permits ColorRow, NumberRow, ToggleRow, ActionRow {
	}

	private record ColorRow(ColorSetting setting, Rect bounds, List<SliderRect> sliders) implements Row {
	}

	private record NumberRow(NumberSetting setting, Rect bounds, Rect slider) implements Row {
	}

	private record ToggleRow(BooleanSetting setting, Rect bounds) implements Row {
	}

	private record ActionRow(ModuleAction action, Rect bounds) implements Row {
	}
}
