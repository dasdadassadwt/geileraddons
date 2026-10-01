package geiler.addons.client.gui;

import geiler.addons.client.module.ColorSetting;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Shared painting and pointer behavior for every HSV/alpha color picker in the mod. */
final class ColorPickerPalette {
	private static final int CHECKER_SIZE = 4;

	private ColorPickerPalette() { }

	static void saturationSquare(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
		ColorSetting setting) {
		int pure = hueColor(setting.hue());
		for (int i = 0; i < width; i++) {
			int top = GuiTheme.lerpColor(0xFFFFFFFF, pure, i / (float) Math.max(1, width - 1));
			graphics.fillGradient(x + i, y, x + i + 1, y + height, top, 0xFF000000);
		}
		crosshair(graphics, x + Math.round(setting.saturation() * (width - 1)),
			y + Math.round((1 - setting.brightness()) * (height - 1)));
	}

	static void hueBar(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
		ColorSetting setting) {
		for (int i = 0; i < width; i++) graphics.fill(x + i, y, x + i + 1, y + height, hueColor(i / (float) width));
		marker(graphics, x, y, width, height, Math.round(setting.hue() * (width - 1)));
	}

	static void alphaBar(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
		ColorSetting setting) {
		checkerboard(graphics, x, y, width, height);
		int opaque = setting.opaqueArgb();
		for (int i = 0; i < width; i++) {
			int alpha = Math.round(255 * i / (float) Math.max(1, width - 1));
			graphics.fill(x + i, y, x + i + 1, y + height,
				(alpha << 24) | (opaque & 0x00FFFFFF));
		}
		marker(graphics, x, y, width, height, Math.round(setting.alpha() / 255.0f * (width - 1)));
	}

	static void checkerboard(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
		for (int row = 0; row < height; row += CHECKER_SIZE) {
			for (int column = 0; column < width; column += CHECKER_SIZE) {
				boolean light = ((row / CHECKER_SIZE) + (column / CHECKER_SIZE)) % 2 == 0;
				graphics.fill(x + column, y + row, Math.min(x + column + CHECKER_SIZE, x + width),
					Math.min(y + row + CHECKER_SIZE, y + height), light ? 0xFF9A9A9A : 0xFF5E5E5E);
			}
		}
	}

	static void applySquare(ColorSetting setting, double mouseX, double mouseY,
		int x, int y, int width, int height) {
		setting.setSaturationBrightness(fraction(mouseX, x, width),
			1 - fraction(mouseY, y, height));
	}

	static void applyHue(ColorSetting setting, double mouseX, int x, int width) {
		setting.setHue(fraction(mouseX, x, width));
	}

	static void applyAlpha(ColorSetting setting, double mouseX, int x, int width) {
		setting.setChannel(ColorSetting.Channel.ALPHA,
			Math.round(fraction(mouseX, x, width) * 255));
	}

	private static float fraction(double position, int start, int size) {
		return (float) Math.max(0, Math.min(1, (position - start) / Math.max(1, size - 1)));
	}

	private static void crosshair(GuiGraphicsExtractor graphics, int x, int y) {
		graphics.fill(x - 3, y - 1, x - 1, y, 0xFF000000);
		graphics.fill(x + 2, y - 1, x + 4, y, 0xFF000000);
		graphics.fill(x - 1, y - 3, x, y - 1, 0xFF000000);
		graphics.fill(x - 1, y + 2, x, y + 4, 0xFF000000);
		graphics.fill(x - 2, y - 2, x + 3, y - 1, 0xFFFFFFFF);
		graphics.fill(x - 2, y + 1, x + 3, y + 2, 0xFFFFFFFF);
		graphics.fill(x - 2, y - 1, x - 1, y + 1, 0xFFFFFFFF);
		graphics.fill(x + 2, y - 1, x + 3, y + 1, 0xFFFFFFFF);
	}

	private static void marker(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int offset) {
		int markerX = x + offset;
		graphics.fill(markerX - 1, y - 2, markerX + 2, y + height + 2, 0xFF000000);
		graphics.fill(markerX, y - 1, markerX + 1, y + height + 1, 0xFFFFFFFF);
	}

	private static int hueColor(float hue) {
		float sector = (hue - (float) Math.floor(hue)) * 6.0f;
		float rising = sector % 1;
		int up = Math.round(rising * 255);
		int down = 255 - up;
		return switch ((int) sector) {
			case 0 -> 0xFFFF0000 | (up << 8);
			case 1 -> 0xFF00FF00 | (down << 16);
			case 2 -> 0xFF00FF00 | up;
			case 3 -> 0xFF0000FF | (down << 8);
			case 4 -> 0xFF0000FF | (up << 16);
			default -> 0xFFFF0000 | down;
		};
	}
}
