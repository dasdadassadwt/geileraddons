package geiler.addons.client.gui;

import geiler.addons.client.config.ClickGuiState;
import geiler.addons.client.config.ModConfig;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.ModuleAction;
import geiler.addons.client.module.ModuleManager;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.update.UpdateChecker;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

import static geiler.addons.client.gui.GuiTheme.*;

public class ClickGuiScreen extends Screen {
	/** Share of the screen width the panel aims for, before the height cap below applies. */
	private static final float WIDTH_FRACTION = 0.62f;
	/** The panel never grows past this share of the screen height. */
	private static final float MAX_HEIGHT_FRACTION = 0.75f;
	private static final int MIN_PANEL_WIDTH = 240;
	/** Left panel's share of the total width; the module panel takes the rest. */
	private static final int CATEGORY_WIDTH_DIVISOR = 3;

	private static final int ROW_HEIGHT = 24;
	private static final int GROUP_HEADER_HEIGHT = 20;
	private static final int PADDING = 8;
	private static final int CHANNEL_ROW_HEIGHT = 14;
	private static final int SCROLLBAR_WIDTH = 3;
	/** Room reserved on a setting row's right for the swatch, switch or value read-out. */
	private static final int VALUE_GUTTER = 36;

	private static final int LINE_HEIGHT = 9;
	private static final int TEXT_HEIGHT = 8;

	private static final int CARD_COLUMNS = 2;
	private static final int CARD_GAP = 6;
	private static final int CARD_INSET = 8;
	/** Descriptions longer than this are clipped rather than pushing every card taller. */
	private static final int CARD_MAX_DESC_LINES = 3;
	private static final int SWITCH_WIDTH = 20;
	private static final int SWITCH_HEIGHT = 11;

	private Category selectedCategory;
	private Module openSettingsModule;
	private ColorSetting expandedColorSetting;
	private ColorSetting.Channel draggingChannel;
	private NumberSetting draggingNumberSetting;
	private int settingsScroll;
	private int gridScroll;

	public ClickGuiScreen() {
		super(Component.literal("GeilerAddons"));
		selectedCategory = ClickGuiState.category();
		openSettingsModule = ClickGuiState.openModule();
		expandedColorSetting = ClickGuiState.expandedColor();
		settingsScroll = ClickGuiState.settingsScroll();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void removed() {
		// Written once on the way out rather than on every scroll notch, which would mean a
		// config write per mouse-wheel click.
		persistView();
		ModConfig.save();
		super.removed();
	}

	private void persistView() {
		ClickGuiState.setCategory(selectedCategory);
		ClickGuiState.setOpenModule(openSettingsModule);
		ClickGuiState.setExpandedColor(expandedColorSetting);
		ClickGuiState.setSettingsScroll(settingsScroll);
	}

	// ---- layout -------------------------------------------------------------------------

	/** Widest 2:1 box that fits inside both the width and height budgets. */
	private int panelWidth() {
		int byWidth = Math.round(this.width * WIDTH_FRACTION);
		int byHeight = Math.round(this.height * MAX_HEIGHT_FRACTION) * 2;
		int width = Math.max(MIN_PANEL_WIDTH, Math.min(byWidth, byHeight));
		// Even, so panelHeight() is exactly half and the 2:1 ratio holds after integer division.
		return Math.min(width, this.width) & ~1;
	}

	private int panelHeight() {
		return panelWidth() / 2;
	}

	private int categoryWidth() {
		return panelWidth() / CATEGORY_WIDTH_DIVISOR;
	}

	private int moduleWidth() {
		return panelWidth() - categoryWidth();
	}

	private int panelX() {
		return (this.width - panelWidth()) / 2;
	}

	private int panelY() {
		return (this.height - panelHeight()) / 2;
	}

	// ---- rendering ----------------------------------------------------------------------

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int panelX = panelX();
		int panelY = panelY();
		int categoryWidth = categoryWidth();
		int moduleWidth = moduleWidth();
		int panelHeight = panelHeight();
		int rightX = panelX + categoryWidth;
		Font font = this.font;

		roundedRectBordered(graphics, panelX, panelY, categoryWidth, panelHeight, RADIUS, PANEL_TOP, PANEL_BOTTOM, BORDER);
		roundedRectBordered(graphics, rightX, panelY, moduleWidth, panelHeight, RADIUS, MODULE_PANEL_TOP, MODULE_PANEL_BOTTOM, BORDER);

		renderCategories(graphics, font, mouseX, mouseY, panelX, panelY);

		List<Module> modules = ModuleManager.modules(selectedCategory);
		if (openSettingsModule != null && modules.contains(openSettingsModule)) {
			renderSettingsView(graphics, font, mouseX, mouseY, rightX, panelY);
		} else {
			openSettingsModule = null;
			renderModuleGrid(graphics, font, mouseX, mouseY, modules, rightX, panelY);
		}
	}

	private void renderCategories(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY, int panelX, int panelY) {
		List<Rect> categoryRows = categoryRows(panelX, panelY);
		Category[] categories = Category.values();
		for (int i = 0; i < categories.length; i++) {
			Rect row = categoryRows.get(i);
			Category category = categories[i];
			boolean selected = category == selectedCategory;
			boolean hovered = row.contains(mouseX, mouseY);
			if (selected) {
				roundedRect(graphics, row.x + 5, row.y, row.w - 10, row.h - 2, RADIUS_SMALL, CATEGORY_SELECTED);
			} else if (hovered) {
				roundedRect(graphics, row.x + 5, row.y, row.w - 10, row.h - 2, RADIUS_SMALL, CATEGORY_HOVER);
			}
			int color = selected ? TEXT_PRIMARY : TEXT_SECONDARY;
			graphics.text(font, category.displayName(), row.x + 14, row.y + (row.h - TEXT_HEIGHT) / 2, color);
		}

		// The update notice sits under the categories. Clipped to the panel rather than left to
		// spill across the module list, since this column is narrow on a small screen.
		String notice = UpdateChecker.bannerText();
		if (notice != null) {
			int available = categoryWidth() - 28;
			String text = font.width(notice) <= available ? notice : font.plainSubstrByWidth(notice, available);
			graphics.text(font, text, panelX + 14, panelY + panelHeight() - PADDING - TEXT_HEIGHT, TEXT_WARN);
		}
	}

	// ---- module grid --------------------------------------------------------------------

	private void renderModuleGrid(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY, List<Module> modules, int x, int panelY) {
		Rect viewport = gridViewport(x, panelY);
		List<CardRect> cards = cardRects(modules, x, panelY);
		boolean hoverable = viewport.contains(mouseX, mouseY);

		graphics.enableScissor(viewport.x, viewport.y, viewport.x + viewport.w, viewport.y + viewport.h);
		for (CardRect card : cards) {
			renderCard(graphics, font, mouseX, mouseY, card, hoverable);
		}
		graphics.disableScissor();

		renderScrollbar(graphics, viewport, gridContentHeight(modules), gridScroll);
	}

	private void renderCard(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY, CardRect card, boolean hoverable) {
		Module module = card.module;
		Rect bounds = card.bounds;
		boolean enabled = module.isEnabled();
		boolean hovered = hoverable && bounds.contains(mouseX, mouseY);

		int background = enabled ? CARD_BG_ENABLED : (hovered ? CARD_BG_HOVER : CARD_BG);
		int border = enabled ? CARD_BORDER_ENABLED : CARD_BORDER;
		roundedRectBordered(graphics, bounds.x, bounds.y, bounds.w, bounds.h, RADIUS_SMALL, background, background, border);

		// An enabled module that its own context has switched off is drawn muted, so "on but doing
		// nothing" never looks the same as "on and working".
		boolean dimmed = enabled && !module.isActive();
		int nameColor = dimmed ? TEXT_MUTED : TEXT_PRIMARY;

		int textX = bounds.x + CARD_INSET;
		int textWidth = bounds.w - CARD_INSET * 2;
		graphics.text(font, module.name(), textX, bounds.y + CARD_INSET, nameColor);

		int descY = bounds.y + CARD_INSET + LINE_HEIGHT + 3;
		for (FormattedCharSequence line : descriptionLines(font, module, textWidth)) {
			graphics.text(font, line, textX, descY, TEXT_MUTED);
			descY += LINE_HEIGHT;
		}

		String status = module.inactiveReason();
		if (status != null) {
			graphics.text(font, status, textX, bounds.y + bounds.h - CARD_INSET - TEXT_HEIGHT, TEXT_WARN);
		}

		Rect toggle = switchRect(bounds);
		toggleSwitch(graphics, toggle.x, toggle.y, toggle.w, toggle.h, enabled);

		if (module.hasSettings()) {
			graphics.text(font, "⚙", bounds.x + bounds.w - CARD_INSET - 6,
				bounds.y + bounds.h - CARD_INSET - TEXT_HEIGHT, TEXT_MUTED);
		}
	}

	private Rect switchRect(Rect card) {
		return new Rect(card.x + card.w - SWITCH_WIDTH - CARD_INSET, card.y + CARD_INSET - 1, SWITCH_WIDTH, SWITCH_HEIGHT);
	}

	private List<FormattedCharSequence> descriptionLines(Font font, Module module, int width) {
		List<FormattedCharSequence> lines = font.split(Component.literal(module.description()), width);
		return lines.size() > CARD_MAX_DESC_LINES ? lines.subList(0, CARD_MAX_DESC_LINES) : lines;
	}

	/** Uniform across the category so the grid stays on a shared baseline. */
	private int cardHeight(List<Module> modules) {
		int width = cardWidth() - CARD_INSET * 2;
		int descLines = 1;
		for (Module module : modules) {
			descLines = Math.max(descLines, descriptionLines(this.font, module, width).size());
		}
		// Name, description, then a reserved status line: reserved rather than conditional so a
		// module going inactive does not resize the whole grid under the cursor.
		return CARD_INSET + LINE_HEIGHT + 3 + descLines * LINE_HEIGHT + 3 + TEXT_HEIGHT + CARD_INSET;
	}

	private int cardWidth() {
		int available = moduleWidth() - PADDING * 2 - CARD_GAP * (CARD_COLUMNS - 1);
		return available / CARD_COLUMNS;
	}

	private Rect gridViewport(int x, int panelY) {
		return new Rect(x, panelY + PADDING, moduleWidth(), panelHeight() - PADDING * 2);
	}

	private int gridContentHeight(List<Module> modules) {
		if (modules.isEmpty()) return 0;
		int rows = (modules.size() + CARD_COLUMNS - 1) / CARD_COLUMNS;
		return rows * cardHeight(modules) + (rows - 1) * CARD_GAP;
	}

	private List<CardRect> cardRects(List<Module> modules, int x, int panelY) {
		Rect viewport = gridViewport(x, panelY);
		int maxScroll = Math.max(0, gridContentHeight(modules) - viewport.h);
		gridScroll = Math.max(0, Math.min(maxScroll, gridScroll));

		int cardWidth = cardWidth();
		int cardHeight = cardHeight(modules);
		List<CardRect> cards = new ArrayList<>();
		for (int i = 0; i < modules.size(); i++) {
			int column = i % CARD_COLUMNS;
			int row = i / CARD_COLUMNS;
			int cardX = x + PADDING + column * (cardWidth + CARD_GAP);
			int cardY = viewport.y + row * (cardHeight + CARD_GAP) - gridScroll;
			cards.add(new CardRect(modules.get(i), new Rect(cardX, cardY, cardWidth, cardHeight)));
		}
		return cards;
	}

	// ---- settings view ------------------------------------------------------------------

	private void renderSettingsView(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY, int x, int y) {
		Rect header = headerRow(x, y);
		if (header.contains(mouseX, mouseY)) {
			roundedRect(graphics, header.x + 5, header.y, header.w - 10, header.h - 2, RADIUS_SMALL, CATEGORY_HOVER);
		}
		graphics.text(font, "< " + openSettingsModule.name(), header.x + 14, header.y + (header.h - TEXT_HEIGHT) / 2, TEXT_PRIMARY);

		Rect viewport = settingsViewport(x, y);
		List<Row> rows = settingsRows(openSettingsModule, x, y);
		boolean hoverable = viewport.contains(mouseX, mouseY);

		graphics.enableScissor(viewport.x, viewport.y, viewport.x + viewport.w, viewport.y + viewport.h);
		for (Row row : rows) {
			switch (row) {
				case GroupRow groupRow -> renderGroupRow(graphics, font, mouseX, mouseY, groupRow, hoverable);
				case ColorRow colorRow -> renderColorRow(graphics, font, mouseX, mouseY, colorRow, hoverable);
				case NumberRow numberRow -> renderNumberRow(graphics, font, numberRow);
				case ToggleRow toggleRow -> renderToggleRow(graphics, font, mouseX, mouseY, toggleRow, hoverable);
				case ActionRow actionRow -> renderActionRow(graphics, font, mouseX, mouseY, actionRow, hoverable);
			}
		}
		graphics.disableScissor();

		renderScrollbar(graphics, viewport, settingsContentHeight(openSettingsModule, x), settingsScroll);
	}

	private void renderGroupRow(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY, GroupRow groupRow, boolean hoverable) {
		Rect bounds = groupRow.bounds;
		boolean hovered = hoverable && bounds.contains(mouseX, mouseY);
		roundedRect(graphics, bounds.x + 5, bounds.y + 1, bounds.w - 10, bounds.h - 3, RADIUS_SMALL,
			hovered ? CARD_BG_HOVER : GROUP_HEADER);
		boolean collapsed = ClickGuiState.isCollapsed(openSettingsModule, groupRow.group);
		graphics.text(font, collapsed ? "▸" : "▾", bounds.x + 12, bounds.y + (bounds.h - TEXT_HEIGHT) / 2, TEXT_SECONDARY);
		graphics.text(font, groupRow.group.name(), bounds.x + 24, bounds.y + (bounds.h - TEXT_HEIGHT) / 2, TEXT_PRIMARY);
	}

	private void renderColorRow(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY, ColorRow colorRow, boolean hoverable) {
		Rect bounds = colorRow.bounds;
		// Only the label strip is clickable, not the slider area an expanded row adds below it.
		if (hoverable && new Rect(bounds.x, bounds.y, bounds.w, ROW_HEIGHT).contains(mouseX, mouseY)) {
			roundedRect(graphics, bounds.x + 9, bounds.y, bounds.w - 18, ROW_HEIGHT - 2, RADIUS_SMALL, CATEGORY_HOVER);
		}
		graphics.text(font, colorRow.setting.name(), bounds.x + 16, bounds.y + (ROW_HEIGHT - TEXT_HEIGHT) / 2, TEXT_SECONDARY);

		int swatchSize = 12;
		int swatchX = bounds.x + bounds.w - swatchSize - 14;
		int swatchY = bounds.y + (ROW_HEIGHT - swatchSize) / 2;
		roundedRectBordered(graphics, swatchX, swatchY, swatchSize, swatchSize, 3,
			colorRow.setting.opaqueArgb(), colorRow.setting.opaqueArgb(), BORDER);

		if (colorRow.sliders == null) return;
		for (SliderRect slider : colorRow.sliders) {
			int value = colorRow.setting.channel(slider.channel);
			graphics.text(font, channelLabel(slider.channel), slider.rect.x - 12, slider.rect.y + 1, TEXT_MUTED);
			roundedRect(graphics, slider.rect.x, slider.rect.y, slider.rect.w, slider.rect.h, slider.rect.h / 2, SLIDER_TRACK);
			int fillWidth = Math.round(slider.rect.w * (value / 255.0f));
			if (fillWidth > 0) {
				roundedRect(graphics, slider.rect.x, slider.rect.y, fillWidth, slider.rect.h, slider.rect.h / 2, SLIDER_FILL);
			}
			graphics.text(font, String.valueOf(value), slider.rect.x + slider.rect.w + 6, slider.rect.y + 1, TEXT_MUTED);
		}
	}

	private void renderNumberRow(GuiGraphicsExtractor graphics, Font font, NumberRow numberRow) {
		graphics.text(font, numberRow.setting.name(), numberRow.bounds.x + 16, numberRow.bounds.y + 1, TEXT_SECONDARY);
		Rect slider = numberRow.slider;
		roundedRect(graphics, slider.x, slider.y, slider.w, slider.h, slider.h / 2, SLIDER_TRACK);
		int fillWidth = Math.round(slider.w * numberRow.setting.fraction());
		if (fillWidth > 0) {
			roundedRect(graphics, slider.x, slider.y, fillWidth, slider.h, slider.h / 2, SLIDER_FILL);
		}
		graphics.text(font, numberRow.setting.display(), slider.x + slider.w + 8, slider.y - 2, TEXT_MUTED);
	}

	private void renderToggleRow(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY, ToggleRow toggleRow, boolean hoverable) {
		Rect bounds = toggleRow.bounds;
		if (hoverable && bounds.contains(mouseX, mouseY)) {
			roundedRect(graphics, bounds.x + 9, bounds.y, bounds.w - 18, bounds.h - 2, RADIUS_SMALL, CATEGORY_HOVER);
		}
		graphics.text(font, toggleRow.setting.name(), bounds.x + 16, bounds.y + (bounds.h - TEXT_HEIGHT) / 2, TEXT_SECONDARY);

		int switchX = bounds.x + bounds.w - SWITCH_WIDTH - 14;
		int switchY = bounds.y + (bounds.h - SWITCH_HEIGHT) / 2;
		toggleSwitch(graphics, switchX, switchY, SWITCH_WIDTH, SWITCH_HEIGHT, toggleRow.setting.value());
	}

	private void renderActionRow(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY, ActionRow actionRow, boolean hoverable) {
		Rect bounds = actionRow.bounds;
		boolean hovered = hoverable && bounds.contains(mouseX, mouseY);
		int background = hovered ? BUTTON_HOVER : BUTTON_BG;
		roundedRect(graphics, bounds.x + 14, bounds.y + 2, bounds.w - 28, bounds.h - 6, RADIUS_SMALL, background);
		graphics.centeredText(font, actionRow.action.label(), bounds.x + bounds.w / 2, bounds.y + (bounds.h - TEXT_HEIGHT) / 2, TEXT_PRIMARY);
	}

	private void renderScrollbar(GuiGraphicsExtractor graphics, Rect viewport, int contentHeight, int scroll) {
		if (contentHeight <= viewport.h) return;
		int trackX = viewport.x + viewport.w - SCROLLBAR_WIDTH - 3;
		int thumbHeight = Math.max(16, viewport.h * viewport.h / contentHeight);
		int travel = viewport.h - thumbHeight;
		int maxScroll = contentHeight - viewport.h;
		int thumbY = viewport.y + travel * scroll / maxScroll;
		roundedRect(graphics, trackX, thumbY, SCROLLBAR_WIDTH, thumbHeight, SCROLLBAR_WIDTH / 2, SCROLLBAR);
	}

	private Rect headerRow(int x, int y) {
		return new Rect(x, y + PADDING, moduleWidth(), ROW_HEIGHT);
	}

	/** The clipped, scrollable area below the back header that the setting rows live in. */
	private Rect settingsViewport(int x, int panelY) {
		Rect header = headerRow(x, panelY);
		int top = header.y + header.h;
		return new Rect(x, top, moduleWidth(), panelY + panelHeight() - PADDING - top);
	}

	/** Setting rows with the current scroll already applied, so callers can hit-test them directly. */
	private List<Row> settingsRows(Module module, int x, int panelY) {
		Rect viewport = settingsViewport(x, panelY);
		int maxScroll = Math.max(0, settingsContentHeight(module, x) - viewport.h);
		settingsScroll = Math.max(0, Math.min(maxScroll, settingsScroll));
		return layoutRows(module, x, viewport.y - settingsScroll);
	}

	/** Measured by running the same layout, so it can never drift from what is drawn. */
	private int settingsContentHeight(Module module, int x) {
		List<Row> rows = layoutRows(module, x, 0);
		int bottom = 0;
		for (Row row : rows) {
			Rect bounds = row.bounds();
			bottom = Math.max(bottom, bounds.y + bounds.h);
		}
		return bottom;
	}

	private int colorRowHeight(ColorSetting setting) {
		return ROW_HEIGHT + (setting == expandedColorSetting ? 4 * CHANNEL_ROW_HEIGHT + PADDING : 0);
	}

	/** @param startY where the first row begins; already offset by the scroll position */
	private List<Row> layoutRows(Module module, int x, int startY) {
		List<Row> rows = new ArrayList<>();
		int moduleWidth = moduleWidth();
		int cursorY = startY;

		for (SettingGroup group : module.groups()) {
			if (group.name() != null) {
				rows.add(new GroupRow(group, new Rect(x, cursorY, moduleWidth, GROUP_HEADER_HEIGHT)));
				cursorY += GROUP_HEADER_HEIGHT;
				if (ClickGuiState.isCollapsed(module, group)) continue;
			}
			for (Setting setting : group.settings()) {
				switch (setting) {
					case ColorSetting colorSetting -> {
						int rowHeight = colorRowHeight(colorSetting);
						rows.add(new ColorRow(colorSetting, new Rect(x, cursorY, moduleWidth, rowHeight),
							colorSliders(colorSetting, x, cursorY, moduleWidth)));
						cursorY += rowHeight;
					}
					case NumberSetting numberSetting -> {
						Rect slider = new Rect(x + 16, cursorY + 13, moduleWidth - 32 - VALUE_GUTTER, 6);
						rows.add(new NumberRow(numberSetting, new Rect(x, cursorY, moduleWidth, ROW_HEIGHT), slider));
						cursorY += ROW_HEIGHT;
					}
					case BooleanSetting booleanSetting -> {
						rows.add(new ToggleRow(booleanSetting, new Rect(x, cursorY, moduleWidth, ROW_HEIGHT)));
						cursorY += ROW_HEIGHT;
					}
					case ModuleAction action -> {
						rows.add(new ActionRow(action, new Rect(x, cursorY, moduleWidth, ROW_HEIGHT)));
						cursorY += ROW_HEIGHT;
					}
				}
			}
		}

		return rows;
	}

	private List<SliderRect> colorSliders(ColorSetting setting, int x, int cursorY, int moduleWidth) {
		if (setting != expandedColorSetting) return null;
		List<SliderRect> sliders = new ArrayList<>();
		ColorSetting.Channel[] channels = {
			ColorSetting.Channel.RED, ColorSetting.Channel.GREEN, ColorSetting.Channel.BLUE, ColorSetting.Channel.ALPHA
		};
		int sliderY = cursorY + ROW_HEIGHT + 2;
		int sliderX = x + 28;
		int sliderWidth = moduleWidth - 28 - VALUE_GUTTER;
		for (ColorSetting.Channel channel : channels) {
			sliders.add(new SliderRect(channel, new Rect(sliderX, sliderY, sliderWidth, 6)));
			sliderY += CHANNEL_ROW_HEIGHT;
		}
		return sliders;
	}

	// ---- input --------------------------------------------------------------------------

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int mouseX = (int) event.x();
		int mouseY = (int) event.y();
		int panelX = panelX();
		int panelY = panelY();
		int rightX = panelX + categoryWidth();
		boolean left = event.button() == 0;
		boolean right = event.button() == 1;
		if (!left && !right) return super.mouseClicked(event, doubleClick);

		List<Rect> categoryRows = categoryRows(panelX, panelY);
		Category[] categories = Category.values();
		for (int i = 0; i < categories.length; i++) {
			if (left && categoryRows.get(i).contains(mouseX, mouseY)) {
				selectedCategory = categories[i];
				closeSettings();
				gridScroll = 0;
				return true;
			}
		}

		List<Module> modules = ModuleManager.modules(selectedCategory);

		if (openSettingsModule != null && modules.contains(openSettingsModule)) {
			return settingsClicked(openSettingsModule, mouseX, mouseY, rightX, panelY, left);
		}

		for (CardRect card : cardRects(modules, rightX, panelY)) {
			if (!card.bounds.contains(mouseX, mouseY)) continue;
			// The switch is the only thing that toggles the module; anywhere else on the card
			// opens its settings, on either button, so there is no hidden right-click gesture.
			if (left && switchRect(card.bounds).contains(mouseX, mouseY)) {
				card.module.toggle();
				persistView();
				ModConfig.save();
			} else if (card.module.hasSettings()) {
				openSettingsModule = card.module;
				expandedColorSetting = null;
				settingsScroll = 0;
				persistView();
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
				case GroupRow groupRow -> {
					if (groupRow.bounds.contains(mouseX, mouseY)) {
						ClickGuiState.toggleCollapsed(module, groupRow.group);
						ModConfig.save();
						return true;
					}
				}
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
						persistView();
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
						persistView();
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
		int rightX = panelX() + categoryWidth();
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
			persistView();
			ModConfig.save();
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		int rightX = panelX() + categoryWidth();
		int notch = (int) Math.round(scrollY * CHANNEL_ROW_HEIGHT);

		if (openSettingsModule != null) {
			Rect viewport = settingsViewport(rightX, panelY());
			if (viewport.contains(mouseX, mouseY)) {
				int maxScroll = Math.max(0, settingsContentHeight(openSettingsModule, rightX) - viewport.h);
				settingsScroll = Math.max(0, Math.min(maxScroll, settingsScroll - notch));
				persistView();
				return true;
			}
		} else {
			List<Module> modules = ModuleManager.modules(selectedCategory);
			Rect viewport = gridViewport(rightX, panelY());
			if (viewport.contains(mouseX, mouseY)) {
				int maxScroll = Math.max(0, gridContentHeight(modules) - viewport.h);
				gridScroll = Math.max(0, Math.min(maxScroll, gridScroll - notch));
				return true;
			}
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	private void closeSettings() {
		openSettingsModule = null;
		expandedColorSetting = null;
		settingsScroll = 0;
		persistView();
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

	private List<Rect> categoryRows(int panelX, int panelY) {
		List<Rect> rows = new ArrayList<>();
		Category[] categories = Category.values();
		int categoryWidth = categoryWidth();
		for (int i = 0; i < categories.length; i++) {
			rows.add(new Rect(panelX, panelY + PADDING + i * ROW_HEIGHT, categoryWidth, ROW_HEIGHT));
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

	private record CardRect(Module module, Rect bounds) {
	}

	/** One laid-out line in the settings panel. */
	private sealed interface Row permits GroupRow, ColorRow, NumberRow, ToggleRow, ActionRow {
		Rect bounds();
	}

	private record GroupRow(SettingGroup group, Rect bounds) implements Row {
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
