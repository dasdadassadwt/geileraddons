package geiler.addons.client.render;

import geiler.addons.client.gui.GuiTheme;
import geiler.addons.client.module.impl.VisualModule;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;

import java.util.BitSet;

/** Shared themed label treatment and per-frame placement for labels projected from the world. */
public final class ProjectedLabelRenderer {
	private static final int CELL_SIZE = 16;
	private static final int[] VERTICAL_OFFSETS = {0, -1, 1, -2, 2, -3, 3, -4, 4};
	private static final ThreadLocal<float[]> PROJECTION = ThreadLocal.withInitial(() -> new float[2]);
	private static final BitSet OCCUPIED = new BitSet();
	private static int cellsWide;
	private static int screenWidth;
	private static int screenHeight;

	private ProjectedLabelRenderer() { }

	/** Called once before the mod's projected label elements are drawn each HUD frame. */
	public static void beginFrame() {
		VisualModule.INSTANCE.refreshTheme();
		Minecraft minecraft = Minecraft.getInstance();
		screenWidth = minecraft.getWindow().getGuiScaledWidth();
		screenHeight = minecraft.getWindow().getGuiScaledHeight();
		cellsWide = Math.max(1, (screenWidth + CELL_SIZE - 1) / CELL_SIZE);
		OCCUPIED.clear();
	}

	/** Draws a centered, theme-backed label; {@code signalColor} remains a semantic color cue. */
	public static boolean draw(GuiGraphicsExtractor graphics, Camera camera, Font font, Vec3 world,
		String text, int signalColor, float requestedScale) {
		if (text == null || text.isBlank() || camera == null || font == null || world == null) return false;
		if (screenWidth <= 0 || screenHeight <= 0) beginFrame();
		float[] projected = PROJECTION.get();
		if (!WorldToScreen.projectInto(camera, world, projected)) return false;

		float scale = Float.isFinite(requestedScale) ? Math.max(0.5f, Math.min(3.0f, requestedScale)) : 1.0f;
		int maxWidth = Math.max(1, screenWidth - 16);
		int maxTextWidth = Math.max(1, (int) (maxWidth / scale) - 10);
		String shown = fit(font, text, maxTextWidth);
		int width = Math.min(maxWidth, (int) Math.ceil(font.width(shown) * scale) + 10);
		int height = Math.max(14, (int) Math.ceil(font.lineHeight * scale) + 4);
		if (width >= screenWidth || height >= screenHeight) return false;
		if (projected[0] < -width || projected[0] > screenWidth + width
			|| projected[1] < -height || projected[1] > screenHeight + height) return false;

		int left = clamp(Math.round(projected[0] - width / 2.0f), 2, screenWidth - width - 2);
		int top = Math.round(projected[1] - height - 3);
		for (int offset : VERTICAL_OFFSETS) {
			int candidateTop = clamp(top + offset * (height + 2), 2, screenHeight - height - 2);
			if (!isFree(left, candidateTop, width, height)) continue;
			drawLabel(graphics, font, shown, left, candidateTop, width, height, scale, signalColor);
			occupy(left, candidateTop, width, height);
			return true;
		}
		return false;
	}

	private static void drawLabel(GuiGraphicsExtractor graphics, Font font, String text,
		int x, int y, int width, int height, float scale, int signalColor) {
		GuiTheme.roundedRectBordered(graphics, x, y, width, height, 4,
			GuiTheme.PANEL_TOP, GuiTheme.PANEL_BOTTOM, GuiTheme.BORDER);
		graphics.fill(x + 2, y + 3, x + 4, y + height - 3, 0xFF000000 | (signalColor & 0x00FFFFFF));
		graphics.pose().pushMatrix();
		graphics.pose().translate(x + 7, y + Math.max(1, (height - font.lineHeight * scale) / 2.0f));
		graphics.pose().scale(scale, scale);
		graphics.text(font, text, 0, 0, GuiTheme.TEXT_PRIMARY);
		graphics.pose().popMatrix();
	}

	private static boolean isFree(int x, int y, int width, int height) {
		int minX = Math.max(0, x / CELL_SIZE);
		int maxX = Math.max(minX, (x + width - 1) / CELL_SIZE);
		int minY = Math.max(0, y / CELL_SIZE);
		int maxY = Math.max(minY, (y + height - 1) / CELL_SIZE);
		for (int cellY = minY; cellY <= maxY; cellY++) {
			int base = cellY * cellsWide;
			for (int cellX = minX; cellX <= maxX; cellX++) {
				if (OCCUPIED.get(base + cellX)) return false;
			}
		}
		return true;
	}

	private static void occupy(int x, int y, int width, int height) {
		int minX = Math.max(0, x / CELL_SIZE);
		int maxX = Math.max(minX, (x + width - 1) / CELL_SIZE);
		int minY = Math.max(0, y / CELL_SIZE);
		int maxY = Math.max(minY, (y + height - 1) / CELL_SIZE);
		for (int cellY = minY; cellY <= maxY; cellY++) {
			int base = cellY * cellsWide;
			for (int cellX = minX; cellX <= maxX; cellX++) OCCUPIED.set(base + cellX);
		}
	}

	private static String fit(Font font, String text, int maxWidth) {
		if (font.width(text) <= maxWidth) return text;
		String ellipsis = "…";
		int prefixWidth = Math.max(0, maxWidth - font.width(ellipsis));
		return font.plainSubstrByWidth(text, prefixWidth) + ellipsis;
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
