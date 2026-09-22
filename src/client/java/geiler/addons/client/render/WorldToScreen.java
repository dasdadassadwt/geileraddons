package geiler.addons.client.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

/**
 * Projects a world position onto GUI coordinates, so labels can be drawn with the real font on the
 * HUD instead of as in-world geometry.
 *
 * <p>Done from the camera's own basis vectors and field of view rather than by multiplying through
 * a projection matrix: every input here is a value this codebase already reads elsewhere, so there
 * is no guessing at what a given matrix is pre-multiplied by.
 */
public final class WorldToScreen {
	/** Anything nearer than this along the view axis is level with or behind the eye. */
	private static final double MIN_DEPTH = 0.05;
	private static Camera cachedCamera;
	private static double cachedFov = Double.NaN;
	private static int cachedWidth;
	private static int cachedHeight;
	private static double cachedTanHalfFov;
	private static double cachedAspect;

	private WorldToScreen() {
	}

	/**
	 * @return {x, y} in GUI-scaled coordinates, or null if the point is behind the camera
	 */
	public static float[] project(Camera camera, Vec3 target) {
		float[] projected = new float[2];
		return projectInto(camera, target, projected) ? projected : null;
	}

	/** Projects into caller-owned storage to avoid one array allocation per rendered label. */
	public static boolean projectInto(Camera camera, Vec3 target, float[] projected) {
		if (camera == null || target == null || projected == null || projected.length < 2) return false;
		Minecraft mc = Minecraft.getInstance();
		Vec3 cameraPosition = camera.position();
		double relativeX = target.x - cameraPosition.x;
		double relativeY = target.y - cameraPosition.y;
		double relativeZ = target.z - cameraPosition.z;

		Vector3fc forward = camera.forwardVector();
		Vector3fc up = camera.upVector();
		Vector3fc left = camera.leftVector();

		double depth = relativeX * forward.x() + relativeY * forward.y() + relativeZ * forward.z();
		if (depth < MIN_DEPTH) return false;

		double vertical = relativeX * up.x() + relativeY * up.y() + relativeZ * up.z();
		// leftVector points left, so negating it gives the screen-right axis.
		double horizontal = -(relativeX * left.x() + relativeY * left.y() + relativeZ * left.z());
		var window = mc.getWindow();
		int framebufferWidth = window.getWidth();
		int framebufferHeight = window.getHeight();
		double fov = camera.getFov();
		if (camera != cachedCamera || framebufferWidth != cachedWidth || framebufferHeight != cachedHeight
			|| fov != cachedFov) {
			cachedCamera = camera;
			cachedWidth = framebufferWidth;
			cachedHeight = framebufferHeight;
			cachedFov = fov;
			cachedTanHalfFov = Math.tan(Math.toRadians(fov) / 2.0);
			cachedAspect = (double) framebufferWidth / Math.max(1, framebufferHeight);
		}
		if (cachedTanHalfFov <= 0) return false;
		// Aspect comes from the real framebuffer; the GUI-scaled size is rounded to whole pixels
		// and would skew x slightly.

		double ndcX = (horizontal / depth) / (cachedTanHalfFov * cachedAspect);
		double ndcY = (vertical / depth) / cachedTanHalfFov;

		projected[0] = (float) ((ndcX * 0.5 + 0.5) * window.getGuiScaledWidth());
		projected[1] = (float) ((1.0 - (ndcY * 0.5 + 0.5)) * window.getGuiScaledHeight());
		return Float.isFinite(projected[0]) && Float.isFinite(projected[1]);
	}
}
