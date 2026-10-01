package geiler.addons.client.dungeon;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Pure, versioned, bounded clipboard format for player-authored dungeon guide content. */
public final class DungeonPackageCodec {
	private DungeonPackageCodec() { }

	public static String encodeGuides(List<DungeonGuideNode> entries) {
		if (entries == null || entries.size() > ClientJsonFile.MAX_ENTRIES) return null;
		GuidePackage data = new GuidePackage();
		data.version = 1;
		data.nodes = entries;
		return boundedEncode(data);
	}

	public static DecodeResult<DungeonGuideNode> decodeGuides(String encoded) {
		if (!validRoot(encoded)) return failure("Package JSON is invalid or too large.");
		GuidePackage data;
		try { data = ClientJsonFile.GSON.fromJson(encoded, GuidePackage.class); }
		catch (RuntimeException exception) { return failure("Package data is invalid."); }
		if (data == null || data.version != 1 || data.nodes == null
			|| data.nodes.size() > ClientJsonFile.MAX_ENTRIES) return failure("Unsupported package version or entry count.");
		Set<String> ids = new HashSet<>();
		List<DungeonGuideNode> clean = new ArrayList<>();
		for (DungeonGuideNode node : data.nodes) {
			if (node == null) continue;
			node.sanitize();
			if (!ids.add(node.id)) { node.id = UUID.randomUUID().toString(); ids.add(node.id); }
			clean.add(node);
		}
		return new DecodeResult<>(true, List.copyOf(clean), null);
	}

	private static String boundedEncode(Object data) {
		String json = ClientJsonFile.GSON.toJson(data);
		return json.getBytes(StandardCharsets.UTF_8).length <= ClientJsonFile.MAX_PACKAGE_BYTES ? json : null;
	}

	private static boolean validRoot(String encoded) {
		if (encoded == null || encoded.getBytes(StandardCharsets.UTF_8).length > ClientJsonFile.MAX_PACKAGE_BYTES) return false;
		try { return JsonParser.parseString(encoded).isJsonObject(); }
		catch (JsonParseException | IllegalStateException exception) { return false; }
	}

	private static <T> DecodeResult<T> failure(String error) { return new DecodeResult<>(false, List.of(), error); }

	private static final class GuidePackage { int version; List<DungeonGuideNode> nodes; }
	public record DecodeResult<T>(boolean success, List<T> entries, String error) { }
}
