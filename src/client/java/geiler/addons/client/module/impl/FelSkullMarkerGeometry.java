package geiler.addons.client.module.impl;

import net.minecraft.world.entity.LivingEntity;

/** Floor marker bounds ported from SkyHanni's stationary-Fel filled waypoint geometry. */
final class FelSkullMarkerGeometry {
	private FelSkullMarkerGeometry() { }

	static DungeonMobTargetMemory.Bounds bounds(LivingEntity body) {
		if (body == null) throw new IllegalArgumentException("Fel body is required");
		return bounds(body.getX(), body.getY(), body.getZ());
	}

	static DungeonMobTargetMemory.Bounds bounds(double bodyX, double bodyY, double bodyZ) {
		if (!Double.isFinite(bodyX) || !Double.isFinite(bodyY) || !Double.isFinite(bodyZ)) {
			throw new IllegalArgumentException("Invalid Fel body position");
		}
		// The marker entity is one block below the visible skull, so retain the source
		// 0.6-block footprint and raise the rendered anchor to the skull's block.
		double markerY = bodyY + 1.0;
		return new DungeonMobTargetMemory.Bounds(
			bodyX - 0.3, markerY - 0.03, bodyZ - 0.3,
			bodyX + 0.3, markerY + 0.57, bodyZ + 0.3);
	}
}
