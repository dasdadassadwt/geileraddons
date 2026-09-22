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

	/** Whether this element is active; Move Elements shows its name when it is inactive. */
	boolean visible();

	/** Whether active content belongs on the live HUD; false is useful for editor-only previews. */
	default boolean renderOnHud() {
		return true;
	}

	void render(GuiGraphicsExtractor graphics, Font font, int x, int y);
}
