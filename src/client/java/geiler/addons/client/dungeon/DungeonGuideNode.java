package geiler.addons.client.dungeon;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/** One player-authored step in a floor and phase-specific guide. */
public final class DungeonGuideNode {
	public enum Shape { BOX, BEACON, RING, TEXT_ONLY }
	public enum Trigger { MANUAL, ENTER_RADIUS, AFTER_SECONDS, CHAT_EVENT }
	public enum Condition { MANUAL, ENTER_RADIUS, AFTER_SECONDS, CHAT_EVENT, DETECTED_PHASE }
	public enum ConditionRule { ALL, ANY }

	public static final int DEFAULT_RING_COUNT = 3;
	public static final float DEFAULT_RING_HEIGHT = 2.5f;
	public static final float DEFAULT_RING_RADIUS = 1.2f;
	public static final float DEFAULT_RING_SPEED = 0.35f;
	public static final float DEFAULT_RING_WIDTH = 1.5f;

	public String id = UUID.randomUUID().toString();
	public String floor = "F1";
	public String phase = "ENTRY";
	public String routeName = "Default";
	public int order;
	public String label = "Guide step";
	public int x;
	public int y;
	public int z;
	public float size = 1.0f;
	/** Null means an older route file has no per-node ring appearance yet. */
	public Integer ringCount;
	public Float ringHeight;
	public Float ringRadius;
	public Float ringSpeed;
	public Float ringWidth;
	public Boolean ringFill;
	/** Sentinel lets Gson migration preserve the former size-as-radius behavior for old files. */
	public float triggerRadius = -1.0f;
	public float rotation;
	public int color = 0xFFFFD24A;
	public int fillColor = 0x33FFD24A;
	public int labelColor = 0xFFFFD24A;
	public float labelScale = 1.0f;
	/** Zero means to use the module-wide render distance. */
	public int visibilityDistance;
	public Shape shape = Shape.BOX;
	public Trigger trigger = Trigger.MANUAL;
	/** Null is reserved for pre-condition-format files so sanitize can migrate their legacy trigger. */
	public Set<Condition> conditions;
	public ConditionRule conditionRule = ConditionRule.ALL;
	public float triggerSeconds = 0;
	public String eventText = "";
	public int macroId = -1;

	public DungeonGuideNode() { }

	public DungeonGuideNode(DungeonFloor floor, String phase, int order, int x, int y, int z) {
		this.floor = floor == null ? "F1" : floor.displayName();
		this.phase = clean(phase, 32);
		this.order = Math.max(0, order);
		this.triggerRadius = 2.0f;
		this.x = x;
		this.y = y;
		this.z = z;
		this.shape = Shape.BOX;
		this.conditions = EnumSet.of(Condition.MANUAL);
	}

	public void sanitize() {
		id = clean(id, 64);
		if (id.isBlank()) id = UUID.randomUUID().toString();
		floor = DungeonFloor.parse(floor) == null ? "F1" : DungeonFloor.parse(floor).displayName();
		phase = clean(phase, 32).toUpperCase(java.util.Locale.ROOT);
		if (phase.isBlank()) phase = "ENTRY";
		routeName = clean(routeName, 48);
		if (routeName.isBlank()) routeName = "Default";
		label = clean(label, 64);
		if (label.isBlank()) label = "Guide step";
		size = Float.isFinite(size) ? Math.max(0.25f, Math.min(16.0f, size)) : 1.0f;
		if (ringCount != null) ringCount = Math.max(1, Math.min(8, ringCount));
		ringHeight = clampOptional(ringHeight, 0.25f, 8.0f);
		ringRadius = clampOptional(ringRadius, 0.25f, 8.0f);
		ringSpeed = clampOptional(ringSpeed, 0.05f, 2.0f);
		ringWidth = clampOptional(ringWidth, 0.5f, 5.0f);
		triggerRadius = Float.isFinite(triggerRadius) && triggerRadius > 0
			? Math.max(0.5f, Math.min(64.0f, triggerRadius)) : Math.max(0.5f, Math.min(64.0f, size));
		rotation = Float.isFinite(rotation) ? rotation % 360.0f : 0;
		triggerSeconds = Float.isFinite(triggerSeconds) ? Math.max(0.0f, Math.min(3600.0f, triggerSeconds)) : 0;
		labelScale = Float.isFinite(labelScale) ? Math.max(0.5f, Math.min(3.0f, labelScale)) : 1.0f;
		visibilityDistance = Math.max(0, Math.min(256, visibilityDistance));
		eventText = clean(eventText, 96);
		if (shape == null) shape = Shape.BEACON;
		if (trigger == null) trigger = Trigger.MANUAL;
		if (conditions == null || conditions.equals(EnumSet.of(Condition.MANUAL)) && trigger != Trigger.MANUAL) {
			conditions = EnumSet.of(switch (trigger) {
				case MANUAL -> Condition.MANUAL;
				case ENTER_RADIUS -> Condition.ENTER_RADIUS;
				case AFTER_SECONDS -> Condition.AFTER_SECONDS;
				case CHAT_EVENT -> Condition.CHAT_EVENT;
			});
		}
		conditions.remove(null);
		if (conditions.isEmpty()) conditions.add(Condition.MANUAL);
		if (conditionRule == null) conditionRule = ConditionRule.ALL;
		updateLegacyTrigger();
		macroId = Math.max(-1, macroId);
		order = Math.max(0, order);
	}

	public boolean hasCondition(Condition condition) {
		return conditions != null && conditions.contains(condition);
	}

	public int ringCountValue() { return ringCount == null ? DEFAULT_RING_COUNT : ringCount; }
	public float ringHeightValue() { return ringHeight == null ? DEFAULT_RING_HEIGHT : ringHeight; }
	public float ringRadiusValue() { return ringRadius == null ? DEFAULT_RING_RADIUS : ringRadius; }
	public float ringSpeedValue() { return ringSpeed == null ? DEFAULT_RING_SPEED : ringSpeed; }
	public float ringWidthValue() { return ringWidth == null ? DEFAULT_RING_WIDTH : ringWidth; }
	public boolean ringFillEnabled() { return Boolean.TRUE.equals(ringFill); }

	/** Give a newly selected ring the current defaults without changing its siblings. */
	public void useDefaultRingAppearanceIfUnset() {
		if (ringCount == null) ringCount = DEFAULT_RING_COUNT;
		if (ringHeight == null) ringHeight = DEFAULT_RING_HEIGHT;
		if (ringRadius == null) ringRadius = DEFAULT_RING_RADIUS;
		if (ringSpeed == null) ringSpeed = DEFAULT_RING_SPEED;
		if (ringWidth == null) ringWidth = DEFAULT_RING_WIDTH;
		if (ringFill == null) ringFill = false;
	}

	/** Fills only absent legacy fields; explicit per-node values always win. */
	public boolean migrateMissingRingAppearance(int legacyCount, float legacyHeight, float legacyRadius,
		float legacySpeed, float legacyWidth) {
		if (shape != Shape.RING) return false;
		boolean changed = false;
		if (ringCount == null) { ringCount = Math.max(1, Math.min(8, legacyCount)); changed = true; }
		if (ringHeight == null) { ringHeight = clampFinite(legacyHeight, 0.25f, 8.0f, DEFAULT_RING_HEIGHT); changed = true; }
		if (ringRadius == null) { ringRadius = clampFinite(legacyRadius, 0.25f, 8.0f, DEFAULT_RING_RADIUS); changed = true; }
		if (ringSpeed == null) { ringSpeed = clampFinite(legacySpeed, 0.05f, 2.0f, DEFAULT_RING_SPEED); changed = true; }
		if (ringWidth == null) { ringWidth = clampFinite(legacyWidth, 0.5f, 5.0f, DEFAULT_RING_WIDTH); changed = true; }
		if (ringFill == null) { ringFill = false; changed = true; }
		return changed;
	}

	public void updateLegacyTrigger() {
		if (conditions == null || conditions.isEmpty()) { trigger = Trigger.MANUAL; return; }
		trigger = conditions.contains(Condition.CHAT_EVENT) ? Trigger.CHAT_EVENT
			: conditions.contains(Condition.AFTER_SECONDS) ? Trigger.AFTER_SECONDS
			: conditions.contains(Condition.ENTER_RADIUS) ? Trigger.ENTER_RADIUS : Trigger.MANUAL;
	}

	private static String clean(String value, int limit) {
		if (value == null) return "";
		String cleaned = value.replaceAll("[\\p{Cntrl}]", "").trim();
		return cleaned.length() > limit ? cleaned.substring(0, limit) : cleaned;
	}

	private static Float clampOptional(Float value, float min, float max) {
		return value == null || !Float.isFinite(value) ? null : Math.max(min, Math.min(max, value));
	}

	private static float clampFinite(float value, float min, float max, float fallback) {
		return Float.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
	}
}
