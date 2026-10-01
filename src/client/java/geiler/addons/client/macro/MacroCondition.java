package geiler.addons.client.macro;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Immutable condition tree used by If/Else and Wait-Until steps. */
public sealed interface MacroCondition permits MacroCondition.Always, MacroCondition.Screen,
	MacroCondition.Slot, MacroCondition.Item, MacroCondition.Chat, MacroCondition.World,
	MacroCondition.All, MacroCondition.Any, MacroCondition.Not, MacroCondition.Variable,
	MacroCondition.Hypixel {
	record Always(boolean expected) implements MacroCondition {
	}

	record Screen(String title, boolean mustBeOpen, boolean contains) implements MacroCondition {
	}

	record Slot(int slotId, boolean mustExist) implements MacroCondition {
	}

	enum ItemScope {
		CONTAINER("container"),
		PLAYER_INVENTORY("player"),
		CONTAINER_AND_PLAYER("all");

		private final String serializedName;

		ItemScope(String serializedName) {
			this.serializedName = serializedName;
		}

		public String serializedName() {
			return serializedName;
		}

		public boolean includesPlayerSlot(boolean playerSlot) {
			return switch (this) {
				case CONTAINER -> !playerSlot;
				case PLAYER_INVENTORY -> playerSlot;
				case CONTAINER_AND_PLAYER -> true;
			};
		}

		public static ItemScope fromSerialized(String value, ItemScope fallback) {
			if (value == null) return fallback;
			String normalized = value.toLowerCase(Locale.ROOT);
			for (ItemScope scope : values()) {
				if (scope.serializedName.equals(normalized)) return scope;
			}
			return fallback;
		}

		public static ItemScope fromLegacy(boolean includePlayerInventory) {
			return includePlayerInventory ? CONTAINER_AND_PLAYER : CONTAINER;
		}
	}

	record Item(String name, boolean mustExist, boolean contains, ItemScope scope) implements MacroCondition {
		public Item {
			if (scope == null) scope = ItemScope.CONTAINER;
		}

		/** Keeps source callers using the former boolean option compatible. */
		public Item(String name, boolean mustExist, boolean contains, boolean includePlayerInventory) {
			this(name, mustExist, contains, ItemScope.fromLegacy(includePlayerInventory));
		}

		public boolean matches(boolean found) {
			return found == mustExist;
		}
	}

	record Chat(String text, boolean contains) implements MacroCondition {
	}

	record World(boolean mustBeInWorld) implements MacroCondition {
	}

	record Variable(String name, Operator operator, MacroValue value, String globalVariableId) implements MacroCondition {
		public enum Operator { EQUALS, NOT_EQUALS, GREATER_THAN, GREATER_OR_EQUAL, LESS_THAN, LESS_OR_EQUAL }
		public Variable(String name, Operator operator, MacroValue value) {
			this(name, operator, value, null);
		}
		public Variable {
			name = name == null ? "value" : name.strip();
			if (operator == null) operator = Operator.EQUALS;
			if (value == null) value = MacroValue.literal(MacroValue.Type.TEXT, "");
			globalVariableId = globalVariableId == null || globalVariableId.isBlank()
				? null : globalVariableId.substring(0, Math.min(64, globalVariableId.length()));
		}
		public boolean comparesGlobal() { return globalVariableId != null; }
	}

	record Hypixel(Field field, Operator operator, String expected, String argument, int plotId)
		implements MacroCondition {
		public enum Field {
			ISLAND, DUNGEON_FLOOR, PARTY_MEMBER, PARTY_HAS_MEMBER, PARTY_SIZE, PARTY_LEADER,
			GARDEN_PEST_STATUS, GARDEN_PEST_COUNT
		}
		public enum Operator { EQUALS, NOT_EQUALS, CONTAINS, GREATER_THAN, GREATER_OR_EQUAL, LESS_THAN, LESS_OR_EQUAL }
		public Hypixel(Field field, Operator operator, String expected) {
			this(field, operator, expected, "", -1);
		}
		public Hypixel {
			if (field == null) field = Field.ISLAND;
			if (operator == null) operator = Operator.EQUALS;
			expected = expected == null ? "" : expected.substring(0, Math.min(128, expected.length()));
			argument = argument == null ? "" : argument.substring(0, Math.min(32, argument.length()));
			if (plotId < -1 || plotId > 24) plotId = -1;
		}
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
