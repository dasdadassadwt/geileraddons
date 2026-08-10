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

	private WorldToScreen() {
	}

	/**
	 * @return {x, y} in GUI-scaled coordinates, or null if the point is behind the camera
	 */
	public static float[] project(Camera camera, Vec3 target) {
		Minecraft mc = Minecraft.getInstance();
		Vec3 relative = target.subtract(camera.position());

		Vector3fc forward = camera.forwardVector();
		Vector3fc up = camera.upVector();
		Vector3fc left = camera.leftVector();

		double depth = relative.x * forward.x() + relative.y * forward.y() + relative.z * forward.z();
		if (depth < MIN_DEPTH) return null;

		double vertical = relative.x * up.x() + relative.y * up.y() + relative.z * up.z();
		// leftVector points left, so negating it gives the screen-right axis.
		double horizontal = -(relative.x * left.x() + relative.y * left.y() + relative.z * left.z());

		double tanHalfFov = Math.tan(Math.toRadians(camera.getFov()) / 2.0);
		if (tanHalfFov <= 0) return null;
		// Aspect comes from the real framebuffer; the GUI-scaled size is rounded to whole pixels
		// and would skew x slightly.
		double aspect = (double) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());

		double ndcX = (horizontal / depth) / (tanHalfFov * aspect);
		double ndcY = (vertical / depth) / tanHalfFov;

		float screenX = (float) ((ndcX * 0.5 + 0.5) * mc.getWindow().getGuiScaledWidth());
		float screenY = (float) ((1.0 - (ndcY * 0.5 + 0.5)) * mc.getWindow().getGuiScaledHeight());
		return new float[]{screenX, screenY};
	}
}
