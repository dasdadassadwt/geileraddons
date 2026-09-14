package geiler.addons.client.module;

import net.minecraft.util.ARGB;

public final class ColorSetting implements Setting {
	public enum Channel { RED, GREEN, BLUE, ALPHA }

	private final String name;
	private int red;
	private int green;
	private int blue;
	private int alpha;
	/**
	 * The picker's hue, kept alongside the colour rather than derived from it on demand.
	 *
	 * <p>Black, white and every grey have no hue to recover - red and blue both collapse to the
	 * same run of greys. Deriving it would snap the picker back to red the moment a colour was
	 * dragged to an edge of the square, so the last hue the user actually chose is remembered
	 * instead. It is display state, not part of the colour, and is never persisted.
	 */
	private float hue;
	private float saturation;
	private float brightness;

	public ColorSetting(String name, int red, int green, int blue, int alpha) {
		this.name = name;
		set(red, green, blue, alpha);
	}

	@Override
	public String name() {
		return name;
	}

	public int red() {
		return red;
	}

	public int green() {
		return green;
	}

	public int blue() {
		return blue;
	}

	public int alpha() {
		return alpha;
	}

	public int channel(Channel channel) {
		return switch (channel) {
			case RED -> red;
			case GREEN -> green;
			case BLUE -> blue;
			case ALPHA -> alpha;
		};
	}

	public void setChannel(Channel channel, int value) {
		value = clamp(value);
		switch (channel) {
			case RED -> red = value;
			case GREEN -> green = value;
			case BLUE -> blue = value;
			case ALPHA -> alpha = value;
		}
		if (channel != Channel.ALPHA) {
			syncHsv();
		}
	}

	public void set(int red, int green, int blue, int alpha) {
		this.red = clamp(red);
		this.green = clamp(green);
		this.blue = clamp(blue);
		this.alpha = clamp(alpha);
		syncHsv();
	}

	// ---- picker ---------------------------------------------------------------------------

	public float hue() {
		return hue;
	}

	public float saturation() {
		return saturation;
	}

	public float brightness() {
		return brightness;
	}

	public void setHue(float hue) {
		setHsv(hue, saturation, brightness);
	}

	public void setSaturationBrightness(float saturation, float brightness) {
		setHsv(hue, saturation, brightness);
	}

	/** Sets the colour from HSV, keeping the hue even where the resulting RGB no longer implies one. */
	public void setHsv(float hue, float saturation, float brightness) {
		this.hue = wrap(hue);
		this.saturation = clamp01(saturation);
		this.brightness = clamp01(brightness);

		float chroma = this.brightness * this.saturation;
		float sector = this.hue * 6.0f;
		float second = chroma * (1 - Math.abs(sector % 2 - 1));
		float floor = this.brightness - chroma;

		float r;
		float g;
		float b;
		switch ((int) sector) {
			case 0: r = chroma; g = second; b = 0; break;
			case 1: r = second; g = chroma; b = 0; break;
			case 2: r = 0; g = chroma; b = second; break;
			case 3: r = 0; g = second; b = chroma; break;
			case 4: r = second; g = 0; b = chroma; break;
			default: r = chroma; g = 0; b = second; break;
		}
		this.red = Math.round((r + floor) * 255);
		this.green = Math.round((g + floor) * 255);
		this.blue = Math.round((b + floor) * 255);
	}

	/** RRGGBBAA, the form the picker's text field reads and writes. */
	public String hex() {
		return String.format("%02X%02X%02X%02X", red, green, blue, alpha);
	}

	/** @return false if the text isn't 6 or 8 hex digits, leaving the colour untouched */
	public boolean setHex(String text) {
		String digits = text.trim();
		if (digits.startsWith("#")) {
			digits = digits.substring(1);
		}
		if (digits.length() != 6 && digits.length() != 8) return false;
		try {
			int r = Integer.parseInt(digits.substring(0, 2), 16);
			int g = Integer.parseInt(digits.substring(2, 4), 16);
			int b = Integer.parseInt(digits.substring(4, 6), 16);
			int a = digits.length() == 8 ? Integer.parseInt(digits.substring(6, 8), 16) : alpha;
			set(r, g, b, a);
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	private void syncHsv() {
		int max = Math.max(red, Math.max(green, blue));
		int min = Math.min(red, Math.min(green, blue));
		brightness = max / 255.0f;
		saturation = max == 0 ? 0 : (max - min) / (float) max;

		if (max == min) return;
		// Only touched when the colour actually has a hue: a grey would otherwise report red and
		// silently drag the picker there.
		float span = max - min;
		float derived;
		if (max == red) {
			derived = ((green - blue) / span) % 6;
		} else if (max == green) {
			derived = (blue - red) / span + 2;
		} else {
			derived = (red - green) / span + 4;
		}
		hue = wrap(derived / 6.0f);
	}

	private static float clamp01(float value) {
		return Math.max(0, Math.min(1, value));
	}

	private static float wrap(float hue) {
		hue %= 1.0f;
		return hue < 0 ? hue + 1.0f : hue;
	}

	/** Packed ARGB int (0xAARRGGBB), matching what MC's vertex/gui color APIs expect. */
	public int argb() {
		return ARGB.color(alpha, red, green, blue);
	}

	/** Same color at full opacity, for swatch previews. */
	public int opaqueArgb() {
		return ARGB.color(255, red, green, blue);
	}

	private static int clamp(int value) {
		return Math.max(0, Math.min(255, value));
	}
}
