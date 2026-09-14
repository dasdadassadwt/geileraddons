package geiler.addons.client.party;

import geiler.addons.client.dungeon.DungeonClass;

import java.util.UUID;

/** Immutable party-member data shared by the dungeon modules. */
public record PartyMember(String name, UUID uuid, DungeonClass dungeonClass) {
	public PartyMember withUuid(UUID value) {
		return new PartyMember(name, value, dungeonClass);
	}

	public PartyMember withClass(DungeonClass value) {
		return new PartyMember(name, uuid, value);
	}
}
