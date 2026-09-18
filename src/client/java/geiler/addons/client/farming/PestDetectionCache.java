package geiler.addons.client.farming;

import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Client-thread cache for ArmorStand head classification, including negative results. */
public final class PestDetectionCache {
	private final Map<UUID, Entry> entries = new HashMap<>();

	public Optional<PestKind> get(UUID entityId, ItemStack head, Supplier<Optional<PestKind>> detector) {
		Entry cached = entries.get(entityId);
		if (cached != null && ItemStack.matches(cached.head(), head)) {
			return Optional.ofNullable(cached.kind());
		}

		Optional<PestKind> detected = detector.get();
		entries.put(entityId, new Entry(head.copy(), detected.orElse(null)));
		return detected;
	}

	public void retainAll(Set<UUID> visibleEntities) {
		entries.keySet().retainAll(visibleEntities);
	}

	public void clear() {
		entries.clear();
	}

	private record Entry(ItemStack head, PestKind kind) {
	}
}
