package geiler.addons.client.gui;

import geiler.addons.client.dungeon.DungeonGuideStore;
import geiler.addons.client.dungeon.DungeonGuideSegments;
import geiler.addons.client.dungeon.DungeonFloor;
import geiler.addons.client.dungeon.DungeonContextTracker;
import geiler.addons.client.module.impl.VisualModule;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

import static geiler.addons.client.gui.GuiTheme.*;

/** Lists named guide routes so the player can select one for its floor and phase. */
public final class DungeonRoutePickerScreen extends Screen {
	private final Screen parent;
	private String notice = "";
	private int scroll;
	private DungeonFloor draftFloor;
	private int draftPhaseIndex;
	private DungeonGuideStore.TransitionTiming draftTiming = DungeonGuideStore.TransitionTiming.NEXT_OBJECTIVE;
	private EditBox routeName;

	public DungeonRoutePickerScreen(Screen parent) {
		super(Component.literal("Dungeon Guide Routes"));
		this.parent = parent;
		this.draftFloor = DungeonContextTracker.currentFloor();
	}

	@Override public boolean isPauseScreen() { return false; }
	@Override protected void init() {
		Layout l = layout();
		routeName = addRenderableWidget(new EditBox(font, l.x + 10, l.y + 71,
			routeNameWidth(l), 18, Component.literal("New route name")));
		routeName.setMaxLength(48);
		routeName.setHint(Component.literal("New route name"));
	}

	@Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		VisualModule.INSTANCE.refreshTheme();
		graphics.fill(0, 0, width, height, DIALOG_SHADE);
		Layout l = layout();
		roundedRectBordered(graphics, l.x, l.y, l.w, l.h, RADIUS, PANEL_TOP, PANEL_BOTTOM, BORDER);
		graphics.centeredText(font, "Dungeon Guide Routes", l.x + l.w / 2, l.y + 12, TEXT_PRIMARY);
		graphics.centeredText(font, fit("Choose a route, create one, or right-click to change its timing", l.w - 16),
			l.x + l.w / 2, l.y + 27, TEXT_MUTED);
		int selectorWidth = selectorWidth(l);
		button(graphics, l.x + 10, l.y + 46, selectorWidth, 20,
			"Floor: " + (draftFloor == null ? "Unknown" : draftFloor.displayName()), mouseX, mouseY);
		button(graphics, l.x + 15 + selectorWidth, l.y + 46, selectorWidth, 20,
			"Stage: " + fit(currentPhase(), selectorWidth - 12), mouseX, mouseY);
		button(graphics, l.x + 20 + selectorWidth * 2, l.y + 46, selectorWidth, 20,
			draftTiming == DungeonGuideStore.TransitionTiming.NEXT_OBJECTIVE ? "Advance: next" : "Advance: event", mouseX, mouseY);
		int createWidth = createButtonWidth(l);
		button(graphics, createButtonX(l), l.y + 71, createWidth, 18, "Create Route", mouseX, mouseY);
		List<DungeonGuideStore.Route> routes = DungeonGuideStore.routes();
		int top = l.y + 101, rowHeight = 25, bottom = l.y + l.h - 34;
		int visible = Math.max(0, (bottom - top) / rowHeight);
		scroll = Math.max(0, Math.min(scroll, Math.max(0, routes.size() - visible)));
		graphics.enableScissor(l.x + 8, top, l.x + l.w - 8, bottom);
		if (routes.isEmpty()) graphics.text(font, "Create a route in the guide editor first", l.x + 14, top + 6, TEXT_MUTED);
		for (int i = scroll; i < Math.min(routes.size(), scroll + visible); i++) {
			DungeonGuideStore.Route route = routes.get(i);
			int y = top + (i - scroll) * rowHeight;
			boolean selected = route.name().equals(DungeonGuideStore.activeRoute(route.floor(), route.phase()));
			boolean hovered = mouseX >= l.x + 8 && mouseX < l.x + l.w - 8 && mouseY >= y && mouseY < y + rowHeight;
			if (selected || hovered) roundedRect(graphics, l.x + 8, y, l.w - 16, rowHeight - 2, RADIUS_SMALL,
				selected ? CATEGORY_SELECTED : CATEGORY_HOVER);
			String label = route.floor() + " · " + route.phase() + " · " + route.name() + " · "
				+ (DungeonGuideStore.routeTiming(route.floor(), route.phase(), route.name())
					== DungeonGuideStore.TransitionTiming.NEXT_OBJECTIVE ? "next objective" : "event stage");
			graphics.text(font, fit(label, l.w - 28), l.x + 14, y + 7, TEXT_PRIMARY);
		}
		graphics.disableScissor();
		button(graphics, l.x + l.w - 68, l.y + l.h - 27, 60, 19, "Done", mouseX, mouseY);
		if (!notice.isBlank()) graphics.text(font, fit(notice, Math.max(1, l.w - 84)), l.x + 10, l.y + l.h - 14, TEXT_MUTED);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	@Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) return true;
		if (event.button() != 0 && event.button() != 1) return false;
		Layout l = layout();
		int selectorWidth = selectorWidth(l);
		if (inside(event.x(), event.y(), l.x + 10, l.y + 46, selectorWidth, 20)) {
			DungeonFloor[] floors = DungeonFloor.values();
			draftFloor = draftFloor == null ? floors[0] : floors[(draftFloor.ordinal() + 1) % floors.length];
			draftPhaseIndex = 0;
			return true;
		}
		if (inside(event.x(), event.y(), l.x + 15 + selectorWidth, l.y + 46, selectorWidth, 20)) {
			List<String> phases = phaseChoices();
			if (!phases.isEmpty()) draftPhaseIndex = (draftPhaseIndex + 1) % phases.size();
			return true;
		}
		if (inside(event.x(), event.y(), l.x + 20 + selectorWidth * 2, l.y + 46, selectorWidth, 20)) {
			draftTiming = draftTiming == DungeonGuideStore.TransitionTiming.NEXT_OBJECTIVE
				? DungeonGuideStore.TransitionTiming.EVENT_SEGMENT : DungeonGuideStore.TransitionTiming.NEXT_OBJECTIVE;
			return true;
		}
		if (inside(event.x(), event.y(), createButtonX(l), l.y + 71, createButtonWidth(l), 18)) {
			if (draftFloor == null) {
				notice = "Choose a floor before creating a route";
				return true;
			}
			String name = routeName.getValue().trim();
			notice = DungeonGuideStore.createRoute(draftFloor.displayName(), currentPhase(), name, draftTiming)
				? "Created " + name + " · select it below" : "Enter a unique name for this floor and stage";
			return true;
		}
		if (inside(event.x(), event.y(), l.x + l.w - 68, l.y + l.h - 27, 60, 19)) { onClose(); return true; }
		int top = l.y + 101, rowHeight = 25, bottom = l.y + l.h - 34;
		if (event.x() < l.x + 8 || event.x() >= l.x + l.w - 8 || event.y() < top || event.y() >= bottom) return false;
		int index = (int) ((event.y() - top) / rowHeight) + scroll;
		List<DungeonGuideStore.Route> routes = DungeonGuideStore.routes();
		if (index < 0 || index >= routes.size()) return false;
		DungeonGuideStore.Route route = routes.get(index);
		if (event.button() == 1) {
			DungeonGuideStore.TransitionTiming old = DungeonGuideStore.routeTiming(route.floor(), route.phase(), route.name());
			DungeonGuideStore.TransitionTiming next = old == DungeonGuideStore.TransitionTiming.NEXT_OBJECTIVE
				? DungeonGuideStore.TransitionTiming.EVENT_SEGMENT : DungeonGuideStore.TransitionTiming.NEXT_OBJECTIVE;
			DungeonGuideStore.setRouteTiming(route.floor(), route.phase(), route.name(), next);
			notice = "Route timing: " + (next == DungeonGuideStore.TransitionTiming.NEXT_OBJECTIVE ? "next objective" : "event stage");
		} else {
			DungeonGuideStore.setActiveRoute(route.floor(), route.phase(), route.name());
			notice = "Selected " + route.floor() + " · " + route.phase() + " · " + route.name();
		}
		return true;
	}

	@Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		Layout l = layout();
		if (mouseX >= l.x && mouseX < l.x + l.w && mouseY >= l.y + 101 && mouseY < l.y + l.h - 34) {
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
	private List<String> phaseChoices() {
		List<String> result = new java.util.ArrayList<>();
		if (draftFloor == null) return result;
		for (DungeonGuideSegments.Segment segment : DungeonGuideSegments.builtIn(draftFloor)) result.add(segment.id());
		for (var segment : DungeonGuideStore.customSegments(draftFloor)) result.add(segment.id);
		return result;
	}
	private String currentPhase() {
		List<String> choices = phaseChoices();
		return choices.isEmpty() ? "Choose floor" : choices.get(Math.min(draftPhaseIndex, choices.size() - 1));
	}

	private int selectorWidth(Layout layout) { return Math.max(1, (layout.w - 40) / 3); }
	private int createButtonWidth(Layout layout) { return Math.max(1, Math.min(108, (layout.w - 26) / 2)); }
	private int createButtonX(Layout layout) { return layout.x + layout.w - 10 - createButtonWidth(layout); }
	private int routeNameWidth(Layout layout) { return Math.max(1, layout.w - 20 - createButtonWidth(layout) - 6); }

	private void button(GuiGraphicsExtractor graphics, int x, int y, int w, int h, String text, int mouseX, int mouseY) {
		boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		roundedRect(graphics, x, y, w, h, RADIUS_SMALL, hover ? CARD_BG_HOVER : CARD_BG);
		graphics.centeredText(font, fit(text, w - 8), x + w / 2, y + (h - font.lineHeight) / 2, TEXT_PRIMARY);
	}
	private String fit(String value, int maxWidth) {
		if (maxWidth <= 0) return "";
		return font.width(value) <= maxWidth ? value
			: font.plainSubstrByWidth(value, Math.max(1, maxWidth - font.width("…"))) + "…";
	}
	private boolean inside(double x, double y, int bx, int by, int bw, int bh) { return x >= bx && x < bx + bw && y >= by && y < by + bh; }
	private Layout layout() { int w = Math.max(1, Math.min(650, width - 16)); int h = Math.max(1, Math.min(height - 16, 450)); int x = (width - w) / 2, y = (height - h) / 2; return new Layout(x, y, w, h); }
	private record Layout(int x, int y, int w, int h) { }
}
