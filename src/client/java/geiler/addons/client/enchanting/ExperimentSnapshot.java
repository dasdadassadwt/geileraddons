package geiler.addons.client.enchanting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Immutable input boundary between Minecraft/container code and the pure solver engine. */
public record ExperimentSnapshot(String title, String status, List<ExperimentCell> cells,
	String ultrasequencerPaneColor, long revision, boolean statusEvent) {
	public ExperimentSnapshot {
		title = title == null ? "" : title.trim();
		status = status == null ? "" : status.trim();
		List<ExperimentCell> copy = new ArrayList<>(cells == null ? List.of() : cells);
		copy.sort(Comparator.comparingInt(ExperimentCell::slotId));
		cells = List.copyOf(copy);
		ultrasequencerPaneColor = ultrasequencerPaneColor == null
			? null : ultrasequencerPaneColor.trim().toLowerCase(java.util.Locale.ROOT);
	}

	public ExperimentSnapshot(String title, String status, List<ExperimentCell> cells) {
		this(title, status, cells, null, 0L, false);
	}

	public ExperimentSnapshot(String title, String status, List<ExperimentCell> cells,
		String ultrasequencerPaneColor, long revision) {
		this(title, status, cells, ultrasequencerPaneColor, revision, false);
	}

	/**
	 * Compatibility constructor for older offline fixtures. Progress fields are intentionally
	 * ignored: cursors now advance only from ordered container events and accepted local clicks.
	 */
	@Deprecated
	public ExperimentSnapshot(String title, String status, List<ExperimentCell> cells,
		int ignoredSequenceLength, int ignoredCompletedRounds, int ignoredAuthoritativeProgress,
		long revision) {
		this(title, status, cells, null, revision, false);
	}

	public ExperimentType type() {
		return ExperimentType.fromTitle(title).orElse(null);
	}

	public ExperimentTier tier() {
		return ExperimentTier.fromTitle(title).orElse(ExperimentTier.UNKNOWN);
	}

	public ExperimentPhase phase() {
		return ExperimentPhase.detect(type(), status);
	}

	public Optional<ExperimentCell> highlightedCell() {
		return cells.stream().filter(cell -> cell.highlighted() && cell.hasValue()).findFirst();
	}
}
