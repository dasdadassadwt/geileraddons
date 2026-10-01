package geiler.addons.client.dungeon;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Floor-specific Guide stages and client-visible chat milestones. */
public final class DungeonGuideSegments {
	private static final List<Segment> COMMON = List.of(
		new Segment("ENTRY", "Entry"), new Segment("CLEAR", "Clear"),
		new Segment("BLOOD_OPEN", "Blood Open"), new Segment("BLOOD_CLEAR", "Blood Clear"),
		new Segment("BOSS_ENTRY", "Boss Entry"));

	private DungeonGuideSegments() { }

	public static List<Segment> builtIn(DungeonFloor floor) {
		if (floor == null) return List.of();
		List<Segment> result = new ArrayList<>(COMMON);
		switch (floor.number()) {
			case 0 -> result.add(new Segment("BONZO", "Bonzo"));
			case 1 -> { result.add(new Segment("BONZO_SIKE", "Bonzo's Sike")); result.add(new Segment("BONZO", "Bonzo")); }
			case 2 -> { result.add(new Segment("SCARF_MINIONS", "Scarf's Minions")); result.add(new Segment("SCARF", "Scarf")); }
			case 3 -> {
				result.add(new Segment("GUARDIANS", "Guardians"));
				result.add(new Segment("PROFESSOR", "The Professor"));
				result.add(new Segment("TRANSFORMED_PROFESSOR", "Transformed Professor"));
			}
			case 4 -> result.add(new Segment("THORN", "Thorn"));
			case 5 -> result.add(new Segment("LIVID", "Livid"));
			case 6 -> {
				result.add(new Segment("TERRACOTTAS", "Terracottas"));
				result.add(new Segment("SADAN_GIANTS", "Sadan's Giants"));
				result.add(new Segment("SADAN", "Sadan"));
			}
			case 7 -> {
				result.add(new Segment("MAXOR", "Maxor"));
				result.add(new Segment("STORM", "Storm"));
				result.add(new Segment("TERMINALS", "Terminals"));
				result.add(new Segment("GOLDOR", "Goldor"));
				result.add(new Segment("NECRON", "Necron"));
				if (floor.master()) result.add(new Segment("WITHER_KING", "Wither King"));
			}
			default -> { }
		}
		result.add(new Segment("COMPLETE", "Complete"));
		return List.copyOf(result);
	}

	public static String label(DungeonFloor floor, String id) {
		for (Segment segment : builtIn(floor)) if (segment.id.equals(id)) return segment.label;
		if ("BLOOD".equals(id)) return "Blood";
		if ("BOSS".equals(id)) return "Boss";
		return id == null ? "Unknown" : id.replace('_', ' ');
	}

	public static boolean contains(DungeonFloor floor, String id) {
		if (id == null) return false;
		for (Segment segment : builtIn(floor)) if (segment.id.equals(id)) return true;
		return false;
	}

	public static String next(DungeonFloor floor, String id) {
		List<Segment> segments = builtIn(floor);
		for (int i = 0; i < segments.size(); i++) if (segments.get(i).id.equals(id))
			return segments.get(Math.min(i + 1, segments.size() - 1)).id;
		return id;
	}

	/** Returns the milestone reached by a chat line, if the line is specific to this floor. */
	public static Event fromChat(DungeonFloor floor, String message) {
		if (floor == null || message == null) return null;
		String line = message.toLowerCase(Locale.ROOT).trim();
		if (line.contains("mort:") && (line.contains("found this map") || line.contains("right-click the orb")))
			return new Event("ENTRY", "Mort run-start chat");
		if (line.contains("blood door has been opened")) return new Event("BLOOD_OPEN", "Blood door chat");
		if (line.contains("the watcher:") && line.contains("you have proven yourself") && line.contains("may pass"))
			return new Event("BLOOD_CLEAR", "Watcher completion chat");
		if (bossEntry(floor, line)) return new Event("BOSS_ENTRY", "Boss entry chat");
		if ((line.startsWith("☠ defeated ") || line.startsWith("defeated ")) && defeatBoss(floor, line))
			return new Event("COMPLETE", "Boss defeat chat");
		return switch (floor.number()) {
			case 1 -> line.contains("bonzo:") && line.contains("oh i'm dead")
				? new Event("BONZO_SIKE", "Bonzo dialogue") : null;
			case 2 -> line.contains("scarf:") && line.contains("let's dance")
				? new Event("SCARF_MINIONS", "Scarf dialogue") : null;
			case 3 -> {
				if (line.contains("professor:") && line.contains("guardians") && line.contains("weakness"))
					yield new Event("GUARDIANS", "Professor guardian dialogue");
				if (line.contains("professor:") && line.contains("ultimate technique"))
					yield new Event("PROFESSOR", "Professor dialogue");
				yield null;
			}
			case 6 -> {
				if (line.contains("sadan:") && line.endsWith("enough!"))
					yield new Event("TERRACOTTAS", "Sadan terracotta dialogue");
				if (line.contains("sadan:") && line.contains("earned my respect"))
					yield new Event("SADAN_GIANTS", "Sadan giant dialogue");
				yield null;
			}
			case 7 -> {
				if (line.contains("storm:") && line.contains("pathetic maxor"))
					yield new Event("MAXOR", "Storm dialogue");
				if (line.contains("goldor:") && line.contains("trespass into my domain"))
					yield new Event("STORM", "Goldor dialogue");
				if (line.contains("core entrance is opening"))
					yield new Event("TERMINALS", "Core entrance chat");
				if (line.contains("necron:") && line.contains("went further than any human"))
					yield new Event("GOLDOR", "Necron dialogue");
				if (floor.master() && line.contains("necron:") && line.contains("all this, for nothing"))
					yield new Event("NECRON", "Necron dialogue");
				yield null;
			}
			default -> null;
		};
	}

	private static boolean bossEntry(DungeonFloor floor, String line) {
		return switch (floor.number()) {
			case 0, 1 -> line.contains("bonzo:") && line.contains("basically unbeatable");
			case 2 -> line.contains("scarf:") && line.contains("journey ends");
			case 3 -> line.contains("professor:") && line.contains("burdened with terrible news");
			case 4 -> line.contains("thorn:") && line.contains("welcome adventurers");
			case 5 -> line.contains("livid:") && line.contains("master of shadows");
			case 6 -> line.contains("sadan:") && line.contains("defy me");
			case 7 -> line.contains("maxor:") && line.contains("look who's here");
			default -> false;
		};
	}

	private static boolean defeatBoss(DungeonFloor floor, String line) {
		return switch (floor.number()) {
			case 0, 1 -> line.contains("bonzo");
			case 2 -> line.contains("scarf");
			case 3 -> line.contains("professor");
			case 4 -> line.contains("thorn");
			case 5 -> line.contains("livid");
			case 6 -> line.contains("sadan");
			case 7 -> line.contains(floor.master() ? "wither king" : "necron");
			default -> false;
		};
	}

	public record Segment(String id, String label) { }
	public record Event(String segmentId, String source) { }
}
