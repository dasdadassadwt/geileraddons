package geiler.addons.client.farming;

/** Pure geometry helpers for the Pest Highlighter's smooth vertical ring animation. */
public final class PestAnimation {
	private PestAnimation() {
	}

	/** A world-space point on the ring selected as the best tracer endpoint. */
	public record RingTarget(double x, double y, double z, int ringIndex) {
	}

	/**
	 * Returns a triangular-wave position between the supplied feet and head heights.
	 *
	 * @param feetY lower endpoint in world coordinates
	 * @param headY upper endpoint in world coordinates
	 * @param elapsedTicks client game time including the current partial tick
	 * @param cyclesPerSecond animation speed
	 */
	public static double verticalPosition(double feetY, double headY, double elapsedTicks,
		double cyclesPerSecond) {
		if (!Double.isFinite(feetY) || !Double.isFinite(headY)) return 0;
		if (!Double.isFinite(elapsedTicks) || !Double.isFinite(cyclesPerSecond)) return feetY;
		if (headY < feetY) {
			double swapped = feetY;
			feetY = headY;
			headY = swapped;
		}
		if (headY == feetY) return feetY;

		double phase = elapsedTicks * cyclesPerSecond / 20.0;
		return feetY + (headY - feetY) * triangleWave(phase);
	}

	/**
	 * Returns one animated height per ring. The phase offset is stable per index, so adding a ring
	 * does not make the other rings jump to a new endpoint.
	 */
	public static double[] ringPositions(double feetY, double headY, double elapsedTicks,
		double cyclesPerSecond, int ringCount) {
		int count = Math.max(1, Math.min(8, ringCount));
		double[] result = new double[count];
		for (int index = 0; index < count; index++) {
			result[index] = verticalPosition(feetY, headY, elapsedTicks + (20.0 / Math.max(0.0001, cyclesPerSecond)) * index / count,
				cyclesPerSecond);
		}
		return result;
	}

	/**
	 * Chooses the point on the nearest ring circumference that faces the camera. Using the same
	 * selected point for the tracer and ring renderer keeps the line attached while the ring moves.
	 */
	public static RingTarget closestRingTarget(double centerX, double centerZ, double cameraX,
		double cameraY, double cameraZ, float radius, double[] ringHeights) {
		if (ringHeights == null || ringHeights.length == 0) {
			return new RingTarget(centerX, cameraY, centerZ, -1);
		}
		double dx = cameraX - centerX;
		double dz = cameraZ - centerZ;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		if (!(horizontal > 1.0e-8) || !Double.isFinite(horizontal)) {
			dx = 1.0;
			dz = 0.0;
			horizontal = 1.0;
		}
		double targetX = centerX + dx / horizontal * Math.max(0.0, radius);
		double targetZ = centerZ + dz / horizontal * Math.max(0.0, radius);
		int best = 0;
		double bestDistance = Double.POSITIVE_INFINITY;
		for (int index = 0; index < ringHeights.length; index++) {
			double y = ringHeights[index];
			if (!Double.isFinite(y)) continue;
			double distance = squaredDistance(cameraX, cameraY, cameraZ, targetX, y, targetZ);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = index;
			}
		}
		double y = Double.isFinite(ringHeights[best]) ? ringHeights[best] : cameraY;
		return new RingTarget(targetX, y, targetZ, best);
	}

	private static double squaredDistance(double ax, double ay, double az, double bx, double by, double bz) {
		double dx = ax - bx;
		double dy = ay - by;
		double dz = az - bz;
		return dx * dx + dy * dy + dz * dz;
	}

	/** Repeats 0 -> 1 -> 0 once per unit phase. */
	static double triangleWave(double phase) {
		if (!Double.isFinite(phase)) return 0;
		double cycle = phase - Math.floor(phase);
		return cycle < 0.5 ? cycle * 2.0 : 2.0 - cycle * 2.0;
	}
}
