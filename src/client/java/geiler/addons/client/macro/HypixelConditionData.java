package geiler.addons.client.macro;

import geiler.addons.client.dungeon.DungeonFloor;
import geiler.addons.client.dungeon.DungeonContextTracker;
import geiler.addons.client.farming.GardenPlotGrid;
import geiler.addons.client.farming.GardenPlotState;
import geiler.addons.client.location.HypixelModApi;
import geiler.addons.client.location.Island;
import geiler.addons.client.module.impl.GardenPlotBordersModule;
import geiler.addons.client.party.PartyListBackend;
import geiler.addons.client.party.PartySnapshot;
import net.minecraft.client.Minecraft;

/** Typed, client-observed values for macro conditions; missing and stale facts stay unavailable. */
public final class HypixelConditionData {
	private HypixelConditionData() { }

	public static Value read(MacroCondition.Hypixel condition, Minecraft minecraft) {
		if (condition == null || minecraft == null || !minecraft.isSameThread()) return Value.unavailable();
		return switch (condition.field()) {
			case ISLAND -> island();
			case DUNGEON_FLOOR -> dungeonFloor();
			case PARTY_MEMBER -> partyMember();
			case PARTY_HAS_MEMBER -> partyHasMember(condition.argument());
			case PARTY_SIZE -> partySize();
			case PARTY_LEADER -> partyLeader();
			case GARDEN_PEST_STATUS -> gardenStatus(condition.plotId(), minecraft);
			case GARDEN_PEST_COUNT -> gardenCount(condition.plotId(), minecraft);
		};
	}

	private static Value island() {
		if (!HypixelModApi.hasLocation()) return Value.unavailable();
		Island island = HypixelModApi.currentIsland();
		return island == Island.NONE ? Value.unavailable() : Value.available(island.name());
	}

	private static Value dungeonFloor() {
		if (!HypixelModApi.hasLocation()) return Value.unavailable();
		if (HypixelModApi.currentIsland() != Island.CATACOMBS) return Value.available("NONE");
		DungeonFloor floor = DungeonContextTracker.currentFloor();
		return floor == null ? Value.unavailable() : Value.available(floor.displayName());
	}

	private static Value partyMember() {
		if (!PartyListBackend.hasFreshSnapshot()) return Value.unavailable();
		return Value.available(PartyListBackend.snapshot().inParty());
	}

	private static Value partyHasMember(String wanted) {
		if (!PartyListBackend.hasFreshSnapshot() || wanted == null || wanted.isBlank()) return Value.unavailable();
		PartySnapshot party = PartyListBackend.snapshot();
		return Value.available(party.members().stream().anyMatch(member -> member.name().equalsIgnoreCase(wanted.trim())));
	}

	private static Value partySize() {
		if (!PartyListBackend.hasFreshSnapshot()) return Value.unavailable();
		return Value.available(PartyListBackend.snapshot().members().size());
	}

	private static Value partyLeader() {
		if (!PartyListBackend.hasFreshSnapshot()) return Value.unavailable();
		PartySnapshot party = PartyListBackend.snapshot();
		if (!party.inParty()) return Value.available("NONE");
		return party.leaderName() == null ? Value.unavailable() : Value.available(party.leaderName());
	}

	private static Value gardenStatus(int requestedPlotId, Minecraft minecraft) {
		GardenPlotState.PlotStatus status = gardenPlot(requestedPlotId, minecraft);
		return status == null ? Value.unavailable() : Value.available(status.status().name());
	}

	private static Value gardenCount(int requestedPlotId, Minecraft minecraft) {
		GardenPlotState.PlotStatus status = gardenPlot(requestedPlotId, minecraft);
		return status == null || status.pestCount() == null
			? Value.unavailable() : Value.available(status.pestCount());
	}

	private static GardenPlotState.PlotStatus gardenPlot(int requestedPlotId, Minecraft minecraft) {
		if (!HypixelModApi.onGarden() || minecraft.player == null) return null;
		int plotId = requestedPlotId;
		if (plotId < 0) {
			plotId = GardenPlotGrid.plotAt(minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ())
				.map(GardenPlotGrid.Plot::id).orElse(-1);
		}
		return GardenPlotBordersModule.freshPlotStatus(plotId).orElse(null);
	}

	public record Value(boolean available, Object value) {
		private static Value available(Object value) { return new Value(value != null, value); }
		private static Value unavailable() { return new Value(false, null); }
	}
}
