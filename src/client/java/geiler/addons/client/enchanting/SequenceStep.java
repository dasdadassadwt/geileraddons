package geiler.addons.client.enchanting;

import java.util.ArrayList;
import java.util.List;

/** One remembered value and the playable slots that currently contain it. */
public record SequenceStep(int index, String value, List<Integer> slotIds) {
	public SequenceStep {
		if (index < 0) throw new IllegalArgumentException("index must be non-negative");
		value = value == null ? "" : value.trim();
		List<Integer> copy = new ArrayList<>(slotIds == null ? List.of() : slotIds);
		copy.removeIf(slot -> slot == null || slot < 0);
		copy.sort(Integer::compareTo);
		slotIds = List.copyOf(copy.stream().distinct().toList());
	}

	public boolean containsSlot(int slotId) {
		return slotIds.contains(slotId);
	}
}
