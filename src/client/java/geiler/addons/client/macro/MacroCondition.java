package geiler.addons.client.macro;

import java.util.ArrayList;
import java.util.List;

/** Immutable condition tree used by If/Else and Wait-Until steps. */
public sealed interface MacroCondition permits MacroCondition.Always, MacroCondition.Screen,
	MacroCondition.Slot, MacroCondition.Item, MacroCondition.Chat, MacroCondition.World,
	MacroCondition.All, MacroCondition.Any, MacroCondition.Not {
	record Always(boolean expected) implements MacroCondition {
	}

	record Screen(String title, boolean mustBeOpen, boolean contains) implements MacroCondition {
	}

	record Slot(int slotId, boolean mustExist) implements MacroCondition {
	}

	record Item(String name, boolean mustExist, boolean contains, boolean includePlayerInventory) implements MacroCondition {
	}

	record Chat(String text, boolean contains) implements MacroCondition {
	}

	record World(boolean mustBeInWorld) implements MacroCondition {
	}

	record All(List<MacroCondition> children) implements MacroCondition {
		public All {
			children = List.copyOf(children == null ? List.of() : new ArrayList<>(children));
		}
	}

	record Any(List<MacroCondition> children) implements MacroCondition {
		public Any {
			children = List.copyOf(children == null ? List.of() : new ArrayList<>(children));
		}
	}

	record Not(MacroCondition child) implements MacroCondition {
	}
}
