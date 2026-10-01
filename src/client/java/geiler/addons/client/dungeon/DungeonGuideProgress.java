package geiler.addons.client.dungeon;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pure ordered-group state machine used by Dungeon Guide. */
public final class DungeonGuideProgress {
	private DungeonFloor floor;
	private String phase = "ENTRY";
	private String routeName = "Default";
	private int index;
	private long stepStartedNanos;
	private RouteSnapshot cachedSnapshot;
	private List<DungeonGuideNode> cachedGroup = List.of();
	private boolean routeDataCached;
	private long cachedGuideRevision = Long.MIN_VALUE;
	private DungeonFloor cachedFloor;
	private String cachedPhase;
	private String cachedRouteName;
	private List<RouteStep> cachedSteps = List.of();

	public void setFloor(DungeonFloor floor, long nowNanos) {
		if (this.floor == floor) return;
		this.floor = floor;
		phase = "ENTRY";
		index = 0;
		stepStartedNanos = nowNanos;
	}

	public boolean setPhase(String phase, long nowNanos) {
		if (phase == null || !phase.matches("[A-Z][A-Z0-9_]{0,31}")) return false;
		this.phase = phase;
		return true;
	}

	public boolean setRoute(String routeName, long nowNanos) {
		if (routeName == null || routeName.isBlank() || this.routeName.equals(routeName)) return false;
		this.routeName = routeName;
		index = 0;
		stepStartedNanos = nowNanos;
		return true;
	}

	public String phase() { return phase; }
	public DungeonFloor floor() { return floor; }
	public int index() { return index; }
	public long elapsedNanos(long nowNanos) { return Math.max(0L, nowNanos - stepStartedNanos); }

	public List<DungeonGuideNode> group(List<DungeonGuideNode> nodes) {
		if (floor == null || nodes == null) return List.of();
		return nodes.stream().filter(node -> floor.displayName().equals(node.floor)
			&& routeName.equals(node.routeName))
			.sorted(Comparator.comparingInt((DungeonGuideNode node) -> DungeonGuideStore.phaseOrder(node.floor, node.phase))
				.thenComparing(node -> node.phase)
				.thenComparingInt(node -> node.order)).toList();
	}

	/** Reuses the ordered route and immutable step values until an input to rendering/progress changes. */
	public RouteSnapshot snapshot(List<DungeonGuideNode> nodes, long guideRevision) {
		return snapshot(nodes, guideRevision, System.nanoTime());
	}

	public RouteSnapshot snapshot(List<DungeonGuideNode> nodes, long guideRevision, long nowNanos) {
		boolean guideChanged = routeDataCached && cachedGuideRevision != guideRevision;
		boolean rebuildRouteData = !routeDataCached || cachedGuideRevision != guideRevision
			|| cachedFloor != floor || !cachedPhase.equals(phase) || !cachedRouteName.equals(routeName);
		if (!rebuildRouteData && cachedSnapshot != null && cachedSnapshot.stepIndex() == index) {
			return cachedSnapshot;
		}

		String rebuildReason = rebuildReason(guideRevision, rebuildRouteData);
		long startNanos = System.nanoTime();
		if (rebuildRouteData) {
			List<DungeonGuideNode> nextGroup = group(nodes);
			List<RouteStep> nextSteps = nextGroup.stream().map(RouteStep::from).toList();
			if (guideChanged) {
				String activeId = activeStepId();
				int oldActiveIndex = uniqueIndex(cachedSteps, activeId);
				int nextActiveIndex = uniqueIndex(nextSteps, activeId);
				if (oldActiveIndex == index && nextActiveIndex >= 0) {
					// Keep the same waypoint active across removal, insertion, or reordering elsewhere.
					index = nextActiveIndex;
				} else {
					// The active waypoint was removed, ambiguous, or the route had no active waypoint.
					// Restart from the beginning and restart its timer instead of silently activating
					// whatever shifted into the old numeric slot (or treating an empty route as complete).
					index = 0;
					stepStartedNanos = nowNanos;
					rebuildReason = "guide-data-reset";
				}
			}
			cachedGroup = nextGroup;
			cachedSteps = nextSteps;
			cachedGuideRevision = guideRevision;
			cachedFloor = floor;
			cachedPhase = phase;
			cachedRouteName = routeName;
			routeDataCached = true;
		}
		long buildNanos = Math.max(0L, System.nanoTime() - startNanos);
		cachedSnapshot = new RouteSnapshot(floor, phase, routeName, index, guideRevision,
			rebuildReason, buildNanos, cachedSteps);
		return cachedSnapshot;
	}

	private String activeStepId() {
		return index >= 0 && index < cachedSteps.size() ? cachedSteps.get(index).id() : null;
	}

	private static int uniqueIndex(List<RouteStep> steps, String id) {
		if (id == null || id.isBlank()) return -1;
		int found = -1;
		for (int i = 0; i < steps.size(); i++) {
			if (!id.equals(steps.get(i).id())) continue;
			if (found >= 0) return -1;
			found = i;
		}
		return found;
	}

	/** Ordered source nodes are retained for the existing non-render API; renderers use the deep immutable steps. */
	public List<DungeonGuideNode> cachedGroup() { return cachedGroup; }

	private String rebuildReason(long guideRevision, boolean rebuildRouteData) {
		if (!routeDataCached) return "initial";
		if (cachedGuideRevision != guideRevision) return "guide-data";
		if (cachedFloor != floor) return "floor";
		if (!cachedRouteName.equals(routeName)) return "route";
		if (!cachedPhase.equals(phase)) return "phase";
		if (!rebuildRouteData && cachedSnapshot != null && cachedSnapshot.stepIndex() != index) return "step";
		return "refresh";
	}

	public int remaining(List<DungeonGuideNode> nodes) {
		return Math.max(0, group(nodes).size() - index);
	}

	public void next(List<DungeonGuideNode> nodes, long nowNanos) { advance(nodes, nowNanos); }

	/** Advances once and returns the step whose configured trigger has just fired. */
	public DungeonGuideNode advance(List<DungeonGuideNode> nodes, long nowNanos) {
		List<DungeonGuideNode> group = group(nodes);
		if (index < group.size()) {
			DungeonGuideNode fired = group.get(index);
			index++;
			stepStartedNanos = nowNanos;
			return fired;
		}
		return null;
	}

	/** Advances the published route without filtering or sorting the source node list again. */
	public RouteStep advance(RouteSnapshot snapshot, long nowNanos) {
		if (snapshot == null || snapshot != cachedSnapshot || snapshot.stepIndex() != index) return null;
		RouteStep fired = snapshot.current();
		if (fired == null) return null;
		index++;
		stepStartedNanos = nowNanos;
		return fired;
	}

	/** The user's Next Step bind intentionally overrides any automatic trigger. */
	public DungeonGuideNode advanceManually(List<DungeonGuideNode> nodes, long nowNanos) {
		return advance(nodes, nowNanos);
	}
	public RouteStep advanceManually(RouteSnapshot snapshot, long nowNanos) {
		return advance(snapshot, nowNanos);
	}
	public void previous(long nowNanos) {
		int previous = Math.max(0, index - 1);
		if (previous != index) { index = previous; stepStartedNanos = nowNanos; }
	}

	/**
	 * A phase transition requires exactly one configured exact phrase for the current floor.
	 * Duplicate phrases are ambiguous and do not move the route.
	 */
	public boolean observeConfiguredEvent(String message, List<DungeonGuideNode> nodes, long nowNanos) {
		if (!hasUniqueConfiguredEventMatch(message, nodes)) return false;
		observeConfiguredTrigger(message, nodes, nowNanos);
		return true;
	}

	public DungeonGuideNode observeConfiguredTrigger(String message, List<DungeonGuideNode> nodes, long nowNanos) {
		if (message == null || message.isBlank()) return null;
		List<DungeonGuideNode> ordered = group(nodes);
		if (index >= ordered.size()) return null;
		DungeonGuideNode active = ordered.get(index);
		return triggerReady(active, elapsedNanos(nowNanos), Double.POSITIVE_INFINITY, message, phase)
			? advance(nodes, nowNanos) : null;
	}

	private boolean hasUniqueConfiguredEventMatch(String message, List<DungeonGuideNode> nodes) {
		if (floor == null || message == null || nodes == null) return false;
		String candidate = message.trim().toLowerCase(Locale.ROOT);
		DungeonGuideNode match = null;
		for (DungeonGuideNode node : nodes) {
			if (!floor.displayName().equals(node.floor) || node.eventText == null || node.eventText.isBlank()
				|| !candidate.equals(node.eventText.trim().toLowerCase(Locale.ROOT))) continue;
			if (match != null && !match.id.equals(node.id)) return false;
			match = node;
		}
		return match != null;
	}

	public static boolean triggerReady(DungeonGuideNode node, long elapsedNanos, double distanceSquared) {
		return triggerReady(node, elapsedNanos, distanceSquared, "", node == null ? "" : node.phase);
	}

	public static boolean triggerReady(DungeonGuideNode node, long elapsedNanos, double distanceSquared,
		String exactChatMessage, String detectedPhase) {
		return node != null && triggerReady(node.conditions, node.conditionRule, node.phase, node.eventText,
			node.triggerRadius, node.triggerSeconds, elapsedNanos, distanceSquared, exactChatMessage, detectedPhase);
	}

	public static boolean triggerReady(RouteStep node, long elapsedNanos, double distanceSquared,
		String exactChatMessage, String detectedPhase) {
		return node != null && triggerReady(node.conditions(), node.conditionRule(), node.phase(), node.eventText(),
			node.triggerRadius(), node.triggerSeconds(), elapsedNanos, distanceSquared, exactChatMessage, detectedPhase);
	}

	private static boolean triggerReady(Set<DungeonGuideNode.Condition> conditions,
		DungeonGuideNode.ConditionRule conditionRule, String phase, String eventText,
		float triggerRadius, float triggerSeconds, long elapsedNanos, double distanceSquared,
		String exactChatMessage, String detectedPhase) {
		if (conditions == null || conditions.isEmpty()) return false;
		String chat = exactChatMessage == null ? "" : exactChatMessage.trim();
		boolean any = false;
		boolean all = true;
		String configuredEvent = eventText == null ? "" : eventText;
		for (DungeonGuideNode.Condition condition : conditions) {
			boolean passed = switch (condition) {
				case MANUAL -> false;
				case ENTER_RADIUS -> distanceSquared <= triggerRadius * triggerRadius;
				case AFTER_SECONDS -> elapsedNanos >= (long) (triggerSeconds * 1_000_000_000L);
				case CHAT_EVENT -> !configuredEvent.isBlank() && chat.equalsIgnoreCase(configuredEvent.trim());
				case DETECTED_PHASE -> phase.equals(detectedPhase);
			};
			any |= passed;
			all &= passed;
		}
		return conditionRule == DungeonGuideNode.ConditionRule.ANY ? any : all;
	}

	public static final class RouteSnapshot {
		private final DungeonFloor floor;
		private final String phase;
		private final String routeName;
		private final int stepIndex;
		private final long guideRevision;
		private final String rebuildReason;
		private final long buildNanos;
		private final List<RouteStep> steps;

		private RouteSnapshot(DungeonFloor floor, String phase, String routeName, int stepIndex,
			long guideRevision, String rebuildReason, long buildNanos, List<RouteStep> steps) {
			this.floor = floor;
			this.phase = phase;
			this.routeName = routeName;
			this.stepIndex = stepIndex;
			this.guideRevision = guideRevision;
			this.rebuildReason = rebuildReason;
			this.buildNanos = buildNanos;
			this.steps = steps;
		}
		public DungeonFloor floor() { return floor; }
		public String phase() { return phase; }
		public String routeName() { return routeName; }
		public int stepIndex() { return stepIndex; }
		public long guideRevision() { return guideRevision; }
		public String rebuildReason() { return rebuildReason; }
		public long buildNanos() { return buildNanos; }
		public List<RouteStep> steps() { return steps; }
		public RouteStep current() { return stepIndex < 0 || stepIndex >= steps.size() ? null : steps.get(stepIndex); }
		public int nodeCount() { return steps.size(); }
		public int remaining() { return Math.max(0, steps.size() - stepIndex); }
		public boolean complete() { return !steps.isEmpty() && stepIndex >= steps.size(); }
	}

	public record RouteStep(String id, String phase, String label, int x, int y, int z,
		int visibilityDistance, float size, float rotation, int color, int fillColor, int labelColor,
		float labelScale, DungeonGuideNode.Shape shape, int ringCount, float ringHeight, float ringRadius,
		float ringSpeed, float ringWidth, boolean ringFill, Set<DungeonGuideNode.Condition> conditions,
		DungeonGuideNode.ConditionRule conditionRule, float triggerRadius, float triggerSeconds,
		String eventText, int macroId) {
		public RouteStep {
			conditions = conditions == null ? Set.of() : Set.copyOf(conditions);
			eventText = eventText == null ? "" : eventText;
		}
		private static RouteStep from(DungeonGuideNode node) {
			return new RouteStep(node.id, node.phase, node.label, node.x, node.y, node.z,
				node.visibilityDistance, node.size, node.rotation, node.color, node.fillColor,
				node.labelColor, node.labelScale, node.shape, node.ringCountValue(), node.ringHeightValue(),
				node.ringRadiusValue(), node.ringSpeedValue(), node.ringWidthValue(), node.ringFillEnabled(),
				node.conditions, node.conditionRule,
				node.triggerRadius, node.triggerSeconds, node.eventText, node.macroId);
		}
	}
}
