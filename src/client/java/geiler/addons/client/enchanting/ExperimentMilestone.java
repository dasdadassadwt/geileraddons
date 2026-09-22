package geiler.addons.client.enchanting;

import java.util.Optional;

/** Bonus-click stopping point, including serum adjustment and Ultrasequencer's one-round offset. */
public record ExperimentMilestone(ExperimentType type, ExperimentTier tier, int serumsConsumed,
	int displayedSequenceLength, int completedRoundThreshold, int maximumExtraClicks) {
	public ExperimentMilestone {
		if (type == null || type == ExperimentType.SUPERPAIRS) {
			throw new IllegalArgumentException("Only sequence experiments have click milestones");
		}
		if (tier == null || tier == ExperimentTier.UNKNOWN) {
			throw new IllegalArgumentException("A known tier is required");
		}
		serumsConsumed = Math.max(0, Math.min(3, serumsConsumed));
		if (displayedSequenceLength < 1 || completedRoundThreshold < 0) {
			throw new IllegalArgumentException("Invalid milestone threshold");
		}
	}

	public static Optional<ExperimentMilestone> forExperiment(ExperimentType type,
		ExperimentTier tier, int serumsConsumed) {
		if (type == null || type == ExperimentType.SUPERPAIRS || tier == null
			|| tier == ExperimentTier.UNKNOWN) return Optional.empty();
		int base = type == ExperimentType.CHRONOMATRON
			? tier.chronomatronThreshold() : tier.ultrasequencerThreshold();
		if (base < 1) return Optional.empty();
		int serumCount = Math.max(0, Math.min(3, serumsConsumed));
		int displayed = Math.max(1, base - serumCount);
		int completed = type == ExperimentType.ULTRASEQUENCER ? displayed - 1 : displayed;
		return Optional.of(new ExperimentMilestone(type, tier, serumCount, displayed, completed,
		tier.maximumExtraClicks()));
	}

	public boolean reached(int currentSequenceLength, int completedRounds) {
		return reached(currentSequenceLength, completedRounds, ExperimentPhase.SOLVE);
	}

	public boolean reached(int currentSequenceLength, int completedRounds, ExperimentPhase phase) {
		int effectiveCompletedRounds = completedRounds;
		if (type == ExperimentType.CHRONOMATRON && phase == ExperimentPhase.ROUND_COMPLETE
			&& completedRounds >= 0) {
			// Chronomatron increments this counter when the following pattern begins. Count the
			// just-finished round at its actual ROUND_COMPLETE boundary instead of waiting one stage.
			effectiveCompletedRounds++;
		}
		if (effectiveCompletedRounds >= 0) {
			return effectiveCompletedRounds >= completedRoundThreshold;
		}
		if (type == ExperimentType.CHRONOMATRON && phase == ExperimentPhase.ROUND_COMPLETE) {
			return currentSequenceLength >= displayedSequenceLength;
		}
		return type == ExperimentType.ULTRASEQUENCER
			? currentSequenceLength >= displayedSequenceLength
			: currentSequenceLength > displayedSequenceLength;
	}
}
