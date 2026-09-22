package geiler.addons.client.module.impl;

import geiler.addons.client.gui.SlotIdOverlay;
import geiler.addons.client.hud.HudElement;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Shows container slot indices and provides their preview in the movable HUD editor. */
public final class SlotIdsModule extends Module implements HudElement {
	public static final SlotIdsModule INSTANCE = new SlotIdsModule();

	private SlotIdsModule() {
		super("Slot IDs", "Shows each container slot's runtime index for macro setup.", Category.DEV);
	}

	@Override
	public String id() {
		return "slot_ids";
	}

	@Override
	public String displayName() {
		return "Slot IDs";
	}

	@Override
	public int width(Font font) {
		return SlotIdOverlay.PLAYER_INVENTORY_WIDTH;
	}

	@Override
	public int height(Font font) {
		return SlotIdOverlay.PLAYER_INVENTORY_HEIGHT;
	}

	@Override
	public boolean visible() {
		return isEnabled();
	}

	@Override
	public boolean renderOnHud() {
		// The real overlay is positioned on each vanilla slot; only its sample belongs in Move Elements.
		return false;
	}

	@Override
	public void render(GuiGraphicsExtractor graphics, Font font, int x, int y) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null) {
			SlotIdOverlay.renderMenu(minecraft.player.inventoryMenu, graphics, font, x, y);
		}
	}
}
