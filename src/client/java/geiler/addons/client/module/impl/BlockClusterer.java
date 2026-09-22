package geiler.addons.client.module.impl;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CancellationException;

/** Pure six-neighbour connected-component grouping for ESP block positions. */
public final class BlockClusterer {
	private static final int[][] FACES = {
		{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
	};

	private BlockClusterer() { }

	public static List<Cluster> cluster(Collection<BlockPos> source, boolean connectTouching) {
		if (source == null || source.isEmpty()) return List.of();
		if (!connectTouching) {
			LongOpenHashSet seen = new LongOpenHashSet(source.size());
			List<Cluster> singles = new ArrayList<>();
			for (BlockPos pos : source) {
				if (pos != null && seen.add(pos.asLong())) singles.add(clusterOf(List.of(pos)));
			}
			return List.copyOf(singles);
		}
		LongOpenHashSet remaining = new LongOpenHashSet(source.size());
		int checked = 0;
		for (BlockPos pos : source) {
			checkCancelled(checked++);
			if (pos != null) remaining.add(pos.asLong());
		}
		if (remaining.isEmpty()) return List.of();
		List<Cluster> result = new ArrayList<>();
		while (!remaining.isEmpty()) {
			LongIterator starts = remaining.iterator();
			long start = starts.nextLong();
			remaining.remove(start);
			LongArrayFIFOQueue pending = new LongArrayFIFOQueue();
			List<BlockPos> component = new ArrayList<>();
			pending.enqueue(start);
			long sumX = 0, sumY = 0, sumZ = 0;
			while (!pending.isEmpty()) {
				checkCancelled(checked++);
				long current = pending.dequeueLong();
				BlockPos position = BlockPos.of(current);
				component.add(position);
				sumX += position.getX();
				sumY += position.getY();
				sumZ += position.getZ();
				for (int[] face : FACES) {
					long adjacent = BlockPos.offset(current, face[0], face[1], face[2]);
					if (remaining.remove(adjacent)) pending.enqueue(adjacent);
				}
			}
			double size = component.size();
			result.add(new Cluster(component, new Vec3((sumX + 0.5 * size) / size,
				(sumY + 0.5 * size) / size, (sumZ + 0.5 * size) / size)));
		}
		return List.copyOf(result);
	}

	private static void checkCancelled(int checked) {
		if ((checked & 0x3FF) == 0 && Thread.currentThread().isInterrupted()) {
			throw new CancellationException();
		}
	}

	private static Cluster clusterOf(List<BlockPos> blocks) {
		List<BlockPos> immutable = List.copyOf(blocks);
		long x = 0, y = 0, z = 0;
		for (BlockPos pos : immutable) {
			x += pos.getX();
			y += pos.getY();
			z += pos.getZ();
		}
		double size = immutable.size();
		return new Cluster(immutable, new Vec3((x + 0.5 * size) / size, (y + 0.5 * size) / size,
			(z + 0.5 * size) / size));
	}

	public record Cluster(List<BlockPos> blocks, Vec3 center) {
		public Cluster { blocks = List.copyOf(blocks); }
	}
}
