package geiler.addons.client.module.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure grid rules shared by the editor input and offline layout checks. */
public final class InventoryButtonLayout {
	public static final int CELL_SIZE = 18;
	public static final int FIRST_SLOT_X = 8;
	public static final int FIRST_SLOT_Y = 18;

	private InventoryButtonLayout() { }

	/** Grid cells share the exact 18px lattice used by vanilla inventory slots. */
	public static int gridX(double cursorX, int inventoryLeft) {
		return (int) Math.round((cursorX - (inventoryLeft + FIRST_SLOT_X) - CELL_SIZE / 2.0) / CELL_SIZE);
	}

	public static int gridY(double cursorY, int inventoryTop) {
		return (int) Math.round((cursorY - (inventoryTop + FIRST_SLOT_Y) - CELL_SIZE / 2.0) / CELL_SIZE);
	}

	public static int pixelX(int gridX, int inventoryLeft) {
		return inventoryLeft + FIRST_SLOT_X + gridX * CELL_SIZE;
	}

	public static int pixelY(int gridY, int inventoryTop) {
		return inventoryTop + FIRST_SLOT_Y + gridY * CELL_SIZE;
	}

	/** Pixel location for a persisted placement, including its pre-slot-grid compatibility anchor. */
	public static int placementPixelX(InventoryButtonPlacement placement, int inventoryLeft) {
		return inventoryLeft + (placement.slotAligned() ? FIRST_SLOT_X : 0) + placement.gridX() * CELL_SIZE;
	}

	public static int placementPixelY(InventoryButtonPlacement placement, int inventoryTop) {
		return inventoryTop + (placement.slotAligned() ? FIRST_SLOT_Y : 0) + placement.gridY() * CELL_SIZE;
	}

	public static Bounds inventoryBounds(int left, int top, int width, int height) {
		return new Bounds(left, top, width, height);
	}

	/** Tests a proposed cell against the viewport, vanilla UI, and other button placements. */
	public static boolean canPlace(List<InventoryButtonPlacement> placements, int movingId,
		int gridX, int gridY, int screenWidth, int screenHeight, int inventoryLeft, int inventoryTop,
		List<Bounds> blockedBounds) {
		if (!fitsViewportAndBounds(gridX, gridY, screenWidth, screenHeight, inventoryLeft, inventoryTop,
			blockedBounds)) return false;
		int candidateX = pixelX(gridX, inventoryLeft);
		int candidateY = pixelY(gridY, inventoryTop);
		Bounds candidate = new Bounds(candidateX, candidateY, CELL_SIZE, CELL_SIZE);
		for (InventoryButtonPlacement placement : placements) {
			if (placement.id() == movingId) continue;
			if (candidate.intersects(
				placementPixelX(placement, inventoryLeft), placementPixelY(placement, inventoryTop),
				CELL_SIZE, CELL_SIZE)) return false;
		}
		return true;
	}

	public static boolean isPlacementValid(List<InventoryButtonPlacement> placements,
		InventoryButtonPlacement placement, int screenWidth, int screenHeight,
		int inventoryLeft, int inventoryTop, List<Bounds> blockedBounds) {
		if (placement == null) return false;
		int x = placementPixelX(placement, inventoryLeft);
		int y = placementPixelY(placement, inventoryTop);
		if (!fitsPixelAndBounds(x, y, screenWidth, screenHeight, blockedBounds)) return false;
		Bounds rectangle = new Bounds(x, y, CELL_SIZE, CELL_SIZE);
		for (InventoryButtonPlacement other : placements) {
			if (other.id() == placement.id()) continue;
			if (rectangle.intersects(placementPixelX(other, inventoryLeft),
				placementPixelY(other, inventoryTop), CELL_SIZE, CELL_SIZE)) return false;
		}
		return true;
	}

	public static int invalidCount(List<InventoryButtonPlacement> placements, int screenWidth, int screenHeight,
		int inventoryLeft, int inventoryTop, List<Bounds> blockedBounds) {
		int invalid = 0;
		for (InventoryButtonPlacement placement : placements) {
			if (!isPlacementValid(placements, placement, screenWidth, screenHeight,
				inventoryLeft, inventoryTop, blockedBounds)) invalid++;
		}
		return invalid;
	}

	/**
	 * Moves only currently invalid placements, preserving every valid cell. Reflow order and
	 * equal-distance tie breaks are stable so the same layout produces the same result.
	 */
	public static ReflowResult reflowInvalid(List<InventoryButtonPlacement> placements,
		int screenWidth, int screenHeight, int inventoryLeft, int inventoryTop,
		List<Bounds> blockedBounds) {
		List<InventoryButtonPlacement> ordered = new ArrayList<>(placements);
		ordered.sort(Comparator.comparingInt(InventoryButtonPlacement::id));
		List<Bounds> occupied = new ArrayList<>();
		List<InventoryButtonPlacement> invalid = new ArrayList<>();
		for (InventoryButtonPlacement placement : ordered) {
			int x = placementPixelX(placement, inventoryLeft);
			int y = placementPixelY(placement, inventoryTop);
			Bounds rectangle = new Bounds(x, y, CELL_SIZE, CELL_SIZE);
			if (fitsPixelAndBounds(x, y, screenWidth, screenHeight, blockedBounds)
				&& !overlaps(occupied, x, y)) {
				occupied.add(rectangle);
				continue;
			}
			invalid.add(placement);
		}

		int moved = 0;
		int remaining = 0;
		for (InventoryButtonPlacement placement : invalid) {
			Cell next = nearestFreeCell(placement, occupied,
				screenWidth, screenHeight, inventoryLeft, inventoryTop, blockedBounds);
			if (next == null) {
				remaining++;
				continue;
			}
			placement.setGrid(next.x, next.y);
			occupied.add(new Bounds(pixelX(next.x, inventoryLeft), pixelY(next.y, inventoryTop), CELL_SIZE, CELL_SIZE));
			moved++;
		}
		return new ReflowResult(moved, remaining);
	}

	/** The cell under the cursor, ignoring any saved placements that are currently invalid. */
	public static InventoryButtonPlacement hit(List<InventoryButtonPlacement> placements,
		double cursorX, double cursorY, int inventoryLeft, int inventoryTop,
		int screenWidth, int screenHeight, List<Bounds> blockedBounds) {
		for (int index = placements.size() - 1; index >= 0; index--) {
			InventoryButtonPlacement placement = placements.get(index);
			if (!isPlacementValid(placements, placement, screenWidth, screenHeight,
				inventoryLeft, inventoryTop, blockedBounds)) continue;
			int x = placementPixelX(placement, inventoryLeft);
			int y = placementPixelY(placement, inventoryTop);
			if (cursorX >= x && cursorX < x + CELL_SIZE && cursorY >= y && cursorY < y + CELL_SIZE) {
				return placement;
			}
		}
		return null;
	}

	/** Finds a saved placement even when its cell has become invalid, provided it is still visible. */
	public static InventoryButtonPlacement hitSaved(List<InventoryButtonPlacement> placements,
		double cursorX, double cursorY, int inventoryLeft, int inventoryTop,
		int screenWidth, int screenHeight) {
		for (int index = placements.size() - 1; index >= 0; index--) {
			InventoryButtonPlacement placement = placements.get(index);
			int x = placementPixelX(placement, inventoryLeft);
			int y = placementPixelY(placement, inventoryTop);
			if (x >= 0 && y >= 0 && x + CELL_SIZE <= screenWidth && y + CELL_SIZE <= screenHeight
				&& cursorX >= x && cursorX < x + CELL_SIZE && cursorY >= y && cursorY < y + CELL_SIZE) {
				return placement;
			}
		}
		return null;
	}

	/** Whether a grid cell is a visible exterior cell, without considering button occupancy. */
	public static boolean fitsViewportAndBounds(int gridX, int gridY, int screenWidth, int screenHeight,
		int inventoryLeft, int inventoryTop, List<Bounds> blockedBounds) {
		int x = pixelX(gridX, inventoryLeft);
		int y = pixelY(gridY, inventoryTop);
		if (x < 0 || y < 0 || x + CELL_SIZE > screenWidth || y + CELL_SIZE > screenHeight) return false;
		if (blockedBounds == null) return true;
		for (Bounds bounds : blockedBounds) {
			if (bounds != null && bounds.intersects(x, y, CELL_SIZE, CELL_SIZE)) return false;
		}
		return true;
	}

	private static Cell nearestFreeCell(InventoryButtonPlacement placement, List<Bounds> occupied,
		int screenWidth, int screenHeight, int inventoryLeft, int inventoryTop,
		List<Bounds> blockedBounds) {
		int originX = inventoryLeft + FIRST_SLOT_X;
		int originY = inventoryTop + FIRST_SLOT_Y;
		int minX = (int) Math.ceil(-originX / (double) CELL_SIZE);
		int minY = (int) Math.ceil(-originY / (double) CELL_SIZE);
		int maxX = (int) Math.floor((screenWidth - CELL_SIZE - originX) / (double) CELL_SIZE);
		int maxY = (int) Math.floor((screenHeight - CELL_SIZE - originY) / (double) CELL_SIZE);
		long desiredPixelX = placementPixelX(placement, inventoryLeft);
		long desiredPixelY = placementPixelY(placement, inventoryTop);
		Cell best = null;
		long bestDistance = Long.MAX_VALUE;
		for (int y = minY; y <= maxY; y++) {
			for (int x = minX; x <= maxX; x++) {
				int cellX = pixelX(x, inventoryLeft);
				int cellY = pixelY(y, inventoryTop);
				if (!fitsPixelAndBounds(cellX, cellY, screenWidth, screenHeight, blockedBounds)
					|| overlaps(occupied, cellX, cellY)) continue;
				long dx = cellX - desiredPixelX;
				long dy = cellY - desiredPixelY;
				long distance = dx * dx + dy * dy;
				if (distance < bestDistance || (distance == bestDistance && precedes(x, y, best))) {
					best = new Cell(x, y);
					bestDistance = distance;
				}
			}
		}
		return best;
	}

	private static boolean fitsPixelAndBounds(int x, int y, int screenWidth, int screenHeight,
		List<Bounds> blockedBounds) {
		if (x < 0 || y < 0 || x + CELL_SIZE > screenWidth || y + CELL_SIZE > screenHeight) return false;
		if (blockedBounds == null) return true;
		for (Bounds bounds : blockedBounds) {
			if (bounds != null && bounds.intersects(x, y, CELL_SIZE, CELL_SIZE)) return false;
		}
		return true;
	}

	private static boolean overlaps(List<Bounds> occupied, int x, int y) {
		for (Bounds bounds : occupied) {
			if (bounds.intersects(x, y, CELL_SIZE, CELL_SIZE)) return true;
		}
		return false;
	}

	private static boolean precedes(int x, int y, Cell other) {
		return other == null || y < other.y || (y == other.y && x < other.x);
	}

	public record Bounds(int x, int y, int width, int height) {
		public boolean intersects(int otherX, int otherY, int otherWidth, int otherHeight) {
			return otherX < x + width && otherX + otherWidth > x
				&& otherY < y + height && otherY + otherHeight > y;
		}
	}

	public record ReflowResult(int moved, int remaining) { }
	private record Cell(int x, int y) { }
}
