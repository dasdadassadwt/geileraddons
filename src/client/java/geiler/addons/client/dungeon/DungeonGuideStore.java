package geiler.addons.client.dungeon;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import geiler.addons.GeilerAddons;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/** One route file per floor, phase, and route name, with a small manifest for discovery. */
public final class DungeonGuideStore {
	private static final String DEFAULT_NEXT_KEY = "key.keyboard.right.bracket";
	private static final String DEFAULT_PREVIOUS_KEY = "key.keyboard.left.bracket";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path ROOT;
	private static final Path INDEX;
	private static final Path LEGACY;
	private static final List<DungeonGuideNode> NODES = new ArrayList<>();
	private static List<DungeonGuideNode> nodesSnapshot;
	private static final Map<String, String> ACTIVE_ROUTES = new LinkedHashMap<>();
	private static final Map<String, String> ROUTE_NAMES = new LinkedHashMap<>();
	private static final Map<String, TransitionTiming> ROUTE_TIMINGS = new LinkedHashMap<>();
	private static final List<DungeonGuideCustomSegment> CUSTOM_SEGMENTS = new ArrayList<>();
	private static boolean loaded;
	private static boolean dirty;
	private static boolean persistenceBlocked;
	private static int saveDelayTicks;
	private static long mutationVersion;
	private static long guideRevision;
	private static long pendingVersion;
	private static Future<Boolean> pendingSave;
	private static String nextKey = DEFAULT_NEXT_KEY;
	private static String previousKey = DEFAULT_PREVIOUS_KEY;
	static {
		Path configDirectory = null;
		try { configDirectory = FabricLoader.getInstance().getConfigDir(); }
		catch (RuntimeException ignored) { }
		if (configDirectory == null) {
			// Pure offline checks can inspect phase ordering without a bootstrapped Fabric loader.
			// Mark persistence blocked so the harmless fallback paths can never be read or written.
			Path fallback = Path.of(".", ".geileraddons-offline");
			ROOT = fallback.resolve("dungeon-guides");
			INDEX = ROOT.resolve("index.json");
			LEGACY = fallback.resolve("dungeon-guides.json");
			persistenceBlocked = true;
		} else {
			ROOT = configDirectory.resolve("geileraddons/dungeon-guides");
			INDEX = ROOT.resolve("index.json");
			LEGACY = configDirectory.resolve("geileraddons/dungeon-guides.json");
		}
	}

	private DungeonGuideStore() { }

	public static void load() {
		if (loaded) return;
		loaded = true;
		if (persistenceBlocked) return;
		ClientJsonFile.ReadResult<Index> indexResult = ClientJsonFile.readResult(INDEX, Index.class);
		if (indexResult.exists()) {
			if (!indexResult.valid() || (indexResult.value().version != 1 && indexResult.value().version != 2)
				|| indexResult.value().routes == null
				|| indexResult.value().routes.size() > ClientJsonFile.MAX_ENTRIES) {
				persistenceBlocked = true;
				GeilerAddons.LOGGER.error("Dungeon Guide route index is invalid; leaving the files untouched");
				return;
			}
			Index index = indexResult.value();
			int loadedVersion = index.version;
			nextKey = validKey(index.nextKey, nextKey);
			previousKey = validKey(index.previousKey, previousKey);
			if (index.activeRoutes != null) ACTIVE_ROUTES.putAll(index.activeRoutes);
			if (index.routeNames != null) ROUTE_NAMES.putAll(index.routeNames);
			if (index.customSegments != null) for (DungeonGuideCustomSegment segment : index.customSegments) {
				if (segment == null) { persistenceBlocked = true; continue; }
				segment.sanitize();
				if (CUSTOM_SEGMENTS.stream().anyMatch(existing -> existing.id.equals(segment.id))) {
					persistenceBlocked = true;
					continue;
				}
				CUSTOM_SEGMENTS.add(segment);
			}
			Set<String> ids = new HashSet<>();
			for (RouteEntry entry : index.routes) {
				if (!validEntry(entry)) { persistenceBlocked = true; continue; }
				ROUTE_TIMINGS.put(routeIdentity(entry.floor, entry.phase, entry.name),
					TransitionTiming.parse(entry.timing));
				Path path = ROOT.resolve(entry.file).normalize();
				if (!path.startsWith(ROOT.normalize())) { persistenceBlocked = true; continue; }
				ClientJsonFile.ReadResult<RouteFile> routeResult = ClientJsonFile.readResult(path, RouteFile.class);
				if (!routeResult.exists() || !routeResult.valid()) { persistenceBlocked = true; continue; }
				RouteFile file = routeResult.value();
				if (file == null || file.version != 1 || file.nodes == null || file.nodes.size() > ClientJsonFile.MAX_ENTRIES) {
					persistenceBlocked = true;
					continue;
				}
				for (DungeonGuideNode node : file.nodes) {
					if (node == null) { persistenceBlocked = true; continue; }
					node.sanitize();
					if (!node.floor.equals(entry.floor) || !node.phase.equals(entry.phase)
						|| !node.routeName.equals(entry.name) || !ids.add(node.id)) { persistenceBlocked = true; continue; }
					NODES.add(node);
				}
			}
			if (persistenceBlocked) GeilerAddons.LOGGER.error("One or more Dungeon Guide route files could not be read; saves are paused to protect them");
			if (!persistenceBlocked && loadedVersion == 2) {
				for (DungeonGuideNode node : NODES) {
					String selected = ROUTE_NAMES.putIfAbsent(node.floor, node.routeName);
					if (selected != null && !selected.equals(node.routeName)) persistenceBlocked = true;
				}
				if (persistenceBlocked) GeilerAddons.LOGGER.error("Dungeon Guide v2 contains more than one route name for a floor; saves are paused to protect it");
			}
			if (!persistenceBlocked && loadedVersion == 1) {
				if (!archiveLegacyFiles(index)) {
					persistenceBlocked = true;
					GeilerAddons.LOGGER.error("Could not archive legacy Dungeon Guide routes; migration is paused to protect them");
					return;
				}
				migrateLegacyRoutes(index);
			}
			return;
		}

		ClientJsonFile.ReadResult<LegacyCatalog> legacyResult = ClientJsonFile.readResult(LEGACY, LegacyCatalog.class);
		if (legacyResult.exists() && !legacyResult.valid()) {
			persistenceBlocked = true;
			GeilerAddons.LOGGER.error("Legacy Dungeon Guide catalog is invalid; leaving it untouched");
			return;
		}
		LegacyCatalog legacy = legacyResult.value();
		if (legacy == null || legacy.version != 1) return;
		if (!archiveLegacyCatalog()) {
			persistenceBlocked = true;
			GeilerAddons.LOGGER.error("Could not archive the legacy Dungeon Guide catalog; migration is paused to protect it");
			return;
		}
		if (legacy.nodes != null && legacy.nodes.size() <= ClientJsonFile.MAX_ENTRIES) {
			Set<String> ids = new HashSet<>();
			for (DungeonGuideNode node : legacy.nodes) {
				if (node == null) continue;
				node.sanitize();
				if (ids.add(node.id)) {
					NODES.add(node);
					ROUTE_TIMINGS.putIfAbsent(routeIdentity(node.floor, node.phase, node.routeName), TransitionTiming.NEXT_OBJECTIVE);
				}
			}
		}
		nextKey = validKey(legacy.nextKey, nextKey);
		previousKey = validKey(legacy.previousKey, previousKey);
		normalizeRouteNames();
		dirty = true;
		mutationVersion++;
		saveDelayTicks = 0;
	}

	public static List<DungeonGuideNode> all() {
		load();
		if (nodesSnapshot == null) nodesSnapshot = List.copyOf(NODES);
		return nodesSnapshot;
	}
	/** Changes only when route data or phase ordering can affect a published guide snapshot. */
	public static long guideRevision() { load(); return guideRevision; }
	public static DungeonGuideNode at(int index) { load(); return index < 0 || index >= NODES.size() ? null : NODES.get(index); }
	public static void add(DungeonGuideNode node) { load(); if (node == null || NODES.size() >= ClientJsonFile.MAX_ENTRIES) return; node.sanitize(); node.routeName = routeName(node.floor); node.order = NODES.stream().filter(existing -> existing.floor.equals(node.floor) && existing.phase.equals(node.phase)).mapToInt(existing -> existing.order).max().orElse(-1) + 1; NODES.add(node); ROUTE_TIMINGS.putIfAbsent(routeIdentity(node.floor, node.phase, node.routeName), TransitionTiming.NEXT_OBJECTIVE); markGuideDataChanged(); markDirty(); }
	public static void remove(String id) { load(); if (NODES.removeIf(node -> node.id.equals(id))) { markGuideDataChanged(); markDirty(); } }
	public static void changed() { load(); NODES.forEach(DungeonGuideNode::sanitize); for (DungeonGuideNode node : NODES) { node.routeName = routeName(node.floor); ROUTE_TIMINGS.putIfAbsent(routeIdentity(node.floor, node.phase, node.routeName), TransitionTiming.NEXT_OBJECTIVE); } markGuideDataChanged(); markDirty(); }
	public static String nextKey() { load(); return nextKey; }
	public static String previousKey() { load(); return previousKey; }
	public static void setNextKey(String key) { load(); nextKey = validKey(key, nextKey); markDirty(); }
	public static void setPreviousKey(String key) { load(); previousKey = validKey(key, previousKey); markDirty(); }
	public static List<Route> routes() {
		load();
		return NODES.stream().map(node -> new Route(node.floor, node.phase, routeName(node.floor))).distinct()
			.sorted(Comparator.comparing(Route::floor).thenComparingInt(route -> phaseOrder(route.floor(), route.phase()))
				.thenComparing(Route::phase)).toList();
	}
	public static String routeName(String floor) { load(); return ROUTE_NAMES.getOrDefault(floor, "Dungeon Route"); }
	public static boolean setRouteName(String floor, String name) {
		load();
		if (DungeonFloor.parse(floor) == null || name == null) return false;
		String cleaned = name.replaceAll("[\\p{Cntrl}]", "").trim();
		if (cleaned.isBlank() || cleaned.length() > 48) return false;
		String old = routeName(floor);
		if (old.equals(cleaned)) return true;
		ROUTE_NAMES.put(floor, cleaned);
		for (DungeonGuideNode node : NODES) if (node.floor.equals(floor)) node.routeName = cleaned;
		Map<String, TransitionTiming> migratedTimings = new LinkedHashMap<>();
		for (Map.Entry<String, TransitionTiming> entry : ROUTE_TIMINGS.entrySet()) {
			Route route = parseRouteIdentity(entry.getKey());
			if (route == null || !route.floor().equals(floor)) migratedTimings.put(entry.getKey(), entry.getValue());
			else migratedTimings.put(routeIdentity(floor, route.phase(), cleaned), entry.getValue());
		}
		ROUTE_TIMINGS.clear(); ROUTE_TIMINGS.putAll(migratedTimings);
		markGuideDataChanged();
		markDirty();
		return true;
	}
	public static int phaseOrder(String floorName, String phase) {
		DungeonFloor floor = DungeonFloor.parse(floorName);
		if (floor == null || phase == null) return Integer.MAX_VALUE;
		List<DungeonGuideSegments.Segment> builtIn = DungeonGuideSegments.builtIn(floor);
		int order = 0;
		for (int i = 0; i < builtIn.size(); i++) {
			if (builtIn.get(i).id().equals(phase)) return order;
			order++;
			for (DungeonGuideCustomSegment custom : CUSTOM_SEGMENTS) {
				if (!custom.floor.equals(floor.displayName()) || custom.afterBuiltInIndex != i) continue;
				if (custom.id.equals(phase)) return order;
				order++;
			}
		}
		return Integer.MAX_VALUE;
	}
	public static List<DungeonGuideCustomSegment> customSegments(DungeonFloor floor) {
		load();
		return CUSTOM_SEGMENTS.stream().filter(segment -> floor != null && segment.floor.equals(floor.displayName())).toList();
	}
	public static boolean addCustomSegment(DungeonGuideCustomSegment segment) {
		load();
		if (segment == null || CUSTOM_SEGMENTS.size() >= ClientJsonFile.MAX_ENTRIES) return false;
		segment.sanitize();
		if (CUSTOM_SEGMENTS.stream().anyMatch(existing -> existing.id.equals(segment.id))) return false;
		CUSTOM_SEGMENTS.add(segment);
		markGuideDataChanged();
		markDirty();
		return true;
	}
	public static void customSegmentChanged() { load(); CUSTOM_SEGMENTS.forEach(DungeonGuideCustomSegment::sanitize); markGuideDataChanged(); markDirty(); }
	public static boolean moveCustomSegment(String id, int direction) {
		load();
		if (id == null || direction == 0) return false;
		for (int i = 0; i < CUSTOM_SEGMENTS.size(); i++) {
			DungeonGuideCustomSegment segment = CUSTOM_SEGMENTS.get(i);
			if (!id.equals(segment.id)) continue;
			int neighbor = i + Integer.signum(direction);
			if (neighbor >= 0 && neighbor < CUSTOM_SEGMENTS.size()) {
				DungeonGuideCustomSegment other = CUSTOM_SEGMENTS.get(neighbor);
				if (other.floor.equals(segment.floor) && other.afterBuiltInIndex == segment.afterBuiltInIndex) {
					java.util.Collections.swap(CUSTOM_SEGMENTS, i, neighbor);
					markGuideDataChanged();
					markDirty();
					return true;
				}
			}
			int max = DungeonGuideSegments.builtIn(DungeonFloor.parse(segment.floor)).size() - 1;
			int next = Math.max(0, Math.min(max, segment.afterBuiltInIndex + Integer.signum(direction)));
			if (next == segment.afterBuiltInIndex) return false;
			segment.afterBuiltInIndex = next;
			markGuideDataChanged();
			markDirty();
			return true;
		}
		return false;
	}
	public static boolean removeCustomSegment(String id) {
		load();
		if (id == null || CUSTOM_SEGMENTS.stream().noneMatch(segment -> segment.id.equals(id))) return false;
		if (NODES.stream().anyMatch(node -> node.phase.equals(id))) return false;
		boolean removed = CUSTOM_SEGMENTS.removeIf(segment -> segment.id.equals(id));
		if (removed) { markGuideDataChanged(); markDirty(); }
		return removed;
	}
	public static boolean createRoute(String floor, String phase, String name, TransitionTiming timing) {
		load();
		if (DungeonFloor.parse(floor) == null || phase == null || !phase.matches("[A-Z][A-Z0-9_]{0,31}")
			|| name == null || name.isBlank() || name.length() > 48 || timing == null) return false;
		if (!routeName(floor).equals("Dungeon Route")) return false;
		ROUTE_NAMES.put(floor, name.trim());
		ROUTE_TIMINGS.put(routeIdentity(floor, phase, name.trim()), timing);
		markGuideDataChanged();
		markDirty(); return true;
	}
	public static TransitionTiming routeTiming(String floor, String phase, String name) {
		load();
		return ROUTE_TIMINGS.getOrDefault(routeIdentity(floor, phase, name), TransitionTiming.NEXT_OBJECTIVE);
	}
	public static boolean setRouteTiming(String floor, String phase, String name, TransitionTiming timing) {
		load();
		String key = routeIdentity(floor, phase, name);
		if (!ROUTE_TIMINGS.containsKey(key) || timing == null) return false;
		ROUTE_TIMINGS.put(key, timing);
		markDirty();
		return true;
	}
	public static String activeRoute(String floor, String phase) {
		return routeName(floor);
	}
	public static boolean setActiveRoute(String floor, String phase, String name) {
		return setRouteName(floor, name);
	}
	/** Migrates absent per-ring fields from the former module-wide settings after config load. */
	public static boolean migrateLegacyRingAppearance(int count, float height, float radius,
		float speed, float width) {
		load();
		if (persistenceBlocked) return false;
		boolean changed = false;
		for (DungeonGuideNode node : NODES) {
			changed |= node.migrateMissingRingAppearance(count, height, radius, speed, width);
		}
		if (changed) {
			markGuideDataChanged();
			markDirty();
		}
		return changed;
	}
	public static void tick() {
		resolvePending();
		if (!dirty || persistenceBlocked || pendingSave != null) return;
		if (--saveDelayTicks <= 0) save();
	}
	public static void flush() {
		flushAndReport();
	}

	/** Waits for the current route snapshot and reports any persistence failure to config tools. */
	public static boolean flushAndReport() {
		load();
		if (persistenceBlocked) return false;
		for (int i = 0; i < 2; i++) {
			if (!resolvePending(true) || persistenceBlocked) return false;
			if (!dirty) return true;
			if (pendingSave != null) continue;
			save();
		}
		return resolvePending(true) && !dirty && !persistenceBlocked;
	}

	/** Starts any needed writer using a client-thread snapshot; callers may await it off-thread. */
	public static void prepareFlush() {
		load();
		resolvePending();
		if (!persistenceBlocked && dirty && pendingSave == null) save();
	}

	/** Clears routes and route-level settings after the user confirms a factory reset. */
	public static boolean resetToFactoryDefaults() {
		load();
		if (persistenceBlocked || pendingSave != null) return false;
		NODES.clear();
		ACTIVE_ROUTES.clear();
		ROUTE_NAMES.clear();
		ROUTE_TIMINGS.clear();
		CUSTOM_SEGMENTS.clear();
		nextKey = DEFAULT_NEXT_KEY;
		previousKey = DEFAULT_PREVIOUS_KEY;
		markGuideDataChanged();
		markDirty();
		saveDelayTicks = 0;
		return true;
	}

	/** Replaces the live route cache with the already-validated profile now installed on disk. */
	public static boolean reloadFromDisk() {
		// The config transfer flow drains this store before installing the new files. Flushing here
		// would serialize the old in-memory routes over the imported profile.
		if (pendingSave != null || dirty) return false;
		NODES.clear();
		ACTIVE_ROUTES.clear();
		ROUTE_NAMES.clear();
		ROUTE_TIMINGS.clear();
		CUSTOM_SEGMENTS.clear();
		nodesSnapshot = null;
		loaded = false;
		persistenceBlocked = false;
		dirty = false;
		pendingSave = null;
		nextKey = DEFAULT_NEXT_KEY;
		previousKey = DEFAULT_PREVIOUS_KEY;
		load();
		markGuideDataChanged();
		return !persistenceBlocked;
	}

	public static String exportPackage() { load(); return DungeonPackageCodec.encodeGuides(NODES); }
	public static ImportResult importPackage(String encoded) {
		load();
		DungeonPackageCodec.DecodeResult<DungeonGuideNode> decoded = DungeonPackageCodec.decodeGuides(encoded);
		if (!decoded.success()) return new ImportResult(false, 0, decoded.error());
		Set<String> ids = new HashSet<>();
		for (DungeonGuideNode existing : NODES) ids.add(existing.id);
		int imported = 0;
		for (DungeonGuideNode node : decoded.entries()) {
			if (node == null || NODES.size() >= ClientJsonFile.MAX_ENTRIES) continue;
			node.sanitize();
			if (!ids.add(node.id)) node.id = java.util.UUID.randomUUID().toString();
			node.routeName = routeName(node.floor);
			NODES.add(node);
			ROUTE_TIMINGS.putIfAbsent(routeIdentity(node.floor, node.phase, node.routeName), TransitionTiming.NEXT_OBJECTIVE);
			imported++;
		}
		if (imported > 0) { markGuideDataChanged(); markDirty(); }
		return new ImportResult(true, imported, null);
	}

	private static void save() {
		if (persistenceBlocked) return;
		long version = mutationVersion;
		Map<Route, List<DungeonGuideNode>> grouped = new LinkedHashMap<>();
		NODES.stream().sorted(Comparator.comparing((DungeonGuideNode n) -> n.floor)
			.thenComparing(n -> n.phase).thenComparing(n -> n.routeName, String.CASE_INSENSITIVE_ORDER)
			.thenComparingInt(n -> n.order)).forEach(node -> grouped
				.computeIfAbsent(new Route(node.floor, node.phase, node.routeName), ignored -> new ArrayList<>()).add(node));
		for (Route route : routes()) grouped.computeIfAbsent(route, ignored -> new ArrayList<>());
		List<ClientJsonFile.Write> writes = new ArrayList<>();
		Index index = new Index();
		index.version = 2;
		index.nextKey = nextKey;
		index.previousKey = previousKey;
		index.activeRoutes = new LinkedHashMap<>(ACTIVE_ROUTES);
		index.routeNames = new LinkedHashMap<>(ROUTE_NAMES);
		index.customSegments = new ArrayList<>(CUSTOM_SEGMENTS);
		for (Map.Entry<Route, List<DungeonGuideNode>> entry : grouped.entrySet()) {
			Route route = entry.getKey();
			String relative = route.floor() + "/" + route.phase() + "/" + routeFileName(route.name());
			RouteFile file = new RouteFile();
			file.version = 1;
			file.nodes = entry.getValue();
			writes.add(new ClientJsonFile.Write(ROOT.resolve(relative), GSON.toJson(file).getBytes(StandardCharsets.UTF_8)));
			RouteEntry routeEntry = new RouteEntry();
			routeEntry.floor = route.floor();
			routeEntry.phase = route.phase();
			routeEntry.name = route.name();
			routeEntry.file = relative;
			routeEntry.timing = routeTiming(route.floor(), route.phase(), route.name()).name();
			index.routes.add(routeEntry);
		}
		writes.add(new ClientJsonFile.Write(INDEX, GSON.toJson(index).getBytes(StandardCharsets.UTF_8)));
		pendingVersion = version;
		pendingSave = ClientJsonFile.writeBatchAsync(writes);
	}

	private static boolean resolvePending() { return resolvePending(false); }
	private static boolean resolvePending(boolean wait) {
		if (pendingSave == null) return true;
		if (!wait && !pendingSave.isDone()) return false;
		Future<Boolean> resolving = pendingSave;
		boolean success = false;
		try { success = resolving.get(); }
		catch (InterruptedException error) { Thread.currentThread().interrupt(); return false; }
		catch (ExecutionException error) { GeilerAddons.LOGGER.error("Dungeon Guide route save failed", error.getCause()); }
		if (pendingSave == resolving) pendingSave = null;
		if (success && pendingVersion == mutationVersion) dirty = false;
		else { dirty = true; saveDelayTicks = 40; }
		return true;
	}

	private static void markDirty() {
		if (persistenceBlocked) return;
		dirty = true;
		mutationVersion++;
		saveDelayTicks = 20;
	}

	private static void markGuideDataChanged() {
		guideRevision++;
		nodesSnapshot = null;
	}

	private static boolean archiveLegacyFiles(Index index) {
		Path archive = ROOT.resolve("archive/legacy-v1");
		try {
			Files.createDirectories(archive);
			copyIfAbsent(INDEX, archive.resolve("index.json"));
			for (RouteEntry entry : index.routes) {
				if (!validEntry(entry)) return false;
				Path source = ROOT.resolve(entry.file).normalize();
				if (!source.startsWith(ROOT.normalize())) return false;
				Path target = archive.resolve("routes").resolve(entry.file).normalize();
				if (!target.startsWith(archive.resolve("routes").normalize())) return false;
				copyIfAbsent(source, target);
			}
			return true;
		} catch (java.io.IOException error) {
			GeilerAddons.LOGGER.error("Could not archive legacy Dungeon Guide files", error);
			return false;
		}
	}

	private static boolean archiveLegacyCatalog() {
		Path archive = ROOT.resolve("archive/legacy-v1/dungeon-guides.json");
		try { Files.createDirectories(archive.getParent()); copyIfAbsent(LEGACY, archive); return true; }
		catch (java.io.IOException error) { GeilerAddons.LOGGER.error("Could not archive legacy Dungeon Guide catalog", error); return false; }
	}

	private static void copyIfAbsent(Path source, Path target) throws java.io.IOException {
		if (source == null || !Files.isRegularFile(source)) return;
		Files.createDirectories(target.getParent());
		if (!Files.exists(target)) Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
	}

	private static void migrateLegacyRoutes(Index legacy) {
		Set<String> floors = new java.util.LinkedHashSet<>();
		for (RouteEntry entry : legacy.routes) if (validEntry(entry)) floors.add(entry.floor);
		for (DungeonGuideNode node : NODES) floors.add(node.floor);
		for (String floor : floors) {
			Set<String> names = new java.util.LinkedHashSet<>();
			for (DungeonGuideNode node : NODES) if (node.floor.equals(floor)) names.add(node.routeName);
			for (RouteEntry entry : legacy.routes) if (validEntry(entry) && entry.floor.equals(floor)) names.add(entry.name);
			String commonName = names.isEmpty() ? "Dungeon Route" : names.size() == 1 ? names.iterator().next() : "Combined Route";
			ROUTE_NAMES.put(floor, commonName);
		}
		normalizeRouteNames();
		markGuideDataChanged();
		markDirty();
		saveDelayTicks = 0;
		GeilerAddons.LOGGER.info("Migrated {} Dungeon Guide steps into one named route per floor", NODES.size());
	}

	private static void normalizeRouteNames() {
		normalizeRouteNamesAndOrder(NODES, ROUTE_NAMES);
		Map<String, TransitionTiming> timings = new LinkedHashMap<>();
		for (Map.Entry<String, TransitionTiming> entry : ROUTE_TIMINGS.entrySet()) {
			Route route = parseRouteIdentity(entry.getKey());
			if (route == null) continue;
			String name = ROUTE_NAMES.getOrDefault(route.floor(), "Dungeon Route");
			timings.putIfAbsent(routeIdentity(route.floor(), route.phase(), name), entry.getValue());
		}
		for (DungeonGuideNode node : NODES) timings.putIfAbsent(routeIdentity(node.floor, node.phase, node.routeName), TransitionTiming.NEXT_OBJECTIVE);
		ROUTE_TIMINGS.clear(); ROUTE_TIMINGS.putAll(timings);
		ACTIVE_ROUTES.clear();
	}

	/** Preserves a sole legacy name and combines multi-route floors in stable phase/name/order order. */
	static void normalizeRouteNamesAndOrder(List<DungeonGuideNode> nodes, Map<String, String> routeNames) {
		Map<String, Set<String>> oldNames = new LinkedHashMap<>();
		for (DungeonGuideNode node : nodes) {
			if (node == null) continue;
			String name = node.routeName == null ? "" : node.routeName.trim();
			if (!name.isEmpty()) oldNames.computeIfAbsent(node.floor, ignored -> new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER)).add(name);
		}
		for (DungeonGuideNode node : nodes) {
			if (node == null) continue;
			Set<String> names = oldNames.getOrDefault(node.floor, Set.of());
			String selected = names.isEmpty() ? "Dungeon Route" : names.size() == 1 ? names.iterator().next() : "Combined Route";
			routeNames.putIfAbsent(node.floor, selected);
		}
		nodes.sort(Comparator.comparing((DungeonGuideNode node) -> DungeonFloor.parse(node.floor).ordinal())
			.thenComparingInt(node -> phaseOrder(node.floor, node.phase))
			.thenComparing(node -> node.phase)
			.thenComparing(node -> node.routeName, String.CASE_INSENSITIVE_ORDER)
			.thenComparingInt(node -> node.order));
		for (DungeonGuideNode node : nodes) node.routeName = routeNames.computeIfAbsent(node.floor, ignored -> "Dungeon Route");
		Map<String, Integer> nextOrder = new LinkedHashMap<>();
		for (DungeonGuideNode node : nodes) {
			String key = node.floor + "|" + node.phase;
			node.order = nextOrder.getOrDefault(key, 0);
			nextOrder.put(key, node.order + 1);
		}
	}
	private static boolean validEntry(RouteEntry entry) {
		return entry != null && DungeonFloor.parse(entry.floor) != null && entry.phase != null
			&& entry.phase.matches("[A-Z][A-Z0-9_]{0,31}") && entry.name != null
			&& !entry.name.isBlank() && entry.file != null && entry.file.matches("[A-Za-z0-9._/-]{1,256}")
			&& !entry.file.contains("..") && entry.file.toLowerCase(Locale.ROOT).endsWith(".json");
	}
	private static String routeFileName(String name) {
		String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
		while (slug.startsWith(".")) slug = slug.substring(1);
		if (slug.isBlank()) slug = "route";
		if (slug.length() > 48) slug = slug.substring(0, 48);
		return slug + "-" + Integer.toUnsignedString(name.hashCode(), 36) + ".json";
	}
	private static String routeKey(String floor, String phase) { return floor + "|" + phase; }
	private static String routeIdentity(String floor, String phase, String name) {
		return floor + "\u001f" + phase + "\u001f" + name;
	}
	private static Route parseRouteIdentity(String key) {
		String[] parts = key.split("\u001f", 3);
		return parts.length == 3 ? new Route(parts[0], parts[1], parts[2]) : null;
	}
	private static String validKey(String value, String fallback) {
		if (value == null || value.isBlank()) return fallback;
		String cleaned = value.trim();
		return cleaned.length() > 64 ? fallback : cleaned;
	}

	private static final class Index {
		int version;
		List<RouteEntry> routes = new ArrayList<>();
		String nextKey;
		String previousKey;
		Map<String, String> activeRoutes = new LinkedHashMap<>();
		Map<String, String> routeNames = new LinkedHashMap<>();
		List<DungeonGuideCustomSegment> customSegments = new ArrayList<>();
	}
	private static final class RouteEntry { String floor; String phase; String name; String file; String timing; }
	private static final class RouteFile { int version; List<DungeonGuideNode> nodes; }
	private static final class LegacyCatalog { int version; List<DungeonGuideNode> nodes; String nextKey; String previousKey; }
	public record Route(String floor, String phase, String name) { }
	public enum TransitionTiming {
		NEXT_OBJECTIVE, EVENT_SEGMENT;
		static TransitionTiming parse(String value) {
			if (value == null) return NEXT_OBJECTIVE;
			try { return valueOf(value); } catch (IllegalArgumentException ignored) { return NEXT_OBJECTIVE; }
		}
	}
	public record ImportResult(boolean success, int imported, String error) { }
}
