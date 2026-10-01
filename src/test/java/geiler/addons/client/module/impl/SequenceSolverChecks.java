package geiler.addons.client.module.impl;

import geiler.addons.client.enchanting.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerListener;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Regression traces for the upstream event contracts and the actual vanilla listener boundary. */
final class SequenceSolverChecks {
	static void run() {
		checkUltrasequencerSolutionAdapter();
		checkAutoUltrasequencerDispatchProgress();
		checkExecutionProgressPresentationSnapshot();
		checkRenderDoesNotLearn();
		checkUltraRounds();
		checkUltraRoundTwoSolve();
		checkUltraTimerAfterPaneEdge();
		checkUltraOpeningClick();
		checkVanillaListenerBridge();
	}

	private static void checkUltrasequencerSolutionAdapter() {
		List<SequenceStep> steps = List.of(new SequenceStep(0, "1", List.of(30)),
			new SequenceStep(1, "2", List.of(31)), new SequenceStep(2, "3", List.of(32)));
		SolverView firstResult = solverView(ExperimentType.ULTRASEQUENCER, ExperimentPhase.SOLVE,
			steps, 0, steps.getFirst());
		var solution = UltrasequencerSequenceExecutor.Solution.from(firstResult);
		require(solution.isPresent(), "a complete healthy solver view is accepted as the solution source");
		require(solution.orElseThrow().sequence().equals(steps),
			"the adapter preserves the solver's exact ordered sequence and slot IDs");

		SolverView incomplete = new SolverView(ExperimentType.ULTRASEQUENCER, ExperimentTier.HIGH,
			ExperimentPhase.SOLVE, 4, 0, steps, 0, 0, 0, Optional.of(steps.getFirst()),
			Optional.empty(), Optional.empty(), SuperpairsBoard.View.empty(), Optional.empty(), false);
		require(UltrasequencerSequenceExecutor.Solution.from(incomplete).isEmpty(),
			"a sequence shorter than the solver's reported length is unavailable to Auto");
		require(UltrasequencerSequenceExecutor.Solution.from(
			solverView(ExperimentType.ULTRASEQUENCER, ExperimentPhase.WAITING, steps, 0, steps.getFirst())).isEmpty(),
			"a remembered but non-solve solver state is not dispatched");
	}

	/** Auto replays one solver snapshot on its own cursor and never waits for per-click solver progress. */
	private static void checkAutoUltrasequencerDispatchProgress() {
		List<SequenceStep> steps = List.of(new SequenceStep(0, "1", List.of(30)),
			new SequenceStep(1, "2", List.of(31)), new SequenceStep(2, "3", List.of(32)));
		SolverView unchangedSolver = solverView(ExperimentType.ULTRASEQUENCER, ExperimentPhase.SOLVE,
			steps, 0, steps.getFirst());
		UltrasequencerSequenceExecutor executor = new UltrasequencerSequenceExecutor();
		Object screen = new Object();
		Object menu = new Object();
		long millis = 1_000_000L;
		long start = 10_000 * millis;
		long firstDelay = 500 * millis;
		long nextDelay = 300 * millis;
		List<Integer> dispatchedSlots = new ArrayList<>();

		require(executor.tick(screen, menu, 1, true, unchangedSolver, start, firstDelay).action()
			== UltrasequencerSequenceExecutor.Action.WAIT,
			"the first click waits for Auto Experiments' configured first delay");
		require(executor.tick(screen, menu, 1, true, unchangedSolver, start + firstDelay - 1,
			firstDelay).action() == UltrasequencerSequenceExecutor.Action.WAIT,
			"the first slot cannot fire early");
		var first = executor.tick(screen, menu, 1, true, unchangedSolver, start + firstDelay, firstDelay);
		require(first.action() == UltrasequencerSequenceExecutor.Action.CLICK && first.slotId() == 30,
			"the first solver slot is dispatched in order");
		dispatchedSlots.add(first.slotId());
		executor.dispatchFinished(true, start + firstDelay, nextDelay);
		require(unchangedSolver.visualIndex() == 0,
			"Auto dispatch does not mutate or wait for the solver cursor");
		require(executor.tick(screen, menu, 1, true, unchangedSolver, start + firstDelay + nextDelay - 1,
			nextDelay).action() == UltrasequencerSequenceExecutor.Action.WAIT,
			"the configured inter-click delay is applied before step two");
		var second = executor.tick(screen, menu, 1, true, unchangedSolver,
			start + firstDelay + nextDelay, nextDelay);
		require(second.action() == UltrasequencerSequenceExecutor.Action.CLICK && second.slotId() == 31,
			"the second solver slot runs even though no per-click solver acknowledgement arrived");
		dispatchedSlots.add(second.slotId());
		executor.dispatchFinished(true, start + firstDelay + nextDelay, nextDelay);
		var third = executor.tick(screen, menu, 1, true, unchangedSolver,
			start + firstDelay + nextDelay * 2, nextDelay);
		require(third.action() == UltrasequencerSequenceExecutor.Action.CLICK && third.slotId() == 32,
			"the final solver slot runs in the captured order");
		dispatchedSlots.add(third.slotId());
		executor.dispatchFinished(true, start + firstDelay + nextDelay * 2, nextDelay);
		require(dispatchedSlots.equals(List.of(30, 31, 32)),
			"every captured slot reaches vanilla exactly once and in sequence order");
		require(executor.tick(screen, menu, 1, true, unchangedSolver,
			start + firstDelay + nextDelay * 20, nextDelay).action() == UltrasequencerSequenceExecutor.Action.WAIT
			&& dispatchedSlots.size() == 3,
			"an unchanged solver snapshot cannot resend an already consumed slot");
		UltrasequencerSequenceExecutor rejectedDispatch = new UltrasequencerSequenceExecutor();
		var rejected = rejectedDispatch.tick(screen, menu, 9, true, unchangedSolver, start, 0L);
		rejectedDispatch.dispatchFinished(false, start, nextDelay);
		require(rejected.action() == UltrasequencerSequenceExecutor.Action.CLICK
			&& rejectedDispatch.tick(screen, menu, 9, true, unchangedSolver,
				start + nextDelay, nextDelay).action() == UltrasequencerSequenceExecutor.Action.PAUSED,
			"a rejected or uncertain vanilla dispatch pauses and never resends its claimed slot");

		UltrasequencerSequenceExecutor changedSolution = new UltrasequencerSequenceExecutor();
		var claimed = changedSolution.tick(screen, menu, 2, true, unchangedSolver, start, 0L);
		require(claimed.action() == UltrasequencerSequenceExecutor.Action.CLICK,
			"a healthy complete solver sequence can start without an extra board parse");
		changedSolution.dispatchFinished(true, start, nextDelay);
		List<SequenceStep> changedSteps = List.of(steps.getFirst(),
			new SequenceStep(1, "2", List.of(99)), steps.get(2));
		SolverView changedView = solverView(ExperimentType.ULTRASEQUENCER, ExperimentPhase.SOLVE,
			changedSteps, 0, changedSteps.getFirst());
		require(changedSolution.tick(screen, menu, 2, true, changedView,
			start + nextDelay, nextDelay).action() == UltrasequencerSequenceExecutor.Action.PAUSED,
			"a changed ordered solution pauses before any remaining slot is clicked");
		UltrasequencerSequenceExecutor unavailableSolution = new UltrasequencerSequenceExecutor();
		var unavailableFirst = unavailableSolution.tick(screen, menu, 8, true, unchangedSolver, start, 0L);
		unavailableSolution.dispatchFinished(true, start, nextDelay);
		SolverView waitingSolver = solverView(ExperimentType.ULTRASEQUENCER, ExperimentPhase.WAITING,
			steps, 0, steps.getFirst());
		require(unavailableSolution.tick(screen, menu, 8, true, waitingSolver,
			start + nextDelay, nextDelay).action() == UltrasequencerSequenceExecutor.Action.PAUSED
			&& unavailableFirst.slotId() == 30,
			"an unavailable solver state before completion pauses without sending a stale next slot");

		UltrasequencerSequenceExecutor contextChange = new UltrasequencerSequenceExecutor();
		Object newMenu = new Object();
		contextChange.tick(screen, menu, 3, true, unchangedSolver, start, 100 * millis);
		require(contextChange.tick(screen, newMenu, 4, true, unchangedSolver,
			start + 50 * millis, 100 * millis).action() == UltrasequencerSequenceExecutor.Action.WAIT,
			"a new menu and session discard the old queued click and arm a new delay");
		require(contextChange.tick(screen, newMenu, 4, true, unchangedSolver,
			start + 149 * millis, 100 * millis).action() == UltrasequencerSequenceExecutor.Action.WAIT
			&& contextChange.tick(screen, newMenu, 4, true, unchangedSolver,
				start + 150 * millis, 100 * millis).action() == UltrasequencerSequenceExecutor.Action.CLICK,
			"the replacement menu receives only its own delayed first slot");

		UltrasequencerSequenceExecutor unsafeSession = new UltrasequencerSequenceExecutor();
		unsafeSession.tick(screen, menu, 5, true, unchangedSolver, start, 100 * millis);
		require(unsafeSession.tick(screen, menu, 5, false, unchangedSolver,
			start + 50 * millis, 100 * millis).action() == UltrasequencerSequenceExecutor.Action.PAUSED,
			"a lost active session pauses before dispatch");

		UltrasequencerSequenceExecutor noClickMilestone = new UltrasequencerSequenceExecutor();
		SolverView reachedMilestone = new SolverView(ExperimentType.ULTRASEQUENCER, ExperimentTier.HIGH,
			ExperimentPhase.SOLVE, steps.size(), 0, steps, 0, 0, 0, Optional.of(steps.getFirst()),
			Optional.empty(), Optional.empty(), SuperpairsBoard.View.empty(), Optional.empty(), true);
		require(noClickMilestone.tick(screen, menu, 6, true, reachedMilestone, start, 0L).action()
			== UltrasequencerSequenceExecutor.Action.STOPPED,
			"a milestone reached before any Auto click stops without closing the menu");
		UltrasequencerSequenceExecutor closeMilestone = new UltrasequencerSequenceExecutor();
		var beforeMilestone = closeMilestone.tick(screen, menu, 7, true, unchangedSolver, start, 0L);
		closeMilestone.dispatchFinished(true, start, nextDelay);
		require(beforeMilestone.action() == UltrasequencerSequenceExecutor.Action.CLICK,
			"the milestone fixture records an Auto-dispatched click");
		require(closeMilestone.tick(screen, menu, 7, true, reachedMilestone,
			start + 1, 0L).action() == UltrasequencerSequenceExecutor.Action.CLOSE_MENU,
			"a later milestone closes the menu after Auto has dispatched a click");
		require(closeMilestone.tick(screen, menu, 7, true, reachedMilestone,
			start + 2, 0L).action() == UltrasequencerSequenceExecutor.Action.STOPPED,
			"milestone close is requested once");

		ExperimentMilestone sequenceLengthMilestone = new ExperimentMilestone(
			ExperimentType.ULTRASEQUENCER, ExperimentTier.HIGH, 0, steps.size(), steps.size() - 1, 0);
		SolverView targetLengthView = new SolverView(ExperimentType.ULTRASEQUENCER, ExperimentTier.HIGH,
			ExperimentPhase.SOLVE, steps.size(), 0, steps, 0, 0, 0, Optional.of(steps.getFirst()),
			Optional.empty(), Optional.empty(), SuperpairsBoard.View.empty(),
			Optional.of(sequenceLengthMilestone), false);
		UltrasequencerSequenceExecutor targetLength = new UltrasequencerSequenceExecutor();
		for (int index = 0; index < steps.size(); index++) {
			var next = targetLength.tick(screen, menu, 10, true, targetLengthView, start + index, 0L);
			require(next.action() == UltrasequencerSequenceExecutor.Action.CLICK
				&& next.slotId() == steps.get(index).slotIds().getFirst(),
				"the milestone round still dispatches solver slot " + (index + 1) + " in order");
			UltrasequencerSequenceExecutor.Decision finished = targetLength.dispatchFinished(
				true, start + index, 0L);
			if (index + 1 == steps.size()) {
				require(finished.action() == UltrasequencerSequenceExecutor.Action.CLOSE_MENU,
					"the full solution at the solver-reported milestone requests a menu close without cursor ack");
			}
		}
	}

	/** The Solver may display Auto's cursor only for the exact active context and captured solution. */
	private static void checkExecutionProgressPresentationSnapshot() {
		require("Experimentation Solver".equals(ExperimentSolverModule.INSTANCE.name())
			&& "Solver".equals(ExperimentSolverModule.INSTANCE.configName()),
			"the visible module rename retains the legacy persisted module identity");
		List<SequenceStep> steps = List.of(new SequenceStep(0, "1", List.of(30)),
			new SequenceStep(1, "2", List.of(31)), new SequenceStep(2, "3", List.of(32)));
		SolverView view = solverView(ExperimentType.ULTRASEQUENCER, ExperimentPhase.SOLVE,
			steps, 0, steps.getFirst());
		UltrasequencerSequenceExecutor executor = new UltrasequencerSequenceExecutor();
		Object screen = new Object();
		Object menu = new Object();
		long generation = 42;
		long start = 20_000_000L;
		executor.tick(screen, menu, generation, true, view, start, 100L);
		var initial = executor.presentationProgress(screen, menu, generation, view).orElseThrow();
		require(initial.cursor() == 0 && initial.sequenceLength() == 3 && !initial.dispatchPending(),
			"presentation begins at Auto's independent cursor without advancing it");
		require(displayedSlots(ExperimentSolverModule.ultrasequencerPreview(view,
			Optional.of(initial), 3)).equals(List.of(30, 31, 32)),
			"the initial Auto snapshot keeps the current and future tile highlights in order");
		require(executor.cursor() == 0,
			"reading presentation progress has no effect on Auto execution state");
		require(executor.presentationProgress(new Object(), menu, generation, view).isEmpty()
			&& executor.presentationProgress(screen, new Object(), generation, view).isEmpty()
			&& executor.presentationProgress(screen, menu, generation + 1, view).isEmpty(),
			"a different screen, menu, or session generation cannot reuse the display snapshot");

		var click = executor.tick(screen, menu, generation, true, view, start + 100L, 100L);
		var dispatching = executor.presentationProgress(screen, menu, generation, view).orElseThrow();
		require(click.action() == UltrasequencerSequenceExecutor.Action.CLICK
			&& dispatching.cursor() == 1 && dispatching.dispatchPending(),
			"the display snapshot reports the claimed Auto position while vanilla dispatch is pending");
		require(displayedSlots(ExperimentSolverModule.ultrasequencerPreview(view,
			Optional.of(dispatching), 3)).equals(List.of(30, 31, 32)),
			"a pending dispatch keeps its clicked tile current until vanilla returns");
		executor.dispatchFinished(true, start + 100L, 200L);
		var dispatched = executor.presentationProgress(screen, menu, generation, view).orElseThrow();
		require(dispatched.cursor() == 1 && !dispatched.dispatchPending(),
			"a successful dispatch updates only the displayed Auto cursor, not Solver progress");
		require(displayedSlots(ExperimentSolverModule.ultrasequencerPreview(view,
			Optional.of(dispatched), 3)).equals(List.of(31, 32)),
			"after vanilla dispatch, tile priorities advance to the next Auto slot while Solver stays unchanged");

		List<SequenceStep> changedSteps = List.of(steps.getFirst(),
			new SequenceStep(1, "2", List.of(99)), steps.get(2));
		SolverView changedView = solverView(ExperimentType.ULTRASEQUENCER, ExperimentPhase.SOLVE,
			changedSteps, 0, changedSteps.getFirst());
		require(executor.presentationProgress(screen, menu, generation, changedView).isEmpty(),
			"a changed ordered-solution fingerprint cannot display stale Auto progress");
		require(displayedSlots(ExperimentSolverModule.ultrasequencerPreview(changedView,
			Optional.of(dispatched), 3)).equals(List.of(30, 99, 32)),
			"a stale Auto snapshot falls back to the Solver's current ordered tile preview");
	}

	private static List<Integer> displayedSlots(List<SequenceStep> steps) {
		return steps.stream().map(step -> step.slotIds().getFirst()).toList();
	}

	private static SolverView solverView(ExperimentType type, ExperimentPhase phase,
		List<SequenceStep> sequence, int index, SequenceStep current) {
		return new SolverView(type, ExperimentTier.HIGH, phase, sequence.size(), 0, sequence,
			index, index, index, Optional.of(current), Optional.empty(), Optional.empty(),
			SuperpairsBoard.View.empty(), Optional.empty(), false);
	}

	/**
	 * A pane repaint can arrive before the round's timer, which puts the model at END while the
	 * round's numbers are still remembered. END used to ignore the timer outright, so the one signal
	 * that opens the solve window was discarded and the round sat at ROUND_COMPLETE for its whole
	 * solve. The timer now reopens a round that was never replayed, and the cursor still starts over.
	 */
	private static void checkUltraTimerAfterPaneEdge() {
		UltrasequencerModel model = new UltrasequencerModel();
		model.markDirty(List.of("gray"));
		model.observe("Remember the pattern!", List.of(ExperimentCell.number(30, 1),
			ExperimentCell.number(31, 2)), null);
		require(model.state() == UltrasequencerModel.State.WAIT, "the memory notice starts the round");

		// The pane repaint lands before the timer, so the round looks finished although it never ran.
		model.markDirty(List.of("blue"));
		require(model.state() == UltrasequencerModel.State.END,
			"a pane colour before the timer reads as a round edge");
		require(model.sequence().size() == 2, "the remembered numbers survive that edge");

		model.observe("Timer: 3s", List.of(), null);
		require(model.state() == UltrasequencerModel.State.SHOW,
			"the timer reopens a round whose edge arrived before it");
		require(model.currentIndex() == 0, "the reopened round starts at its first number");
		require(model.click(30) && model.click(31), "the reopened round replays every click");

		// A round that really was replayed must not be reopened by a later stale timer.
		model.markDirty(List.of("purple"));
		require(model.state() == UltrasequencerModel.State.END,
			"the completed round ends at its pane edge again");
		model.observe("Timer: 2s", List.of(), null);
		require(model.state() == UltrasequencerModel.State.END,
			"a timer cannot restart a round that was already replayed");

		// And a timer with nothing remembered must not invent a solve.
		UltrasequencerModel empty = new UltrasequencerModel();
		empty.markDirty(List.of("gray"));
		empty.markDirty(List.of("blue"));
		empty.observe("Timer: 3s", List.of(), null);
		require(empty.state() != UltrasequencerModel.State.SHOW,
			"a timer without remembered numbers cannot open a solve");
	}

	/** The solver exposes the remembered first slot early, while the click gate still waits for solve. */
	private static void checkUltraOpeningClick() {
		String title = "Ultrasequencer (High)";
		List<ExperimentCell> cells = List.of(ExperimentCell.number(30, 1),
			ExperimentCell.number(31, 2), ExperimentCell.number(32, 3));
		ExperimentSolverEngine engine = new ExperimentSolverEngine();
		engine.observe(new ExperimentSnapshot(title, "Remember the pattern!", cells));

		require(engine.view().phase() == ExperimentPhase.WAITING,
			"the round waits for its solve window after the memory notice");
		require(engine.view().current().orElseThrow().containsSlot(30),
			"the first solver slot is already available during the wait");
		ExperimentClickGate gate = new ExperimentClickGate();
		boolean[] dispatched = {false};
		require(!gate.dispatchSequenceClick(engine, 0, 30, true, id -> dispatched[0] = true),
			"the vanilla click gate waits for the solver's solve phase");
		require(!dispatched[0], "the pre-solve slot is not dispatched");

		engine.observe(new ExperimentSnapshot(title, "Timer: 3s", cells));
		require(engine.view().phase() == ExperimentPhase.SOLVE, "the timer opens the solve phase");
		require(gate.dispatchSequenceClick(engine, 0, 30, true, id -> dispatched[0] = true),
			"the expected first slot is dispatched after the solver opens the solve phase");
		require(dispatched[0] && engine.view().visualIndex() == 1,
			"the confirmed first click advances the solver cursor exactly once");
		require(!gate.dispatchSequenceClick(engine, 0, 30, true, id -> { }),
			"the confirmed first slot cannot be dispatched a second time");

		// Chronomatron has no opening click: its solve is announced, never inferred.
		ExperimentSolverEngine chrono = new ExperimentSolverEngine();
		chrono.observe(new ExperimentSnapshot("Chronomatron (High)", "Remember the pattern!",
			List.of(ExperimentCell.token(17, "red", true))));
		require(!new ExperimentClickGate().dispatchSequenceClick(chrono, 0, 17, true, id -> { }),
			"Chronomatron still requires its own solve result before any click");
	}

	/**
	 * Round two of a real board, starting from the pane colour that is actually there.
	 *
	 * <p>The older traces all seeded {@code markDirty(List.of("gray"))} before anything else, which
	 * hides the one path a live board takes: the first pane colour a round sees is never black, so a
	 * new round's very first observation is already a colour change. This check drives round two
	 * through that exact order - pane colour, memory notice, timer, clicks - because that is the
	 * sequence BUG-003 was about and the one the old traces could not reach.
	 */
	private static void checkUltraRoundTwoSolve() {
		UltrasequencerModel fresh = new UltrasequencerModel();
		fresh.markDirty(List.of("orange"));
		// A pane colour that follows no round is read as the edge of one, so a brand new round starts
		// out looking finished. That is harmless only because the memory notice below resets it, which
		// is exactly what this trace is here to prove.
		require(fresh.state() == UltrasequencerModel.State.END,
			"a pane colour with no round in progress reads as a round edge");

		fresh.observe("Remember the pattern!", List.of(ExperimentCell.number(30, 1),
			ExperimentCell.number(31, 2)), null);
		require(fresh.state() == UltrasequencerModel.State.WAIT, "the memory notice starts round one");
		fresh.observe("Timer: 3s", List.of(), null);
		require(fresh.state() == UltrasequencerModel.State.SHOW, "the timer opens the solve");
		require(fresh.click(30) && fresh.click(31), "round one replays both clicks");
		fresh.markDirty(List.of("blue"));
		require(fresh.state() == UltrasequencerModel.State.END, "the completed round ends at its pane edge");
		require(fresh.completedRounds() == 1, "round one is counted once");

		// Round two starts the way the server starts it: the board repaints its panes first, and only
		// then does the next memory notice arrive. The repaint must not cost the round its capture.
		fresh.markDirty(List.of("purple"));
		fresh.observe("Remember the pattern!", List.of(ExperimentCell.number(40, 1),
			ExperimentCell.number(41, 2), ExperimentCell.number(42, 3)), null);
		require(fresh.state() == UltrasequencerModel.State.WAIT,
			"round two captures its numbers even though a pane colour preceded the notice");
		require(fresh.sequence().size() == 3, "round two captures its own numbers");
		fresh.observe("Timer: 3s", List.of(), null);
		require(fresh.state() == UltrasequencerModel.State.SHOW,
			"round two reaches its solve; a second round must be replayable, not just capturable");
		require(fresh.click(40) && fresh.click(41) && fresh.click(42), "round two replays all three clicks");
		fresh.markDirty(List.of("green"));
		require(fresh.completedRounds() == 2, "round two is counted once");
	}

	private static void checkRenderDoesNotLearn() {
		ExperimentSolverEngine engine = new ExperimentSolverEngine();
		String title = "Chronomatron (High)";
		List<ExperimentCell> red = List.of(ExperimentCell.token(17, "red", true));
		for (int i = 0; i < 20; i++) engine.observe(new ExperimentSnapshot(title, "Remember the pattern!", red));
		require(engine.view().sequence().isEmpty(), "render frames cannot invent reveal events");
		engine.observe(new ExperimentSnapshot(title, "Timer: 3s", red), ChronomatronEvent.board(17, "red", true));
		require(engine.view().phase() == ExperimentPhase.WAITING, "board events cannot synthesize timer events");
		engine.observe(new ExperimentSnapshot(title, "Timer: 3s", red), ChronomatronEvent.status("Timer: 3s"));
		require(engine.view().phase() == ExperimentPhase.SOLVE, "real timer opens solve");
		engine.confirmClick(17);
		for (int i = 0; i < 20; i++) engine.observe(new ExperimentSnapshot(title, "Remember the pattern!", red));
		require(engine.view().phase() == ExperimentPhase.ROUND_COMPLETE, "stale render cannot reopen click one");
		require(engine.view().visualIndex() == 1, "render cannot rewind completed cursor");
	}

	private static void checkUltraRounds() {
		UltrasequencerModel model = new UltrasequencerModel();
		model.markDirty(List.of("gray"));
		for (int round = 1; round <= 8; round++) {
			List<ExperimentCell> cells = new ArrayList<>();
			for (int i = 1; i <= round; i++) cells.add(ExperimentCell.number(9 + i, i));
			model.observe("Remember the pattern!", cells, null);
			model.observe("Remember the pattern!", List.of(ExperimentCell.number(44, 99)), null);
			require(model.sequence().size() == round, "WAIT freezes tick-captured number map");
			model.observe("Timer: 3s", List.of(), null);
			for (int i = 1; i <= round; i++) {
				require(model.sequence().get(i - 1).slotIds().equals(List.of(9 + i)), "number order retained");
				require(model.currentIndex() == i - 1, "cursor follows the remembered click order");
				require(model.click(9 + i), "expected Ultra click accepted");
				model.observe("Timer: 2s", List.of(), null);
			}
			require(model.currentIndex() == round - 1,
				"last selection waits for the server colour edge, as upstream");
			if (round < 8) {
				model.markDirty(List.of(round % 2 == 0 ? "blue" : "orange"));
				require(model.state() == UltrasequencerModel.State.END,
					"a pane boundary after the final click ends the round");
				model.observe("Remember the pattern!", List.of(), null);
				require(model.state() == UltrasequencerModel.State.REMEMBER,
					"tick starts the next round without a status callback");
				require(model.completedRounds() == round, "each real boundary counts exactly one round");
			}
		}
		require(model.completedRounds() == 7, "all eight Ultra rounds retained");

		UltrasequencerModel interrupted = new UltrasequencerModel();
		interrupted.markDirty(List.of("gray"));
		interrupted.observe("Remember the pattern!", List.of(ExperimentCell.number(30, 1),
			ExperimentCell.number(31, 2), ExperimentCell.number(32, 3)), null);
		interrupted.observe("Timer: 3s", List.of(), null);
		require(interrupted.click(30), "first click of the round is accepted");
		interrupted.markDirty(List.of("blue"));
		interrupted.markDirty(List.of("gray"));
		require(interrupted.state() == UltrasequencerModel.State.SHOW,
			"pressed and released button panes cannot end an unfinished round");
		require(interrupted.currentIndex() == 1, "the same panes cannot rewind or freeze the cursor");
		require(interrupted.click(31) && interrupted.click(32),
			"the rest of the round stays clickable after the pane churn");
		interrupted.observe("Remember the pattern!", List.of(ExperimentCell.number(30, 1),
			ExperimentCell.number(31, 2), ExperimentCell.number(32, 3),
			ExperimentCell.number(33, 4)), null);
		require(interrupted.sequence().size() == 4,
			"the next memory notice resets and captures the longer round");
		require(interrupted.completedRounds() == 1,
			"an interrupted solve counts its round exactly once");

		UltrasequencerModel counted = new UltrasequencerModel();
		counted.markDirty(List.of("white"));
		counted.observe("Remember the pattern!", List.of(ExperimentCell.number(30, 1)), null);
		counted.observe("Timer: 3s", List.of(), null);
		require(counted.click(30), "single-click round accepted before its boundary");
		counted.markDirty(List.of("red"));
		require(counted.state() == UltrasequencerModel.State.END,
			"a completed round still ends through its pane boundary");
		counted.observe("Remember the pattern!", List.of(ExperimentCell.number(30, 1),
			ExperimentCell.number(31, 2)), null);
		require(counted.completedRounds() == 1,
			"a round that already ended through a pane is not counted twice");

		model.reset();
		model.observe("Remember the pattern!", List.of(
			new ExperimentCell(20, "1", 4, true, false, false),
			new ExperimentCell(21, "9", 5, true, false, false)), null);
		model.observe("Timer: 3s", List.of(), null);
		require(model.click(20) && model.currentIndex() == 1, "Ultra uses saved stack count for successor");
	}

	private static void checkVanillaListenerBridge() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		// 26.1 binds item defaults while loading data packs. This headless fixture needs only
		// the common components; names and glints used below are explicit per-stack overrides.
		net.minecraft.core.registries.BuiltInRegistries.ITEM.listElements().forEach(holder -> {
			if (!holder.areComponentsBound()) holder.bindComponents(DataComponents.COMMON_ITEM_COMPONENTS);
		});
		TestMenu menu = new TestMenu();
		ChronomatronModel model = new ChronomatronModel();
		int[] events = {0};
		menu.addSlotListener(new ContainerListener() {
			@Override public void slotChanged(AbstractContainerMenu source, int slot, ItemStack stack) {
				events[0]++;
				if (slot == 49) model.observe(ChronomatronEvent.status(stack.getHoverName().getString()));
				else if (slot >= 17 && slot <= 25) model.observe(ChronomatronEvent.board(slot,
					stack.is(Items.RED_TERRACOTTA) || stack.is(Items.RED_STAINED_GLASS)
						? "red" : "blue", stack.hasFoil()));
			}
			@Override public void dataChanged(AbstractContainerMenu source, int property, int value) { }
		});
		for (int round = 1; round <= 6; round++) {
			set(menu, 49, status("Remember the pattern!"));
			if (round > 1) set(menu, 17 + (round - 2) % 2, tile((round - 2) % 2 == 0, false));
			for (int i = 0; i < round; i++) {
				set(menu, 17 + i % 2, tile(i % 2 == 0, true));
				if (i + 1 < round) set(menu, 17 + i % 2, tile(i % 2 == 0, false));
			}
			for (int i = 0; i < Math.min(round, 2); i++) set(menu, 17 + i, glass(i == 0, false));
			set(menu, 49, status("Timer: 3s"));
			require(model.items().size() == round, "real listener captures exactly one new item each round");
			for (int i = 0; i < round; i++) {
				require(model.click(i % 2 == 0 ? "red" : "blue"), "real listener trace retains click order");
				// Player click glints are delivered too, but may not grow memory or rewind progress.
				set(menu, 17 + i % 2, glass(i % 2 == 0, true));
				set(menu, 17 + i % 2, glass(i % 2 == 0, false));
			}
			require(model.state() == ChronomatronModel.State.END, "final click remains ended");
		}
		int before = events[0];
		menu.broadcastChanges();
		ExperimentMenuUpdates.afterServerUpdate(menu, () -> {});
		require(events[0] == before, "two mods broadcasting cannot duplicate unchanged slot events");
		List<ItemStack> full = new ArrayList<>();
		for (int i = 0; i < 54; i++) full.add(ItemStack.EMPTY);
		full.set(49, status("Remember the pattern!"));
		menu.initializeContents(100, full, ItemStack.EMPTY);
		ExperimentMenuUpdates.afterServerUpdate(menu, () -> {});
		require(model.state() == ChronomatronModel.State.REMEMBER, "full-content update reaches listener");
	}
	private static void set(TestMenu menu, int slot, ItemStack stack) {
		menu.setItem(slot, menu.getStateId() + 1, stack);
		ExperimentMenuUpdates.afterServerUpdate(menu, () -> {});
	}
	private static ItemStack tile(boolean red, boolean glint) {
		ItemStack stack = new ItemStack(red ? Items.RED_TERRACOTTA : Items.BLUE_TERRACOTTA);
		stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, glint);
		return stack;
	}
	private static ItemStack status(String text) {
		ItemStack stack = new ItemStack(Items.CLOCK);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(text));
		return stack;
	}
	private static ItemStack glass(boolean red, boolean glint) {
		ItemStack stack = new ItemStack(red ? Items.RED_STAINED_GLASS : Items.BLUE_STAINED_GLASS);
		stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, glint);
		return stack;
	}
	private static final class TestMenu extends AbstractContainerMenu {
		TestMenu() {
			super(null, 1);
			SimpleContainer inventory = new SimpleContainer(54);
			for (int i = 0; i < 54; i++) addSlot(new Slot(inventory, i, 0, 0));
		}
		@Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
		@Override public boolean stillValid(Player player) { return true; }
	}
	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
