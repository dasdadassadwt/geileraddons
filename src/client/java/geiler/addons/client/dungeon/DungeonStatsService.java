package geiler.addons.client.dungeon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import geiler.addons.GeilerAddons;
import net.minecraft.client.Minecraft;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/** Asynchronous, cached adapter for the profile endpoints used by Odin. */
public final class DungeonStatsService {
	private static final String API_BASE = "https://hypixel.odtheking.com/";
	private static final String UUID_BASE = "https://api.minecraftservices.com/minecraft/profile/lookup/name/";
	private static final long CACHE_TTL_MILLIS = 5 * 60 * 1000L;
	private static final Duration TIMEOUT = Duration.ofSeconds(10);
	private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, r -> {
		Thread thread = new Thread(r, "GeilerAddons dungeon stats");
		thread.setDaemon(true);
		return thread;
	});
	private static final HttpClient HTTP = HttpClient.newBuilder()
		.connectTimeout(TIMEOUT)
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();
	private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();
	private static final Map<String, CompletableFuture<Result>> IN_FLIGHT = new ConcurrentHashMap<>();

	private DungeonStatsService() {
	}

	public static void fetch(String name, Consumer<Result> callback) {
		if (name == null || name.isBlank()) {
			GeilerAddons.LOGGER.debug("[Dungeon Stats] Ignored blank profile request");
			return;
		}
		String key = name.toLowerCase(Locale.ROOT);
		CacheEntry cached = CACHE.get(key);
		if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MILLIS) {
			GeilerAddons.LOGGER.debug("[Dungeon Stats] Cache hit for {}", name);
			Minecraft.getInstance().execute(() -> callback.accept(Result.success(cached.stats)));
			return;
		}

		AtomicBoolean created = new AtomicBoolean();
		CompletableFuture<Result> future = IN_FLIGHT.computeIfAbsent(key, ignored -> {
			created.set(true);
			GeilerAddons.LOGGER.debug("[Dungeon Stats] Fetching {} from Mojang and hypixel.odtheking.com", name);
			return CompletableFuture.supplyAsync(() -> load(name), EXECUTOR)
				.exceptionally(error -> Result.failure(errorText(error)));
		});
		if (!created.get()) GeilerAddons.LOGGER.debug("[Dungeon Stats] Joined in-flight request for {}", name);
		future.whenComplete((result, error) -> IN_FLIGHT.remove(key, future));
		future.thenAccept(result -> Minecraft.getInstance().execute(() -> callback.accept(result)));
	}

	public static void clearCache() {
		CACHE.clear();
	}

	public record Result(DungeonStats stats, String error) {
		public static Result success(DungeonStats stats) { return new Result(stats, null); }
		public static Result failure(String error) { return new Result(null, error); }
		public boolean available() { return stats != null; }
	}

	private record CacheEntry(DungeonStats stats, long timestamp) {
	}

	private static Result load(String name) {
		try {
			GeilerAddons.LOGGER.debug("[Dungeon Stats] Looking up UUID for {}", name);
			JsonObject uuidJson = getJson(UUID_BASE + encodePath(name));
			String id = string(uuidJson, "id");
			String returnedName = string(uuidJson, "name");
			if (id == null) {
				GeilerAddons.LOGGER.debug("[Dungeon Stats] Mojang returned no UUID for {}", name);
				return Result.failure("Mojang did not return a UUID");
			}
			UUID uuid = UUID.fromString(withDashes(id));
			GeilerAddons.LOGGER.debug("[Dungeon Stats] Loading profile {} ({})", returnedName == null ? name : returnedName, id);
			JsonObject profile = getJson(API_BASE + "get/" + id);
			JsonObject member = profileMember(profile, id);
			if (member == null) {
				GeilerAddons.LOGGER.debug("[Dungeon Stats] No profile member data for {}", name);
				return Result.failure("Profile data is unavailable");
			}

			DungeonStats stats = parseStats(returnedName == null ? name : returnedName, uuid, profile, member);
			CACHE.put(name.toLowerCase(Locale.ROOT), new CacheEntry(stats, System.currentTimeMillis()));
			GeilerAddons.LOGGER.debug("[Dungeon Stats] Loaded {}: cata={}, class={}, secrets={}, runs={}, mp={}",
				stats.name(), stats.catacombsLevel(), stats.selectedClass(), stats.totalSecrets(), stats.totalRuns(), stats.magicalPower());
			return Result.success(stats);
		} catch (Exception exception) {
			GeilerAddons.LOGGER.debug("[Dungeon Stats] Profile lookup failed for {}: {}", name, errorText(exception));
			return Result.failure(errorText(exception));
		}
	}

	private static JsonObject getJson(String url) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
			.header("User-Agent", "GeilerAddons")
			.header("Accept", "application/json")
			.GET().build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		GeilerAddons.LOGGER.debug("[Dungeon Stats] GET {} -> {} (final URI {})", url, response.statusCode(), response.uri());
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new IOException("HTTP " + response.statusCode());
		}
		JsonElement parsed = JsonParser.parseString(response.body());
		if (!parsed.isJsonObject()) throw new IOException("Response was not an object");
		return parsed.getAsJsonObject();
	}

	private static JsonObject profileMember(JsonObject root, String uuid) {
		JsonObject profile = profileForMember(root, uuid);
		if (profile == null) return null;
		String compact = uuid.replace("-", "");
		String dashed = withDashes(compact);
		JsonObject members = object(profile, "members");
		JsonObject member = object(members, compact);
		return member == null ? object(members, dashed) : member;
	}

	private static DungeonStats parseStats(String name, UUID uuid, JsonObject root, JsonObject member) {
		JsonObject dungeons = object(member, "dungeons");
		JsonObject types = object(dungeons, "dungeon_types");
		JsonObject catacombs = object(types, "catacombs");
		JsonObject master = object(types, "master_catacombs");
		int cata = level(number(catacombs, "experience"), 50);

		Map<DungeonClass, Integer> classLevels = new EnumMap<>(DungeonClass.class);
		JsonObject classes = object(dungeons, "player_classes");
		for (DungeonClass dungeonClass : DungeonClass.values()) {
			JsonObject value = object(classes, dungeonClass.name().toLowerCase(Locale.ROOT));
			classLevels.put(dungeonClass, level(number(value, "experience"), 50));
		}
		double classAverage = classLevels.values().stream().mapToInt(Integer::intValue).average().orElse(0);

		long secrets = firstNonZero(dungeons, "total_secrets", "totalSecrets", "secrets", "secrets_found");
		long runs = totalRuns(catacombs, master);
		JsonObject banking = object(profileForMember(root, uuid.toString()), "banking");
		long bank = firstLong(banking, "balance", "bank_balance");
		boolean bankKnown = banking != null && hasAny(banking, "balance", "bank_balance");
		int magicalPower = (int) firstNonZero(member, "highest_magical_power", "magical_power",
			"magicalPower", "magical_power_value");

		EnumSet<DungeonStats.Gear> gear = EnumSet.noneOf(DungeonStats.Gear.class);
		String allText = collectItemText(member);
		if (allText.contains("terminator")) gear.add(DungeonStats.Gear.TERMINATOR);
		if (allText.contains("hyperion")) gear.add(DungeonStats.Gear.HYPERION);
		if (allText.contains("golden_dragon") || allText.contains("golden dragon")) gear.add(DungeonStats.Gear.GOLDEN_DRAGON);

		Map<DungeonFloor, Long> pbs = new EnumMap<>(DungeonFloor.class);
		readPbs(pbs, catacombs, false);
		readPbs(pbs, master, true);
		boolean gearKnown = hasInventoryData(member) || allText.contains("terminator") || allText.contains("hyperion")
			|| allText.contains("golden_dragon") || allText.contains("golden dragon");
		return new DungeonStats(name, uuid, cata, DungeonClass.parse(firstString(dungeons, "selected_dungeon_class", "selectedClass")),
			classLevels, classAverage, secrets, runs, magicalPower, bank, bankKnown, gearKnown, gear, pbs);
	}

	private static void readPbs(Map<DungeonFloor, Long> output, JsonObject type, boolean master) {
		if (type == null) return;
		JsonObject fastest = object(type, "fastest_time_s_plus");
		if (fastest == null) fastest = object(type, "fastestTimeSPlus");
		if (fastest == null) return;
		for (int i = 1; i <= 7; i++) {
			long raw = firstLong(fastest, String.valueOf(i), "floor_" + i, "F" + i);
			if (raw <= 0) continue;
			long seconds = raw > 10_000 ? raw / 1000 : raw;
			output.put(DungeonFloor.parse((master ? "M" : "F") + i), seconds);
		}
	}

	private static long totalRuns(JsonObject normal, JsonObject master) {
		return completions(normal) + completions(master);
	}

	private static long completions(JsonObject type) {
		JsonObject completions = object(type, "tier_completions");
		if (completions == null) completions = object(type, "tierCompletions");
		if (completions == null) return 0;
		long total = 0;
		for (int i = 1; i <= 7; i++) total += firstLong(completions, String.valueOf(i), "floor_" + i);
		return total;
	}

	private static int level(long xp, int cap) {
		long[] cumulative = {
			0, 50, 125, 235, 395, 625, 955, 1425, 2095, 3045, 4385,
			6275, 8940, 12700, 17960, 25340, 35640, 50040, 70040, 97640,
			135640, 188140, 259640, 356640, 488640, 668640, 911640, 1239640,
			1684640, 2284640, 3084640, 4149640, 5559640, 7459640, 9959640,
			13259640, 17559640, 23159640, 30359640, 39559640, 51559640,
			66559640, 85559640, 109559640, 139559640, 177559640, 225559640,
			285559640, 360559640, 453559640, 569809640
		};
		int result = 0;
		for (int i = 0; i < cumulative.length && i <= cap; i++) {
			if (xp >= cumulative[i]) result = i;
			else break;
		}
		return result;
	}

	private static String collectItemText(JsonElement element) {
		StringBuilder out = new StringBuilder();
		collectItemText(element, out);
		return out.toString().toLowerCase(Locale.ROOT);
	}

	private static void collectItemText(JsonElement element, StringBuilder out) {
		if (element == null || element.isJsonNull()) return;
		if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
			String value = element.getAsString();
			out.append(value).append(' ');
			if (value.length() > 32 && value.matches("[A-Za-z0-9+/=]+")) searchCompressedNbt(value, out);
		} else if (element.isJsonArray()) {
			for (JsonElement child : element.getAsJsonArray()) collectItemText(child, out);
		} else if (element.isJsonObject()) {
			for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
				out.append(entry.getKey()).append(' ');
				collectItemText(entry.getValue(), out);
			}
		}
	}

	private static void searchCompressedNbt(String value, StringBuilder out) {
		try {
			byte[] bytes = Base64.getDecoder().decode(value);
			try (DataInputStream input = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(bytes)))) {
				NbtTextReader.read(input, out);
			}
		} catch (Exception ignored) {
			// Inventory fields are optional and not every long string is compressed NBT.
		}
	}

	private static boolean hasInventoryData(JsonElement element) {
		if (element == null || element.isJsonNull()) return false;
		if (element.isJsonArray()) {
			for (JsonElement child : element.getAsJsonArray()) if (hasInventoryData(child)) return true;
			return false;
		}
		if (!element.isJsonObject()) return false;
		for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
			String key = entry.getKey().toLowerCase(Locale.ROOT);
			JsonElement value = entry.getValue();
			if ((key.equals("inv_contents") || key.equals("ender_chest_contents") || key.equals("armor_contents")
				|| key.equals("talisman_bag")) && value != null && !value.isJsonNull()) {
				if (value.isJsonPrimitive() && !value.getAsString().isBlank()) return true;
				if (value.isJsonArray() && !value.getAsJsonArray().isEmpty()) return true;
				if (value.isJsonObject() && !value.getAsJsonObject().entrySet().isEmpty()) return true;
			}
			if (hasInventoryData(value)) return true;
		}
		return false;
	}

	private static JsonObject object(JsonObject parent, String name) {
		if (parent == null || !parent.has(name) || !parent.get(name).isJsonObject()) return null;
		return parent.getAsJsonObject(name);
	}

	private static JsonArray array(JsonObject parent, String name) {
		if (parent == null || !parent.has(name) || !parent.get(name).isJsonArray()) return null;
		return parent.getAsJsonArray(name);
	}

	private static long number(JsonObject object, String key) {
		if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) return 0;
		try { return object.get(key).getAsLong(); } catch (RuntimeException ignored) { return 0; }
	}

	private static long firstLong(JsonObject object, String... keys) {
		for (String key : keys) {
			long value = number(object, key);
			if (value != 0) return value;
		}
		return 0;
	}

	private static long firstNonZero(JsonObject object, String... keys) {
		long direct = firstLong(object, keys);
		return direct != 0 ? direct : findLong(object, keys);
	}

	private static long findLong(JsonElement element, String... keys) {
		if (element == null || element.isJsonNull()) return 0;
		if (element.isJsonObject()) {
			JsonObject object = element.getAsJsonObject();
			for (String key : keys) {
				long value = number(object, key);
				if (value != 0) return value;
			}
			for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
				long value = findLong(entry.getValue(), keys);
				if (value != 0) return value;
			}
		} else if (element.isJsonArray()) {
			for (JsonElement child : element.getAsJsonArray()) {
				long value = findLong(child, keys);
				if (value != 0) return value;
			}
		}
		return 0;
	}

	private static boolean hasAny(JsonObject object, String... keys) {
		if (object == null) return false;
		for (String key : keys) if (object.has(key)) return true;
		return false;
	}

	private static JsonObject profileForMember(JsonObject root, String uuid) {
		JsonArray profiles = array(root, "profiles");
		if (profiles == null) return null;
		String compact = uuid.replace("-", "");
		String dashed = withDashes(compact);
		JsonObject fallback = null;
		for (JsonElement element : profiles) {
			if (!element.isJsonObject()) continue;
			JsonObject profile = element.getAsJsonObject();
			JsonObject members = object(profile, "members");
			if (members == null || (!members.has(compact) && !members.has(dashed))) continue;
			if (booleanValue(profile, "selected")) return profile;
			if (fallback == null) fallback = profile;
		}
		return fallback;
	}

	private static boolean booleanValue(JsonObject object, String key) {
		if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) return false;
		try { return object.get(key).getAsBoolean(); } catch (RuntimeException ignored) { return false; }
	}

	private static String firstString(JsonObject object, String... keys) {
		for (String key : keys) {
			String value = string(object, key);
			if (value != null) return value;
		}
		return null;
	}

	private static String string(JsonObject object, String key) {
		if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) return null;
		try { return object.get(key).getAsString(); } catch (RuntimeException ignored) { return null; }
	}

	private static String withDashes(String id) {
		String value = id.replace("-", "");
		if (value.length() != 32) return id;
		return value.substring(0, 8) + "-" + value.substring(8, 12) + "-" + value.substring(12, 16)
			+ "-" + value.substring(16, 20) + "-" + value.substring(20);
	}

	private static String encodePath(String value) {
		return value.trim().replace(" ", "%20");
	}

	private static String errorText(Throwable exception) {
		String message = exception.getMessage();
		return message == null || message.isBlank() ? "Request failed" : message;
	}

	public static String formatTime(long seconds) {
		if (seconds <= 0) return "-";
		return (seconds / 60) + ":" + String.format(Locale.ROOT, "%02d", seconds % 60);
	}

	/** Minimal NBT text walker used only to find item ids in compressed inventory fields. */
	private static final class NbtTextReader {
		static void read(DataInputStream input, StringBuilder out) throws IOException {
			int type = input.readUnsignedByte();
			if (type == 0) return;
			input.readUTF();
			readPayload(input, type, out);
		}

		private static void readPayload(DataInputStream input, int type, StringBuilder out) throws IOException {
			switch (type) {
				case 1 -> input.readByte();
				case 2 -> input.readShort();
				case 3 -> input.readInt();
				case 4 -> input.readLong();
				case 5 -> input.readFloat();
				case 6 -> input.readDouble();
				case 7 -> input.skipBytes(input.readInt());
				case 8 -> out.append(input.readUTF()).append(' ');
				case 9 -> {
					int childType = input.readUnsignedByte();
					int count = input.readInt();
					for (int i = 0; i < count; i++) readPayload(input, childType, out);
				}
				case 10 -> {
					while (true) {
						int childType = input.readUnsignedByte();
						if (childType == 0) break;
						out.append(input.readUTF()).append(' ');
						readPayload(input, childType, out);
					}
				}
				case 11 -> input.skipBytes(input.readInt() * 4);
				case 12 -> input.skipBytes(input.readInt() * 8);
				default -> throw new IOException("Unknown NBT tag " + type);
			}
		}
	}
}
