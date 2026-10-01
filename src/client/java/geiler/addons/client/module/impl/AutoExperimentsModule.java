package geiler.addons.client.module.impl;

import geiler.addons.client.config.GeilerAddonsLog;
import geiler.addons.client.enchanting.AutoExperimentAutomation;
import geiler.addons.client.enchanting.AutoExperimentDelayRange;
import geiler.addons.client.enchanting.ExperimentBoardGeometry;
import geiler.addons.client.enchanting.ExperimentClickGate;
import geiler.addons.client.enchanting.ExperimentPhase;
import geiler.addons.client.enchanting.ExperimentTier;
import geiler.addons.client.enchanting.ExperimentType;
import geiler.addons.client.enchanting.SolverView;
import geiler.addons.client.enchanting.UltrasequencerSequenceExecutor;
import geiler.addons.client.mixin.AbstractContainerScreenInvoker;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.SettingGroup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/** Automatically replays the shared Chronomatron and Ultrasequencer solutions. */
public final class AutoExperimentsModule extends Module {
	public static final AutoExperimentsModule INSTANCE = new AutoExperimentsModule();

	private final BooleanSetting chronomatron;
	private final BooleanSetting ultrasequencer;
	private final NumberSetting firstClickDelay;
	private final NumberSetting minimumClickDelay;
	private final NumberSetting maximumClickDelay;
	private final BooleanSetting debug;
	private final AutoExperimentAutomation automation = new AutoExperimentAutomation();
	private final UltrasequencerSequenceExecutor ultrasequencerAutomation = new UltrasequencerSequenceExecutor();
	private String reportedDecision = "";

	private AutoExperimentsModule() {
		this(new Settings());
	}

	private AutoExperimentsModule(Settings settings) {
		super("Auto Experiments", "Automatically solves Chronomatron and Ultrasequencer.",
			Category.ENCHANTING, settings.chronomatron, settings.ultrasequencer,
			settings.firstClickDelay, settings.minimumClickDelay, settings.maximumClickDelay,
			settings.debug);
		chronomatron = settings.chronomatron;
		ultrasequencer = settings.ultrasequencer;
		firstClickDelay = settings.firstClickDelay;
		minimumClickDelay = settings.minimumClickDelay;
		maximumClickDelay = settings.maximumClickDelay;
		debug = settings.debug;
		group(
			new SettingGroup("Experiments", chronomatron, ultrasequencer),
			new SettingGroup("Timing", firstClickDelay, minimumClickDelay, maximumClickDelay),
			new SettingGroup("Diagnostics", debug)
		);
	}

	private static final class Settings {
		final BooleanSetting chronomatron = new BooleanSetting("Chronomatron", true);
		final BooleanSetting ultrasequencer = new BooleanSetting("Ultrasequencer", true);
		final NumberSetting firstClickDelay = new NumberSetting("First Click Delay (ms)", 0, 2000, 500, true);
		final NumberSetting minimumClickDelay = new NumberSetting("Minimum Click Delay (ms)", 0, 2000,
			AutoExperimentDelayRange.DEFAULT_MINIMUM_MILLIS, true);
		final NumberSetting maximumClickDelay = new NumberSetting("Maximum Click Delay (ms)", 0, 2000,
			AutoExperimentDelayRange.DEFAULT_MAXIMUM_MILLIS, true);
		final BooleanSetting debug = new BooleanSetting("Debug Automation", false);
	}

	public boolean supports(ExperimentType type) {
		return switch (type) {
			case CHRONOMATRON -> chronomatron.value();
			case ULTRASEQUENCER -> ultrasequencer.value();
			case SUPERPAIRS -> false;
		};
	}

	public BooleanSetting chronomatron() {
		return chronomatron;
	}

	public BooleanSetting ultrasequencer() {
		return ultrasequencer;
	}

	public NumberSetting firstClickDelay() {
		return firstClickDelay;
	}

	public NumberSetting minimumClickDelay() {
		return minimumClickDelay;
	}

	public NumberSetting maximumClickDelay() {
		return maximumClickDelay;
	}

	/** Read-only Auto cursor for the matching live Ultrasequencer screen and solver solution. */
	public Optional<UltrasequencerSequenceExecutor.ExecutionProgress> ultrasequencerPresentationProgress(
		AbstractContainerScreen<?> screen, SolverView solverView) {
		ExperimentController controller = ExperimentController.INSTANCE;
		if (!isEnabled() || !supports(ExperimentType.ULTRASEQUENCER)
			|| screen == null || Minecraft.getInstance().screen != screen
			|| !controller.isAutoEligibleScreen(screen)) return Optional.empty();
		ExperimentSolverModule.Session session = controller.session();
		if (session == null || session.type != ExperimentType.ULTRASEQUENCER
			|| session.attachedMenu != screen.getMenu()
			|| screen.getMenu().containerId != session.menuId) return Optional.empty();
		return ultrasequencerAutomation.presentationProgress(screen, screen.getMenu(),
			controller.sessionGeneration(), solverView);
	}

	/** Runs after the shared controller has observed the current client tick. */
	public void tick() {
		GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.ENCHANTING, name(), debug.value());
		if (!isEnabled()) {
			automation.reset();
			ultrasequencerAutomation.reset();
			reportInactive();
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		AbstractContainerScreen<?> screen = minecraft.screen instanceof AbstractContainerScreen<?> container
			? container : null;
		ExperimentType screenType = screen == null ? null
			: ExperimentType.fromTitle(screen.getTitle().getString()).orElse(null);
		if (screenType == ExperimentType.ULTRASEQUENCER && supports(ExperimentType.ULTRASEQUENCER)) {
			automation.reset();
			tickUltrasequencer(screen);
			return;
		}
		ultrasequencerAutomation.reset();
		AbstractContainerMenu menu = screen == null ? null : screen.getMenu();
		AutoExperimentAutomation.Snapshot snapshot = snapshot(screen);
		long firstDelayNanos = firstClickDelay.intValue() * 1_000_000L;
		AutoExperimentAutomation.Decision decision = automation.tick(snapshot, System.nanoTime(),
			firstDelayNanos);
		applyDecision(screen, menu, decision);
		if (decision.action() != AutoExperimentAutomation.Action.CLICK || screen == null) {
			reportDecision(screen, snapshot, decision, "none", "");
			return;
		}

		boolean dispatched = false;
		String failure = "";
		try {
			dispatched = ExperimentController.INSTANCE.dispatchAutoSequenceClick(screen,
				decision.sequenceIndex(), decision.slotId(),
				(slot, slotId, button, input) -> ((AbstractContainerScreenInvoker) (Object) screen)
					.geileraddons$invokeSlotClicked(slot, slotId, button, input));
		} catch (RuntimeException exception) {
			// The click gate has already prevented confirmation if vanilla dispatch failed. Do not retry.
			dispatched = false;
			failure = exception.getClass().getSimpleName()
				+ (exception.getMessage() == null ? "" : ": " + exception.getMessage());
		}
		AutoExperimentDelayRange delayRange = new AutoExperimentDelayRange(minimumClickDelay.intValue(),
			maximumClickDelay.intValue());
		long betweenClickDelayNanos = delayRange.sampleNanos(
			bound -> ThreadLocalRandom.current().nextLong(bound));
		AutoExperimentAutomation.Decision result = automation.clickResult(dispatched,
			snapshot((Minecraft.getInstance().screen instanceof AbstractContainerScreen<?> current)
				? current : null), System.nanoTime(), betweenClickDelayNanos);
		applyDecision(screen, menu, result);
		reportDecision(screen, snapshot, result, dispatched ? "dispatched" : "rejected", failure);
	}

	/** True while the automation diagnostics are switched on; the controller reuses this gate. */
	boolean debugAutomation() {
		return debug.value();
	}

	/**
	 * Records why automation is not switching on. This is the one line that distinguishes "the module
	 * is off" from "the screen was not recognised" from "Auto is not enabled for this experiment", so
	 * it is written even before the early return that would otherwise hide the reason.
	 */
	private void reportInactive() {
		if (!debug.value()) {
			reportedDecision = "";
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		AbstractContainerScreen<?> screen = minecraft.screen instanceof AbstractContainerScreen<?> container
			? container : null;
		String title = screen == null ? "" : screen.getTitle().getString();
		ExperimentType type = ExperimentType.fromTitle(title).orElse(null);
		if (type == null) {
			reportedDecision = "";
			return;
		}
		String fingerprint = "inactive|" + title + "|" + type;
		if (fingerprint.equals(reportedDecision)) return;
		reportedDecision = fingerprint;
		writeDiagnostic("auto inactive: module disabled, screen=" + title
			+ ", type=" + type + ", supports=" + supports(type));
	}

	/** Writes one diagnostic line whenever the automation decision actually changes. */
	private void reportDecision(AbstractContainerScreen<?> screen,
		AutoExperimentAutomation.Snapshot snapshot, AutoExperimentAutomation.Decision decision,
		String dispatch, String failure) {
		if (!debug.value() || screen == null || snapshot.type() == null) {
			reportedDecision = "";
			return;
		}
		ExperimentController controller = ExperimentController.INSTANCE;
		SolverView view = controller.view();
		String fingerprint = String.join("|",
			snapshot.type().name(), String.valueOf(snapshot.tier()),
			String.valueOf(snapshot.contextValid()), String.valueOf(snapshot.gameEnabled()),
			String.valueOf(controller.isAutoEligibleScreen(screen)),
			String.valueOf(controller.hasActiveOwner(snapshot.type())),
			String.valueOf(controller.sessionGeneration()),
			String.valueOf(view.phase()),
			snapshot.currentIndex() + "/" + snapshot.sequenceLength(),
			String.valueOf(snapshot.expectedSlotId()),
			decision.action().name(), dispatch, failure);
		if (fingerprint.equals(reportedDecision)) return;
		reportedDecision = fingerprint;
		writeDiagnostic("auto: screen=" + screen.getTitle().getString()
			+ ", type=" + snapshot.type() + ", tier=" + snapshot.tier()
			+ ", enabled=" + isEnabled() + ", supports=" + supports(snapshot.type())
			+ ", eligible=" + controller.isAutoEligibleScreen(screen)
			+ ", owner=" + controller.hasActiveOwner(snapshot.type())
			+ ", generation=" + controller.sessionGeneration()
			+ ", engine=" + view.phase() + " " + snapshot.currentIndex()
			+ "/" + snapshot.sequenceLength() + " expected=" + snapshot.expectedSlotId()
			+ ", decision=" + decision.action() + ", dispatch=" + dispatch
			+ (failure.isEmpty() ? "" : ", failure=" + failure)
			+ (decision.explanation().isBlank() ? "" : ", reason=" + decision.explanation()));
	}

	private void writeDiagnostic(String line) {
		GeilerAddonsLog.write(Category.ENCHANTING, name(), 0L, line);
	}

	private void tickUltrasequencer(AbstractContainerScreen<?> screen) {
		ExperimentController controller = ExperimentController.INSTANCE;
		SolverView solverView = controller.view();
		long now = System.nanoTime();
		ExperimentSolverModule.Session session = controller.session();
		boolean sessionValid = controller.isAutoEligibleScreen(screen) && session != null
			&& session.attachedMenu == screen.getMenu() && session.type == ExperimentType.ULTRASEQUENCER
			&& screen.getMenu().containerId == session.menuId;
		UltrasequencerSequenceExecutor.Decision decision = ultrasequencerAutomation.tick(screen,
			screen.getMenu(), controller.sessionGeneration(), sessionValid, solverView, now,
			firstClickDelay.intValue() * 1_000_000L);
		String pauseNotice = ultrasequencerAutomation.consumePauseNotice();
		if (!pauseNotice.isBlank()) showMessage(pauseNotice);

		if (decision.action() == UltrasequencerSequenceExecutor.Action.CLOSE_MENU) {
			applyDecision(screen, screen.getMenu(), new AutoExperimentAutomation.Decision(
				AutoExperimentAutomation.Action.CLOSE_MENU, -1, -1, ""));
			reportUltrasequencer(screen, solverView, ultrasequencerAutomation.reason(), "milestone reached; close requested");
			return;
		}
		if (decision.action() != UltrasequencerSequenceExecutor.Action.CLICK) {
			if (decision.action() == UltrasequencerSequenceExecutor.Action.PAUSED) {
				applyDecision(screen, screen.getMenu(), new AutoExperimentAutomation.Decision(
					AutoExperimentAutomation.Action.PAUSED, -1, -1, ""));
			}
			reportUltrasequencer(screen, solverView, decision.explanation(), "not attempted");
			return;
		}

		boolean dispatched = false;
		String failure = "";
		try {
			dispatched = controller.dispatchAutoUltrasequencerClick(screen, decision.slotId(),
				(slot, slotId, button, input) -> ((AbstractContainerScreenInvoker) (Object) screen)
					.geileraddons$invokeSlotClicked(slot, slotId, button, input));
		} catch (RuntimeException exception) {
			// The executor already consumed this position, so an uncertain dispatch can never be resent.
			failure = exception.getClass().getSimpleName()
				+ (exception.getMessage() == null ? "" : ": " + exception.getMessage());
		}
		long nextDelayNanos = sampleBetweenClickDelayNanos();
		UltrasequencerSequenceExecutor.Decision completion = ultrasequencerAutomation.dispatchFinished(
			dispatched, System.nanoTime(), nextDelayNanos);
		pauseNotice = ultrasequencerAutomation.consumePauseNotice();
		if (!pauseNotice.isBlank()) showMessage(pauseNotice);
		if (completion.action() == UltrasequencerSequenceExecutor.Action.CLOSE_MENU) {
			applyDecision(screen, screen.getMenu(), new AutoExperimentAutomation.Decision(
				AutoExperimentAutomation.Action.CLOSE_MENU, -1, -1, ""));
			reportUltrasequencer(screen, controller.view(), ultrasequencerAutomation.reason(),
				"milestone reached; close requested");
			return;
		}
		String dispatch = failure.isEmpty() ? dispatched ? "vanilla dispatched" : "session guard rejected"
			: "dispatch threw; paused without resend (" + failure + ")";
		reportUltrasequencer(screen, controller.view(), ultrasequencerAutomation.reason(), dispatch);
	}

	private void reportUltrasequencer(AbstractContainerScreen<?> screen, SolverView view,
		String decision, String dispatch) {
		if (!debug.value()) {
			reportedDecision = "";
			return;
		}
		ExperimentController controller = ExperimentController.INSTANCE;
		SolverView solverView = view == null ? SolverView.idle() : view;
		String current = solverView.current()
			.map(step -> step.index() + ":" + step.value() + "@" + step.slotIds()).orElse("none");
		boolean solverOwner = ExperimentSolverModule.INSTANCE.isEnabled()
			&& ExperimentSolverModule.INSTANCE.supports(ExperimentType.ULTRASEQUENCER);
		String line = "ultrasequencer: screen=" + (screen == null ? "none" : screen.getTitle().getString())
			+ ", decision=" + decision
			+ ", solutionCursor=" + ultrasequencerAutomation.cursor()
			+ "/" + ultrasequencerAutomation.sequenceLength()
			+ ", solver=" + solverView.phase()
			+ ", current=" + current
			+ ", visual=" + solverView.visualIndex() + "/" + solverView.sequence().size()
			+ ", paused=" + ultrasequencerAutomation.paused()
			+ ", pauseReason=" + ultrasequencerAutomation.pauseReason()
			+ ", owners=auto:" + (isEnabled() && supports(ExperimentType.ULTRASEQUENCER))
			+ ",solver:" + solverOwner + ",active:" + controller.hasActiveOwner(ExperimentType.ULTRASEQUENCER)
			+ ", pendingDelay=" + ultrasequencerAutomation.pendingDelay()
			+ ", dispatch=" + dispatch;
		String fingerprint = "ultra|" + line;
		if (fingerprint.equals(reportedDecision)) return;
		reportedDecision = fingerprint;
		writeDiagnostic(line);
	}

	/** Cancels a queued click and pauses until the user toggles the module off and on. */
	public void pauseAfterManualInput(AbstractContainerScreen<?> screen) {
		if (!isEnabled() || !ExperimentController.INSTANCE.isAutoEligibleScreen(screen)) return;
		ExperimentType type = ExperimentType.fromTitle(screen.getTitle().getString()).orElse(null);
		String explanation;
		if (type == ExperimentType.ULTRASEQUENCER) {
			explanation = ultrasequencerAutomation.pauseForManualInput(screen, screen.getMenu(),
				ExperimentController.INSTANCE.sessionGeneration());
		} else {
			explanation = automation.pauseForManualInput();
		}
		if (!explanation.isBlank()) showMessage(explanation);
	}

	/** Intercepts Chronomatron clicks when the Solver replacement surface is disabled. */
	public boolean handleVanillaSlotClick(AbstractContainerScreen<?> screen, Slot clickedSlot,
		int slotId, int button, ContainerInput input, ExperimentSolverModule.SlotClickDispatcher dispatcher) {
		if (ExperimentController.INSTANCE.isDispatchingCustomClick()
			|| !ExperimentController.INSTANCE.isAutoEligibleScreen(screen)
			|| !isBoardSlot(screen.getMenu(), screen.getTitle().getString(), clickedSlot, slotId)) return false;

		ExperimentType type = ExperimentType.fromTitle(screen.getTitle().getString()).orElse(null);
		if (type == ExperimentType.ULTRASEQUENCER) {
			pauseAfterManualInput(screen);
			return false;
		}
		boolean correctStep = button == 0 && input == ContainerInput.PICKUP
			&& ExperimentController.INSTANCE.isExpectedStepClick(slotId);
		// Only a click that is not the step the solver wants counts as the player taking over. A
		// correct one is the player doing the same thing automation would have done, and pausing
		// there cancelled the queued click.
		if (!correctStep) pauseAfterManualInput(screen);
		if (button == 0 && input == ContainerInput.PICKUP) {
			ExperimentClickGate.SequenceDispatchResult result =
				ExperimentController.INSTANCE.dispatchManualSequenceClick(screen, slotId, dispatcher);
			if (correctStep) {
				if (result.progressAdvanced()) {
					AutoExperimentAutomation.Decision synchronizedStep = automation.synchronizeAfterManualProgress(
						snapshot(screen), System.nanoTime(), sampleBetweenClickDelayNanos());
					applyDecision(screen, screen.getMenu(), synchronizedStep);
				} else {
					pauseAfterManualInput(screen);
				}
			}
		}
		return true;
	}

	@Override
	protected void onEnable() {
		automation.reset();
		ultrasequencerAutomation.reset();
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.screen instanceof AbstractContainerScreen<?> screen) {
			ExperimentController.INSTANCE.onScreenOpened(screen);
		}
	}

	@Override
	protected void onDisable() {
		automation.reset();
		ultrasequencerAutomation.reset();
		ExperimentController.INSTANCE.onOwnerDisabled();
	}

	private AutoExperimentAutomation.Snapshot snapshot(AbstractContainerScreen<?> screen) {
		ExperimentController controller = ExperimentController.INSTANCE;
		if (screen == null) {
			return new AutoExperimentAutomation.Snapshot(isEnabled(), false, false,
				screen, screen == null ? null : screen.getMenu(), null, ExperimentTier.UNKNOWN,
				ExperimentPhase.IDLE, "", false, 0, 0, -1, -1, false);
		}

		String title = screen.getTitle().getString();
		ExperimentType type = ExperimentType.fromTitle(title).orElse(null);
		if (type == null) {
			ExperimentType hintedType = hintedSequenceType(title);
			if (hintedType != null && supports(hintedType)) {
				// Recognize only enough of a malformed Chronomatron/Ultrasequencer title to stop safely.
				// The normal type parser still owns all actual observation and click eligibility.
				return new AutoExperimentAutomation.Snapshot(isEnabled(), true, true, screen,
					screen.getMenu(), hintedType, ExperimentTier.UNKNOWN, ExperimentPhase.IDLE,
					"", false, 0, 0, -1, -1, false);
			}
			return new AutoExperimentAutomation.Snapshot(isEnabled(), false, false,
				screen, screen.getMenu(), null, ExperimentTier.UNKNOWN,
				ExperimentPhase.IDLE, "", false, 0, 0, -1, -1, false);
		}
		if (!controller.isAutoEligibleScreen(screen)) {
			return new AutoExperimentAutomation.Snapshot(isEnabled(), false, false,
				screen, screen.getMenu(), type, ExperimentTier.UNKNOWN,
				ExperimentPhase.IDLE, "", false, 0, 0, -1, -1, false);
		}
		ExperimentTier tier = ExperimentTier.fromTitle(title).orElse(ExperimentTier.UNKNOWN);
		ExperimentSolverModule.Session session = controller.session();
		SolverView view = controller.view();
		boolean sameSession = session != null && session.attachedMenu == screen.getMenu()
			&& session.type == type && screen.getMenu().containerId == session.menuId;
		boolean sameView = view.type() == type;
		int expectedSlot = sameView ? view.current().flatMap(step -> step.slotIds().stream().findFirst())
			.orElse(-1) : -1;
		return new AutoExperimentAutomation.Snapshot(isEnabled(), supports(type), true,
			screen, screen.getMenu(), type, tier,
			sameView ? view.phase() : ExperimentPhase.IDLE,
			sameSession ? session.instruction : "",
			sameView && !view.sequence().isEmpty(), sameView ? view.visualIndex() : 0,
			sameView ? view.sequence().size() : 0, expectedSlot,
			sameView ? view.completedRounds() : -1,
			sameView && view.milestoneReached());
	}

	static ExperimentType hintedSequenceType(String title) {
		String normalized = ExperimentPhase.normalizeStatus(title);
		if (normalized.startsWith("chronomatron (")) return ExperimentType.CHRONOMATRON;
		if (normalized.startsWith("ultrasequencer (")) return ExperimentType.ULTRASEQUENCER;
		return null;
	}

	private static boolean isBoardSlot(AbstractContainerMenu menu, String title, Slot slot, int slotId) {
		if (slot == null || slot.index != slotId || slot.container instanceof Inventory) return false;
		ExperimentType type = ExperimentType.fromTitle(title).orElse(null);
		ExperimentTier tier = ExperimentTier.fromTitle(title).orElse(ExperimentTier.UNKNOWN);
		return type != null && (type == ExperimentType.CHRONOMATRON || type == ExperimentType.ULTRASEQUENCER)
			&& ExperimentBoardGeometry.forExperiment(type, tier).containsSlot(slotId);
	}

	private long sampleBetweenClickDelayNanos() {
		return new AutoExperimentDelayRange(minimumClickDelay.intValue(), maximumClickDelay.intValue())
			.sampleNanos(bound -> ThreadLocalRandom.current().nextLong(bound));
	}

	private void showPause(AutoExperimentAutomation.Decision decision) {
		if (decision != null && decision.action() == AutoExperimentAutomation.Action.PAUSED
			&& !decision.explanation().isBlank()) showMessage(decision.explanation());
	}

	private void applyDecision(AbstractContainerScreen<?> screen, AbstractContainerMenu expectedMenu,
		AutoExperimentAutomation.Decision decision) {
		showPause(decision);
		if (decision == null || decision.action() != AutoExperimentAutomation.Action.CLOSE_MENU) return;
		Minecraft minecraft = Minecraft.getInstance();
		if (screen == null || expectedMenu == null || !minecraft.isSameThread()
			|| minecraft.screen != screen || screen.getMenu() != expectedMenu
			|| !ExperimentController.INSTANCE.isAutoEligibleScreen(screen)) return;
		screen.onClose();
	}

	private static void showMessage(String message) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.gui != null) {
			minecraft.gui.getChat().addClientSystemMessage(Component.literal("[GeilerAddons] " + message));
		}
	}

}
