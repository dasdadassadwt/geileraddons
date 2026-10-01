package geiler.addons.client.module.impl;

import geiler.addons.client.gui.SlotIdBadgeLayout;
import geiler.addons.client.hud.HudElement;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.SettingGroup;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Shows each runtime menu slot id as a badge on that actual slot. */
public final class SlotIdsModule extends Module implements HudElement {
	public static final SlotIdsModule INSTANCE = new SlotIdsModule();
	private final ColorSetting labelTextColor;

	private SlotIdsModule() {
		this(new Settings());
	}

	private SlotIdsModule(Settings settings) {
		super("Slot IDs", "Shows each container slot's runtime index for macro setup.",
			Category.DEV, settings.labelTextColor);
		this.labelTextColor = settings.labelTextColor;
		group(new SettingGroup("Display", settings.labelTextColor));
	}

	private static final class Settings {
		final ColorSetting labelTextColor = new ColorSetting("Label Text Color", 255, 77, 90, 255);
	}

	public ColorSetting labelTextColor() {
		return labelTextColor;
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
		return Math.max(8, Math.min(15, font.width("41") + SlotIdBadgeLayout.BADGE_PADDING));
	}

	@Override
	public int height(Font font) {
		return SlotIdBadgeLayout.BADGE_HEIGHT;
	}

	@Override
	public boolean visible() {
		return true;
	}

	@Override
	public boolean renderOnHud() {
		return false;
	}

	/** Shows a sample only in Move Elements; live badges remain attached to their actual menu slots. */
	@Override
	public void render(GuiGraphicsExtractor graphics, Font font, int x, int y) {
		String sample = "41";
		int width = width(font);
		graphics.fill(x, y, x + width, y + SlotIdBadgeLayout.BADGE_HEIGHT, SlotIdBadgeLayout.BADGE_COLOR);
		graphics.text(font, sample, x + width - font.width(sample) - 1, y, labelTextColor.argb());
	}
}
