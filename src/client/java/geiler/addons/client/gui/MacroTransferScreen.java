package geiler.addons.client.gui;

import geiler.addons.client.config.ModConfig;
import geiler.addons.client.macro.MacroDefinition;
import geiler.addons.client.macro.MacroTransfer;
import geiler.addons.client.module.impl.MacrosModule;
import geiler.addons.client.module.impl.VisualModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static geiler.addons.client.gui.GuiTheme.*;

/** Selects one or more macros for clipboard sharing or appends a shared package. */
public final class MacroTransferScreen extends Screen {
	private static final int PANEL_MARGIN = 22;
	private static final int ROW_HEIGHT = 23;
	private static final int BUTTON_HEIGHT = 20;
	private static final int MAX_VISIBLE_ROWS = 14;

	private final Screen parent;
	private final List<MacroDefinition> macros = new ArrayList<>();
	private final Set<Integer> selectedIds = new HashSet<>();
	private int scrollRows;
	private String status = "";

	public MacroTransferScreen(Screen parent) {
		super(Component.literal("Share / Paste Macros"));
		this.parent = parent;
		macros.addAll(MacrosModule.INSTANCE.macros());
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		VisualModule.INSTANCE.refreshTheme();
		graphics.fill(0, 0, width, height, DIALOG_SHADE);
		int panelX = PANEL_MARGIN;
		int panelY = PANEL_MARGIN;
		int panelW = Math.max(340, width - PANEL_MARGIN * 2);
		int panelH = Math.max(210, height - PANEL_MARGIN * 2);
		roundedRectBordered(graphics, panelX, panelY, panelW, panelH, RADIUS,
			PANEL_TOP, PANEL_BOTTOM, BORDER);

		Font font = this.font;
		graphics.text(font, "Share / Paste Macros", panelX + 12, panelY + 9, TEXT_PRIMARY);
		graphics.text(font, "Select multiple macros, copy them as portable JSON, or append a package from the clipboard.",
			panelX + 12, panelY + 22, TEXT_MUTED);

		int headerButtonX = panelX + panelW - 94;
		button(graphics, allSelected() ? "Clear all" : "Select all", headerButtonX, panelY + 7,
			82, BUTTON_HEIGHT, mouseX, mouseY);

		int listX = panelX + 10;
		int listY = panelY + 48;
		int controlsY = panelY + panelH - 30;
		int visible = Math.min(MAX_VISIBLE_ROWS, Math.max(1, (controlsY - listY - 8) / ROW_HEIGHT));
		int maxScroll = Math.max(0, macros.size() - visible);
		scrollRows = Math.min(scrollRows, maxScroll);
		if (macros.isEmpty()) {
			graphics.centeredText(font, "No macros to share yet.", panelX + panelW / 2, listY + 18, TEXT_MUTED);
		} else {
			for (int row = 0; row < visible; row++) {
				int index = scrollRows + row;
				if (index >= macros.size()) break;
				MacroDefinition macro = macros.get(index);
				int y = listY + row * ROW_HEIGHT;
				boolean hovered = mouseX >= listX && mouseX < panelX + panelW - 10
					&& mouseY >= y && mouseY < y + ROW_HEIGHT - 3;
				boolean selected = selectedIds.contains(macro.id());
				int background = selected ? CARD_BG_ENABLED : (hovered ? CARD_BG_HOVER : CARD_BG);
				roundedRectBordered(graphics, listX, y, panelW - 20, ROW_HEIGHT - 3, RADIUS_SMALL,
					background, background, selected ? CARD_BORDER_ENABLED : CARD_BORDER);
				int checkX = listX + 7;
				int checkY = y + 5;
				roundedRectBordered(graphics, checkX, checkY, 14, 14, 3,
					selected ? BUTTON_HOVER : SLIDER_TRACK, selected ? BUTTON_HOVER : SLIDER_TRACK,
					selected ? TEXT_PRIMARY : BORDER);
				if (selected) graphics.centeredText(font, "✓", checkX + 7, checkY + 3, TEXT_ON_ACCENT);
				graphics.text(font, trim(macro.name(), panelW - 102) + "  •  id " + macro.id(),
					listX + 28, y + 5, selected ? TEXT_ON_ACCENT : TEXT_PRIMARY);
				graphics.text(font, macro.steps().size() + " step(s)  •  "
					+ (macro.enabled() ? "enabled" : "disabled") + "  •  " + macro.keybind().displayName(),
					listX + 28, y + 14, selected ? TEXT_ON_ACCENT : TEXT_MUTED);
			}
		}
		if (macros.size() > visible) {
			graphics.text(font, "Scroll to select more macros (" + (scrollRows + 1) + "–"
				+ Math.min(macros.size(), scrollRows + visible) + ").", listX, controlsY - 11, TEXT_WARN);
		}

		int shareX = listX;
		button(graphics, "Share selected", shareX, controlsY, 108, BUTTON_HEIGHT, mouseX, mouseY);
		button(graphics, "Paste from clipboard", shareX + 113, controlsY, 136, BUTTON_HEIGHT, mouseX, mouseY);
		button(graphics, "Done", panelX + panelW - 52, controlsY, 42, BUTTON_HEIGHT, mouseX, mouseY);
		graphics.text(font, selectedIds.size() + " selected", shareX + 256, controlsY + 6, TEXT_MUTED);
		if (!status.isBlank()) graphics.text(font, trim(status, panelW - 24), listX, controlsY - 25, TEXT_PRIMARY);
	}

	private void button(GuiGraphicsExtractor graphics, String label, int x, int y, int w, int h,
		int mouseX, int mouseY) {
		boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		roundedRect(graphics, x, y, w, h, RADIUS_SMALL, hovered ? BUTTON_HOVER : BUTTON_BG);
		graphics.centeredText(font, label, x + w / 2, y + 6, hovered ? TEXT_ON_ACCENT : TEXT_PRIMARY);
	}

	private boolean allSelected() {
		return !macros.isEmpty() && selectedIds.size() == macros.size();
	}

	private void toggleAll() {
		if (allSelected()) {
			selectedIds.clear();
		} else {
			for (MacroDefinition macro : macros) selectedIds.add(macro.id());
		}
	}

	private void shareSelected() {
		List<MacroDefinition> selected = new ArrayList<>();
		for (MacroDefinition macro : macros) if (selectedIds.contains(macro.id())) selected.add(macro);
		if (selected.isEmpty()) {
			status = "Select at least one macro first.";
			return;
		}
		Minecraft.getInstance().keyboardHandler.setClipboard(MacroTransfer.encode(selected));
		status = "Copied " + selected.size() + " macro(s) to the clipboard.";
	}

	private void pasteMacros() {
		MacroTransfer.ImportResult result = MacrosModule.INSTANCE.importEncoded(
			Minecraft.getInstance().keyboardHandler.getClipboard());
		if (!result.success()) {
			status = result.error();
			return;
		}
		macros.addAll(result.macros());
		selectedIds.clear();
		for (MacroDefinition macro : result.macros()) selectedIds.add(macro.id());
		status = "Pasted " + result.macros().size() + " macro(s) as new entries.";
		ModConfig.markDirty();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != 0) return true;
		int x = (int) event.x();
		int y = (int) event.y();
		int panelX = PANEL_MARGIN;
		int panelY = PANEL_MARGIN;
		int panelW = Math.max(340, width - PANEL_MARGIN * 2);
		int panelH = Math.max(210, height - PANEL_MARGIN * 2);
		int listX = panelX + 10;
		int listY = panelY + 48;
		int controlsY = panelY + panelH - 30;
		int visible = Math.min(MAX_VISIBLE_ROWS, Math.max(1, (controlsY - listY - 8) / ROW_HEIGHT));

		if (x >= panelX + panelW - 94 && x < panelX + panelW - 12 && y >= panelY + 7
			&& y < panelY + 7 + BUTTON_HEIGHT) {
			toggleAll();
			return true;
		}
		for (int row = 0; row < visible; row++) {
			int index = scrollRows + row;
			if (index >= macros.size()) break;
			int rowY = listY + row * ROW_HEIGHT;
			if (x < listX || x >= panelX + panelW - 10 || y < rowY || y >= rowY + ROW_HEIGHT - 3) continue;
			MacroDefinition macro = macros.get(index);
			if (!selectedIds.add(macro.id())) selectedIds.remove(macro.id());
			return true;
		}
		if (y >= controlsY && y < controlsY + BUTTON_HEIGHT) {
			if (x >= listX && x < listX + 108) {
				shareSelected();
				return true;
			}
			if (x >= listX + 113 && x < listX + 249) {
				pasteMacros();
				return true;
			}
			if (x >= panelX + panelW - 52 && x < panelX + panelW - 10) {
				closeToParent();
				return true;
			}
		}
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		int panelY = PANEL_MARGIN;
		int panelH = Math.max(210, height - PANEL_MARGIN * 2);
		int listY = panelY + 48;
		int controlsY = panelY + panelH - 30;
		if (mouseY >= listY && mouseY < controlsY) {
			int visible = Math.min(MAX_VISIBLE_ROWS, Math.max(1, (controlsY - listY - 8) / ROW_HEIGHT));
			int max = Math.max(0, macros.size() - visible);
			scrollRows = Math.max(0, Math.min(max, scrollRows - (int) Math.signum(scrollY)));
		}
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE) {
			closeToParent();
			return true;
		}
		return true;
	}

	@Override
	public void onClose() {
		closeToParent();
	}

	private void closeToParent() {
		ModConfig.markDirty();
		minecraft.setScreen(parent);
	}

	private static String trim(String value, int max) {
		if (value == null) return "";
		return value.length() <= max ? value : value.substring(0, Math.max(0, max - 1)) + "…";
	}

}
