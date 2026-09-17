package geiler.addons.client.enchanting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shared, Minecraft-free state machine for Chronomatron, Ultrasequencer, and Superpairs.
 *
 * <p>The sequence solvers intentionally have one source of truth for each cursor: ordered menu
 * events update the memory/state machine and an accepted local click advances the cursor. A polled
 * snapshot can refresh the visible board, but it can never rewrite progress from an inferred or
 * delayed counter.</p>
 */
public final class ExperimentSolverEngine {
	public record Configuration(int serumsConsumed) {
		public Configuration {
			serumsConsumed = Math.max(0, Math.min(3, serumsConsumed));
		}

		public static Configuration defaults() {
			return new Configuration(0);
		}
	}

	private static final class MutableStep {
		private final String value;
		private final List<Integer> slots = new ArrayList<>();

		private MutableStep(String value, int slotId) {
			this.value = value;
			addSlot(slotId);
		}

		private void addSlot(int slotId) {
			if (slotId >= 0 && !slots.contains(slotId)) slots.add(slotId);
		}

		private void replaceSlots(List<Integer> slotIds) {
			slots.clear();
			for (int slotId : slotIds) addSlot(slotId);
		}

		private boolean containsSlot(int slotId) {
			return slots.contains(slotId);
		}

		private SequenceStep immutable(int index) {
			return new SequenceStep(index, value, slots);
		}
	}

	private Configuration configuration;
	private ExperimentType type;
	private ExperimentTier tier = ExperimentTier.UNKNOWN;
	private ExperimentPhase phase = ExperimentPhase.IDLE;
	private long lastRevision = Long.MIN_VALUE;
	private int currentSequenceLength = -1;
	private int completedRounds = -1;
	/** Kept as separate fields for the existing render contract; both mirror the model cursor. */
	private int authoritativeIndex;
	private int predictedIndex;
	private final List<MutableStep> sequence = new ArrayList<>();
	private final ChronomatronModel chronomatron = new ChronomatronModel();
	private final Map<Integer, String> chronomatronBoard = new LinkedHashMap<>();
	private final UltrasequencerModel ultrasequencer = new UltrasequencerModel();
	private final SuperpairsBoard superpairs = new SuperpairsBoard();

	public ExperimentSolverEngine() {
		this(Configuration.defaults());
	}

	public ExperimentSolverEngine(Configuration configuration) {
		this.configuration = configuration == null ? Configuration.defaults() : configuration;
	}

	public Configuration configuration() {
		return configuration;
	}

	public void configure(Configuration configuration) {
		this.configuration = configuration == null ? Configuration.defaults() : configuration;
	}

	/** Consumes a new server/container snapshot and returns the complete immutable render view. */
	public SolverView observe(ExperimentSnapshot snapshot) {
		return observe(snapshot, null);
	}

	/** Consumes a snapshot together with its ordered Chronomatron event, when present. */
	public SolverView observe(ExperimentSnapshot snapshot, ChronomatronEvent chronomatronEvent) {
		if (snapshot == null || snapshot.type() == null) {
			resetInternal();
			return SolverView.idle();
		}

		ExperimentType incomingType = snapshot.type();
		ExperimentTier incomingTier = snapshot.tier();
		if (type != incomingType || (tier != ExperimentTier.UNKNOWN && incomingTier != ExperimentTier.UNKNOWN
			&& tier != incomingTier)) {
			resetInternal();
			type = incomingType;
			tier = incomingTier;
		}
		if (lastRevision != Long.MIN_VALUE && snapshot.revision() < lastRevision) {
			// A delayed callback must not roll a local cursor or a remembered sequence backwards.
			return view();
		}
		lastRevision = Math.max(lastRevision, snapshot.revision());
		if (tier == ExperimentTier.UNKNOWN && incomingTier != ExperimentTier.UNKNOWN) tier = incomingTier;

		return switch (type) {
			case CHRONOMATRON -> observeChronomatron(snapshot, chronomatronEvent);
			case ULTRASEQUENCER -> observeUltrasequencer(snapshot);
			case SUPERPAIRS -> observeSuperpairs(snapshot);
		};
	}

	/** Records a local click; sequence progress advances only after server-confirmed state. */
	public ClickDecision onClick(int slotId) {
		if (type == null) return new ClickDecision(slotId, false, false, false, false, "no experiment");
		if (phase != ExperimentPhase.SOLVE) {
			return new ClickDecision(slotId, true, false, false, false, "not in solve phase");
		}
		if (type == ExperimentType.SUPERPAIRS) {
			boolean changed = superpairs.click(slotId);
			return new ClickDecision(slotId, true, changed, false, changed, changed ? "selected" : "unplayable");
		}

		if (!expectedSequenceSlot(slotId)) {
			return new ClickDecision(slotId, true, false, false, false, "unexpected slot");
		}
		return new ClickDecision(slotId, true, true, false, false, "awaiting server confirmation");
	}

	/**
	 * Confirms a click after the vanilla container click has been dispatched. This is deliberately
	 * separate from {@link #onClick(int)}: normal menu clicks do not always produce an item diff.
	 */
	public ClickDecision confirmClick(int slotId) {
		if (type == null) return new ClickDecision(slotId, false, false, false, false, "no experiment");
		if (phase != ExperimentPhase.SOLVE || type == ExperimentType.SUPERPAIRS) {
			return new ClickDecision(slotId, true, false, false, false, "not a sequence click");
		}
		if (!expectedSequenceSlot(slotId)) {
			return new ClickDecision(slotId, true, false, false, false, "unexpected slot");
		}
		if (!advanceSequenceClick(slotId)) {
			return new ClickDecision(slotId, true, false, false, false, "unexpected slot");
		}
		return new ClickDecision(slotId, true, true, false, true, "click dispatched");
	}

	public SolverView reset() {
		resetInternal();
		return SolverView.idle();
	}

	/** Alias for lifecycle adapters that receive a disconnect rather than a screen close. */
	public SolverView onDisconnect() {
		return reset();
	}

	public SolverView view() {
		List<SequenceStep> steps = sequenceView();
		int visualIndex = Math.min(displayIndex(), steps.size());
		Optional<SequenceStep> current = stepAt(steps, visualIndex);
		Optional<SequenceStep> next = stepAt(steps, visualIndex + 1);
		Optional<SequenceStep> nextNext = stepAt(steps, visualIndex + 2);
		Optional<ExperimentMilestone> milestone = ExperimentMilestone.forExperiment(type, tier,
			configuration.serumsConsumed());
		boolean reached = (phase == ExperimentPhase.SOLVE || phase == ExperimentPhase.ROUND_COMPLETE)
			&& milestone.map(value -> value.reached(currentSequenceLength, completedRounds)).orElse(false);
		return new SolverView(type, tier, phase, currentSequenceLength, completedRounds, steps,
			authoritativeIndex, predictedIndex, visualIndex, current, next, nextNext,
			superpairs.view(), milestone, reached);
	}

	private SolverView observeChronomatron(ExperimentSnapshot snapshot, ChronomatronEvent event) {
		boolean explicitRememberEvent = event != null
			&& event.kind() == ChronomatronEvent.Kind.STATUS
			&& ExperimentPhase.isRememberStatus(event.status());
		if (phase == ExperimentPhase.COMPLETE && explicitRememberEvent) {
			// A menu with the same title can be opened for a fresh game after a terminal result.
			chronomatron.reset();
			chronomatronBoard.clear();
			phase = ExperimentPhase.IDLE;
		}

		chronomatron.observe(event);

		chronomatronBoard.clear();
		for (ExperimentCell cell : snapshot.cells()) {
			if (cell.hasValue() && !cell.removed()) chronomatronBoard.put(cell.slotId(), cell.value());
		}
		syncChronomatronSequence();
		currentSequenceLength = sequence.size();
		completedRounds = chronomatron.completedRounds();
		authoritativeIndex = chronomatron.currentOrdinal();
		predictedIndex = authoritativeIndex;

		phase = chronomatron.phase();
		return view();
	}

	private SolverView observeUltrasequencer(ExperimentSnapshot snapshot) {
		if (phase == ExperimentPhase.COMPLETE && ExperimentPhase.isRememberStatus(snapshot.status())) {
			ultrasequencer.reset();
			phase = ExperimentPhase.IDLE;
		}
		ultrasequencer.observe(snapshot.status(), snapshot.cells(), snapshot.ultrasequencerPaneColor(),
			snapshot.statusEvent());
		sequence.clear();
		for (SequenceStep step : ultrasequencer.sequence()) {
			MutableStep copy = new MutableStep(step.value(), -1);
			copy.replaceSlots(step.slotIds());
			sequence.add(copy);
		}
		currentSequenceLength = sequence.size();
		completedRounds = ultrasequencer.completedRounds();
		authoritativeIndex = ultrasequencer.currentIndex();
		predictedIndex = authoritativeIndex;

		phase = ultrasequencer.phase();
		return view();
	}

	/** Inventory mutations, unlike rendering, can signal an Ultrasequencer round boundary. */
	public void markUltrasequencerDirty(List<String> paneColors) {
		if (type != ExperimentType.ULTRASEQUENCER) return;
		ultrasequencer.markDirty(paneColors);
		phase = ultrasequencer.phase();
	}

	private SolverView observeSuperpairs(ExperimentSnapshot snapshot) {
		if (phase == ExperimentPhase.COMPLETE && !ExperimentPhase.isTerminalStatus(snapshot.status())) {
			superpairs.reset();
		}
		superpairs.observe(snapshot.cells());
		sequence.clear();
		currentSequenceLength = -1;
		completedRounds = -1;
		authoritativeIndex = 0;
		predictedIndex = 0;
		phase = ExperimentPhase.isTerminalStatus(snapshot.status())
			? ExperimentPhase.COMPLETE : ExperimentPhase.SOLVE;
		return view();
	}

	private void syncChronomatronSequence() {
		sequence.clear();
		for (String item : chronomatron.items()) {
			MutableStep step = new MutableStep(item, -1);
			List<Integer> liveSlots = new ArrayList<>();
			for (Map.Entry<Integer, String> entry : chronomatronBoard.entrySet()) {
				if (item.equals(entry.getValue())) liveSlots.add(entry.getKey());
			}
			step.replaceSlots(liveSlots);
			sequence.add(step);
		}
	}

	private boolean expectedSequenceSlot(int slotId) {
		int index = displayIndex();
		if (index < 0 || index >= sequence.size()) return false;
		if (type == ExperimentType.CHRONOMATRON) {
			return chronomatron.matches(index, chronomatronBoard.get(slotId));
		}
		return sequence.get(index).containsSlot(slotId);
	}

	private boolean advanceSequenceClick(int slotId) {
		boolean advanced = type == ExperimentType.CHRONOMATRON
			? chronomatron.click(chronomatronBoard.get(slotId))
			: ultrasequencer.click(slotId);
		if (!advanced) return false;
		authoritativeIndex = displayIndexFromModel();
		predictedIndex = authoritativeIndex;
		phase = type == ExperimentType.CHRONOMATRON ? chronomatron.phase() : ultrasequencer.phase();
		return true;
	}

	private int displayIndexFromModel() {
		return type == ExperimentType.CHRONOMATRON
			? chronomatron.currentOrdinal() : ultrasequencer.currentIndex();
	}

	private List<SequenceStep> sequenceView() {
		List<SequenceStep> result = new ArrayList<>();
		for (int i = 0; i < sequence.size(); i++) result.add(sequence.get(i).immutable(i));
		return List.copyOf(result);
	}

	private int displayIndex() {
		if (type == ExperimentType.CHRONOMATRON) return chronomatron.currentOrdinal();
		if (type == ExperimentType.ULTRASEQUENCER) return ultrasequencer.currentIndex();
		return authoritativeIndex;
	}

	private static Optional<SequenceStep> stepAt(List<SequenceStep> steps, int index) {
		return index >= 0 && index < steps.size() ? Optional.of(steps.get(index)) : Optional.empty();
	}

	private void resetProgress() {
		phase = ExperimentPhase.IDLE;
		currentSequenceLength = -1;
		completedRounds = -1;
		authoritativeIndex = 0;
		predictedIndex = 0;
		sequence.clear();
		chronomatron.reset();
		chronomatronBoard.clear();
		ultrasequencer.reset();
		superpairs.reset();
	}

	private void resetInternal() {
		type = null;
		tier = ExperimentTier.UNKNOWN;
		lastRevision = Long.MIN_VALUE;
		resetProgress();
	}
}
