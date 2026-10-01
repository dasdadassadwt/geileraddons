package geiler.addons.client.gui;

import geiler.addons.client.config.ConfigTransferService;
import geiler.addons.client.config.ConfigScreenGeometry;
import geiler.addons.client.config.ConfigScreenGeometry.Footer;
import geiler.addons.client.config.ConfigScreenGeometry.Rect;
import geiler.addons.client.module.impl.VisualModule;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static geiler.addons.client.gui.GuiTheme.*;

/** Lists self-contained config profiles and requires a preview plus a separate import confirmation. */
public final class ConfigTransferScreen extends Screen {
	private final Screen parent;
	private final List<ConfigTransferService.Profile> profiles = new ArrayList<>();
	private EditBox exportName;
	private ConfigTransferService.Profile selected;
	private ConfigTransferService.Profile previewed;
	private boolean loading = true;
	private boolean running;
	private int scroll;
	private String notice = "";
	private Path lastResultPath;

	public ConfigTransferScreen(Screen parent) {
		super(Component.literal("GeilerAddons Configuration Profiles"));
		this.parent = parent;
		refreshProfiles();
	}

	@Override public void init() {
		Layout l = layout();
		Rect name = toolbar(l).name();
		exportName = addRenderableWidget(new EditBox(font, name.x(), name.y(), name.width(), name.height(),
			Component.literal("Profile name")));
		exportName.setMaxLength(64);
		exportName.setHint(Component.literal("Profile name (optional)"));
	}
	@Override public boolean isPauseScreen() { return false; }

	@Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		VisualModule.INSTANCE.refreshTheme();
		Layout l = layout();
		graphics.fill(0, 0, width, height, DIALOG_SHADE);
		roundedRectBordered(graphics, l.x, l.y, l.w, l.h, RADIUS, PANEL_TOP, PANEL_BOTTOM, BORDER);
		boolean compact = ConfigScreenGeometry.compactProfilePanel(rect(l));
		graphics.centeredText(font, "Configuration Profiles", l.x + l.w / 2, l.y + (compact ? 5 : 9), TEXT_PRIMARY);
		if (!compact) graphics.text(font, fit("Export includes settings and split local collections; imports replace the full active profile.", l.w - 24),
			l.x + 12, l.y + 29, TEXT_MUTED);
		Toolbar toolbar = toolbar(l);
		if (previewed == null) {
			String exportLabel = compact ? "Export" : "Export Snapshot";
			String refreshLabel = compact ? (loading ? "…" : "Refresh") : (loading ? "Loading…" : "Refresh");
			button(graphics, exportLabel, toolbar.export().x(), toolbar.export().y(), toolbar.export().width(), toolbar.export().height(), mouseX, mouseY, running);
			button(graphics, refreshLabel, toolbar.refresh().x(), toolbar.refresh().y(), toolbar.refresh().width(), toolbar.refresh().height(), mouseX, mouseY, loading || running);
		}
		exportName.visible = previewed == null;
		Rect viewport = previewed == null ? profileViewport(l) : previewViewport(l);
		int rowHeight = 26;
		int visible = previewed == null ? ConfigScreenGeometry.visibleRows(viewport, rowHeight) : 0;
		scroll = Math.max(0, Math.min(scroll, ConfigScreenGeometry.maxScroll(profiles.size(), visible)));
		if (profiles.isEmpty() && !loading && previewed == null) graphics.centeredText(font, fit("No valid exported profiles yet.", l.w - 24), l.x + l.w / 2, viewport.y() + 16, TEXT_MUTED);
		if (viewport.height() > 0) graphics.enableScissor(viewport.x(), viewport.y(), viewport.right(), viewport.bottom());
		boolean needsScroll = previewed == null && profiles.size() > visible;
		int cardWidth = Math.max(0, l.w - 20 - (needsScroll ? 8 : 0));
		for (int row = 0; row < visible; row++) {
			int i = scroll + row;
			if (i >= profiles.size()) break;
			ConfigTransferService.Profile profile = profiles.get(i);
			int y = viewport.y() + row * rowHeight;
			boolean active = selected != null && profile.path().equals(selected.path());
			boolean hover = new Rect(l.x + 10, y, cardWidth, rowHeight - 2).contains(mouseX, mouseY);
			roundedRectBordered(graphics, l.x + 10, y, cardWidth, rowHeight - 2, RADIUS_SMALL,
				active ? CARD_BG_ENABLED : hover ? CARD_BG_HOVER : CARD_BG,
				active ? CARD_BG_ENABLED : CARD_BG, active ? CARD_BORDER_ENABLED : BORDER);
			graphics.text(font, fit(profile.name(), l.w - 158), l.x + 18, y + 4, TEXT_PRIMARY);
			String detail = profile.entries() + " files · " + formatBytes(profile.bytes()) + " · " + profile.createdAt();
			graphics.text(font, fit(detail, l.w - 158), l.x + 18, y + 14, TEXT_MUTED);
		}
		if (previewed != null) {
			graphics.text(font, "IMPORT PREVIEW", viewport.x() + 3, viewport.y() + 2, TEXT_PRIMARY);
			graphics.text(font, fit("Will replace all active settings and split data.", viewport.width() - 6), viewport.x() + 3, viewport.y() + 13, TEXT_WARN);
			graphics.text(font, fit(previewed.name() + " · " + previewed.entries() + " files · " + formatBytes(previewed.bytes()), viewport.width() - 6),
				viewport.x() + 3, viewport.y() + 24, TEXT_MUTED);
			graphics.text(font, fit(previewed.createdAt(), viewport.width() - 6), viewport.x() + 3, viewport.y() + 35, TEXT_MUTED);
		}
		if (viewport.height() > 0) graphics.disableScissor();
		if (needsScroll && viewport.height() > 0) {
			int trackX = viewport.right() - 3;
			graphics.fill(trackX, viewport.y(), trackX + 2, viewport.bottom(), SLIDER_TRACK);
			int maxScroll = ConfigScreenGeometry.maxScroll(profiles.size(), visible);
			int thumbHeight = Math.max(12, viewport.height() * visible / profiles.size());
			int thumbY = viewport.y() + (maxScroll == 0 ? 0 : (viewport.height() - thumbHeight) * scroll / maxScroll);
			graphics.fill(trackX, thumbY, trackX + 2, thumbY + Math.min(thumbHeight, viewport.height()), SCROLLBAR);
		}
		Rect panel = rect(l);
		Footer footer = ConfigScreenGeometry.transferFooter(panel, previewed != null);
		button(graphics, previewed != null ? "Back" : lastResultPath == null ? "Folder" : "Result",
			footer.folder().x(), footer.folder().y(), footer.folder().width(), footer.folder().height(), mouseX, mouseY, running);
		button(graphics, previewed == null ? "Preview Import…" : "CONFIRM IMPORT",
			footer.action().x(), footer.action().y(), footer.action().width(), footer.action().height(), mouseX, mouseY,
			running || selected == null || (previewed == null && selected == null));
		button(graphics, "Done", footer.done().x(), footer.done().y(), footer.done().width(), footer.done().height(), mouseX, mouseY, running);
		if (!notice.isBlank() && previewed == null) graphics.text(font, fit(notice, l.w - 20), l.x + 12,
			compact ? l.y + 45 : footer.action().y() - 13, TEXT_MUTED);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	@Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) return true;
		if (event.button() != 0 || running) return true;
		Layout l = layout(); int x = (int) event.x(), y = (int) event.y();
		Toolbar toolbar = toolbar(l);
		Footer footer = ConfigScreenGeometry.transferFooter(rect(l), previewed != null);
		Rect viewport = previewed == null ? profileViewport(l) : previewViewport(l);
		int rowHeight = 26;
		int visible = previewed == null ? ConfigScreenGeometry.visibleRows(viewport, rowHeight) : 0;
		boolean needsScroll = previewed == null && profiles.size() > visible;
		int cardWidth = Math.max(0, l.w - 20 - (needsScroll ? 8 : 0));
		for (int row = 0; row < visible; row++) {
			int i = scroll + row;
			if (i >= profiles.size()) break;
			int rowY = viewport.y() + row * rowHeight;
			if (new Rect(l.x + 10, rowY, cardWidth, rowHeight - 2).contains(x, y)) {
				selected = profiles.get(i); previewed = null; notice = ""; return true;
			}
		}
		if (previewed == null && toolbar.refresh().contains(x, y)) { refreshProfiles(); return true; }
		if (previewed == null && toolbar.export().contains(x, y)) {
			running = true; notice = "Saving and exporting…";
			ConfigTransferService.exportProfile(exportName.getValue(), result -> {
				running = false; notice = result.message(); lastResultPath = result.path(); previewed = null; refreshProfiles();
			}); return true;
		}
		if (footer.folder().contains(x, y) && previewed != null) { previewed = null; return true; }
		if (footer.folder().contains(x, y)) {
			Path open = lastResultPath == null ? ConfigTransferService.configDirectory() : lastResultPath.toAbsolutePath().getParent();
			ConfigTransferService.openFolder(open); return true;
		}
		if (footer.done().contains(x, y)) { onClose(); return true; }
		if (previewed == null && selected != null
			&& footer.action().contains(x, y)) {
			running = true; notice = "Validating profile before showing confirmation…";
			ConfigTransferService.preview(selected.path(), result -> {
				running = false;
				if (result.success()) { previewed = result.profile(); notice = "Review the replacement warning, then confirm once more."; }
				else notice = "Import blocked: " + result.message();
			}); return true;
		}
		if (previewed != null && footer.action().contains(x, y)) {
			running = true; notice = "Creating recovery copy and importing…";
			ConfigTransferService.importProfile(previewed.path(), result -> {
				running = false; notice = result.message(); lastResultPath = result.recoveryPath(); previewed = null;
				if (result.success()) refreshProfiles();
			}); return true;
		}
		return true;
	}

	@Override public boolean keyPressed(KeyEvent event) {
		if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
		return super.keyPressed(event);
	}
	@Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		Layout l = layout(); if (previewed != null) return true; Rect viewport = profileViewport(l);
		if (!viewport.contains((int) mouseX, (int) mouseY)) return true;
		int visible = ConfigScreenGeometry.visibleRows(viewport, 26);
		scroll = Math.max(0, Math.min(ConfigScreenGeometry.maxScroll(profiles.size(), visible), scroll - (int) Math.signum(scrollY)));
		return true;
	}
	@Override public void onClose() { if (!running) minecraft.setScreen(parent); }
	private void refreshProfiles() {
		loading = true;
		ConfigTransferService.listProfiles(found -> {
			profiles.clear(); profiles.addAll(found); loading = false;
			if (selected != null) selected = profiles.stream().filter(p -> p.path().equals(selected.path())).findFirst().orElse(null);
		});
	}
	private void button(GuiGraphicsExtractor g, String label, int x, int y, int w, int h, int mx, int my, boolean disabled) {
		boolean hover = !disabled && mx >= x && mx < x + w && my >= y && my < y + h;
		roundedRect(g, x, y, w, h, RADIUS_SMALL, disabled ? SLIDER_TRACK : hover ? BUTTON_HOVER : BUTTON_BG);
		g.centeredText(font, fit(label, w - 8), x + w / 2, y + (h - font.lineHeight) / 2,
			disabled ? TEXT_MUTED : hover ? TEXT_ON_ACCENT : TEXT_PRIMARY);
	}
	private String fit(String value, int width) { return font.width(value) <= width ? value : font.plainSubstrByWidth(value, Math.max(1, width - font.width("…"))) + "…"; }
	private boolean inside(int x, int y, int bx, int by, int bw, int bh) { return x >= bx && x < bx + bw && y >= by && y < by + bh; }
	private Rect profileViewport(Layout l) { return ConfigScreenGeometry.profileViewport(rect(l)); }
	private Rect previewViewport(Layout l) { return ConfigScreenGeometry.previewViewport(rect(l)); }
	private Toolbar toolbar(Layout l) {
		if (ConfigScreenGeometry.compactProfilePanel(rect(l))) {
			int inner = Math.max(1, l.w - 24);
			int refreshWidth = l.w < 280 ? 56 : 64;
			int exportWidth = l.w < 280 ? 78 : 104;
			int fieldWidth = Math.max(1, inner - refreshWidth - exportWidth - 8);
			int y = l.y + 23;
			Rect name = new Rect(l.x + 12, y, fieldWidth, 20);
			Rect export = new Rect(name.right() + 4, y, exportWidth, 20);
			Rect refresh = new Rect(export.right() + 4, y, refreshWidth, 20);
			return new Toolbar(name, export, refresh);
		}
		int fieldWidth = Math.max(60, Math.min(l.w - 260, l.w - 152));
		return new Toolbar(new Rect(l.x + 12, l.y + 43, fieldWidth, 20),
			new Rect(l.x + l.w - 132, l.y + 43, 120, 20), new Rect(l.x + l.w - 86, l.y + 70, 74, 18));
	}
	private Rect rect(Layout l) { return new Rect(l.x, l.y, l.w, l.h); }
	private Layout layout() { Rect panel = ConfigScreenGeometry.panel(width, height, 680, 470); return new Layout(panel.x(), panel.y(), panel.width(), panel.height()); }
	private static String formatBytes(long bytes) { return bytes < 1024 * 1024 ? (bytes / 1024) + " KiB" : String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0)); }
	private record Layout(int x, int y, int w, int h) { }
	private record Toolbar(Rect name, Rect export, Rect refresh) { }
}
