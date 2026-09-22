package geiler.addons.client.module.impl;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import geiler.addons.client.location.HypixelModApi;
import geiler.addons.client.location.Island;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Budgeted client-thread scanner that reads only loaded chunk sections and publishes immutable snapshots. */
final class BlockEspScanner {
	private static final int CHUNK_BUDGET_PER_TICK = 32;
	private static final int BLOCK_BUDGET_PER_TICK = 250_000;
	private static final long BLOCK_SCAN_BUDGET_NANOS = 2_000_000L;
	private static final int REFRESH_DELAY_TICKS = 40;
	private static final long CENTER_REFRESH_DISTANCE_SQUARED = 64L;
	private static final ThreadPoolExecutor SNAPSHOT_WORKER = new ThreadPoolExecutor(1, 1, 0L,
		TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), task -> {
		Thread thread = new Thread(task, "GeilerAddons Block ESP Clustering");
		thread.setDaemon(true);
		return thread;
	});

	private Scan scan;
	private FutureTask<Snapshot> pendingSnapshot;
	private ClientLevel lastLevel;
	private Snapshot snapshot = Snapshot.EMPTY;
	private String lastSignature = "";
	private int ticksUntilRefresh;

	Snapshot snapshot() { return snapshot; }

	void clear(List<BlockEspEntry> entries) {
		scan = null;
		cancelPendingSnapshot();
		snapshot = Snapshot.EMPTY;
		lastLevel = null;
		lastSignature = "";
		ticksUntilRefresh = 0;
		for (BlockEspEntry entry : entries) entry.setStatus(0);
	}

	void tick(ClientLevel level, LocalPlayer player, int renderDistanceBlocks, List<BlockEspEntry> entries) {
		if (level == null || player == null) {
			clear(entries);
			return;
		}
		List<Target> targets = new ArrayList<>();
		Island currentIsland = HypixelModApi.currentIsland();
		for (BlockEspEntry entry : entries) {
			if (!entry.enabled().value() || entry.blockId().isBlank()) {
				entry.setStatus(0);
				continue;
			}
			Block block = BlockEspRules.resolve(entry.blockId());
			if (block == null || !BlockEspRules.matches(entry.blockId(), block)
				|| !entry.appliesOn(currentIsland)) {
				entry.setStatus(0);
				continue;
			}
			int radius = effectiveRadius(renderDistanceBlocks, entry.useCustomRange().value(), entry.customRange().intValue());
			targets.add(new Target(entry, block, radius, entry.blockId(), entry.connectTouching().value(), currentIsland));
		}
		String signature = signature(targets);
		BlockPos current = player.blockPosition();
		if (level != lastLevel) {
			lastLevel = level;
			snapshot = Snapshot.EMPTY;
			scan = null;
			cancelPendingSnapshot();
			ticksUntilRefresh = 0;
			lastSignature = "";
		}
		if (!signature.equals(lastSignature)) {
			scan = null;
			cancelPendingSnapshot();
			ticksUntilRefresh = 0;
			lastSignature = signature;
		}
		if (targets.isEmpty()) {
			snapshot = Snapshot.EMPTY;
			scan = null;
			cancelPendingSnapshot();
			ticksUntilRefresh = 0;
			for (BlockEspEntry entry : entries) entry.setStatus(0);
			return;
		}
		if (scan == null) {
			boolean moved = snapshot.center() != null
				&& snapshot.center().distSqr(current) > CENTER_REFRESH_DISTANCE_SQUARED;
			if (!moved && ticksUntilRefresh > 0) {
				ticksUntilRefresh--;
				return;
			}
			scan = new Scan(level, current, targets);
		}
		advance(scan);
		if (scan.complete) {
			if (pendingSnapshot == null) {
				// The scan is complete here; the worker only reads frozen candidates and never touches the world.
				pendingSnapshot = new FutureTask<>(scan::toSnapshot);
				SNAPSHOT_WORKER.execute(pendingSnapshot);
			}
			if (!pendingSnapshot.isDone()) return;
			try {
				snapshot = pendingSnapshot.get();
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				scan = null;
				pendingSnapshot = null;
				ticksUntilRefresh = REFRESH_DELAY_TICKS;
				return;
			} catch (ExecutionException | CancellationException exception) {
				// Keep the last complete result if clustering fails; a later refresh can retry.
				scan = null;
				pendingSnapshot = null;
				ticksUntilRefresh = REFRESH_DELAY_TICKS;
				return;
			}
			for (Target target : scan.targets) {
				EntryResult result = snapshot.result(target.entry.id());
				target.entry.setStatus(result == null ? 0 : result.matchCount());
			}
			scan = null;
			pendingSnapshot = null;
			ticksUntilRefresh = REFRESH_DELAY_TICKS;
		}
	}

	private void cancelPendingSnapshot() {
		FutureTask<Snapshot> pending = pendingSnapshot;
		if (pending == null) return;
		pending.cancel(true);
		SNAPSHOT_WORKER.remove(pending);
		pendingSnapshot = null;
	}

	static int effectiveRadius(int renderDistanceBlocks, boolean useCustomRange, int customRange) {
		int renderRadius = Math.max(0, renderDistanceBlocks);
		return useCustomRange ? Math.min(renderRadius, Math.max(0, customRange)) : renderRadius;
	}

	static boolean mayInspectChunk(boolean chunkAlreadyLoaded) { return chunkAlreadyLoaded; }

	static boolean withinRadius(BlockPos center, BlockPos position, int radius) {
		return withinRadius(center, position.getX(), position.getY(), position.getZ(), radius);
	}

	private static boolean withinRadius(BlockPos center, int x, int y, int z, int radius) {
		long dx = (long) center.getX() - x;
		long dy = (long) center.getY() - y;
		long dz = (long) center.getZ() - z;
		return dx * dx + dy * dy + dz * dz <= (long) radius * radius;
	}

	private static String signature(List<Target> targets) {
		StringBuilder result = new StringBuilder();
		for (Target target : targets) result.append(target.entry.id()).append(':').append(target.blockId)
			.append(':').append(target.radius).append(':').append(target.connectTouching).append(':')
			.append(target.island).append('|');
		return result.toString();
	}

	private static void advance(Scan scan) {
		long deadline = System.nanoTime() + BLOCK_SCAN_BUDGET_NANOS;
		if (!scan.sectionsPrepared) {
			prepareSections(scan, deadline);
			return;
		}
		int checkGeneration = ++scan.checkGeneration;
		int work = 0;
		int iterations = 0;
		while (work < BLOCK_BUDGET_PER_TICK && scan.taskIndex < scan.tasks.size()) {
			if ((iterations++ & 0x3FF) == 0 && System.nanoTime() >= deadline) break;
			SectionTask task = scan.tasks.get(scan.taskIndex);
			if (task.checkedGeneration != checkGeneration) {
				task.checkedGeneration = checkGeneration;
				if (task.chunk != scan.level.getChunkSource().getChunk(task.chunkX, task.chunkZ, false)) {
					task.localIndex = LevelChunkSection.SECTION_SIZE;
					continue;
				}
			}
			if (task.localIndex >= LevelChunkSection.SECTION_SIZE) {
				scan.taskIndex++;
				continue;
			}
			int index = task.localIndex++;
			work++;
			int localX = index & 15;
			int localZ = (index >>> 4) & 15;
			int localY = index >>> 8;
			int x = task.chunkX * 16 + localX;
			int y = task.minY + localY;
			int z = task.chunkZ * 16 + localZ;
			if (!withinRadius(scan.center, x, y, z, scan.maxRadius)) continue;
			List<Target> matching = scan.targetsByBlock.get(task.section.getBlockState(localX, localY, localZ).getBlock());
			if (matching == null) continue;
			BlockPos position = null;
			for (Target target : matching) {
				if (!withinRadius(scan.center, x, y, z, target.radius)) continue;
				if (position == null) position = new BlockPos(x, y, z);
				scan.candidates.get(target.entry.id()).offer(position);
			}
		}
		scan.complete = scan.taskIndex >= scan.tasks.size();
	}

	private static void prepareSections(Scan scan, long deadline) {
		int work = 0;
		while (work < CHUNK_BUDGET_PER_TICK && scan.chunkIndex < scan.chunkCoordinates.size()) {
			ChunkCoordinate coordinate = scan.chunkCoordinates.get(scan.chunkIndex++);
			work++;
			LevelChunk chunk = scan.level.getChunkSource().getChunk(coordinate.x, coordinate.z, false);
			if (!mayInspectChunk(chunk != null)) {
				if (System.nanoTime() >= deadline) break;
				continue;
			}
			List<SectionTask> chunkTasks = new ArrayList<>();
			LevelChunkSection[] sections = chunk.getSections();
			for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
				LevelChunkSection section = sections[sectionIndex];
				if (section == null) continue;
				int minY = chunk.getMinY() + sectionIndex * 16;
				if (!Scan.intersectsSphere(scan.center, coordinate.x * 16, minY, coordinate.z * 16, scan.maxRadius)) continue;
				if (!section.maybeHas(state -> scan.selectedBlocks.contains(state.getBlock()))) continue;
				long distance = Scan.distanceToSectionSquared(scan.center, coordinate.x * 16, minY, coordinate.z * 16);
				chunkTasks.add(new SectionTask(chunk, section, coordinate.x, coordinate.z, minY, distance));
			}
			chunkTasks.sort(Comparator.comparingLong(SectionTask::distanceSquared).thenComparingInt(SectionTask::minY));
			scan.tasks.addAll(chunkTasks);
			if (System.nanoTime() >= deadline) break;
		}
		scan.sectionsPrepared = scan.chunkIndex >= scan.chunkCoordinates.size();
	}

	private record Target(BlockEspEntry entry, Block block, int radius, String blockId,
		boolean connectTouching, Island island) { }

	static final class Snapshot {
		static final Snapshot EMPTY = new Snapshot(null, List.of());
		private final BlockPos center;
		private final List<EntryResult> results;

		Snapshot(BlockPos center, List<EntryResult> results) {
			this.center = center == null ? null : center.immutable();
			this.results = List.copyOf(results);
		}

		BlockPos center() { return center; }
		List<EntryResult> results() { return results; }
		EntryResult result(int id) {
			for (EntryResult result : results) if (result.entryId() == id) return result;
			return null;
		}
	}

	record EntryResult(int entryId, String blockId, int radius, boolean connectTouching, Island island,
		Set<BlockPos> blocks, List<BlockClusterer.Cluster> clusters,
		BlockEspRenderCache renderCache, int matchCount) {
		EntryResult {
			blocks = Collections.unmodifiableSet(blocks);
			clusters = List.copyOf(clusters);
		}
	}

	private static final class Scan {
		final ClientLevel level;
		final BlockPos center;
		final int maxRadius;
		final List<Target> targets;
		final Map<Block, List<Target>> targetsByBlock = new HashMap<>();
		final Map<Integer, Candidates> candidates = new HashMap<>();
		final Set<Block> selectedBlocks = new HashSet<>();
		final List<ChunkCoordinate> chunkCoordinates;
		final List<SectionTask> tasks = new ArrayList<>();
		int chunkIndex;
		int taskIndex;
		int checkGeneration;
		boolean sectionsPrepared;
		boolean complete;

		Scan(ClientLevel level, BlockPos center, List<Target> targets) {
			this.level = level;
			this.center = center.immutable();
			this.targets = List.copyOf(targets);
			this.maxRadius = targets.stream().mapToInt(Target::radius).max().orElse(0);
			for (Target target : targets) {
				targetsByBlock.computeIfAbsent(target.block, ignored -> new ArrayList<>()).add(target);
				selectedBlocks.add(target.block);
				candidates.put(target.entry.id(), new Candidates());
			}
			int minChunkX = Math.floorDiv(this.center.getX() - maxRadius, 16);
			int maxChunkX = Math.floorDiv(this.center.getX() + maxRadius, 16);
			int minChunkZ = Math.floorDiv(this.center.getZ() - maxRadius, 16);
			int maxChunkZ = Math.floorDiv(this.center.getZ() + maxRadius, 16);
			List<ChunkCoordinate> coordinates = new ArrayList<>();
			for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
				for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
					long dx = distanceToInterval(this.center.getX(), chunkX * 16, chunkX * 16 + 15);
					long dz = distanceToInterval(this.center.getZ(), chunkZ * 16, chunkZ * 16 + 15);
					long distance = dx * dx + dz * dz;
					if (distance <= (long) maxRadius * maxRadius) coordinates.add(new ChunkCoordinate(chunkX, chunkZ, distance));
				}
			}
			coordinates.sort(Comparator.comparingLong(ChunkCoordinate::distanceSquared)
				.thenComparingInt(ChunkCoordinate::x).thenComparingInt(ChunkCoordinate::z));
			this.chunkCoordinates = List.copyOf(coordinates);
		}

		static boolean intersectsSphere(BlockPos center, int minX, int minY, int minZ, int radius) {
			return distanceToSectionSquared(center, minX, minY, minZ) <= (long) radius * radius;
		}

		static long distanceToSectionSquared(BlockPos center, int minX, int minY, int minZ) {
			long dx = distanceToInterval(center.getX(), minX, minX + 15);
			long dy = distanceToInterval(center.getY(), minY, minY + 15);
			long dz = distanceToInterval(center.getZ(), minZ, minZ + 15);
			return dx * dx + dy * dy + dz * dz;
		}

		static long distanceToInterval(int point, int min, int max) {
			return point < min ? (long) min - point : point > max ? (long) point - max : 0;
		}

		Snapshot toSnapshot() {
			List<EntryResult> results = new ArrayList<>();
			for (Target target : targets) {
				Set<BlockPos> blocks = candidates.get(target.entry.id()).positions;
				// Disconnected rendering consumes positions directly; avoid allocating one cluster per match.
				List<BlockClusterer.Cluster> clusters = target.connectTouching
					? BlockClusterer.cluster(blocks, true) : List.of();
				BlockEspRenderCache renderCache = target.connectTouching
					? BlockEspRenderCache.prepare(blocks) : null;
				results.add(new EntryResult(target.entry.id(), target.blockId, target.radius,
					target.connectTouching, target.island, blocks, clusters, renderCache, blocks.size()));
			}
			return new Snapshot(center, results);
		}
	}

	private record ChunkCoordinate(int x, int z, long distanceSquared) { }

	private static final class SectionTask {
		final LevelChunk chunk;
		final LevelChunkSection section;
		final int chunkX;
		final int chunkZ;
		final int minY;
		final long distanceSquared;
		int localIndex;
		int checkedGeneration = -1;

		SectionTask(LevelChunk chunk, LevelChunkSection section, int chunkX, int chunkZ, int minY,
			long distanceSquared) {
			this.chunk = chunk;
			this.section = section;
			this.chunkX = chunkX;
			this.chunkZ = chunkZ;
			this.minY = minY;
			this.distanceSquared = distanceSquared;
		}

		int chunkX() { return chunkX; }
		int chunkZ() { return chunkZ; }
		int minY() { return minY; }
		long distanceSquared() { return distanceSquared; }
	}

	static final class Candidates {
		final Set<BlockPos> positions = new HashSet<>();
		void offer(BlockPos position) { positions.add(position); }
	}
}
