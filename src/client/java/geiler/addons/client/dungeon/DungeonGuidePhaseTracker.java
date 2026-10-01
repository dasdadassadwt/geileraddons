package geiler.addons.client.dungeon;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Client-session dungeon stage with an explicit manual override and visible evidence. */
public final class DungeonGuidePhaseTracker {
	private DungeonFloor floor;
	private String phase = "ENTRY";
	private String evidence = "Waiting for dungeon events";
	private boolean manual;
	private long enteredNanos;

	public void setFloor(DungeonFloor floor, long nowNanos) {
		if (this.floor == floor) return;
		this.floor = floor;
		phase = "ENTRY";
		evidence = floor == null ? "Outside dungeon" : "Dungeon floor detected";
		manual = false;
		enteredNanos = nowNanos;
	}

	public boolean choose(String phase, long nowNanos) {
		if (floor == null || phase == null || !orderedSegments().contains(phase)) return false;
		this.phase = phase;
		this.manual = true;
		this.evidence = "Manual choice";
		this.enteredNanos = nowNanos;
		return true;
	}

	public void resumeAuto() {
		manual = false;
		evidence = "Automatic detection resumed";
	}

	public boolean observe(DungeonGuideSegments.Event event,
		DungeonGuideStore.TransitionTiming timing, long nowNanos) {
		if (floor == null || manual || event == null || event.segmentId() == null) return false;
		List<String> order = orderedSegments();
		int at = order.indexOf(event.segmentId());
		if (at < 0) return false;
		String target = timing == DungeonGuideStore.TransitionTiming.NEXT_OBJECTIVE
			? order.get(Math.min(order.size() - 1, at + 1)) : event.segmentId();
		if (order.indexOf(target) <= order.indexOf(phase)) return false;
		phase = target;
		evidence = event.source();
		enteredNanos = nowNanos;
		return true;
	}

	public boolean observeCustomChat(String message, long nowNanos) {
		if (floor == null || manual || message == null) return false;
		for (DungeonGuideCustomSegment segment : DungeonGuideStore.customSegments(floor)) {
			if (segment.trigger != DungeonGuideCustomSegment.Trigger.CHAT_EVENT
				|| segment.eventText.isBlank() || !message.trim().equalsIgnoreCase(segment.eventText)) continue;
			return enterCustom(segment, "Configured chat event", nowNanos);
		}
		return false;
	}

	public boolean tickCustom(double x, double y, double z, long nowNanos) {
		if (floor == null || manual) return false;
		for (DungeonGuideCustomSegment segment : DungeonGuideStore.customSegments(floor)) {
			if (segment.trigger == DungeonGuideCustomSegment.Trigger.ENTER_RADIUS) {
				double dx = x - (segment.x + 0.5), dy = y - (segment.y + 0.5), dz = z - (segment.z + 0.5);
				if (dx * dx + dy * dy + dz * dz <= segment.radius * segment.radius
					&& enterCustom(segment, "Configured location", nowNanos)) return true;
			} else if (segment.trigger == DungeonGuideCustomSegment.Trigger.AFTER_SECONDS && segment.seconds > 0
				&& nowNanos - enteredNanos >= (long) (segment.seconds * 1_000_000_000L)
				&& enterCustom(segment, "Configured timer", nowNanos)) return true;
		}
		return false;
	}

	private boolean enterCustom(DungeonGuideCustomSegment segment, String source, long nowNanos) {
		List<String> order = orderedSegments();
		if (order.indexOf(segment.id) != order.indexOf(phase) + 1) return false;
		phase = segment.id;
		evidence = source;
		enteredNanos = nowNanos;
		return true;
	}

	public List<String> orderedSegments() {
		if (floor == null) return List.of();
		List<String> result = new ArrayList<>();
		List<DungeonGuideCustomSegment> custom = new ArrayList<>(DungeonGuideStore.customSegments(floor));
		custom.sort(Comparator.comparingInt(segment -> segment.afterBuiltInIndex));
		List<DungeonGuideSegments.Segment> builtIn = DungeonGuideSegments.builtIn(floor);
		for (int i = 0; i < builtIn.size(); i++) {
			result.add(builtIn.get(i).id());
			for (DungeonGuideCustomSegment segment : custom)
				if (segment.afterBuiltInIndex == i) result.add(segment.id);
		}
		return List.copyOf(result);
	}

	public String phase() { return phase; }
	public String evidence() { return evidence; }
	public boolean manual() { return manual; }
	public DungeonFloor floor() { return floor; }
}
