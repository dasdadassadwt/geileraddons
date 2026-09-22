package geiler.addons.client.gui;

import geiler.addons.client.collections.FolderTree;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import geiler.addons.client.module.impl.VisualModule;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static geiler.addons.client.gui.GuiTheme.*;

/** Shared CRUD UI for a feature's independent folder tree and entries. */
public final class FolderManagerScreen extends Screen {
	private static final int ROW_HEIGHT = 19;
	private final Screen parent;
	private final String title;
	private final FolderTree tree;
	private final Supplier<List<Entry>> entries;
	private final BiConsumer<String, String> moveEntry;
	private final Runnable changed;
	private String selectedFolder;
	private String selectedEntry;
	private Popup popup;
	private Dialog dialog;
	private String dialogText = "";
	private int folderScroll;
	private int entryScroll;
	private Layout layout;

	public FolderManagerScreen(Screen parent, String title, FolderTree tree, Supplier<List<Entry>> entries,
		BiConsumer<String, String> moveEntry, Runnable changed) {
		super(Component.literal("Folders"));
		this.parent = parent;
		this.title = title;
		this.tree = tree;
		this.entries = entries;
		this.moveEntry = moveEntry;
		this.changed = changed;
	}

	@Override
	public boolean isPauseScreen() { return false; }

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		VisualModule.INSTANCE.refreshTheme();
		graphics.fill(0, 0, width, height, DIALOG_SHADE);
		layout = layout();
		Rect panel = layout.panel;
		roundedRectBordered(graphics, panel.x, panel.y, panel.w, panel.h, RADIUS,
			PANEL_TOP, PANEL_BOTTOM, BORDER);
		graphics.text(font, title + " Folders", panel.x + 12, panel.y + 10, TEXT_PRIMARY);
		graphics.text(font, "Folders are independent for this feature. Deleting one promotes its contents.",
			panel.x + 12, panel.y + 24, TEXT_MUTED);
		graphics.fill(panel.x + 10, panel.y + 43, panel.x + panel.w - 10, panel.y + 44, BORDER);
		renderFolders(graphics, mouseX, mouseY);
		renderEntries(graphics, mouseX, mouseY);
		renderActions(graphics, mouseX, mouseY);
		if (popup != null) renderPopup(graphics, mouseX, mouseY);
		if (dialog != null) renderDialog(graphics, mouseX, mouseY);
	}

	private void renderFolders(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		Rect pane = layout.folders;
		pane(graphics, pane, "Folders", "Click to select a parent or folder.");
		List<Row> rows = new ArrayList<>();
		for (FolderTree.Folder root : tree.childrenOf(null)) appendFolderRows(root, 0, rows);
		int top = pane.y + 39;
		Rect view = new Rect(pane.x + 4, top, pane.w - 8, Math.max(10, pane.h - 44));
		folderScroll = clamp(folderScroll, 0, Math.max(0, rows.size() * ROW_HEIGHT - view.h));
		graphics.enableScissor(view.x, view.y, view.x + view.w, view.y + view.h);
		row(graphics, mouseX, mouseY, new Row(null, "Root", 0), view, 0, Objects.equals(selectedFolder, null));
		for (int i = 0; i < rows.size(); i++) row(graphics, mouseX, mouseY, rows.get(i), view,
			(i + 1) * ROW_HEIGHT - folderScroll, Objects.equals(selectedFolder, rows.get(i).id));
		graphics.disableScissor();
	}

	private void appendFolderRows(FolderTree.Folder folder, int depth, List<Row> rows) {
		rows.add(new Row(folder.id(), folder.name(), depth));
		for (FolderTree.Folder child : tree.childrenOf(folder.id())) appendFolderRows(child, depth + 1, rows);
	}

	private void row(GuiGraphicsExtractor graphics, int mouseX, int mouseY, Row row, Rect view,
		int offset, boolean selected) {
		int y = view.y + offset;
		Rect bounds = new Rect(view.x, y, view.w - 4, ROW_HEIGHT - 1);
		boolean hovered = bounds.contains(mouseX, mouseY) && view.contains(mouseX, mouseY);
		if (selected || hovered) roundedRect(graphics, bounds.x, bounds.y, bounds.w, bounds.h,
			RADIUS_SMALL, selected ? CATEGORY_SELECTED : CATEGORY_HOVER);
		graphics.text(font, trim(font, row.label, bounds.w - 12 - row.depth * 10),
			bounds.x + 6 + row.depth * 10, bounds.y + 5, selected ? TEXT_ON_ACCENT : TEXT_SECONDARY);
	}

	private void renderEntries(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		Rect pane = layout.entries;
		String folderName = selectedFolder == null ? "Root" : name(selectedFolder);
		pane(graphics, pane, "Entries in " + folderName, "Select an entry to move it.");
		List<Entry> visible = entries.get().stream().filter(entry -> Objects.equals(entry.folderId(), selectedFolder)).toList();
		Rect view = new Rect(pane.x + 4, pane.y + 39, pane.w - 8, Math.max(10, pane.h - 44));
		entryScroll = clamp(entryScroll, 0, Math.max(0, visible.size() * ROW_HEIGHT - view.h));
		graphics.enableScissor(view.x, view.y, view.x + view.w, view.y + view.h);
		if (visible.isEmpty()) graphics.text(font, "No entries in this folder.", view.x + 6, view.y + 6, TEXT_MUTED);
		for (int i = 0; i < visible.size(); i++) {
			Entry entry = visible.get(i);
			int y = view.y + i * ROW_HEIGHT - entryScroll;
			Rect bounds = new Rect(view.x, y, view.w - 4, ROW_HEIGHT - 1);
			boolean hovered = bounds.contains(mouseX, mouseY) && view.contains(mouseX, mouseY);
			boolean selected = Objects.equals(selectedEntry, entry.id());
			if (selected || hovered) roundedRect(graphics, bounds.x, bounds.y, bounds.w, bounds.h,
				RADIUS_SMALL, selected ? CATEGORY_SELECTED : CATEGORY_HOVER);
			graphics.text(font, trim(font, entry.name(), bounds.w - 12), bounds.x + 6, bounds.y + 5,
				selected ? TEXT_ON_ACCENT : TEXT_SECONDARY);
		}
		graphics.disableScissor();
	}

	private void renderActions(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int y = layout.panel.y + layout.panel.h - 29;
		int x = layout.panel.x + 10;
		int gap = 4;
		int[] widths = actionWidths();
		String[] labels = widths[0] < 50
			? new String[]{"New", "Rename", "Parent", "Delete", "Move", "Done"}
			: new String[]{"New Folder", "Rename", "Move Folder", "Delete", "Move Entry", "Done"};
		for (int i = 0; i < labels.length; i++) {
			Rect button = new Rect(x, y, widths[i], 20);
			boolean enabled = switch (i) {
				case 1, 2, 3 -> selectedFolder != null;
				case 4 -> selectedEntry != null;
				default -> true;
			};
			button(graphics, labels[i], button, mouseX, mouseY, enabled);
			x += widths[i] + gap;
		}
	}

	private void renderPopup(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		Popup active = popup;
		Rect bounds = active.bounds(layout);
		roundedRectBordered(graphics, bounds.x, bounds.y, bounds.w, bounds.h, RADIUS_SMALL,
			MODULE_PANEL_TOP, MODULE_PANEL_BOTTOM, BORDER);
		List<Target> targets = active.targets;
		Rect view = new Rect(bounds.x + 4, bounds.y + 4, bounds.w - 8, bounds.h - 8);
		int visibleRows = Math.max(1, view.h / ROW_HEIGHT);
		active.scroll = clamp(active.scroll, 0, Math.max(0, targets.size() - visibleRows));
		graphics.enableScissor(view.x, view.y, view.x + view.w, view.y + view.h);
		for (int i = active.scroll; i < Math.min(targets.size(), active.scroll + visibleRows); i++) {
			Target target = targets.get(i);
			int y = view.y + (i - active.scroll) * ROW_HEIGHT;
			Rect row = new Rect(view.x, y, view.w, ROW_HEIGHT);
			boolean hover = row.contains(mouseX, mouseY);
			if (hover) roundedRect(graphics, row.x, row.y, row.w, row.h, RADIUS_SMALL, BUTTON_HOVER);
			graphics.text(font, trim(font, target.name, row.w - 10), row.x + 5, row.y + 5,
				hover ? TEXT_ON_ACCENT : TEXT_PRIMARY);
		}
		graphics.disableScissor();
	}

	private void renderDialog(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		graphics.fill(0, 0, width, height, 0x90000000);
		Rect box = layout.dialog;
		roundedRectBordered(graphics, box.x, box.y, box.w, box.h, RADIUS,
			MODULE_PANEL_TOP, MODULE_PANEL_BOTTOM, BORDER);
		graphics.centeredText(font, dialog == Dialog.CREATE ? "Create Folder" : "Rename Folder",
			box.x + box.w / 2, box.y + 10, TEXT_PRIMARY);
		Rect input = new Rect(box.x + 12, box.y + 34, box.w - 24, 20);
		roundedRectBordered(graphics, input.x, input.y, input.w, input.h, RADIUS_SMALL,
			CARD_BG, CARD_BG, SLIDER_FILL);
		graphics.text(font, trim(font, dialogText.isEmpty() ? "Folder name…" : dialogText, input.w - 12),
			input.x + 6, input.y + 6, dialogText.isEmpty() ? TEXT_MUTED : TEXT_PRIMARY);
		Rect save = new Rect(box.x + 12, box.y + 62, (box.w - 30) / 2, 19);
		Rect cancel = new Rect(save.x + save.w + 6, save.y, save.w, save.h);
		button(graphics, "Save", save, mouseX, mouseY, !dialogText.isBlank());
		button(graphics, "Cancel", cancel, mouseX, mouseY, true);
	}

	private void pane(GuiGraphicsExtractor graphics, Rect pane, String heading, String hint) {
		roundedRectBordered(graphics, pane.x, pane.y, pane.w, pane.h, RADIUS_SMALL,
			MODULE_PANEL_TOP, MODULE_PANEL_BOTTOM, BORDER);
		graphics.text(font, trim(font, heading, pane.w - 12), pane.x + 6, pane.y + 6, TEXT_PRIMARY);
		graphics.text(font, trim(font, hint, pane.w - 12), pane.x + 6, pane.y + 19, TEXT_MUTED);
	}

	private void button(GuiGraphicsExtractor graphics, String label, Rect bounds, int mouseX, int mouseY, boolean enabled) {
		boolean hover = enabled && bounds.contains(mouseX, mouseY);
		roundedRect(graphics, bounds.x, bounds.y, bounds.w, bounds.h, RADIUS_SMALL,
			enabled ? (hover ? BUTTON_HOVER : BUTTON_BG) : SLIDER_TRACK);
		graphics.centeredText(font, label, bounds.x + bounds.w / 2,
			bounds.y + (bounds.h - 8) / 2, enabled ? TEXT_PRIMARY : TEXT_MUTED);
	}

	private Layout layout() {
		int w = Math.max(220, Math.min(720, width - 20));
		int h = Math.max(150, Math.min(430, height - 20));
		int x = (width - w) / 2;
		int y = (height - h) / 2;
		int contentY = y + 48;
		int contentH = Math.max(36, h - 86);
		int gap = 6;
		int innerW = w - 20;
		int folderW = innerW / 2;
		return new Layout(new Rect(x, y, w, h), new Rect(x + 10, contentY, folderW, contentH),
			new Rect(x + 10 + folderW + gap, contentY, innerW - folderW - gap, contentH),
			new Rect(x + (w - 220) / 2, y + (h - 92) / 2, 220, 92));
	}

	private int[] actionWidths() {
		int available = layout.panel.w - 20 - 5 * 4;
		int each = available / 6;
		int[] result = {each, each, each, each, each, available - each * 5};
		return result;
	}

	private String name(String id) {
		FolderTree.Folder folder = tree.folder(id);
		return folder == null ? "Root" : folder.name();
	}

	private List<Target> parentTargets(String movingId) {
		List<Target> result = new ArrayList<>();
		result.add(new Target(null, "Root"));
		for (FolderTree.Folder folder : tree.folders()) {
			if (!folder.id().equals(movingId) && !tree.isDescendant(folder.id(), movingId)) {
				result.add(new Target(folder.id(), folderPath(folder.id())));
			}
		}
		return result;
	}

	private String folderPath(String id) {
		List<String> names = new ArrayList<>();
		FolderTree.Folder current = tree.folder(id);
		while (current != null) {
			names.add(0, current.name());
			current = tree.folder(current.parentId());
		}
		return String.join(" / ", names);
	}

	private void beginDialog(Dialog mode) {
		dialog = mode;
		dialogText = mode == Dialog.RENAME && selectedFolder != null ? name(selectedFolder) : "";
		popup = null;
	}

	private void saveDialog() {
		if (dialog == null || dialogText.isBlank()) return;
		if (dialog == Dialog.CREATE) tree.create(dialogText, selectedFolder);
		else tree.rename(selectedFolder, dialogText);
		dialog = null;
		changed.run();
	}

	private void activateAction(int index) {
		switch (index) {
			case 0 -> beginDialog(Dialog.CREATE);
			case 1 -> beginDialog(Dialog.RENAME);
			case 2 -> popup = new Popup(PopupAction.MOVE_FOLDER, parentTargets(selectedFolder), 0);
			case 3 -> {
				String parentId = tree.delete(selectedFolder);
				for (Entry entry : entries.get()) if (selectedFolder.equals(entry.folderId())) moveEntry.accept(entry.id(), parentId);
				selectedFolder = parentId;
				selectedEntry = null;
				changed.run();
			}
			case 4 -> {
				List<Target> targets = parentTargets(null);
				popup = new Popup(PopupAction.MOVE_ENTRY, targets, 0);
			}
			case 5 -> onClose();
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != 0) return true;
		int x = (int) event.x();
		int y = (int) event.y();
		if (dialog != null) {
			Rect box = layout.dialog;
			Rect save = new Rect(box.x + 12, box.y + 62, (box.w - 30) / 2, 19);
			Rect cancel = new Rect(save.x + save.w + 6, save.y, save.w, save.h);
			if (save.contains(x, y)) saveDialog();
			else if (cancel.contains(x, y)) dialog = null;
			return true;
		}
		if (popup != null) {
			Rect bounds = popup.bounds(layout);
			Rect view = new Rect(bounds.x + 4, bounds.y + 4, bounds.w - 8, bounds.h - 8);
			if (!bounds.contains(x, y)) { popup = null; return true; }
			int row = (y - view.y) / ROW_HEIGHT + popup.scroll;
			if (row < 0 || row >= popup.targets.size()) return true;
			Target target = popup.targets.get(row);
			if (popup.action == PopupAction.MOVE_FOLDER) tree.move(selectedFolder, target.id);
			else if (selectedEntry != null) moveEntry.accept(selectedEntry, target.id);
			popup = null;
			changed.run();
			return true;
		}
		Rect buttons = new Rect(layout.panel.x + 10, layout.panel.y + layout.panel.h - 29,
			layout.panel.w - 20, 20);
		if (buttons.contains(x, y)) {
			int cursor = buttons.x;
			int[] widths = actionWidths();
			for (int i = 0; i < widths.length; i++) {
				Rect button = new Rect(cursor, buttons.y, widths[i], buttons.h);
				if (button.contains(x, y)) {
					boolean enabled = switch (i) { case 1, 2, 3 -> selectedFolder != null; case 4 -> selectedEntry != null; default -> true; };
					if (enabled) activateAction(i);
					return true;
				}
				cursor += widths[i] + 4;
			}
			return true;
		}
		if (layout.folders.contains(x, y)) {
			List<Row> rows = new ArrayList<>();
			for (FolderTree.Folder root : tree.childrenOf(null)) appendFolderRows(root, 0, rows);
			Rect view = new Rect(layout.folders.x + 4, layout.folders.y + 39, layout.folders.w - 8,
				Math.max(10, layout.folders.h - 44));
			if (view.contains(x, y)) {
				int row = (y - view.y + folderScroll) / ROW_HEIGHT;
				if (row == 0) { selectedFolder = null; selectedEntry = null; return true; }
				if (row > 0 && row <= rows.size()) { selectedFolder = rows.get(row - 1).id; selectedEntry = null; return true; }
			}
			return true;
		}
		if (layout.entries.contains(x, y)) {
			List<Entry> visible = entries.get().stream().filter(entry -> Objects.equals(entry.folderId(), selectedFolder)).toList();
			Rect view = new Rect(layout.entries.x + 4, layout.entries.y + 39, layout.entries.w - 8,
				Math.max(10, layout.entries.h - 44));
			if (view.contains(x, y)) {
				int row = (y - view.y + entryScroll) / ROW_HEIGHT;
				if (row >= 0 && row < visible.size()) selectedEntry = visible.get(row).id();
			}
			return true;
		}
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (popup != null) {
			popup.scroll = Math.max(0, popup.scroll - (int) Math.signum(scrollY));
			return true;
		}
		if (layout != null && layout.folders.contains((int) mouseX, (int) mouseY)) folderScroll = Math.max(0, folderScroll - (int) Math.signum(scrollY) * 2);
		else if (layout != null && layout.entries.contains((int) mouseX, (int) mouseY)) entryScroll = Math.max(0, entryScroll - (int) Math.signum(scrollY) * 2);
		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (dialog == null || !event.isAllowedChatCharacter()) return true;
		if (dialogText.length() < 48) dialogText += event.codepointAsString();
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (dialog != null) {
			if (event.key() == InputConstants.KEY_ESCAPE) { dialog = null; return true; }
			if (event.key() == InputConstants.KEY_RETURN) { saveDialog(); return true; }
			if (event.key() == InputConstants.KEY_BACKSPACE && !dialogText.isEmpty()) {
				dialogText = dialogText.substring(0, dialogText.offsetByCodePoints(dialogText.length(), -1));
				return true;
			}
			if (event.key() == GLFW.GLFW_KEY_A && (event.modifiers() & InputConstants.MOD_CONTROL) != 0) {
				dialogText = "";
				return true;
			}
			return true;
		}
		if (event.key() == InputConstants.KEY_ESCAPE) { onClose(); return true; }
		return true;
	}

	@Override
	public void onClose() {
		if (minecraft != null) minecraft.setScreen(parent);
	}

	private static String trim(Font font, String value, int maxWidth) {
		if (value == null || maxWidth <= 0) return "";
		if (font.width(value) <= maxWidth) return value;
		String ellipsis = "…";
		int end = value.length();
		while (end > 0 && font.width(value.substring(0, end) + ellipsis) > maxWidth) end--;
		return end == 0 ? ellipsis : value.substring(0, end) + ellipsis;
	}

	private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

	private record Rect(int x, int y, int w, int h) {
		boolean contains(int px, int py) { return px >= x && py >= y && px < x + w && py < y + h; }
	}
	private record Layout(Rect panel, Rect folders, Rect entries, Rect dialog) { }
	private record Row(String id, String label, int depth) { }
	private record Target(String id, String name) { }
	public record Entry(String id, String name, String folderId) { }
	private enum Dialog { CREATE, RENAME }
	private enum PopupAction { MOVE_FOLDER, MOVE_ENTRY }
	private static final class Popup {
		final PopupAction action;
		final List<Target> targets;
		int scroll;
		Popup(PopupAction action, List<Target> targets, int scroll) { this.action = action; this.targets = targets; this.scroll = scroll; }
		Rect bounds(Layout layout) {
			int w = Math.min(260, layout.panel.w - 20);
			int h = Math.min(180, targets.size() * ROW_HEIGHT + 8);
			return new Rect(layout.panel.x + (layout.panel.w - w) / 2, layout.panel.y + 48, w, Math.max(40, h));
		}
	}
}
