package geiler.addons.client.enchanting;

import java.util.List;
import java.util.Optional;

/** Immutable render/input snapshot produced by {@link ExperimentSolverEngine}. */
public record SolverView(ExperimentType type, ExperimentTier tier, ExperimentPhase phase,
	int currentSequenceLength, int completedRounds, List<SequenceStep> sequence,
	int authoritativeIndex, int predictedIndex, int visualIndex,
	Optional<SequenceStep> current, Optional<SequenceStep> next, Optional<SequenceStep> nextNext,
	SuperpairsBoard.View superpairs, Optional<ExperimentMilestone> milestone,
	boolean milestoneReached) {
	public SolverView {
		sequence = List.copyOf(sequence == null ? List.of() : sequence);
		current = current == null ? Optional.empty() : current;
		next = next == null ? Optional.empty() : next;
		nextNext = nextNext == null ? Optional.empty() : nextNext;
		superpairs = superpairs == null ? SuperpairsBoard.View.empty() : superpairs;
		milestone = milestone == null ? Optional.empty() : milestone;
	}

	public static SolverView idle() {
		return new SolverView(null, ExperimentTier.UNKNOWN, ExperimentPhase.IDLE, -1, -1,
			List.of(), 0, 0, 0, Optional.empty(), Optional.empty(), Optional.empty(),
			SuperpairsBoard.View.empty(), Optional.empty(), false);
	}

	/** The real remaining sequence, capped without manufacturing unavailable future clicks. */
	public List<SequenceStep> upcoming(int maximum) {
		if (maximum <= 0 || visualIndex < 0 || visualIndex >= sequence.size()) return List.of();
		return sequence.subList(visualIndex, Math.min(sequence.size(), visualIndex + maximum));
	}
}
