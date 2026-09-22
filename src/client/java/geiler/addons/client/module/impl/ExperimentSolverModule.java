package geiler.addons.client.module.impl;

import geiler.addons.client.enchanting.ExperimentCell;
import geiler.addons.client.enchanting.ExperimentBoardGeometry;
import geiler.addons.client.enchanting.ChronomatronEvent;
import geiler.addons.client.enchanting.ExperimentPhase;
import geiler.addons.client.enchanting.ExperimentSnapshot;
import geiler.addons.client.enchanting.ExperimentSolverEngine;
import geiler.addons.client.enchanting.ExperimentTier;
import geiler.addons.client.enchanting.ExperimentType;
import geiler.addons.client.enchanting.SequenceStep;
import geiler.addons.client.enchanting.SolverView;
import geiler.addons.client.enchanting.SuperpairsBoard;
import geiler.addons.client.gui.GuiTheme;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.module.TextSetting;
import geiler.addons.client.tree.ChatText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerListener;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
	 * The screen-facing half of the experimentation solver.
 *
	 * <p>The shared controller owns observation and solver state. This adapter renders its immutable
	 * view, draws the Superpairs memory maintained by the shared menu session, and translates an
	 * accepted custom hit back into vanilla's
 * {@code slotClicked} call. Sequence progress advances only after that ordinary vanilla dispatch,
 * so the custom surface never predicts a click that the server has not accepted.</p>
 */
public final class ExperimentSolverModule extends Module {
	public static final ExperimentSolverModule INSTANCE = new ExperimentSolverModule();

	/** Odin's custom terminal surface is built on a 24-pixel logical slot grid. */
	private static final int BASE_SLOT_SIZE = 24;
	private static final int BASE_PANEL_PADDING = 2;
	private static final int SCREEN_MARGIN = 4;
	private static final int INFO_HEIGHT = 46;
	private static final long SUPERPAIRS_CLICK_FEEDBACK_NANOS = 280_000_000L;
	private static final int SUPERPAIRS_UNKNOWN_COLOR = 0xFFFFFFFF;
	private static final int SUPERPAIRS_UNKNOWN_BORDER = 0xFF25252D;
	private static final int SUPERPAIRS_SELECTED_BORDER = 0xFFFFFFFF;
	private static final int SUPERPAIRS_MATCH_COLOR = 0xFFFFC107;
	private static final int SUPERPAIRS_MATCH_BORDER = 0xFFFF8F00;
	private static final int SUPERPAIRS_KNOWN_COLOR = 0xFFE53935;
	private static final int SUPERPAIRS_KNOWN_BORDER = 0xFFFFB4B4;
	private static final int SUPERPAIRS_COLLECTED_COLOR = 0xFF16A34A;
	private static final int SUPERPAIRS_COLLECTED_BORDER = 0xFFB7FFCB;
	private static final Pattern CLICKS_REMAINING_PATTERN = Pattern.compile(
		"(?i)(?:(\\d+)\\s*(?:clicks?|tries?|attempts?)\\s*(?:left|remaining)"
			+ "|(?:clicks?|tries?|attempts?)\\s*(?:left|remaining)\\s*[:\\-]?\\s*(\\d+)"
			+ "|(?:left|remaining)\\s*[:\\-]?\\s*(\\d+)\\s*(?:clicks?|tries?|attempts?)"
			+ "|(?:left|remaining)\\s*(?:clicks?|tries?|attempts?)\\s*[:\\-]?\\s*(\\d+))");

	private final BooleanSetting chronomatron;
	private final BooleanSetting ultrasequencer;
	private final BooleanSetting superpairs;
	private final NumberSetting chronomatronFutureClicks;
	private final NumberSetting ultrasequencerFutureClicks;
	private final BooleanSetting preventMisclicks;
	private final NumberSetting serumsConsumed;
	private final NumberSetting size;
	private final NumberSetting roundness;
	private final NumberSetting slotGap;
	private final BooleanSetting clickSounds;
	private final TextSetting clickSound;
	private final NumberSetting clickSoundPitch;
	private final NumberSetting clickSoundVolume;
	private final BooleanSetting completeSounds;
	private final BooleanSetting maxClickAlert;
	private final TextSetting completeSound;
	private final NumberSetting completeSoundPitch;
	private final NumberSetting completeSoundVolume;
	private final ColorSetting panelColor;
	private final ColorSetting textColor;
	private final ColorSetting currentColor;
	private final ColorSetting nextColor;
	private final ColorSetting nextNextColor;
	private final ColorSetting nextNextNextColor;
	private final ColorSetting discoveredColor;
	private final ColorSetting unknownColor;
	private long maxAlertedSessionGeneration = Long.MIN_VALUE;
	private int superpairsFeedbackSlot = -1;
	private long superpairsFeedbackStartedNanos;

	private ExperimentSolverModule() {
		this(new Settings());
	}

	private ExperimentSolverModule(Settings settings) {
		super("Solver", "Solves Experimentation Table games.", Category.ENCHANTING,
			settings.chronomatron, settings.ultrasequencer, settings.superpairs,
			settings.chronomatronFutureClicks, settings.ultrasequencerFutureClicks,
			settings.preventMisclicks, settings.serumsConsumed,
			settings.size, settings.roundness, settings.slotGap,
			settings.clickSounds, settings.clickSound, settings.clickSoundPitch, settings.clickSoundVolume,
			settings.completeSounds, settings.maxClickAlert, settings.completeSound, settings.completeSoundPitch,
			settings.completeSoundVolume, settings.panelColor, settings.textColor,
			settings.currentColor, settings.nextColor, settings.nextNextColor, settings.nextNextNextColor,
			settings.discoveredColor, settings.unknownColor);
		this.chronomatron = settings.chronomatron;
		this.ultrasequencer = settings.ultrasequencer;
		this.superpairs = settings.superpairs;
		this.chronomatronFutureClicks = settings.chronomatronFutureClicks;
		this.ultrasequencerFutureClicks = settings.ultrasequencerFutureClicks;
		this.preventMisclicks = settings.preventMisclicks;
		this.serumsConsumed = settings.serumsConsumed;
		this.size = settings.size;
		this.roundness = settings.roundness;
		this.slotGap = settings.slotGap;
		this.clickSounds = settings.clickSounds;
		this.clickSound = settings.clickSound;
		this.clickSoundPitch = settings.clickSoundPitch;
		this.clickSoundVolume = settings.clickSoundVolume;
		this.completeSounds = settings.completeSounds;
		this.maxClickAlert = settings.maxClickAlert;
		this.completeSound = settings.completeSound;
		this.completeSoundPitch = settings.completeSoundPitch;
		this.completeSoundVolume = settings.completeSoundVolume;
		this.panelColor = settings.panelColor;
		this.textColor = settings.textColor;
		this.currentColor = settings.currentColor;
		this.nextColor = settings.nextColor;
		this.nextNextColor = settings.nextNextColor;
		this.nextNextNextColor = settings.nextNextNextColor;
		this.discoveredColor = settings.discoveredColor;
		this.unknownColor = settings.unknownColor;
		group(
			new SettingGroup("Experiments", settings.chronomatron, settings.ultrasequencer, settings.superpairs),
			new SettingGroup("Preview", settings.chronomatronFutureClicks, settings.ultrasequencerFutureClicks),
			new SettingGroup("Protection", settings.preventMisclicks, settings.serumsConsumed),
			new SettingGroup("Appearance", settings.size, settings.roundness, settings.slotGap),
			new SettingGroup("Sounds", settings.clickSounds, settings.clickSound,
				settings.clickSoundPitch, settings.clickSoundVolume, settings.completeSounds, settings.maxClickAlert,
				settings.completeSound, settings.completeSoundPitch, settings.completeSoundVolume),
			new SettingGroup("Colors", settings.panelColor, settings.textColor,
				settings.currentColor, settings.nextColor, settings.nextNextColor, settings.nextNextNextColor,
				settings.discoveredColor, settings.unknownColor)
		);
	}

	private static final class Settings {
		final BooleanSetting chronomatron = new BooleanSetting("Chronomatron", true);
		final BooleanSetting ultrasequencer = new BooleanSetting("Ultrasequencer", true);
		final BooleanSetting superpairs = new BooleanSetting("Superpairs", true);
		final NumberSetting chronomatronFutureClicks = new NumberSetting(
			"Chronomatron Future Clicks", 0, 3, 1, true);
		final NumberSetting ultrasequencerFutureClicks = new NumberSetting(
			"Ultrasequencer Future Clicks", 0, 3, 2, true);
		final BooleanSetting preventMisclicks = new BooleanSetting("Prevent Misclicks", true);
		final NumberSetting serumsConsumed = new NumberSetting("Serums Consumed", 0, 3, 0, true);
		// Keep the original persistence key while removing dungeon-terminal wording from the UI.
		final NumberSetting size = new NumberSetting("Term Size", "Size", 1.0f, 3.0f, 2.0f);
		final NumberSetting roundness = new NumberSetting("Roundness", 0, 15, 5, true);
		final NumberSetting slotGap = new NumberSetting("Slot Gap", 0, 8, 2, true);
		final BooleanSetting clickSounds = new BooleanSetting("Click Sounds", true);
		final TextSetting clickSound = new TextSetting("Click Sound", "entity.item.pickup", 96);
		final NumberSetting clickSoundPitch = new NumberSetting("Click Sound Pitch", 0.1f, 2.0f, 1.0f);
		final NumberSetting clickSoundVolume = new NumberSetting("Click Sound Volume", 0.0f, 1.0f, 1.0f);
		final BooleanSetting completeSounds = new BooleanSetting("Complete Sounds", false);
		final BooleanSetting maxClickAlert = new BooleanSetting("Max Click Alert", true);
		final TextSetting completeSound = new TextSetting("Complete Sound", "entity.experience_orb.pickup", 96);
		final NumberSetting completeSoundPitch = new NumberSetting("Complete Sound Pitch", 0.1f, 2.0f, 1.0f);
		final NumberSetting completeSoundVolume = new NumberSetting("Complete Sound Volume", 0.0f, 1.0f, 1.0f);
		final ColorSetting panelColor = new ColorSetting("Panel Color", "Background", 26, 26, 26, 255);
		final ColorSetting textColor = new ColorSetting("Text Color", 240, 240, 244, 255);
		final ColorSetting currentColor = new ColorSetting("Current Color", "Order 1", 85, 255, 85, 255);
		final ColorSetting nextColor = new ColorSetting("Next Color", "Order 2", 42, 127, 42, 255);
		final ColorSetting nextNextColor = new ColorSetting("Next Next Color", "Order 3", 21, 63, 21, 255);
		final ColorSetting nextNextNextColor = new ColorSetting(
			"Next Next Next Color", "Order 4", 10, 31, 10, 255);
		final ColorSetting discoveredColor = new ColorSetting("Discovered Color", 108, 99, 255, 255);
		final ColorSetting unknownColor = new ColorSetting("Unknown Color", 75, 75, 85, 255);
	}

	/** Whether the adapter should claim a recognized experiment of the requested type. */
	public boolean supports(geiler.addons.client.enchanting.ExperimentType type) {
		return switch (type) {
			case CHRONOMATRON -> chronomatron.value();
			case ULTRASEQUENCER -> ultrasequencer.value();
			case SUPERPAIRS -> superpairs.value();
		};
	}

	/** The pure engine shared with a future packet/render adapter. */
	public ExperimentSolverEngine engine() {
		return ExperimentController.INSTANCE.engine();
	}

	private SolverView solverView() {
		return ExperimentController.INSTANCE.view();
	}

	private Session session() {
		return ExperimentController.INSTANCE.session();
	}

	public ExperimentSolverEngine.Configuration configuration() {
		return new ExperimentSolverEngine.Configuration(serumsConsumed.intValue());
	}

	public BooleanSetting chronomatron() {
		return chronomatron;
	}

	public BooleanSetting ultrasequencer() {
		return ultrasequencer;
	}

	public BooleanSetting superpairs() {
		return superpairs;
	}

	public NumberSetting chronomatronFutureClicks() {
		return chronomatronFutureClicks;
	}

	public NumberSetting ultrasequencerFutureClicks() {
		return ultrasequencerFutureClicks;
	}

	@Override
	public boolean isSettingVisible(Setting setting) {
		int maximumFutureClicks = Math.max(chronomatronFutureClicks.intValue(),
			ultrasequencerFutureClicks.intValue());
		if (setting == nextColor) return maximumFutureClicks >= 1;
		if (setting == nextNextColor) return maximumFutureClicks >= 2;
		if (setting == nextNextNextColor) return maximumFutureClicks >= 3;
		return true;
	}

	public NumberSetting serumsConsumed() {
		return serumsConsumed;
	}

	/** Called once per client tick, before the screen is drawn. */
	public void tick() {
		ExperimentController.INSTANCE.tick();
		alertAtMaxClickMilestone();
	}

	private void alertAtMaxClickMilestone() {
		Session activeSession = session();
		if (!isEnabled() || activeSession == null || !supports(activeSession.type)) return;
		long generation = ExperimentController.INSTANCE.sessionGeneration();
		if (displayPhase() == Phase.MAX_CLICKS && maxAlertedSessionGeneration != generation) {
			maxAlertedSessionGeneration = generation;
			if (maxClickAlert.value() || completeSounds.value()) {
				playSound(completeSound, completeSoundPitch, completeSoundVolume);
			}
		}
	}

	/** Resets remembered stacks when the container is closed or replaced. */
	public void onScreenClosed() {
		ExperimentController.INSTANCE.onScreenClosed();
		superpairsFeedbackSlot = -1;
		maxAlertedSessionGeneration = Long.MIN_VALUE;
	}

	/** Attach before the opening inventory packets can arrive. */
	public void onScreenOpened(AbstractContainerScreen<?> screen) {
		ExperimentController.INSTANCE.onScreenOpened(screen);
	}

	/** Called after a server menu mutation, before listeners are broadcast. */
	public void onContainerUpdated(AbstractContainerMenu menu) {
		ExperimentController.INSTANCE.onContainerUpdated(menu);
	}

	public boolean observesMenu(AbstractContainerMenu menu) {
		return ExperimentController.INSTANCE.observesMenu(menu);
	}

	/**
	 * Draws the complete replacement surface. Returning true tells the mixin to skip vanilla's
	 * container extraction for this frame.
	 */
	public boolean renderCustomScreen(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics,
		int mouseX, int mouseY, float delta) {
		if (!ownsScreen(screen)) return false;
		observe(screen);
		SolverView solverView = solverView();
		int width = Math.max(1, Math.min(screen.width, Math.max(1, graphics.guiWidth())));
		int height = Math.max(1, Math.min(screen.height, Math.max(1, graphics.guiHeight())));
		Layout layout = layout(screen, width, height);

		// Odin's custom terminal GUI remains a centered rounded board; the compact status header is
		// inside that surface so it cannot become a second, drifting overlay.
		graphics.enableScissor(0, 0, width, height);
		GuiTheme.roundedRect(graphics, layout.panelX, layout.panelY, layout.panelWidth,
			layout.panelHeight, layout.radius, panelColor.argb());
		// Item rendering and text are not guaranteed to respect the tile rectangle. Keep every
		// translated child inside the replacement surface, including during a small-screen fit.
		int panelLeft = Math.max(0, Math.min(width, layout.panelX));
		int panelTop = Math.max(0, Math.min(height, layout.panelY));
		int panelRight = Math.max(panelLeft, Math.min(width, layout.panelX + layout.panelWidth));
		int panelBottom = Math.max(panelTop, Math.min(height, layout.panelY + layout.panelHeight));
		graphics.enableScissor(panelLeft, panelTop, panelRight, panelBottom);

		Font font = Minecraft.getInstance().font;
		renderStatus(graphics, font, layout);
		if (layout.slotLayouts.isEmpty() && solverView.type() != ExperimentType.SUPERPAIRS) {
			Phase phase = displayPhase();
			String label = phase == Phase.SOLVE ? Phase.WAITING.label : phase.label;
			graphics.centeredText(font, label, layout.panelX + layout.panelWidth / 2,
				layout.boardY + Math.max(0, (layout.panelHeight - INFO_HEIGHT - font.lineHeight) / 2),
				textColor.argb());
		}

		for (SlotLayout slotLayout : layout.slotLayouts) {
			SlotVisual visual = slotLayout.visual;
			int radius = Math.min(layout.radius, slotLayout.size / 2);
			if (visual.cardState != null) {
				int fill = slotColor(visual);
				int border = superpairBorderColor(visual.cardState);
				float feedback = superpairsFeedback(visual.boardSlot.slot.index);
				if (feedback > 0.0f) {
					// The server reveal can arrive a frame later. This flash makes the local click
					// immediately legible without inventing an item or changing solver state.
					fill = GuiTheme.lerpColor(fill, 0xFFFFFFFF, 0.18f * feedback);
					border = GuiTheme.lerpColor(border, 0xFFFFFFFF, feedback);
				}
				GuiTheme.roundedRectBordered(graphics, slotLayout.x, slotLayout.y, slotLayout.size,
					slotLayout.size, radius, fill, fill, border);
			} else {
				GuiTheme.roundedRect(graphics, slotLayout.x, slotLayout.y, slotLayout.size,
					slotLayout.size, radius, slotColor(visual));
			}

			if (solverView.type() == ExperimentType.SUPERPAIRS) {
				renderSuperpairsContent(graphics, font, slotLayout, mouseX, mouseY);
			} else if (!visual.orderLabel.isBlank()) {
				String orderLabel = fitText(font, visual.orderLabel, Math.max(1, slotLayout.size - 4));
				graphics.centeredText(font, orderLabel,
					slotLayout.x + slotLayout.size / 2,
					slotLayout.y + Math.max(0, (slotLayout.size - font.lineHeight) / 2), textColor.argb());
			}
		}
		graphics.disableScissor();
		graphics.disableScissor();
		return true;
	}

	private void renderStatus(GuiGraphicsExtractor graphics, Font font, Layout layout) {
		List<String> lines = statusLines();
		if (lines.isEmpty()) return;
		int x = layout.panelX + 7;
		int available = Math.max(1, layout.panelWidth - 14);
		int y = layout.panelY + 5;
		for (int i = 0; i < Math.min(3, lines.size()); i++) {
			String line = fitText(font, lines.get(i), available);
			if (!line.isBlank()) {
				int color = i == 0 ? textColor.argb() : GuiTheme.withOpacity(textColor.argb(), 0.78f);
				graphics.text(font, line, x, y, color);
			}
			y += font.lineHeight + 1;
		}
		int dividerY = Math.min(layout.panelY + INFO_HEIGHT - 3,
			layout.panelY + Math.max(0, layout.panelHeight - 2));
		graphics.fill(layout.panelX + Math.min(6, Math.max(0, layout.panelWidth / 2)), dividerY,
			layout.panelX + Math.max(1, layout.panelWidth - Math.min(6, Math.max(0, layout.panelWidth / 2))),
			dividerY + 1,
			GuiTheme.withOpacity(unknownColor.argb(), 0.45f));
	}

	private List<String> statusLines() {
		Session session = session();
		SolverView solverView = solverView();
		if (session == null || solverView.type() == null) return List.of();
		String typeName = solverView.type().displayName();
		String phase = displayPhase().label;
		if (phase.isBlank()) phase = "SOLVE";
		String instruction = session.instruction;
		if (instruction == null || instruction.isBlank()) instruction = phase;
		String budget = session.serverClicksRemaining >= 0
			? " • Budget " + session.serverClicksRemaining : "";

		if (solverView.type() == ExperimentType.SUPERPAIRS) {
			SuperpairsBoard.View board = solverView.superpairs();
			String selected = board.selectedSlot() < 0 || cardPosition(board.selectedSlot(), board) <= 0
				? "none" : "#" + cardPosition(board.selectedSlot(), board);
			String waiting = board.waitingForReveal() ? " • revealing" : "";
			int totalCards = board.cards().size();
			int cardsLeft = Math.max(0, totalCards - board.resolvedCount());
			return List.of(typeName + " • " + phase,
				"Status: " + instruction + " • Selected: " + selected + waiting,
				"Collected: " + board.collectedCount() / 2 + "/" + board.totalPairs() + " pairs • "
					+ cardsLeft + " cards left • Discovered: " + board.discoveredCount() + "/" + totalCards + budget);
		}

		int length = solverView.sequence().size();
		int progress = Math.max(0, Math.min(length, solverView.visualIndex()));
		String lengthText = length > 0 ? Integer.toString(length) : "?";
		String progressText = length > 0 ? progress + "/" + lengthText : "0/?";
		String remainingText = length > 0 ? Integer.toString(Math.max(0, length - progress)) : "?";
		String next = solverView.current().map(step -> "#" + (step.index() + 1)).orElse("none");
		String finished = displayPhase() == Phase.MAX_CLICKS ? "complete"
			: solverView.milestoneReached() ? "target reached" : Math.max(0, solverView.completedRounds()) + " rounds";
		return List.of(typeName + " • " + phase,
			"Status: " + instruction,
			"Progress: " + progressText + " • Next: " + next + " • "
				+ remainingText + " clicks left • Done: " + finished + budget);
	}

	private static int cardPosition(int slotId, SuperpairsBoard.View board) {
		for (int i = 0; i < board.cards().size(); i++) {
			if (board.cards().get(i).slotId() == slotId) return i + 1;
		}
		return 0;
	}

	private static String fitText(Font font, String text, int available) {
		if (text == null || text.isBlank() || available <= 0) return "";
		if (font.width(text) <= available) return text;
		String ellipsis = "…";
		if (available <= font.width(ellipsis)) return ellipsis;
		return font.plainSubstrByWidth(text, available - font.width(ellipsis)) + ellipsis;
	}

	private void renderSuperpairsContent(GuiGraphicsExtractor graphics, Font font, SlotLayout slotLayout,
		int mouseX, int mouseY) {
		BoardSlot boardSlot = slotLayout.visual.boardSlot;
		SuperpairsBoard.CardState state = slotLayout.visual.cardState;
		ItemStack stack = boardSlot.renderStack;
		if (!stack.isEmpty()) {
			int itemX = slotLayout.x + Math.max(0, (slotLayout.size - 16) / 2);
			int itemY = slotLayout.y + Math.max(1, (slotLayout.size - 20) / 2);
			graphics.item(stack, itemX, itemY);
			graphics.itemDecorations(font, stack, itemX, itemY);
			if (slotLayout.contains(mouseX, mouseY)) {
				graphics.setTooltipForNextFrame(font, stack, mouseX, mouseY);
			}
		} else if (state == SuperpairsBoard.CardState.SELECTED) {
			// Show immediate local acknowledgement while the real item is still in flight.
			graphics.centeredText(font, "?", slotLayout.x + slotLayout.size / 2,
				slotLayout.y + Math.max(0, (slotLayout.size - font.lineHeight) / 2),
				superpairTextColor(state));
		}
		if (!boardSlot.name.isBlank() && slotLayout.size >= 34) {
			String label = font.plainSubstrByWidth(boardSlot.name, Math.max(1, slotLayout.size - 4));
			graphics.centeredText(font, label, slotLayout.x + slotLayout.size / 2,
				slotLayout.y + slotLayout.size - font.lineHeight, superpairTextColor(state));
		}
		if (state == SuperpairsBoard.CardState.RESOLVED) {
			String marker = "✓";
			graphics.text(font, marker, slotLayout.x + slotLayout.size - font.width(marker) - 3,
				slotLayout.y + 3, superpairTextColor(state));
		}
	}

	/**
	 * Handles a click on the replacement surface. The dispatcher is the mixin's invoker for
	 * vanilla's protected slotClicked method; this module never constructs or sends a packet.
	 */
	public boolean handleCustomClick(AbstractContainerScreen<?> screen, MouseButtonEvent event,
		SlotClickDispatcher dispatcher) {
		if (!ownsScreen(screen)) return false;
		observe(screen);
		Layout layout = layout(screen, Math.max(1, screen.width), Math.max(1, screen.height));
		if (!layout.contains(event.x(), event.y())) {
			// Leave clicks outside the replacement surface to vanilla so its normal close behavior is
			// preserved. The surface itself still covers every original puzzle slot.
			return false;
		}
		SlotLayout hit = null;
		for (SlotLayout candidate : layout.slotLayouts) {
			if (candidate.contains(event.x(), event.y())) {
				hit = candidate;
				break;
			}
		}
		if (hit != null) AutoExperimentsModule.INSTANCE.pauseAfterManualInput(screen);
		// Experiment buttons are left-click controls. Consume other buttons inside the replacement
		// surface so they cannot fall through to the hidden container slots.
		if (event.button() != 0) return true;
		if (hit == null || !accepts(hit.visual)) return true;

		BoardSlot boardSlot = hit.visual.boardSlot;
		if (solverView().type() == ExperimentType.SUPERPAIRS) {
			if (!ExperimentController.INSTANCE.dispatchSuperpairsClick(screen, boardSlot.slot.index,
				event.button(), dispatcher)) return true;
			superpairsFeedbackSlot = boardSlot.slot.index;
			superpairsFeedbackStartedNanos = System.nanoTime();
		} else {
			if (!ExperimentController.INSTANCE.dispatchSequenceClick(screen, boardSlot.slot.index, dispatcher)) {
				return true;
			}
		}
		playClickSound();
		return true;
	}

	private boolean accepts(SlotVisual visual) {
		SolverView solverView = solverView();
		if (solverView.phase() != ExperimentPhase.SOLVE) return false;
		if (displayPhase() != Phase.SOLVE) return false;
		if (!preventMisclicks.value()) return true;
		if (solverView.type() == ExperimentType.SUPERPAIRS) {
			return visual.cardState != SuperpairsBoard.CardState.RESOLVED;
		}
		// Future cells are informative. With protection enabled only the actual next click is legal.
		return visual.priority == Priority.CURRENT;
	}

	private void observe(AbstractContainerScreen<?> screen) {
		ExperimentController.INSTANCE.observeScreen(screen);
		alertAtMaxClickMilestone();
	}

	private Phase displayPhase() {
		Session session = session();
		SolverView solverView = solverView();
		if (session == null) return Phase.MEMORIZE;
		boolean milestoneReached = (solverView.phase() == ExperimentPhase.SOLVE
			|| solverView.phase() == ExperimentPhase.ROUND_COMPLETE) && solverView.milestoneReached();
		if (session.phase == Phase.MAX_CLICKS || solverView.phase() == ExperimentPhase.COMPLETE
			|| milestoneReached) return Phase.MAX_CLICKS;
		return switch (solverView.phase()) {
			case MEMORIZE -> Phase.MEMORIZE;
			case WAITING -> Phase.WAITING;
			case SOLVE -> Phase.SOLVE;
			case ROUND_COMPLETE -> Phase.ROUND_COMPLETE;
			default -> session.phase;
		};
	}

	private void playSound(TextSetting sound, NumberSetting pitch, NumberSetting volume) {
		Identifier id = Identifier.tryParse(sound.value().trim());
		if (id == null) return;
		SoundEvent event = BuiltInRegistries.SOUND_EVENT.getValue(id);
		if (event == null) return;
		Minecraft.getInstance().getSoundManager().play(
			SimpleSoundInstance.forUI(event, pitch.value(), volume.value()));
	}

	private void playClickSound() {
		if (!clickSounds.value()) return;
		playSound(clickSound, clickSoundPitch, clickSoundVolume);
	}

	private int slotColor(SlotVisual visual) {
		if (visual.cardState != null) {
			return switch (visual.cardState) {
				case SELECTED -> currentColor.argb();
				case MATCH -> SUPERPAIRS_MATCH_COLOR;
				case RESOLVED -> SUPERPAIRS_COLLECTED_COLOR;
				case KNOWN -> SUPERPAIRS_KNOWN_COLOR;
				case UNKNOWN -> SUPERPAIRS_UNKNOWN_COLOR;
			};
		}
		return switch (visual.priority) {
			case CURRENT -> currentColor.argb();
			case NEXT -> nextColor.argb();
			case NEXT_NEXT -> nextNextColor.argb();
			case NEXT_NEXT_NEXT -> nextNextNextColor.argb();
			case NONE -> unknownColor.argb();
		};
	}

	private static int superpairBorderColor(SuperpairsBoard.CardState state) {
		return switch (state) {
			case SELECTED -> SUPERPAIRS_SELECTED_BORDER;
			case MATCH -> SUPERPAIRS_MATCH_BORDER;
			case RESOLVED -> SUPERPAIRS_COLLECTED_BORDER;
			case KNOWN -> SUPERPAIRS_KNOWN_BORDER;
			case UNKNOWN -> SUPERPAIRS_UNKNOWN_BORDER;
		};
	}

	private static int superpairTextColor(SuperpairsBoard.CardState state) {
		return switch (state) {
			case UNKNOWN, SELECTED, MATCH -> 0xFF16161B;
			case KNOWN, RESOLVED -> 0xFFFFFFFF;
		};
	}

	private float superpairsFeedback(int slotId) {
		if (superpairsFeedbackSlot != slotId) return 0.0f;
		long elapsed = System.nanoTime() - superpairsFeedbackStartedNanos;
		if (elapsed <= 0) return 1.0f;
		if (elapsed >= SUPERPAIRS_CLICK_FEEDBACK_NANOS) {
			superpairsFeedbackSlot = -1;
			return 0.0f;
		}
		return 1.0f - elapsed / (float) SUPERPAIRS_CLICK_FEEDBACK_NANOS;
	}

	@Override
	protected void onDisable() {
		maxAlertedSessionGeneration = Long.MIN_VALUE;
		superpairsFeedbackSlot = -1;
		ExperimentController.INSTANCE.onOwnerDisabled();
	}

	public static boolean isRecognized(AbstractContainerScreen<?> screen) {
		return screen != null && ExperimentType.fromTitle(screen.getTitle().getString()).isPresent();
	}

	private boolean isSupported(AbstractContainerScreen<?> screen) {
		if (!isRecognized(screen)) return false;
		return supports(ExperimentType.fromTitle(screen.getTitle().getString()).orElseThrow());
	}

	/** Used by both mixin extraction hooks so vanilla cannot draw the original puzzle underneath. */
	public boolean ownsScreen(AbstractContainerScreen<?> screen) {
		return isEnabled() && isSupported(screen);
	}

	/** Allows the mixin to distinguish our intentional vanilla dispatch from click-through input. */
	public boolean isDispatchingCustomClick() {
		return ExperimentController.INSTANCE.isDispatchingCustomClick();
	}

	private static boolean isBoardSlot(ExperimentType type, ExperimentTier tier, Slot slot) {
		return slot != null && type != null
			&& ExperimentBoardGeometry.forExperiment(type, tier).containsSlot(slot.index);
	}

	private List<SlotVisual> visibleSlotVisuals() {
		Session session = session();
		SolverView solverView = solverView();
		if (session == null) return List.of();
		if (solverView.type() == ExperimentType.SUPERPAIRS) {
			Map<Integer, SuperpairsBoard.CardState> states = new LinkedHashMap<>();
			for (SuperpairsBoard.Card card : solverView.superpairs().cards()) {
				states.put(card.slotId(), card.state());
			}
			List<SlotVisual> result = new ArrayList<>(session.slots.size());
			for (BoardSlot slot : session.slots) {
				SuperpairsBoard.CardState state = states.getOrDefault(slot.slot.index,
					SuperpairsBoard.CardState.UNKNOWN);
				Priority priority = switch (state) {
					case SELECTED -> Priority.CURRENT;
					case MATCH -> Priority.NEXT;
					default -> Priority.NONE;
				};
				result.add(new SlotVisual(slot, priority, "", state));
			}
			return List.copyOf(result);
		}
		if (solverView.phase() != ExperimentPhase.SOLVE || displayPhase() != Phase.SOLVE) return List.of();

		Map<Integer, BoardSlot> slotsById = new LinkedHashMap<>();
		for (BoardSlot slot : session.slots) slotsById.put(slot.slot.index, slot);
		Map<Integer, MutableSlotVisual> visible = new LinkedHashMap<>();
		int visibleSteps = previewSteps(solverView.type());
		List<SequenceStep> upcoming = solverView.upcoming(visibleSteps);
		for (int offset = 0; offset < upcoming.size(); offset++) {
			SequenceStep step = upcoming.get(offset);
			Priority priority = Priority.fromOffset(offset);
			for (int slotId : step.slotIds()) {
				BoardSlot slot = slotsById.get(slotId);
				if (slot != null) {
					visible.computeIfAbsent(slotId, ignored -> new MutableSlotVisual(slot))
						.add(step.index() + 1, priority);
				}
			}
		}
		List<SlotVisual> result = new ArrayList<>(visible.size());
		for (BoardSlot slot : session.slots) {
			MutableSlotVisual visual = visible.get(slot.slot.index);
			if (visual != null) result.add(visual.immutable());
		}
		return List.copyOf(result);
	}

	/** Number of sequence buttons drawn for a puzzle, including the current button. */
	int previewSteps(ExperimentType type) {
		return 1 + futureClicks(type);
	}

	private int futureClicks(ExperimentType type) {
		return switch (type) {
			case CHRONOMATRON -> chronomatronFutureClicks.intValue();
			case ULTRASEQUENCER -> ultrasequencerFutureClicks.intValue();
			case SUPERPAIRS -> 0;
		};
	}

	private Layout layout(AbstractContainerScreen<?> screen, int availableWidth, int availableHeight) {
		Session session = session();
		int width = Math.max(1, Math.min(screen.width, availableWidth));
		int height = Math.max(1, Math.min(screen.height, availableHeight));
		ExperimentType type = session == null ? ExperimentType.ULTRASEQUENCER : session.type;
		ExperimentTier tier = session == null ? ExperimentTier.UNKNOWN : session.tier;
		ExperimentBoardGeometry geometry = ExperimentBoardGeometry.forExperiment(type, tier);
		int columns = geometry.columns();
		int rows = geometry.rows();
		int logicalGap = slotGap.intValue();
		int logicalBoardWidth = columns * BASE_SLOT_SIZE + (columns - 1) * logicalGap;
		int logicalBoardHeight = rows * BASE_SLOT_SIZE + (rows - 1) * logicalGap;
		int logicalPanelWidth = logicalBoardWidth + BASE_PANEL_PADDING * 2;
		int logicalPanelHeight = logicalBoardHeight + BASE_PANEL_PADDING * 2;
		float fitScale = Math.min(
			Math.max(1, width - SCREEN_MARGIN * 2) / (float) logicalPanelWidth,
			Math.max(1, height - SCREEN_MARGIN * 2 - INFO_HEIGHT) / (float) logicalPanelHeight);
		float scale = Math.max(0.05f, Math.min(size.value(), fitScale));
		int panelWidth = Math.max(1, Math.min(width, Math.round(logicalPanelWidth * scale)));
		int boardHeight = Math.round(logicalPanelHeight * scale);
		int panelHeight = Math.max(1, Math.min(height, boardHeight + INFO_HEIGHT));
		int panelX = Math.max(0, (width - panelWidth) / 2);
		int panelY = Math.max(0, (height - panelHeight) / 2);
		int boardY = panelY + Math.min(INFO_HEIGHT, Math.max(0, panelHeight - 1));
		int slotSize = Math.max(1, Math.round(BASE_SLOT_SIZE * scale));
		int radius = Math.max(0, Math.min(Math.round(roundness.intValue() * scale),
			Math.min(panelWidth, panelHeight) / 2));

		List<SlotVisual> visuals = visibleSlotVisuals();
		List<SlotLayout> slotLayouts = new ArrayList<>(visuals.size());
		for (SlotVisual visual : visuals) {
			int slotId = visual.boardSlot.slot.index;
			int column = geometry.column(slotId);
			int row = geometry.row(slotId);
			if (column < 0 || column >= columns || row < 0 || row >= rows) continue;
			int x = panelX + Math.round((BASE_PANEL_PADDING
				+ column * (BASE_SLOT_SIZE + logicalGap)) * scale);
			int y = panelY + Math.round((BASE_PANEL_PADDING
				+ row * (BASE_SLOT_SIZE + logicalGap)) * scale);
			y += boardY - panelY;
			if (x < panelX || y < panelY || x + slotSize > panelX + panelWidth
				|| y + slotSize > panelY + panelHeight) continue;
			slotLayouts.add(new SlotLayout(visual, x, y, slotSize));
		}
		return new Layout(panelX, panelY, panelWidth, panelHeight, boardY, radius,
			List.copyOf(slotLayouts));
	}

	@FunctionalInterface
	public interface SlotClickDispatcher {
		void dispatch(Slot slot, int slotId, int button, ContainerInput input);
	}

	private enum Phase {
		MEMORIZE("MEMORIZE"),
		WAITING("WAITING"),
		SOLVE(""),
		ROUND_COMPLETE("ROUND COMPLETE"),
		MAX_CLICKS("MAX CLICKS");

		private final String label;

		Phase(String label) {
			this.label = label;
		}
	}

	private enum Priority {
		CURRENT(0),
		NEXT(1),
		NEXT_NEXT(2),
		NEXT_NEXT_NEXT(3),
		NONE(4);

		private final int rank;

		Priority(int rank) {
			this.rank = rank;
		}

		private static Priority fromOffset(int offset) {
			return switch (offset) {
				case 0 -> CURRENT;
				case 1 -> NEXT;
				case 2 -> NEXT_NEXT;
				default -> NEXT_NEXT_NEXT;
			};
		}
	}

	static final class Session {
		final ExperimentType type;
		final ExperimentTier tier;
		final int menuId;
		final Map<Integer, ItemStack> rememberedStacks = new LinkedHashMap<>();
		final List<ItemStack> superpairTypes = new ArrayList<>();
		final Map<Integer, String> superpairValues = new LinkedHashMap<>();
		// Superpairs redraws are intentionally driven by invalidation. The menu is updated every tick
		// even when its board has not changed, so rebuilding tooltip names and immutable stack copies in
		// that hot path caused a large frame-time spike when the custom screen opened.
		final Map<Integer, ItemStack> superpairLiveStacks = new LinkedHashMap<>();
		ItemStack cachedStatusStack = ItemStack.EMPTY;
		StatusInfo cachedStatusInfo = new StatusInfo("", -1);
		boolean statusCacheInitialized;
		final SuperpairsCacheGate superpairCache = new SuperpairsCacheGate();
		List<BoardSlot> slots = List.of();
		long renderSlotsRevision = Long.MIN_VALUE;
		String instruction = "";
		Phase phase = Phase.WAITING;
		final List<SlotUpdate> slotUpdates = new ArrayList<>();
		int serverClicksRemaining = -1;
		AbstractContainerMenu attachedMenu;
		long updateSequence;
		final ContainerListener containerListener = new ContainerListener() {
			@Override
			public void slotChanged(AbstractContainerMenu handler, int slotId, ItemStack stack) {
				if (slotId < 0 || type == null) return;
				ItemStack updateStack = stack == null ? ItemStack.EMPTY : stack.copy();
				// Ultrasequencer is reconstructed from the authoritative menu once per client tick.
				// Its pane animation can update many slots in one packet; queueing a complete board
				// snapshot for every setter only repeats work and cannot improve the model.
				if (type == ExperimentType.ULTRASEQUENCER) return;
				if (slotId == 49) {
					if (type == ExperimentType.SUPERPAIRS) {
						// The next tick reads the cached status stack once. There is no useful historical
						// solver event here, and building an item tooltip for every opening callback is
						// needlessly expensive.
						superpairCache.invalidateObservation();
						return;
					}
					enqueueSlotUpdate(handler, SlotUpdate.Kind.STATUS, slotId, updateStack,
						updateStack.isEmpty() ? "" : updateStack.getHoverName().getString());
					return;
				}
				if (!ExperimentBoardGeometry.forExperiment(type, tier).containsSlot(slotId)) return;
				if (type == ExperimentType.SUPERPAIRS) {
					superpairCache.invalidateRender();
					superpairCache.invalidateObservation();
					// Superpairs is read from the authoritative menu snapshot on the next client tick.
					// Do not associate an arbitrary callback with the last local click: a late reveal can
					// belong to an earlier card, and the board remembers every revealed slot independently.
					return;
				}
				// Capture a complete immutable frame at the callback boundary. Rebuilding an older event
				// from the live menu later lets a burst of updates borrow future card values and corrupts
				// Superpairs comparisons.
				enqueueSlotUpdate(handler, SlotUpdate.Kind.BOARD, slotId, updateStack, instruction(handler));
			}

			@Override
			public void dataChanged(AbstractContainerMenu handler, int property, int value) {
			}
		};

		Session(ExperimentType type, ExperimentTier tier, int menuId) {
			this.type = type;
			this.tier = tier;
			this.menuId = menuId;
		}

		private void enqueueSlotUpdate(AbstractContainerMenu handler, SlotUpdate.Kind kind, int slotId,
			ItemStack stack, String status) {
			Map<Integer, ItemStack> board = new LinkedHashMap<>();
			for (Slot slot : handler.slots) {
				if (isExperimentBoardSlot(type, tier, slot)) board.put(slot.index, slot.getItem().copy());
			}
			if (kind == SlotUpdate.Kind.BOARD && isExperimentBoardSlot(type, tier, findSlot(handler, slotId))) {
				board.put(slotId, stack.copy());
			}
			slotUpdates.add(new SlotUpdate(kind, slotId, stack, status, ++updateSequence, board));
		}

		private static Slot findSlot(AbstractContainerMenu menu, int slotId) {
			for (Slot slot : menu.slots) if (slot.index == slotId) return slot;
			return null;
		}

		private static boolean isExperimentBoardSlot(ExperimentType type, ExperimentTier tier, Slot slot) {
			return slot != null && !(slot.container instanceof Inventory) && isBoardSlot(type, tier, slot);
		}

		boolean observe(AbstractContainerScreen<?> screen) {
			attach(screen.getMenu());
			if (type == ExperimentType.SUPERPAIRS) return observeSuperpairs(screen);
			List<Slot> menuSlots = new ArrayList<>();
			for (Slot slot : screen.getMenu().slots) {
				if (!(slot.container instanceof Inventory) && isBoardSlot(type, tier, slot)) menuSlots.add(slot);
			}
			StatusInfo statusInfo = status(screen.getMenu());
			instruction = statusInfo.text();
			if (statusInfo.clicksRemaining() >= 0) serverClicksRemaining = statusInfo.clicksRemaining();
			menuSlots.sort(Comparator.comparingInt(slot -> slot.index));
			captureSuperpairReveal(screen);

			Phase previousPhase = phase;
			phase = phaseFor(type, instruction, phase);
			if (type == ExperimentType.SUPERPAIRS && previousPhase == Phase.MAX_CLICKS
				&& phase != Phase.MAX_CLICKS) {
				resetSuperpairMemory();
			}
			// Do not touch solver memory here. The queued listener frames are drained immediately
			// afterward and must be interpreted against the exact historical order they captured.
			if (slots.isEmpty() || renderSlotsRevision != updateSequence) {
				slots = List.copyOf(boardSlots(menuSlots, false));
			}
			return true;
		}

		private boolean observeSuperpairs(AbstractContainerScreen<?> screen) {
			StatusInfo statusInfo = cachedStatus(screen.getMenu());
			String previousInstruction = instruction;
			int previousClicks = serverClicksRemaining;
			instruction = statusInfo.text();
			if (statusInfo.clicksRemaining() >= 0) serverClicksRemaining = statusInfo.clicksRemaining();
			if (!previousInstruction.equals(instruction) || previousClicks != serverClicksRemaining) {
				superpairCache.invalidateObservation();
			}

			Phase previousPhase = phase;
			phase = phaseFor(type, instruction, phase);
			if (previousPhase == Phase.MAX_CLICKS && phase != Phase.MAX_CLICKS) {
				resetSuperpairMemory();
			}
			refreshRenderSlots(screen);
			return true;
		}

		void refreshRenderSlots(AbstractContainerScreen<?> screen) {
			if (type == ExperimentType.SUPERPAIRS) {
				captureSuperpairReveal(screen);
				if (superpairLiveBoardChanged(screen)) {
					superpairCache.invalidateRender();
				}
				if (!superpairCache.consumeRenderDirty()) return;
				List<Slot> menuSlots = new ArrayList<>();
				for (Slot slot : screen.getMenu().slots) {
					if (isExperimentBoardSlot(type, tier, slot)) menuSlots.add(slot);
				}
				menuSlots.sort(Comparator.comparingInt(slot -> slot.index));
				slots = List.copyOf(boardSlots(menuSlots, true));
				superpairLiveStacks.clear();
				for (Slot slot : menuSlots) superpairLiveStacks.put(slot.index, slot.getItem().copy());
				return;
			}
			if (!slots.isEmpty() && renderSlotsRevision == updateSequence) return;
			captureSuperpairReveal(screen);
			List<Slot> menuSlots = new ArrayList<>();
			for (Slot slot : screen.getMenu().slots) {
				if (!(slot.container instanceof Inventory) && isBoardSlot(type, tier, slot)) menuSlots.add(slot);
			}
			menuSlots.sort(Comparator.comparingInt(slot -> slot.index));
			slots = List.copyOf(boardSlots(menuSlots, true));
			renderSlotsRevision = updateSequence;
		}

		private List<BoardSlot> boardSlots(List<Slot> menuSlots, boolean rememberReveals) {
			List<BoardSlot> result = new ArrayList<>(menuSlots.size());
			for (Slot slot : menuSlots) {
				ItemStack visible = slot.getItem();
				boolean hidden = type == ExperimentType.SUPERPAIRS && isHiddenCard(visible);
				ItemStack stored = rememberedStacks.get(slot.index);
				ItemStack renderStack;
				if (type != ExperimentType.SUPERPAIRS) renderStack = visible.copy();
				else if ((hidden || visible.isEmpty()) && stored != null) renderStack = stored.copy();
				else if (hidden) renderStack = ItemStack.EMPTY;
				else renderStack = visible.copy();
				String name = type == ExperimentType.SUPERPAIRS && !renderStack.isEmpty()
					? renderStack.getHoverName().getString() : "";
				result.add(new BoardSlot(slot, renderStack, name));
			}
			return List.copyOf(result);
		}

		void attach(AbstractContainerMenu menu) {
			if (attachedMenu == menu) return;
			if (attachedMenu != null) attachedMenu.removeSlotListener(containerListener);
			attachedMenu = menu;
			menu.addSlotListener(containerListener);
		}

		void close() {
			if (attachedMenu != null) attachedMenu.removeSlotListener(containerListener);
			attachedMenu = null;
			slotUpdates.clear();
			superpairLiveStacks.clear();
		}

		private void resetSuperpairMemory() {
			rememberedStacks.clear();
			superpairTypes.clear();
			superpairValues.clear();
			superpairLiveStacks.clear();
			superpairCache.invalidateRender();
		}

		boolean takeSuperpairsObservationDirty() {
			return superpairCache.consumeObservationDirty();
		}

		List<SlotUpdate> drainSlotUpdates() {
			if (slotUpdates.isEmpty()) return List.of();
			List<SlotUpdate> updates = List.copyOf(slotUpdates);
			slotUpdates.clear();
			return updates;
		}

		ExperimentSnapshot snapshot(AbstractContainerScreen<?> screen, boolean predictPending) {
			return snapshot(screen, predictPending, null);
		}

		ExperimentSnapshot snapshot(AbstractContainerScreen<?> screen, boolean ignoredPredictPending,
			SlotUpdate update) {
			if (type == ExperimentType.SUPERPAIRS && update != null && update.kind == SlotUpdate.Kind.BOARD) {
				for (Map.Entry<Integer, ItemStack> entry : update.boardStacks.entrySet()) {
					if (!isHiddenCard(entry.getValue())) rememberSuperpairStack(entry.getKey(), entry.getValue());
				}
			}
			List<ExperimentCell> cells = new ArrayList<>(slots.size());
			String paneColor = null;
			for (BoardSlot slot : slots) {
				ItemStack captured = update == null ? null : update.boardStacks.get(slot.slot.index);
				ItemStack stack = captured == null ? slot.slot.getItem().copy() : captured.copy();
				if (type == ExperimentType.ULTRASEQUENCER && paneColor == null) {
					paneColor = ultrasequencerPaneColor(stack);
				}
				boolean hidden = type == ExperimentType.SUPERPAIRS && isHiddenCard(stack);
				boolean revealed = !stack.isEmpty() && !hidden;
				String value = switch (type) {
					case CHRONOMATRON -> chronomatronValue(stack);
					case SUPERPAIRS -> superpairValue(slot.slot.index, stack, revealed);
					case ULTRASEQUENCER -> stack.getHoverName().getString();
				};
				int number = type == ExperimentType.ULTRASEQUENCER
					? ultrasequencerNumber(stack, value) : parseNumber(value);
				boolean highlighted = type == ExperimentType.CHRONOMATRON && !stack.isEmpty()
					&& stack.hasFoil();
				// An unmatched Superpairs card can be sent as AIR while Hypixel hides it again. The
				// remembered value must remain playable; only the board model marks a pair resolved after
				// both matching reveals are observed.
				boolean removed = type != ExperimentType.SUPERPAIRS && stack.isEmpty();
				cells.add(new ExperimentCell(slot.slot.index, value, number, revealed, highlighted, removed));
			}
			String status = update != null && update.kind == SlotUpdate.Kind.STATUS ? update.status
				: (update != null && !update.status.isBlank() ? update.status : instruction);
			long snapshotRevision = update == null ? updateSequence : update.sequence;
			return new ExperimentSnapshot(screen.getTitle().getString(), status, cells,
				paneColor, snapshotRevision, update != null && update.kind == SlotUpdate.Kind.STATUS);
		}

		private String superpairValue(int slotId, ItemStack stack, boolean revealed) {
			ItemStack stored = rememberedStacks.get(slotId);
			if (revealed) {
				rememberSuperpairStack(slotId, stack);
				stored = rememberedStacks.get(slotId);
			}
			if (stored == null) return null;
			ItemStack identity = stored;
			return superpairValues.computeIfAbsent(slotId, ignored -> superpairKey(identity));
		}

		private void captureSuperpairReveal(AbstractContainerScreen<?> screen) {
			if (type != ExperimentType.SUPERPAIRS) return;
			for (Slot slot : screen.getMenu().slots) {
				if (!isExperimentBoardSlot(type, tier, slot)) continue;
				ItemStack stack = slot.getItem();
				if (!isHiddenCard(stack)) rememberSuperpairStack(slot.index, stack);
			}
		}

		private void rememberSuperpairStack(int slotId, ItemStack stack) {
			if (slotId < 0 || stack == null || isHiddenCard(stack)) return;
			ItemStack copy = stack.copy();
			if (!rememberedStacks.containsKey(slotId)) {
				rememberedStacks.put(slotId, copy);
				superpairCache.invalidateRender();
			}
			superpairValues.computeIfAbsent(slotId, ignored -> superpairKey(copy));
		}

		private String superpairKey(ItemStack stack) {
			for (int i = 0; i < superpairTypes.size(); i++) {
				if (ItemStack.matches(superpairTypes.get(i), stack)) return "pair-" + i;
			}
			superpairTypes.add(stack.copy());
			return "pair-" + (superpairTypes.size() - 1);
		}

		private static Phase phaseFor(ExperimentType type, String status, Phase previous) {
			if (!ExperimentPhase.isKnownStatus(type, status)) return previous;
			return switch (ExperimentPhase.detect(type, status)) {
				case MEMORIZE -> Phase.MEMORIZE;
				case WAITING -> Phase.WAITING;
				case SOLVE -> Phase.SOLVE;
				case ROUND_COMPLETE -> Phase.ROUND_COMPLETE;
				case COMPLETE -> Phase.MAX_CLICKS;
				case IDLE -> Phase.WAITING;
			};
		}

		private static String instruction(AbstractContainerScreen<?> screen) {
			return instruction(screen.getMenu());
		}

		private static String instruction(AbstractContainerMenu menu) {
			return status(menu).text();
		}

		private StatusInfo cachedStatus(AbstractContainerMenu menu) {
			ItemStack current = statusStack(menu);
			if (!statusCacheInitialized || !sameStack(cachedStatusStack, current)) {
				cachedStatusStack = current.copy();
				cachedStatusInfo = status(menu);
				statusCacheInitialized = true;
			}
			return cachedStatusInfo;
		}

		private boolean superpairLiveBoardChanged(AbstractContainerScreen<?> screen) {
			int boardCount = 0;
			for (Slot slot : screen.getMenu().slots) {
				if (!isExperimentBoardSlot(type, tier, slot)) continue;
				boardCount++;
				ItemStack previous = superpairLiveStacks.get(slot.index);
				if (previous == null || !sameStack(previous, slot.getItem())) return true;
			}
			return boardCount != superpairLiveStacks.size();
		}

		private static ItemStack statusStack(AbstractContainerMenu menu) {
			for (Slot slot : menu.slots) {
				if (slot.index == 49) return slot.getItem();
			}
			return ItemStack.EMPTY;
		}

		private static boolean sameStack(ItemStack first, ItemStack second) {
			return first != null && second != null && first.getCount() == second.getCount()
				&& ItemStack.matches(first, second);
		}

		private static StatusInfo status(AbstractContainerMenu menu) {
			for (Slot slot : menu.slots) {
				if (slot.index != 49 || slot.getItem().isEmpty()) continue;
				ItemStack stack = slot.getItem();
				List<String> lines = new ArrayList<>();
				lines.add(stack.getHoverName().getString());
				Minecraft client = Minecraft.getInstance();
				Item.TooltipContext context = client.level == null
					? Item.TooltipContext.EMPTY : Item.TooltipContext.of(client.level);
				stack.getTooltipLines(context, client.player, TooltipFlag.NORMAL)
					.forEach(line -> lines.add(line.getString()));
				return new StatusInfo(lines.get(0), parseClicksRemaining(lines));
			}
			return new StatusInfo("", -1);
		}

		private static int parseClicksRemaining(List<String> lines) {
			for (String line : lines) {
				if (line == null) continue;
				Matcher matcher = CLICKS_REMAINING_PATTERN.matcher(stripFormatting(line));
				if (!matcher.find()) continue;
				for (int i = 1; i <= matcher.groupCount(); i++) {
					if (matcher.group(i) == null) continue;
					try {
						return Math.max(0, Integer.parseInt(matcher.group(i)));
					} catch (NumberFormatException ignored) {
						return -1;
					}
				}
			}
			return -1;
		}

		private static String stripFormatting(String value) {
			return ChatText.plain(value == null ? "" : value).trim();
		}

		private static int parseNumber(String value) {
			if (value == null) return -1;
			try {
				return Integer.parseInt(value.trim());
			} catch (NumberFormatException ignored) {
				return -1;
			}
		}

		private static int ultrasequencerNumber(ItemStack stack, String value) {
			return stack != null && !stack.isEmpty() && value.matches("\\d+") ? stack.getCount() : -1;
		}

		/** Returns only non-black stained-glass-pane colors used for Ultrasequencer round edges. */
		static String ultrasequencerPaneColor(ItemStack stack) {
			if (stack == null || stack.isEmpty()) return null;
			Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
			if (id != null && id.getNamespace().equals("minecraft")) {
				String path = id.getPath();
				if (path.endsWith("_stained_glass_pane")) {
					String color = path.substring(0, path.length() - "_stained_glass_pane".length());
					return "black".equals(color) ? null : color;
				}
			}
			return null;
		}

		/** Exact item identity normalization used by Skyblocker's Chronomatron model. */
		private static String chronomatronValue(ItemStack stack) {
			if (stack == null || stack.isEmpty()) return null;
			Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
			if (id == null || !id.getNamespace().equals("minecraft")) return null;
			return switch (id.getPath()) {
				case "red_terracotta", "red_stained_glass" -> "red";
				case "orange_terracotta", "orange_stained_glass" -> "orange";
				case "yellow_terracotta", "yellow_stained_glass" -> "yellow";
				case "lime_terracotta", "lime_stained_glass" -> "lime";
				case "green_terracotta", "green_stained_glass" -> "green";
				case "cyan_terracotta", "cyan_stained_glass" -> "cyan";
				case "light_blue_terracotta", "light_blue_stained_glass" -> "light_blue";
				case "blue_terracotta", "blue_stained_glass" -> "blue";
				case "purple_terracotta", "purple_stained_glass" -> "purple";
				case "pink_terracotta", "pink_stained_glass" -> "pink";
				default -> null;
			};
		}

		private static String semanticValue(ItemStack stack) {
			if (stack == null || stack.isEmpty()) return null;
			Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
			if (id != null) {
				String path = id.getPath();
				if (path.endsWith("_terracotta")) return path.substring(0, path.length() - "_terracotta".length());
				if (path.endsWith("_stained_glass")) {
					return path.substring(0, path.length() - "_stained_glass".length());
				}
				if (path.endsWith("_stained_glass_pane")) {
					return path.substring(0, path.length() - "_stained_glass_pane".length());
				}
			}
			String name = stack.getHoverName().getString();
			String lower = name.toLowerCase(Locale.ROOT);
			if (lower.contains("terracotta") || lower.contains("stained glass")) {
				for (String color : List.of("red", "orange", "yellow", "lime", "green", "cyan",
					"light blue", "blue", "purple", "pink")) {
					if (lower.contains(color)) return color;
				}
			}
			return name;
		}

		private static boolean isHiddenCard(ItemStack stack) {
			if (stack == null || stack.isEmpty()) return true;
			Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
			// Hypixel uses cyan stained glass and a black stained-glass pane as the hidden cards. Do not
			// classify every glass variant as hidden: a real revealed card can itself be a stained-glass
			// item, and treating it as a placeholder loses a pair from the memory map.
			if (id != null && (id.getPath().equals("cyan_stained_glass")
				|| id.getPath().equals("black_stained_glass_pane"))) return true;
			String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
			return name.contains("click to reveal") || name.contains("unknown") || name.equals("?");
		}

		private record StatusInfo(String text, int clicksRemaining) {
			private StatusInfo {
				text = text == null ? "" : text.trim();
				clicksRemaining = Math.max(-1, clicksRemaining);
			}
		}

		record SlotUpdate(Kind kind, int slotId, ItemStack stack, String status,
			long sequence, Map<Integer, ItemStack> boardStacks) {
			enum Kind {
				BOARD,
				STATUS
			}

			SlotUpdate {
				if (kind == null) throw new IllegalArgumentException("kind must not be null");
				stack = stack == null ? ItemStack.EMPTY : stack.copy();
				status = status == null ? "" : status;
				Map<Integer, ItemStack> copy = new LinkedHashMap<>();
				if (boardStacks != null) {
					for (Map.Entry<Integer, ItemStack> entry : boardStacks.entrySet()) {
						if (entry.getKey() != null && entry.getValue() != null) {
							copy.put(entry.getKey(), entry.getValue().copy());
						}
					}
				}
				boardStacks = Map.copyOf(copy);
			}

			ChronomatronEvent chronomatronEvent() {
				return kind == Kind.STATUS
					? ChronomatronEvent.status(status)
					: ChronomatronEvent.board(slotId, chronomatronValue(stack),
						!stack.isEmpty() && stack.hasFoil());
			}
		}
	}

	/**
	 * Separates render-cache invalidation from solver observation invalidation. An unchanged
	 * Superpairs frame consumes neither path, which keeps the hot render/tick loop allocation-free.
	 */
	static final class SuperpairsCacheGate {
		private boolean renderDirty = true;
		private boolean observationDirty = true;

		void invalidateRender() {
			renderDirty = true;
			observationDirty = true;
		}

		void invalidateObservation() {
			observationDirty = true;
		}

		boolean consumeRenderDirty() {
			if (!renderDirty) return false;
			renderDirty = false;
			return true;
		}

		boolean consumeObservationDirty() {
			if (!observationDirty) return false;
			observationDirty = false;
			return true;
		}
	}

	private static final class MutableSlotVisual {
		private final BoardSlot boardSlot;
		private final List<Integer> orders = new ArrayList<>();
		private Priority priority = Priority.NONE;

		private MutableSlotVisual(BoardSlot boardSlot) {
			this.boardSlot = boardSlot;
		}

		private void add(int order, Priority candidate) {
			if (!orders.contains(order)) orders.add(order);
			if (candidate.rank < priority.rank) priority = candidate;
		}

		private SlotVisual immutable() {
			StringBuilder label = new StringBuilder();
			for (int order : orders) {
				if (!label.isEmpty()) label.append(' ');
				label.append(order);
			}
			return new SlotVisual(boardSlot, priority, label.toString(), null);
		}
	}

	private record BoardSlot(Slot slot, ItemStack renderStack, String name) {
	}

	private record SlotVisual(BoardSlot boardSlot, Priority priority, String orderLabel,
		SuperpairsBoard.CardState cardState) {
	}

	private record Layout(int panelX, int panelY, int panelWidth, int panelHeight, int boardY, int radius,
		List<SlotLayout> slotLayouts) {
		boolean contains(double mouseX, double mouseY) {
			return mouseX >= panelX && mouseX < panelX + panelWidth
				&& mouseY >= panelY && mouseY < panelY + panelHeight;
		}
	}

	private record SlotLayout(SlotVisual visual, int x, int y, int size) {
		boolean contains(double mouseX, double mouseY) {
			return mouseX >= x && mouseX < x + size && mouseY >= y && mouseY < y + size;
		}
	}
}
