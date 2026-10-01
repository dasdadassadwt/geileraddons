package geiler.addons.client.dungeon;

import java.util.function.Function;

/** Retains an eligible dungeon-door trace briefly after the player leaves its connected room. */
public final class DungeonDoorTraceGrace {
	public static final long GRACE_TICKS = 10L * 20L;
	private Object world;
	private DungeonFloor floor;
	private DungeonDoorTracker.Door target;
	private long lastEligibleTick = Long.MIN_VALUE;

	public DungeonDoorTracker.Door update(boolean inDungeon, Object currentWorld, DungeonFloor currentFloor,
		DungeonDoorTracker.Door selected, boolean closed, boolean eligible, long gameTick) {
		return update(inDungeon, currentWorld, currentFloor, selected,
			closed ? BloodDoorTraceRules.DoorPresence.CLOSED : BloodDoorTraceRules.DoorPresence.OPEN,
			eligible, ignored -> BloodDoorTraceRules.DoorPresence.UNKNOWN, gameTick);
	}

	public DungeonDoorTracker.Door update(boolean inDungeon, Object currentWorld, DungeonFloor currentFloor,
		DungeonDoorTracker.Door selected, BloodDoorTraceRules.DoorPresence selectedPresence, boolean eligible,
		Function<DungeonDoorTracker.Door, BloodDoorTraceRules.DoorPresence> presenceOfRetainedTarget, long gameTick) {
		if (!inDungeon || currentWorld == null) {
			reset();
			return null;
		}
		if (world != currentWorld || floor != currentFloor) {
			reset();
			world = currentWorld;
			floor = currentFloor;
		}
		if (selected != null && selectedPresence == BloodDoorTraceRules.DoorPresence.CLOSED && eligible) {
			if (!selected.equals(target)) clearTarget();
			target = selected;
			lastEligibleTick = gameTick;
			return target;
		}
		if (target == null) return null;
		if (selected != null && selected.equals(target)
			&& selectedPresence == BloodDoorTraceRules.DoorPresence.OPEN) {
			clearTarget();
			return null;
		}
		BloodDoorTraceRules.DoorPresence retainedPresence = presenceOfRetainedTarget == null
			? BloodDoorTraceRules.DoorPresence.UNKNOWN : presenceOfRetainedTarget.apply(target);
		if (retainedPresence == BloodDoorTraceRules.DoorPresence.OPEN
			|| gameTick < lastEligibleTick || gameTick - lastEligibleTick >= GRACE_TICKS) {
			clearTarget();
			return null;
		}
		return target;
	}

	public void reset() {
		world = null;
		floor = null;
		clearTarget();
	}

	private void clearTarget() {
		target = null;
		lastEligibleTick = Long.MIN_VALUE;
	}
}
