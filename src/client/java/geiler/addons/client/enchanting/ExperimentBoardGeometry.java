package geiler.addons.client.enchanting;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The live board slots used by Hypixel's experiment containers.
 *
 * <p>The slot ids are not one universal centered rectangle. Chronomatron uses the contiguous
 * ranges 17..25 or 17..34, while Ultrasequencer reads the full 9..44 range and ignores the
 * decorative panes. Keeping the ordered slot list here makes capture, rendering, and click
 * filtering use the same mapping.</p>
 */
public record ExperimentBoardGeometry(int columns, int rows, List<Integer> slotIds) {
	private static final Map<ExperimentType, EnumMap<ExperimentTier, ExperimentBoardGeometry>> CACHE = buildCache();

	public ExperimentBoardGeometry {
		if (columns < 1 || rows < 1) throw new IllegalArgumentException("Invalid board dimensions");
		List<Integer> copy = new ArrayList<>(slotIds == null ? List.of() : slotIds);
		if (copy.isEmpty() || copy.size() > columns * rows
			|| copy.stream().anyMatch(slot -> slot == null || slot < 0)
			|| copy.stream().distinct().count() != copy.size()) {
			throw new IllegalArgumentException("Invalid experiment board slots");
		}
		slotIds = List.copyOf(copy);
	}

	public static ExperimentBoardGeometry forExperiment(ExperimentType type, ExperimentTier tier) {
		if (type == null) throw new IllegalArgumentException("type must not be null");
		ExperimentTier safeTier = tier == null ? ExperimentTier.UNKNOWN : tier;
		return CACHE.get(type).get(safeTier);
	}

	private static Map<ExperimentType, EnumMap<ExperimentTier, ExperimentBoardGeometry>> buildCache() {
		Map<ExperimentType, EnumMap<ExperimentTier, ExperimentBoardGeometry>> cache = new EnumMap<>(ExperimentType.class);
		for (ExperimentType type : ExperimentType.values()) {
			EnumMap<ExperimentTier, ExperimentBoardGeometry> byTier = new EnumMap<>(ExperimentTier.class);
			for (ExperimentTier tier : ExperimentTier.values()) byTier.put(tier, create(type, tier));
			cache.put(type, byTier);
		}
		return Map.copyOf(cache);
	}

	private static ExperimentBoardGeometry create(ExperimentType type, ExperimentTier safeTier) {
		return switch (type) {
			case CHRONOMATRON -> switch (safeTier) {
				case HIGH, GRAND, SUPREME -> range(9, 17);
				case TRANSCENDENT, METAPHYSICAL, UNKNOWN, BEGINNER -> range(18, 17);
			};
			// Skyblocker uses all 36 board slots and filters decorative panes by their item name.
			case ULTRASEQUENCER -> rectangle(9, 4, 0, 1);
			case SUPERPAIRS -> switch (safeTier) {
				case BEGINNER -> rectangle(7, 2, 1, 1);
				case HIGH, GRAND -> rectangle(5, 4, 2, 1);
				default -> rectangle(7, 4, 1, 1);
			};
		};
	}

	public boolean containsSlot(int slotId) {
		return slotIds.contains(slotId);
	}

	/** Returns the compact custom-GUI column for a live container slot. */
	public int column(int slotId) {
		int index = slotIds.indexOf(slotId);
		return index < 0 ? -1 : index % columns;
	}

	/** Returns the compact custom-GUI row for a live container slot. */
	public int row(int slotId) {
		int index = slotIds.indexOf(slotId);
		return index < 0 ? -1 : index / columns;
	}

	public int slotId(int column, int row) {
		if (column < 0 || column >= columns || row < 0 || row >= rows) return -1;
		int index = row * columns + column;
		return index >= slotIds.size() ? -1 : slotIds.get(index);
	}

	private static ExperimentBoardGeometry range(int size, int firstSlot) {
		List<Integer> slots = new ArrayList<>(size);
		for (int i = 0; i < size; i++) slots.add(firstSlot + i);
		return new ExperimentBoardGeometry(9, Math.max(1, (size + 8) / 9), slots);
	}

	private static ExperimentBoardGeometry rectangle(int columns, int rows, int firstColumn,
		int firstRow) {
		List<Integer> slots = new ArrayList<>(columns * rows);
		for (int row = 0; row < rows; row++) {
			for (int column = 0; column < columns; column++) {
				slots.add((firstRow + row) * 9 + firstColumn + column);
			}
		}
		return new ExperimentBoardGeometry(columns, rows, slots);
	}
}
