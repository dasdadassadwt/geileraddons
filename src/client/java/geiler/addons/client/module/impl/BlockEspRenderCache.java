package geiler.addons.client.module.impl;

import geiler.addons.client.render.EspRenderer;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** Immutable, worker-built geometry indexes for one completed connected Block ESP result. */
final class BlockEspRenderCache {
	private final LongSet membership;
	private final List<EspRenderer.OutlineSegment> outline;

	private BlockEspRenderCache(LongSet membership, List<EspRenderer.OutlineSegment> outline) {
		this.membership = membership;
		this.outline = List.copyOf(outline);
	}

	static BlockEspRenderCache prepare(Set<BlockPos> blocks) {
		LongOpenHashSet membership = new LongOpenHashSet(blocks.size());
		int checked = 0;
		for (BlockPos block : blocks) {
			if ((checked++ & 0x3FF) == 0 && Thread.currentThread().isInterrupted()) {
				throw new CancellationException();
			}
			membership.add(block.asLong());
		}
		List<EspRenderer.OutlineSegment> outline = EspRenderer.blockOutlineSegments(blocks);
		return new BlockEspRenderCache(LongSets.unmodifiable(membership), outline);
	}

	LongSet membership() { return membership; }
	List<EspRenderer.OutlineSegment> outline() { return outline; }
}
