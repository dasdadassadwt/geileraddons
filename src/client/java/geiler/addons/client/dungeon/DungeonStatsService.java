package geiler.addons.client.dungeon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import geiler.addons.GeilerAddons;
import net.minecraft.client.Minecraft;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
	/** Profile responses are untrusted remote input; never let one consume arbitrary memory. */
	private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
	private static final int MAX_JSON_DEPTH = 32;
	private static final int MAX_JSON_NODES = 50_000;
	private static final int MAX_ITEM_TEXT_CHARS = 64 * 1024;
	private static final int MAX_COMPRESSED_VALUE_CHARS = 256 * 1024;
	private static final int MAX_NBT_BYTES = 2 * 1024 * 1024;
	private static final int MAX_NBT_DEPTH = 32;
	private static final int MAX_NBT_NODES = 20_000;
	private static final int MAX_NBT_STRING_CHARS = 8 * 1024;
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
		fetch(name, callback, false);
	}

	/** Fetches a fresh profile even when the normal five-minute cache contains an older result. */
	public static void fetchFresh(String name, Consumer<Result> callback) {
		fetch(name, callback, true);
	}

	private static void fetch(String name, Consumer<Result> callback, boolean forceRefresh) {
		if (name == null || name.isBlank()) {
			GeilerAddons.LOGGER.debug("[Dungeon Stats] Ignored blank profile request");
			return;
		}
		String key = name.toLowerCase(Locale.ROOT);
		CacheEntry cached = CACHE.get(key);
		if (!forceRefresh && cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MILLIS) {
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
			if (stats.cacheable()) {
				CACHE.put(name.toLowerCase(Locale.ROOT), new CacheEntry(stats, System.currentTimeMillis()));
			} else {
				GeilerAddons.LOGGER.debug("[Dungeon Stats] Keeping incomplete profile for {} out of cache", name);
			}
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
		HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
		GeilerAddons.LOGGER.debug("[Dungeon Stats] GET {} -> {} (final URI {})", url, response.statusCode(), response.uri());
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			try (InputStream ignored = response.body()) {
				// Drain nothing on an error response; the status is enough to classify it.
			}
			throw new IOException("HTTP " + response.statusCode());
		}
		String body;
		try (InputStream input = response.body()) {
			body = readLimited(input, MAX_RESPONSE_BYTES);
		}
		JsonElement parsed;
		try {
			parsed = JsonParser.parseString(body);
		} catch (StackOverflowError overflow) {
			throw new IOException("Response nesting exceeded parser limits", overflow);
		}
		if (!parsed.isJsonObject()) throw new IOException("Response was not an object");
		return parsed.getAsJsonObject();
	}

	private static String readLimited(InputStream input, int maximumBytes) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximumBytes, 64 * 1024));
		byte[] buffer = new byte[8192];
		int total = 0;
		while (true) {
			int read = input.read(buffer);
			if (read < 0) break;
			if (read == 0) continue;
			total += read;
			if (total > maximumBytes) throw new IOException("Response exceeded " + maximumBytes + " bytes");
			output.write(buffer, 0, read);
		}
		return output.toString(StandardCharsets.UTF_8);
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
		EnumSet<DungeonStats.DataField> available = EnumSet.noneOf(DungeonStats.DataField.class);
		int cata = level(number(catacombs, "experience"), 50);
		if (hasNumber(catacombs, "experience")) available.add(DungeonStats.DataField.CATACOMBS_LEVEL);

		Map<DungeonClass, Integer> classLevels = new EnumMap<>(DungeonClass.class);
		JsonObject classes = object(dungeons, "player_classes");
		boolean allClassesKnown = classes != null;
		for (DungeonClass dungeonClass : DungeonClass.values()) {
			JsonObject value = object(classes, dungeonClass.name().toLowerCase(Locale.ROOT));
			if (hasNumber(value, "experience")) {
				classLevels.put(dungeonClass, level(number(value, "experience"), 50));
			} else {
				allClassesKnown = false;
			}
		}
		double classAverage = classLevels.values().stream().mapToInt(Integer::intValue).average().orElse(0);
		if (allClassesKnown) available.add(DungeonStats.DataField.CLASS_AVERAGE);

		NumberValue secretValue = findNumber(dungeons, "total_secrets", "totalSecrets", "secrets", "secrets_found");
		long secrets = secretValue.present() ? secretValue.value() : 0;
		if (secretValue.present()) available.add(DungeonStats.DataField.SECRETS);
		long runs = totalRuns(catacombs, master);
		if (hasCompletions(catacombs) || hasCompletions(master)) available.add(DungeonStats.DataField.RUNS);
		JsonObject banking = object(profileForMember(root, uuid.toString()), "banking");
		long bank = firstLong(banking, "balance", "bank_balance");
		boolean bankKnown = banking != null && hasNumber(banking, "balance", "bank_balance");
		if (bankKnown) available.add(DungeonStats.DataField.BANK);
		NumberValue magicalPowerValue = findNumber(member, "highest_magical_power", "magical_power",
			"magicalPower", "magical_power_value");
		int magicalPower = (int) (magicalPowerValue.present() ? magicalPowerValue.value() : 0);
		if (magicalPowerValue.present()) available.add(DungeonStats.DataField.MAGICAL_POWER);

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
		if (gearKnown) available.add(DungeonStats.DataField.GEAR);
		DungeonClass selectedClass = DungeonClass.parse(firstString(dungeons, "selected_dungeon_class", "selectedClass"));
		if (selectedClass != null) available.add(DungeonStats.DataField.SELECTED_CLASS);
		return new DungeonStats(name, uuid, cata, selectedClass, classLevels, classAverage, secrets, runs,
			magicalPower, bank, bankKnown, gearKnown, gear, pbs, available);
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
		collectInventoryText(element, out, 0, new JsonBudget(MAX_JSON_NODES), false);
		return out.toString().toLowerCase(Locale.ROOT);
	}

	private static void collectInventoryText(JsonElement element, StringBuilder out, int depth,
		JsonBudget budget, boolean insideInventory) {
		if (element == null || element.isJsonNull() || depth > MAX_JSON_DEPTH || !budget.visit()) return;
		if (element.isJsonPrimitive()) {
			if (!insideInventory || !element.getAsJsonPrimitive().isString()) return;
			String value = element.getAsString();
			appendLimited(out, value);
			if (value.length() > 32 && value.length() <= MAX_COMPRESSED_VALUE_CHARS
				&& value.matches("[A-Za-z0-9+/=]+")) searchCompressedNbt(value, out);
			return;
		}
		if (element.isJsonArray()) {
			for (JsonElement child : element.getAsJsonArray()) {
				collectInventoryText(child, out, depth + 1, budget, insideInventory);
				if (out.length() >= MAX_ITEM_TEXT_CHARS) return;
			}
			return;
		}
		if (!element.isJsonObject()) return;
		for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
			boolean inventory = insideInventory || isInventoryKey(entry.getKey());
			if (inventory) appendLimited(out, entry.getKey());
			collectInventoryText(entry.getValue(), out, depth + 1, budget, inventory);
			if (out.length() >= MAX_ITEM_TEXT_CHARS) return;
		}
	}

	private static void appendLimited(StringBuilder out, String value) {
		if (value == null || out.length() >= MAX_ITEM_TEXT_CHARS) return;
		int remaining = MAX_ITEM_TEXT_CHARS - out.length();
		out.append(value, 0, Math.min(value.length(), remaining)).append(' ');
	}

	private static boolean isInventoryKey(String key) {
		if (key == null) return false;
		String normalized = key.toLowerCase(Locale.ROOT);
		return normalized.equals("inv_contents") || normalized.equals("ender_chest_contents")
			|| normalized.equals("armor_contents") || normalized.equals("talisman_bag")
			|| normalized.equals("wardrobe_contents") || normalized.equals("equipment_contents")
			|| normalized.equals("inventory") || normalized.equals("items") || normalized.equals("item_data")
			|| normalized.endsWith("_contents");
	}

	private static void searchCompressedNbt(String value, StringBuilder out) {
		searchCompressedNbtReadable(value, out);
	}

	private static boolean searchCompressedNbtReadable(String value, StringBuilder out) {
		try {
			byte[] bytes = Base64.getDecoder().decode(value);
			if (bytes.length == 0 || bytes.length > MAX_NBT_BYTES) return false;
			try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes));
				DataInputStream input = new DataInputStream(new LimitedInputStream(gzip, MAX_NBT_BYTES))) {
				NbtTextReader.read(input, out);
			}
			return true;
		} catch (Exception ignored) {
			// Inventory fields are optional and not every long string is compressed NBT.
			return false;
		}
	}

	private static boolean hasInventoryData(JsonElement element) {
		return findInventoryData(element, 0, new JsonBudget(MAX_JSON_NODES));
	}

	private static boolean findInventoryData(JsonElement element, int depth, JsonBudget budget) {
		if (element == null || element.isJsonNull() || depth > MAX_JSON_DEPTH || !budget.visit()) return false;
		if (element.isJsonArray()) {
			for (JsonElement child : element.getAsJsonArray()) {
				if (findInventoryData(child, depth + 1, budget)) return true;
			}
			return false;
		}
		if (!element.isJsonObject()) return false;
		for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
			JsonElement value = entry.getValue();
			if (isInventoryKey(entry.getKey())) {
				// Do not descend through an inventory payload after it has been identified. Item
				// metadata can contain arbitrary nested fields; only a non-empty, readable payload
				// makes gear availability trustworthy.
				if (inventoryValueIsReadable(value)) return true;
				continue;
			}
			if (findInventoryData(value, depth + 1, budget)) return true;
		}
		return false;
	}

	private static boolean inventoryValueIsReadable(JsonElement value) {
		return inventoryValueIsReadable(value, 0, new JsonBudget(MAX_JSON_NODES));
	}

	private static boolean inventoryValueIsReadable(JsonElement value, int depth, JsonBudget budget) {
		if (value == null || value.isJsonNull()) return false;
		if (depth > MAX_JSON_DEPTH || !budget.visit()) return false;
		if (value.isJsonObject()) {
			for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
				if (inventoryValueIsReadable(entry.getValue(), depth + 1, budget)) return true;
			}
			return false;
		}
		if (value.isJsonArray()) {
			if (value.getAsJsonArray().isEmpty()) return false;
			for (JsonElement child : value.getAsJsonArray()) {
				if (inventoryValueIsReadable(child, depth + 1, budget)) return true;
			}
			return false;
		}
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return false;
		String text = value.getAsString();
		if (text.isBlank()) return false;
		if (text.length() > MAX_COMPRESSED_VALUE_CHARS) return false;
		return text.length() <= 32 || !text.matches("[A-Za-z0-9+/=]+")
			|| searchCompressedNbtReadable(text, new StringBuilder());
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

	private static boolean hasNumber(JsonObject object, String... keys) {
		if (object == null) return false;
		for (String key : keys) {
			if (!object.has(key) || !object.get(key).isJsonPrimitive()) continue;
			try {
				object.get(key).getAsLong();
				return true;
			} catch (RuntimeException ignored) {
				// Try the next API spelling.
			}
		}
		return false;
	}

	private static NumberValue findNumber(JsonElement element, String... keys) {
		return findNumber(element, 0, new JsonBudget(MAX_JSON_NODES), keys);
	}

	private static NumberValue findNumber(JsonElement element, int depth, JsonBudget budget,
		String... keys) {
		if (element == null || element.isJsonNull() || depth > MAX_JSON_DEPTH || !budget.visit()) {
			return NumberValue.MISSING;
		}
		if (element.isJsonObject()) {
			JsonObject object = element.getAsJsonObject();
			for (String key : keys) {
				if (hasNumber(object, key)) return new NumberValue(number(object, key), true);
			}
			for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
				NumberValue found = findNumber(entry.getValue(), depth + 1, budget, keys);
				if (found.present()) return found;
			}
		} else if (element.isJsonArray()) {
			for (JsonElement child : element.getAsJsonArray()) {
				NumberValue found = findNumber(child, depth + 1, budget, keys);
				if (found.present()) return found;
			}
		}
		return NumberValue.MISSING;
	}

	private static boolean hasCompletions(JsonObject type) {
		return object(type, "tier_completions") != null || object(type, "tierCompletions") != null;
	}

	private record NumberValue(long value, boolean present) {
		private static final NumberValue MISSING = new NumberValue(0, false);
	}

	private static final class JsonBudget {
		private int remaining;

		private JsonBudget(int maximumNodes) {
			remaining = maximumNodes;
		}

		private boolean visit() {
			return remaining-- > 0;
		}
	}

	private static JsonObject profileForMember(JsonObject root, String uuid) {
		JsonArray profiles = array(root, "profiles");
		if (profiles == null) return null;
		String compact = uuid.replace("-", "");
		String dashed = withDashes(compact);
		JsonObject fallback = null;
		int inspected = 0;
		for (JsonElement element : profiles) {
			if (++inspected > 128) break;
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
			NbtBudget budget = new NbtBudget();
			int type = input.readUnsignedByte();
			if (type == 0) return;
			readString(input, budget);
			readPayload(input, type, out, budget, 0);
		}

		private static void readPayload(DataInputStream input, int type, StringBuilder out,
			NbtBudget budget, int depth) throws IOException {
			if (depth > MAX_NBT_DEPTH || !budget.visit()) throw new IOException("NBT limits exceeded");
			switch (type) {
				case 1 -> input.readByte();
				case 2 -> input.readShort();
				case 3 -> input.readInt();
				case 4 -> input.readLong();
				case 5 -> input.readFloat();
				case 6 -> input.readDouble();
				case 7 -> skipBytes(input, input.readInt());
				case 8 -> appendLimited(out, readString(input, budget));
				case 9 -> {
					int childType = input.readUnsignedByte();
					int count = input.readInt();
					if (count < 0 || count > MAX_NBT_NODES) throw new IOException("Invalid NBT list length");
					for (int i = 0; i < count; i++) readPayload(input, childType, out, budget, depth + 1);
				}
				case 10 -> {
					while (true) {
						int childType = input.readUnsignedByte();
						if (childType == 0) break;
						appendLimited(out, readString(input, budget));
						readPayload(input, childType, out, budget, depth + 1);
					}
				}
				case 11 -> skipArray(input, input.readInt(), 4);
				case 12 -> skipArray(input, input.readInt(), 8);
				default -> throw new IOException("Unknown NBT tag " + type);
			}
		}

		private static String readString(DataInputStream input, NbtBudget budget) throws IOException {
			int length = input.readUnsignedShort();
			if (length > MAX_NBT_STRING_CHARS || !budget.consumeBytes(length)) {
				throw new IOException("NBT string limit exceeded");
			}
			byte[] bytes = new byte[length];
			input.readFully(bytes);
			return new String(bytes, StandardCharsets.UTF_8);
		}

		private static void skipArray(DataInputStream input, int count, int bytesPerValue) throws IOException {
			if (count < 0 || (long) count * bytesPerValue > MAX_NBT_BYTES) {
				throw new IOException("NBT array limit exceeded");
			}
			skipBytes(input, count * bytesPerValue);
		}

		private static void skipBytes(DataInputStream input, int count) throws IOException {
			if (count < 0 || count > MAX_NBT_BYTES) throw new IOException("NBT byte limit exceeded");
			input.skipNBytes(count);
		}
	}

	private static final class NbtBudget {
		private int nodes = MAX_NBT_NODES;
		private int bytes = MAX_NBT_BYTES;

		private boolean visit() {
			return nodes-- > 0;
		}

		private boolean consumeBytes(int amount) {
			if (amount < 0 || amount > bytes) return false;
			bytes -= amount;
			return true;
		}
	}

	/** Stops a compressed inventory payload from expanding without bound while it is parsed. */
	private static final class LimitedInputStream extends FilterInputStream {
		private int remaining;

		private LimitedInputStream(InputStream input, int limit) {
			super(input);
			remaining = limit;
		}

		@Override
		public int read() throws IOException {
			if (remaining <= 0) throw new IOException("Input limit exceeded");
			int value = super.read();
			if (value >= 0) remaining--;
			return value;
		}

		@Override
		public int read(byte[] bytes, int offset, int length) throws IOException {
			if (remaining <= 0) throw new IOException("Input limit exceeded");
			int allowed = Math.min(length, remaining);
			int read = super.read(bytes, offset, allowed);
			if (read > 0) remaining -= read;
			return read;
		}
	}
}
