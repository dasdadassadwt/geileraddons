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
		// Chronomatron reveals the next sequence before the player solves it. Its completed-round
		// counter is therefore the authoritative boundary: a High game reaches maximum clicks when
		// round ten starts, not while round nine is merely being displayed. The sequence length is
		// only a fallback for adapters that cannot provide the counter yet.
		if (completedRounds >= 0) return completedRounds >= completedRoundThreshold;
		return type == ExperimentType.ULTRASEQUENCER
			? currentSequenceLength >= displayedSequenceLength
			: currentSequenceLength > displayedSequenceLength;
	}
}
