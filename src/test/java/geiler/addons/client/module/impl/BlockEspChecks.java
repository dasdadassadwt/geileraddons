package geiler.addons.client.module.impl;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.List;

/** Offline checks for exact matching, loaded-space bounds, clustering, island defaults, and uncapped collection. */
public final class BlockEspChecks {
	private BlockEspChecks() { }

	public static void run() {
		check(BlockEspRules.matches("minecraft:stone", Blocks.STONE), "registered block id matches its exact block type");
		check(BlockEspRules.resolve("mod_missing:not_registered") == null,
			"unknown saved block ids do not fall back to air or another registry default");
		check(!BlockEspRules.matches("minecraft:dirt", Blocks.STONE), "different registered block ids never match");
		check(!BlockEspRules.matches("", Blocks.STONE), "blank selection matches nothing");

		BlockEspEntry entry = BlockEspModule.INSTANCE.blank(50_000);
		check(entry.box().value() && entry.fill().value() && entry.outline().value(),
			"new block ESP entries expose box, fill, and outline independently and enabled");
		entry.fill().setValue(false);
		check(!entry.fill().value() && entry.box().value() && entry.outline().value(),
			"fill can be hidden without disabling the exposed outline shape");
		check(entry.islands().values().stream().noneMatch(setting -> setting.value()),
			"new block ESP entries start with every island filter disabled");
		check(!entry.useCustomRange().value() && entry.customRange().intValue() == 32,
			"new entries use full render distance by default and retain a 32-block custom radius");
		check(BlockEspScanner.effectiveRadius(192, false, 32) == 192,
			"default radius reaches the full effective render distance");
		check(BlockEspScanner.effectiveRadius(512, false, 32) == 512,
			"default scan radius is not capped at 64 blocks");
		check(BlockEspScanner.effectiveRadius(192, true, 32) == 32,
			"custom radius narrows a specific entry");
		check(BlockEspScanner.effectiveRadius(16, true, 32) == 16,
			"custom radius never extends past effective render distance");
		check(BlockEspScanner.effectiveRadius(0, false, 32) == 0,
			"zero render distance never scans outside the available range");
		BlockPos origin = new BlockPos(0, 64, 0);
		check(BlockEspScanner.withinRadius(origin, new BlockPos(3, 64, 0), 3),
			"scan sphere includes its radius boundary");
		check(!BlockEspScanner.withinRadius(origin, new BlockPos(2, 66, 0), 2),
			"scan sphere excludes positions outside its radius");
		check(!BlockEspScanner.mayInspectChunk(false), "unloaded chunks are skipped without requesting them");
		check(BlockEspScanner.mayInspectChunk(true), "already-loaded chunks may be inspected");

		Vec3 camera = new Vec3(13.25, 72.0, -8.5);
		checkTracerRay(camera, new Vector3f(0, 0, -1), new Vec3(24.5, 65.5, 3.5), 0.5,
			"forward-facing camera starts its tracer just ahead of the crosshair");
		checkTracerRay(camera, new Vector3f(1, 0, 0), new Vec3(-2.5, 79.0, 18.0), 0.5,
			"positive yaw direction keeps the tracer start on the camera ray");
		checkTracerRay(camera, new Vector3f(-1, 0, 0), new Vec3(0.5, 42.0, -22.0), 0.5,
			"negative yaw direction keeps the tracer start on the camera ray");
		checkTracerRay(camera, new Vector3f(1, 2, -3), new Vec3(100.5, 10.0, 41.5), 0.5,
			"combined yaw and pitch use a normalized camera-relative start and exact cluster target");
		BlockEspTracerGeometry.Endpoints noDirection = BlockEspTracerGeometry.endpoints(camera,
			new Vector3f(), new Vec3(15, 70, -4), 0.5);
		check(noDirection.start().equals(Vec3.ZERO)
			&& noDirection.end().equals(new Vec3(15, 70, -4).subtract(camera)),
			"a degenerate direction falls back to a finite camera-to-cluster line");

		List<BlockPos> chain = List.of(new BlockPos(0, 0, 0), new BlockPos(1, 0, 0), new BlockPos(1, 1, 0));
		List<BlockClusterer.Cluster> clusters = BlockClusterer.cluster(chain, true);
		check(clusters.size() == 1 && clusters.getFirst().blocks().size() == 3,
			"face-adjacent selected blocks form one cluster");
		check(clusters.getFirst().center().equals(new Vec3(7.0 / 6.0, 5.0 / 6.0, 0.5)),
			"cluster uses the mean block center for its label and tracer");
		check(BlockClusterer.cluster(chain, false).size() == 3,
			"disabling connected blocks leaves one cluster per exact match");
		check(BlockClusterer.cluster(List.of(new BlockPos(0, 0, 0), new BlockPos(1, 1, 0)), true).size() == 2,
			"edge/corner contact does not connect blocks");
		check(BlockClusterer.cluster(List.of(new BlockPos(0, 0, 0), new BlockPos(1, 0, 0),
			new BlockPos(4, 0, 0)), true).size() == 2, "disconnected voxel groups retain separate labels and tracers");

		BlockEspScanner.Candidates candidates = new BlockEspScanner.Candidates();
		for (int index = 2_048; index >= 0; index--) {
			candidates.offer(new BlockPos(index, 0, 0));
		}
		check(candidates.positions.size() == 2_049,
			"scanner retains every match beyond the former 1,024-position cap");
		check(candidates.positions.contains(new BlockPos(0, 0, 0))
			&& candidates.positions.contains(new BlockPos(2_048, 0, 0)),
			"uncapped collection keeps matches regardless of scan order");
	}

	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}

	private static void checkTracerRay(Vec3 camera, Vector3f direction, Vec3 target,
		double distance, String message) {
		BlockEspTracerGeometry.Endpoints endpoints = BlockEspTracerGeometry.endpoints(camera, direction, target, distance);
		Vector3f normalized = new Vector3f(direction).normalize();
		Vec3 expectedStart = new Vec3(normalized.x() * distance, normalized.y() * distance,
			normalized.z() * distance);
		Vec3 expectedEnd = target.subtract(camera);
		check(close(endpoints.start(), expectedStart), message + " (start)");
		check(close(endpoints.end(), expectedEnd), message + " (cluster target)");
		check(close(endpoints.end().add(camera), target), message + " (world reconstruction)");
	}

	private static boolean close(Vec3 first, Vec3 second) {
		return Math.abs(first.x - second.x) < 1.0e-6
			&& Math.abs(first.y - second.y) < 1.0e-6
			&& Math.abs(first.z - second.z) < 1.0e-6;
	}
}
