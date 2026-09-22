package geiler.addons.client.macro;

/** Persisted world placement and trigger/render settings for one macro event stack. */
public final class MacroWorldRegion {
	public enum Shape { SQUARE, CIRCLE, RING }

	private boolean placed;
	private String worldKey = "";
	private double x;
	private double y;
	private double z;
	private Shape shape = Shape.CIRCLE;
	private double size = 8;
	private double innerSize = 4;
	private double yTolerance = 3;
	private int repeatDelayMillis = 1_000;
	private boolean oncePerWorld;
	private int color = 0xFF43D9C4;
	private int fillOpacity = 22;
	private float lineWidth = 2.5f;

	public boolean placed() { return placed; }
	public String worldKey() { return worldKey; }
	public double x() { return x; }
	public double y() { return y; }
	public double z() { return z; }
	public Shape shape() { return shape; }
	public double size() { return size; }
	public double innerSize() { return innerSize; }
	public double yTolerance() { return yTolerance; }
	public int repeatDelayMillis() { return repeatDelayMillis; }
	public boolean oncePerWorld() { return oncePerWorld; }
	public int color() { return color; }
	public int fillOpacity() { return fillOpacity; }
	public float lineWidth() { return lineWidth; }

	public void place(String ownerWorldKey, double centerX, double centerY, double centerZ) {
		if (ownerWorldKey == null || ownerWorldKey.isBlank()
			|| !Double.isFinite(centerX) || !Double.isFinite(centerY) || !Double.isFinite(centerZ)) {
			clearPlacement();
			return;
		}
		placed = true;
		worldKey = ownerWorldKey.substring(0, Math.min(256, ownerWorldKey.length()));
		x = centerX;
		y = centerY;
		z = centerZ;
	}

	public void restorePlacement(boolean isPlaced, String ownerWorldKey, double centerX, double centerY, double centerZ) {
		place(ownerWorldKey, centerX, centerY, centerZ);
		if (!isPlaced) clearPlacement();
	}

	public void clearPlacement() {
		placed = false;
		worldKey = "";
		x = y = z = 0;
	}

	public void setShape(Shape value) { shape = value == null ? Shape.CIRCLE : value; }
	public void setSize(double value) {
		size = Double.isFinite(value) ? clamp(value, 1, 128) : 8;
		innerSize = Math.min(innerSize, size - 0.25);
	}
	public void setInnerSize(double value) { innerSize = Double.isFinite(value) ? clamp(value, 0, size - 0.25) : 4; }
	public void setYTolerance(double value) { yTolerance = Double.isFinite(value) ? clamp(value, 0, 64) : 3; }
	public void setRepeatDelayMillis(int value) { repeatDelayMillis = clamp(value, 50, 3_600_000); }
	public void setOncePerWorld(boolean value) { oncePerWorld = value; }
	public void setColor(int value) { color = value | 0xFF000000; }
	public void setFillOpacity(int value) { fillOpacity = clamp(value, 0, 180); }
	public void setLineWidth(float value) { lineWidth = Float.isFinite(value) ? (float) clamp(value, 1, 8) : 2.5f; }

	/** Shape sizes are radii for round shapes and half-widths for squares. */
	public boolean contains(String currentWorldKey, double px, double py, double pz) {
		if (!placed || currentWorldKey == null || !worldKey.equals(currentWorldKey)
			|| !Double.isFinite(px) || !Double.isFinite(py) || !Double.isFinite(pz)
			|| Math.abs(py - y) > yTolerance) return false;
		double dx = Math.abs(px - x);
		double dz = Math.abs(pz - z);
		return switch (shape) {
			case SQUARE -> dx <= size && dz <= size;
			case CIRCLE -> dx * dx + dz * dz <= size * size;
			case RING -> {
				double distanceSquared = dx * dx + dz * dz;
				yield distanceSquared <= size * size && distanceSquared >= innerSize * innerSize;
			}
		};
	}

	public void copyFrom(MacroWorldRegion other) {
		if (other == null) return;
		restorePlacement(other.placed, other.worldKey, other.x, other.y, other.z);
		setShape(other.shape);
		setSize(other.size);
		setInnerSize(other.innerSize);
		setYTolerance(other.yTolerance);
		setRepeatDelayMillis(other.repeatDelayMillis);
		setOncePerWorld(other.oncePerWorld);
		setColor(other.color);
		setFillOpacity(other.fillOpacity);
		setLineWidth(other.lineWidth);
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
