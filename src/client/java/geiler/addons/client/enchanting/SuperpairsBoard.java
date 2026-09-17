package geiler.addons.client.enchanting;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

/**
 * The renderer-independent Superpairs memory.
 *
 * <p>Superpairs has no sequence to wait for. Every playable field may be clicked, and the only
 * local memory needed to discover a card is the last clicked slot. This mirrors Skyblocker's
 * solver: the adapter supplies a card value after the server reveals it, while this class keeps
 * discovered identities, duplicate hints, and completed pairs.</p>
 */
public final class SuperpairsBoard {
	private static final long REVEAL_RETRY_DELAY_NANOS = 500_000_000L;
	private final Set<Integer> slots = new LinkedHashSet<>();
	private final Map<Integer, String> known = new LinkedHashMap<>();
	private final Set<Integer> resolved = new LinkedHashSet<>();
	private final Set<Integer> duplicateSlots = new LinkedHashSet<>();
	private final Set<Integer> visibleSlots = new HashSet<>();
	private int selectedSlot = -1;
	private int awaitingRevealSlot = -1;
	private long awaitingRevealAtNanos;
	private int comparisonFirstSlot = -1;
	private int comparisonSecondSlot = -1;
	private boolean comparisonMatch;
	private boolean comparisonMismatch;
	private boolean comparisonRevealed;

	public void reset() {
		slots.clear();
		known.clear();
		resolved.clear();
		duplicateSlots.clear();
		visibleSlots.clear();
		selectedSlot = -1;
		awaitingRevealSlot = -1;
		awaitingRevealAtNanos = 0;
		clearComparison();
	}

	/** Applies the current server-visible board while retaining values learned earlier. */
	public void observe(Iterable<ExperimentCell> cells) {
		if (cells == null) return;
		Set<Integer> visible = new HashSet<>();
		for (ExperimentCell cell : cells) {
			if (cell == null) continue;
			slots.add(cell.slotId());
			if (!cell.revealed() || !cell.hasValue()) continue;
			visible.add(cell.slotId());
			if (cell.slotId() == awaitingRevealSlot
				|| ((comparisonFirstSlot == cell.slotId() || comparisonSecondSlot == cell.slotId())
					&& !visibleSlots.contains(cell.slotId()))) {
				comparisonRevealed = true;
			}
			if (!known.containsKey(cell.slotId())) {
				known.put(cell.slotId(), cell.value());
				markDuplicate(cell.slotId(), cell.value());
			}
		}

		if (awaitingRevealSlot >= 0 && known.containsKey(awaitingRevealSlot)) {
			awaitingRevealSlot = -1;
			awaitingRevealAtNanos = 0;
		}
		evaluateComparison();
		if (comparisonMatch && comparisonRevealed && pairHidden(visible)) {
			resolved.add(comparisonFirstSlot);
			resolved.add(comparisonSecondSlot);
			selectedSlot = -1;
			awaitingRevealSlot = -1;
			awaitingRevealAtNanos = 0;
			clearComparison();
		} else if (comparisonMismatch && comparisonRevealed && pairHidden(visible)) {
			selectedSlot = -1;
			awaitingRevealSlot = -1;
			awaitingRevealAtNanos = 0;
			clearComparison();
		}

		visibleSlots.clear();
		visibleSlots.addAll(visible);
	}

	private boolean pairHidden(Set<Integer> visible) {
		// A reveal can be represented by an empty stack or by the server's hidden-card
		// placeholder. The comparison state already proves both cards were revealed, so
		// returning to two hidden fields is the reliable completion signal.
		return !visible.contains(comparisonFirstSlot) && !visible.contains(comparisonSecondSlot);
	}

	private void markDuplicate(int slotId, String value) {
		for (Map.Entry<Integer, String> entry : known.entrySet()) {
			if (entry.getKey() != slotId && value.equals(entry.getValue())
				&& !resolved.contains(entry.getKey())) {
				duplicateSlots.add(entry.getKey());
				duplicateSlots.add(slotId);
			}
		}
	}

	private void evaluateComparison() {
		if (comparisonFirstSlot < 0 || comparisonSecondSlot < 0) return;
		String first = known.get(comparisonFirstSlot);
		String second = known.get(comparisonSecondSlot);
		if (first == null || second == null) return;
		comparisonMatch = first.equals(second);
		comparisonMismatch = !comparisonMatch;
	}

	/**
	 * Records a local click. A second click is deliberately rejected until the first reveal is
	 * observed; this prevents one pending server update from being attributed to the wrong card.
	 * If the reveal never arrives, the same control becomes retryable after a short grace period.
	 */
	public boolean click(int slotId) {
		return click(slotId, System.nanoTime());
	}

	boolean click(int slotId, long nowNanos) {
		if (!slots.contains(slotId) || resolved.contains(slotId)) return false;
		if (awaitingRevealSlot >= 0) {
			if (nowNanos - awaitingRevealAtNanos < REVEAL_RETRY_DELAY_NANOS) return false;
			// The old request is no longer trusted. Drop only the unconfirmed selection; learned
			// identities and already completed pairs remain authoritative.
			selectedSlot = -1;
			awaitingRevealSlot = -1;
			awaitingRevealAtNanos = 0;
			clearComparison();
		}
		if (selectedSlot == slotId) return false;
		int previous = selectedSlot;
		selectedSlot = slotId;
		awaitingRevealSlot = known.containsKey(slotId) ? -1 : slotId;
		awaitingRevealAtNanos = awaitingRevealSlot >= 0 ? nowNanos : 0;
		clearComparison();
		if (previous >= 0 && previous != slotId && !resolved.contains(previous)) {
			comparisonFirstSlot = previous;
			comparisonSecondSlot = slotId;
			// If both identities were already learned, the server may reveal and hide the pair in one
			// update (the instant-reward event). Their identities are enough to classify the pair; the
			// hide transition still remains the completion boundary below.
			comparisonRevealed = visibleSlots.contains(previous) || visibleSlots.contains(slotId)
				|| (known.containsKey(previous) && known.containsKey(slotId));
			evaluateComparison();
		}
		return true;
	}

	private void clearComparison() {
		comparisonFirstSlot = -1;
		comparisonSecondSlot = -1;
		comparisonMatch = false;
		comparisonMismatch = false;
		comparisonRevealed = false;
	}

	public OptionalInt suggestedMatch(int slotId) {
		String value = known.get(slotId);
		if (value == null || resolved.contains(slotId)) return OptionalInt.empty();
		for (Map.Entry<Integer, String> entry : known.entrySet()) {
			if (entry.getKey() != slotId && !resolved.contains(entry.getKey())
				&& value.equals(entry.getValue())) return OptionalInt.of(entry.getKey());
		}
		return OptionalInt.empty();
	}

	public View view() {
		Map<String, List<Integer>> pairs = new LinkedHashMap<>();
		for (Map.Entry<Integer, String> entry : known.entrySet()) {
			pairs.computeIfAbsent(entry.getValue(), ignored -> new ArrayList<>()).add(entry.getKey());
		}
		List<Card> cards = new ArrayList<>();
		for (int slot : slots) {
			String value = known.get(slot);
			CardState state;
			if (resolved.contains(slot)) state = CardState.RESOLVED;
			else if (selectedSlot == slot) state = CardState.SELECTED;
			else if (duplicateSlots.contains(slot)) state = CardState.MATCH;
			else if (value != null) state = CardState.KNOWN;
			else state = CardState.UNKNOWN;
			cards.add(new Card(slot, value, state));
		}
		Map<String, List<Integer>> immutablePairs = new LinkedHashMap<>();
		for (Map.Entry<String, List<Integer>> entry : pairs.entrySet()) {
			immutablePairs.put(entry.getKey(), List.copyOf(entry.getValue()));
		}
		return new View(cards, selectedSlot, awaitingRevealSlot, immutablePairs);
	}

	public enum CardState { UNKNOWN, KNOWN, SELECTED, MATCH, RESOLVED }

	public record Card(int slotId, String value, CardState state) {
	}

	public record View(List<Card> cards, int selectedSlot, int awaitingRevealSlot,
		Map<String, List<Integer>> pairs) {
		public View {
			cards = List.copyOf(cards == null ? List.of() : cards);
			pairs = Map.copyOf(pairs == null ? Map.of() : pairs);
		}

		public int knownCount() {
			return (int) cards.stream().filter(card -> card.value() != null && !card.value().isBlank()).count();
		}

		public int discoveredCount() {
			return knownCount();
		}

		public int resolvedCount() {
			return (int) cards.stream().filter(card -> card.state() == CardState.RESOLVED).count();
		}

		public int collectedCount() {
			return resolvedCount();
		}

		public int resolvedPairs() {
			return resolvedCount() / 2;
		}

		public int totalPairs() {
			return cards.size() / 2;
		}

		public boolean waitingForReveal() {
			return awaitingRevealSlot >= 0;
		}

		public static View empty() {
			return new View(List.of(), -1, -1, Map.of());
		}
	}
}
