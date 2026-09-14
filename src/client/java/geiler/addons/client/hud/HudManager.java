package geiler.addons.client.hud;

import geiler.addons.client.gui.MoveUiScreen;
import geiler.addons.client.module.impl.VisualModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The registry of movable HUD panels and where each one sits.
 *
 * <p>Positions are stored as a fraction of the room the element has to move in, not as pixels:
 * 0 is flush against the left or top edge and 1 is flush against the right or bottom, so an
 * element placed on one resolution stays fully on screen at any other, and changing the GUI scale
 * doesn't push a panel off the edge.
 */
public final class HudManager {
	private static final List<HudElement> elements = new ArrayList<>();
	private static final Map<String, float[]> positions = new LinkedHashMap<>();

	private HudManager() {
	}

	public static void register(HudElement element, float defaultX, float defaultY) {
		elements.add(element);
		positions.putIfAbsent(element.id(), new float[]{clamp(defaultX), clamp(defaultY)});
	}

	public static List<HudElement> elements() {
		return elements;
	}

	public static int x(HudElement element, Font font, int screenWidth) {
		return Math.round(fraction(element)[0] * travel(screenWidth, element.width(font)));
	}

	public static int y(HudElement element, Font font, int screenHeight) {
		return Math.round(fraction(element)[1] * travel(screenHeight, element.height(font)));
	}

	public static void setPosition(HudElement element, int x, int y, Font font, int screenWidth, int screenHeight) {
		float[] position = fraction(element);
		position[0] = clamp(x / (float) travel(screenWidth, element.width(font)));
		position[1] = clamp(y / (float) travel(screenHeight, element.height(font)));
	}

	/** Draws every visible element. Skipped while the Move Elements screen has its own copies up. */
	public static void render(GuiGraphicsExtractor graphics) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.options.hideGui || mc.screen instanceof MoveUiScreen) return;
		VisualModule.INSTANCE.refreshTheme();

		Font font = mc.font;
		int width = graphics.guiWidth();
		int height = graphics.guiHeight();
		for (HudElement element : elements) {
			if (!element.visible()) continue;
			element.render(graphics, font, x(element, font, width), y(element, font, height));
		}
	}

	public static Map<String, float[]> positions() {
		return positions;
	}

	/** Applies saved positions; anything not mentioned keeps the default it registered with. */
	public static void restore(Map<String, float[]> saved) {
		for (Map.Entry<String, float[]> entry : saved.entrySet()) {
			float[] value = entry.getValue();
			if (value == null || value.length != 2) continue;
			positions.put(entry.getKey(), new float[]{clamp(value[0]), clamp(value[1])});
		}
	}

	private static float[] fraction(HudElement element) {
		return positions.computeIfAbsent(element.id(), id -> new float[]{0, 0});
	}

	/** How many pixels the element can travel; never zero, so the division above is always safe. */
	private static int travel(int screenSize, int elementSize) {
		return Math.max(1, screenSize - elementSize);
	}

	private static float clamp(float value) {
		return Math.max(0, Math.min(1, value));
	}

	/** Snapshot for the config, copied so a later drag can't rewrite what is being serialized. */
	public static Map<String, float[]> snapshot() {
		Map<String, float[]> copy = new HashMap<>();
		for (Map.Entry<String, float[]> entry : positions.entrySet()) {
			copy.put(entry.getKey(), entry.getValue().clone());
		}
		return copy;
	}
}
