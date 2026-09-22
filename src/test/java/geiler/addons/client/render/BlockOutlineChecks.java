package geiler.addons.client.render;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Set;

/** Offline geometry checks for merged voxel contours. */
public final class BlockOutlineChecks {
	private BlockOutlineChecks() { }

	public static void run() {
		List<EspRenderer.OutlineSegment> oneBlock = EspRenderer.blockOutlineSegments(Set.of(new BlockPos(0, 0, 0)));
		check(oneBlock.size() == 12, "one voxel has the twelve edges of one cuboid");

		Set<BlockPos> adjacent = Set.of(new BlockPos(0, 0, 0), new BlockPos(1, 0, 0));
		List<EspRenderer.OutlineSegment> merged = EspRenderer.blockOutlineSegments(adjacent);
		check(merged.size() == 12, "two face-adjacent voxels share one continuous outer contour");
		check(merged.stream().noneMatch(edge -> edge.x0() == 1 && edge.x1() == 1),
			"the shared interior plane has no outline lines");
		check(merged.stream().anyMatch(edge -> Math.abs(edge.x1() - edge.x0()) == 2),
			"collinear block-edge segments merge across their shared vertex");
		check(merged.stream().distinct().count() == merged.size(),
			"the merged contour does not submit duplicate line segments");
	}

	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}
}
