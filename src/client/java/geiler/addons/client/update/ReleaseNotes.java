package geiler.addons.client.update;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a GitHub release body into text that is safe to draw in the Click GUI.
 *
 * <p>The body is remote, untrusted text: it is stripped of every Markdown and HTML construct that
 * would need a renderer, bounded in both length and line count, and returned as plain lines. It is
 * never a link or a click target - the only action the update UI offers is its own buttons.
 */
public final class ReleaseNotes {
	/** Only this repository's release pages are ever offered as a link target. */
	private static final String REPOSITORY_RELEASES = "https://github.com/dasdadassadwt/geileraddons/releases/";
	private static final int MAX_URL_LENGTH = 256;
	/** Bounds for the stored text; the whole response is already capped at 256 KiB. */
	public static final int MAX_NOTES_CHARS = 4_000;
	public static final int MAX_NOTES_LINES = 400;
	public static final int MAX_LINE_CHARS = 200;
	private static final String EMPTY_NOTES = "This release has no notes.";
	private static final String TRUNCATED = "… release notes truncated";

	private static final Pattern LINK = Pattern.compile("!?\\[([^\\]]*)\\]\\([^)]*\\)");
	private static final Pattern HTML = Pattern.compile("<[^>]*>");
	private static final Pattern HEADING = Pattern.compile("^\\s*#{1,6}\\s*");
	private static final Pattern BULLET = Pattern.compile("^\\s*[-*+]\\s+");
	private static final Pattern QUOTE = Pattern.compile("^\\s*>+\\s*");
	private static final Pattern MARKERS = Pattern.compile("[`*_~|]+");

	private ReleaseNotes() {
	}

	/** Plain, bounded lines for the Info panel. Never null and never blank. */
	public static String sanitize(String markdown) {
		if (markdown == null || markdown.isBlank()) return EMPTY_NOTES;
		List<String> lines = new ArrayList<>();
		int characters = 0;
		boolean truncated = false;
		for (String raw : markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
			String line = plainLine(raw);
			if (line.isEmpty()) {
				// One blank line between paragraphs is enough; the panel adds its own spacing.
				if (!lines.isEmpty() && !lines.getLast().isEmpty()) lines.add("");
				continue;
			}
			if (line.length() > MAX_LINE_CHARS) line = line.substring(0, MAX_LINE_CHARS - 1) + "…";
			if (lines.size() >= MAX_NOTES_LINES || characters + line.length() > MAX_NOTES_CHARS) {
				truncated = true;
				break;
			}
			lines.add(line);
			characters += line.length() + 1;
		}
		while (!lines.isEmpty() && lines.getLast().isEmpty()) lines.removeLast();
		if (lines.isEmpty()) return EMPTY_NOTES;
		if (truncated) lines.add(TRUNCATED);
		return String.join("\n", lines);
	}

	/**
	 * The release page from the API response, or null when it is not this project's own release
	 * page.
	 *
	 * <p>A URL out of a response body is the one thing here that could send a player somewhere
	 * unintended, so it is prefix-checked rather than merely parsed.
	 */
	public static String validateReleaseUrl(String htmlUrl) {
		if (htmlUrl == null) return null;
		String trimmed = htmlUrl.strip();
		if (trimmed.isEmpty() || trimmed.length() > MAX_URL_LENGTH) return null;
		if (!trimmed.regionMatches(true, 0, REPOSITORY_RELEASES, 0, REPOSITORY_RELEASES.length())) return null;
		for (int index = 0; index < trimmed.length(); index++) {
			char character = trimmed.charAt(index);
			if (Character.isWhitespace(character) || Character.isISOControl(character)) return null;
		}
		return trimmed;
	}

	/** One body line without its Markdown dressings, control characters, or surrounding space. */
	private static String plainLine(String raw) {
		if (raw == null) return "";
		String line = LINK.matcher(raw).replaceAll("$1");
		line = HTML.matcher(line).replaceAll("");
		line = HEADING.matcher(line).replaceFirst("");
		line = QUOTE.matcher(line).replaceFirst("");
		Matcher bullet = BULLET.matcher(line);
		if (bullet.find()) line = "• " + line.substring(bullet.end());
		line = MARKERS.matcher(line).replaceAll("");
		StringBuilder cleaned = new StringBuilder(line.length());
		for (int index = 0; index < line.length(); index++) {
			char character = line.charAt(index);
			if (character == '\t') {
				cleaned.append(' ');
				continue;
			}
			if (Character.isISOControl(character)) continue;
			cleaned.append(character);
		}
		return cleaned.toString().strip();
	}
}
