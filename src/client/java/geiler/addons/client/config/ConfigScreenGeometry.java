package geiler.addons.client.config;

/** Pure responsive geometry shared by the destructive profile dialogs and their hit targets. */
public final class ConfigScreenGeometry {
	private ConfigScreenGeometry() { }

	public record Rect(int x, int y, int width, int height) {
		public int right() { return x + width; }
		public int bottom() { return y + height; }
		public boolean contains(int px, int py) { return px >= x && px < right() && py >= y && py < bottom(); }
		public boolean overlaps(Rect other) {
			return other != null && x < other.right() && right() > other.x && y < other.bottom() && bottom() > other.y;
		}
	}
	public record Footer(Rect folder, Rect action, Rect done) { }
	public record ResetFooter(Rect back, Rect action) { }

	public static Rect panel(int screenWidth, int screenHeight, int maxWidth, int maxHeight) {
		int width = Math.max(1, Math.min(maxWidth, screenWidth - 16));
		int height = Math.max(1, Math.min(maxHeight, screenHeight - 16));
		return new Rect((screenWidth - width) / 2, (screenHeight - height) / 2, width, height);
	}

	public static Rect content(Rect panel, int topInset, int bottomInset) {
		int top = panel.y() + topInset;
		int bottom = Math.max(top, panel.bottom() - bottomInset);
		return new Rect(panel.x() + 10, top, Math.max(0, panel.width() - 20), bottom - top);
	}

	public static boolean compactProfilePanel(Rect panel) { return panel.height() < 200; }

	public static Rect profileViewport(Rect panel) {
		return compactProfilePanel(panel) ? content(panel, 56, 31) : content(panel, 94, 61);
	}

	public static Rect previewViewport(Rect panel) {
		return compactProfilePanel(panel) ? content(panel, 32, 31) : content(panel, 94, 61);
	}

	public static int visibleRows(Rect viewport, int rowHeight) {
		return rowHeight <= 0 ? 0 : Math.max(0, viewport.height() / rowHeight);
	}

	public static int maxScroll(int totalRows, int visibleRows) {
		return Math.max(0, totalRows - Math.max(0, visibleRows));
	}

	/** Footer buttons are laid out from one set of rectangles, so paint and click bounds cannot diverge. */
	public static Footer transferFooter(Rect panel, boolean confirming) {
		int margin = 12;
		int gap = 6;
		int height = 20;
		int doneWidth = Math.min(42, Math.max(1, panel.width() - margin * 2));
		int actionY = panel.bottom() - 30;
		int available = Math.max(0, panel.width() - margin * 2 - doneWidth - gap * 2);
		int folderWidth = Math.min(panel.width() < 360 ? 72 : 142, Math.max(0, available - 88));
		int wantedAction = confirming ? 178 : 128;
		int actionWidth = Math.min(wantedAction, Math.max(0, available - folderWidth));
		int folderX = panel.x() + margin;
		int doneX = panel.right() - margin - doneWidth;
		int actionX = doneX - gap - actionWidth;
		return new Footer(new Rect(folderX, actionY, folderWidth, height),
			new Rect(actionX, actionY, actionWidth, height), new Rect(doneX, actionY, doneWidth, height));
	}

	public static ResetFooter resetFooter(Rect panel) {
		int margin = 12;
		int gap = 6;
		int height = 22;
		int available = Math.max(2, panel.width() - margin * 2 - gap);
		int backWidth = 74;
		int actionWidth = 160;
		if (available < backWidth + actionWidth) {
			backWidth = Math.min(48, Math.max(1, available / 3));
			actionWidth = Math.max(1, Math.min(actionWidth, available - backWidth));
			backWidth = Math.max(1, available - actionWidth);
		}
		int y = panel.bottom() - 30;
		return new ResetFooter(new Rect(panel.x() + margin, y, backWidth, height),
			new Rect(panel.right() - margin - actionWidth, y, actionWidth, height));
	}
}
