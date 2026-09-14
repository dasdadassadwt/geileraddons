package geiler.addons.client.party;

import java.util.List;

/** A read-only view of the transient party state. */
public record PartySnapshot(List<PartyMember> members, String leaderName, boolean inParty, long generation) {
	public PartySnapshot {
		members = List.copyOf(members);
	}

	public boolean isLeader(String localName) {
		return inParty && leaderName != null && localName != null && leaderName.equalsIgnoreCase(localName);
	}
}
