package geiler.addons.client.module;

import net.minecraft.util.ARGB;

public final class ColorSetting {
	public enum Channel { RED, GREEN, BLUE, ALPHA }

	private final String name;
	private int red;
	private int green;
	private int blue;
	private int alpha;

	public ColorSetting(String name, int red, int green, int blue, int alpha) {
		this.name = name;
		this.red = clamp(red);
		this.green = clamp(green);
		this.blue = clamp(blue);
		this.alpha = clamp(alpha);
	}

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
	}

	public void set(int red, int green, int blue, int alpha) {
		this.red = clamp(red);
		this.green = clamp(green);
		this.blue = clamp(blue);
		this.alpha = clamp(alpha);
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
