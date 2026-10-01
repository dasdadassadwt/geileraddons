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
import geiler.addons.client.module.impl.GeneralModule;
import geiler.addons.client.module.impl.VisualModule;
import geiler.addons.client.update.UpdateChecker;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
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
	/** Width and height are sized independently so the menu follows the user's screen shape. */
	private static final float WIDTH_FRACTION = 0.9004f;
	private static final float MAX_HEIGHT_FRACTION = 0.765f;
	/** Brand band height, scaled with the panel like the approved reference. */
	private static final float HEADER_HEIGHT_FRACTION = 0.19f;
	/** Search and toolbar controls keep their existing height as the brand band grows. */
	private static final float HEADER_CONTROLS_HEIGHT_FRACTION = 0.153f;
	private static final float LOGO_HEADER_HEIGHT_FRACTION = 0.82f;
	private static final float VERSION_WIDTH_FRACTION = 0.0467f;
	private static final float VERSION_HEIGHT_FRACTION = 0.25f;
	private static final int BRAND_TRAILING_INSET = 12;
	private static final Identifier MOD_LOGO = Identifier.fromNamespaceAndPath("geileraddons", "textures/gui/geileraddons-logo.png");
	private static final int TOOLBAR_ICON_TEXTURE_SIZE = 256;
	private static final Identifier ICON_GITHUB = Identifier.fromNamespaceAndPath("geileraddons", "textures/gui/octicon-mark-github-16.png");
	private static final Identifier ICON_SETTINGS = Identifier.fromNamespaceAndPath("geileraddons", "textures/gui/octicon-gear-16.png");
	private static final Identifier ICON_THEME = Identifier.fromNamespaceAndPath("geileraddons", "textures/gui/octicon-paintbrush-16.png");
	private static final Identifier ICON_MOVE = Identifier.fromNamespaceAndPath("geileraddons", "textures/gui/move-arrows-16.png");
	private static final Identifier ICON_SEARCH = Identifier.fromNamespaceAndPath("geileraddons", "textures/gui/octicon-search-16.png");
	private static final Identifier ICON_FAVORITE_OUTLINE = Identifier.fromNamespaceAndPath("geileraddons", "textures/gui/favorite-heart-outline.png");
	private static final Identifier ICON_FAVORITE_FILLED = Identifier.fromNamespaceAndPath("geileraddons", "textures/gui/favorite-heart-filled.png");
	private static final Identifier ICON_MODULE_PLACEHOLDER = Identifier.fromNamespaceAndPath("minecraft", "textures/item/barrier.png");
	private static final int SEARCH_MAX_LENGTH = 64;
	private static final int SEARCH_TEXT_ICON_GAP = 6;
	private static final int SEARCH_TEXT_RIGHT_PADDING = 8;

	private static final int ROW_HEIGHT = 22;
	private static final int GROUP_HEADER_HEIGHT = 18;
	/** How far a nested section's heading steps in from the one it sits inside. */
	private static final int GROUP_INDENT = 10;
	private static final int PADDING = 8;
	private static final int CHANNEL_ROW_HEIGHT = 14;
	private static final int SCROLLBAR_WIDTH = 3;
	/** Empty strip to keep the scrollbar and its hit area clear of module cards. */
	private static final int SCROLLBAR_GUTTER = SCROLLBAR_WIDTH + 6;
	/** Space left for the small update notice at the foot of the category rail. */
	private static final int CATEGORY_NOTICE_HEIGHT = 14;
	private static final int FAVORITE_COLOR = 0xFFFF4B5C;

	/** Colour picker geometry, all measured from the top of the expanded area. */
	private static final int PICKER_INSET = 16;
	private static final int PICKER_SQUARE_HEIGHT = 64;
	private static final int PICKER_BAR_HEIGHT = 8;
	private static final int PICKER_GAP = 5;
	private static final int PICKER_HEX_HEIGHT = 13;
	private static final int PICKER_HEX_WIDTH = 74;
	private static final int PICKER_RGB_HEIGHT = PICKER_SQUARE_HEIGHT + PICKER_GAP + PICKER_BAR_HEIGHT
		+ PICKER_GAP + PICKER_HEX_HEIGHT + PADDING;
	private static final int PICKER_HEIGHT = PICKER_RGB_HEIGHT + PICKER_BAR_HEIGHT + PICKER_GAP;
	/** Room reserved on a setting row's right for the swatch, switch or value read-out. */
	private static final int VALUE_GUTTER = 36;

	private static final int LINE_HEIGHT = 9;
	private static final int TEXT_HEIGHT = 8;
	private static final float MODULE_TEXT_SCALE = 0.76f;
	private static final float CARD_TEXT_SCALE = 0.82f;
	private static final float CATEGORY_TEXT_SCALE = 0.76f;
	private static final float HEADER_TEXT_SCALE = 0.76f;
	private static final float CARD_TITLE_SCALE = 1.50f;
	private static final int PREVIEW_HEIGHT = 62;
	private static final int PREVIEW_GAP = 6;

	private static final float CARD_GAP_FRACTION = 0.0147f;
	private static final float CARD_INSET_FRACTION = 0.018f;
	private static final float CARD_HEIGHT_FRACTION = 0.172f;
	private static final float SWITCH_WIDTH_FRACTION = 0.12f;
	private static final float SWITCH_HEIGHT_FRACTION = 0.25f;
	/** Shared setting-row switch geometry; module cards use their own panel-relative sizing. */
	private static final int SWITCH_WIDTH = 20;
	private static final int SWITCH_HEIGHT = 11;
	private static final int KEYBIND_WIDTH = 18;
	private static final int KEYBIND_HEIGHT = 15;
	private static final int SET_BIND_WIDTH = 46;

	private static final int TEXT_FIELD_WIDTH = 64;
	private static final int TEXT_FIELD_HEIGHT = 13;
	private static final int NUMBER_FIELD_WIDTH = 64;
	private static final int CHOICE_FIELD_WIDTH = 92;
	private static final int CHOICE_FIELD_HEIGHT = 15;
	/** Caret on for this long, then off for as long again. */
	private static final long CARET_BLINK_MILLIS = 500;
	private static final String REPOSITORY_URL = "https://github.com/dasdadassadwt/geileraddons";
	enum TopBarIcon { GITHUB, SETTINGS, THEME, MOVE_ELEMENTS }
	enum ScrollTarget { CHANGELOG, CATEGORY, SETTINGS, GRID, NONE }

	static ScrollTarget scrollTarget(boolean changelogOpen, boolean categoryHovered, boolean settingsOpen,
		boolean settingsHovered, boolean gridHovered) {
		if (changelogOpen) return ScrollTarget.CHANGELOG;
		if (categoryHovered) return ScrollTarget.CATEGORY;
		if (settingsOpen) return settingsHovered ? ScrollTarget.SETTINGS : ScrollTarget.NONE;
		return gridHovered ? ScrollTarget.GRID : ScrollTarget.NONE;
	}

	/** Which part of the colour picker the mouse is currently dragging. */
	private enum PickerPart { SQUARE, HUE, ALPHA }

	private Category selectedCategory;
	private Module openSettingsModule;
	private ColorSetting expandedColorSetting;
	private PickerPart draggingPicker;
	private NumberSetting draggingNumberSetting;
	private TextSetting focusedTextSetting;
	private int textCursor;
	private boolean textSelectAll;
	private NumberSetting focusedNumberSetting;
	private String numberInput = "";
	private int numberCursor;
	private boolean numberSelectAll;
	/** The colour whose hex field has focus, and what has been typed into it so far. */
	private ColorSetting focusedHexSetting;
	private String hexInput = "";
	private int hexCursor;
	private int settingsScroll;
	private int gridScroll;
	private int categoryScroll;
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
	private int searchCursor;
	private boolean searchFocused;
	private boolean searchSelectAll;

	/** The release-notes panel: open state, its scroll, and the wrapped lines last drawn in it. */
	private boolean changelogOpen;
	private double changelogScroll;
	private String changelogCacheKey = "";
	private List<String> changelogLines = List.of();

	/** Last accepted control, used for a short tactile pulse without delaying the next action. */
	private Object lastInteraction;
	private long lastInteractionNanos;
	private final Map<Object, TogglePulse> togglePulses = new IdentityHashMap<>();
	private final Map<Module, CardSummaryLayout> cardSummaryLayouts = new IdentityHashMap<>();
	private List<ModuleSearchEntry> moduleSearchIndex;
	private List<Module> visibleModuleSnapshot = List.of();
	private Category visibleModuleCategory;
	private String visibleModuleRawQuery;
	private long visibleModuleFavoritesRevision = -1;
	private long favoriteRevision;
	private List<Category> orderedCategorySnapshot;
	private long orderedCategoryFavoritesRevision = -1;
	private Font categoryWidthCachedFont;
	private int categoryWidthCachedPanelWidth = Integer.MIN_VALUE;
	private int categoryWidthCachedPanelHeight = Integer.MIN_VALUE;
	private int categoryWidthCachedValue;
	private SettingsLayoutCache settingsLayoutCache;
	private SettingsLayoutCache positionedSettingsLayout;
	private List<Row> positionedSettingsRows;
	private int positionedSettingsStartY = Integer.MIN_VALUE;
	private long settingsLayoutRevision;

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
		categoryScroll = ClickGuiState.categoryScroll();
		gridScroll = ClickGuiState.gridScroll(selectedCategory);
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
		if (elapsedMillis(lifecycleStartedNanos, System.nanoTime()) < motion.closeMillis()) return;
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
		ClickGuiState.setCategoryScroll(categoryScroll);
		if (searchQuery.isBlank()) ClickGuiState.setGridScroll(selectedCategory, gridScroll);
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
		if (searchQuery.isBlank()) ClickGuiState.setGridScroll(from.category, from.gridScroll);
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

	private void animateToggle(Object target, boolean from, boolean to) {
		if (from == to) {
			togglePulses.remove(target);
			return;
		}
		togglePulses.put(target, new TogglePulse(from, to, System.nanoTime()));
	}

	private float togglePosition(Object target, boolean on, long now, ClickGuiMotion motion) {
		TogglePulse pulse = togglePulses.get(target);
		if (pulse == null) return on ? 1.0f : 0.0f;
		float progress = smoothToggle(elapsedMillis(pulse.startedNanos, now) / 180.0f);
		if (progress >= 1.0f) {
			togglePulses.remove(target);
			return pulse.to ? 1.0f : 0.0f;
		}
		return lerp(pulse.from ? 1.0f : 0.0f, pulse.to ? 1.0f : 0.0f, progress);
	}

	private static float smoothToggle(float progress) {
		float t = Math.max(0.0f, Math.min(1.0f, progress));
		return t * t * (3.0f - 2.0f * t);
	}

	private void setExpandedColor(ColorSetting target) {
		ColorSetting from = expandedColorSetting;
		expandedColorSetting = target;
		invalidateSettingsLayout();
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

	private int panelWidth() {
		return panelWidthForScreen(width);
	}

	private int panelHeight() {
		return panelHeightForScreen(height);
	}

	static int panelWidthForScreen(int screenWidth) {
		return Math.max(1, Math.min(Math.max(1, screenWidth), Math.round(screenWidth * WIDTH_FRACTION)));
	}

	static int panelHeightForScreen(int screenHeight) {
		return Math.max(1, Math.min(Math.max(1, screenHeight), Math.round(screenHeight * MAX_HEIGHT_FRACTION)));
	}

	static int cardHeightForPanelHeight(int panelHeight) {
		return Math.max(52, Math.round(Math.max(1, panelHeight) * CARD_HEIGHT_FRACTION));
	}

	static int cardGapForPanelWidth(int panelWidth) {
		return Math.max(7, Math.round(Math.max(1, panelWidth) * CARD_GAP_FRACTION));
	}

	static int firstGridRowIntersectingViewport(int appliedScroll, int cardHeight, int rowStep) {
		return Math.floorDiv(appliedScroll - cardHeight, Math.max(1, rowStep)) + 1;
	}

	static int lastGridRowIntersectingViewport(int appliedScroll, int viewportHeight, int rowStep) {
		return Math.floorDiv(appliedScroll + viewportHeight - 1, Math.max(1, rowStep));
	}

	static int gridInsetForPanelWidth(int panelWidth) {
		return Math.max(9, Math.round(Math.max(1, panelWidth) * 0.019f));
	}

	private int categoryWidth() {
		int panelWidth = panelWidth();
		int panelHeight = panelHeight();
		if (categoryWidthCachedFont == font && categoryWidthCachedPanelWidth == panelWidth
			&& categoryWidthCachedPanelHeight == panelHeight) return categoryWidthCachedValue;

		int longestLabel = 0;
		for (Category category : Category.values()) longestLabel = Math.max(longestLabel,
			font.width(category.displayName()));
		int logoRoom = categoryBrandRoom(panelWidth, panelHeight);
		int labelRoom = Math.round(longestLabel * CATEGORY_TEXT_SCALE) + 24;
		categoryWidthCachedFont = font;
		categoryWidthCachedPanelWidth = panelWidth;
		categoryWidthCachedPanelHeight = panelHeight;
		categoryWidthCachedValue = Math.min(Math.max(1, panelWidth - 1), Math.max(labelRoom, logoRoom));
		return categoryWidthCachedValue;
	}

	/** Full rail width needed for the logo, version badge, and their surrounding insets. */
	static int categoryBrandRoom(int panelWidth, int panelHeight) {
		int safeWidth = Math.max(1, panelWidth);
		int inset = headerInsetXForPanelWidth(safeWidth);
		int trailingInset = Math.max(BRAND_TRAILING_INSET, Math.round(inset * 0.4f));
		return inset + logoTileSizeForPanelHeight(panelHeight) + logoVersionGapForPanelWidth(safeWidth)
			+ versionBadgeWidthForPanelWidth(safeWidth) + trailingInset;
	}

	private int moduleWidth() {
		return panelWidth() - categoryWidth();
	}

	private int settingsContentWidth() {
		return Math.max(1, moduleWidth() - 2 * (gridInset() + PADDING));
	}

	private int panelX() {
		return (this.width - panelWidth()) / 2;
	}

	private int panelY() {
		return (this.height - panelHeight()) / 2;
	}

	private int contentTop(int outerPanelY) {
		return outerPanelY + headerHeight();
	}

	/**
	 * The header band, which grows by one row only while there is an update to talk about.
	 *
	 * <p>Everything below it - the category rows, the settings view's own header - is measured from
	 * this, so the extra row can never overlap the content it was added above.
	 */
	private int headerHeight() {
		return baseHeaderHeight() + (UpdateChecker.newerVersion() == null ? 0 : Math.max(12, Math.round(panelHeight() * 0.023f)));
	}

	static int baseHeaderHeightForPanelHeight(int panelHeight) {
		return Math.max(40, Math.round(Math.max(1, panelHeight) * HEADER_HEIGHT_FRACTION));
	}

	static int controlHeaderHeightForPanelHeight(int panelHeight) {
		return Math.max(40, Math.round(Math.max(1, panelHeight) * HEADER_CONTROLS_HEIGHT_FRACTION));
	}

	static int logoTileSizeForPanelHeight(int panelHeight) {
		return Math.max(36, Math.round(baseHeaderHeightForPanelHeight(panelHeight) * LOGO_HEADER_HEIGHT_FRACTION));
	}

	static int versionBadgeWidthForPanelWidth(int panelWidth) {
		return Math.max(36, Math.round(Math.max(1, panelWidth) * VERSION_WIDTH_FRACTION));
	}

	static int versionBadgeHeightForPanelHeight(int panelHeight) {
		return Math.max(18, Math.round(baseHeaderHeightForPanelHeight(panelHeight) * VERSION_HEIGHT_FRACTION));
	}

	static int logoVersionGapForPanelWidth(int panelWidth) {
		return Math.max(6, Math.round(Math.max(1, panelWidth) * 0.004f));
	}

	static int headerInsetXForPanelWidth(int panelWidth) {
		return Math.max(8, Math.round(Math.max(1, panelWidth) * 0.018f));
	}

	static int headerInsetYForPanelHeight(int panelHeight) {
		return Math.max(5, Math.round(Math.max(1, panelHeight) * 0.018f));
	}

	static int headerButtonGapForPanelWidth(int panelWidth) {
		return Math.max(5, Math.round(Math.max(1, panelWidth) * 0.012f));
	}

	static int headerButtonSizeForLayout(int panelWidth, int panelHeight) {
		int count = TopBarIcon.values().length;
		int inset = headerInsetXForPanelWidth(panelWidth);
		int gap = headerButtonGapForPanelWidth(panelWidth);
		int available = Math.max(1, panelWidth - inset * 2 - gap * (count - 1));
		int desired = Math.max(24, Math.round(controlHeaderHeightForPanelHeight(panelHeight) * 0.55f));
		return Math.max(1, Math.min(desired, available / count));
	}

	private int baseHeaderHeight() {
		return baseHeaderHeightForPanelHeight(panelHeight());
	}

	private int headerInsetX() { return headerInsetXForPanelWidth(panelWidth()); }
	private int headerButtonGap() { return headerButtonGapForPanelWidth(panelWidth()); }

	private Rect profileHeaderRect(int panelX, int panelY) {
		return profileHeaderRectForLayout(panelX, panelY, panelWidth(), panelHeight());
	}

	static Rect profileHeaderRectForLayout(int panelX, int panelY, int panelWidth, int panelHeight) {
		int size = logoTileSizeForPanelHeight(panelHeight);
		return new Rect(panelX + headerInsetXForPanelWidth(panelWidth),
			panelY + headerInsetYForPanelHeight(panelHeight), size, size);
	}

	private Rect versionRect(int panelX, int panelY) {
		return versionBadgeRectForLayout(panelX, panelY, panelWidth(), panelHeight());
	}

	static Rect versionBadgeRectForLayout(int panelX, int panelY, int panelWidth, int panelHeight) {
		Rect logo = profileHeaderRectForLayout(panelX, panelY, panelWidth, panelHeight);
		int height = versionBadgeHeightForPanelHeight(panelHeight);
		return new Rect(logo.x + logo.w + logoVersionGapForPanelWidth(panelWidth),
			logo.y + (logo.h - height) / 2,
			versionBadgeWidthForPanelWidth(panelWidth), height);
	}

	private Rect updateDownloadRect(int panelX, int panelY) {
		int scale = Math.max(1, Math.round(panelWidth() / 1357.0f * 100));
		int buttonWidth = Math.max(44, 68 * scale / 100);
		int infoWidth = Math.max(30, 38 * scale / 100);
		int x = panelX + panelWidth() - headerInsetX() - buttonWidth - 4 - infoWidth;
		return new Rect(Math.max(panelX + headerInsetX(), x),
			panelY + baseHeaderHeight() + 1, buttonWidth, Math.max(12, Math.round(panelHeight() * 0.019f)));
	}

	private Rect updateInfoRect(int panelX, int panelY) {
		Rect download = updateDownloadRect(panelX, panelY);
		return new Rect(download.x + download.w + Math.max(3, headerButtonGap() / 3), download.y,
			Math.max(30, Math.round(panelWidth() * 0.028f)), download.h);
	}

	private Rect searchRect(int panelX, int panelY) {
		return searchRectForLayout(panelX, panelY, panelWidth(), panelHeight(), categoryWidth());
	}

	static Rect searchRectForLayout(int panelX, int panelY, int panelWidth, int panelHeight, int categoryWidth) {
		Rect repository = topBarRectForLayout(panelX, panelY, panelWidth, panelHeight, TopBarIcon.GITHUB);
		int x = Math.min(panelX + panelWidth,
			panelX + categoryWidth + Math.max(6, Math.round(panelWidth * 0.006f)));
		int right = repository.x - Math.max(6, Math.round(panelWidth * 0.010f));
		int height = Math.max(18, Math.round(controlHeaderHeightForPanelHeight(panelHeight) * 0.38f));
		int availableWidth = Math.max(0, right - x);
		int minWidth = searchIconOffset(height) + searchIconSize(height);
		int width = availableWidth < minWidth ? 0 : availableWidth;
		int y = panelY + (baseHeaderHeightForPanelHeight(panelHeight) - height) / 2;
		return new Rect(x, y, width, height);
	}

	private Rect githubRect(int panelX, int panelY) {
		return topBarRect(panelX, panelY, TopBarIcon.GITHUB);
	}

	private Rect settingsGearRect(int panelX, int panelY) {
		return topBarRect(panelX, panelY, TopBarIcon.SETTINGS);
	}

	private Rect themeRect(int panelX, int panelY) {
		return topBarRect(panelX, panelY, TopBarIcon.THEME);
	}

	private Rect moveElementsRect(int panelX, int panelY) {
		return topBarRect(panelX, panelY, TopBarIcon.MOVE_ELEMENTS);
	}

	private Rect settingsBackRect(int x, int panelY) {
		return settingsHeaderSurfaceRect(x, panelY);
	}

	private Rect settingsBackLabelRect(int x, int panelY) {
		Rect surface = settingsHeaderSurfaceRect(x, panelY);
		int height = Math.min(18, Math.max(1, surface.h - 4));
		return new Rect(surface.x + 4, surface.y + (surface.h - height) / 2,
			Math.min(58, Math.max(1, surface.w - 8)), height);
	}

	private Rect settingsHeaderSurfaceRect(int x, int panelY) {
		Rect viewport = gridViewport(x, panelY);
		int headerHeight = settingsHeaderHeight();
		return new Rect(viewport.x + PADDING, viewport.y + 1,
			Math.max(1, viewport.w - PADDING * 2), Math.max(18, headerHeight - 5));
	}

	private int settingsHeaderHeight() {
		return Math.max(22, Math.round(baseHeaderHeight() * 0.24f));
	}

	private Rect topBarRect(int panelX, int panelY, TopBarIcon icon) {
		return topBarRectForLayout(panelX, panelY, panelWidth(), panelHeight(), icon);
	}

	static Rect topBarRectForLayout(int panelX, int panelY, int panelWidth, int panelHeight, TopBarIcon icon) {
		int size = headerButtonSizeForLayout(panelWidth, panelHeight);
		int gap = headerButtonGapForPanelWidth(panelWidth);
		int count = TopBarIcon.values().length;
		int totalWidth = count * size + (count - 1) * gap;
		int left = panelX + panelWidth - headerInsetXForPanelWidth(panelWidth) - totalWidth;
		int y = panelY + (baseHeaderHeightForPanelHeight(panelHeight) - size) / 2;
		return new Rect(left + icon.ordinal() * (size + gap), y, size, size);
	}

	private List<Module> visibleModules(Category category) {
		if (visibleModuleCategory == category && searchQuery.equals(visibleModuleRawQuery)
			&& visibleModuleFavoritesRevision == favoriteRevision) return visibleModuleSnapshot;
		String query = searchQuery.isBlank() ? "" : searchQuery.trim().toLowerCase(Locale.ROOT);

		List<Module> matches = new ArrayList<>();
		if (query.isEmpty()) {
			for (Module module : ModuleManager.modules(category)) {
				if (module != GeneralModule.INSTANCE && module != VisualModule.INSTANCE) matches.add(module);
			}
		} else {
			for (ModuleSearchEntry entry : moduleSearchIndex()) {
				if (entry.searchText.contains(query)) matches.add(entry.module);
			}
		}
		visibleModuleSnapshot = orderFavoriteModules(matches);
		visibleModuleCategory = category;
		visibleModuleRawQuery = searchQuery;
		visibleModuleFavoritesRevision = favoriteRevision;
		return visibleModuleSnapshot;
	}

	private List<ModuleSearchEntry> moduleSearchIndex() {
		if (moduleSearchIndex == null) {
			List<ModuleSearchEntry> entries = new ArrayList<>();
			for (Module module : ModuleManager.modules()) {
				if (module == GeneralModule.INSTANCE || module == VisualModule.INSTANCE) continue;
				entries.add(new ModuleSearchEntry(module,
					(module.name() + " " + module.description()).toLowerCase(Locale.ROOT)));
			}
			moduleSearchIndex = List.copyOf(entries);
		}
		return moduleSearchIndex;
	}

	private static List<Module> orderFavoriteModules(List<Module> modules) {
		List<Module> favorites = new ArrayList<>();
		List<Module> remaining = new ArrayList<>();
		for (Module module : modules) {
			if (ClickGuiState.isFavorite(module)) favorites.add(module);
			else remaining.add(module);
		}
		favorites.sort(Comparator.comparing(Module::name, String.CASE_INSENSITIVE_ORDER)
			.thenComparing(Module::configName));
		favorites.addAll(remaining);
		return List.copyOf(favorites);
	}

	private List<Category> orderedCategories() {
		if (orderedCategorySnapshot != null && orderedCategoryFavoritesRevision == favoriteRevision) {
			return orderedCategorySnapshot;
		}
		List<Category> favorites = new ArrayList<>();
		List<Category> remaining = new ArrayList<>();
		for (Category category : Category.values()) {
			if (ClickGuiState.isFavorite(category)) favorites.add(category);
			else remaining.add(category);
		}
		favorites.sort(Comparator.comparing(Category::displayName, String.CASE_INSENSITIVE_ORDER)
			.thenComparing(Category::name));
		favorites.addAll(remaining);
		orderedCategorySnapshot = List.copyOf(favorites);
		orderedCategoryFavoritesRevision = favoriteRevision;
		return orderedCategorySnapshot;
	}

	private void favoriteStateChanged() {
		favoriteRevision++;
	}

	private void setSearchQuery(String value) {
		String normalized = value == null ? "" : value;
		if (normalized.length() > SEARCH_MAX_LENGTH) normalized = normalized.substring(0, SEARCH_MAX_LENGTH);
		if (normalized.equals(searchQuery)) return;
		boolean leavingSearch = !searchQuery.isBlank() && normalized.isBlank();
		searchQuery = normalized;
		searchCursor = clampCursor(searchQuery, searchCursor);
		gridScroll = leavingSearch ? ClickGuiState.gridScroll(selectedCategory) : 0;
		gridScrollVisual = 0;
	}

	private void openGeneralSettings() {
		Module general = GeneralModule.INSTANCE;
		if (!ModuleManager.contains(general.category(), general)) return;
		setSearchQuery("");
		ViewState target = new ViewState(general.category(), general,
			ClickGuiState.gridScroll(general.category()), 0, null);
		if (currentView().equals(target)) return;
		markInteraction("General settings");
		playClick();
		requestNavigation(target, Integer.compare(indexOf(general.category()), indexOf(selectedCategory)));
	}

	private void openThemeSettings() {
		Module theme = VisualModule.INSTANCE;
		if (!ModuleManager.contains(theme.category(), theme)) return;
		setSearchQuery("");
		ViewState target = new ViewState(theme.category(), theme,
			ClickGuiState.gridScroll(theme.category()), 0, null);
		if (currentView().equals(target)) return;
		markInteraction("Theme settings");
		playClick();
		requestNavigation(target, Integer.compare(indexOf(theme.category()), indexOf(selectedCategory)));
	}

	private static String repositoryUrl() {
		return FabricLoader.getInstance().getModContainer("geileraddons")
			.flatMap(container -> container.getMetadata().getContact().get("sources"))
			.orElse(REPOSITORY_URL);
	}

	private void renderHeader(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY,
		int panelX, int panelY, int rightX, long now, ClickGuiMotion motion) {
		Rect logo = profileHeaderRect(panelX, panelY);
		Rect versionBounds = versionRect(panelX, panelY);
		Rect search = searchRect(panelX, panelY);
		Rect repository = githubRect(panelX, panelY);
		Rect settings = settingsGearRect(panelX, panelY);
		Rect theme = themeRect(panelX, panelY);
		Rect moveElements = moveElementsRect(panelX, panelY);
		var pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(logo.x, logo.y);
		pose.scale(logo.w / 512.0f, logo.h / 512.0f);
		graphics.blit(RenderPipelines.GUI_TEXTURED, MOD_LOGO, 0, 0, 0, 0,
			512, 512, 512, 512, 0xFFFFFFFF);
		pose.popMatrix();
		String version = UpdateChecker.currentVersion();
		if (version.isBlank()) version = "unknown";
		roundedRectBordered(graphics, versionBounds.x, versionBounds.y, versionBounds.w, versionBounds.h,
			Math.max(5, Math.round(versionBounds.h * 0.22f)), GROUP_HEADER, GROUP_HEADER, BORDER);
		String versionText = textFit(font, "v" + version,
			Math.max(1, Math.round((versionBounds.w - 12) / HEADER_TEXT_SCALE)));
		int versionTextWidth = Math.round(font.width(versionText) * HEADER_TEXT_SCALE);
		int versionTextHeight = Math.round(font.lineHeight * HEADER_TEXT_SCALE);
		drawScaledText(graphics, font, versionText,
			versionBounds.x + (versionBounds.w - versionTextWidth) / 2,
			versionBounds.y + (versionBounds.h - versionTextHeight) / 2,
			TEXT_PRIMARY, HEADER_TEXT_SCALE);

		if (search.w > 0) {
			roundedRectBordered(graphics, search.x, search.y, search.w, search.h,
				Math.max(6, Math.round(search.h * 0.22f)),
				searchFocused ? BUTTON_BG : GROUP_HEADER, searchFocused ? BUTTON_BG : GROUP_HEADER,
				searchFocused ? PANEL_HIGHLIGHT : BORDER);
		}

		renderTopBarButton(graphics, repository, TopBarIcon.GITHUB, "Open GitHub repository", mouseX, mouseY);
		renderTopBarButton(graphics, settings, TopBarIcon.SETTINGS, "General settings", mouseX, mouseY);
		renderTopBarButton(graphics, theme, TopBarIcon.THEME, "Theme settings", mouseX, mouseY);
		renderTopBarButton(graphics, moveElements, TopBarIcon.MOVE_ELEMENTS, "Move HUD elements", mouseX, mouseY);
		String newer = UpdateChecker.newerVersion();
		if (newer != null) {
			renderUpdateButton(graphics, font, updateDownloadRect(panelX, panelY), "Download", mouseX, mouseY);
			renderUpdateButton(graphics, font, updateInfoRect(panelX, panelY), "Info", mouseX, mouseY);
		}

		if (search.w > 0) {
			String queryText = searchQuery.isEmpty() ? "Search modules..." : searchQuery;
			int searchTextInset = searchTextInsetForHeight(search.h);
			int searchTextX = search.x + searchTextInset;
			int searchTextWidth = searchTextFontWidth(search.w, search.h);
			int searchTextPixels = searchTextViewportWidth(search.w, search.h);
			int queryColor = searchQuery.isEmpty() ? TEXT_MUTED : TEXT_PRIMARY;
			TextSlice querySlice = searchQuery.isEmpty()
				? new TextSlice(textFit(font, queryText, searchTextWidth), 0, 0)
				: visibleSlice(font, searchQuery, searchCursor, searchTextWidth);
			int searchTextY = search.y + (search.h - Math.round(TEXT_HEIGHT * HEADER_TEXT_SCALE)) / 2;
			graphics.enableScissor(searchTextX, search.y + 2,
				searchTextX + searchTextPixels, search.y + search.h - 2);
			if (searchFocused && searchSelectAll && !searchQuery.isEmpty() && !querySlice.text().isEmpty()) {
				int selectionWidth = Math.min(searchTextPixels,
					Math.round(font.width(querySlice.text()) * HEADER_TEXT_SCALE));
				graphics.fill(searchTextX, searchTextY - 1, searchTextX + selectionWidth,
					searchTextY + Math.round(TEXT_HEIGHT * HEADER_TEXT_SCALE) + 1, CATEGORY_SELECTED);
				queryColor = TEXT_ON_ACCENT;
			}
			drawScaledText(graphics, font, querySlice.text(), searchTextX, searchTextY,
				queryColor, HEADER_TEXT_SCALE);
			if (searchFocused && (System.currentTimeMillis() / CARET_BLINK_MILLIS) % 2 == 0) {
				if (!searchSelectAll) {
					int cursorInSlice = clampCursor(querySlice.text(), searchCursor - querySlice.start());
					int caretX = searchTextX + Math.round(font.width(querySlice.text().substring(0, cursorInSlice))
						* HEADER_TEXT_SCALE);
					graphics.fill(caretX, search.y + Math.max(3, search.h / 5), caretX + 1,
						search.y + search.h - Math.max(3, search.h / 5), TEXT_PRIMARY);
				}
			}
			graphics.disableScissor();
			renderSearchIcon(graphics, search, GuiTheme.searchIconColor());
		}
	}

	static int searchTextInsetForHeight(int fieldHeight) {
		return searchIconOffset(fieldHeight) + searchIconSize(fieldHeight) + SEARCH_TEXT_ICON_GAP;
	}

	static int searchTextViewportWidth(int fieldWidth, int fieldHeight) {
		return Math.max(1, fieldWidth - searchTextInsetForHeight(fieldHeight) - SEARCH_TEXT_RIGHT_PADDING);
	}

	static int searchTextFontWidth(int fieldWidth, int fieldHeight) {
		return Math.max(1, Math.round(searchTextViewportWidth(fieldWidth, fieldHeight) / HEADER_TEXT_SCALE));
	}

	static double textOffsetForMouse(double mouseX, int textX, float textScale) {
		return (mouseX - textX) / Math.max(0.01f, textScale);
	}

	private static int searchIconSize(int fieldHeight) {
		return Math.min(18, Math.max(12, fieldHeight - 4));
	}

	private static int searchIconOffset(int fieldHeight) {
		return Math.max(8, Math.round(fieldHeight * 0.28f));
	}

	private void renderTopBarButton(GuiGraphicsExtractor graphics, Rect bounds, TopBarIcon icon,
		String tooltip, int mouseX, int mouseY) {
		boolean hovered = bounds.contains(mouseX, mouseY);
		int surface = hovered ? BUTTON_HOVER : GROUP_HEADER;
		roundedRectBordered(graphics, bounds.x, bounds.y, bounds.w, bounds.h,
			Math.max(6, Math.round(bounds.w * 0.24f)), surface, surface,
			hovered ? PANEL_HIGHLIGHT : BORDER);
		int iconColor = hovered ? TEXT_ON_ACCENT : TEXT_PRIMARY;
		Identifier texture = switch (icon) {
			case GITHUB -> ICON_GITHUB;
			case SETTINGS -> ICON_SETTINGS;
			case THEME -> ICON_THEME;
			case MOVE_ELEMENTS -> ICON_MOVE;
		};
		int iconSize = Math.min(32, Math.max(16, bounds.w - 16));
		int iconX = bounds.x + (bounds.w - iconSize) / 2;
		int iconY = bounds.y + (bounds.h - iconSize) / 2;
		var pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(iconX, iconY);
		pose.scale(iconSize / (float) TOOLBAR_ICON_TEXTURE_SIZE,
			iconSize / (float) TOOLBAR_ICON_TEXTURE_SIZE);
		graphics.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 0, 0,
			TOOLBAR_ICON_TEXTURE_SIZE, TOOLBAR_ICON_TEXTURE_SIZE,
			TOOLBAR_ICON_TEXTURE_SIZE, TOOLBAR_ICON_TEXTURE_SIZE, iconColor);
		pose.popMatrix();
		if (hovered) graphics.setTooltipForNextFrame(Component.literal(tooltip), mouseX, mouseY);
	}

	private static void drawScaledText(GuiGraphicsExtractor graphics, Font font, String text,
		int x, int y, int color, float scale) {
		var pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(scale, scale);
		graphics.text(font, text, 0, 0, color);
		pose.popMatrix();
	}

	private void renderSearchIcon(GuiGraphicsExtractor graphics, Rect bounds, int color) {
		int size = searchIconSize(bounds.h);
		int x = bounds.x + searchIconOffset(bounds.h);
		int y = bounds.y + (bounds.h - size) / 2;
		var pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(size / (float) TOOLBAR_ICON_TEXTURE_SIZE,
			size / (float) TOOLBAR_ICON_TEXTURE_SIZE);
		graphics.blit(RenderPipelines.GUI_TEXTURED, ICON_SEARCH, 0, 0, 0, 0,
			TOOLBAR_ICON_TEXTURE_SIZE, TOOLBAR_ICON_TEXTURE_SIZE,
			TOOLBAR_ICON_TEXTURE_SIZE, TOOLBAR_ICON_TEXTURE_SIZE, color);
		pose.popMatrix();
	}

	/** Scalable vector-like marks drawn from GUI primitives instead of low-resolution PNGs. */
	private void renderMoveElementsIcon(GuiGraphicsExtractor graphics, Rect bounds, int color) {
		int u = iconUnit(bounds);
		int cx = bounds.x + bounds.w / 2;
		int cy = bounds.y + bounds.h / 2;
		graphics.fill(cx - u, cy - 6 * u, cx + u, cy + 6 * u, color);
		graphics.fill(cx - 6 * u, cy - u, cx + 6 * u, cy + u, color);
		graphics.fill(cx - 2 * u, cy - 8 * u, cx + 2 * u, cy - 6 * u, color);
		graphics.fill(cx - 8 * u, cy - 2 * u, cx - 6 * u, cy + 2 * u, color);
		graphics.fill(cx + 6 * u, cy - 2 * u, cx + 8 * u, cy + 2 * u, color);
		graphics.fill(cx - 2 * u, cy + 6 * u, cx + 2 * u, cy + 8 * u, color);
		for (int i = 0; i < 2; i++) {
			graphics.fill(cx - (4 + i * 2) * u, cy - (4 - i * 2) * u,
				cx - (2 + i * 2) * u, cy - (2 - i * 2) * u, color);
			graphics.fill(cx + (2 + i * 2) * u, cy - (4 - i * 2) * u,
				cx + (4 + i * 2) * u, cy - (2 - i * 2) * u, color);
			graphics.fill(cx - (4 + i * 2) * u, cy + (2 - i * 2) * u,
				cx - (2 + i * 2) * u, cy + (4 - i * 2) * u, color);
			graphics.fill(cx + (2 + i * 2) * u, cy + (2 - i * 2) * u,
				cx + (4 + i * 2) * u, cy + (4 - i * 2) * u, color);
		}
	}

	private void renderGitHubIcon(GuiGraphicsExtractor graphics, Rect bounds) {
		int u = iconUnit(bounds);
		int cx = bounds.x + bounds.w / 2;
		int cy = bounds.y + bounds.h / 2;
		int color = TEXT_PRIMARY;
		roundedRect(graphics, cx - 7 * u, cy - 6 * u, 14 * u, 13 * u, 6 * u, color);
		// Ears, eye cutouts, and the short curved arms suggest the Octocat silhouette at any size.
		graphics.fill(cx - 7 * u, cy - 9 * u, cx - 3 * u, cy - 5 * u, color);
		graphics.fill(cx + 3 * u, cy - 9 * u, cx + 7 * u, cy - 5 * u, color);
		graphics.fill(cx - 4 * u, cy - 2 * u, cx - 2 * u, cy, PANEL_TOP);
		graphics.fill(cx + 2 * u, cy - 2 * u, cx + 4 * u, cy, PANEL_TOP);
		graphics.fill(cx - 6 * u, cy + 5 * u, cx - 8 * u, cy + 8 * u, color);
		graphics.fill(cx + 6 * u, cy + 5 * u, cx + 8 * u, cy + 8 * u, color);
	}

	private void renderSettingsIcon(GuiGraphicsExtractor graphics, Rect bounds, int color) {
		int u = iconUnit(bounds);
		int cx = bounds.x + bounds.w / 2;
		int cy = bounds.y + bounds.h / 2;
		int tooth = Math.max(2, 3 * u);
		roundedRect(graphics, cx - 3 * u, cy - 8 * u, 6 * u, 16 * u, tooth, color);
		roundedRect(graphics, cx - 8 * u, cy - 3 * u, 16 * u, 6 * u, tooth, color);
		graphics.fill(cx - 6 * u, cy - 6 * u, cx - 3 * u, cy - 3 * u, color);
		graphics.fill(cx + 3 * u, cy - 6 * u, cx + 6 * u, cy - 3 * u, color);
		graphics.fill(cx - 6 * u, cy + 3 * u, cx - 3 * u, cy + 6 * u, color);
		graphics.fill(cx + 3 * u, cy + 3 * u, cx + 6 * u, cy + 6 * u, color);
		roundedRectBordered(graphics, cx - 4 * u, cy - 4 * u, 8 * u, 8 * u,
			4 * u, PANEL_TOP, PANEL_TOP, color, Math.max(1, u * 2));
	}

	private void renderThemeIcon(GuiGraphicsExtractor graphics, Rect bounds, int color) {
		int u = iconUnit(bounds);
		int cx = bounds.x + bounds.w / 2;
		int cy = bounds.y + bounds.h / 2;
		roundedRectBordered(graphics, cx - 8 * u, cy - 8 * u, 16 * u, 16 * u, 7 * u,
			PANEL_TOP, PANEL_TOP, color, Math.max(1, u * 2));
		int[] dots = { -4, 0, 4 };
		int[] paints = { 0xFFFF7777, 0xFFFFD05A, 0xFF60D6A6 };
		for (int i = 0; i < dots.length; i++) {
			roundedRect(graphics, cx + dots[i] * u - u, cy - 2 * u, 2 * u, 2 * u, u, paints[i]);
		}
		graphics.fill(cx - 6 * u, cy + 3 * u, cx + 6 * u, cy + 5 * u, color);
	}

	private int iconUnit(Rect bounds) { return Math.max(1, Math.round(Math.min(bounds.w, bounds.h) / 24.0f)); }

	private void renderModuleIcon(GuiGraphicsExtractor graphics, Rect bounds, Module module, int color) {
		String name = module.name().toLowerCase(Locale.ROOT);
		if (name.contains("mob esp") || name.contains("star mob")) {
			renderSkullIcon(graphics, bounds, color);
		} else if (name.contains("door")) {
			renderDoorIcon(graphics, bounds, color);
		} else if (name.contains("waypoint") || name.contains("secret")) {
			renderWaypointIcon(graphics, bounds, color);
		} else if (name.contains("guide")) {
			renderBookIcon(graphics, bounds, color);
		} else if (name.contains("experiment")) {
			renderFlaskIcon(graphics, bounds, color);
		} else {
			renderCategoryIcon(graphics, bounds, module.category(), color);
		}
	}

	private void renderSkullIcon(GuiGraphicsExtractor graphics, Rect b, int color) {
		int u = iconUnit(b), cx = b.x + b.w / 2, cy = b.y + b.h / 2;
		roundedRectBordered(graphics, cx - 8 * u, cy - 9 * u, 16 * u, 16 * u, 7 * u,
			PANEL_TOP, PANEL_TOP, color, Math.max(1, 2 * u));
		graphics.fill(cx - 6 * u, cy + 3 * u, cx - 3 * u, cy + 7 * u, color);
		graphics.fill(cx - 2 * u, cy + 5 * u, cx + 2 * u, cy + 8 * u, color);
		graphics.fill(cx + 3 * u, cy + 3 * u, cx + 6 * u, cy + 7 * u, color);
		roundedRect(graphics, cx - 5 * u, cy - 3 * u, 3 * u, 4 * u, u, color);
		roundedRect(graphics, cx + 2 * u, cy - 3 * u, 3 * u, 4 * u, u, color);
	}

	private void renderDoorIcon(GuiGraphicsExtractor graphics, Rect b, int color) {
		int u = iconUnit(b), cx = b.x + b.w / 2, cy = b.y + b.h / 2;
		roundedRectBordered(graphics, cx - 8 * u, cy - 10 * u, 16 * u, 20 * u, 2 * u,
			PANEL_TOP, PANEL_TOP, color, Math.max(1, 2 * u));
		graphics.fill(cx - 5 * u, cy - 7 * u, cx + 4 * u, cy + 8 * u, color);
		graphics.fill(cx + 2 * u, cy, cx + 4 * u, cy + 2 * u, PANEL_TOP);
		graphics.fill(cx - 10 * u, cy + 10 * u, cx + 10 * u, cy + 12 * u, color);
	}

	private void renderWaypointIcon(GuiGraphicsExtractor graphics, Rect b, int color) {
		int u = iconUnit(b), cx = b.x + b.w / 2, cy = b.y + b.h / 2;
		roundedRectBordered(graphics, cx - 8 * u, cy - 10 * u, 16 * u, 16 * u, 8 * u,
			PANEL_TOP, PANEL_TOP, color, Math.max(1, 2 * u));
		roundedRect(graphics, cx - 2 * u, cy - 4 * u, 4 * u, 4 * u, 2 * u, color);
		graphics.fill(cx - u, cy + 4 * u, cx + u, cy + 11 * u, color);
		graphics.fill(cx - 3 * u, cy + 6 * u, cx + 3 * u, cy + 8 * u, color);
	}

	private void renderBookIcon(GuiGraphicsExtractor graphics, Rect b, int color) {
		int u = iconUnit(b), cx = b.x + b.w / 2, cy = b.y + b.h / 2;
		roundedRectBordered(graphics, cx - 10 * u, cy - 8 * u, 9 * u, 17 * u, 3 * u,
			PANEL_TOP, PANEL_TOP, color, Math.max(1, 2 * u));
		roundedRectBordered(graphics, cx + u, cy - 8 * u, 9 * u, 17 * u, 3 * u,
			PANEL_TOP, PANEL_TOP, color, Math.max(1, 2 * u));
		graphics.fill(cx - u, cy - 8 * u, cx + u, cy + 10 * u, color);
	}

	private void renderFlaskIcon(GuiGraphicsExtractor graphics, Rect b, int color) {
		int u = iconUnit(b), cx = b.x + b.w / 2, cy = b.y + b.h / 2;
		graphics.fill(cx - 3 * u, cy - 10 * u, cx + 3 * u, cy - 3 * u, color);
		graphics.fill(cx - 5 * u, cy - 10 * u, cx + 5 * u, cy - 8 * u, color);
		graphics.fill(cx - 3 * u, cy - 3 * u, cx - 9 * u, cy + 7 * u, color);
		graphics.fill(cx + 3 * u, cy - 3 * u, cx + 9 * u, cy + 7 * u, color);
		graphics.fill(cx - 9 * u, cy + 5 * u, cx + 9 * u, cy + 9 * u, color);
		graphics.fill(cx - 6 * u, cy + 2 * u, cx + 6 * u, cy + 4 * u, color);
	}

	private void renderCategoryIcon(GuiGraphicsExtractor graphics, Rect b, Category category, int color) {
		int u = iconUnit(b), cx = b.x + b.w / 2, cy = b.y + b.h / 2;
		switch (category) {
			case F7 -> {
				roundedRectBordered(graphics, cx - 7 * u, cy - 7 * u, 14 * u, 14 * u, 2 * u,
					PANEL_TOP, PANEL_TOP, color, Math.max(1, 2 * u));
				graphics.fill(cx - 2 * u, cy - 6 * u, cx, cy + 7 * u, color);
				graphics.fill(cx - 6 * u, cy - 2 * u, cx + 7 * u, cy, color);
			}
			case ENCHANTING -> renderBookIcon(graphics, b, color);
			case VISUAL -> {
				roundedRectBordered(graphics, cx - 10 * u, cy - 6 * u, 20 * u, 12 * u, 6 * u,
					PANEL_TOP, PANEL_TOP, color, Math.max(1, u * 2));
				roundedRectBordered(graphics, cx - 3 * u, cy - 3 * u, 6 * u, 6 * u, 3 * u,
					PANEL_TOP, PANEL_TOP, color, Math.max(1, u * 2));
			}
			case HUNTING -> {
				graphics.fill(cx - 8 * u, cy - u, cx + 7 * u, cy + u, color);
				graphics.fill(cx - u, cy - 8 * u, cx + u, cy + 8 * u, color);
				graphics.fill(cx + 5 * u, cy - 4 * u, cx + 8 * u, cy - u, color);
				graphics.fill(cx + 5 * u, cy + u, cx + 8 * u, cy + 4 * u, color);
			}
			case FARMING, FORAGING -> {
				graphics.fill(cx - u, cy - 8 * u, cx + u, cy + 8 * u, color);
				for (int i = 0; i < 3; i++) {
					int dy = (i - 1) * 5 * u;
					roundedRect(graphics, cx - (i % 2 == 0 ? 6 : 1) * u, cy + dy,
						6 * u, 3 * u, 2 * u, color);
					roundedRect(graphics, cx + (i % 2 == 0 ? 1 : -5) * u, cy + dy - 3 * u,
						6 * u, 3 * u, 2 * u, color);
				}
			}
			case MISCELLANEOUS -> {
				for (int i = 0; i < 3; i++) {
					int y = cy - (5 - i * 5) * u;
					graphics.fill(cx - 8 * u, y, cx + 8 * u, y + 2 * u, color);
					roundedRect(graphics, cx + ((i % 2 == 0) ? 2 : -5) * u, y - u,
						4 * u, 4 * u, 2 * u, color);
				}
			}
			case DEV -> {
				graphics.fill(cx - 8 * u, cy - u, cx - 4 * u, cy + u, color);
				graphics.fill(cx - 5 * u, cy - 5 * u, cx - 3 * u, cy - 3 * u, color);
				graphics.fill(cx - 5 * u, cy + 3 * u, cx - 3 * u, cy + 5 * u, color);
				graphics.fill(cx + 4 * u, cy - u, cx + 8 * u, cy + u, color);
				graphics.fill(cx + 3 * u, cy - 5 * u, cx + 5 * u, cy - 3 * u, color);
				graphics.fill(cx + 3 * u, cy + 3 * u, cx + 5 * u, cy + 5 * u, color);
				graphics.fill(cx - u, cy - 8 * u, cx + u, cy + 8 * u, color);
			}
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
		if (openSettingsModule != null && !ModuleManager.contains(selectedCategory, openSettingsModule)) {
			openSettingsModule = null;
			expandedColorSetting = null;
		}
		clampViewScroll();
		if (closing && elapsedMillis(lifecycleStartedNanos, now) >= motion.closeMillis()) return;

		float lifecycle = lifecycleProgress(now, motion);
		float visible = closing ? 1.0f - lifecycle : lifecycle;
		float shade = motion == ClickGuiMotion.EXPRESSIVE ? 0.78f : 0.66f;
		graphics.fill(0, 0, this.width, this.height, withOpacity(0xC0000000, visible * shade));

		int panelX = panelX();
		int panelY = panelY();
		int panelWidth = panelWidth();
		int categoryWidth = categoryWidth();
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

		// The body, dividers, and perimeter are separate so the rounded stroke stays on the outside
		// edge while the two straight separator lines remain square and legible.
		roundedRect(graphics, panelX - 2, panelY + 3, panelWidth() + 4, panelHeight + 2, panelRadius() + 2,
			withOpacity(0xFF000000, 0.32f));
		roundedRect(graphics, panelX, panelY, panelWidth, panelHeight, panelRadius(),
			PANEL_TOP, PANEL_BOTTOM);
		graphics.fill(rightX - 1, panelY, rightX, panelY + panelHeight,
			withOpacity(BORDER, 0.9f));
		graphics.fill(panelX, panelY + headerHeight() - 1, panelX + panelWidth, panelY + headerHeight(),
			withOpacity(BORDER, 0.9f));
		roundedRectOutline(graphics, panelX, panelY, panelWidth, panelHeight, panelRadius(), BORDER);

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
		// Last of all and outside the panel pose: it is a modal over the finished menu, not part of it.
		renderChangelogOverlay(graphics, font, mouseX, mouseY);
	}

	private int panelRadius() { return Math.max(8, Math.round(panelWidth() * 0.014f)); }
	private int componentRadius() { return Math.max(5, Math.round(panelWidth() * 0.008f)); }

	/**
	 * One of the optional update actions, drawn on its own row below the top bar.
	 */
	private void renderUpdateButton(GuiGraphicsExtractor graphics, Font font, Rect bounds, String label,
		int mouseX, int mouseY) {
		boolean hovered = bounds.contains(mouseX, mouseY);
		int surface = hovered ? BUTTON_HOVER : BUTTON_BG;
		roundedRectBordered(graphics, bounds.x, bounds.y, bounds.w, bounds.h, RADIUS_SMALL,
			surface, surface, BORDER);
		// The hovered surface is the accent, so the label has to switch to the colour that reads on it.
		graphics.centeredText(font, textFit(font, label, Math.max(1, bounds.w - 8)),
			bounds.x + bounds.w / 2, bounds.y + (bounds.h - TEXT_HEIGHT) / 2,
			hovered ? TEXT_ON_ACCENT : TEXT_PRIMARY);
	}

	/**
	 * The release-notes panel.
	 *
	 * <p>Drawn over everything, inside the Click GUI rather than as a second screen, so the update can
	 * be read without losing the menu behind it. The body is plain text with no click events: the only
	 * interactive parts are closing it and scrolling it.
	 */
	private void renderChangelogOverlay(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
		if (!changelogOpen) return;
		Rect panel = changelogPanelRect();
		graphics.fill(0, 0, width, height, withOpacity(0xFF000000, 0.45f));
		roundedRectBordered(graphics, panel.x, panel.y, panel.w, panel.h, RADIUS, PANEL_TOP, PANEL_BOTTOM, BORDER);

		String newer = UpdateChecker.newerVersion();
		String title = "GeilerAddons v" + (newer == null ? "?" : newer) + " is available";
		String current = "You have v" + (UpdateChecker.currentVersion().isBlank()
			? "?" : UpdateChecker.currentVersion());
		graphics.text(font, textFit(font, title, Math.max(1, panel.w - 24)), panel.x + 10, panel.y + 8, TEXT_WARN);
		graphics.text(font, textFit(font, current, Math.max(1, panel.w - 24)), panel.x + 10, panel.y + 19, TEXT_MUTED);

		Rect viewport = changelogViewport(panel);
		List<String> lines = changelogLines();
		int contentHeight = lines.size() * LINE_HEIGHT;
		int maxScroll = Math.max(0, contentHeight - viewport.h);
		changelogScroll = Math.max(0, Math.min(maxScroll, changelogScroll));
		graphics.enableScissor(viewport.x, viewport.y, viewport.x + viewport.w, viewport.y + viewport.h);
		for (int index = 0; index < lines.size(); index++) {
			int lineY = viewport.y + index * LINE_HEIGHT - (int) changelogScroll;
			if (lineY + LINE_HEIGHT < viewport.y || lineY > viewport.y + viewport.h) continue;
			graphics.text(font, lines.get(index), viewport.x, lineY, TEXT_SECONDARY);
		}
		graphics.disableScissor();
		renderScrollbar(graphics, viewport, contentHeight, (int) changelogScroll);
		graphics.text(font, "Escape or click outside to close", panel.x + 10, panel.y + panel.h - 13, TEXT_MUTED);
	}

	private Rect changelogPanelRect() {
		int panelW = Math.min(Math.max(240, panelWidth() - 40), Math.max(200, width - 24));
		int panelH = Math.min(Math.max(120, panelHeight() + 40), Math.max(100, height - 24));
		return new Rect((width - panelW) / 2, (height - panelH) / 2, panelW, panelH);
	}

	private Rect changelogViewport(Rect panel) {
		return new Rect(panel.x + 10, panel.y + 32, Math.max(1, panel.w - 20), Math.max(1, panel.h - 50));
	}

	/** Wrapped at the current panel width; recomputed only when the width or the release changes. */
	private List<String> changelogLines() {
		String notes = UpdateChecker.releaseNotes();
		Rect viewport = changelogViewport(changelogPanelRect());
		String key = width + "x" + height + "|" + UpdateChecker.newerVersion() + "|" + notes.length();
		if (key.equals(changelogCacheKey)) return changelogLines;
		List<String> wrapped = new ArrayList<>();
		for (String line : notes.split("\n", -1)) {
			if (line.isEmpty()) {
				wrapped.add("");
				continue;
			}
			String remaining = line;
			while (font.width(remaining) > viewport.w) {
				int split = remaining.length();
				while (split > 1 && font.width(remaining.substring(0, split)) > viewport.w) split--;
				wrapped.add(remaining.substring(0, split));
				remaining = remaining.substring(split);
			}
			wrapped.add(remaining);
		}
		changelogCacheKey = key;
		changelogLines = List.copyOf(wrapped);
		return changelogLines;
	}

	private void openChangelog() {
		changelogOpen = true;
		changelogScroll = 0;
		changelogCacheKey = "";
	}

	private void closeChangelog() {
		changelogOpen = false;
		changelogScroll = 0;
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
		// The banner surface is the accent, which is what the second line below already accounts for.
		graphics.text(font, "Listening for a hotkey: " + textFit(font, target, boxW - 170),
			boxX + 8, boxY + 5, TEXT_ON_ACCENT);
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
		// Establish the narrower clip before drawing the category rail.
		int contentTop = contentTop(panelY);
		graphics.enableScissor(panelX, contentTop, panelX + categoryWidth(), panelY + panelHeight());
		// Drawn from the visible rows so the clip and the click test agree; the full row list is
		// still what the selection transition below interpolates between.
		List<Rect> allRows = categoryRows(panelX, panelY);
		List<Category> categories = orderedCategories();
		boolean moving = transitionFrom != null && transitionFrom.category != selectedCategory;
		if (moving) {
			Rect from = allRows.get(indexOf(transitionFrom.category));
			Rect to = allRows.get(indexOf(selectedCategory));
			float progress = transitionProgress(now, motion);
			int y = Math.round(lerp(from.y, to.y, progress)) - categoryScroll;
			roundedRect(graphics, to.x + 7, y, to.w - 7, to.h, componentRadius(),
				withOpacity(CATEGORY_SELECTED, 0.19f));
		}
		// Only the rows inside the same strip the click test uses are drawn.
		int viewportBottom = contentTop + categoryViewportHeight(panelY);
		for (int i = 0; i < categories.size(); i++) {
			Rect full = allRows.get(i);
			int y = full.y() - categoryScroll;
			if (y + full.h() <= contentTop || y >= viewportBottom) continue;
			Rect row = new Rect(full.x(), y, full.w(), full.h());
			Category category = categories.get(i);
			boolean selected = category == selectedCategory;
			boolean hovered = row.contains(mouseX, mouseY);
			if (!moving && selected) {
				roundedRect(graphics, row.x + 7, row.y, row.w - 7, row.h, componentRadius(),
					withOpacity(CATEGORY_SELECTED, 0.19f));
			} else if (hovered) {
				roundedRect(graphics, row.x + 7, row.y, row.w - 7, row.h, componentRadius(), CATEGORY_HOVER);
			}
			int color = selected ? SLIDER_FILL : TEXT_PRIMARY;
			int labelX = row.x + Math.max(11, Math.round(row.w * 0.10f));
			Rect favorite = categoryFavoriteRect(row);
			int labelWidth = Math.max(1, favorite.x - labelX - 5);
			String label = textFit(font, category.displayName(),
				Math.max(1, Math.round(labelWidth / CATEGORY_TEXT_SCALE)));
			drawScaledText(graphics, font, label, labelX,
				row.y + (row.h - Math.round(TEXT_HEIGHT * CATEGORY_TEXT_SCALE)) / 2,
				color, CATEGORY_TEXT_SCALE);
			renderFavoriteHeart(graphics, favorite, ClickGuiState.isFavorite(category),
				favorite.contains(mouseX, mouseY));
		}

		// The update notice stays in the category rail, clipped so it cannot cover a module card.
		String notice = UpdateChecker.bannerText();
		if (notice != null) {
			int available = Math.max(1, categoryWidth() - 28);
			String text = textFit(font, notice, available);
			int y = panelY + panelHeight() - PADDING - TEXT_HEIGHT;
			graphics.text(font, text, panelX + 14, y, TEXT_WARN);
		}
		graphics.disableScissor();
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
		if (view.module != null && ModuleManager.contains(view.category, view.module)) {
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
		List<CardRect> cards = visibleCardRects(modules, x, panelY, scroll);
		boolean insideViewport = viewport.contains(mouseX, mouseY);

		graphics.enableScissor(viewport.x, viewport.y, viewport.x + viewport.w, viewport.y + viewport.h);
		for (CardRect card : cards) {
			if (card.bounds.y + card.bounds.h <= viewport.y || card.bounds.y >= viewport.y + viewport.h) continue;
			boolean cardHoverable = hoverable && insideViewport;
			float entrance = entering ? cardEntrance(card.index, entranceStart, now, motion) : 1.0f;
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
		boolean enabled = module.showsToggleControl() && module.isEnabled();
		boolean hovered = hoverable && bounds.contains(mouseX, mouseY);
		Rect toggle = cardSwitchRect(bounds);
		Rect keybind = module.showsKeybindControl() ? keybindRect(module, bounds) : null;
		boolean keybindHovered = hoverable && keybind != null && keybind.contains(mouseX, mouseY);
		var pose = graphics.pose();
		pose.pushMatrix();

		float togglePosition = togglePosition(module, enabled, now, motion);
		int background = lerpColor(CARD_BG, CARD_BG_ENABLED, togglePosition);
		int hoverBackground = lerpColor(CARD_BG_HOVER, CARD_BG_ENABLED, togglePosition);
		int border = lerpColor(CARD_BORDER, CARD_BORDER_ENABLED, togglePosition);
		if (hovered) {
			background = lerpColor(background, hoverBackground, 0.85f);
			border = lerpColor(border, PANEL_HIGHLIGHT, 0.82f);
		}
		float pulse = interactionPulse(module, now, motion);
		if (pulse > 0.0f) {
			background = lerpColor(background, CARD_BG_HOVER, pulse * 0.65f);
			border = lerpColor(border, PANEL_HIGHLIGHT, pulse);
		}
		roundedRectBordered(graphics, bounds.x, bounds.y, bounds.w, bounds.h, componentRadius(), background, background, border);

		int inset = cardInset();
		int iconSize = Math.max(16, Math.min(Math.round(bounds.h * 0.70f), Math.round(bounds.w * 0.24f)));
		int iconX = bounds.x + inset + Math.max(1, Math.round(bounds.w * 0.02f));
		Rect icon = new Rect(iconX, bounds.y + (bounds.h - iconSize) / 2, iconSize, iconSize);
		Identifier moduleIcon = ModuleIconTextures.forModule(module.name());
		Identifier iconTexture = moduleIcon == null ? ICON_MODULE_PLACEHOLDER : moduleIcon;
		int iconSourceSize = ModuleIconTextures.sourceSize(module.name());
		var itemPose = graphics.pose();
		itemPose.pushMatrix();
		itemPose.translate(icon.x, icon.y);
		itemPose.scale(iconSize / (float) iconSourceSize, iconSize / (float) iconSourceSize);
		if (moduleIcon != null && ModuleIconTextures.isTintable(module.name())) {
			graphics.blit(RenderPipelines.GUI_TEXTURED, iconTexture, 0, 0, 0, 0,
				iconSourceSize, iconSourceSize, iconSourceSize, iconSourceSize,
				VisualModule.INSTANCE.iconColorSetting().argb());
		} else {
			graphics.blit(RenderPipelines.GUI_TEXTURED, iconTexture, 0, 0, 0, 0,
				iconSourceSize, iconSourceSize, iconSourceSize, iconSourceSize);
		}
		itemPose.popMatrix();

		int textX = icon.x + icon.w + Math.max(8, Math.round(bounds.w * 0.022f));
		Rect favorite = moduleFavoriteRect(module, bounds);
		int textRight = Math.min(favorite.x - Math.max(5, Math.round(bounds.w * 0.015f)),
			module.showsToggleControl() ? toggle.x - Math.max(8, Math.round(bounds.w * 0.025f))
			: bounds.x + bounds.w - Math.max(8, Math.round(bounds.w * 0.035f)));
		int textWidth = Math.max(1, textRight - textX);
		int titleY = bounds.y + Math.max(8, Math.round(bounds.h * 0.17f));
		cardTitleFit(graphics, font, module.name(), textX, titleY, textWidth, TEXT_PRIMARY);
		int titleHeight = Math.max(TEXT_HEIGHT,
			(int) Math.ceil(LINE_HEIGHT * cardTitleScale(font, module.name(), textWidth)));
		String summary = searchQuery.isBlank() ? module.description()
			: module.category().displayName() + " · " + module.description();
		int scaledWidth = cardTextWidth(textWidth);
		List<FormattedCharSequence> summaryLines = cardSummaryLines(font, module, summary, scaledWidth);
		int lineLimit = bounds.h >= 90 ? 2 : 1;
		int summaryY = titleY + titleHeight + 4;
		int summaryLineHeight = Math.max(9, Math.round(bounds.h * 0.095f));
		for (int line = 0; line < Math.min(lineLimit, summaryLines.size()); line++) {
			cardText(graphics, font, summaryLines.get(line), textX, summaryY + line * summaryLineHeight, TEXT_MUTED);
		}

		String status = GeneralModule.INSTANCE.availabilityNotices().value() ? module.inactiveReason() : null;
		if (status != null && bounds.h >= 90) {
			int statusY = summaryY + Math.min(lineLimit, summaryLines.size()) * summaryLineHeight + 3;
			if (keybind == null || statusY + TEXT_HEIGHT < keybind.y) {
				cardTextFit(graphics, font, status, textX, statusY, cardTextWidth(textWidth), TEXT_WARN);
			}
		}

		if (module.showsKeybindControl()) {
			boolean binding = ModuleKeybindManager.isBinding(module);
			boolean unbound = !module.keybind().isBound();
			int keybindBackground = binding || keybindHovered ? BUTTON_HOVER
				: unbound ? GROUP_HEADER : BUTTON_BG;
			roundedRectBordered(graphics, keybind.x, keybind.y, keybind.w, keybind.h, componentRadius(),
				keybindBackground, keybindBackground, binding || keybindHovered ? PANEL_HIGHLIGHT : BORDER);
			String label = binding ? "…" : unbound ? "none" : module.keybind().displayName();
			moduleCenteredText(graphics, font, textFit(font, label, keybind.w - 4),
				keybind.x + keybind.w / 2, keybind.y + (keybind.h - TEXT_HEIGHT) / 2,
				unbound && !binding ? TEXT_SECONDARY : TEXT_PRIMARY);
			if (keybindHovered && module == geiler.addons.client.module.impl.DungeonHelperModule.INSTANCE)
				graphics.setTooltipForNextFrame(Component.literal("Press this key to open the Dungeon Guide editor."), mouseX, mouseY);
		}

		if (module.showsToggleControl()) {
			toggleSwitch(graphics, toggle.x, toggle.y, toggle.w, toggle.h, togglePosition);
		}
		renderFavoriteHeart(graphics, favorite, ClickGuiState.isFavorite(module),
			hoverable && favorite.contains(mouseX, mouseY));
		pose.popMatrix();
	}

	private Rect categoryFavoriteRect(Rect row) {
		int hitSize = Math.max(20, Math.min(26, row.h - 4));
		return new Rect(row.x + row.w - hitSize - 4,
			row.y + (row.h - hitSize) / 2, hitSize, hitSize);
	}

	private Rect moduleFavoriteRect(Module module, Rect card) {
		Rect toggle = module.showsToggleControl() ? cardSwitchRect(card) : null;
		return moduleFavoriteHitRect(card, module.showsToggleControl(), toggle == null ? -1 : toggle.y);
	}

	static Rect moduleFavoriteHitRect(Rect card, boolean showsToggleControl, int switchTop) {
		int hitSize = Math.max(20, Math.min(26, Math.round(card.h * 0.23f)));
		int rightInset = Math.max(6, Math.round(card.w * 0.025f));
		int topInset = Math.max(3, Math.round(card.h * 0.06f));
		int top = card.y + topInset;
		int left = card.x + card.w - rightInset - hitSize;
		if (showsToggleControl) {
			// Keep the heart, switch and keybind on one visual axis. Compact cards reduce the
			// heart's hit target vertically so it stays separate from the switch below it.
			Rect toggle = cardSwitchRect(card);
			hitSize = Math.min(hitSize, Math.max(12, switchTop - card.y - 2));
			left = toggle.x + toggle.w / 2 - hitSize / 2;
			top = Math.max(card.y, Math.min(top, switchTop - hitSize - 2));
		}
		return new Rect(left, top, hitSize, hitSize);
	}

	private void renderFavoriteHeart(GuiGraphicsExtractor graphics, Rect bounds, boolean favorite,
		boolean hovered) {
		int iconSize = Math.max(11, Math.min(17, Math.round(Math.min(bounds.w, bounds.h) * 0.64f)));
		int left = bounds.x + (bounds.w - iconSize) / 2;
		int top = bounds.y + (bounds.h - iconSize) / 2;
		int color = favorite ? FAVORITE_COLOR : hovered ? TEXT_PRIMARY : TEXT_MUTED;
		Identifier icon = favorite ? ICON_FAVORITE_FILLED : ICON_FAVORITE_OUTLINE;
		var pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(left, top);
		pose.scale(iconSize / 96.0f, iconSize / 96.0f);
		graphics.blit(RenderPipelines.GUI_TEXTURED, icon, 0, 0, 0, 0, 96, 96, 96, 96, color);
		pose.popMatrix();
	}

	private int cardInset() { return Math.max(8, Math.round(panelWidth() * CARD_INSET_FRACTION)); }
	private int cardGap() { return cardGapForPanelWidth(panelWidth()); }
	private static int cardSwitchWidth(Rect card) { return Math.max(28, Math.round(card.w * 0.10f)); }
	private static int cardSwitchHeight(Rect card) { return Math.max(12, Math.round(card.h * 0.21f)); }

	static Rect cardSwitchRect(Rect card) {
		int width = cardSwitchWidth(card);
		int height = cardSwitchHeight(card);
		int inset = Math.max(8, Math.round(card.w * 0.035f));
		return new Rect(card.x + card.w - inset - width,
			card.y + Math.round(card.h * 0.40f) - height / 2, width, height);
	}

	private int keybindControlWidth(Module module) {
		return module.keybind().isBound() || ModuleKeybindManager.isBinding(module)
			? KEYBIND_WIDTH : SET_BIND_WIDTH;
	}

	private Rect keybindRect(Module module, Rect card) {
		int controlWidth = keybindControlWidth(module);
		int scaledWidth = Math.min(controlWidth, Math.max(1, Math.round(card.w * 0.18f)));
		int height = Math.max(14, Math.min(KEYBIND_HEIGHT, Math.round(card.h * 0.19f)));
		if (module.showsToggleControl()) {
			Rect toggle = cardSwitchRect(card);
			return new Rect(toggle.x + (toggle.w - scaledWidth) / 2,
				toggle.y + toggle.h + Math.max(2, Math.round(card.h * 0.035f)), scaledWidth, height);
		}
		int inset = Math.max(8, Math.round(card.w * 0.035f));
		return new Rect(card.x + card.w - inset - scaledWidth,
			card.y + card.h - Math.max(4, Math.round(card.h * 0.10f)) - height, scaledWidth, height);
	}

	private static String oneLine(String text) {
		return text == null ? "" : text.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
	}

	/** First sentence is the compact description shown directly on the card. */
	private static String conciseSummary(String text) {
		String summary = oneLine(text);
		int sentenceEnd = summary.indexOf(". ");
		return sentenceEnd > 0 ? summary.substring(0, sentenceEnd + 1) : summary;
	}

	/** Keep description normalization and line breaking out of the per-frame render path. */
	private List<FormattedCharSequence> cardSummaryLines(Font font, Module module, String summary, int width) {
		CardSummaryLayout cached = cardSummaryLayouts.get(module);
		if (cached != null && cached.font == font && cached.source.equals(summary) && cached.width == width) {
			return cached.lines;
		}
		List<FormattedCharSequence> lines = List.copyOf(font.split(Component.literal(conciseSummary(summary)), width));
		cardSummaryLayouts.put(module, new CardSummaryLayout(font, summary, width, lines));
		return lines;
	}

	private static void moduleTextFit(GuiGraphicsExtractor graphics, Font font, String text,
		int x, int y, int availableWidth, int color) {
		if (text == null || text.isEmpty() || availableWidth <= 0) return;
		float scale = Math.min(MODULE_TEXT_SCALE, availableWidth / (float) Math.max(1, font.width(text)));
		var pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(scale, scale);
		graphics.text(font, text, 0, 0, color);
		pose.popMatrix();
	}

	private static void cardTextFit(GuiGraphicsExtractor graphics, Font font, String text,
		int x, int y, int availableWidth, int color) {
		if (text == null || text.isEmpty() || availableWidth <= 0) return;
		float scale = Math.min(CARD_TEXT_SCALE, availableWidth / (float) Math.max(1, font.width(text)));
		var pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(scale, scale);
		graphics.text(font, text, 0, 0, color);
		pose.popMatrix();
	}

	private static void cardTitleFit(GuiGraphicsExtractor graphics, Font font, String text,
		int x, int y, int availableWidth, int color) {
		if (text == null || text.isEmpty() || availableWidth <= 0) return;
		float scale = cardTitleScale(font, text, availableWidth);
		var pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(scale, scale);
		graphics.text(font, text, 0, 0, color);
		pose.popMatrix();
	}

	private static float cardTitleScale(Font font, String text, int availableWidth) {
		return Math.min(CARD_TITLE_SCALE,
			availableWidth / (float) Math.max(1, font.width(text)));
	}

	private static int cardTextWidth(int pixels) {
		return Math.max(1, (int) Math.ceil(pixels / CARD_TEXT_SCALE));
	}

	private static void cardText(GuiGraphicsExtractor graphics, Font font, FormattedCharSequence text,
		int x, int y, int color) {
		var pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(CARD_TEXT_SCALE, CARD_TEXT_SCALE);
		graphics.text(font, text, 0, 0, color);
		pose.popMatrix();
	}

	private static String textFit(Font font, String text, int width) {
		if (text == null || width <= 0) return "";
		if (font.width(text) <= width) return text;
		String ellipsis = "…";
		int available = Math.max(0, width - font.width(ellipsis));
		return font.plainSubstrByWidth(text, available) + ellipsis;
	}

	private static int moduleTextWidth(int pixels) {
		return Math.max(1, (int) Math.ceil(pixels / MODULE_TEXT_SCALE));
	}

	private static void moduleText(GuiGraphicsExtractor graphics, Font font, String text,
		int x, int y, int color) {
		var pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(MODULE_TEXT_SCALE, MODULE_TEXT_SCALE);
		graphics.text(font, text, 0, 0, color);
		pose.popMatrix();
	}

	private static void moduleText(GuiGraphicsExtractor graphics, Font font, FormattedCharSequence text,
		int x, int y, int color) {
		var pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(MODULE_TEXT_SCALE, MODULE_TEXT_SCALE);
		graphics.text(font, text, 0, 0, color);
		pose.popMatrix();
	}

	private static void moduleCenteredText(GuiGraphicsExtractor graphics, Font font, String text,
		int centerX, int y, int color) {
		var pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(centerX, y);
		pose.scale(MODULE_TEXT_SCALE, MODULE_TEXT_SCALE);
		graphics.centeredText(font, text, 0, 0, color);
		pose.popMatrix();
	}

	private static int clampCursor(String text, int cursor) {
		int bounded = Math.max(0, Math.min(text.length(), cursor));
		if (bounded > 0 && bounded < text.length()
			&& Character.isLowSurrogate(text.charAt(bounded))
			&& Character.isHighSurrogate(text.charAt(bounded - 1))) {
			return bounded - 1;
		}
		return bounded;
	}

	private static int moveCursor(String text, int cursor, int direction) {
		cursor = clampCursor(text, cursor);
		if (direction < 0 && cursor > 0) return text.offsetByCodePoints(cursor, -1);
		if (direction > 0 && cursor < text.length()) return text.offsetByCodePoints(cursor, 1);
		return cursor;
	}

	private static TextSlice visibleSlice(Font font, String text, int cursor, int width) {
		if (text == null || text.isEmpty() || width <= 0) return new TextSlice("", 0, 0);
		cursor = clampCursor(text, cursor);
		int start = cursor;
		int leftBudget = Math.max(1, cursor == text.length() ? width : width / 2);
		while (start > 0) {
			int previous = text.offsetByCodePoints(start, -1);
			if (font.width(text.substring(previous, cursor)) > leftBudget) break;
			start = previous;
		}
		int end = cursor;
		while (end < text.length()) {
			int next = text.offsetByCodePoints(end, 1);
			if (font.width(text.substring(start, next)) > width) break;
			end = next;
		}
		return new TextSlice(text.substring(start, end), start, end);
	}

	private static int cursorAtMouse(Font font, String text, double mouseX, int textX,
		int width, int currentCursor) {
		return cursorAtMouse(font, text, mouseX, textX, width, currentCursor, 1.0f);
	}

	private static int cursorAtMouse(Font font, String text, double mouseX, int textX,
		int width, int currentCursor, float textScale) {
		float scale = Math.max(0.01f, textScale);
		TextSlice slice = visibleSlice(font, text, currentCursor, Math.max(1, Math.round(width / scale)));
		double relativeX = textOffsetForMouse(mouseX, textX, scale);
		int cursor = slice.start;
		int previousWidth = 0;
		while (cursor < slice.end) {
			int next = text.offsetByCodePoints(cursor, 1);
			int nextWidth = font.width(text.substring(slice.start, next));
			if (relativeX < (previousWidth + nextWidth) / 2.0) return cursor;
			cursor = next;
			previousWidth = nextWidth;
		}
		return slice.end;
	}

	private int cardHeight(List<Module> modules) { return cardHeightForPanelHeight(panelHeight()); }

	private int cardColumns() {
		return cardColumnsForModuleWidth(moduleWidth());
	}

	static int cardColumnsForModuleWidth(int moduleWidth) {
		return moduleWidth >= 720 ? 3 : 2;
	}

	private int cardWidth() {
		int columns = cardColumns();
		int inset = gridInset();
		int viewportWidth = Math.max(1, moduleWidth() - inset * 2);
		return cardWidthForGridViewport(viewportWidth, columns, cardGap());
	}

	static int gridCardContentWidth(int viewportWidth) {
		return Math.max(1, viewportWidth - SCROLLBAR_GUTTER);
	}

	static int cardWidthForGridViewport(int viewportWidth, int columns, int gap) {
		int count = Math.max(1, columns);
		int available = gridCardContentWidth(viewportWidth) - Math.max(0, gap) * (count - 1);
		return Math.max(1, available / count);
	}

	private int gridInset() { return gridInsetForPanelWidth(panelWidth()); }

	private Rect gridViewport(int x, int panelY) {
		int inset = gridInset();
		return new Rect(x + inset, contentTop(panelY) + inset,
			Math.max(1, moduleWidth() - inset * 2),
			Math.max(1, panelHeight() - headerHeight() - inset * 2));
	}

	private int gridContentHeight(List<Module> modules) {
		if (modules.isEmpty()) return 0;
		int columns = cardColumns();
		int rows = (modules.size() + columns - 1) / columns;
		return rows * cardHeight(modules) + (rows - 1) * cardGap();
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
		int columns = cardColumns();
		List<CardRect> cards = new ArrayList<>();
		for (int i = 0; i < modules.size(); i++) {
			int column = i % columns;
			int row = i / columns;
			int cardX = viewport.x + column * (cardWidth + cardGap());
			int cardY = viewport.y + row * (cardHeight + cardGap()) - appliedScroll;
			cards.add(new CardRect(modules.get(i), new Rect(cardX, cardY, cardWidth, cardHeight), i));
		}
		return cards;
	}

	/** Builds geometry only for rows that can intersect the clipped module grid. */
	private List<CardRect> visibleCardRects(List<Module> modules, int x, int panelY, double scroll) {
		Rect viewport = gridViewport(x, panelY);
		int cardHeight = cardHeight(modules);
		int columns = cardColumns();
		int rowGap = cardGap();
		int rowStep = cardHeight + rowGap;
		int rowCount = (modules.size() + columns - 1) / columns;
		if (rowCount == 0) return List.of();

		int maxScroll = Math.max(0, gridContentHeight(modules) - viewport.h);
		int appliedScroll = Math.max(0, Math.min(maxScroll, (int) Math.round(scroll)));
		int firstVisibleRow = Math.max(0,
			firstGridRowIntersectingViewport(appliedScroll, cardHeight, rowStep));
		int lastVisibleRow = Math.min(rowCount - 1,
			lastGridRowIntersectingViewport(appliedScroll, viewport.h, rowStep));
		int cardWidth = cardWidth();
		List<CardRect> cards = new ArrayList<>(Math.max(0, lastVisibleRow - firstVisibleRow + 1) * columns);
		for (int row = firstVisibleRow; row <= lastVisibleRow; row++) {
			for (int column = 0; column < columns; column++) {
				int index = row * columns + column;
				if (index >= modules.size()) break;
				int cardX = viewport.x + column * (cardWidth + rowGap);
				int cardY = viewport.y + row * rowStep - appliedScroll;
				cards.add(new CardRect(modules.get(index), new Rect(cardX, cardY, cardWidth, cardHeight), index));
			}
		}
		return cards;
	}

	// ---- settings view ------------------------------------------------------------------

	private void renderSettingsView(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY,
		int x, int y, Module module, double scroll, ColorSetting expandedColor, boolean hoverable,
		long now, ClickGuiMotion motion) {
		Rect headerSurface = settingsHeaderSurfaceRect(x, y);
		Rect back = settingsBackRect(x, y);
		Rect backLabel = settingsBackLabelRect(x, y);
		boolean backHovered = hoverable && back.contains(mouseX, mouseY);
		int headerColor = backHovered ? BUTTON_HOVER : GROUP_HEADER;
		roundedRectBordered(graphics, headerSurface.x, headerSurface.y, headerSurface.w,
			headerSurface.h, componentRadius(), headerColor, headerColor,
			backHovered ? PANEL_HIGHLIGHT : BORDER);
		graphics.centeredText(font, textFit(font, "Back", Math.max(1, backLabel.w - 8)),
			backLabel.x + backLabel.w / 2,
			backLabel.y + (backLabel.h - TEXT_HEIGHT) / 2,
			backHovered ? TEXT_ON_ACCENT : TEXT_PRIMARY);
		int titleDividerX = backLabel.x + backLabel.w + 8;
		int dividerTop = headerSurface.y + 4;
		int dividerBottom = headerSurface.y + headerSurface.h - 4;
		graphics.fill(titleDividerX, dividerTop, titleDividerX + 1,
			Math.max(dividerTop + 1, dividerBottom), backHovered ? TEXT_ON_ACCENT : BORDER);
		int titleX = titleDividerX + 9;
		int titleWidth = Math.max(0, headerSurface.x + headerSurface.w - 8 - titleX);
		graphics.text(font, textFit(font, module.name(), titleWidth), titleX,
			headerSurface.y + (headerSurface.h - TEXT_HEIGHT) / 2,
			backHovered ? TEXT_ON_ACCENT : TEXT_PRIMARY);
		int horizontalDividerY = headerSurface.y + headerSurface.h + 2;
		graphics.fill(headerSurface.x + 3, horizontalDividerY,
			headerSurface.x + headerSurface.w - 3, horizontalDividerY + 1, BORDER);
		Rect viewport = settingsViewport(x, y);
		boolean insideViewport = hoverable && viewport.contains(mouseX, mouseY);
		List<Row> rows = settingsRows(module, x, y, scroll, expandedColor);
		int appliedScroll = settingsAppliedScroll(module, viewport, scroll, expandedColor, x);

		graphics.enableScissor(viewport.x, viewport.y, viewport.x + viewport.w, viewport.y + viewport.h);
		renderPreview(graphics, font, module, viewport, appliedScroll);
		if (settingsLayoutTransition != null && settingsLayoutTransition.module() == module && transitionFrom == null) {
			float progress = settingsLayoutProgress(now, motion);
			renderSettingsLayoutTransition(graphics, font, mouseX, mouseY,
				settingsLayoutTransition, viewport, progress, now, motion);
		} else {
			renderSettingRows(graphics, font, mouseX, mouseY, rows, viewport, module, insideViewport, now, motion);
		}
		graphics.disableScissor();

		renderScrollbar(graphics, viewport, settingsContentHeight(module, x, expandedColor), (int) Math.round(scroll));
	}

	private void renderSettingRows(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY,
		List<Row> rows, Rect viewport, Module module, boolean hoverable, long now, ClickGuiMotion motion) {
		for (Row row : rows) {
			if (intersection(viewport, row.bounds()) == null) continue;
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
			if (intersection(viewport, bounds) == null) continue;
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
				moduleText(graphics, font, wrapped, bounds.x + PADDING, lineY, TEXT_PRIMARY);
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
		moduleText(graphics, font, collapsed ? "▸" : "▾", bounds.x + 12 + indent,
			bounds.y + (bounds.h - TEXT_HEIGHT) / 2, TEXT_SECONDARY);
		Rect toggle = groupRow.toggle;
		int labelWidth = bounds.w - 28 - indent - (toggle == null ? 0 : SWITCH_WIDTH + 18);
		moduleText(graphics, font, textFit(font, groupRow.group.name(), moduleTextWidth(labelWidth)),
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
		moduleText(graphics, font, textFit(font, setting.displayName(),
			moduleTextWidth(bounds.w - VALUE_GUTTER - 28)),
			bounds.x + 16, bounds.y + (ROW_HEIGHT - TEXT_HEIGHT) / 2, TEXT_SECONDARY);

		int swatchSize = 12;
		int swatchX = bounds.x + bounds.w - swatchSize - 14;
		int swatchY = bounds.y + (ROW_HEIGHT - swatchSize) / 2;
		ColorPickerPalette.checkerboard(graphics, swatchX, swatchY, swatchSize, swatchSize);
		roundedRectBordered(graphics, swatchX, swatchY, swatchSize, swatchSize, 3,
			setting.argb(), setting.argb(), BORDER);

		Picker picker = colorRow.picker;
		if (picker == null) return;
		float reveal = pickerReveal(setting, now, motion);
		int revealHeight = Math.round(pickerHeight(setting) * reveal);
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
		if (picker.alpha != null) renderAlphaBar(graphics, picker.alpha, setting);
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
		ColorPickerPalette.saturationSquare(graphics, square.x, square.y, square.w, square.h, setting);
	}

	private void renderHueBar(GuiGraphicsExtractor graphics, Rect bar, ColorSetting setting) {
		ColorPickerPalette.hueBar(graphics, bar.x, bar.y, bar.w, bar.h, setting);
	}

	private void renderAlphaBar(GuiGraphicsExtractor graphics, Rect bar, ColorSetting setting) {
		ColorPickerPalette.alphaBar(graphics, bar.x, bar.y, bar.w, bar.h, setting);
	}

	private void renderHexField(GuiGraphicsExtractor graphics, Font font, Rect field, ColorSetting setting) {
		boolean focused = setting == focusedHexSetting;
		roundedRectBordered(graphics, field.x, field.y, field.w, field.h, 3, SLIDER_TRACK, SLIDER_TRACK,
			focused ? SLIDER_FILL : BORDER);
		String value = focused ? hexInput : setting.hex();
		TextSlice slice = visibleSlice(font, value, focused ? hexCursor : value.length(), Math.max(1, field.w - 8));
		String shown = slice.text();
		int textY = field.y + (field.h - TEXT_HEIGHT) / 2;
		graphics.text(font, shown, field.x + 4, textY, TEXT_PRIMARY);
		if (focused && (System.currentTimeMillis() / CARET_BLINK_MILLIS) % 2 == 0) {
			int cursorInSlice = clampCursor(shown, hexCursor - slice.start());
			int caretX = field.x + 4 + font.width(shown.substring(0, cursorInSlice));
			graphics.fill(caretX, textY - 1, caretX + 1, textY + TEXT_HEIGHT + 1, TEXT_PRIMARY);
		}
	}

	private void renderNumberRow(GuiGraphicsExtractor graphics, Font font, NumberRow numberRow,
		long now, ClickGuiMotion motion) {
		int controlWidth = numberRow.field == null ? VALUE_GUTTER + 28 : numberRow.field.w + 36;
		moduleText(graphics, font, textFit(font, numberRow.setting.displayName(),
			moduleTextWidth(numberRow.bounds.w - controlWidth)),
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
		moduleText(graphics, font, numberRow.setting.display(), slider.x + slider.w + 8, slider.y - 2, TEXT_MUTED);
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
		String value = focused ? numberInput : numberRow.setting.display();
		int inner = Math.max(1, field.w - 8);
		TextSlice slice = visibleSlice(font, value, focused ? numberCursor : value.length(), inner);
		String shown = slice.text();
		int textY = field.y + (field.h - TEXT_HEIGHT) / 2;
		graphics.text(font, shown, field.x + 4, textY, TEXT_PRIMARY);
		if (focused && (System.currentTimeMillis() / CARET_BLINK_MILLIS) % 2 == 0) {
			int cursorInSlice = clampCursor(shown, numberCursor - slice.start());
			int caretX = field.x + 4 + font.width(shown.substring(0, cursorInSlice));
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
		moduleText(graphics, font, textFit(font, toggleRow.setting.displayName(),
			moduleTextWidth(bounds.w - SWITCH_WIDTH - 36)),
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
		moduleText(graphics, font, textFit(font, choiceRow.setting.displayName(),
			moduleTextWidth(bounds.w - choiceRow.field.w - 36)),
			bounds.x + 16,
			bounds.y + (bounds.h - TEXT_HEIGHT) / 2, TEXT_SECONDARY);

		Rect field = choiceRow.field;
		int fieldColor = hovered ? BUTTON_HOVER : (pulse > 0.0f
			? lerpColor(BUTTON_BG, BUTTON_HOVER, pulse * 0.75f) : BUTTON_BG);
		roundedRectBordered(graphics, field.x, field.y, field.w, field.h, 4,
			fieldColor, fieldColor, BORDER);
		String value = textFit(font, choiceRow.setting.value(), field.w - 12);
		// The field surface is accent-derived, so its value needs the accent-safe label colour.
		moduleCenteredText(graphics, font, "‹ " + value + " ›", field.x + field.w / 2,
			field.y + (field.h - TEXT_HEIGHT) / 2, TEXT_ON_ACCENT);
	}

	private void renderTextRow(GuiGraphicsExtractor graphics, Font font, TextRow textRow,
		long now, ClickGuiMotion motion) {
		Rect bounds = textRow.bounds;
		Rect field = textRow.field;
		boolean focused = textRow.setting == focusedTextSetting;
		moduleText(graphics, font, textFit(font, textRow.setting.displayName(),
			moduleTextWidth(bounds.w - textRow.field.w - 36)),
			bounds.x + 16, bounds.y + (bounds.h - TEXT_HEIGHT) / 2, TEXT_SECONDARY);

		float pulse = interactionPulse(textRow.setting, now, motion);
		int fieldBorder = focused ? SLIDER_FILL
			: pulse > 0.0f ? lerpColor(BORDER, PANEL_HIGHLIGHT, pulse) : BORDER;
		roundedRectBordered(graphics, field.x, field.y, field.w, field.h, 3, SLIDER_TRACK, SLIDER_TRACK,
			fieldBorder);

		// Shows the tail rather than the head once the value outgrows the box, so what was just
		// typed stays visible. Dropped a code point at a time rather than a char: halving a
		// surrogate pair leaves a stray the font draws as tofu, which never shrinks the string.
		String value = textRow.setting.value();
		int inner = Math.max(1, field.w - 8);
		TextSlice slice = visibleSlice(font, value, focused ? textCursor : value.length(), inner);
		String text = slice.text();
		int textY = field.y + (field.h - TEXT_HEIGHT) / 2;
		graphics.text(font, text, field.x + 4, textY, TEXT_PRIMARY);

		if (focused && (System.currentTimeMillis() / CARET_BLINK_MILLIS) % 2 == 0) {
			int cursorInSlice = clampCursor(text, textCursor - slice.start());
			int caretX = field.x + 4 + font.width(text.substring(0, cursorInSlice));
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
		// The resting surface is a shaded accent and the hovered one is the accent itself, so the
		// accent-safe label reads correctly against both.
		moduleCenteredText(graphics, font, textFit(font, actionRow.action.label(), moduleTextWidth(bounds.w - 36)),
			bounds.x + bounds.w / 2, bounds.y + (bounds.h - TEXT_HEIGHT) / 2, TEXT_ON_ACCENT);
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

	/** The clipped, scrollable settings area follows the same inset as the module grid. */
	private Rect settingsViewport(int x, int panelY) {
		Rect viewport = gridViewport(x, panelY);
		int header = settingsHeaderHeight();
		return new Rect(viewport.x, viewport.y + header, viewport.w, Math.max(1, viewport.h - header));
	}

	/** Setting rows with the current scroll already applied, so callers can hit-test them directly. */
	private List<Row> settingsRows(Module module, int x, int panelY) {
		return settingsRows(module, x, panelY, settingsScrollVisual,
			renderedExpandedColor(System.nanoTime(), motion()));
	}

	private List<Row> settingsRows(Module module, int x, int panelY, double scroll, ColorSetting expandedColor) {
		Rect viewport = settingsViewport(x, panelY);
		int appliedScroll = settingsAppliedScroll(module, viewport, scroll, expandedColor, x);
		int startY = viewport.y - appliedScroll;
		SettingsLayoutCache layout = settingsLayout(module, x, expandedColor);
		if (positionedSettingsLayout == layout && positionedSettingsStartY == startY
			&& positionedSettingsRows != null) return positionedSettingsRows;

		List<Row> positioned = new ArrayList<>(layout.rows.size());
		for (Row row : layout.rows) {
			Rect bounds = row.bounds();
			positioned.add(startY == 0 ? row : withBounds(row,
				new Rect(bounds.x, bounds.y + startY, bounds.w, bounds.h)));
		}
		positionedSettingsLayout = layout;
		positionedSettingsStartY = startY;
		positionedSettingsRows = List.copyOf(positioned);
		return positionedSettingsRows;
	}

	private int settingsAppliedScroll(Module module, Rect viewport, double scroll,
		ColorSetting expandedColor, int x) {
		int maxScroll = Math.max(0, settingsContentHeight(module, x, expandedColor) - viewport.h);
		return Math.max(0, Math.min(maxScroll, (int) Math.round(scroll)));
	}

	private SettingsLayoutCache settingsLayout(Module module, int x, ColorSetting expandedColor) {
		int rowX = x + gridInset() + PADDING;
		int contentWidth = settingsContentWidth();
		boolean debugEnabled = DebugState.enabled();
		SettingsLayoutCache cached = settingsLayoutCache;
		if (cached != null && cached.module == module && cached.rowX == rowX
			&& cached.contentWidth == contentWidth && cached.expandedColor == expandedColor
			&& cached.revision == settingsLayoutRevision && cached.debugEnabled == debugEnabled) {
			return cached;
		}

		List<Row> rows = List.copyOf(layoutRows(module, rowX, 0, expandedColor));
		int contentHeight = previewOffset(module);
		for (Row row : rows) {
			Rect bounds = row.bounds();
			contentHeight = Math.max(contentHeight, bounds.y + bounds.h);
		}
		SettingsLayoutCache updated = new SettingsLayoutCache(module, rowX, contentWidth,
			expandedColor, settingsLayoutRevision, debugEnabled, rows, contentHeight);
		settingsLayoutCache = updated;
		positionedSettingsLayout = null;
		positionedSettingsRows = null;
		positionedSettingsStartY = Integer.MIN_VALUE;
		return updated;
	}

	private void invalidateSettingsLayout() {
		settingsLayoutRevision++;
		settingsLayoutCache = null;
		positionedSettingsLayout = null;
		positionedSettingsRows = null;
		positionedSettingsStartY = Integer.MIN_VALUE;
	}

	private void clampViewScroll() {
		// The category column is clamped first and independently: its viewport depends only on the
		// panel size, and it has to stay inside its strip whichever view is open.
		clampCategoryScroll();
		if (openSettingsModule != null && ModuleManager.contains(selectedCategory, openSettingsModule)) {
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
		return settingsLayout(module, x, expandedColor).contentHeight;
	}

	private int colorRowHeight(ColorSetting setting) {
		return colorRowHeight(setting, expandedColorSetting);
	}

	private int colorRowHeight(ColorSetting setting, ColorSetting expandedColor) {
		return ROW_HEIGHT + (setting == expandedColor ? pickerHeight(setting) : 0);
	}

	private static int pickerHeight(ColorSetting setting) {
		return setting.alphaEditable() ? PICKER_HEIGHT : PICKER_RGB_HEIGHT;
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
		int moduleWidth = settingsContentWidth();
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
		Rect alpha = setting.alphaEditable() ? new Rect(left, alphaY, width, PICKER_BAR_HEIGHT) : null;
		int hexY = setting.alphaEditable() ? alphaY + PICKER_BAR_HEIGHT + PICKER_GAP : alphaY;
		Rect hex = new Rect(left, hexY,
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
		int mouseX = (int) event.x();
		int mouseY = (int) event.y();
		if (!inputSettled()) {
			// Category navigation remains available during the slide so fast repeated clicks queue
			// instead of disappearing behind the transition input gate.
			if (event.button() == 0 && transitionFrom != null) {
				Category queuedCategory = categoryAt(mouseX, mouseY, panelX(), panelY());
				if (queuedCategory != null) {
					setSearchQuery("");
					ViewState target = new ViewState(queuedCategory, null,
						ClickGuiState.gridScroll(queuedCategory), 0, null);
					requestNavigation(target,
						Integer.compare(indexOf(queuedCategory), indexOf(selectedCategory)));
				}
			}
			return true;
		}
		int panelX = panelX();
		int panelY = panelY();
		int rightX = panelX + categoryWidth();
		boolean left = event.button() == 0;
		boolean right = event.button() == 1;
		if (!left && !right) return super.mouseClicked(event, doubleClick);

		// Any click drops focus; the row that was hit takes it back below. Anything else would
		// leave a field quietly eating keystrokes after the user moved on.
		blurTextField();

		if (changelogOpen) {
			// A click outside closes it; a click inside is swallowed so it cannot act on the menu
			// showing through underneath.
			if (!changelogPanelRect().contains(mouseX, mouseY)) closeChangelog();
			return true;
		}

		Rect search = searchRect(panelX, panelY);
		if (left && search.contains(mouseX, mouseY)) {
			searchFocused = true;
			searchSelectAll = false;
			searchCursor = cursorAtMouse(Minecraft.getInstance().font, searchQuery, mouseX,
				search.x + searchTextInsetForHeight(search.h),
				searchTextViewportWidth(search.w, search.h), searchCursor, HEADER_TEXT_SCALE);
			return true;
		}
		if (left && UpdateChecker.newerVersion() != null) {
			// The optional update actions remain independent of the non-clickable player identity.
			if (updateDownloadRect(panelX, panelY).contains(mouseX, mouseY)) {
				ConfirmLinkScreen.confirmLinkNow(this, UpdateChecker.releaseUrl());
				return true;
			}
			if (updateInfoRect(panelX, panelY).contains(mouseX, mouseY)) {
				playClick();
				openChangelog();
				return true;
			}
		}
		if (left && githubRect(panelX, panelY).contains(mouseX, mouseY)) {
			ConfirmLinkScreen.confirmLinkNow(this, repositoryUrl());
			return true;
		}
		Rect settings = settingsGearRect(panelX, panelY);
		Rect theme = themeRect(panelX, panelY);
		if (left && settingsGearRect(panelX, panelY).contains(mouseX, mouseY)) {
			openGeneralSettings();
			return true;
		}

		if (left && themeRect(panelX, panelY).contains(mouseX, mouseY)) {
			openThemeSettings();
			return true;
		}

		if (left && moveElementsRect(panelX, panelY).contains(mouseX, mouseY)) {
			persistView();
			markInteraction("Move Elements");
			playClick();
			beginClose(new MoveUiScreen(this));
			return true;
		}
		if (left && transitionFrom == null && openSettingsModule != null
			&& settingsBackRect(rightX, panelY).contains(mouseX, mouseY)) {
			markInteraction("Back to modules");
			playClick();
			requestNavigation(new ViewState(selectedCategory, null, gridScroll, 0, null), -1);
			return true;
		}

		Category clickedCategory = categoryAt(mouseX, mouseY, panelX, panelY);
		if (left && clickedCategory != null) {
			Rect fullRow = categoryRows(panelX, panelY).get(indexOf(clickedCategory));
			Rect visibleRow = new Rect(fullRow.x, fullRow.y - categoryScroll, fullRow.w, fullRow.h);
			if (categoryFavoriteRect(visibleRow).contains(mouseX, mouseY)) {
				ClickGuiState.toggleFavorite(clickedCategory);
				favoriteStateChanged();
				playClick();
				ModConfig.markDirty();
				return true;
			}
			setSearchQuery("");
			persistView();
			ViewState target = new ViewState(clickedCategory, null,
				ClickGuiState.gridScroll(clickedCategory), 0, null);
			if (!currentView().equals(target)) {
				markInteraction(clickedCategory);
				playClick();
				requestNavigation(target,
					Integer.compare(indexOf(clickedCategory), indexOf(selectedCategory)));
			}
			return true;
		}

		List<Module> modules = visibleModules(selectedCategory);

		if (openSettingsModule != null && ModuleManager.contains(selectedCategory, openSettingsModule)) {
			return settingsClicked(openSettingsModule, mouseX, mouseY, rightX, panelY, left, right);
		}

		for (CardRect card : cardRects(modules, rightX, panelY,
			transitionFrom == null ? gridScrollVisual : currentView().gridScroll)) {
			if (!card.bounds.contains(mouseX, mouseY)) continue;
			if (left && moduleFavoriteRect(card.module, card.bounds).contains(mouseX, mouseY)) {
				markInteraction(card.module);
				playClick();
				ClickGuiState.toggleFavorite(card.module);
				favoriteStateChanged();
				ModConfig.markDirty();
				return true;
			}
			Rect toggle = cardSwitchRect(card.bounds);
			if (left && card.module.showsKeybindControl()
				&& keybindRect(card.module, card.bounds).contains(mouseX, mouseY)) {
				markInteraction(card.module);
				playClick();
				if (ModuleKeybindManager.isBinding(card.module)) ModuleKeybindManager.cancelBinding();
				else ModuleKeybindManager.beginBinding(card.module);
				return true;
			}
			if (left && card.module.showsToggleControl()
				&& toggle.contains(mouseX, mouseY)) {
				markInteraction(card.module);
				playClick();
				boolean wasEnabled = card.module.isEnabled();
				card.module.toggle();
				animateToggle(card.module, wasEnabled, card.module.isEnabled());
				persistView();
				ModConfig.markDirty();
			} else if (left && card.module.hasSettings()) {
				markInteraction(card.module);
				playClick();
				requestNavigation(new ViewState(card.module.category(), card.module,
					ClickGuiState.gridScroll(card.module.category()), 0, null), 1);
			}
			return true;
		}
		if (gridViewport(rightX, panelY).contains(mouseX, mouseY)) return true;

		return super.mouseClicked(event, doubleClick);
	}

	private boolean settingsClicked(Module module, int mouseX, int mouseY, int x, int panelY,
		boolean left, boolean right) {
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
						invalidateSettingsLayout();
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
						invalidateSettingsLayout();
						// A collapse can make the old scroll point past the new content. Clamp the
						// logical and visual scroll before capturing the incoming layout so the
						// transition never targets an unreachable scrollbar position.
						clampViewScroll();
						List<Row> after = settingsRows(module, x, panelY);
						startSettingsLayoutTransition(module, before, after);
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
						if (picker.alpha != null && picker.alpha.contains(mouseX, mouseY)) {
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
							hexCursor = cursorAtMouse(Minecraft.getInstance().font, hexInput, mouseX,
								picker.hex.x + 4, picker.hex.w - 8, hexCursor);
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
						numberCursor = numberInput.length();
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
						invalidateSettingsLayout();
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
						invalidateSettingsLayout();
						ModConfig.markDirty();
						return true;
					}
				}
				case TextRow textRow -> {
					if (textRow.field.contains(mouseX, mouseY)) {
						markInteraction(textRow.setting);
						playClick();
						focusedTextSetting = textRow.setting;
						textCursor = cursorAtMouse(Minecraft.getInstance().font, textRow.setting.value(), mouseX,
							textRow.field.x + 4, textRow.field.w - 8, textRow.setting.value().length());
						textSelectAll = false;
						return true;
					}
				}
				case ActionRow actionRow -> {
					if (actionRow.bounds.contains(mouseX, mouseY)) {
						markInteraction(actionRow.action);
						playClick();
						actionRow.action.onClick().run();
						invalidateSettingsLayout();
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
				searchCursor = 0;
				searchSelectAll = false;
			}
			String inserted = Character.toString(codepoint);
			if (searchQuery.length() + inserted.length() <= SEARCH_MAX_LENGTH) {
				int cursor = clampCursor(searchQuery, searchCursor);
				setSearchQuery(searchQuery.substring(0, cursor) + inserted + searchQuery.substring(cursor));
				searchCursor = cursor + inserted.length();
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
				String inserted = Character.toString(codepoint).toUpperCase(Locale.ROOT);
				int cursor = clampCursor(hexInput, hexCursor);
				hexInput = hexInput.substring(0, cursor) + inserted + hexInput.substring(cursor);
				hexCursor = cursor + inserted.length();
				focusedHexSetting.setHex(hexInput);
				ModConfig.markDirty();
			}
			return true;
		}
		if (focusedTextSetting != null) {
			String current = focusedTextSetting.value();
			if (textSelectAll) {
				current = "";
				textCursor = 0;
				textSelectAll = false;
			}
			String inserted = Character.toString(codepoint);
			int cursor = clampCursor(current, textCursor);
			focusedTextSetting.setValue(current.substring(0, cursor) + inserted + current.substring(cursor));
			invalidateSettingsLayout();
			textCursor = Math.min(focusedTextSetting.value().length(), cursor + inserted.length());
			ModConfig.markDirty();
			return true;
		}
		if (focusedNumberSetting != null) {
			if (numberSelectAll) {
				numberInput = "";
				numberCursor = 0;
				numberSelectAll = false;
			}
			boolean allowed = Character.isDigit(codepoint) || codepoint == '-' || codepoint == '+'
				|| (!focusedNumberSetting.isInteger() && (codepoint == '.' || codepoint == ','));
			if (allowed && numberInput.length() < 16) {
				String inserted = codepoint == ',' ? "." : Character.toString(codepoint);
				int cursor = clampCursor(numberInput, numberCursor);
				numberInput = numberInput.substring(0, cursor) + inserted + numberInput.substring(cursor);
				numberCursor = cursor + inserted.length();
			}
			return true;
		}
		return super.charTyped(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (changelogOpen && event.key() == GLFW.GLFW_KEY_ESCAPE) {
			// The notes panel is the topmost thing on screen, so Escape has to close it before it
			// reaches any of the menu's own back-out steps.
			closeChangelog();
			return true;
		}
		if (searchFocused) {
			if ((event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0 && event.key() == GLFW.GLFW_KEY_A) {
				searchSelectAll = true;
				return true;
			}
			switch (event.key()) {
				case GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_KEY_DELETE -> {
					if (searchSelectAll) {
						setSearchQuery("");
						searchCursor = 0;
						searchSelectAll = false;
					} else if (event.key() == GLFW.GLFW_KEY_BACKSPACE && searchCursor > 0) {
						int start = moveCursor(searchQuery, searchCursor, -1);
						setSearchQuery(searchQuery.substring(0, start) + searchQuery.substring(searchCursor));
						searchCursor = start;
					} else if (event.key() == GLFW.GLFW_KEY_DELETE && searchCursor < searchQuery.length()) {
						int end = moveCursor(searchQuery, searchCursor, 1);
						setSearchQuery(searchQuery.substring(0, searchCursor) + searchQuery.substring(end));
					}
				}
				case GLFW.GLFW_KEY_LEFT -> {
					searchCursor = searchSelectAll ? 0 : moveCursor(searchQuery, searchCursor, -1);
					searchSelectAll = false;
				}
				case GLFW.GLFW_KEY_RIGHT -> {
					searchCursor = searchSelectAll ? searchQuery.length() : moveCursor(searchQuery, searchCursor, 1);
					searchSelectAll = false;
				}
				case GLFW.GLFW_KEY_HOME -> { searchCursor = 0; searchSelectAll = false; }
				case GLFW.GLFW_KEY_END -> { searchCursor = searchQuery.length(); searchSelectAll = false; }
				case GLFW.GLFW_KEY_ESCAPE -> {
					if (!searchQuery.isEmpty()) { setSearchQuery(""); searchCursor = 0; }
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
				case GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_KEY_DELETE -> {
					if (event.key() == GLFW.GLFW_KEY_BACKSPACE && hexCursor > 0) {
						int start = moveCursor(hexInput, hexCursor, -1);
						hexInput = hexInput.substring(0, start) + hexInput.substring(hexCursor);
						hexCursor = start;
					} else if (event.key() == GLFW.GLFW_KEY_DELETE && hexCursor < hexInput.length()) {
						int end = moveCursor(hexInput, hexCursor, 1);
						hexInput = hexInput.substring(0, hexCursor) + hexInput.substring(end);
					}
					focusedHexSetting.setHex(hexInput);
					ModConfig.markDirty();
				}
				case GLFW.GLFW_KEY_LEFT -> hexCursor = moveCursor(hexInput, hexCursor, -1);
				case GLFW.GLFW_KEY_RIGHT -> hexCursor = moveCursor(hexInput, hexCursor, 1);
				case GLFW.GLFW_KEY_HOME -> hexCursor = 0;
				case GLFW.GLFW_KEY_END -> hexCursor = hexInput.length();
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
						numberCursor = 0;
						numberSelectAll = false;
					} else if (event.key() == GLFW.GLFW_KEY_BACKSPACE && numberCursor > 0) {
						int start = moveCursor(numberInput, numberCursor, -1);
						numberInput = numberInput.substring(0, start) + numberInput.substring(numberCursor);
						numberCursor = start;
					} else if (event.key() == GLFW.GLFW_KEY_DELETE && numberCursor < numberInput.length()) {
						int end = moveCursor(numberInput, numberCursor, 1);
						numberInput = numberInput.substring(0, numberCursor) + numberInput.substring(end);
					}
				}
				case GLFW.GLFW_KEY_LEFT -> {
					numberCursor = numberSelectAll ? 0 : moveCursor(numberInput, numberCursor, -1);
					numberSelectAll = false;
				}
				case GLFW.GLFW_KEY_RIGHT -> {
					numberCursor = numberSelectAll ? numberInput.length() : moveCursor(numberInput, numberCursor, 1);
					numberSelectAll = false;
				}
				case GLFW.GLFW_KEY_HOME -> { numberCursor = 0; numberSelectAll = false; }
				case GLFW.GLFW_KEY_END -> { numberCursor = numberInput.length(); numberSelectAll = false; }
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
				case GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_KEY_DELETE -> {
					String value = focusedTextSetting.value();
					if (textSelectAll) {
						focusedTextSetting.setValue("");
						invalidateSettingsLayout();
						textCursor = 0;
						textSelectAll = false;
					} else if (event.key() == GLFW.GLFW_KEY_BACKSPACE && textCursor > 0) {
						int start = moveCursor(value, textCursor, -1);
						focusedTextSetting.setValue(value.substring(0, start) + value.substring(textCursor));
						invalidateSettingsLayout();
						textCursor = start;
					} else if (event.key() == GLFW.GLFW_KEY_DELETE && textCursor < value.length()) {
						int end = moveCursor(value, textCursor, 1);
						focusedTextSetting.setValue(value.substring(0, textCursor) + value.substring(end));
						invalidateSettingsLayout();
					}
					ModConfig.markDirty();
				}
				case GLFW.GLFW_KEY_LEFT -> {
					textCursor = textSelectAll ? 0 : moveCursor(focusedTextSetting.value(), textCursor, -1);
					textSelectAll = false;
				}
				case GLFW.GLFW_KEY_RIGHT -> {
					textCursor = textSelectAll ? focusedTextSetting.value().length()
						: moveCursor(focusedTextSetting.value(), textCursor, 1);
					textSelectAll = false;
				}
				case GLFW.GLFW_KEY_HOME -> { textCursor = 0; textSelectAll = false; }
				case GLFW.GLFW_KEY_END -> { textCursor = focusedTextSetting.value().length(); textSelectAll = false; }
				case GLFW.GLFW_KEY_A -> {
					if ((event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0) textSelectAll = true;
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
				invalidateSettingsLayout();
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
		textCursor = 0;
		textSelectAll = false;
		focusedNumberSetting = null;
		numberInput = "";
		numberCursor = 0;
		numberSelectAll = false;
		focusedHexSetting = null;
		hexCursor = 0;
		searchFocused = false;
		searchCursor = clampCursor(searchQuery, searchCursor);
		searchSelectAll = false;
		hexInput = "";
		ModConfig.markDirty();
	}

	private void commitNumberInput() {
		if (focusedNumberSetting == null || numberInput == null || numberInput.isBlank()) return;
		try {
			float previous = focusedNumberSetting.value();
			focusedNumberSetting.setValue(Float.parseFloat(numberInput.trim()));
			if (focusedNumberSetting.value() != previous) invalidateSettingsLayout();
		} catch (NumberFormatException ignored) {
			// Keep the last valid value when the user leaves an unfinished number such as "-".
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (!inputSettled()) return true;
		int rightX = panelX() + categoryWidth();
		int notch = (int) Math.round(scrollY * CHANNEL_ROW_HEIGHT);
		int panelY = panelY();
		boolean categoryHovered = mouseX >= panelX() && mouseX < rightX
			&& mouseY >= categoryViewportTop(panelY)
			&& mouseY < categoryViewportTop(panelY) + categoryViewportHeight(panelY);
		Rect settingsArea = openSettingsModule == null ? null : settingsViewport(rightX, panelY);
		boolean settingsHovered = settingsArea != null && settingsArea.contains(mouseX, mouseY);
		List<Module> modules = openSettingsModule == null ? visibleModules(selectedCategory) : List.of();
		Rect gridArea = openSettingsModule == null ? gridViewport(rightX, panelY) : null;
		boolean gridHovered = gridArea != null && gridArea.contains(mouseX, mouseY);
		ScrollTarget target = scrollTarget(changelogOpen, categoryHovered, openSettingsModule != null,
			settingsHovered, gridHovered);
		switch (target) {
		case CHANGELOG -> {
			Rect viewport = changelogViewport(changelogPanelRect());
			int maxScroll = Math.max(0, changelogLines().size() * LINE_HEIGHT - viewport.h);
			changelogScroll = Math.max(0, Math.min(maxScroll, changelogScroll - notch));
			return true;
		}
		case CATEGORY -> {
			// The category column is its own scroll region, and the only way to reach rows that do not fit.
			categoryScroll = Math.max(0, Math.min(categoryMaxScrollCurrent(categoryViewportHeight(panelY)),
				categoryScroll - notch));
			persistView();
			return true;
		}
		case SETTINGS -> {
			int maxScroll = Math.max(0, settingsContentHeight(openSettingsModule, rightX,
				renderedExpandedColor(System.nanoTime(), motion())) - settingsArea.h);
			settingsScroll = Math.max(0, Math.min(maxScroll, settingsScroll - notch));
			persistView();
			return true;
		}
		case GRID -> {
			int maxScroll = Math.max(0, gridContentHeight(modules) - gridArea.h);
			gridScroll = Math.max(0, Math.min(maxScroll, gridScroll - notch));
			persistView();
			return true;
		}
		case NONE -> { }
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
			case SQUARE -> ColorPickerPalette.applySquare(setting, mouseX, mouseY,
				picker.square.x, picker.square.y, picker.square.w, picker.square.h);
			case HUE -> ColorPickerPalette.applyHue(setting, mouseX, picker.hue.x, picker.hue.w);
			case ALPHA -> ColorPickerPalette.applyAlpha(setting, mouseX, picker.alpha.x, picker.alpha.w);
		}
		// The field would otherwise keep showing the value from before the drag started.
		if (focusedHexSetting == setting) {
			hexInput = setting.hex();
		}
	}

	private void applyNumberSlider(NumberSetting setting, Rect slider, int mouseX) {
		float fraction = (mouseX - slider.x) / (float) slider.w;
		float previous = setting.value();
		setting.setFraction(fraction);
		if (setting.value() != previous) invalidateSettingsLayout();
	}

	private List<Rect> categoryRows(int panelX, int panelY) {
		List<Rect> rows = new ArrayList<>();
		List<Category> categories = orderedCategories();
		int categoryWidth = categoryWidth();
		int rowHeight = categoryRowHeight();
		int top = contentTop(panelY) + categoryTopInset();
		for (int i = 0; i < categories.size(); i++) {
			rows.add(new Rect(panelX, top + i * rowHeight, categoryWidth, rowHeight));
		}
		return rows;
	}

	private int categoryRowHeight() { return Math.max(30, Math.round(panelHeight() * 0.098f)); }
	private int categoryTopInset() { return Math.max(8, Math.round(panelHeight() * 0.034f)); }
	static int categoryRowHeightForPanelHeight(int panelHeight) {
		return Math.max(30, Math.round(Math.max(1, panelHeight) * 0.098f));
	}

	/** Top of the strip the category rows are drawn into, below the header band. */
	private int categoryViewportTop(int panelY) {
		return contentTop(panelY);
	}

	/** Height of that strip: everything down to the Move Elements button, which is not scrolled. */
	private int categoryViewportHeight(int panelY) {
		int bottomReserve = UpdateChecker.bannerText() == null ? gridInset() : CATEGORY_NOTICE_HEIGHT + gridInset();
		int bottom = panelY + panelHeight() - bottomReserve;
		return Math.max(1, bottom - categoryViewportTop(panelY));
	}

	/** The category row under the pointer, or null when the pointer is outside the drawn strip. */
	private Category categoryAt(double mouseX, double mouseY, int panelX, int panelY) {
		if (mouseX < panelX || mouseX >= panelX + categoryWidth()) return null;
		int top = categoryViewportTop(panelY);
		if (mouseY < top || mouseY >= top + categoryViewportHeight(panelY)) return null;
		int rowHeight = categoryRowHeight();
		int rowsTop = contentTop(panelY) + categoryTopInset();
		int index = (int) Math.floor((mouseY - rowsTop + categoryScroll) / rowHeight);
		List<Category> categories = orderedCategories();
		if (index < 0 || index >= categories.size()) return null;
		// The last partial row would otherwise be selectable from the padded edge below it.
		int rowTop = rowsTop + index * rowHeight - categoryScroll;
		if (mouseY < rowTop || mouseY >= rowTop + rowHeight) return null;
		return categories.get(index);
	}

	/** Clamps the category column's scroll to what the current panel can show. */
	private int clampCategoryScroll() {
		int max = categoryMaxScrollCurrent(categoryViewportHeight(panelY()));
		categoryScroll = Math.max(0, Math.min(max, categoryScroll));
		return categoryScroll;
	}

	private int categoryContentHeightCurrent() { return categoryTopInset() + Category.values().length * categoryRowHeight(); }

	private int categoryMaxScrollCurrent(int viewportHeight) {
		return Math.max(0, categoryContentHeightCurrent() - Math.max(1, viewportHeight));
	}

	/** Total height the eight category rows need. */
	static int categoryContentHeight() {
		return Category.values().length * ROW_HEIGHT;
	}

	static int categoryContentHeightForPanelHeight(int panelHeight) {
		int row = categoryRowHeightForPanelHeight(panelHeight);
		return Math.max(8, Math.round(Math.max(1, panelHeight) * 0.034f)) + Category.values().length * row;
	}

	/**
	 * How far the category column may scroll, or zero when every row fits.
	 *
	 * <p>Pure so the cut-off can be asserted offline. Measured against the panel the column actually
	 * gets: at 1080p and a GUI scale of three there is room for 5 of the 8 rows, so 3 categories were
	 * previously drawn outside the panel and could only be selected by clicking blind below it.
	 */
	static int categoryMaxScroll(int viewportHeight) {
		return Math.max(0, categoryContentHeight() - Math.max(1, viewportHeight));
	}

	static int categoryMaxScroll(int panelHeight, int viewportHeight) {
		return Math.max(0, categoryContentHeightForPanelHeight(panelHeight) - Math.max(1, viewportHeight));
	}

	private int indexOf(Category category) {
		List<Category> categories = orderedCategories();
		for (int i = 0; i < categories.size(); i++) {
			if (categories.get(i) == category) return i;
		}
		return 0;
	}

	private static float lerp(float from, float to, float progress) {
		return from + (to - from) * progress;
	}

	static record Rect(int x, int y, int w, int h) {
		boolean contains(double px, double py) {
			return px >= x && px < x + w && py >= y && py < y + h;
		}
	}

	private record TextSlice(String text, int start, int end) {
	}

	private record ViewState(Category category, Module module, int gridScroll, int settingsScroll,
		ColorSetting expandedColor) {
	}

	private record NavigationRequest(ViewState view, int direction) {
	}

	private record TogglePulse(boolean from, boolean to, long startedNanos) {
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

	private record CardRect(Module module, Rect bounds, int index) {
	}

	private record ModuleSearchEntry(Module module, String searchText) {
	}

	private record CardSummaryLayout(Font font, String source, int width, List<FormattedCharSequence> lines) {
	}

	private record SettingsLayoutCache(Module module, int rowX, int contentWidth,
		ColorSetting expandedColor, long revision, boolean debugEnabled, List<Row> rows, int contentHeight) {
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
