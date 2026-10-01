package geiler.addons.client.dungeon;

import java.util.Locale;
import java.util.UUID;

/** Player-defined Guide stage and the signal that activates it. */
public final class DungeonGuideCustomSegment {
	public enum Trigger { MANUAL, CHAT_EVENT, ENTER_RADIUS, AFTER_SECONDS }

	public String id = "CUSTOM_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
	public String floor = "F1";
	public String label = "Custom stage";
	public Trigger trigger = Trigger.MANUAL;
	public String eventText = "";
	public int x;
	public int y;
	public int z;
	public float radius = 2.0f;
	public float seconds;
	public int afterBuiltInIndex = 1;

	public void sanitize() {
		if (id == null || !id.matches("CUSTOM_[A-Z0-9_]{1,25}"))
			id = "CUSTOM_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
		DungeonFloor parsed = DungeonFloor.parse(floor);
		floor = parsed == null ? "F1" : parsed.displayName();
		label = clean(label, 48);
		if (label.isBlank()) label = "Custom stage";
		if (trigger == null) trigger = Trigger.MANUAL;
		eventText = clean(eventText, 128);
		radius = Float.isFinite(radius) ? Math.max(0.5f, Math.min(64, radius)) : 2.0f;
		seconds = Float.isFinite(seconds) ? Math.max(0, Math.min(3600, seconds)) : 0;
		afterBuiltInIndex = Math.max(0, Math.min(
			DungeonGuideSegments.builtIn(DungeonFloor.parse(floor)).size() - 1, afterBuiltInIndex));
	}

	private static String clean(String value, int limit) {
		if (value == null) return "";
		String text = value.replaceAll("[\\p{Cntrl}]", "").trim();
		return text.length() <= limit ? text : text.substring(0, limit);
	}
}
