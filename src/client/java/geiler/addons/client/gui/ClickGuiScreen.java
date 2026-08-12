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
import geiler.addons.client.module.TextSetting;
import geiler.addons.client.module.impl.VisualModule;
import geiler.addons.client.update.UpdateChecker;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

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

	/** Colour picker geometry, all measured from the top of the expanded area. */
	private static final int PICKER_INSET = 16;
	private static final int PICKER_SQUARE_HEIGHT = 64;
	private static final int PICKER_BAR_HEIGHT = 8;
	private static final int PICKER_GAP = 5;
	private static final int PICKER_HEX_HEIGHT = 13;
	private static final int PICKER_HEX_WIDTH = 74;
	private static final int PICKER_HEIGHT = PICKER_SQUARE_HEIGHT + PICKER_GAP + PICKER_BAR_HEIGHT
		+ PICKER_GAP + PICKER_BAR_HEIGHT + PICKER_GAP + PICKER_HEX_HEIGHT + PADDING;
	/** Side of the light/dark squares behind a partly transparent colour. */
	private static final int CHECKER_SIZE = 4;
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

	private static final int TEXT_FIELD_WIDTH = 64;
	private static final int TEXT_FIELD_HEIGHT = 13;
	/** Caret on for this long, then off for as long again. */
	private static final long CARET_BLINK_MILLIS = 500;

	/** Which part of the colour picker the mouse is currently dragging. */
	private enum PickerPart { SQUARE, HUE, ALPHA }

	private Category selectedCategory;
	private Module openSettingsModule;
	private ColorSetting expandedColorSetting;
	private PickerPart draggingPicker;
	private NumberSetting draggingNumberSetting;
	private TextSetting focusedTextSetting;
	/** The colour whose hex field has focus, and what has been typed into it so far. */
	private ColorSetting focusedHexSetting;
	private String hexInput = "";
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
		// Before anything reads the palette: dragging a theme colour has to redraw the menu in it
		// on the same frame, which is the whole point of editing a theme from inside the menu.
		VisualModule.INSTANCE.refreshTheme();
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

		Rect move = moveElementsRect(panelX, panelY);
		boolean moveHovered = move.contains(mouseX, mouseY);
		roundedRect(graphics, move.x, move.y, move.w, move.h, RADIUS_SMALL, moveHovered ? BUTTON_HOVER : BUTTON_BG);
		graphics.centeredText(font, "Move Elements", move.x + move.w / 2, move.y + (move.h - TEXT_HEIGHT) / 2, TEXT_PRIMARY);

		// The update notice sits above the button. Clipped to the panel rather than left to
		// spill across the module list, since this column is narrow on a small screen.
		String notice = UpdateChecker.bannerText();
		if (notice != null) {
			int available = categoryWidth() - 28;
			String text = font.width(notice) <= available ? notice : font.plainSubstrByWidth(notice, available);
			graphics.text(font, text, panelX + 14, move.y - 4 - TEXT_HEIGHT, TEXT_WARN);
		}
	}

	private Rect moveElementsRect(int panelX, int panelY) {
		int height = 18;
		return new Rect(panelX + 6, panelY + panelHeight() - PADDING - height, categoryWidth() - 12, height);
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
				case TextRow textRow -> renderTextRow(graphics, font, textRow);
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
		ColorSetting setting = colorRow.setting;
		// Only the label strip is clickable, not the picker an expanded row adds below it.
		if (hoverable && new Rect(bounds.x, bounds.y, bounds.w, ROW_HEIGHT).contains(mouseX, mouseY)) {
			roundedRect(graphics, bounds.x + 9, bounds.y, bounds.w - 18, ROW_HEIGHT - 2, RADIUS_SMALL, CATEGORY_HOVER);
		}
		graphics.text(font, setting.name(), bounds.x + 16, bounds.y + (ROW_HEIGHT - TEXT_HEIGHT) / 2, TEXT_SECONDARY);

		int swatchSize = 12;
		int swatchX = bounds.x + bounds.w - swatchSize - 14;
		int swatchY = bounds.y + (ROW_HEIGHT - swatchSize) / 2;
		checkerboard(graphics, swatchX, swatchY, swatchSize, swatchSize);
		roundedRectBordered(graphics, swatchX, swatchY, swatchSize, swatchSize, 3,
			setting.argb(), setting.argb(), BORDER);

		Picker picker = colorRow.picker;
		if (picker == null) return;
		renderSaturationSquare(graphics, picker.square, setting);
		renderHueBar(graphics, picker.hue, setting);
		renderAlphaBar(graphics, picker.alpha, setting);
		renderHexField(graphics, font, picker.hex, setting);
	}

	/**
	 * The saturation/brightness square, drawn as one vertical gradient per column.
	 *
	 * <p>A column runs from its own saturation at full brightness down to black, and the columns
	 * run from white to the pure hue - which is exactly the standard picker square. Per column
	 * rather than per pixel because the fill API only gradients vertically, and a few hundred
	 * quads on a screen that is already open costs nothing.
	 */
	private void renderSaturationSquare(GuiGraphicsExtractor graphics, Rect square, ColorSetting setting) {
		int pure = hueColor(setting.hue());
		for (int i = 0; i < square.w; i++) {
			int top = lerpColor(0xFFFFFFFF, pure, i / (float) Math.max(1, square.w - 1));
			graphics.fillGradient(square.x + i, square.y, square.x + i + 1, square.y + square.h, top, 0xFF000000);
		}
		crosshair(graphics, square.x + Math.round(setting.saturation() * (square.w - 1)),
			square.y + Math.round((1 - setting.brightness()) * (square.h - 1)));
	}

	private void renderHueBar(GuiGraphicsExtractor graphics, Rect bar, ColorSetting setting) {
		for (int i = 0; i < bar.w; i++) {
			graphics.fill(bar.x + i, bar.y, bar.x + i + 1, bar.y + bar.h, hueColor(i / (float) bar.w));
		}
		marker(graphics, bar, Math.round(setting.hue() * (bar.w - 1)));
	}

	private void renderAlphaBar(GuiGraphicsExtractor graphics, Rect bar, ColorSetting setting) {
		checkerboard(graphics, bar.x, bar.y, bar.w, bar.h);
		int opaque = setting.opaqueArgb();
		for (int i = 0; i < bar.w; i++) {
			int alpha = Math.round(255 * i / (float) Math.max(1, bar.w - 1));
			graphics.fill(bar.x + i, bar.y, bar.x + i + 1, bar.y + bar.h, (alpha << 24) | (opaque & 0x00FFFFFF));
		}
		marker(graphics, bar, Math.round(setting.alpha() / 255.0f * (bar.w - 1)));
	}

	private void renderHexField(GuiGraphicsExtractor graphics, Font font, Rect field, ColorSetting setting) {
		boolean focused = setting == focusedHexSetting;
		roundedRectBordered(graphics, field.x, field.y, field.w, field.h, 3, SLIDER_TRACK, SLIDER_TRACK,
			focused ? SLIDER_FILL : BORDER);
		String shown = focused ? hexInput : setting.hex();
		int textY = field.y + (field.h - TEXT_HEIGHT) / 2;
		graphics.text(font, shown, field.x + 4, textY, TEXT_PRIMARY);
		if (focused && (System.currentTimeMillis() / CARET_BLINK_MILLIS) % 2 == 0) {
			int caretX = field.x + 4 + font.width(shown);
			graphics.fill(caretX, textY - 1, caretX + 1, textY + TEXT_HEIGHT + 1, TEXT_PRIMARY);
		}
	}

	/** Ring rather than a dot, so the selected colour stays visible underneath it. */
	private void crosshair(GuiGraphicsExtractor graphics, int x, int y) {
		int outline = 0xFF000000;
		graphics.fill(x - 3, y - 1, x - 1, y, outline);
		graphics.fill(x + 2, y - 1, x + 4, y, outline);
		graphics.fill(x - 1, y - 3, x, y - 1, outline);
		graphics.fill(x - 1, y + 2, x, y + 4, outline);
		graphics.fill(x - 2, y - 2, x + 3, y - 1, 0xFFFFFFFF);
		graphics.fill(x - 2, y + 1, x + 3, y + 2, 0xFFFFFFFF);
		graphics.fill(x - 2, y - 1, x - 1, y + 1, 0xFFFFFFFF);
		graphics.fill(x + 2, y - 1, x + 3, y + 1, 0xFFFFFFFF);
	}

	private void marker(GuiGraphicsExtractor graphics, Rect bar, int offset) {
		int x = bar.x + offset;
		graphics.fill(x - 1, bar.y - 2, x + 2, bar.y + bar.h + 2, 0xFF000000);
		graphics.fill(x, bar.y - 1, x + 1, bar.y + bar.h + 1, 0xFFFFFFFF);
	}

	/** The usual two-tone grid, so "transparent" doesn't read as "black". */
	private void checkerboard(GuiGraphicsExtractor graphics, int x, int y, int w, int h) {
		for (int row = 0; row < h; row += CHECKER_SIZE) {
			for (int column = 0; column < w; column += CHECKER_SIZE) {
				boolean light = ((row / CHECKER_SIZE) + (column / CHECKER_SIZE)) % 2 == 0;
				graphics.fill(x + column, y + row,
					Math.min(x + column + CHECKER_SIZE, x + w), Math.min(y + row + CHECKER_SIZE, y + h),
					light ? 0xFF9A9A9A : 0xFF5E5E5E);
			}
		}
	}

	/** Fully saturated, fully bright colour at the given hue. */
	private static int hueColor(float hue) {
		float sector = (hue - (float) Math.floor(hue)) * 6.0f;
		float rising = sector % 1;
		int up = Math.round(rising * 255);
		int down = 255 - up;
		return switch ((int) sector) {
			case 0 -> 0xFFFF0000 | (up << 8);
			case 1 -> 0xFF00FF00 | (down << 16);
			case 2 -> 0xFF00FF00 | up;
			case 3 -> 0xFF0000FF | (down << 8);
			case 4 -> 0xFF0000FF | (up << 16);
			default -> 0xFFFF0000 | down;
		};
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

	private void renderTextRow(GuiGraphicsExtractor graphics, Font font, TextRow textRow) {
		Rect bounds = textRow.bounds;
		Rect field = textRow.field;
		boolean focused = textRow.setting == focusedTextSetting;
		graphics.text(font, textRow.setting.name(), bounds.x + 16, bounds.y + (bounds.h - TEXT_HEIGHT) / 2, TEXT_SECONDARY);

		roundedRectBordered(graphics, field.x, field.y, field.w, field.h, 3, SLIDER_TRACK, SLIDER_TRACK,
			focused ? SLIDER_FILL : BORDER);

		// Shows the tail rather than the head once the value outgrows the box, so what was just
		// typed stays visible.
		String text = textRow.setting.value();
		int inner = field.w - 8;
		while (!text.isEmpty() && font.width(text) > inner) {
			text = text.substring(1);
		}
		int textY = field.y + (field.h - TEXT_HEIGHT) / 2;
		graphics.text(font, text, field.x + 4, textY, TEXT_PRIMARY);

		if (focused && (System.currentTimeMillis() / CARET_BLINK_MILLIS) % 2 == 0) {
			int caretX = field.x + 4 + font.width(text);
			graphics.fill(caretX, textY - 1, caretX + 1, textY + TEXT_HEIGHT + 1, TEXT_PRIMARY);
		}
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
		return ROW_HEIGHT + (setting == expandedColorSetting ? PICKER_HEIGHT : 0);
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
							picker(colorSetting, x, cursorY, moduleWidth)));
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
					case TextSetting textSetting -> {
						Rect field = new Rect(x + moduleWidth - TEXT_FIELD_WIDTH - 14,
							cursorY + (ROW_HEIGHT - TEXT_FIELD_HEIGHT) / 2, TEXT_FIELD_WIDTH, TEXT_FIELD_HEIGHT);
						rows.add(new TextRow(textSetting, new Rect(x, cursorY, moduleWidth, ROW_HEIGHT), field));
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

	private Picker picker(ColorSetting setting, int x, int cursorY, int moduleWidth) {
		if (setting != expandedColorSetting) return null;
		int left = x + PICKER_INSET;
		int width = moduleWidth - PICKER_INSET * 2;
		int top = cursorY + ROW_HEIGHT;
		Rect square = new Rect(left, top, width, PICKER_SQUARE_HEIGHT);
		int hueY = square.y + square.h + PICKER_GAP;
		Rect hue = new Rect(left, hueY, width, PICKER_BAR_HEIGHT);
		int alphaY = hueY + PICKER_BAR_HEIGHT + PICKER_GAP;
		Rect alpha = new Rect(left, alphaY, width, PICKER_BAR_HEIGHT);
		Rect hex = new Rect(left, alphaY + PICKER_BAR_HEIGHT + PICKER_GAP, PICKER_HEX_WIDTH, PICKER_HEX_HEIGHT);
		return new Picker(square, hue, alpha, hex);
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

		// Any click drops focus; the row that was hit takes it back below. Anything else would
		// leave a field quietly eating keystrokes after the user moved on.
		blurTextField();

		if (left && moveElementsRect(panelX, panelY).contains(mouseX, mouseY)) {
			persistView();
			this.minecraft.setScreen(new MoveUiScreen(this));
			return true;
		}

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
					Picker picker = colorRow.picker;
					if (picker != null) {
						if (picker.square.contains(mouseX, mouseY)) {
							draggingPicker = PickerPart.SQUARE;
							applyPicker(colorRow.setting, picker, mouseX, mouseY);
							return true;
						}
						if (picker.hue.contains(mouseX, mouseY)) {
							draggingPicker = PickerPart.HUE;
							applyPicker(colorRow.setting, picker, mouseX, mouseY);
							return true;
						}
						if (picker.alpha.contains(mouseX, mouseY)) {
							draggingPicker = PickerPart.ALPHA;
							applyPicker(colorRow.setting, picker, mouseX, mouseY);
							return true;
						}
						if (picker.hex.contains(mouseX, mouseY)) {
							focusedHexSetting = colorRow.setting;
							hexInput = colorRow.setting.hex();
							return true;
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
				case TextRow textRow -> {
					if (textRow.field.contains(mouseX, mouseY)) {
						focusedTextSetting = textRow.setting;
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
		int mouseY = (int) event.y();

		if (draggingPicker != null || draggingNumberSetting != null) {
			for (Row row : settingsRows(openSettingsModule, rightX, panelY())) {
				if (draggingPicker != null && row instanceof ColorRow colorRow
					&& colorRow.setting == expandedColorSetting && colorRow.picker != null) {
					applyPicker(colorRow.setting, colorRow.picker, mouseX, mouseY);
					return true;
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
		if (draggingPicker != null || draggingNumberSetting != null) {
			draggingPicker = null;
			draggingNumberSetting = null;
			persistView();
			ModConfig.save();
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		int codepoint = event.codepoint();
		if (Character.isISOControl(codepoint)) {
			return focusedTextSetting != null || focusedHexSetting != null || super.charTyped(event);
		}
		if (focusedHexSetting != null) {
			// Only hex digits get in, so the field can never hold something unparseable.
			if (Character.digit(codepoint, 16) >= 0 && hexInput.length() < 8) {
				hexInput += Character.toString(codepoint).toUpperCase();
				focusedHexSetting.setHex(hexInput);
			}
			return true;
		}
		if (focusedTextSetting != null) {
			focusedTextSetting.setValue(focusedTextSetting.value() + Character.toString(codepoint));
			return true;
		}
		return super.charTyped(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (focusedHexSetting != null) {
			switch (event.key()) {
				case GLFW.GLFW_KEY_BACKSPACE -> {
					if (!hexInput.isEmpty()) {
						hexInput = hexInput.substring(0, hexInput.length() - 1);
						focusedHexSetting.setHex(hexInput);
					}
				}
				case GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> blurTextField();
				default -> {
					return super.keyPressed(event);
				}
			}
			return true;
		}
		if (focusedTextSetting == null) return super.keyPressed(event);
		switch (event.key()) {
			case GLFW.GLFW_KEY_BACKSPACE -> {
				String value = focusedTextSetting.value();
				if (!value.isEmpty()) {
					focusedTextSetting.setValue(value.substring(0, value.length() - 1));
				}
			}
			// Escape leaves the field rather than the whole screen - closing the menu out from
			// under someone who was only trying to stop typing is the wrong thing to do.
			case GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> blurTextField();
			default -> {
				return super.keyPressed(event);
			}
		}
		return true;
	}

	private void blurTextField() {
		if (focusedTextSetting == null && focusedHexSetting == null) return;
		focusedTextSetting = null;
		focusedHexSetting = null;
		hexInput = "";
		ModConfig.save();
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

	private void applyPicker(ColorSetting setting, Picker picker, int mouseX, int mouseY) {
		switch (draggingPicker) {
			case SQUARE -> setting.setSaturationBrightness(
				fraction(mouseX, picker.square.x, picker.square.w),
				1 - fraction(mouseY, picker.square.y, picker.square.h));
			case HUE -> setting.setHue(fraction(mouseX, picker.hue.x, picker.hue.w));
			case ALPHA -> setting.setChannel(ColorSetting.Channel.ALPHA,
				Math.round(fraction(mouseX, picker.alpha.x, picker.alpha.w) * 255));
		}
		// The field would otherwise keep showing the value from before the drag started.
		if (focusedHexSetting == setting) {
			hexInput = setting.hex();
		}
	}

	private static float fraction(int position, int start, int size) {
		return Math.max(0, Math.min(1, (position - start) / (float) Math.max(1, size - 1)));
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

	private record Rect(int x, int y, int w, int h) {
		boolean contains(double px, double py) {
			return px >= x && px < x + w && py >= y && py < y + h;
		}
	}

	/** The four hit areas of an expanded colour row. */
	private record Picker(Rect square, Rect hue, Rect alpha, Rect hex) {
	}

	private record CardRect(Module module, Rect bounds) {
	}

	/** One laid-out line in the settings panel. */
	private sealed interface Row permits GroupRow, ColorRow, NumberRow, ToggleRow, TextRow, ActionRow {
		Rect bounds();
	}

	private record GroupRow(SettingGroup group, Rect bounds) implements Row {
	}

	private record ColorRow(ColorSetting setting, Rect bounds, Picker picker) implements Row {
	}

	private record NumberRow(NumberSetting setting, Rect bounds, Rect slider) implements Row {
	}

	private record ToggleRow(BooleanSetting setting, Rect bounds) implements Row {
	}

	private record TextRow(TextSetting setting, Rect bounds, Rect field) implements Row {
	}

	private record ActionRow(ModuleAction action, Rect bounds) implements Row {
	}
}
