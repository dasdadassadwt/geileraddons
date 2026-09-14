package geiler.addons.client.hud;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** A movable panel on the HUD. Registered once with {@link HudManager}, which owns its position. */
public interface HudElement {
	/** Stable key this element's position is saved under; renaming one loses its placement. */
	String id();

	/** Shown in the Move Elements screen, where the element may not be drawing anything yet. */
	String displayName();

	int width(Font font);

	int height(Font font);

	/** Whether this should draw on the live HUD; the Move Elements screen shows it either way. */
	boolean visible();

	void render(GuiGraphicsExtractor graphics, Font font, int x, int y);
}
