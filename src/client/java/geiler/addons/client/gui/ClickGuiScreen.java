package geiler.addons.client.gui;

import geiler.addons.client.config.ClickGuiState;
import geiler.addons.client.config.ModConfig;
import geiler.addons.client.macro.MacroTriggerContext;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ChoiceSetting;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.DebugState;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.ModuleAction;
import geiler.addons.client.module.ModuleManager;
import geiler.addons.client.module.ModuleKeybindManager;
import geiler.addons.client.module.ModulePreview;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.module.TextSetting;
import geiler.addons.client.module.impl.VisualModule;
import geiler.addons.client.update.UpdateChecker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static geiler.addons.client.gui.GuiTheme.*;

public class ClickGuiScreen extends Screen {
	/** Share of the screen width the panel aims for, before the height cap below applies. */
	private static final float WIDTH_FRACTION = 0.62f;
	/** The panel never grows past this share of the screen height. */
	private static final float MAX_HEIGHT_FRACTION = 0.75f;
	private static final int MIN_PANEL_WIDTH = 240;
	/** Left panel's share of the total width; the module panel takes the rest. */
	private static final int CATEGORY_WIDTH_DIVISOR = 3;
	/** Reserved inside each panel for the profile/search strip above the old content. */
	private static final int HEADER_HEIGHT = 32;
	private static final int HEADER_ROW_HEIGHT = 20;
	private static final int PROFILE_FACE_SIZE = 18;
	private static final int HEADER_INSET = 6;
	private static final int SEARCH_MAX_LENGTH = 64;

	private static final int ROW_HEIGHT = 24;
	private static final int GROUP_HEADER_HEIGHT = 20;
	/** How far a nested section's heading steps in from the one it sits inside. */
	private static final int GROUP_INDENT = 10;
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
	private static final int PREVIEW_HEIGHT = 62;
	private static final int PREVIEW_GAP = 6;

	private static final int CARD_COLUMNS = 2;
	private static final int CARD_GAP = 6;
	private static final int CARD_INSET = 8;
	/** Descriptions longer than this are clipped rather than pushing every card taller. */
	private static final int CARD_MAX_DESC_LINES = 3;
	private static final int SWITCH_WIDTH = 20;
	private static final int SWITCH_HEIGHT = 11;
	private static final int KEYBIND_HEIGHT = 15;

	private static final int TEXT_FIELD_WIDTH = 64;
	private static final int TEXT_FIELD_HEIGHT = 13;
	private static final int NUMBER_FIELD_WIDTH = 64;
	private static final int CHOICE_FIELD_WIDTH = 92;
	private static final int CHOICE_FIELD_HEIGHT = 15;
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
	private NumberSetting focusedNumberSetting;
	private String numberInput = "";
	private boolean numberSelectAll;
	/** The colour whose hex field has focus, and what has been typed into it so far. */
	private ColorSetting focusedHexSetting;
	private String hexInput = "";
	private int settingsScroll;
	private int gridScroll;
	private double settingsScrollVisual;
	private double gridScrollVisual;

	/** The current target view is applied immediately; these snapshots let it slide in cleanly. */
	private ViewState transitionFrom;
	private ViewState transitionTo;
	private int transitionDirection;
	private long transitionStartedNanos;
	private final Deque<NavigationRequest> navigationQueue = new ArrayDeque<>();

	private final long openedAtNanos = System.nanoTime();
	private long lifecycleStartedNanos = openedAtNanos;
	private boolean closing;
	private Screen pendingScreen;
	private long lastFrameNanos = openedAtNanos;
	private String searchQuery = "";
	private boolean searchFocused;
	private boolean searchSelectAll;

	/** Last accepted control, used for a short tactile pulse without delaying the next action. */
	private Object lastInteraction;
	private long lastInteractionNanos;
	private final Map<Object, TogglePulse> togglePulses = new IdentityHashMap<>();
	/** Hover motion is edge-triggered so a cursor resting on a card does not make it vibrate. */
	private final Map<Object, Boolean> hoverStates = new IdentityHashMap<>();
	private final Map<Object, HoverPulse> hoverPulses = new IdentityHashMap<>();

	private ColorSetting expansionFrom;
	private ColorSetting expansionTo;
	private long expansionStartedNanos;
	private SettingsLayoutTransition settingsLayoutTransition;

	public ClickGuiScreen() {
		super(Component.literal("GeilerAddons"));
		selectedCategory = ClickGuiState.category();
		openSettingsModule = ClickGuiState.openModule();
		expandedColorSetting = ClickGuiState.expandedColor();
		settingsScroll = ClickGuiState.settingsScroll();
		settingsScrollVisual = settingsScroll;
		gridScrollVisual = gridScroll;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		beginClose(null);
	}

	/**
	 * Screen replacement belongs to the client tick, not render extraction. Calling setScreen from
	 * the extractor leaves the old screen registered for the final frame on 26.1 and can make the
	 * finished close animation appear frozen. The extractor simply stops drawing at the endpoint;
	 * this tick performs the one state transition afterwards.
	 */
	@Override
	public void tick() {
		super.tick();
		if (!closing) return;
		ClickGuiMotion motion = motion();
		if (elapsedMillis(lifecycleStartedNanos, System.nanoTime()) < motion.lifecycleMillis()) return;
		Screen next = pendingScreen;
		pendingScreen = null;
		closing = false;
		this.minecraft.setScreen(next);
	}

	@Override
	public void removed() {
		// Written once on the way out rather than on every scroll notch, which would mean a
		// config write per mouse-wheel click.
		persistView();
		ModuleKeybindManager.cancelBinding();
		ModConfig.save();
		super.removed();
	}

	private void persistView() {
		ClickGuiState.setCategory(selectedCategory);
		ClickGuiState.setOpenModule(openSettingsModule);
		ClickGuiState.setExpandedColor(expandedColorSetting);
		ClickGuiState.setSettingsScroll(settingsScroll);
		ModConfig.markDirty();
	}

	private ClickGuiMotion motion() {
		return ClickGuiMotion.fromSetting(VisualModule.INSTANCE.clickGuiMotion().value());
	}

	/** Advances render-only animation state without putting any of it into the saved GUI state. */
	private void updateAnimationState(long now, ClickGuiMotion motion) {
		if (motion == ClickGuiMotion.NONE) {
			while (!navigationQueue.isEmpty()) {
				applyView(navigationQueue.removeFirst().view);
			}
			transitionFrom = null;
			transitionTo = null;
		} else if (transitionFrom != null) {
			long elapsed = elapsedMillis(transitionStartedNanos, now);
			if (elapsed >= motion.transitionMillis()) {
				transitionFrom = null;
				transitionTo = null;
				if (!navigationQueue.isEmpty()) {
					NavigationRequest next = navigationQueue.removeFirst();
					startNavigation(next.view, next.direction, now, motion);
				}
			}
		}

		long frameMillis = Math.min(50L, elapsedMillis(lastFrameNanos, now));
		lastFrameNanos = now;
		if (motion == ClickGuiMotion.NONE) {
			gridScrollVisual = gridScroll;
			settingsScrollVisual = settingsScroll;
		} else {
			// Exponential settling is frame-rate independent and reaches the exact target when the
			// remaining distance is smaller than a pixel, so the scrollbar never shimmers forever.
			double factor = 1.0 - Math.exp(-frameMillis / (motion == ClickGuiMotion.EXPRESSIVE ? 95.0 : 60.0));
			gridScrollVisual = settle(gridScrollVisual, gridScroll, factor);
			settingsScrollVisual = settle(settingsScrollVisual, settingsScroll, factor);
		}
		if ((expansionFrom != null || expansionTo != null)
			&& (motion == ClickGuiMotion.NONE
				|| elapsedMillis(expansionStartedNanos, now) >= motion.transitionMillis())) {
			expansionFrom = null;
			expansionTo = null;
		}
		if (settingsLayoutTransition != null
			&& (motion == ClickGuiMotion.NONE
				|| elapsedMillis(settingsLayoutTransition.startedNanos(), now) >= motion.transitionMillis())) {
			settingsLayoutTransition = null;
		}
	}

	private static double settle(double current, double target, double factor) {
		double value = current + (target - current) * factor;
		return Math.abs(target - value) < 0.05 ? target : value;
	}

	private float lifecycleProgress(long now, ClickGuiMotion motion) {
		if (motion == ClickGuiMotion.NONE) return 1.0f;
		long elapsed = elapsedMillis(lifecycleStartedNanos, now);
		return closing ? motion.closeProgress(elapsed)
			: motion.ease(elapsed / (float) motion.lifecycleMillis());
	}

	private float transitionProgress(long now, ClickGuiMotion motion) {
		if (motion == ClickGuiMotion.NONE || transitionFrom == null) return 1.0f;
		return motion.ease(elapsedMillis(transitionStartedNanos, now) / (float) motion.transitionMillis());
	}

	private static long elapsedMillis(long startedNanos, long nowNanos) {
		return Math.max(0L, (nowNanos - startedNanos) / 1_000_000L);
	}

	private ViewState currentView() {
		return new ViewState(selectedCategory, openSettingsModule, gridScroll, settingsScroll, expandedColorSetting);
	}

	private void applyView(ViewState view) {
		selectedCategory = view.category;
		openSettingsModule = view.module;
		gridScroll = view.gridScroll;
		settingsScroll = view.settingsScroll;
		expandedColorSetting = view.expandedColor;
	}

	private void requestNavigation(ViewState target, int direction) {
		ClickGuiMotion motion = motion();
		if (currentView().equals(target)) return;
		if (transitionFrom != null) {
			if (navigationQueue.isEmpty() || !navigationQueue.peekLast().view.equals(target)) {
				navigationQueue.addLast(new NavigationRequest(target, direction));
			}
			return;
		}
		if (currentView().equals(target)) return;
		startNavigation(target, direction, System.nanoTime(), motion);
	}

	private void startNavigation(ViewState target, int direction, long now, ClickGuiMotion motion) {
		ViewState from = currentView();
		applyView(target);
		if (from.module != target.module) {
			expansionFrom = null;
			expansionTo = null;
		}
		if (!motion.animated()) {
			transitionFrom = null;
			transitionTo = null;
			return;
		}
		transitionFrom = from;
		transitionTo = target;
		transitionDirection = direction == 0 ? 1 : direction;
		transitionStartedNanos = now;
	}

	private void beginClose(Screen next) {
		if (closing) return;
		pendingScreen = next;
		navigationQueue.clear();
		transitionFrom = null;
		transitionTo = null;
		ClickGuiMotion motion = motion();
		if (!motion.animated()) {
			this.minecraft.setScreen(next);
			return;
		}
		closing = true;
		lifecycleStartedNanos = System.nanoTime();
	}

	private void markInteraction(Object target) {
		lastInteraction = target;
		lastInteractionNanos = System.nanoTime();
	}

	private void playClick() {
		this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
	}

	private float interactionPulse(Object target, long now, ClickGuiMotion motion) {
		if (target == null || target != lastInteraction || motion == ClickGuiMotion.NONE) return 0.0f;
		return 1.0f - motion.ease(elapsedMillis(lastInteractionNanos, now) / 180.0f);
	}

	private void updateHoverPulse(Object target, boolean hovered, long now, ClickGuiMotion motion) {
		boolean wasHovered = Boolean.TRUE.equals(hoverStates.put(target, hovered));
		if (motion == ClickGuiMotion.NONE || !hovered) {
			hoverPulses.remove(target);
			return;
		}
		if (!wasHovered) hoverPulses.put(target, new HoverPulse(now));
	}

	private float hoverJiggle(Object target, long now, ClickGuiMotion motion) {
		if (motion == ClickGuiMotion.NONE) return 0.0f;
		HoverPulse pulse = hoverPulses.get(target);
		if (pulse == null) return 0.0f;
		float progress = elapsedMillis(pulse.startedNanos, now) / (float) motion.hoverMillis();
		if (progress >= 1.0f) {
			hoverPulses.remove(target);
			return 0.0f;
		}
		float envelope = 1.0f - progress;
		return (float) (Math.sin(progress * Math.PI * 6.0) * envelope * envelope * motion.hoverAmplitude());
	}

	private void animateToggle(Object target, boolean from, boolean to) {
		if (from == to || motion() == ClickGuiMotion.NONE) {
			togglePulses.remove(target);
			return;
		}
		togglePulses.put(target, new TogglePulse(from, to, System.nanoTime()));
	}

	private float togglePosition(Object target, boolean on, long now, ClickGuiMotion motion) {
		if (motion == ClickGuiMotion.NONE) return on ? 1.0f : 0.0f;
		TogglePulse pulse = togglePulses.get(target);
		if (pulse == null) return on ? 1.0f : 0.0f;
		float progress = motion.ease(elapsedMillis(pulse.startedNanos, now) / 180.0f);
		if (progress >= 1.0f) {
			togglePulses.remove(target);
			return pulse.to ? 1.0f : 0.0f;
		}
		return lerp(pulse.from ? 1.0f : 0.0f, pulse.to ? 1.0f : 0.0f, progress);
	}

	private void setExpandedColor(ColorSetting target) {
		ColorSetting from = expandedColorSetting;
		expandedColorSetting = target;
		if (from == target || motion() == ClickGuiMotion.NONE) {
			expansionFrom = null;
			expansionTo = null;
			return;
		}
		expansionFrom = from;
		expansionTo = target;
		expansionStartedNanos = System.nanoTime();
	}

	private void startSettingsLayoutTransition(Module module, List<Row> from, List<Row> to) {
		if (motion() == ClickGuiMotion.NONE || from.equals(to)) {
			settingsLayoutTransition = null;
			return;
		}
		settingsLayoutTransition = SettingsLayoutTransition.create(module, from, to, System.nanoTime());
	}

	private float settingsLayoutProgress(long now, ClickGuiMotion motion) {
		if (settingsLayoutTransition == null || motion == ClickGuiMotion.NONE) return 1.0f;
		return motion.ease(elapsedMillis(settingsLayoutTransition.startedNanos(), now)
			/ (float) motion.transitionMillis());
	}

	private float expansionProgress(long now, ClickGuiMotion motion) {
		if (expansionFrom == null && expansionTo == null) return 1.0f;
		if (motion == ClickGuiMotion.NONE) return 1.0f;
		return motion.ease(elapsedMillis(expansionStartedNanos, now) / (float) motion.transitionMillis());
	}

	/** During collapse the old geometry remains briefly so the picker can reveal away cleanly. */
	private ColorSetting renderedExpandedColor(long now, ClickGuiMotion motion) {
		if (expansionTo == null && expansionFrom != null && expansionProgress(now, motion) < 1.0f) {
			return expansionFrom;
		}
		return expandedColorSetting;
	}

	private float pickerReveal(ColorSetting setting, long now, ClickGuiMotion motion) {
		if (expansionFrom == null && expansionTo == null) return setting == expandedColorSetting ? 1.0f : 0.0f;
		float progress = expansionProgress(now, motion);
		if (setting == expansionTo) return progress;
		if (setting == expansionFrom && expansionTo == null) return 1.0f - progress;
		return setting == expandedColorSetting ? 1.0f : 0.0f;
	}

	// ---- layout -------------------------------------------------------------------------

	/** Widest 2:1 box that fits inside both the width and height budgets. */
	private int panelWidth() {
		int byWidth = Math.round(this.width * WIDTH_FRACTION);
		int byHeight = Math.round(this.height * MAX_HEIGHT_FRACTION) * 2;
		int width = Math.min(byWidth, byHeight);
		if (byWidth >= MIN_PANEL_WIDTH && byHeight >= MIN_PANEL_WIDTH) {
			width = Math.max(MIN_PANEL_WIDTH, width);
		}
		// Even, so panelHeight() is exactly half and the 2:1 ratio holds on real screens. Keep a
		// one-pixel fallback for synthetic/tiny viewports used by resize paths instead of creating a
		// panel that extends beyond the actual framebuffer.
		int bounded = Math.max(1, Math.min(width, Math.max(1, this.width)));
		if (bounded < 2) return bounded;
		return Math.max(2, bounded & ~1);
	}

	private int panelHeight() {
		return Math.max(1, Math.min(Math.max(1, this.height), panelWidth() / 2));
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

	private int contentTop(int outerPanelY) {
		return outerPanelY + HEADER_HEIGHT;
	}

	private Rect profileHeaderRect(int panelX, int panelY) {
		return new Rect(panelX + HEADER_INSET, panelY + HEADER_INSET,
			Math.max(1, categoryWidth() - HEADER_INSET * 2), HEADER_ROW_HEIGHT);
	}

	private Rect searchRect(int rightX, int panelY) {
		return new Rect(rightX + HEADER_INSET, panelY + HEADER_INSET,
			Math.max(1, moduleWidth() - HEADER_INSET * 2), HEADER_ROW_HEIGHT);
	}

	private List<Module> visibleModules(Category category) {
		if (searchQuery.isBlank()) return ModuleManager.modules(category);
		String needle = searchQuery.trim().toLowerCase(Locale.ROOT);
		List<Module> matches = new ArrayList<>();
		for (Module module : ModuleManager.modules()) {
			String haystack = (module.name() + " " + module.description()).toLowerCase(Locale.ROOT);
			if (haystack.contains(needle)) matches.add(module);
		}
		return matches;
	}

	private void setSearchQuery(String value) {
		String normalized = value == null ? "" : value;
		if (normalized.length() > SEARCH_MAX_LENGTH) normalized = normalized.substring(0, SEARCH_MAX_LENGTH);
		if (normalized.equals(searchQuery)) return;
		searchQuery = normalized;
		gridScroll = 0;
		gridScrollVisual = 0;
	}

	private void renderHeader(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY,
		int panelX, int panelY, int rightX, long now, ClickGuiMotion motion) {
		Rect profile = profileHeaderRect(panelX, panelY);
		Rect search = searchRect(rightX, panelY);
		boolean profileHovered = profile.contains(mouseX, mouseY);
		boolean searchHovered = search.contains(mouseX, mouseY);
		roundedRect(graphics, profile.x, profile.y, profile.w, profile.h, RADIUS_SMALL,
			profileHovered ? CATEGORY_HOVER : GROUP_HEADER);
		roundedRectBordered(graphics, search.x, search.y, search.w, search.h, RADIUS_SMALL,
			searchFocused ? BUTTON_BG : GROUP_HEADER, searchFocused ? BUTTON_BG : GROUP_HEADER,
			searchFocused ? PANEL_HIGHLIGHT : BORDER);

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null) {
			PlayerFaceExtractor.extractRenderState(graphics, minecraft.player.getSkin(),
				profile.x + 2, profile.y + 1, PROFILE_FACE_SIZE);
		}
		String name = minecraft.player == null ? "Player" : minecraft.player.getName().getString();
		int textX = profile.x + PROFILE_FACE_SIZE + 6;
		int available = Math.max(1, profile.w - PROFILE_FACE_SIZE - 10);
		String version = "v" + (UpdateChecker.currentVersion().isBlank() ? "?" : UpdateChecker.currentVersion());
		String summary = textFit(font, name + "  " + version, available);
		graphics.text(font, summary, textX, profile.y + 3, TEXT_PRIMARY);
		String newer = UpdateChecker.newerVersion();
		if (newer != null && profile.w > 120) {
			String update = textFit(font, "Update v" + newer, Math.max(1, available - 2));
			graphics.text(font, update, textX, profile.y + 11, TEXT_WARN);
		}

		String queryText = searchQuery.isEmpty() ? "Search modules..." : searchQuery;
		int queryColor = searchQuery.isEmpty() ? TEXT_MUTED : TEXT_PRIMARY;
		graphics.text(font, textFit(font, queryText, Math.max(1, search.w - 12)),
			search.x + 6, search.y + 6, queryColor);
		if (searchFocused && (System.currentTimeMillis() / CARET_BLINK_MILLIS) % 2 == 0) {
			int caretX = search.x + 6 + font.width(textFit(font, searchQuery, Math.max(1, search.w - 12)));
			graphics.fill(caretX, search.y + 4, caretX + 1, search.y + search.h - 4, TEXT_PRIMARY);
		}
	}

	// ---- rendering ----------------------------------------------------------------------

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		// Before anything reads the palette: dragging a theme colour has to redraw the menu in it
		// on the same frame, which is the whole point of editing a theme from inside the menu.
		VisualModule.INSTANCE.refreshTheme();
		long now = System.nanoTime();
		ClickGuiMotion motion = motion();
		updateAnimationState(now, motion);
		if (openSettingsModule != null && !ModuleManager.modules(selectedCategory).contains(openSettingsModule)) {
			openSettingsModule = null;
			expandedColorSetting = null;
		}
		clampViewScroll();
		if (closing && elapsedMillis(lifecycleStartedNanos, now) >= motion.lifecycleMillis()) return;

		float lifecycle = lifecycleProgress(now, motion);
		float visible = closing ? 1.0f - lifecycle : lifecycle;
		float shade = motion == ClickGuiMotion.EXPRESSIVE ? 0.78f : 0.66f;
		graphics.fill(0, 0, this.width, this.height, withOpacity(0xC0000000, visible * shade));

		int panelX = panelX();
		int panelY = panelY();
		int panelWidth = panelWidth();
		int categoryWidth = categoryWidth();
		int moduleWidth = moduleWidth();
		int panelHeight = panelHeight();
		int rightX = panelX + categoryWidth;
		Font font = this.font;

		var pose = graphics.pose();
		pose.pushMatrix();
		float scale;
		if (closing) {
			scale = motion.closeScale(lifecycle);
		} else if (motion == ClickGuiMotion.NONE) {
			scale = 1.0f;
		} else if (motion == ClickGuiMotion.REDUCED) {
			scale = 0.90f + 0.10f * lifecycle;
		} else {
			scale = 0.72f + 0.28f * motion.spring(lifecycle);
		}
		pose.translate(this.width / 2.0f, this.height / 2.0f);
		pose.scale(scale, scale);
		pose.translate(-this.width / 2.0f, -this.height / 2.0f);

		// A soft drop edge and a one-pixel highlight make the same flat palette read as a raised,
		// intentional surface without adding another theme-specific colour.
		roundedRect(graphics, panelX - 2, panelY + 3, panelWidth() + 4, panelHeight + 2, RADIUS + 2,
			withOpacity(0xFF000000, 0.32f));
		roundedRectBordered(graphics, panelX, panelY, categoryWidth, panelHeight, RADIUS, PANEL_TOP, PANEL_BOTTOM, BORDER);
		roundedRectBordered(graphics, rightX, panelY, moduleWidth, panelHeight, RADIUS, MODULE_PANEL_TOP, MODULE_PANEL_BOTTOM, BORDER);
		graphics.fillGradient(panelX + 10, panelY + 2, panelX + categoryWidth - 10, panelY + 3,
			withOpacity(PANEL_HIGHLIGHT, 0.75f), withOpacity(PANEL_HIGHLIGHT, 0.0f));
		graphics.fillGradient(rightX + 10, panelY + 2, rightX + moduleWidth - 10, panelY + 3,
			withOpacity(PANEL_HIGHLIGHT, 0.75f), withOpacity(PANEL_HIGHLIGHT, 0.0f));

		// The rounded panels are decorative; all animated children must still be contained by their
		// rectangular surface bounds while the pose is scaled or translated.
		graphics.enableScissor(panelX, panelY, panelX + panelWidth, panelY + panelHeight);
		renderHeader(graphics, font, mouseX, mouseY, panelX, panelY, rightX, now, motion);
		renderCategories(graphics, font, mouseX, mouseY, panelX, panelY, now, motion);
		renderView(graphics, font, mouseX, mouseY, rightX, panelY, now, motion, lifecycle);
		graphics.disableScissor();
		if (visible < 1.0f && motion != ClickGuiMotion.NONE) {
			// A dark veil over the panel makes the zoom read as a fade as well. It is removed as the
			// panel settles in and applied during close while the panel shrinks away.
			float fade = (1.0f - visible) * (motion == ClickGuiMotion.EXPRESSIVE ? 0.42f : 0.20f);
			roundedRect(graphics, panelX, panelY, panelWidth, panelHeight, RADIUS,
				withOpacity(0xFF000000, fade));
		}
		pose.popMatrix();
		renderHoverTooltip(graphics, font, mouseX, mouseY);
		renderBindingNotice(graphics, font);
	}

	private void renderBindingNotice(GuiGraphicsExtractor graphics, Font font) {
		String target;
		if (ModuleKeybindManager.bindingModule() != null) {
			target = ModuleKeybindManager.bindingModule().name();
		} else if (ModuleKeybindManager.bindingMacro() != null) {
			target = ModuleKeybindManager.bindingMacro().name();
		} else {
			return;
		}
		int boxW = Math.min(420, Math.max(220, width - 24));
		int boxH = 30;
		int boxX = (width - boxW) / 2;
		int boxY = height - boxH - 12;
		roundedRectBordered(graphics, boxX, boxY, boxW, boxH, RADIUS_SMALL,
			BUTTON_HOVER, BUTTON_HOVER, TEXT_PRIMARY);
		graphics.text(font, "Listening for a hotkey: " + textFit(font, target, boxW - 170),
			boxX + 8, boxY + 5, TEXT_PRIMARY);
		graphics.text(font, "Press and release a key; Escape clears; click the key field to cancel.",
			boxX + 8, boxY + 16, TEXT_ON_ACCENT);
	}

	private void renderHoverTooltip(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
		if (closing || transitionFrom != null || openSettingsModule == null || !inputSettled()) return;
		Rect viewport = settingsViewport(panelX() + categoryWidth(), panelY());
		if (!viewport.contains(mouseX, mouseY)) return;
		for (Row row : settingsRows(openSettingsModule, panelX() + categoryWidth(), panelY())) {
			if (!row.bounds().contains(mouseX, mouseY)) continue;
			String description = settingTooltip(openSettingsModule, row);
			if (description != null && !description.isBlank()) {
				graphics.setTooltipForNextFrame(Component.literal(description), mouseX, mouseY);
			}
			return;
		}
	}

	private String settingTooltip(Module module, Row row) {
		return switch (row) {
			case GroupRow groupRow -> "Click to expand or collapse " + groupRow.group.name() + ".";
			case ColorRow ignored -> "Click a colour to open its picker; drag the controls or enter a hex value.";
			case NumberRow numberRow -> usesNumberTextInput(numberRow.setting)
				? "Type a number between " + numberText(numberRow.setting.min()) + " and "
					+ numberText(numberRow.setting.max()) + ". Press Enter to apply."
				: "Drag the slider to choose a value between " + numberText(numberRow.setting.min())
					+ " and " + numberText(numberRow.setting.max()) + ".";
			case ToggleRow toggleRow -> toggleTooltip(module, toggleRow.setting);
			case ChoiceRow choiceRow -> choiceTooltip(module, choiceRow.setting);
			case TextRow textRow -> "Macros".equals(module.name())
				&& "Rename Macro".equals(textRow.setting.displayName())
				? "Type the macro name to show in the macro list, hotkey banner, editor, and logs. Press Enter to finish."
				: "Click the field and type " + textRow.setting.displayName() + ". Press Enter to finish.";
			case ActionRow actionRow -> actionRow.action.description() != null
				? actionRow.action.description() : "Click to run " + actionRow.action.label() + ".";
		};
	}

	private String choiceTooltip(Module module, ChoiceSetting setting) {
		if ("Macros".equals(module.name()) && "Trigger Context".equals(setting.name())) {
			for (MacroTriggerContext context : MacroTriggerContext.values()) {
				if (context.label().equals(setting.value())) return context.description();
			}
			return "Choose where the macro hotkey is allowed to start.";
		}
		return "Click to choose the next value; right-click chooses the previous value.";
	}

	private String toggleTooltip(Module module, BooleanSetting setting) {
		if ("Macros".equals(module.name()) && "Enable Macro System".equals(setting.displayName())) {
			return "Master toggle for all macro hotkeys and running workflows. The module card must also be enabled.";
		}
		if ("Macros".equals(module.name()) && "Macro Enabled".equals(setting.displayName())) {
			return "Allow this macro to start from its hotkey without deleting its workflow.";
		}
		return "Toggle " + setting.displayName() + ".";
	}

	private static String numberText(float value) {
		return value == Math.round(value) ? Integer.toString(Math.round(value)) : Float.toString(value);
	}

	private void renderCategories(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY,
		int panelX, int panelY, long now, ClickGuiMotion motion) {
		// Category labels and their hover jiggle belong to this column only. The outer panel clip
		// contains the whole menu, so establish the narrower clip before drawing this side.
		int contentTop = contentTop(panelY);
		graphics.enableScissor(panelX, contentTop, panelX + categoryWidth(), panelY + panelHeight());
		List<Rect> categoryRows = categoryRows(panelX, panelY);
		Category[] categories = Category.values();
		boolean moving = transitionFrom != null && transitionFrom.category != selectedCategory;
		if (moving) {
			Rect from = categoryRows.get(indexOf(transitionFrom.category));
			Rect to = categoryRows.get(indexOf(selectedCategory));
			float progress = transitionProgress(now, motion);
			int y = Math.round(lerp(from.y, to.y, progress));
			roundedRect(graphics, to.x + 5, y, to.w - 10, to.h - 2, RADIUS_SMALL, CATEGORY_SELECTED);
		}
		for (int i = 0; i < categories.length; i++) {
			Rect row = categoryRows.get(i);
			Category category = categories[i];
			boolean selected = category == selectedCategory;
			boolean hovered = row.contains(mouseX, mouseY);
			updateHoverPulse(category, hovered, now, motion);
			var pose = graphics.pose();
			pose.pushMatrix();
			pose.translate(hoverJiggle(category, now, motion), 0.0f);
			if (!moving && selected) {
				roundedRect(graphics, row.x + 5, row.y, row.w - 10, row.h - 2, RADIUS_SMALL, CATEGORY_SELECTED);
			} else if (hovered) {
				roundedRect(graphics, row.x + 5, row.y, row.w - 10, row.h - 2, RADIUS_SMALL, CATEGORY_HOVER);
			}
			int color = selected ? TEXT_PRIMARY : TEXT_SECONDARY;
			graphics.text(font, textFit(font, category.displayName(), Math.max(1, row.w - 20)),
				row.x + 14, row.y + (row.h - TEXT_HEIGHT) / 2, color);
			pose.popMatrix();
		}

		Rect move = moveElementsRect(panelX, panelY);
		boolean moveHovered = move.contains(mouseX, mouseY);
		roundedRect(graphics, move.x, move.y, move.w, move.h, RADIUS_SMALL, moveHovered ? BUTTON_HOVER : BUTTON_BG);
		graphics.centeredText(font, textFit(font, "Move Elements", Math.max(1, move.w - 6)),
			move.x + move.w / 2, move.y + (move.h - TEXT_HEIGHT) / 2, TEXT_PRIMARY);

		// The update notice sits above the button. Clipped to the panel rather than left to
		// spill across the module list, since this column is narrow on a small screen.
		String notice = UpdateChecker.bannerText();
		if (notice != null) {
			int available = Math.max(1, categoryWidth() - 28);
			String text = textFit(font, notice, available);
			graphics.text(font, text, panelX + 14, move.y - 4 - TEXT_HEIGHT, TEXT_WARN);
		}
		graphics.disableScissor();
	}

	private Rect moveElementsRect(int panelX, int panelY) {
		int height = 18;
		return new Rect(panelX + 6, panelY + panelHeight() - PADDING - height,
			Math.max(1, categoryWidth() - 12), height);
	}

	private void renderView(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY,
		int rightX, int panelY, long now, ClickGuiMotion motion, float lifecycle) {
		// This clip is deliberately established before renderViewAt translates the outgoing and
		// incoming pages. Their own viewports may move; the module surface must not.
		int contentTop = contentTop(panelY);
		graphics.enableScissor(rightX, contentTop, rightX + moduleWidth(), panelY + panelHeight());
		if (transitionFrom != null) {
			float progress = transitionProgress(now, motion);
			int travel = moduleWidth() + 18;
			int outgoingOffset = Math.round(-transitionDirection * travel * progress);
			int incomingOffset = Math.round(transitionDirection * travel * (1.0f - progress));
			renderViewAt(graphics, font, mouseX, mouseY, transitionFrom, rightX, panelY, outgoingOffset,
				false, false, transitionStartedNanos, now, motion);
			renderViewAt(graphics, font, mouseX, mouseY, transitionTo, rightX, panelY, incomingOffset,
				false, true, transitionStartedNanos, now, motion);
			graphics.disableScissor();
			return;
		}

		ViewState current = currentView();
		renderViewAt(graphics, font, mouseX, mouseY, current, rightX, panelY, 0,
			true, true, openedAtNanos, now, motion);
		graphics.disableScissor();
	}

	private void renderViewAt(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY,
		ViewState view, int rightX, int panelY, int offset, boolean hoverable, boolean entering,
		long entranceStart, long now, ClickGuiMotion motion) {
		var pose = graphics.pose();
		pose.pushMatrix();
		if (offset != 0) pose.translate(offset, 0.0f);
		List<Module> modules = visibleModules(view.category);
		if (view.module != null && ModuleManager.modules(view.category).contains(view.module)) {
			double scroll = transitionFrom == null ? settingsScrollVisual : view.settingsScroll;
			ColorSetting expanded = transitionFrom == null ? renderedExpandedColor(now, motion) : view.expandedColor;
			renderSettingsView(graphics, font, mouseX, mouseY, rightX, panelY, view.module, scroll,
				expanded, hoverable, now, motion);
		} else {
			double scroll = transitionFrom == null ? gridScrollVisual : view.gridScroll;
			renderModuleGrid(graphics, font, mouseX, mouseY, modules, rightX, panelY, scroll,
				hoverable, entering, entranceStart, now, motion);
		}
		pose.popMatrix();
	}

	// ---- module grid --------------------------------------------------------------------

	private void renderModuleGrid(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY,
		List<Module> modules, int x, int panelY, double scroll, boolean hoverable, boolean entering,
		long entranceStart, long now, ClickGuiMotion motion) {
		Rect viewport = gridViewport(x, panelY);
		List<CardRect> cards = cardRects(modules, x, panelY, scroll);
		boolean insideViewport = viewport.contains(mouseX, mouseY);

		graphics.enableScissor(viewport.x, viewport.y, viewport.x + viewport.w, viewport.y + viewport.h);
		for (int i = 0; i < cards.size(); i++) {
			CardRect card = cards.get(i);
			boolean cardHoverable = hoverable && insideViewport;
			float entrance = entering ? cardEntrance(i, entranceStart, now, motion) : 1.0f;
			float lift = (1.0f - entrance) * (motion == ClickGuiMotion.EXPRESSIVE ? 10.0f : 5.0f);
			float cardScale = 0.97f + 0.03f * (motion == ClickGuiMotion.EXPRESSIVE
				? motion.spring(entrance) : entrance);
			var pose = graphics.pose();
			pose.pushMatrix();
			pose.translate(card.bounds.x + card.bounds.w / 2.0f, card.bounds.y + card.bounds.h / 2.0f + lift);
			pose.scale(cardScale, cardScale);
			pose.translate(-(card.bounds.x + card.bounds.w / 2.0f), -(card.bounds.y + card.bounds.h / 2.0f));
			renderCard(graphics, font, mouseX, mouseY, card, cardHoverable, now, motion);
			pose.popMatrix();
		}
		graphics.disableScissor();

		renderScrollbar(graphics, viewport, gridContentHeight(modules), (int) Math.round(scroll));
	}

	private float cardEntrance(int index, long entranceStart, long now, ClickGuiMotion motion) {
		if (motion == ClickGuiMotion.NONE) return 1.0f;
		int staggerIndex = Math.min(index, 5);
		long delayed = elapsedMillis(entranceStart, now) - (long) staggerIndex * motion.cardStaggerMillis();
		return motion.ease(delayed / (float) Math.max(1, motion.transitionMillis()));
	}

	private void renderCard(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY,
		CardRect card, boolean hoverable, long now, ClickGuiMotion motion) {
		Module module = card.module;
		Rect bounds = card.bounds;
		boolean enabled = module.isEnabled();
		boolean hovered = hoverable && bounds.contains(mouseX, mouseY);
		updateHoverPulse(module, hovered, now, motion);
		var pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(hoverJiggle(module, now, motion), 0.0f);

		float togglePosition = togglePosition(module, enabled, now, motion);
		int background = lerpColor(CARD_BG, CARD_BG_ENABLED, togglePosition);
		int hoverBackground = lerpColor(CARD_BG_HOVER, CARD_BG_ENABLED, togglePosition);
		if (hovered) background = lerpColor(background, hoverBackground, 0.85f);
		int border = lerpColor(CARD_BORDER, CARD_BORDER_ENABLED, togglePosition);
		float pulse = interactionPulse(module, now, motion);
		if (pulse > 0.0f) {
			background = lerpColor(background, CARD_BG_HOVER, pulse * 0.65f);
			border = lerpColor(border, PANEL_HIGHLIGHT, pulse);
		}
		roundedRectBordered(graphics, bounds.x, bounds.y, bounds.w, bounds.h, RADIUS_SMALL, background, background, border);

		// The card reflects the switch the user set and nothing else. Context idling used to mute
		// the name too, which read as the module turning itself off and on while travelling - the
		// reason line below says it in words, which is the part that was actually wanted.
		int textX = bounds.x + CARD_INSET;
		int textWidth = bounds.w - CARD_INSET * 2;
		String cardName = searchQuery.isBlank() ? module.name()
			: "[" + module.category().displayName() + "] " + module.name();
		graphics.text(font, textFit(font, cardName, textWidth - SWITCH_WIDTH - 4),
			textX, bounds.y + CARD_INSET, TEXT_PRIMARY);

		int descY = bounds.y + CARD_INSET + LINE_HEIGHT + 3;
		for (FormattedCharSequence line : descriptionLines(font, module, textWidth)) {
			graphics.text(font, line, textX, descY, TEXT_MUTED);
			descY += LINE_HEIGHT;
		}

		String status = module.inactiveReason();
		if (status != null) {
			graphics.text(font, textFit(font, status, textWidth),
				textX, bounds.y + bounds.h - CARD_INSET - KEYBIND_HEIGHT - 4 - TEXT_HEIGHT, TEXT_WARN);
		}

		Rect keybind = keybindRect(bounds);
		boolean binding = ModuleKeybindManager.isBinding(module);
		boolean keybindHovered = hoverable && keybind.contains(mouseX, mouseY);
		int keybindBackground = binding || keybindHovered ? BUTTON_HOVER : BUTTON_BG;
		roundedRectBordered(graphics, keybind.x, keybind.y, keybind.w, keybind.h, RADIUS_SMALL,
			keybindBackground, keybindBackground, binding ? TEXT_PRIMARY : BORDER);
		String keyText = binding ? "Listening…" : module.keybind().displayName();
		graphics.centeredText(font, textFit(font, keyText, keybind.w - 8),
			keybind.x + keybind.w / 2, keybind.y + (keybind.h - TEXT_HEIGHT) / 2, TEXT_PRIMARY);

		Rect toggle = switchRect(bounds);
		toggleSwitch(graphics, toggle.x, toggle.y, toggle.w, toggle.h,
			togglePosition);
		pose.popMatrix();
	}

	private Rect switchRect(Rect card) {
		return new Rect(card.x + card.w - SWITCH_WIDTH - CARD_INSET, card.y + CARD_INSET - 1, SWITCH_WIDTH, SWITCH_HEIGHT);
	}

	private Rect keybindRect(Rect card) {
		return new Rect(card.x + CARD_INSET, card.y + card.h - CARD_INSET - KEYBIND_HEIGHT,
			card.w - CARD_INSET * 2, KEYBIND_HEIGHT);
	}

	private List<FormattedCharSequence> descriptionLines(Font font, Module module, int width) {
		List<FormattedCharSequence> lines = font.split(Component.literal(module.description()), width);
		return lines.size() > CARD_MAX_DESC_LINES ? lines.subList(0, CARD_MAX_DESC_LINES) : lines;
	}

	private static String textFit(Font font, String text, int width) {
		if (text == null || width <= 0) return "";
		if (font.width(text) <= width) return text;
		String ellipsis = "…";
		int available = Math.max(0, width - font.width(ellipsis));
		return font.plainSubstrByWidth(text, available) + ellipsis;
	}

	/** Uniform across the category so the grid stays on a shared baseline. */
	private int cardHeight(List<Module> modules) {
		int width = Math.max(1, cardWidth() - CARD_INSET * 2);
		int descLines = 1;
		for (Module module : modules) {
			descLines = Math.max(descLines, descriptionLines(this.font, module, width).size());
		}
		// Name, description, status and keybind field are reserved so the grid never resizes when
		// a module changes context or starts listening for a key.
		return CARD_INSET + LINE_HEIGHT + 3 + descLines * LINE_HEIGHT + 3 + TEXT_HEIGHT
			+ 4 + KEYBIND_HEIGHT + CARD_INSET;
	}

	private int cardWidth() {
		int available = moduleWidth() - PADDING * 2 - CARD_GAP * (CARD_COLUMNS - 1);
		return Math.max(1, available / CARD_COLUMNS);
	}

	private Rect gridViewport(int x, int panelY) {
		return new Rect(x, contentTop(panelY) + PADDING, Math.max(1, moduleWidth()),
			Math.max(1, panelHeight() - HEADER_HEIGHT - PADDING * 2));
	}

	private int gridContentHeight(List<Module> modules) {
		if (modules.isEmpty()) return 0;
		int rows = (modules.size() + CARD_COLUMNS - 1) / CARD_COLUMNS;
		return rows * cardHeight(modules) + (rows - 1) * CARD_GAP;
	}

	private List<CardRect> cardRects(List<Module> modules, int x, int panelY) {
		return cardRects(modules, x, panelY, gridScrollVisual);
	}

	private List<CardRect> cardRects(List<Module> modules, int x, int panelY, double scroll) {
		Rect viewport = gridViewport(x, panelY);
		int maxScroll = Math.max(0, gridContentHeight(modules) - viewport.h);
		int appliedScroll = Math.max(0, Math.min(maxScroll, (int) Math.round(scroll)));

		int cardWidth = cardWidth();
		int cardHeight = cardHeight(modules);
		List<CardRect> cards = new ArrayList<>();
		for (int i = 0; i < modules.size(); i++) {
			int column = i % CARD_COLUMNS;
			int row = i / CARD_COLUMNS;
			int cardX = x + PADDING + column * (cardWidth + CARD_GAP);
			int cardY = viewport.y + row * (cardHeight + CARD_GAP) - appliedScroll;
			cards.add(new CardRect(modules.get(i), new Rect(cardX, cardY, cardWidth, cardHeight)));
		}
		return cards;
	}

	// ---- settings view ------------------------------------------------------------------

	private void renderSettingsView(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY,
		int x, int y, Module module, double scroll, ColorSetting expandedColor, boolean hoverable,
		long now, ClickGuiMotion motion) {
		Rect header = headerRow(x, y);
		if (hoverable && header.contains(mouseX, mouseY)) {
			roundedRect(graphics, header.x + 5, header.y, header.w - 10, header.h - 2, RADIUS_SMALL, CATEGORY_HOVER);
		}
		graphics.text(font, textFit(font, "< " + module.name(), Math.max(1, header.w - 28)),
			header.x + 14, header.y + (header.h - TEXT_HEIGHT) / 2, TEXT_PRIMARY);

		Rect viewport = settingsViewport(x, y);
		boolean insideViewport = hoverable && viewport.contains(mouseX, mouseY);
		List<Row> rows = settingsRows(module, x, y, scroll, expandedColor);
		int appliedScroll = settingsAppliedScroll(module, x, y, scroll, expandedColor);

		graphics.enableScissor(viewport.x, viewport.y, viewport.x + viewport.w, viewport.y + viewport.h);
		renderPreview(graphics, font, module, viewport, appliedScroll);
		if (settingsLayoutTransition != null && settingsLayoutTransition.module() == module && transitionFrom == null) {
			float progress = settingsLayoutProgress(now, motion);
			renderSettingsLayoutTransition(graphics, font, mouseX, mouseY,
				settingsLayoutTransition, viewport, progress, now, motion);
		} else {
			renderSettingRows(graphics, font, mouseX, mouseY, rows, module, insideViewport, now, motion);
		}
		graphics.disableScissor();

		renderScrollbar(graphics, viewport, settingsContentHeight(module, x, expandedColor), (int) Math.round(scroll));
	}

	private void renderSettingRows(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY,
		List<Row> rows, Module module, boolean hoverable, long now, ClickGuiMotion motion) {
		for (Row row : rows) {
			renderSettingRow(graphics, font, mouseX, mouseY, row, module, hoverable, now, motion);
		}
	}

	/**
	 * Renders one row per setting identity while a group changes shape. Shared rows move between
	 * their old and new bounds; rows introduced or removed by the group are revealed through one
	 * bounded window. Keeping the identity in one transition entry prevents the old/new snapshot
	 * pair from ever being painted as two complete lists on top of each other.
	 */
	private void renderSettingsLayoutTransition(GuiGraphicsExtractor graphics, Font font, int mouseX,
		int mouseY, SettingsLayoutTransition transition, Rect viewport, float progress, long now,
		ClickGuiMotion motion) {
		List<RowTransition> rows = new ArrayList<>(transition.rows());
		rows.sort(Comparator.comparingInt((RowTransition row) -> transitionBounds(row, progress).y)
			.thenComparingInt(RowTransition::order));
		for (RowTransition row : rows) {
			Rect bounds = transitionBounds(row, progress);
			Row source = transitionSource(row, progress);
			Row rendered = withBounds(source, bounds);
			Rect reveal = transitionReveal(row, transition, progress);
			if (reveal == null) {
				renderSettingRow(graphics, font, mouseX, mouseY, rendered, transition.module(), false, now, motion);
				continue;
			}

			Rect clip = intersection(viewport, reveal);
			if (clip == null || clip.w <= 0 || clip.h <= 0) continue;
			graphics.enableScissor(clip.x, clip.y, clip.x + clip.w, clip.y + clip.h);
			renderSettingRow(graphics, font, mouseX, mouseY, rendered, transition.module(), false, now, motion);
			graphics.disableScissor();
		}
	}

	private void renderSettingRow(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY,
		Row row, Module module, boolean hoverable, long now, ClickGuiMotion motion) {
		switch (row) {
			case GroupRow groupRow -> renderGroupRow(graphics, font, mouseX, mouseY, groupRow, module, hoverable, now, motion);
			case ColorRow colorRow -> renderColorRow(graphics, font, mouseX, mouseY, colorRow, hoverable, now, motion);
			case NumberRow numberRow -> renderNumberRow(graphics, font, numberRow, now, motion);
			case ToggleRow toggleRow -> renderToggleRow(graphics, font, mouseX, mouseY, toggleRow, hoverable, now, motion);
			case ChoiceRow choiceRow -> renderChoiceRow(graphics, font, mouseX, mouseY, choiceRow, hoverable, now, motion);
			case TextRow textRow -> renderTextRow(graphics, font, textRow, now, motion);
			case ActionRow actionRow -> renderActionRow(graphics, font, mouseX, mouseY, actionRow, hoverable, now, motion);
		}
	}

	private Row transitionSource(RowTransition transition, float progress) {
		if (transition.from() == null) return transition.to();
		if (transition.to() == null) return transition.from();
		return progress < 0.5f ? transition.from() : transition.to();
	}

	private Rect transitionBounds(RowTransition transition, float progress) {
		Rect from = transition.from() == null ? transition.to().bounds() : transition.from().bounds();
		Rect to = transition.to() == null ? transition.from().bounds() : transition.to().bounds();
		return new Rect(
			Math.round(lerp(from.x, to.x, progress)),
			Math.round(lerp(from.y, to.y, progress)),
			Math.max(1, Math.round(lerp(from.w, to.w, progress))),
			Math.max(1, Math.round(lerp(from.h, to.h, progress))));
	}

	private Rect transitionReveal(RowTransition row, SettingsLayoutTransition transition, float progress) {
		if (row.from() != null && row.to() == null && transition.fromReveal() != null) {
			Rect bounds = transition.fromReveal();
			return new Rect(bounds.x, bounds.y, bounds.w,
				Math.max(0, Math.round(bounds.h * (1.0f - progress))));
		}
		if (row.from() == null && row.to() != null && transition.toReveal() != null) {
			Rect bounds = transition.toReveal();
			return new Rect(bounds.x, bounds.y, bounds.w,
				Math.max(0, Math.round(bounds.h * progress)));
		}
		return null;
	}

	/** Rebuilds a row's secondary hit geometry after its animated bounds move. */
	private Row withBounds(Row row, Rect bounds) {
		Rect original = row.bounds();
		int dx = bounds.x - original.x;
		int dy = bounds.y - original.y;
		return switch (row) {
			case GroupRow groupRow -> new GroupRow(groupRow.group, bounds,
				translateRect(groupRow.toggle, dx, dy), groupRow.depth, groupRow.collapsed);
			case ColorRow colorRow -> new ColorRow(colorRow.setting, bounds,
				translatePicker(colorRow.picker, dx, dy));
			case NumberRow numberRow -> new NumberRow(numberRow.setting, bounds,
				translateRect(numberRow.slider, dx, dy), translateRect(numberRow.field, dx, dy));
			case ToggleRow toggleRow -> new ToggleRow(toggleRow.setting, bounds);
			case ChoiceRow choiceRow -> new ChoiceRow(choiceRow.setting, bounds,
				translateRect(choiceRow.field, dx, dy));
			case TextRow textRow -> new TextRow(textRow.setting, bounds,
				translateRect(textRow.field, dx, dy));
			case ActionRow actionRow -> new ActionRow(actionRow.action, bounds);
		};
	}

	private static Rect translateRect(Rect rect, int dx, int dy) {
		return rect == null ? null : new Rect(rect.x + dx, rect.y + dy, rect.w, rect.h);
	}

	private static Picker translatePicker(Picker picker, int dx, int dy) {
		if (picker == null) return null;
		return new Picker(translateRect(picker.square, dx, dy), translateRect(picker.hue, dx, dy),
			translateRect(picker.alpha, dx, dy), translateRect(picker.hex, dx, dy));
	}

	private static Rect intersection(Rect first, Rect second) {
		int left = Math.max(first.x, second.x);
		int top = Math.max(first.y, second.y);
		int right = Math.min(first.x + first.w, second.x + second.w);
		int bottom = Math.min(first.y + first.h, second.y + second.h);
		return right <= left || bottom <= top ? null : new Rect(left, top, right - left, bottom - top);
	}

	private void renderPreview(GuiGraphicsExtractor graphics, Font font, Module module, Rect viewport,
		int appliedScroll) {
		if (!(module instanceof ModulePreview preview)) return;
		Rect bounds = new Rect(viewport.x + PADDING, viewport.y + PADDING - appliedScroll,
			Math.max(1, viewport.w - PADDING * 2), PREVIEW_HEIGHT);
		roundedRectBordered(graphics, bounds.x, bounds.y, bounds.w, bounds.h, RADIUS_SMALL,
			CARD_BG, CARD_BG, CARD_BORDER);
		graphics.enableScissor(bounds.x, bounds.y, bounds.x + bounds.w, bounds.y + bounds.h);
		int lineY = bounds.y + 5;
		int textWidth = Math.max(1, bounds.w - PADDING * 2);
		for (Component line : preview.previewLines(font, textWidth)) {
			for (FormattedCharSequence wrapped : font.split(line, textWidth)) {
				if (lineY + LINE_HEIGHT > bounds.y + bounds.h - 3) break;
				graphics.text(font, wrapped, bounds.x + PADDING, lineY, TEXT_PRIMARY);
				lineY += LINE_HEIGHT;
			}
			if (lineY + LINE_HEIGHT > bounds.y + bounds.h - 3) break;
		}
		graphics.disableScissor();
	}

	private void renderGroupRow(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY,
		GroupRow groupRow, Module module, boolean hoverable, long now, ClickGuiMotion motion) {
		Rect bounds = groupRow.bounds;
		boolean hovered = hoverable && bounds.contains(mouseX, mouseY);
		float pulse = interactionPulse(groupRow.group, now, motion);
		// A nested section is stepped in from its parent, so the two never read as siblings.
		int indent = groupRow.depth * GROUP_INDENT;
		int headerColor = hovered ? CARD_BG_HOVER
			: pulse > 0.0f ? lerpColor(GROUP_HEADER, CARD_BG_HOVER, pulse * 0.8f) : GROUP_HEADER;
		roundedRect(graphics, bounds.x + 5 + indent, bounds.y + 1, bounds.w - 10 - indent, bounds.h - 3,
			RADIUS_SMALL, headerColor);
		boolean collapsed = groupRow.collapsed;
		graphics.text(font, collapsed ? "▸" : "▾", bounds.x + 12 + indent, bounds.y + (bounds.h - TEXT_HEIGHT) / 2, TEXT_SECONDARY);
		Rect toggle = groupRow.toggle;
		int labelWidth = bounds.w - 28 - indent - (toggle == null ? 0 : SWITCH_WIDTH + 18);
		graphics.text(font, textFit(font, groupRow.group.name(), labelWidth),
			bounds.x + 24 + indent, bounds.y + (bounds.h - TEXT_HEIGHT) / 2, TEXT_PRIMARY);

		if (toggle != null) {
			toggleSwitch(graphics, toggle.x, toggle.y, toggle.w, toggle.h,
				togglePosition(groupRow.group.toggle(), groupRow.group.toggle().value(), now, motion));
		}
	}

	private void renderColorRow(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY,
		ColorRow colorRow, boolean hoverable, long now, ClickGuiMotion motion) {
		Rect bounds = colorRow.bounds;
		ColorSetting setting = colorRow.setting;
		float pulse = interactionPulse(setting, now, motion);
		// Only the label strip is clickable, not the picker an expanded row adds below it.
		boolean hovered = hoverable && new Rect(bounds.x, bounds.y, bounds.w, ROW_HEIGHT).contains(mouseX, mouseY);
		if (hovered || pulse > 0.0f) {
			int highlight = hovered ? CATEGORY_HOVER : withOpacity(PANEL_HIGHLIGHT, pulse * 0.35f);
			roundedRect(graphics, bounds.x + 9, bounds.y, bounds.w - 18, ROW_HEIGHT - 2, RADIUS_SMALL, highlight);
		}
		graphics.text(font, textFit(font, setting.displayName(), bounds.w - VALUE_GUTTER - 28),
			bounds.x + 16, bounds.y + (ROW_HEIGHT - TEXT_HEIGHT) / 2, TEXT_SECONDARY);

		int swatchSize = 12;
		int swatchX = bounds.x + bounds.w - swatchSize - 14;
		int swatchY = bounds.y + (ROW_HEIGHT - swatchSize) / 2;
		checkerboard(graphics, swatchX, swatchY, swatchSize, swatchSize);
		roundedRectBordered(graphics, swatchX, swatchY, swatchSize, swatchSize, 3,
			setting.argb(), setting.argb(), BORDER);

		Picker picker = colorRow.picker;
		if (picker == null) return;
		float reveal = pickerReveal(setting, now, motion);
		int revealHeight = Math.round(PICKER_HEIGHT * reveal);
		if (revealHeight <= 0) return;
		var pose = graphics.pose();
		pose.pushMatrix();
		int translation = motion == ClickGuiMotion.EXPRESSIVE && reveal < 1.0f
			? Math.round((1.0f - reveal) * 7.0f) : 0;
		if (translation != 0) pose.translate(0.0f, translation);
		// Capture the reveal clip after the animated translation so the clip and picker move together.
		graphics.enableScissor(colorRow.bounds.x, picker.square.y + translation,
			colorRow.bounds.x + colorRow.bounds.w, picker.square.y + translation + revealHeight);
		renderSaturationSquare(graphics, picker.square, setting);
		renderHueBar(graphics, picker.hue, setting);
		renderAlphaBar(graphics, picker.alpha, setting);
		renderHexField(graphics, font, picker.hex, setting);
		graphics.disableScissor();
		pose.popMatrix();
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

	private void renderNumberRow(GuiGraphicsExtractor graphics, Font font, NumberRow numberRow,
		long now, ClickGuiMotion motion) {
		int controlWidth = numberRow.field == null ? VALUE_GUTTER + 28 : numberRow.field.w + 36;
		graphics.text(font, textFit(font, numberRow.setting.displayName(), numberRow.bounds.w - controlWidth),
			numberRow.bounds.x + 16, numberRow.bounds.y + 1, TEXT_SECONDARY);
		if (numberRow.field != null) {
			renderNumberField(graphics, font, numberRow, now, motion);
			return;
		}
		Rect slider = numberRow.slider;
		float pulse = interactionPulse(numberRow.setting, now, motion);
		int track = pulse > 0.0f ? lerpColor(SLIDER_TRACK, PANEL_HIGHLIGHT, pulse * 0.4f) : SLIDER_TRACK;
		roundedRect(graphics, slider.x, slider.y, slider.w, slider.h, slider.h / 2, track);
		int fillWidth = Math.round(slider.w * numberRow.setting.fraction());
		if (fillWidth > 0) {
			int fill = pulse > 0.0f ? lerpColor(SLIDER_FILL, TEXT_PRIMARY, pulse * 0.22f) : SLIDER_FILL;
			roundedRect(graphics, slider.x, slider.y, fillWidth, slider.h, slider.h / 2, fill);
		}
		graphics.text(font, numberRow.setting.display(), slider.x + slider.w + 8, slider.y - 2, TEXT_MUTED);
	}

	private void renderNumberField(GuiGraphicsExtractor graphics, Font font, NumberRow numberRow,
		long now, ClickGuiMotion motion) {
		Rect field = numberRow.field;
		boolean focused = numberRow.setting == focusedNumberSetting;
		float pulse = interactionPulse(numberRow.setting, now, motion);
		int border = focused ? SLIDER_FILL
			: pulse > 0.0f ? lerpColor(BORDER, PANEL_HIGHLIGHT, pulse) : BORDER;
		roundedRectBordered(graphics, field.x, field.y, field.w, field.h, 3,
			SLIDER_TRACK, SLIDER_TRACK, border);
		String shown = focused ? numberInput : numberRow.setting.display();
		int inner = field.w - 8;
		while (!shown.isEmpty() && font.width(shown) > inner) {
			shown = shown.substring(shown.offsetByCodePoints(0, 1));
		}
		int textY = field.y + (field.h - TEXT_HEIGHT) / 2;
		graphics.text(font, shown, field.x + 4, textY, TEXT_PRIMARY);
		if (focused && (System.currentTimeMillis() / CARET_BLINK_MILLIS) % 2 == 0) {
			int caretX = field.x + 4 + font.width(shown);
			graphics.fill(caretX, textY - 1, caretX + 1, textY + TEXT_HEIGHT + 1, TEXT_PRIMARY);
		}
	}

	private void renderToggleRow(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY,
		ToggleRow toggleRow, boolean hoverable, long now, ClickGuiMotion motion) {
		Rect bounds = toggleRow.bounds;
		boolean hovered = hoverable && bounds.contains(mouseX, mouseY);
		float pulse = interactionPulse(toggleRow.setting, now, motion);
		if (hovered || pulse > 0.0f) {
			int highlight = hovered ? CATEGORY_HOVER : withOpacity(PANEL_HIGHLIGHT, pulse * 0.35f);
			roundedRect(graphics, bounds.x + 9, bounds.y, bounds.w - 18, bounds.h - 2, RADIUS_SMALL, highlight);
		}
		graphics.text(font, textFit(font, toggleRow.setting.displayName(), bounds.w - SWITCH_WIDTH - 36),
			bounds.x + 16, bounds.y + (bounds.h - TEXT_HEIGHT) / 2, TEXT_SECONDARY);

		int switchX = bounds.x + bounds.w - SWITCH_WIDTH - 14;
		int switchY = bounds.y + (bounds.h - SWITCH_HEIGHT) / 2;
		toggleSwitch(graphics, switchX, switchY, SWITCH_WIDTH, SWITCH_HEIGHT,
			togglePosition(toggleRow.setting, toggleRow.setting.value(), now, motion));
	}

	private void renderChoiceRow(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY,
		ChoiceRow choiceRow, boolean hoverable, long now, ClickGuiMotion motion) {
		Rect bounds = choiceRow.bounds;
		boolean hovered = hoverable && bounds.contains(mouseX, mouseY);
		float pulse = interactionPulse(choiceRow.setting, now, motion);
		if (hovered) {
			roundedRect(graphics, bounds.x + 9, bounds.y, bounds.w - 18, bounds.h - 2, RADIUS_SMALL, CATEGORY_HOVER);
		}
		graphics.text(font, textFit(font, choiceRow.setting.displayName(), bounds.w - choiceRow.field.w - 36),
			bounds.x + 16,
			bounds.y + (bounds.h - TEXT_HEIGHT) / 2, TEXT_SECONDARY);

		Rect field = choiceRow.field;
		int fieldColor = hovered ? BUTTON_HOVER : (pulse > 0.0f
			? lerpColor(BUTTON_BG, BUTTON_HOVER, pulse * 0.75f) : BUTTON_BG);
		roundedRectBordered(graphics, field.x, field.y, field.w, field.h, 4,
			fieldColor, fieldColor, BORDER);
		String value = textFit(font, choiceRow.setting.value(), field.w - 12);
		graphics.centeredText(font, "‹ " + value + " ›", field.x + field.w / 2,
			field.y + (field.h - TEXT_HEIGHT) / 2, TEXT_PRIMARY);
	}

	private void renderTextRow(GuiGraphicsExtractor graphics, Font font, TextRow textRow,
		long now, ClickGuiMotion motion) {
		Rect bounds = textRow.bounds;
		Rect field = textRow.field;
		boolean focused = textRow.setting == focusedTextSetting;
		graphics.text(font, textFit(font, textRow.setting.displayName(), bounds.w - textRow.field.w - 36),
			bounds.x + 16, bounds.y + (bounds.h - TEXT_HEIGHT) / 2, TEXT_SECONDARY);

		float pulse = interactionPulse(textRow.setting, now, motion);
		int fieldBorder = focused ? SLIDER_FILL
			: pulse > 0.0f ? lerpColor(BORDER, PANEL_HIGHLIGHT, pulse) : BORDER;
		roundedRectBordered(graphics, field.x, field.y, field.w, field.h, 3, SLIDER_TRACK, SLIDER_TRACK,
			fieldBorder);

		// Shows the tail rather than the head once the value outgrows the box, so what was just
		// typed stays visible. Dropped a code point at a time rather than a char: halving a
		// surrogate pair leaves a stray the font draws as tofu, which never shrinks the string.
		String text = textRow.setting.value();
		int inner = field.w - 8;
		while (!text.isEmpty() && font.width(text) > inner) {
			text = text.substring(text.offsetByCodePoints(0, 1));
		}
		int textY = field.y + (field.h - TEXT_HEIGHT) / 2;
		graphics.text(font, text, field.x + 4, textY, TEXT_PRIMARY);

		if (focused && (System.currentTimeMillis() / CARET_BLINK_MILLIS) % 2 == 0) {
			int caretX = field.x + 4 + font.width(text);
			graphics.fill(caretX, textY - 1, caretX + 1, textY + TEXT_HEIGHT + 1, TEXT_PRIMARY);
		}
	}

	private void renderActionRow(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY,
		ActionRow actionRow, boolean hoverable, long now, ClickGuiMotion motion) {
		Rect bounds = actionRow.bounds;
		boolean hovered = hoverable && bounds.contains(mouseX, mouseY);
		float pulse = interactionPulse(actionRow.action, now, motion);
		int background = hovered ? BUTTON_HOVER
			: pulse > 0.0f ? lerpColor(BUTTON_BG, BUTTON_HOVER, pulse * 0.8f) : BUTTON_BG;
		roundedRect(graphics, bounds.x + 14, bounds.y + 2, bounds.w - 28, bounds.h - 6, RADIUS_SMALL, background);
		graphics.centeredText(font, textFit(font, actionRow.action.label(), bounds.w - 36),
			bounds.x + bounds.w / 2, bounds.y + (bounds.h - TEXT_HEIGHT) / 2, TEXT_PRIMARY);
	}

	private void renderScrollbar(GuiGraphicsExtractor graphics, Rect viewport, int contentHeight, int scroll) {
		if (contentHeight <= viewport.h) return;
		int trackX = viewport.x + viewport.w - SCROLLBAR_WIDTH - 3;
		int thumbHeight = Math.max(16, viewport.h * viewport.h / contentHeight);
		int travel = viewport.h - thumbHeight;
		int maxScroll = contentHeight - viewport.h;
		int appliedScroll = Math.max(0, Math.min(maxScroll, scroll));
		int thumbY = viewport.y + travel * appliedScroll / maxScroll;
		roundedRect(graphics, trackX, thumbY, SCROLLBAR_WIDTH, thumbHeight, SCROLLBAR_WIDTH / 2, SCROLLBAR);
	}

	private Rect headerRow(int x, int y) {
		return new Rect(x, contentTop(y) + PADDING, moduleWidth(), ROW_HEIGHT);
	}

	/** The clipped, scrollable area below the back header that the setting rows live in. */
	private Rect settingsViewport(int x, int panelY) {
		Rect header = headerRow(x, panelY);
		int top = header.y + header.h;
		return new Rect(x, top, Math.max(1, moduleWidth()),
			Math.max(1, panelY + panelHeight() - PADDING - top));
	}

	/** Setting rows with the current scroll already applied, so callers can hit-test them directly. */
	private List<Row> settingsRows(Module module, int x, int panelY) {
		return settingsRows(module, x, panelY, settingsScrollVisual,
			renderedExpandedColor(System.nanoTime(), motion()));
	}

	private List<Row> settingsRows(Module module, int x, int panelY, double scroll, ColorSetting expandedColor) {
		int appliedScroll = settingsAppliedScroll(module, x, panelY, scroll, expandedColor);
		Rect viewport = settingsViewport(x, panelY);
		return layoutRows(module, x, viewport.y - appliedScroll, expandedColor);
	}

	private int settingsAppliedScroll(Module module, int x, int panelY, double scroll,
		ColorSetting expandedColor) {
		Rect viewport = settingsViewport(x, panelY);
		int maxScroll = Math.max(0, settingsContentHeight(module, x, expandedColor) - viewport.h);
		return Math.max(0, Math.min(maxScroll, (int) Math.round(scroll)));
	}

	private void clampViewScroll() {
		if (openSettingsModule != null && ModuleManager.modules(selectedCategory).contains(openSettingsModule)) {
			int x = panelX() + categoryWidth();
			int max = Math.max(0, settingsContentHeight(openSettingsModule, x, expandedColorSetting)
				- settingsViewport(x, panelY()).h);
			settingsScroll = Math.max(0, Math.min(max, settingsScroll));
			settingsScrollVisual = Math.max(0, Math.min(max, settingsScrollVisual));
			return;
		}
		List<Module> modules = visibleModules(selectedCategory);
		int max = Math.max(0, gridContentHeight(modules) - gridViewport(panelX() + categoryWidth(), panelY()).h);
		gridScroll = Math.max(0, Math.min(max, gridScroll));
		gridScrollVisual = Math.max(0, Math.min(max, gridScrollVisual));
	}

	/** Measured by running the same layout, so it can never drift from what is drawn. */
	private int settingsContentHeight(Module module, int x) {
		return settingsContentHeight(module, x, expandedColorSetting);
	}

	private int settingsContentHeight(Module module, int x, ColorSetting expandedColor) {
		List<Row> rows = layoutRows(module, x, 0, expandedColor);
		int bottom = previewOffset(module);
		for (Row row : rows) {
			Rect bounds = row.bounds();
			bottom = Math.max(bottom, bounds.y + bounds.h);
		}
		return bottom;
	}

	private int colorRowHeight(ColorSetting setting) {
		return colorRowHeight(setting, expandedColorSetting);
	}

	private int colorRowHeight(ColorSetting setting, ColorSetting expandedColor) {
		return ROW_HEIGHT + (setting == expandedColor ? PICKER_HEIGHT : 0);
	}

	private static boolean usesNumberTextInput(NumberSetting setting) {
		return setting.prefersTextInput();
	}

	/** @param startY where the first row begins; already offset by the scroll position */
	private List<Row> layoutRows(Module module, int x, int startY) {
		return layoutRows(module, x, startY, expandedColorSetting);
	}

	/** @param startY where the first row begins; already offset by the scroll position */
	private List<Row> layoutRows(Module module, int x, int startY, ColorSetting expandedColor) {
		List<Row> rows = new ArrayList<>();
		int cursorY = startY + previewOffset(module);
		for (SettingGroup group : module.groups()) {
			cursorY = layoutGroup(module, group, x, cursorY, 0, rows, expandedColor);
		}
		return rows;
	}

	private static int previewOffset(Module module) {
		return module instanceof ModulePreview ? PREVIEW_HEIGHT + PREVIEW_GAP : 0;
	}

	/**
	 * Lays out one section and anything nested inside it.
	 *
	 * @param depth how deep this section is nested, which is what indents its heading
	 * @return where the next row begins
	 */
	private int layoutGroup(Module module, SettingGroup group, int x, int cursorY, int depth,
		List<Row> rows, ColorSetting expandedColor) {
		int moduleWidth = moduleWidth();
		if (!visibleGroup(module, group)) return cursorY;

		if (group.name() != null) {
			Rect bounds = new Rect(x, cursorY, moduleWidth, GROUP_HEADER_HEIGHT);
			// Lined up with the switch on a toggle row, so the column reads straight down.
			Rect toggle = group.toggle() == null || !visibleSetting(module, group.toggle()) ? null
				: new Rect(bounds.x + bounds.w - SWITCH_WIDTH - 14,
					bounds.y + (bounds.h - SWITCH_HEIGHT) / 2, SWITCH_WIDTH, SWITCH_HEIGHT);
			boolean collapsed = ClickGuiState.isCollapsed(module, group);
			rows.add(new GroupRow(group, bounds, toggle, depth, collapsed));
			cursorY += GROUP_HEADER_HEIGHT;
			// Folding a section takes everything nested in it with it, not just its own rows.
			if (collapsed) return cursorY;
		}

		for (Setting setting : group.settings()) {
			if (!visibleSetting(module, setting)) continue;
			switch (setting) {
				case ColorSetting colorSetting -> {
					int rowHeight = colorRowHeight(colorSetting, expandedColor);
					rows.add(new ColorRow(colorSetting, new Rect(x, cursorY, moduleWidth, rowHeight),
						picker(colorSetting, x, cursorY, moduleWidth, expandedColor)));
					cursorY += rowHeight;
				}
				case NumberSetting numberSetting -> {
					Rect bounds = new Rect(x, cursorY, moduleWidth, ROW_HEIGHT);
					if (usesNumberTextInput(numberSetting)) {
						int fieldWidth = Math.min(NUMBER_FIELD_WIDTH, Math.max(1, moduleWidth - 18));
						Rect field = new Rect(x + moduleWidth - fieldWidth - 14,
							cursorY + (ROW_HEIGHT - TEXT_FIELD_HEIGHT) / 2, fieldWidth, TEXT_FIELD_HEIGHT);
						rows.add(new NumberRow(numberSetting, bounds, null, field));
					} else {
						Rect slider = new Rect(x + 16, cursorY + 13,
							Math.max(1, moduleWidth - 32 - VALUE_GUTTER), 6);
						rows.add(new NumberRow(numberSetting, bounds, slider, null));
					}
					cursorY += ROW_HEIGHT;
				}
				case BooleanSetting booleanSetting -> {
					rows.add(new ToggleRow(booleanSetting, new Rect(x, cursorY, moduleWidth, ROW_HEIGHT)));
					cursorY += ROW_HEIGHT;
				}
				case ChoiceSetting choiceSetting -> {
					int fieldWidth = Math.min(CHOICE_FIELD_WIDTH, Math.max(1, moduleWidth - 18));
					Rect field = new Rect(x + moduleWidth - fieldWidth - 14,
						cursorY + (ROW_HEIGHT - CHOICE_FIELD_HEIGHT) / 2,
						fieldWidth, CHOICE_FIELD_HEIGHT);
					rows.add(new ChoiceRow(choiceSetting, new Rect(x, cursorY, moduleWidth, ROW_HEIGHT), field));
					cursorY += ROW_HEIGHT;
				}
				case TextSetting textSetting -> {
					int fieldWidth = Math.min(TEXT_FIELD_WIDTH, Math.max(1, moduleWidth - 18));
					Rect field = new Rect(x + moduleWidth - fieldWidth - 14,
						cursorY + (ROW_HEIGHT - TEXT_FIELD_HEIGHT) / 2, fieldWidth, TEXT_FIELD_HEIGHT);
					rows.add(new TextRow(textSetting, new Rect(x, cursorY, moduleWidth, ROW_HEIGHT), field));
					cursorY += ROW_HEIGHT;
				}
				case ModuleAction action -> {
					rows.add(new ActionRow(action, new Rect(x, cursorY, moduleWidth, ROW_HEIGHT)));
					cursorY += ROW_HEIGHT;
				}
			}
		}

		for (SettingGroup child : group.children()) {
			cursorY = layoutGroup(module, child, x, cursorY, depth + 1, rows, expandedColor);
		}
		return cursorY;
	}

	private boolean visibleSetting(Module module, Setting setting) {
		return setting != null && (DebugState.enabled() || !setting.isDebugOnly())
			&& module.isSettingVisible(setting);
	}

	private boolean visibleGroup(Module module, SettingGroup group) {
		if (!DebugState.enabled() && group.debugOnly()) return false;
		if (group.name() != null && group.toggle() != null && visibleSetting(module, group.toggle())) return true;
		for (Setting setting : group.settings()) {
			if (visibleSetting(module, setting)) return true;
		}
		for (SettingGroup child : group.children()) {
			if (visibleGroup(module, child)) return true;
		}
		return false;
	}

	private Picker picker(ColorSetting setting, int x, int cursorY, int moduleWidth, ColorSetting expandedColor) {
		if (setting != expandedColor) return null;
		int left = x + PICKER_INSET;
		int width = Math.max(1, moduleWidth - PICKER_INSET * 2);
		int top = cursorY + ROW_HEIGHT;
		Rect square = new Rect(left, top, width, PICKER_SQUARE_HEIGHT);
		int hueY = square.y + square.h + PICKER_GAP;
		Rect hue = new Rect(left, hueY, width, PICKER_BAR_HEIGHT);
		int alphaY = hueY + PICKER_BAR_HEIGHT + PICKER_GAP;
		Rect alpha = new Rect(left, alphaY, width, PICKER_BAR_HEIGHT);
		Rect hex = new Rect(left, alphaY + PICKER_BAR_HEIGHT + PICKER_GAP,
			Math.min(PICKER_HEX_WIDTH, width), PICKER_HEX_HEIGHT);
		return new Picker(square, hue, alpha, hex);
	}

	// ---- input --------------------------------------------------------------------------

	private boolean inputSettled() {
		if (closing || transitionFrom != null || settingsLayoutTransition != null
			|| expansionFrom != null || expansionTo != null) return false;
		ClickGuiMotion motion = motion();
		return motion == ClickGuiMotion.NONE
			|| elapsedMillis(lifecycleStartedNanos, System.nanoTime()) >= motion.lifecycleMillis();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (!inputSettled()) return true;
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

		if (left && searchRect(rightX, panelY).contains(mouseX, mouseY)) {
			searchFocused = true;
			searchSelectAll = false;
			return true;
		}
		if (left && profileHeaderRect(panelX, panelY).contains(mouseX, mouseY)) {
			String link = UpdateChecker.newerVersion() == null
				? UpdateChecker.repositoryPage() : UpdateChecker.releasesPage();
			ConfirmLinkScreen.confirmLinkNow(this, link);
			return true;
		}

		if (left && moveElementsRect(panelX, panelY).contains(mouseX, mouseY)) {
			persistView();
			markInteraction("Move Elements");
			playClick();
			beginClose(new MoveUiScreen(this));
			return true;
		}

		List<Rect> categoryRows = categoryRows(panelX, panelY);
		Category[] categories = Category.values();
		for (int i = 0; i < categories.length; i++) {
			if (left && categoryRows.get(i).contains(mouseX, mouseY)) {
				setSearchQuery("");
				ViewState target = new ViewState(categories[i], null, 0, 0, null);
				if (!currentView().equals(target)) {
					markInteraction(categories[i]);
					playClick();
					requestNavigation(target, Integer.compare(i, indexOf(selectedCategory)));
				}
				return true;
			}
		}

		List<Module> modules = visibleModules(selectedCategory);

		if (openSettingsModule != null && ModuleManager.modules(selectedCategory).contains(openSettingsModule)) {
			return settingsClicked(openSettingsModule, mouseX, mouseY, rightX, panelY, left, right);
		}

		for (CardRect card : cardRects(modules, rightX, panelY,
			transitionFrom == null ? gridScrollVisual : currentView().gridScroll)) {
			if (!card.bounds.contains(mouseX, mouseY)) continue;
			if (left && keybindRect(card.bounds).contains(mouseX, mouseY)) {
				markInteraction(card.module);
				playClick();
				if (ModuleKeybindManager.isBinding(card.module)) ModuleKeybindManager.cancelBinding();
				else ModuleKeybindManager.beginBinding(card.module);
				return true;
			}
			// The switch is the only thing that toggles the module; anywhere else on the card
			// opens its settings, on either button, so there is no hidden right-click gesture.
			if (left && switchRect(card.bounds).contains(mouseX, mouseY)) {
				markInteraction(card.module);
				playClick();
				boolean wasEnabled = card.module.isEnabled();
				card.module.toggle();
				animateToggle(card.module, wasEnabled, card.module.isEnabled());
				persistView();
				ModConfig.markDirty();
			} else if (card.module.hasSettings()) {
				markInteraction(card.module);
				playClick();
				if (!searchQuery.isBlank() && card.module.category() != selectedCategory) {
					selectedCategory = card.module.category();
				}
				requestNavigation(new ViewState(card.module.category(), card.module, gridScroll, 0, null), 1);
			}
			return true;
		}

		return super.mouseClicked(event, doubleClick);
	}

	private boolean settingsClicked(Module module, int mouseX, int mouseY, int x, int panelY,
		boolean left, boolean right) {
		Rect header = headerRow(x, panelY);
		if (left && header.contains(mouseX, mouseY)) {
			markInteraction(module);
			playClick();
			requestNavigation(new ViewState(selectedCategory, null, gridScroll, 0, null), -1);
			return true;
		}

		Rect viewport = settingsViewport(x, panelY);
		if ((!left && !right) || !viewport.contains(mouseX, mouseY)) {
			// Swallow anything else inside the panel so clicks don't fall through to the world.
			return true;
		}

		for (Row row : settingsRows(module, x, panelY)) {
			switch (row) {
				case GroupRow groupRow -> {
					// The switch is tested first: it sits inside the header, which would otherwise
					// fold the section shut under the cursor instead.
					if (groupRow.toggle != null && groupRow.toggle.contains(mouseX, mouseY)) {
						markInteraction(groupRow.group);
						playClick();
						boolean wasEnabled = groupRow.group.toggle().value();
						groupRow.group.toggle().toggle();
						animateToggle(groupRow.group.toggle(), wasEnabled, groupRow.group.toggle().value());
						persistView();
						ModConfig.markDirty();
						return true;
					}
					if (groupRow.bounds.contains(mouseX, mouseY)) {
						markInteraction(groupRow.group);
						playClick();
						List<Row> before = settingsRows(module, x, panelY);
						ClickGuiState.toggleCollapsed(module, groupRow.group);
						// A collapse can make the old scroll point past the new content. Clamp the
						// logical and visual scroll before capturing the incoming layout so the
						// transition never targets an unreachable scrollbar position.
						clampViewScroll();
						List<Row> after = settingsRows(module, x, panelY);
						startSettingsLayoutTransition(module, before, after);
						ModConfig.markDirty();
						return true;
					}
				}
				case ColorRow colorRow -> {
					Picker picker = colorRow.picker;
					if (picker != null) {
						if (picker.square.contains(mouseX, mouseY)) {
							markInteraction(colorRow.setting);
							playClick();
							draggingPicker = PickerPart.SQUARE;
							applyPicker(colorRow.setting, picker, mouseX, mouseY);
							return true;
						}
						if (picker.hue.contains(mouseX, mouseY)) {
							markInteraction(colorRow.setting);
							playClick();
							draggingPicker = PickerPart.HUE;
							applyPicker(colorRow.setting, picker, mouseX, mouseY);
							return true;
						}
						if (picker.alpha.contains(mouseX, mouseY)) {
							markInteraction(colorRow.setting);
							playClick();
							draggingPicker = PickerPart.ALPHA;
							applyPicker(colorRow.setting, picker, mouseX, mouseY);
							return true;
						}
						if (picker.hex.contains(mouseX, mouseY)) {
							markInteraction(colorRow.setting);
							playClick();
							focusedHexSetting = colorRow.setting;
							hexInput = colorRow.setting.hex();
							return true;
						}
					}
					if (new Rect(colorRow.bounds.x, colorRow.bounds.y, colorRow.bounds.w, ROW_HEIGHT).contains(mouseX, mouseY)) {
						markInteraction(colorRow.setting);
						playClick();
						setExpandedColor(expandedColorSetting == colorRow.setting ? null : colorRow.setting);
						// The picker changes the content height immediately even while its reveal is animated.
						// Clamp now so the temporary geometry and scrollbar cannot target a dead scroll point.
						clampViewScroll();
						persistView();
						return true;
					}
				}
				case NumberRow numberRow -> {
					if (numberRow.field != null && numberRow.field.contains(mouseX, mouseY)) {
						markInteraction(numberRow.setting);
						playClick();
						focusedNumberSetting = numberRow.setting;
						numberInput = numberRow.setting.display();
						numberSelectAll = true;
						return true;
					}
					if (numberRow.slider != null && numberRow.slider.contains(mouseX, mouseY)) {
						markInteraction(numberRow.setting);
						playClick();
						draggingNumberSetting = numberRow.setting;
						applyNumberSlider(numberRow.setting, numberRow.slider, mouseX);
						return true;
					}
				}
				case ToggleRow toggleRow -> {
					if (toggleRow.bounds.contains(mouseX, mouseY)) {
						markInteraction(toggleRow.setting);
						playClick();
						boolean wasEnabled = toggleRow.setting.value();
						toggleRow.setting.toggle();
						animateToggle(toggleRow.setting, wasEnabled, toggleRow.setting.value());
						persistView();
						ModConfig.markDirty();
						return true;
					}
				}
				case ChoiceRow choiceRow -> {
					if (choiceRow.bounds.contains(mouseX, mouseY)) {
						markInteraction(choiceRow.setting);
						playClick();
						if (left) choiceRow.setting.selectNext();
						else if (right) choiceRow.setting.selectPrevious();
						ModConfig.markDirty();
						return true;
					}
				}
				case TextRow textRow -> {
					if (textRow.field.contains(mouseX, mouseY)) {
						markInteraction(textRow.setting);
						playClick();
						focusedTextSetting = textRow.setting;
						return true;
					}
				}
				case ActionRow actionRow -> {
					if (actionRow.bounds.contains(mouseX, mouseY)) {
						markInteraction(actionRow.action);
						playClick();
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
		if (!inputSettled()) return true;
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
				if (row instanceof NumberRow numberRow && numberRow.setting == draggingNumberSetting
					&& numberRow.slider != null) {
					applyNumberSlider(numberRow.setting, numberRow.slider, mouseX);
					return true;
				}
			}
		}

		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (!inputSettled()) {
			draggingPicker = null;
			draggingNumberSetting = null;
			return true;
		}
		if (draggingPicker != null || draggingNumberSetting != null) {
			draggingPicker = null;
			draggingNumberSetting = null;
			persistView();
			ModConfig.markDirty();
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		int codepoint = event.codepoint();
		if (searchFocused) {
			if (Character.isISOControl(codepoint)) return true;
			if (searchSelectAll) {
				setSearchQuery("");
				searchSelectAll = false;
			}
			if (searchQuery.length() < SEARCH_MAX_LENGTH) {
				setSearchQuery(searchQuery + Character.toString(codepoint));
			}
			return true;
		}
		if (Character.isISOControl(codepoint)) {
			return focusedTextSetting != null || focusedNumberSetting != null
				|| focusedHexSetting != null || super.charTyped(event);
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
		if (focusedNumberSetting != null) {
			if (numberSelectAll) {
				numberInput = "";
				numberSelectAll = false;
			}
			boolean allowed = Character.isDigit(codepoint) || codepoint == '-' || codepoint == '+'
				|| (!focusedNumberSetting.isInteger() && (codepoint == '.' || codepoint == ','));
			if (allowed && numberInput.length() < 16) {
				numberInput += codepoint == ',' ? '.' : Character.toString(codepoint);
			}
			return true;
		}
		return super.charTyped(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (searchFocused) {
			if ((event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0 && event.key() == GLFW.GLFW_KEY_A) {
				searchSelectAll = true;
				return true;
			}
			switch (event.key()) {
				case GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_KEY_DELETE -> {
					if (searchSelectAll) {
						setSearchQuery("");
						searchSelectAll = false;
					} else if (!searchQuery.isEmpty()) {
						int end = searchQuery.offsetByCodePoints(searchQuery.length(), -1);
						setSearchQuery(searchQuery.substring(0, end));
					}
				}
				case GLFW.GLFW_KEY_ESCAPE -> {
					if (!searchQuery.isEmpty()) setSearchQuery("");
					else blurTextField();
				}
				case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> blurTextField();
				default -> {
					return true;
				}
			}
			return true;
		}
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
		if (focusedNumberSetting != null) {
			switch (event.key()) {
				case GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_KEY_DELETE -> {
					if (numberSelectAll) {
						numberInput = "";
						numberSelectAll = false;
					} else if (!numberInput.isEmpty()) {
						int end = numberInput.offsetByCodePoints(numberInput.length(), -1);
						numberInput = numberInput.substring(0, end);
					}
				}
				case GLFW.GLFW_KEY_ESCAPE -> {
					focusedNumberSetting = null;
					numberInput = "";
					numberSelectAll = false;
				}
				case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> blurTextField();
				case GLFW.GLFW_KEY_A -> {
					if ((event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0) numberSelectAll = true;
				}
				default -> {
					return true;
				}
			}
			return true;
		}
		if (focusedTextSetting != null) {
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

		if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
			if (draggingPicker != null) {
				draggingPicker = null;
				return true;
			}
			if (draggingNumberSetting != null) {
				draggingNumberSetting = null;
				return true;
			}
			if (expandedColorSetting != null) {
				expandedColorSetting = null;
				persistView();
				return true;
			}
			if (openSettingsModule != null) {
				requestNavigation(new ViewState(selectedCategory, null, gridScroll, 0, null), -1);
				persistView();
				return true;
			}
			return super.keyPressed(event);
		}
		return super.keyPressed(event);
	}

	private void blurTextField() {
		if (focusedTextSetting == null && focusedNumberSetting == null
			&& focusedHexSetting == null && !searchFocused) return;
		commitNumberInput();
		focusedTextSetting = null;
		focusedNumberSetting = null;
		numberInput = "";
		numberSelectAll = false;
		focusedHexSetting = null;
		searchFocused = false;
		searchSelectAll = false;
		hexInput = "";
		ModConfig.markDirty();
	}

	private void commitNumberInput() {
		if (focusedNumberSetting == null || numberInput == null || numberInput.isBlank()) return;
		try {
			focusedNumberSetting.setValue(Float.parseFloat(numberInput.trim()));
		} catch (NumberFormatException ignored) {
			// Keep the last valid value when the user leaves an unfinished number such as "-".
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (!inputSettled()) return true;
		int rightX = panelX() + categoryWidth();
		int notch = (int) Math.round(scrollY * CHANNEL_ROW_HEIGHT);

		if (openSettingsModule != null) {
			Rect viewport = settingsViewport(rightX, panelY());
			if (viewport.contains(mouseX, mouseY)) {
				int maxScroll = Math.max(0, settingsContentHeight(openSettingsModule, rightX,
					renderedExpandedColor(System.nanoTime(), motion())) - viewport.h);
				settingsScroll = Math.max(0, Math.min(maxScroll, settingsScroll - notch));
				persistView();
				return true;
			}
		} else {
			List<Module> modules = visibleModules(selectedCategory);
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
			rows.add(new Rect(panelX, contentTop(panelY) + PADDING + i * ROW_HEIGHT, categoryWidth, ROW_HEIGHT));
		}
		return rows;
	}

	private static int indexOf(Category category) {
		Category[] categories = Category.values();
		for (int i = 0; i < categories.length; i++) {
			if (categories[i] == category) return i;
		}
		return 0;
	}

	private static float lerp(float from, float to, float progress) {
		return from + (to - from) * progress;
	}

	private record Rect(int x, int y, int w, int h) {
		boolean contains(double px, double py) {
			return px >= x && px < x + w && py >= y && py < y + h;
		}
	}

	private record ViewState(Category category, Module module, int gridScroll, int settingsScroll,
		ColorSetting expandedColor) {
	}

	private record NavigationRequest(ViewState view, int direction) {
	}

	private record TogglePulse(boolean from, boolean to, long startedNanos) {
	}

	private record HoverPulse(long startedNanos) {
	}

	private record RowTransition(Object identity, Row from, Row to, int order) {
	}

	private record SettingsLayoutTransition(Module module, List<RowTransition> rows, Rect fromReveal,
		Rect toReveal, long startedNanos) {
		private static SettingsLayoutTransition create(Module module, List<Row> from, List<Row> to,
			long startedNanos) {
			IdentityHashMap<Object, Row> fromByIdentity = new IdentityHashMap<>();
			IdentityHashMap<Object, Row> toByIdentity = new IdentityHashMap<>();
			List<Object> identities = new ArrayList<>();

			for (Row row : from) {
				Object identity = rowIdentity(row);
				if (!fromByIdentity.containsKey(identity)) {
					fromByIdentity.put(identity, row);
					identities.add(identity);
				}
			}
			for (Row row : to) {
				Object identity = rowIdentity(row);
				if (!toByIdentity.containsKey(identity)) toByIdentity.put(identity, row);
				if (!fromByIdentity.containsKey(identity)) identities.add(identity);
			}

			List<RowTransition> rows = new ArrayList<>(identities.size());
			for (int i = 0; i < identities.size(); i++) {
				Object identity = identities.get(i);
				rows.add(new RowTransition(identity, fromByIdentity.get(identity), toByIdentity.get(identity), i));
			}
			return new SettingsLayoutTransition(module, rows,
				revealBounds(rows, true), revealBounds(rows, false), startedNanos);
		}

		private static Rect revealBounds(List<RowTransition> rows, boolean fromSide) {
			int left = Integer.MAX_VALUE;
			int top = Integer.MAX_VALUE;
			int right = Integer.MIN_VALUE;
			int bottom = Integer.MIN_VALUE;
			for (RowTransition row : rows) {
				boolean belongs = fromSide
					? row.from() != null && row.to() == null
					: row.from() == null && row.to() != null;
				if (!belongs) continue;
				Rect bounds = fromSide ? row.from().bounds() : row.to().bounds();
				left = Math.min(left, bounds.x);
				top = Math.min(top, bounds.y);
				right = Math.max(right, bounds.x + bounds.w);
				bottom = Math.max(bottom, bounds.y + bounds.h);
			}
			return left == Integer.MAX_VALUE ? null : new Rect(left, top, right - left, bottom - top);
		}
	}

	/** The four hit areas of an expanded colour row. */
	private record Picker(Rect square, Rect hue, Rect alpha, Rect hex) {
	}

	private record CardRect(Module module, Rect bounds) {
	}

	/** One laid-out line in the settings panel. */
	private sealed interface Row permits GroupRow, ColorRow, NumberRow, ToggleRow, ChoiceRow, TextRow, ActionRow {
		Rect bounds();
	}

	private static Object rowIdentity(Row row) {
		return switch (row) {
			case GroupRow groupRow -> groupRow.group;
			case ColorRow colorRow -> colorRow.setting;
			case NumberRow numberRow -> numberRow.setting;
			case ToggleRow toggleRow -> toggleRow.setting;
			case ChoiceRow choiceRow -> choiceRow.setting;
			case TextRow textRow -> textRow.setting;
			case ActionRow actionRow -> actionRow.action;
		};
	}

	/**
	 * @param toggle hit area of the switch on the heading, or null when the section has none
	 * @param depth  how deep the section is nested, which is what indents its heading
	 */
	private record GroupRow(SettingGroup group, Rect bounds, Rect toggle, int depth, boolean collapsed) implements Row {
	}

	private record ColorRow(ColorSetting setting, Rect bounds, Picker picker) implements Row {
	}

	private record NumberRow(NumberSetting setting, Rect bounds, Rect slider, Rect field) implements Row {
	}

	private record ToggleRow(BooleanSetting setting, Rect bounds) implements Row {
	}

	private record ChoiceRow(ChoiceSetting setting, Rect bounds, Rect field) implements Row {
	}

	private record TextRow(TextSetting setting, Rect bounds, Rect field) implements Row {
	}

	private record ActionRow(ModuleAction action, Rect bounds) implements Row {
	}
}
