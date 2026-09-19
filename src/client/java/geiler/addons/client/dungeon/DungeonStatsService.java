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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
	/** Current Odin API base; the service currently redirects profile traffic to its legacy host. */
	private static final String API_BASE = "https://api.odtheking.com/hypixel/";
	private static final String UUID_BASE = "https://api.minecraftservices.com/minecraft/profile/lookup/name/";
	private static final long CACHE_TTL_MILLIS = 5 * 60 * 1000L;
	private static final int MAX_CACHE_ENTRIES = 512;
	private static final Duration TIMEOUT = Duration.ofSeconds(10);
	/** Profile responses are untrusted remote input; never let one consume arbitrary memory. */
	private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
	private static final int MAX_JSON_DEPTH = 32;
	private static final int MAX_JSON_NODES = 50_000;
	private static final int MAX_ITEM_TEXT_CHARS = 64 * 1024;
	private static final int MAX_COMPRESSED_VALUE_CHARS = 256 * 1024;
	private static final int MAX_NBT_BYTES = 2 * 1024 * 1024;
	private static final int MAX_NBT_DEPTH = 32;
	private static final int MAX_NBT_NODES = 20_000;
	private static final int MAX_NBT_STRING_CHARS = 8 * 1024;
	private static final int MAX_GEAR_TOOLTIP_ITEMS = 64;
	private static final int MAX_GEAR_TOOLTIP_LINES = 64;
	private static final int MAX_GEAR_TOOLTIP_LINE_CHARS = 2_048;
	private static final int MAX_GEAR_TOOLTIP_CHARS = 64 * 1024;
	private static final int MAX_GOLDEN_DRAGON_PETS = 32;
	private static final long[] DUNGEON_XP_LEVELS = {
		0, 50, 125, 235, 395, 625, 955, 1425, 2095, 3045, 4385,
		6275, 8940, 12700, 17960, 25340, 35640, 50040, 70040, 97640,
		135640, 188140, 259640, 356640, 488640, 668640, 911640, 1239640,
		1684640, 2284640, 3084640, 4149640, 5559640, 7459640, 9959640,
		13259640, 17559640, 23159640, 30359640, 39559640, 51559640,
		66559640, 85559640, 109559640, 139559640, 177559640, 225559640,
		285559640, 360559640, 453559640, 569809640
	};
	/** Keys that identify an actual item entry inside one of the supported inventory containers. */
	private static final Set<String> ITEM_ID_KEYS = Set.of(
		"id", "item_id", "itemid", "item_name", "itemname", "internalname", "internal_name",
		"displayname", "display_name", "tag", "nbt");
	private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, r -> {
		Thread thread = new Thread(r, "GeilerAddons dungeon stats");
		thread.setDaemon(true);
		return thread;
	});
	/** Network requests started after UUID resolution must not block the profile worker threads. */
	private static final ExecutorService HTTP_EXECUTOR = Executors.newFixedThreadPool(4, r -> {
		Thread thread = new Thread(r, "GeilerAddons dungeon stats HTTP");
		thread.setDaemon(true);
		return thread;
	});
	private static final HttpClient HTTP = HttpClient.newBuilder()
		.connectTimeout(TIMEOUT)
		.followRedirects(HttpClient.Redirect.NEVER)
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
		pruneCache(System.currentTimeMillis());
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

	/** Cancels profile work when the client is stopping; no callback may outlive the session. */
	public static void close() {
		for (CompletableFuture<Result> future : IN_FLIGHT.values()) future.cancel(true);
		IN_FLIGHT.clear();
		CACHE.clear();
		EXECUTOR.shutdownNow();
		HTTP_EXECUTOR.shutdownNow();
	}

	private static void pruneCache(long now) {
		for (Map.Entry<String, CacheEntry> entry : CACHE.entrySet()) {
			if (now - entry.getValue().timestamp >= CACHE_TTL_MILLIS) {
				CACHE.remove(entry.getKey(), entry.getValue());
			}
		}
		if (CACHE.size() <= MAX_CACHE_ENTRIES) return;
		// The cache is keyed by player name rather than access order. Removing arbitrary survivors is
		// preferable to retaining unbounded remote data; a subsequent request simply refreshes it.
		int excess = CACHE.size() - MAX_CACHE_ENTRIES;
		for (String key : CACHE.keySet()) {
			if (excess-- <= 0) break;
			CACHE.remove(key);
		}
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
			// The profile and dedicated secrets endpoint are independent. Fetch them together so a
			// party scan is not serialized behind two large profile responses per player.
			CompletableFuture<JsonObject> profileFuture = CompletableFuture.supplyAsync(
				() -> getJsonUnchecked(API_BASE + "get/" + id), HTTP_EXECUTOR);
			CompletableFuture<JsonElement> secretsFuture = CompletableFuture.supplyAsync(
				() -> getJsonElementUnchecked(API_BASE + "secrets/" + id), HTTP_EXECUTOR);
			JsonObject profile = profileFuture.join();
			Long endpointSecrets = readLong(secretsFuture);
			JsonObject member = profileMember(profile, id);
			if (member == null) {
				GeilerAddons.LOGGER.debug("[Dungeon Stats] No profile member data for {}", name);
				return Result.failure("Profile data is unavailable");
			}

			DungeonStats stats = parseStats(returnedName == null ? name : returnedName, uuid, profile, member,
				endpointSecrets);
			if (stats.cacheable()) {
				CACHE.put(name.toLowerCase(Locale.ROOT), new CacheEntry(stats, System.currentTimeMillis()));
				pruneCache(System.currentTimeMillis());
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

	private static Long readLong(CompletableFuture<JsonElement> future) {
		try {
			JsonElement value = future.join();
			if (value == null || !value.isJsonPrimitive()) return null;
			return value.getAsLong();
		} catch (RuntimeException exception) {
			GeilerAddons.LOGGER.debug("[Dungeon Stats] Dedicated secrets lookup failed: {}", errorText(exception));
			return null;
		}
	}

	private static JsonObject getJsonUnchecked(String url) {
		try {
			return getJson(url);
		} catch (IOException | InterruptedException exception) {
			if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
			throw new RuntimeException(exception);
		}
	}

	private static JsonElement getJsonElementUnchecked(String url) {
		try {
			return getJsonElement(url);
		} catch (IOException | InterruptedException exception) {
			if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
			throw new RuntimeException(exception);
		}
	}

	private static JsonObject getJson(String url) throws IOException, InterruptedException {
		JsonElement parsed = getJsonElement(url);
		if (!parsed.isJsonObject()) throw new IOException("Response was not an object");
		return parsed.getAsJsonObject();
	}

	private static JsonElement getJsonElement(String url) throws IOException, InterruptedException {
		URI current = URI.create(url);
		if (!allowedUri(current)) throw new IOException("Refusing untrusted endpoint " + current.getHost());
		HttpResponse<InputStream> response;
		int redirects = 0;
		while (true) {
			HttpRequest request = HttpRequest.newBuilder(current).timeout(TIMEOUT)
				.header("User-Agent", "GeilerAddons")
				.header("Accept", "application/json")
				.GET().build();
			response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
			int status = response.statusCode();
			if (status < 300 || status >= 400) break;
			if (redirects++ >= 1) {
				try (InputStream ignored = response.body()) {
					// Close the response before rejecting an unexpected redirect chain.
				}
				throw new IOException("Too many redirects");
			}
			String location = response.headers().firstValue("Location").orElse(null);
			try (InputStream ignored = response.body()) {
				// The redirect body is not part of the profile contract.
			}
			if (location == null) throw new IOException("Redirect had no location");
			URI next = current.resolve(location);
			if (!allowedUri(next)) throw new IOException("Refusing redirected endpoint " + next.getHost());
			current = next;
		}
		if (!allowedUri(response.uri())) {
			try (InputStream ignored = response.body()) {
				// Close a response that should never have been accepted.
			}
			throw new IOException("Refusing redirected endpoint " + response.uri().getHost());
		}
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
		return parsed;
	}

	private static boolean allowedUri(URI uri) {
		if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) return false;
		String host = uri.getHost();
		return "api.minecraftservices.com".equalsIgnoreCase(host)
			|| "api.odtheking.com".equalsIgnoreCase(host)
			|| "hypixel.odtheking.com".equalsIgnoreCase(host);
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

	private static DungeonStats parseStats(String name, UUID uuid, JsonObject root, JsonObject member,
		Long endpointSecrets) {
		JsonObject dungeons = object(member, "dungeons");
		JsonObject types = object(dungeons, "dungeon_types");
		JsonObject catacombs = object(types, "catacombs");
		JsonObject master = object(types, "master_catacombs");
		EnumSet<DungeonStats.DataField> available = EnumSet.noneOf(DungeonStats.DataField.class);
		boolean cataExperienceKnown = hasNumber(catacombs, "experience");
		long cataExperience = Math.max(0, number(catacombs, "experience"));
		int cata = level(cataExperience, 50);
		if (cataExperienceKnown) available.add(DungeonStats.DataField.CATACOMBS_LEVEL);

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

		NumberValue profileSecretValue = findNumber(dungeons, "total_secrets", "totalSecrets", "secrets", "secrets_found");
		NumberValue secretValue = endpointSecrets == null
			? profileSecretValue : new NumberValue(Math.max(0, endpointSecrets), true);
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
		List<DungeonStats.ItemDetails> itemDetails = collectGearDetails(member);
		for (DungeonStats.ItemDetails item : itemDetails) gear.add(item.gear());
		if (containsItemIdentifier(allText, "terminator")) gear.add(DungeonStats.Gear.TERMINATOR);
		if (containsItemIdentifier(allText, "hyperion")) gear.add(DungeonStats.Gear.HYPERION);
		if (containsItemIdentifier(allText, "golden_dragon") || containsItemIdentifier(allText, "golden dragon")) {
			gear.add(DungeonStats.Gear.GOLDEN_DRAGON);
		}
		if (containsPetIdentifier(member, "golden_dragon")) gear.add(DungeonStats.Gear.GOLDEN_DRAGON);

		Map<DungeonFloor, Long> pbs = new EnumMap<>(DungeonFloor.class);
		if (hasPersonalBestData(catacombs) || hasPersonalBestData(master)) {
			available.add(DungeonStats.DataField.PERSONAL_BESTS);
		}
		readPbs(pbs, catacombs, false);
		readPbs(pbs, master, true);
		// The text collector is only a name extractor. Gate its result with validated item-shaped
		// inventory evidence so an API error/status string containing "terminator" cannot authorize a
		// gear check and turn an incomplete profile into a false kick.
		boolean inventoryKnown = hasInventoryData(member);
		boolean petKnown = hasPetData(member);
		List<DungeonStats.GoldenDragonPet> goldenDragonPets = collectGoldenDragonDetails(member);
		EnumSet<DungeonStats.Gear> knownGear = EnumSet.noneOf(DungeonStats.Gear.class);
		if (inventoryKnown) {
			knownGear.add(DungeonStats.Gear.TERMINATOR);
			knownGear.add(DungeonStats.Gear.HYPERION);
		}
		if (petKnown) knownGear.add(DungeonStats.Gear.GOLDEN_DRAGON);
		if (knownGear.size() == DungeonStats.Gear.values().length) available.add(DungeonStats.DataField.GEAR);
		DungeonClass selectedClass = DungeonClass.parse(firstString(dungeons, "selected_dungeon_class", "selectedClass"));
		if (selectedClass != null) available.add(DungeonStats.DataField.SELECTED_CLASS);
		return new DungeonStats(name, uuid, cata, selectedClass, classLevels, classAverage, secrets, runs,
			magicalPower, bank, bankKnown, knownGear, gear, pbs, available, cataExperience,
			cataExperienceKnown, itemDetails, goldenDragonPets);
	}

	/** Package boundary for offline fixtures; production requests enter through {@link #fetch}. */
	static DungeonStats parseForChecks(String name, UUID uuid, JsonObject root, JsonObject member,
		Long endpointSecrets) {
		return parseStats(name, uuid, root, member, endpointSecrets);
	}

	private static List<DungeonStats.ItemDetails> collectGearDetails(JsonObject member) {
		List<DungeonStats.ItemDetails> output = new ArrayList<>();
		collectInventoryContainers(member, 0, new JsonBudget(MAX_JSON_NODES),
			new TooltipBudget(), output);
		return List.copyOf(output);
	}

	private static void collectInventoryContainers(JsonElement element, int depth, JsonBudget jsonBudget,
		TooltipBudget tooltipBudget, List<DungeonStats.ItemDetails> output) {
		if (element == null || element.isJsonNull() || depth > MAX_JSON_DEPTH || !jsonBudget.visit()) return;
		if (element.isJsonArray()) {
			for (JsonElement child : element.getAsJsonArray()) {
				collectInventoryContainers(child, depth + 1, jsonBudget, tooltipBudget, output);
			}
			return;
		}
		if (!element.isJsonObject()) return;
		for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
			JsonElement value = entry.getValue();
			if (isInventoryKey(entry.getKey())) {
				collectInventoryDetails(value, inventoryLabel(entry.getKey()), depth + 1,
					new JsonBudget(MAX_JSON_NODES), tooltipBudget, output);
			} else {
				collectInventoryContainers(value, depth + 1, jsonBudget, tooltipBudget, output);
			}
			if (tooltipBudget.exhausted()) return;
		}
	}

	private static void collectInventoryDetails(JsonElement element, String source, int depth,
		JsonBudget jsonBudget, TooltipBudget tooltipBudget, List<DungeonStats.ItemDetails> output) {
		if (element == null || element.isJsonNull() || depth > MAX_JSON_DEPTH || !jsonBudget.visit()
			|| tooltipBudget.exhausted()) return;
		if (element.isJsonPrimitive()) {
			if (!element.getAsJsonPrimitive().isString()) return;
			String value = element.getAsString();
			if (looksLikeCompressedNbt(value)) {
				readGearDetailsFromNbt(value, source, tooltipBudget, output);
			} else {
				DungeonStats.Gear gear = gearForIdentifier(value);
				if (gear != null && tooltipBudget.takeItem()) {
					output.add(new DungeonStats.ItemDetails(gear, value, gearDisplayName(gear), source, List.of()));
				}
			}
			return;
		}
		if (element.isJsonArray()) {
			for (JsonElement child : element.getAsJsonArray()) {
				collectInventoryDetails(child, source, depth + 1, jsonBudget, tooltipBudget, output);
				if (tooltipBudget.exhausted()) return;
			}
			return;
		}
		if (!element.isJsonObject()) return;

		JsonObject object = element.getAsJsonObject();
		if (hasGearIdentityInItemObject(object)) {
			DungeonStats.ItemDetails item = itemDetailsFromObject(jsonValue(object, 0,
				new JsonBudget(MAX_JSON_NODES)), source, tooltipBudget);
			if (item != null) {
				output.add(item);
				return;
			}
		}
		for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
			JsonElement value = entry.getValue();
			String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
			if (key.equals("data") && value.isJsonPrimitive()
				&& value.getAsJsonPrimitive().isString() && looksLikeCompressedNbt(value.getAsString())) {
				readGearDetailsFromNbt(value.getAsString(), source, tooltipBudget, output);
			} else if (isInventoryKey(key)) {
				collectInventoryDetails(value, inventoryLabel(key), depth + 1,
					jsonBudget, tooltipBudget, output);
			} else {
				collectInventoryDetails(value, source, depth + 1, jsonBudget, tooltipBudget, output);
			}
			if (tooltipBudget.exhausted()) return;
		}
	}

	/** Avoid converting every ordinary inventory item into a second object graph. */
	private static boolean hasGearIdentityInItemObject(JsonObject object) {
		for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
			String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
			JsonElement value = entry.getValue();
			if (isItemIdentityKey(key) && value != null && value.isJsonPrimitive()
				&& value.getAsJsonPrimitive().isString()
				&& gearForIdentifier(value.getAsString()) != null) return true;
			if (isItemMetadataKey(key) || key.equals("components")) {
				if (containsGearIdentity(value, 0, new JsonBudget(2_000))) return true;
			}
		}
		return false;
	}

	private static boolean containsGearIdentity(JsonElement element, int depth, JsonBudget budget) {
		if (element == null || element.isJsonNull() || depth > MAX_JSON_DEPTH || !budget.visit()) return false;
		if (element.isJsonArray()) {
			for (JsonElement child : element.getAsJsonArray()) {
				if (containsGearIdentity(child, depth + 1, budget)) return true;
			}
			return false;
		}
		if (!element.isJsonObject()) return false;
		for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
			String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
			JsonElement value = entry.getValue();
			if (isItemIdentityKey(key) && value != null && value.isJsonPrimitive()
				&& value.getAsJsonPrimitive().isString()
				&& gearForIdentifier(value.getAsString()) != null) return true;
			if (containsGearIdentity(value, depth + 1, budget)) return true;
		}
		return false;
	}

	private static Object jsonValue(JsonElement element, int depth, JsonBudget budget) {
		if (element == null || element.isJsonNull() || depth > MAX_JSON_DEPTH || !budget.visit()) return null;
		if (element.isJsonPrimitive()) {
			if (element.getAsJsonPrimitive().isString()) return element.getAsString();
			if (element.getAsJsonPrimitive().isBoolean()) return element.getAsBoolean();
			try { return element.getAsNumber(); } catch (RuntimeException ignored) { return null; }
		}
		if (element.isJsonArray()) {
			List<Object> values = new ArrayList<>();
			for (JsonElement child : element.getAsJsonArray()) {
				values.add(jsonValue(child, depth + 1, budget));
			}
			return values;
		}
		if (element.isJsonObject()) {
			Map<String, Object> values = new LinkedHashMap<>();
			for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
				values.put(entry.getKey(), jsonValue(entry.getValue(), depth + 1, budget));
			}
			return values;
		}
		return null;
	}

	private static boolean looksLikeCompressedNbt(String value) {
		return value != null && value.length() > 32 && value.length() <= MAX_COMPRESSED_VALUE_CHARS
			&& value.matches("[A-Za-z0-9+/=]+");
	}

	private static void readGearDetailsFromNbt(String value, String source,
		TooltipBudget tooltipBudget, List<DungeonStats.ItemDetails> output) {
		try {
			byte[] bytes = Base64.getDecoder().decode(value);
			if (bytes.length == 0 || bytes.length > MAX_NBT_BYTES) return;
			try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes));
				DataInputStream input = new DataInputStream(new LimitedInputStream(gzip, MAX_NBT_BYTES))) {
				Object root = NbtTreeReader.read(input);
				collectGearItems(root, source, tooltipBudget, output, 0);
			}
		} catch (Exception ignored) {
			// Invalid optional inventory details do not discard other profile statistics.
		}
	}

	private static void collectGearItems(Object value, String source, TooltipBudget tooltipBudget,
		List<DungeonStats.ItemDetails> output, int depth) {
		if (value == null || depth > MAX_NBT_DEPTH || tooltipBudget.exhausted()) return;
		if (value instanceof Map<?, ?> map) {
			if (isNbtItemObject(map)) {
				DungeonStats.ItemDetails item = itemDetailsFromObject(map, source, tooltipBudget);
				if (item != null) {
					output.add(item);
					return;
				}
			}
			for (Object child : map.values()) {
				collectGearItems(child, source, tooltipBudget, output, depth + 1);
				if (tooltipBudget.exhausted()) return;
			}
		} else if (value instanceof List<?> list) {
			for (Object child : list) {
				collectGearItems(child, source, tooltipBudget, output, depth + 1);
				if (tooltipBudget.exhausted()) return;
			}
		}
	}

	private static boolean isNbtItemObject(Map<?, ?> map) {
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
			if (isItemIdentityKey(key) && gearForIdentifier(profileText(entry.getValue())) != null) return true;
			if (isItemMetadataKey(key) || key.equals("components")) {
				if (findGearIdentity(entry.getValue(), 0) != null) return true;
			}
		}
		return false;
	}

	private static DungeonStats.ItemDetails itemDetailsFromObject(Object value, String source,
		TooltipBudget tooltipBudget) {
		if (!(value instanceof Map<?, ?> map)) return null;
		DungeonStats.Gear gear = findGearIdentity(map, 0);
		if (gear == null) return null;
		String identifier = findGearIdentifier(map, 0);
		String displayName = findDisplayName(map);
		if (displayName.isBlank()) displayName = gearDisplayName(gear);
		if (!tooltipBudget.takeItem()) return null;
		displayName = tooltipBudget.take(displayName);
		identifier = tooltipBudget.take(identifier);
		List<String> lore = collectLore(map, tooltipBudget);
		return new DungeonStats.ItemDetails(gear, identifier, displayName, source, lore);
	}

	private static DungeonStats.Gear findGearIdentity(Object value, int depth) {
		if (value == null || depth > MAX_NBT_DEPTH) return null;
		if (value instanceof Map<?, ?> map) {
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
				if (isItemIdentityKey(key)) {
					DungeonStats.Gear gear = gearForIdentifier(profileText(entry.getValue()));
					if (gear != null) return gear;
				}
				if (!key.equals("lore") && !key.equals("minecraft:lore")) {
					DungeonStats.Gear nested = findGearIdentity(entry.getValue(), depth + 1);
					if (nested != null) return nested;
				}
			}
		} else if (value instanceof List<?> list) {
			for (Object child : list) {
				DungeonStats.Gear nested = findGearIdentity(child, depth + 1);
				if (nested != null) return nested;
			}
		}
		return null;
	}

	private static String findGearIdentifier(Object value, int depth) {
		if (value == null || depth > MAX_NBT_DEPTH) return "";
		if (value instanceof Map<?, ?> map) {
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
				if (isItemIdentityKey(key) && gearForIdentifier(profileText(entry.getValue())) != null) {
					return profileText(entry.getValue());
				}
				if (!key.equals("lore") && !key.equals("minecraft:lore")) {
					String nested = findGearIdentifier(entry.getValue(), depth + 1);
					if (!nested.isBlank()) return nested;
				}
			}
		} else if (value instanceof List<?> list) {
			for (Object child : list) {
				String nested = findGearIdentifier(child, depth + 1);
				if (!nested.isBlank()) return nested;
			}
		}
		return "";
	}

	private static String findDisplayName(Object value) {
		Object tag = mapValue(value, "tag", "nbt");
		Object display = mapValue(tag, "display");
		String result = profileText(mapValue(display, "Name", "name"));
		if (!result.isBlank()) return result;
		Object components = mapValue(value, "components");
		result = profileText(mapValue(components, "minecraft:custom_name", "custom_name", "name"));
		if (!result.isBlank()) return result;
		return findNamedText(value, Set.of("display_name", "displayname", "custom_name", "name"), 0);
	}

	private static String findNamedText(Object value, Set<String> keys, int depth) {
		if (value == null || depth > MAX_NBT_DEPTH) return "";
		if (value instanceof Map<?, ?> map) {
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
				if (keys.contains(key)) {
					String found = profileText(entry.getValue());
					if (!found.isBlank()) return found;
				}
			}
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
				if (!key.equals("lore") && !key.equals("minecraft:lore")) {
					String found = findNamedText(entry.getValue(), keys, depth + 1);
					if (!found.isBlank()) return found;
				}
			}
		} else if (value instanceof List<?> list) {
			for (Object child : list) {
				String found = findNamedText(child, keys, depth + 1);
				if (!found.isBlank()) return found;
			}
		}
		return "";
	}

	private static List<String> collectLore(Object value, TooltipBudget tooltipBudget) {
		List<String> lines = new ArrayList<>();
		collectLore(value, tooltipBudget, lines, 0);
		return List.copyOf(lines);
	}

	private static void collectLore(Object value, TooltipBudget tooltipBudget, List<String> lines, int depth) {
		if (value == null || depth > MAX_NBT_DEPTH || lines.size() >= MAX_GEAR_TOOLTIP_LINES
			|| tooltipBudget.exhausted()) return;
		if (value instanceof Map<?, ?> map) {
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
				if (key.equals("lore") || key.equals("minecraft:lore")) {
					Object loreValue = entry.getValue();
					if (loreValue instanceof List<?> list) {
						for (Object line : list) {
							if (lines.size() >= MAX_GEAR_TOOLTIP_LINES || tooltipBudget.exhausted()) break;
							String text = profileText(line);
							if (!text.isBlank()) lines.add(tooltipBudget.take(text));
						}
					} else {
						String text = profileText(loreValue);
						if (!text.isBlank()) lines.add(tooltipBudget.take(text));
					}
				} else {
					collectLore(entry.getValue(), tooltipBudget, lines, depth + 1);
				}
				if (lines.size() >= MAX_GEAR_TOOLTIP_LINES || tooltipBudget.exhausted()) return;
			}
		} else if (value instanceof List<?> list) {
			for (Object child : list) {
				collectLore(child, tooltipBudget, lines, depth + 1);
				if (lines.size() >= MAX_GEAR_TOOLTIP_LINES || tooltipBudget.exhausted()) return;
			}
		}
	}

	private static Object mapValue(Object value, String... keys) {
		if (!(value instanceof Map<?, ?> map)) return null;
		for (String wanted : keys) {
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				if (wanted.equalsIgnoreCase(String.valueOf(entry.getKey()))) return entry.getValue();
			}
		}
		return null;
	}

	private static String profileText(Object value) {
		if (value == null) return "";
		if (value instanceof String text) return text;
		if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
		if (value instanceof Map<?, ?> || value instanceof List<?>) return new com.google.gson.Gson().toJson(value);
		return "";
	}

	private static DungeonStats.Gear gearForIdentifier(String value) {
		if (value == null) return null;
		String normalized = value.trim().toLowerCase(Locale.ROOT)
			.replace("minecraft:", "").replace(' ', '_');
		return switch (normalized) {
			case "terminator" -> DungeonStats.Gear.TERMINATOR;
			case "hyperion" -> DungeonStats.Gear.HYPERION;
			case "golden_dragon" -> DungeonStats.Gear.GOLDEN_DRAGON;
			default -> null;
		};
	}

	private static String gearDisplayName(DungeonStats.Gear gear) {
		return switch (gear) {
			case TERMINATOR -> "Terminator";
			case HYPERION -> "Hyperion";
			case GOLDEN_DRAGON -> "Golden Dragon";
		};
	}

	private static String inventoryLabel(String key) {
		return switch (key == null ? "" : key.toLowerCase(Locale.ROOT)) {
			case "inv_contents", "inventory", "items", "item_data" -> "Inventory";
			case "ender_chest_contents" -> "Ender Chest";
			case "armor_contents" -> "Armor";
			case "talisman_bag" -> "Accessory Bag";
			case "wardrobe_contents" -> "Wardrobe";
			case "equipment_contents" -> "Equipment";
			default -> key == null ? "Inventory" : key.replace('_', ' ');
		};
	}

	private static List<DungeonStats.GoldenDragonPet> collectGoldenDragonDetails(JsonObject member) {
		JsonObject petsData = object(member, "pets_data");
		JsonElement pets = petsData == null ? (member == null ? null : member.get("pets")) : petsData.get("pets");
		List<DungeonStats.GoldenDragonPet> output = new ArrayList<>();
		collectGoldenDragonDetails(pets, output, 0, new JsonBudget(MAX_JSON_NODES), new TooltipBudget());
		return List.copyOf(output);
	}

	private static void collectGoldenDragonDetails(JsonElement element,
		List<DungeonStats.GoldenDragonPet> output, int depth, JsonBudget budget, TooltipBudget detailBudget) {
		if (element == null || element.isJsonNull() || depth > MAX_JSON_DEPTH || !budget.visit()
			|| output.size() >= MAX_GOLDEN_DRAGON_PETS || detailBudget.exhausted()) return;
		if (element.isJsonArray()) {
			for (JsonElement child : element.getAsJsonArray()) {
				collectGoldenDragonDetails(child, output, depth + 1, budget, detailBudget);
				if (output.size() >= MAX_GOLDEN_DRAGON_PETS || detailBudget.exhausted()) return;
			}
			return;
		}
		if (!element.isJsonObject()) return;
		JsonObject object = element.getAsJsonObject();
		if (isGoldenDragonPet(object)) {
			if (!detailBudget.takeItem()) return;
			output.add(new DungeonStats.GoldenDragonPet(
				detailBudget.take(firstPrimitiveText(object, "tier", "rarity")),
				detailBudget.take(firstPrimitiveText(object, "level", "lvl")),
				detailBudget.take(firstPrimitiveText(object, "exp", "experience", "xp")),
				detailBudget.take(firstPrimitiveText(object, "heldItem", "held_item", "helditem")),
				detailBudget.take(firstPrimitiveText(object, "skin")), primitiveBoolean(object, "active")));
			return;
		}
		for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
			collectGoldenDragonDetails(entry.getValue(), output, depth + 1, budget, detailBudget);
			if (output.size() >= MAX_GOLDEN_DRAGON_PETS || detailBudget.exhausted()) return;
		}
	}

	private static boolean isGoldenDragonPet(JsonObject object) {
		if (object == null) return false;
		for (String key : new String[] {"type", "pet_type", "id", "internal_name"}) {
			String value = firstPrimitiveText(object, key);
			if (normalizeIdentifier(value).equals("golden_dragon")) return true;
		}
		return false;
	}

	private static String firstPrimitiveText(JsonObject object, String... keys) {
		if (object == null) return "";
		for (String key : keys) {
			for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
				if (key.equalsIgnoreCase(entry.getKey()) && entry.getValue().isJsonPrimitive()
					&& !entry.getValue().getAsJsonPrimitive().isBoolean()) {
					return entry.getValue().getAsString();
				}
			}
		}
		return "";
	}

	private static Boolean primitiveBoolean(JsonObject object, String key) {
		if (object == null) return null;
		for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
			if (!key.equalsIgnoreCase(entry.getKey()) || !entry.getValue().isJsonPrimitive()
				|| !entry.getValue().getAsJsonPrimitive().isBoolean()) continue;
			return entry.getValue().getAsBoolean();
		}
		return null;
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

	private static boolean hasPersonalBestData(JsonObject type) {
		return object(type, "fastest_time_s_plus") != null || object(type, "fastestTimeSPlus") != null;
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

	public record CataProgress(int level, long totalExperience, long experienceIntoLevel,
		long experienceForLevel, long overflowExperience, boolean capped) {
	}

	/** Breaks down the API's raw Catacombs XP without discarding level-50 overflow. */
	public static CataProgress catacombsProgress(long totalExperience) {
		long xp = Math.max(0, totalExperience);
		int cataLevel = level(xp, 50);
		if (cataLevel >= 50) {
			return new CataProgress(50, xp, 0, 0,
				Math.max(0, xp - DUNGEON_XP_LEVELS[50]), true);
		}
		long start = DUNGEON_XP_LEVELS[cataLevel];
		long needed = DUNGEON_XP_LEVELS[cataLevel + 1] - start;
		return new CataProgress(cataLevel, xp, xp - start, needed, 0, false);
	}

	private static int level(long xp, int cap) {
		int result = 0;
		for (int i = 0; i < DUNGEON_XP_LEVELS.length && i <= cap; i++) {
			if (xp >= DUNGEON_XP_LEVELS[i]) result = i;
			else break;
		}
		return result;
	}

	private static String collectItemText(JsonElement element) {
		StringBuilder out = new StringBuilder();
		collectInventoryIdentifiers(element, out, 0, new JsonBudget(MAX_JSON_NODES), false);
		return out.toString().toLowerCase(Locale.ROOT);
	}

	/** Walks only item identity fields; lore and status text are deliberately ignored. */
	private static void collectInventoryIdentifiers(JsonElement element, StringBuilder out, int depth,
		JsonBudget budget, boolean insideInventory) {
		if (element == null || element.isJsonNull() || depth > MAX_JSON_DEPTH || !budget.visit()) return;
		if (element.isJsonPrimitive()) {
			if (!insideInventory || !element.getAsJsonPrimitive().isString()) return;
			String value = element.getAsString();
			if (likelyItemIdentifier(value)) appendLimited(out, value);
			else if (value.length() > 32 && value.length() <= MAX_COMPRESSED_VALUE_CHARS
				&& value.matches("[A-Za-z0-9+/=]+")) searchCompressedNbt(value, out);
			return;
		}
		if (element.isJsonArray()) {
			for (JsonElement child : element.getAsJsonArray()) {
				collectInventoryIdentifiers(child, out, depth + 1, budget, insideInventory);
				if (out.length() >= MAX_ITEM_TEXT_CHARS) return;
			}
			return;
		}
		if (!element.isJsonObject()) return;
		for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
			String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
			JsonElement value = entry.getValue();
			if (isInventoryKey(key)) {
				collectInventoryIdentifiers(value, out, depth + 1, budget, true);
			} else if (insideInventory && isItemIdentityKey(key)) {
				collectIdentityValue(value, out, depth + 1, budget);
			} else if (insideInventory && key.equals("data")) {
				// Odin wraps Hypixel inventory fields as {type, data}; data is the compressed NBT string.
				collectIdentityValue(value, out, depth + 1, budget);
			} else if (insideInventory && isItemMetadataKey(key)) {
				collectInventoryIdentifiers(value, out, depth + 1, budget, true);
			} else if (insideInventory && (value.isJsonObject() || value.isJsonArray())) {
				// Nested item wrappers are common in profile payloads, but primitive lore values are not.
				collectInventoryIdentifiers(value, out, depth + 1, budget, true);
			}
			if (out.length() >= MAX_ITEM_TEXT_CHARS) return;
		}
	}

	private static void collectIdentityValue(JsonElement value, StringBuilder out, int depth,
		JsonBudget budget) {
		if (value == null || value.isJsonNull()) return;
		if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
			String text = value.getAsString();
			if (likelyItemIdentifier(text)) appendLimited(out, text);
			else if (text.length() > 32 && text.length() <= MAX_COMPRESSED_VALUE_CHARS
				&& text.matches("[A-Za-z0-9+/=]+")) searchCompressedNbt(text, out);
			return;
		}
		collectInventoryIdentifiers(value, out, depth, budget, true);
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

	private static boolean isItemIdentityKey(String key) {
		return key.equals("id") || key.equals("item_id") || key.equals("itemid")
			|| key.equals("item_name") || key.equals("itemname") || key.equals("internalname")
			|| key.equals("internal_name") || key.equals("displayname") || key.equals("display_name");
	}

	private static boolean isItemMetadataKey(String key) {
		return key.equals("tag") || key.equals("nbt") || key.equals("extra_attributes")
			|| key.equals("extraattributes");
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

	/** Package boundary for the offline parser checks; AutoKick uses the field set on DungeonStats. */
	static boolean hasValidatedInventoryData(JsonElement element) {
		return hasInventoryData(element);
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
		return inventoryValueIsReadable(value, 0, new JsonBudget(MAX_JSON_NODES), true);
	}

	private static boolean inventoryValueIsReadable(JsonElement value, int depth, JsonBudget budget,
		boolean allowPrimitive) {
		if (value == null || value.isJsonNull()) return false;
		if (depth > MAX_JSON_DEPTH || !budget.visit()) return false;
		if (value.isJsonObject()) {
			if (isItemObject(value.getAsJsonObject())) return true;
			for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
				String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
				JsonElement child = entry.getValue();
				if (isItemIdentityKey(key) && identityValueIsReadable(child, depth + 1, budget)) return true;
				if (key.equals("data") && identityValueIsReadable(child, depth + 1, budget)) return true;
				if ((isItemMetadataKey(key) || child.isJsonObject() || child.isJsonArray())
					&& inventoryValueIsReadable(child, depth + 1, budget, false)) return true;
			}
			return false;
		}
		if (value.isJsonArray()) {
			if (value.getAsJsonArray().isEmpty()) return false;
			for (JsonElement child : value.getAsJsonArray()) {
				if (inventoryValueIsReadable(child, depth + 1, budget, allowPrimitive)) return true;
			}
			return false;
		}
		if (!allowPrimitive || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return false;
		String text = value.getAsString();
		if (text.isBlank()) return false;
		if (text.length() > MAX_COMPRESSED_VALUE_CHARS) return false;
		if (text.length() > 32 && text.matches("[A-Za-z0-9+/=]+")) {
			return searchCompressedNbtReadable(text, new StringBuilder());
		}
		return likelyItemIdentifier(text);
	}

	/**
	 * A container is only complete when it contains an item-shaped object or a recognizable item
	 * identifier. Arbitrary status/error strings are deliberately not enough to authorize gear
	 * enforcement in AutoKick.
	 */
	private static boolean isItemObject(JsonObject object) {
		for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
			String key = entry.getKey().toLowerCase(Locale.ROOT);
			if (!ITEM_ID_KEYS.contains(key)) continue;
			JsonElement value = entry.getValue();
			if (value == null || value.isJsonNull()) continue;
			if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
				if (isItemIdentityKey(key) && likelyItemIdentifier(value.getAsString())) return true;
			} else if (key.equals("tag") || key.equals("nbt")) {
				if (inventoryValueIsReadable(value)) return true;
			}
		}
		return false;
	}

	private static boolean identityValueIsReadable(JsonElement value, int depth, JsonBudget budget) {
		if (value == null || value.isJsonNull() || depth > MAX_JSON_DEPTH || !budget.visit()) return false;
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return false;
		String text = value.getAsString();
		if (likelyItemIdentifier(text)) return true;
		return text.length() > 32 && text.length() <= MAX_COMPRESSED_VALUE_CHARS
			&& text.matches("[A-Za-z0-9+/=]+") && searchCompressedNbtReadable(text, new StringBuilder());
	}

	private static boolean likelyItemIdentifier(String value) {
		String normalized = value.trim();
		String lower = normalized.toLowerCase(Locale.ROOT);
		if (lower.equals("terminator") || lower.equals("hyperion")
			|| lower.equals("golden_dragon") || lower.equals("golden dragon")) return true;
		if (normalized.contains(":")) return normalized.matches("[A-Za-z0-9_.-]+:[A-Za-z0-9_./-]+");
		return normalized.matches("[A-Z0-9]+(?:_[A-Z0-9]+)+");
	}

	private static boolean containsItemIdentifier(String text, String identifier) {
		if (text == null || identifier == null || identifier.isBlank()) return false;
		String lower = text.toLowerCase(Locale.ROOT);
		String needle = identifier.toLowerCase(Locale.ROOT);
		int from = 0;
		while ((from = lower.indexOf(needle, from)) >= 0) {
			int end = from + needle.length();
			boolean left = from == 0 || !Character.isLetterOrDigit(lower.charAt(from - 1));
			boolean right = end == lower.length() || !Character.isLetterOrDigit(lower.charAt(end));
			if (left && right) return true;
			from = end;
		}
		return false;
	}

	/** Pet data is a separate API section; it must not be inferred from inventory availability. */
	private static boolean containsPetIdentifier(JsonObject member, String identifier) {
		if (member == null || identifier == null || identifier.isBlank()) return false;
		JsonObject petsData = object(member, "pets_data");
		JsonElement pets = petsData == null ? member.get("pets") : petsData.get("pets");
		return containsPetIdentifier(pets, identifier, 0, new JsonBudget(MAX_JSON_NODES));
	}

	private static boolean containsPetIdentifier(JsonElement element, String identifier, int depth,
		JsonBudget budget) {
		if (element == null || element.isJsonNull() || depth > MAX_JSON_DEPTH || !budget.visit()) return false;
		if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
			return normalizeIdentifier(element.getAsString()).equals(normalizeIdentifier(identifier));
		}
		if (element.isJsonArray()) {
			for (JsonElement child : element.getAsJsonArray()) {
				if (containsPetIdentifier(child, identifier, depth + 1, budget)) return true;
			}
			return false;
		}
		if (!element.isJsonObject()) return false;
		for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
			String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
			if ((key.equals("type") || key.equals("pet_type") || key.equals("id") || key.equals("internal_name"))
				&& entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString()
				&& normalizeIdentifier(entry.getValue().getAsString()).equals(normalizeIdentifier(identifier))) return true;
			if (containsPetIdentifier(entry.getValue(), identifier, depth + 1, budget)) return true;
		}
		return false;
	}

	private static boolean hasPetData(JsonObject member) {
		if (member == null) return false;
		JsonObject petsData = object(member, "pets_data");
		if (petsData != null && array(petsData, "pets") != null) return true;
		return member.has("pets") && member.get("pets").isJsonArray();
	}

	private static String normalizeIdentifier(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
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
		JsonObject completions = object(type, "tier_completions");
		if (completions == null) completions = object(type, "tierCompletions");
		if (completions == null) return false;
		for (int i = 1; i <= 7; i++) {
			if (hasNumber(completions, String.valueOf(i), "floor_" + i)) return true;
		}
		return false;
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

	private static final class TooltipBudget {
		private int remainingItems = MAX_GEAR_TOOLTIP_ITEMS;
		private int remainingChars = MAX_GEAR_TOOLTIP_CHARS;

		private boolean takeItem() {
			if (remainingItems <= 0 || remainingChars <= 0) return false;
			remainingItems--;
			return true;
		}

		private String take(String value) {
			if (value == null || value.isEmpty() || remainingChars <= 0) return "";
			int allowed = Math.min(Math.min(value.length(), MAX_GEAR_TOOLTIP_LINE_CHARS), remainingChars);
			remainingChars -= allowed;
			if (value.length() <= allowed) return value;
			if (allowed <= 1) return value.substring(0, allowed);
			return value.substring(0, allowed - 1) + "…";
		}

		private boolean exhausted() {
			return remainingItems <= 0 || remainingChars <= 0;
		}
	}

	/** Parses bounded NBT values into detached Java data so tooltip formatting stays client-thread-only. */
	private static final class NbtTreeReader {
		static Object read(DataInputStream input) throws IOException {
			NbtBudget budget = new NbtBudget();
			int type = input.readUnsignedByte();
			if (type == 0) return null;
			readString(input, budget);
			return readPayload(input, type, budget, 0);
		}

		private static Object readPayload(DataInputStream input, int type, NbtBudget budget,
			int depth) throws IOException {
			if (depth > MAX_NBT_DEPTH || !budget.visit()) throw new IOException("NBT limits exceeded");
			return switch (type) {
				case 1 -> input.readByte();
				case 2 -> input.readShort();
				case 3 -> input.readInt();
				case 4 -> input.readLong();
				case 5 -> input.readFloat();
				case 6 -> input.readDouble();
				case 7 -> {
					NbtTextReader.skipArray(input, input.readInt(), 1);
					yield null;
				}
				case 8 -> readString(input, budget);
				case 9 -> {
					int childType = input.readUnsignedByte();
					int count = input.readInt();
					if (count < 0 || count > MAX_NBT_NODES || (childType == 0 && count != 0)) {
						throw new IOException("Invalid NBT list length");
					}
					List<Object> list = new ArrayList<>(count);
					for (int i = 0; i < count; i++) {
						list.add(readPayload(input, childType, budget, depth + 1));
					}
					yield list;
				}
				case 10 -> {
					Map<String, Object> compound = new LinkedHashMap<>();
					while (true) {
						int childType = input.readUnsignedByte();
						if (childType == 0) break;
						String key = readString(input, budget);
						compound.put(key, readPayload(input, childType, budget, depth + 1));
					}
					yield compound;
				}
				case 11 -> {
					NbtTextReader.skipArray(input, input.readInt(), 4);
					yield null;
				}
				case 12 -> {
					NbtTextReader.skipArray(input, input.readInt(), 8);
					yield null;
				}
				default -> throw new IOException("Unknown NBT tag " + type);
			};
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
	}

	/** Minimal NBT text walker used only to find item ids in compressed inventory fields. */
	private static final class NbtTextReader {
		static void read(DataInputStream input, StringBuilder out) throws IOException {
			NbtBudget budget = new NbtBudget();
			int type = input.readUnsignedByte();
			if (type == 0) return;
			readString(input, budget);
			readPayload(input, type, out, budget, 0, "");
		}

		private static void readPayload(DataInputStream input, int type, StringBuilder out,
			NbtBudget budget, int depth, String key) throws IOException {
			if (depth > MAX_NBT_DEPTH || !budget.visit()) throw new IOException("NBT limits exceeded");
			switch (type) {
				case 1 -> input.readByte();
				case 2 -> input.readShort();
				case 3 -> input.readInt();
				case 4 -> input.readLong();
				case 5 -> input.readFloat();
				case 6 -> input.readDouble();
				case 7 -> skipBytes(input, input.readInt());
				case 8 -> {
					String value = readString(input, budget);
					String normalizedKey = key == null ? "" : key.toLowerCase(Locale.ROOT);
					if (isItemIdentityKey(normalizedKey) && likelyItemIdentifier(value)) appendLimited(out, value);
				}
				case 9 -> {
					int childType = input.readUnsignedByte();
					int count = input.readInt();
					if (count < 0 || count > MAX_NBT_NODES) throw new IOException("Invalid NBT list length");
					for (int i = 0; i < count; i++) readPayload(input, childType, out, budget, depth + 1, key);
				}
				case 10 -> {
					while (true) {
						int childType = input.readUnsignedByte();
						if (childType == 0) break;
						String childKey = readString(input, budget);
						readPayload(input, childType, out, budget, depth + 1, childKey);
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
