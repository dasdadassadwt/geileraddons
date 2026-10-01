package geiler.addons.client.module.impl;

import geiler.addons.client.config.GeilerAddonsLog;
import geiler.addons.client.enchanting.ChronomatronEvent;
import geiler.addons.client.enchanting.ExperimentBoardGeometry;
import geiler.addons.client.enchanting.ExperimentClickGate;
import geiler.addons.client.enchanting.ExperimentCell;
import geiler.addons.client.enchanting.ExperimentPhase;
import geiler.addons.client.enchanting.ExperimentSnapshot;
import geiler.addons.client.enchanting.ExperimentSolverEngine;
import geiler.addons.client.enchanting.ExperimentTier;
import geiler.addons.client.enchanting.ExperimentType;
import geiler.addons.client.enchanting.SolverView;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.impl.ExperimentSolverModule.Session;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.List;

/**
 * The single owner of experiment observation, menu listeners, and solver state.
 *
 * <p>The Solver module is a renderer and manual-input adapter. Auto Experiments reads this
 * controller's immutable view and uses its own timed Ultrasequencer execution cursor; automated
 * clicks do not advance or confirm the solver cursor.</p>
 */
public final class ExperimentController {
	public static final ExperimentController INSTANCE = new ExperimentController();

	private final ExperimentSolverEngine engine = new ExperimentSolverEngine();
	private final ExperimentClickGate clickGate = new ExperimentClickGate();
	private Session session;
	private SolverView view = SolverView.idle();
	private long sessionGeneration;
	/** Last dispatch rejection already reported, so a held mouse button cannot flood the log. */
	private String reportedRejection = "";

	private ExperimentController() {
	}

	public ExperimentSolverEngine engine() {
		engine.configure(ExperimentSolverModule.INSTANCE.configuration());
		return engine;
	}

	public SolverView view() {
		return view;
	}

	Session session() {
		return session;
	}

	public long sessionGeneration() {
		return sessionGeneration;
	}

	public boolean isDispatchingClick() {
		return clickGate.dispatching();
	}

	/** Called once on the client tick; exactly one observer feeds the shared engine. */
	public void tick() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.screen instanceof AbstractContainerScreen<?> screen && isEligibleScreen(screen)) {
			observe(screen);
			if (session != null && session.type == ExperimentType.ULTRASEQUENCER) {
				view = engine.observe(session.snapshot(screen, false));
			}
		} else if (session != null || view.type() != null) {
			reset();
		}
	}

	public void onScreenOpened(AbstractContainerScreen<?> screen) {
		if (isEligibleScreen(screen)) observe(screen);
		else reset();
	}

	public void observeScreen(AbstractContainerScreen<?> screen) {
		if (isEligibleScreen(screen)) observe(screen);
	}

	public void onOwnerDisabled() {
		if (session != null && !hasActiveOwner(session.type)) reset();
	}

	public void onScreenClosed() {
		reset();
	}

	/** Called after client-side server-menu mutations, before the menu rebroadcasts its listeners. */
	public void onContainerUpdated(AbstractContainerMenu menu) {
		if (session == null || session.attachedMenu != menu || !hasActiveOwner(session.type)) return;
		if (session.type == ExperimentType.ULTRASEQUENCER) {
			List<String> colors = new ArrayList<>();
			for (Slot slot : menu.slots) {
				if (slot.index < 9 || slot.index > 44) continue;
				String color = Session.ultrasequencerPaneColor(slot.getItem());
				if (color != null) colors.add(color);
			}
			engine.markUltrasequencerDirty(colors);
			view = engine.view();
		}
	}

	public boolean observesMenu(AbstractContainerMenu menu) {
		return session != null && session.attachedMenu == menu && hasActiveOwner(session.type);
	}

	public boolean isAutoEligibleScreen(AbstractContainerScreen<?> screen) {
		if (screen == null || !AutoExperimentsModule.INSTANCE.isEnabled()) return false;
		ExperimentType type = ExperimentType.fromTitle(screen.getTitle().getString()).orElse(null);
		return type != null && AutoExperimentsModule.INSTANCE.supports(type);
	}

	public boolean isDispatchingCustomClick() {
		return isDispatchingClick();
	}

	/**
	 * Whether a click on this slot is the step the solver is currently asking for.
	 *
	 * <p>Used to tell a player's legal move apart from a player taking over. Both were previously
	 * treated the same way, so a manual click on the correct button - which on the Ultrasequencer is
	 * the round's opening move - paused automation and cancelled the click that was already queued.
	 */
	public boolean isExpectedStepClick(int slotId) {
		return view.current().map(step -> step.containsSlot(slotId)).orElse(false);
	}

	/**
	 * Validates the live screen, active menu, puzzle, and exact solver step, then invokes vanilla's
	 * normal PICKUP slot-click path and confirms that step once after dispatch.
	 */
	public boolean dispatchSequenceClick(AbstractContainerScreen<?> screen, int slotId,
		ExperimentSolverModule.SlotClickDispatcher dispatcher) {
		return dispatchSequenceClickResult(screen, -1, slotId, dispatcher).dispatched();
	}

	ExperimentClickGate.SequenceDispatchResult dispatchManualSequenceClick(AbstractContainerScreen<?> screen,
		int slotId, ExperimentSolverModule.SlotClickDispatcher dispatcher) {
		return dispatchSequenceClickResult(screen, -1, slotId, dispatcher);
	}

	private ExperimentClickGate.SequenceDispatchResult dispatchSequenceClickResult(
		AbstractContainerScreen<?> screen, int expectedSequenceIndex,
		int slotId, ExperimentSolverModule.SlotClickDispatcher dispatcher) {
		if (screen == null || dispatcher == null || slotId < 0) {
			logDispatchRejection("invalid request");
			return ExperimentClickGate.SequenceDispatchResult.REJECTED;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (!minecraft.isSameThread()) {
			logDispatchRejection("not on the client thread");
			return ExperimentClickGate.SequenceDispatchResult.REJECTED;
		}
		if (minecraft.screen != screen) {
			logDispatchRejection("screen replaced");
			return ExperimentClickGate.SequenceDispatchResult.REJECTED;
		}
		if (!isEligibleScreen(screen)) {
			logDispatchRejection("no active experiment owner");
			return ExperimentClickGate.SequenceDispatchResult.REJECTED;
		}
		observe(screen);
		if (!isCurrentSession(screen)) {
			logDispatchRejection("session replaced");
			return ExperimentClickGate.SequenceDispatchResult.REJECTED;
		}
		if (!hasActiveOwner(session.type)) {
			logDispatchRejection("module disabled during dispatch");
			return ExperimentClickGate.SequenceDispatchResult.REJECTED;
		}
		if (session.type != ExperimentType.CHRONOMATRON
			&& session.type != ExperimentType.ULTRASEQUENCER) {
			logDispatchRejection("experiment is not a sequence game");
			return ExperimentClickGate.SequenceDispatchResult.REJECTED;
		}
		Slot slot = boardSlot(screen.getMenu(), session.type, session.tier, slotId);
		if (slot == null) {
			logDispatchRejection("slot " + slotId + " is not a live board slot");
			return ExperimentClickGate.SequenceDispatchResult.REJECTED;
		}
		engine.configure(ExperimentSolverModule.INSTANCE.configuration());
		int dispatchSequenceIndex = expectedSequenceIndex >= 0 ? expectedSequenceIndex
			: view.current().map(step -> step.index()).orElse(-1);
		ExperimentClickGate.SequenceDispatchResult result = clickGate.dispatchSequenceClickResult(
			engine, dispatchSequenceIndex, slotId,
			isCurrentSession(screen) && hasActiveOwner(session.type),
			id -> dispatcher.dispatch(slot, id, 0, ContainerInput.PICKUP));
		if (result.dispatched()) {
			view = engine.view();
			if (result == ExperimentClickGate.SequenceDispatchResult.UNCERTAIN) {
				logDispatchRejection("vanilla click ran, but solver progress was not confirmed safely; do not resend");
			}
		} else {
			logDispatchRejection("click gate rejected index " + dispatchSequenceIndex + " slot " + slotId
				+ " at " + view.phase() + " index " + view.visualIndex()
				+ "/" + view.sequence().size());
		}
		return result;
	}

	/** Records the first gate that refused an automated click; silent rejections are undiagnosable. */
	private void logDispatchRejection(String reason) {
		if (!AutoExperimentsModule.INSTANCE.debugAutomation()) {
			reportedRejection = "";
			return;
		}
		if (reason.equals(reportedRejection)) return;
		reportedRejection = reason;
		GeilerAddonsLog.write(Category.ENCHANTING, AutoExperimentsModule.INSTANCE.name(), 0L,
			"dispatch rejected: " + reason);
	}

	/** Chronomatron-only Auto entry point; Ultrasequencer uses its one-pass executor below. */
	public boolean dispatchAutoSequenceClick(AbstractContainerScreen<?> screen, int sequenceIndex, int slotId,
		ExperimentSolverModule.SlotClickDispatcher dispatcher) {
		if (!isAutoEligibleScreen(screen)
			|| ExperimentType.fromTitle(screen.getTitle().getString()).orElse(null)
				!= ExperimentType.CHRONOMATRON) return false;
		return dispatchSequenceClickResult(screen, sequenceIndex, slotId, dispatcher).dispatched();
	}

	/**
	 * Dispatches one solver-provided Ultrasequencer slot through vanilla after checking only the
	 * active client screen, experiment session, and board-slot identity. Auto owns the execution
	 * cursor; this path deliberately does not confirm a solver click or inspect server progress.
	 */
	boolean dispatchAutoUltrasequencerClick(AbstractContainerScreen<?> screen,
		int slotId, ExperimentSolverModule.SlotClickDispatcher dispatcher) {
		if (screen == null || dispatcher == null || slotId < 0
			|| !isAutoEligibleScreen(screen)
			|| ExperimentType.fromTitle(screen.getTitle().getString()).orElse(null) != ExperimentType.ULTRASEQUENCER) {
			logDispatchRejection("Ultrasequencer request is no longer eligible");
			return false;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (!minecraft.isSameThread() || minecraft.screen != screen) {
			logDispatchRejection("Ultrasequencer screen changed before dispatch");
			return false;
		}
		if (!isCurrentSession(screen) || session.type != ExperimentType.ULTRASEQUENCER
			|| !hasActiveOwner(ExperimentType.ULTRASEQUENCER)) {
			logDispatchRejection("Ultrasequencer session changed before dispatch");
			return false;
		}
		Slot slot = boardSlot(screen.getMenu(), session.type, session.tier, slotId);
		if (slot == null) {
			logDispatchRejection("Ultrasequencer slot " + slotId + " is not part of the active menu board");
			return false;
		}
		if (clickGate.dispatching()) {
			logDispatchRejection("another custom experiment click is already dispatching");
			return false;
		}
		clickGate.dispatchVanilla(() -> dispatcher.dispatch(slot, slotId, 0, ContainerInput.PICKUP));
		return true;
	}

	/** Superpairs is manual-only, but its intentional custom click still passes through vanilla. */
	public boolean dispatchSuperpairsClick(AbstractContainerScreen<?> screen, int slotId, int button,
		ExperimentSolverModule.SlotClickDispatcher dispatcher) {
		if (screen == null || dispatcher == null || slotId < 0) return false;
		Minecraft minecraft = Minecraft.getInstance();
		if (!minecraft.isSameThread() || minecraft.screen != screen || !isEligibleScreen(screen)) return false;
		observe(screen);
		if (!isCurrentSession(screen) || session.type != ExperimentType.SUPERPAIRS
			|| !ExperimentSolverModule.INSTANCE.isEnabled()
			|| !ExperimentSolverModule.INSTANCE.supports(ExperimentType.SUPERPAIRS)) return false;
		Slot slot = boardSlot(screen.getMenu(), session.type, session.tier, slotId);
		if (slot == null) return false;
		var decision = engine.onClick(slotId);
		if (!decision.expected() || !decision.visualStateChanged()) return false;
		clickGate.dispatchVanilla(() -> dispatcher.dispatch(slot, slotId, button, ContainerInput.PICKUP));
		view = engine.view();
		return true;
	}

	private boolean isEligibleScreen(AbstractContainerScreen<?> screen) {
		if (screen == null) return false;
		ExperimentType type = ExperimentType.fromTitle(screen.getTitle().getString()).orElse(null);
		return type != null && hasActiveOwner(type);
	}

	/** Whether any module currently owns this experiment; also reported by the Auto diagnostics. */
	boolean hasActiveOwner(ExperimentType type) {
		if (type == null) return false;
		ExperimentSolverModule solver = ExperimentSolverModule.INSTANCE;
		AutoExperimentsModule automatic = AutoExperimentsModule.INSTANCE;
		return hasActiveOwner(type, solver.isEnabled(), solver.supports(type), automatic.isEnabled(),
			type != ExperimentType.SUPERPAIRS && automatic.supports(type));
	}

	static boolean hasActiveOwner(ExperimentType type, boolean solverEnabled, boolean solverSupports,
		boolean autoEnabled, boolean autoSupports) {
		return type != null && (solverEnabled && solverSupports
			|| type != ExperimentType.SUPERPAIRS && autoEnabled && autoSupports);
	}

	private boolean isCurrentSession(AbstractContainerScreen<?> screen) {
		return screen != null && session != null && session.attachedMenu == screen.getMenu()
			&& screen.getMenu().containerId == session.menuId
			&& ExperimentType.fromTitle(screen.getTitle().getString()).orElse(null) == session.type;
	}

	private static Slot boardSlot(AbstractContainerMenu menu, ExperimentType type,
		ExperimentTier tier, int slotId) {
		if (!ExperimentBoardGeometry.forExperiment(type, tier).containsSlot(slotId)) return null;
		for (Slot slot : menu.slots) {
			if (slot.index == slotId && !(slot.container instanceof Inventory)) return slot;
		}
		return null;
	}

	private void observe(AbstractContainerScreen<?> screen) {
		String title = screen.getTitle().getString();
		ExperimentType type = ExperimentType.fromTitle(title).orElse(null);
		ExperimentTier tier = ExperimentTier.fromTitle(title).orElse(ExperimentTier.UNKNOWN);
		int menuId = screen.getMenu().containerId;
		if (session == null || session.type != type || session.tier != tier || session.menuId != menuId
			|| session.attachedMenu != screen.getMenu()) {
			if (session != null) session.close();
			session = new Session(type, tier, menuId);
			engine.reset();
			view = SolverView.idle();
			sessionGeneration++;
		}
		if (!session.observe(screen)) return;
		engine.configure(ExperimentSolverModule.INSTANCE.configuration());
		boolean superpairsChanged = session.type == ExperimentType.SUPERPAIRS
			&& session.takeSuperpairsObservationDirty();
		for (Session.SlotUpdate update : session.drainSlotUpdates()) {
			if (session.type == ExperimentType.ULTRASEQUENCER) continue;
			ChronomatronEvent event = session.type == ExperimentType.CHRONOMATRON
				? update.chronomatronEvent() : null;
			view = engine.observe(session.snapshot(screen, false, update), event);
		}
		if (session.type == ExperimentType.ULTRASEQUENCER && view.type() == null) {
			// Establish the model before mutation callbacks; capture the memory only on a tick.
			view = engine.observe(new ExperimentSnapshot(title, "", List.of()));
		} else if (session.type != ExperimentType.ULTRASEQUENCER) {
			if (session.type != ExperimentType.SUPERPAIRS || superpairsChanged || view.type() == null) {
				view = engine.observe(session.snapshot(screen, false));
			}
		}
		// Superpairs values are learned from revealed menu stacks. Rebuild only after callbacks drain.
		session.refreshRenderSlots(screen);
	}

	private void reset() {
		boolean hadSession = session != null || view.type() != null;
		if (session != null) session.close();
		session = null;
		view = engine.reset();
		if (hadSession) sessionGeneration++;
	}
}
