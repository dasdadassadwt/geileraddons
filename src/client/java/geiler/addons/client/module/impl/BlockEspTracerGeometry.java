package geiler.addons.client.module.impl;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

/** Camera-relative tracer endpoints whose start stays on the camera's crosshair ray. */
public final class BlockEspTracerGeometry {
	private BlockEspTracerGeometry() { }

	public static Endpoints endpoints(Vec3 cameraPosition, Vector3fc cameraForward,
		Vec3 target, double startDistance) {
		double length = Math.sqrt(cameraForward.x() * cameraForward.x()
			+ cameraForward.y() * cameraForward.y() + cameraForward.z() * cameraForward.z());
		if (!Double.isFinite(length) || length <= 1.0e-9) {
			return new Endpoints(Vec3.ZERO, target.subtract(cameraPosition));
		}
		double distance = Double.isFinite(startDistance) ? Math.max(0, startDistance) : 0;
		Vec3 start = new Vec3(cameraForward.x() / length * distance,
			cameraForward.y() / length * distance, cameraForward.z() / length * distance);
		return new Endpoints(start, target.subtract(cameraPosition));
	}

	public record Endpoints(Vec3 start, Vec3 end) { }
}
