package geiler.addons.client.gui;

import geiler.addons.client.dungeon.DungeonContextTracker;
import geiler.addons.client.dungeon.DungeonFloor;
import geiler.addons.client.dungeon.DungeonGuideCustomSegment;
import geiler.addons.client.dungeon.DungeonGuideSegments;
import geiler.addons.client.dungeon.DungeonGuideStore;
import geiler.addons.client.module.impl.DungeonHelperModule;
import geiler.addons.client.module.impl.VisualModule;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

import static geiler.addons.client.gui.GuiTheme.*;

/** Stage ordering, manual correction, and custom triggers for Dungeon Guide. */
public final class DungeonGuideStagesScreen extends Screen {
	private final Screen parent;
	private DungeonFloor floor;
	private String selectedId = "ENTRY";
	private String notice = "";
	private int scroll;
	private EditBox label;
	private EditBox event;
	private EditBox position;
	private EditBox radius;
	private EditBox seconds;

	public DungeonGuideStagesScreen(Screen parent) {
		super(Component.literal("Dungeon Guide Stages"));
		this.parent = parent;
		floor = DungeonContextTracker.currentFloor();
	}

	@Override public boolean isPauseScreen() { return false; }
	@Override protected void init() {
		DungeonGuideCustomSegment selected = selectedCustom();
		if (selected == null) return;
		Layout l = layout();
		int x = l.right + 8, w = Math.max(1, l.w - l.leftWidth - 20);
		label = field(x, l.y + 96, w, selected.label, value -> { selected.label = value; DungeonGuideStore.customSegmentChanged(); });
		event = field(x, l.y + 132, w, selected.eventText, value -> { selected.eventText = value; DungeonGuideStore.customSegmentChanged(); });
		position = field(x, l.y + 168, w, selected.x + "," + selected.y + "," + selected.z, value -> {
			String[] parts = value.split(",", -1);
			if (parts.length != 3) return;
			try {
				selected.x = Integer.parseInt(parts[0].trim()); selected.y = Integer.parseInt(parts[1].trim());
				selected.z = Integer.parseInt(parts[2].trim()); DungeonGuideStore.customSegmentChanged();
			} catch (NumberFormatException ignored) { }
		});
		radius = field(x, l.y + 204, w, String.valueOf(selected.radius), value -> {
			try { selected.radius = Float.parseFloat(value); DungeonGuideStore.customSegmentChanged(); }
			catch (NumberFormatException ignored) { }
		});
		seconds = field(x, l.y + 240, w, String.valueOf(selected.seconds), value -> {
			try { selected.seconds = Float.parseFloat(value); DungeonGuideStore.customSegmentChanged(); }
			catch (NumberFormatException ignored) { }
		});
	}

	private EditBox field(int x, int y, int w, String value, java.util.function.Consumer<String> responder) {
		EditBox box = addRenderableWidget(new EditBox(font, x, y, w, 18, Component.literal("Stage setting")));
		box.setMaxLength(128);
		box.setValue(value);
		box.setResponder(responder);
		return box;
	}

	@Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		VisualModule.INSTANCE.refreshTheme();
		graphics.fill(0, 0, width, height, DIALOG_SHADE);
		Layout l = layout();
		roundedRectBordered(graphics, l.x, l.y, l.w, l.h, RADIUS, PANEL_TOP, PANEL_BOTTOM, BORDER);
		graphics.text(font, fit("Dungeon Guide · Stages", l.w - 24), l.x + 12, l.y + 11, TEXT_PRIMARY);
		graphics.text(font, fit("Auto detection: " + DungeonHelperModule.INSTANCE.phaseEvidence(), l.w - 24),
			l.x + 12, l.y + 26, TEXT_MUTED);
		button(graphics, l.x + 8, l.y + 44, l.leftWidth - 12, 19,
			"Floor: " + (floor == null ? "Unknown" : floor.displayName()), mouseX, mouseY);
		graphics.fill(l.right, l.y + 40, l.right + 1, l.y + l.h - 54, BORDER);
		List<Stage> stages = stages();
		int rowTop = l.y + 70, rowHeight = 20;
		int visible = Math.max(1, (l.h - 125) / rowHeight);
		scroll = Math.max(0, Math.min(scroll, Math.max(0, stages.size() - visible)));
		graphics.enableScissor(l.x + 8, rowTop, l.right - 4, l.y + l.h - 55);
		if (floor == null) graphics.text(font, fit("Choose a floor above to view its stages.", l.leftWidth - 20),
			l.x + 12, rowTop + 6, TEXT_MUTED);
		for (int i = scroll; i < stages.size(); i++) {
			int y = rowTop + (i - scroll) * rowHeight;
			if (y >= l.y + l.h - 55) break;
			Stage stage = stages.get(i);
			if (stage.id.equals(selectedId)) roundedRect(graphics, l.x + 8, y, l.leftWidth - 12, 18, 3, CATEGORY_SELECTED);
			graphics.text(font, fit(stage.label, l.leftWidth - 24), l.x + 14, y + 5, TEXT_PRIMARY);
		}
		graphics.disableScissor();
		DungeonGuideCustomSegment custom = selectedCustom();
		graphics.text(font, custom == null ? "Built-in stage" : "Custom stage", l.right + 8, l.y + 49, TEXT_PRIMARY);
		graphics.text(font, fit(selectedId, l.w - l.leftWidth - 22), l.right + 8, l.y + 67, TEXT_MUTED);
		if (custom != null) {
			String[] names = {"Stage name", "Exact chat event", "Location · x,y,z", "Location radius", "Timer · seconds"};
			for (int i = 0; i < names.length; i++) graphics.text(font, names[i], l.right + 8, l.y + 82 + i * 36, TEXT_MUTED);
			graphics.text(font, "Trigger: " + custom.trigger + " · after built-in #" + custom.afterBuiltInIndex,
				l.right + 8, l.y + 270, TEXT_SECONDARY);
		}
		String[] buttons = {"Add Custom", "Use Stage", "Auto", "Trigger", "Earlier", "Later", "Remove", "Done"};
		int bw = Math.max(1, (l.w - 20) / 4);
		int top = l.y + l.h - 48;
		for (int i = 0; i < buttons.length; i++) button(graphics, l.x + 5 + (i % 4) * (bw + 3), top + (i / 4) * 21, bw, 19, buttons[i], mouseX, mouseY);
		if (!notice.isBlank()) graphics.text(font, fit(notice, l.w - 20), l.x + 10, l.y + l.h - 61, TEXT_MUTED);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	@Override public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick) {
		if (super.mouseClicked(click, doubleClick)) return true;
		if (click.button() != 0) return false;
		Layout l = layout();
		if (inside(click, l.x + 8, l.y + 44, l.leftWidth - 12, 19)) {
			DungeonFloor[] floors = DungeonFloor.values();
			floor = floor == null ? floors[0] : floors[(floor.ordinal() + 1) % floors.length];
			selectedId = "ENTRY"; scroll = 0; rebuildWidgets(); return true;
		}
		int rowTop = l.y + 70;
		if (click.x() >= l.x + 8 && click.x() < l.right - 4 && click.y() >= rowTop && click.y() < l.y + l.h - 55) {
			int row = (int) ((click.y() - rowTop) / 20) + scroll;
			List<Stage> stages = stages();
			if (row >= 0 && row < stages.size()) { selectedId = stages.get(row).id; rebuildWidgets(); }
			return true;
		}
		int bw = Math.max(1, (l.w - 20) / 4), top = l.y + l.h - 48;
		for (int i = 0; i < 8; i++) {
			if (!inside(click, l.x + 5 + (i % 4) * (bw + 3), top + (i / 4) * 21, bw, 19)) continue;
			DungeonGuideCustomSegment selected = selectedCustom();
			switch (i) {
				case 0 -> {
					if (floor == null) notice = "Choose a floor before adding a custom stage";
					else {
						DungeonGuideCustomSegment fresh = new DungeonGuideCustomSegment(); fresh.floor = floor.displayName();
						fresh.afterBuiltInIndex = 1; DungeonGuideStore.addCustomSegment(fresh); selectedId = fresh.id; rebuildWidgets();
					}
				}
				case 1 -> notice = floor == null ? "Choose a floor before selecting a stage"
					: DungeonHelperModule.INSTANCE.setPhase(selectedId)
						? "Manual stage: " + selectedId : "Enter this floor before choosing its stage";
				case 2 -> { DungeonHelperModule.INSTANCE.resumeAutomaticPhase(); notice = "Automatic stage detection resumed"; }
				case 3 -> { if (selected != null) { var triggers = DungeonGuideCustomSegment.Trigger.values();
					selected.trigger = triggers[(selected.trigger.ordinal() + 1) % triggers.length]; DungeonGuideStore.customSegmentChanged(); } }
				case 4 -> { if (selected != null) DungeonGuideStore.moveCustomSegment(selected.id, -1); }
				case 5 -> { if (selected != null) DungeonGuideStore.moveCustomSegment(selected.id, 1); }
				case 6 -> { if (selected != null) { boolean removed = DungeonGuideStore.removeCustomSegment(selected.id);
					notice = removed ? "Stage removed" : "Remove its guide steps first"; if (removed) { selectedId = "ENTRY"; rebuildWidgets(); } } }
				case 7 -> onClose();
			}
			return true;
		}
		return false;
	}

	@Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		Layout l = layout();
		if (mouseX >= l.x + 8 && mouseX < l.right - 4 && mouseY >= l.y + 70 && mouseY < l.y + l.h - 55) {
			scroll = Math.max(0, scroll - (int) Math.round(scrollY * 3));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override public boolean keyPressed(KeyEvent event) {
		if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
		return super.keyPressed(event);
	}
	@Override public void onClose() { minecraft.setScreen(parent); }

	private DungeonGuideCustomSegment selectedCustom() {
		if (floor == null) return null;
		for (DungeonGuideCustomSegment segment : DungeonGuideStore.customSegments(floor)) if (segment.id.equals(selectedId)) return segment;
		return null;
	}
	private List<Stage> stages() {
		if (floor == null) return List.of();
		List<Stage> result = new ArrayList<>();
		List<DungeonGuideCustomSegment> custom = new ArrayList<>(DungeonGuideStore.customSegments(floor));
		custom.sort(java.util.Comparator.comparingInt(segment -> segment.afterBuiltInIndex));
		List<DungeonGuideSegments.Segment> builtIn = DungeonGuideSegments.builtIn(floor);
		for (int i = 0; i < builtIn.size(); i++) {
			var stage = builtIn.get(i);
			result.add(new Stage(stage.id(), stage.label()));
			for (DungeonGuideCustomSegment segment : custom) if (segment.afterBuiltInIndex == i)
				result.add(new Stage(segment.id, segment.label));
		}
		return result;
	}
	private String fit(String value, int max) { return font.width(value) <= max ? value : font.plainSubstrByWidth(value, Math.max(1, max - font.width("…"))) + "…"; }
	private boolean inside(MouseButtonEvent event, int x, int y, int w, int h) { return event.x() >= x && event.x() < x + w && event.y() >= y && event.y() < y + h; }
	private void button(GuiGraphicsExtractor graphics, int x, int y, int w, int h, String text, int mouseX, int mouseY) {
		boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		roundedRect(graphics, x, y, w, h, RADIUS_SMALL, hover ? CARD_BG_HOVER : CARD_BG);
		graphics.centeredText(font, fit(text, w - 8), x + w / 2, y + (h - font.lineHeight) / 2, TEXT_PRIMARY);
	}
	private Layout layout() { int w = Math.max(1, Math.min(740, width - 12)); int h = Math.max(1, Math.min(540, height - 12)); int x = (width - w) / 2, y = (height - h) / 2; int left = Math.max(130, Math.min(300, w * 42 / 100)); return new Layout(x, y, w, h, left, x + left); }
	private record Stage(String id, String label) { }
	private record Layout(int x, int y, int w, int h, int leftWidth, int right) { }
}
