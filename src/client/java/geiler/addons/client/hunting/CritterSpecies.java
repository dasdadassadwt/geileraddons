package geiler.addons.client.hunting;

import geiler.addons.client.tree.ChatText;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The 37 Critter Safari species, and how to read a rare one out of a name tag.
 *
 * <p>Hypixel labels a critter with an entity whose custom name is exactly the species name, and a
 * rare variant is named for it: {@code Sparkling Rockmite}. That prefix is the only thing this
 * recognises, rather than searching a label for any species name it contains - a nearby armour
 * stand advertising a "Tepid Shard" must not read as a Tepid.
 *
 * <p>Pure logic, no Minecraft types, so it can be exercised offline against real and adversarial
 * labels. See REFERENCES.md for where the roster came from.
 */
public final class CritterSpecies {
	private static final String SPARKLING = "Sparkling";

	/** Lower-cased name to the canonical spelling, which is what gets shown to the user. */
	private static final Map<String, String> BY_NAME = new LinkedHashMap<>();

	private static final String[] NAMES = {
		// Forest
		"Foxtrot", "Bluebird", "Honeybug", "Treefrog", "Woodchucker", "Fluffling", "Hideonfloor",
		"Parakeet", "Macaw",
		// Cavern
		"Cavernfish", "Flitter", "Shyworm", "Driftling", "Chuckwalla", "Rockmite", "Scrappy",
		"Snoozle", "Gemzie",
		// Icy
		"Strongarm", "Tepid", "Polaris", "Shuddersquid", "Billygoat", "Mantis Shrimp", "Nozzlenose",
		"Troodon", "Wumpa",
		// Haunted
		"Areita", "Bloodbat", "Duplico", "Gazer", "Litterbug", "Solsnatcher", "Gimmiegold",
		"Hideonwall", "Hideyho", "Doomspiral",
	};

	static {
		for (String name : NAMES) {
			BY_NAME.put(name.toLowerCase(Locale.ROOT), name);
		}
	}

	private CritterSpecies() {
	}

	/** Total species count - 37. */
	public static int count() {
		return NAMES.length;
	}

	/**
	 * The species a label names outright, or null.
	 *
	 * <p>Whole-string equality rather than {@code contains}: a label is the species name and
	 * nothing else, so anything longer is a different thing that merely mentions one.
	 */
	public static String named(String label) {
		if (label == null) return null;
		String text = ChatText.stripForMatch(label);
		return text.isEmpty() ? null : BY_NAME.get(text.toLowerCase(Locale.ROOT));
	}

	/**
	 * The species a label names as a sparkling one, or null if it names none.
	 *
	 * <p>The length guard is what stops a label reading exactly {@code Sparkling} from being
	 * treated as a rare something-or-other with an empty species.
	 */
	public static String sparkling(String label) {
		if (label == null) return null;
		String text = ChatText.stripForMatch(label);
		if (text.length() <= SPARKLING.length()) return null;
		if (!text.regionMatches(true, 0, SPARKLING, 0, SPARKLING.length())) return null;
		return named(text.substring(SPARKLING.length()));
	}
}
