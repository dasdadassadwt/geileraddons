package geiler.addons.client.gui;

import geiler.addons.client.config.ConfigTransferService;
import geiler.addons.client.config.ConfigScreenGeometry;
import geiler.addons.client.config.ConfigScreenGeometry.Rect;
import geiler.addons.client.module.impl.VisualModule;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static geiler.addons.client.gui.GuiTheme.*;

/** Two-stage, recoverable configuration reset flow. */
public final class ConfigResetScreen extends Screen {
	private static final int ROW = 22;
	private final Screen parent;
	private final EnumSet<ConfigTransferService.ResetArea> selected = EnumSet.allOf(ConfigTransferService.ResetArea.class);
	private boolean review;
	private boolean running;
	private boolean finished;
	private int scroll;
	private String status = "";
	private Path recoveryPath;

	public ConfigResetScreen(Screen parent) {
		super(Component.literal("Reset GeilerAddons Configuration"));
		this.parent = parent;
	}

	@Override public boolean isPauseScreen() { return false; }

	@Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		VisualModule.INSTANCE.refreshTheme();
		Layout l = layout();
		graphics.fill(0, 0, width, height, DIALOG_SHADE);
		roundedRectBordered(graphics, l.x, l.y, l.w, l.h, RADIUS, PANEL_TOP, PANEL_BOTTOM, BORDER);
		graphics.centeredText(font, review ? "Review Reset" : "Reset Configuration", l.x + l.w / 2, l.y + 10, TEXT_PRIMARY);
		if (!review) {
			graphics.centeredText(font, fit("Choose areas; scroll for more. Unchecked areas are preserved.", l.w - 20),
				l.x + l.w / 2, l.y + 27, TEXT_MUTED);
			Rect viewport = checklistViewport(l);
			int visible = ConfigScreenGeometry.visibleRows(viewport, ROW);
			scroll = Math.max(0, Math.min(scroll, ConfigScreenGeometry.maxScroll(ConfigTransferService.ResetArea.values().length, visible)));
			if (viewport.height() > 0) graphics.enableScissor(viewport.x(), viewport.y(), viewport.right(), viewport.bottom());
			for (int row = 0; row < visible; row++) {
				int i = scroll + row;
				if (i >= ConfigTransferService.ResetArea.values().length) break;
				ConfigTransferService.ResetArea area = ConfigTransferService.ResetArea.values()[i];
				int y = viewport.y() + row * ROW;
				boolean checked = selected.contains(area);
				boolean hovered = !running && mouseX >= l.x + 12 && mouseX < l.x + l.w - 12 && mouseY >= y && mouseY < y + ROW - 2;
				roundedRectBordered(graphics, l.x + 12, y, l.w - 24, ROW - 2, RADIUS_SMALL,
					hovered ? CARD_BG_HOVER : CARD_BG, CARD_BG, BORDER);
				roundedRectBordered(graphics, l.x + 20, y + 3, 15, 15, 3,
					checked ? BUTTON_HOVER : SLIDER_TRACK, checked ? BUTTON_HOVER : SLIDER_TRACK, BORDER);
				if (checked) graphics.centeredText(font, "✓", l.x + 27, y + 6, TEXT_ON_ACCENT);
				graphics.text(font, fit(area.label(), l.w - 66), l.x + 42, y + 6, TEXT_PRIMARY);
				if (hovered) graphics.setTooltipForNextFrame(Component.literal(area.label()), mouseX, mouseY);
			}
			if (viewport.height() > 0) graphics.disableScissor();
			button(graphics, "Cancel", l.x + 12, l.y + l.h - 29, 74, 20, mouseX, mouseY, running);
			button(graphics, "Review Reset…", l.x + l.w - 122, l.y + l.h - 29, 110, 20, mouseX, mouseY, running || selected.isEmpty());
		} else {
			Rect viewport = reviewViewport(l);
			List<ReviewLine> lines = reviewLines(l);
			int visible = ConfigScreenGeometry.visibleRows(viewport, 10);
			scroll = Math.max(0, Math.min(scroll, ConfigScreenGeometry.maxScroll(lines.size(), visible)));
			if (viewport.height() > 0) graphics.enableScissor(viewport.x(), viewport.y(), viewport.right(), viewport.bottom());
			for (int row = 0; row < visible; row++) {
				int i = scroll + row;
				if (i >= lines.size()) break;
				graphics.text(font, lines.get(i).text(), viewport.x() + 3, viewport.y() + row * 10, lines.get(i).color());
			}
			if (viewport.height() > 0) graphics.disableScissor();
			if (!finished) graphics.centeredText(font, "A full recovery profile is saved before applying this reset.",
				l.x + l.w / 2, l.y + l.h - 53, TEXT_WARN);
			ConfigScreenGeometry.ResetFooter footer = ConfigScreenGeometry.resetFooter(rect(l));
			button(graphics, finished ? "Done" : "Back", footer.back().x(), footer.back().y(), footer.back().width(), footer.back().height(), mouseX, mouseY, running);
			button(graphics, finished ? "Done" : running ? "Resetting…" : "RESET SELECTED DATA",
				footer.action().x(), footer.action().y(), footer.action().width(), footer.action().height(), mouseX, mouseY, running || (!finished && selected.isEmpty()));
		}
		if (!status.isBlank()) graphics.centeredText(font, fit(status, l.w - 24), l.x + l.w / 2,
			l.y + l.h - (recoveryPath == null ? 43 : 39), TEXT_MUTED);
		if (recoveryPath != null) button(graphics, "Open recovery folder", l.x + 12, l.y + l.h - 58,
			138, 16, mouseX, mouseY, running);
	}

	@Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != 0 || running) return true;
		Layout l = layout();
		int x = (int) event.x(), y = (int) event.y();
		if (recoveryPath != null && inside(x, y, l.x + 12, l.y + l.h - 58, 138, 16)) { openFolder(recoveryPath); return true; }
		if (!review) {
			Rect viewport = checklistViewport(l);
			int visible = ConfigScreenGeometry.visibleRows(viewport, ROW);
			for (int row = 0; row < visible; row++) {
				int index = scroll + row;
				if (index >= ConfigTransferService.ResetArea.values().length) break;
				ConfigTransferService.ResetArea area = ConfigTransferService.ResetArea.values()[index];
				int rowY = viewport.y() + row * ROW;
				if (inside(x, y, l.x + 12, rowY, l.w - 24, ROW - 2)) {
					if (!selected.remove(area)) selected.add(area);
					return true;
				}
			}
			if (inside(x, y, l.x + 12, l.y + l.h - 29, 74, 20)) { close(); return true; }
			if (inside(x, y, l.x + l.w - 122, l.y + l.h - 29, 110, 20) && !selected.isEmpty()) { review = true; scroll = 0; status = ""; return true; }
		} else {
			if (recoveryPath != null && inside(x, y, l.x + 12, l.y + l.h - 58, 138, 16)) { openFolder(recoveryPath); return true; }
			ConfigScreenGeometry.ResetFooter footer = ConfigScreenGeometry.resetFooter(rect(l));
			if (inside(x, y, footer.back().x(), footer.back().y(), footer.back().width(), footer.back().height())) { if (finished) close(); else { review = false; scroll = 0; } return true; }
			if (inside(x, y, footer.action().x(), footer.action().y(), footer.action().width(), footer.action().height())) {
				if (finished) { close(); return true; }
				running = true;
				status = "Saving recovery profile…";
				ConfigTransferService.reset(new ConfigTransferService.ResetSelection(selected), result -> {
					running = false;
					status = result.message();
					recoveryPath = result.recoveryPath();
					finished = true;
				});
				return true;
			}
		}
		return true;
	}

	@Override public boolean keyPressed(KeyEvent event) {
		if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) { if (running) return true; if (review && !finished) review = false; else close(); return true; }
		return true;
	}
	@Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		Layout l = layout();
		Rect viewport = review ? reviewViewport(l) : checklistViewport(l);
		if (mouseX < viewport.x() || mouseX >= viewport.right() || mouseY < viewport.y() || mouseY >= viewport.bottom()) return true;
		int rows = review ? linesFor(l).size() : ConfigTransferService.ResetArea.values().length;
		int visible = ConfigScreenGeometry.visibleRows(viewport, review ? 10 : ROW);
		scroll = Math.max(0, Math.min(ConfigScreenGeometry.maxScroll(rows, visible), scroll - (int) Math.signum(scrollY)));
		return true;
	}
	@Override public void onClose() { close(); }
	private void close() { if (!running) minecraft.setScreen(parent); }
	private String selectedLabels() { return join(selected); }
	private String retainedLabels() {
		EnumSet<ConfigTransferService.ResetArea> remaining = EnumSet.allOf(ConfigTransferService.ResetArea.class);
		remaining.removeAll(selected);
		return remaining.isEmpty() ? "None" : join(remaining);
	}
	private String join(Set<ConfigTransferService.ResetArea> areas) {
		return areas.stream().map(ConfigTransferService.ResetArea::label).reduce((a, b) -> a + " · " + b).orElse("None");
	}
	private void openFolder(Path path) { ConfigTransferService.openFolder(path.toAbsolutePath().getParent()); }
	private void button(GuiGraphicsExtractor g, String label, int x, int y, int w, int h, int mx, int my, boolean disabled) {
		boolean hover = !disabled && mx >= x && mx < x + w && my >= y && my < y + h;
		roundedRect(g, x, y, w, h, RADIUS_SMALL, disabled ? SLIDER_TRACK : hover ? BUTTON_HOVER : BUTTON_BG);
		g.centeredText(font, fit(label, w - 8), x + w / 2, y + (h - font.lineHeight) / 2,
			disabled ? TEXT_MUTED : hover ? TEXT_ON_ACCENT : TEXT_PRIMARY);
	}
	private Rect checklistViewport(Layout l) { return ConfigScreenGeometry.content(rect(l), 41, 38); }
	private Rect reviewViewport(Layout l) { return ConfigScreenGeometry.content(rect(l), 37, 63); }
	private List<ReviewLine> linesFor(Layout l) { return reviewLines(l); }
	private List<ReviewLine> reviewLines(Layout l) {
		List<ReviewLine> lines = new ArrayList<>();
		int maxWidth = Math.max(1, l.w - 39);
		lines.add(new ReviewLine("Will reset:", TEXT_PRIMARY));
		appendAreas(lines, selected, "• ", TEXT_ON_ACCENT, maxWidth);
		EnumSet<ConfigTransferService.ResetArea> kept = EnumSet.allOf(ConfigTransferService.ResetArea.class);
		kept.removeAll(selected);
		lines.add(new ReviewLine("Will keep:", TEXT_PRIMARY));
		appendAreas(lines, kept, "• ", TEXT_MUTED, maxWidth);
		return List.copyOf(lines);
	}
	private void appendAreas(List<ReviewLine> lines, Set<ConfigTransferService.ResetArea> areas, String firstPrefix, int color, int maxWidth) {
		if (areas.isEmpty()) { lines.add(new ReviewLine(firstPrefix + "None", TEXT_MUTED)); return; }
		for (ConfigTransferService.ResetArea area : areas) {
			String remainder = area.label();
			String prefix = firstPrefix;
			while (!remainder.isBlank()) {
				StringBuilder line = new StringBuilder(prefix);
				String[] words = remainder.split("\\s+");
				int consumed = 0;
				for (String word : words) {
					String candidate = line + (line.length() == prefix.length() ? "" : " ") + word;
					if (font.width(candidate) > maxWidth && line.length() > prefix.length()) break;
					if (font.width(candidate) > maxWidth) break;
					if (line.length() > prefix.length()) line.append(' ');
					line.append(word);
					consumed++;
				}
				if (consumed == 0) { lines.add(new ReviewLine(fit(prefix + words[0], maxWidth), color));
					remainder = String.join(" ", java.util.Arrays.copyOfRange(words, 1, words.length)); }
				else { lines.add(new ReviewLine(line.toString(), color)); remainder = String.join(" ", java.util.Arrays.copyOfRange(words, consumed, words.length)); }
				prefix = "  ";
			}
		}
	}
	private Rect rect(Layout l) { return new Rect(l.x, l.y, l.w, l.h); }
	private Layout layout() { Rect panel = ConfigScreenGeometry.panel(width, height, 620, 460); return new Layout(panel.x(), panel.y(), panel.width(), panel.height()); }
	private boolean inside(int x, int y, int bx, int by, int bw, int bh) { return x >= bx && x < bx + bw && y >= by && y < by + bh; }
	private String fit(String value, int width) { return font.width(value) <= width ? value : font.plainSubstrByWidth(value, Math.max(1, width - font.width("…"))) + "…"; }
	private record Layout(int x, int y, int w, int h) { }
	private record ReviewLine(String text, int color) { }
}
