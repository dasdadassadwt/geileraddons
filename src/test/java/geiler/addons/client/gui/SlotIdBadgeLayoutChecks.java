package geiler.addons.client.gui;

import geiler.addons.client.module.impl.SlotIdsModule;

import java.util.ArrayList;
import java.util.List;

/** Offline checks for the shared live-overlay and player-inventory preview badge layout. */
public final class SlotIdBadgeLayoutChecks {
	private SlotIdBadgeLayoutChecks() { }

	public static void run() {
		List<SlotIdBadgeLayout.SlotPosition> playerInventory = playerInventorySlots();
		check(playerInventory.size() == 46, "the preview includes all 46 player InventoryMenu slots");

		List<SlotIdBadgeLayout.Badge> badges = SlotIdBadgeLayout.layout(playerInventory,
			0, 0, label -> label.length() * 6);
		check(badges.size() == 46, "the layout includes all 46 player menu slots");
		for (int i = 0; i < badges.size(); i++) {
			SlotIdBadgeLayout.Badge badge = badges.get(i);
			check(badge.label().equals(Integer.toString(i)), "menu slot labels retain runtime indices");
			check(badge.x() + badge.width() <= playerInventory.get(i).x() + 16
				&& badge.y() == playerInventory.get(i).y() + 1,
				"each number is anchored inside the slot's top-right corner");
		}

		checkBadge(badges.get(0), 161, 29, 8, "0", "crafting result slot");
		checkBadge(badges.get(5), 15, 9, 8, "5", "first armor slot");
		checkBadge(badges.get(9), 15, 85, 8, "9", "first main-inventory slot");
		checkBadge(badges.get(36), 9, 143, 14, "36", "first hotbar slot");
		checkBadge(badges.get(45), 78, 63, 14, "45", "offhand slot");
		check(SlotIdBadgeLayout.BADGE_COLOR == 0xA0000000 && SlotIdBadgeLayout.BADGE_HEIGHT == 9
			&& SlotIdBadgeLayout.LABEL_COLOR == 0xFFFF4D5A,
			"the top-right badges use their compact dark backing and default-red labels");
		check(SlotIdsModule.INSTANCE.id().equals("slot_ids") && !SlotIdsModule.INSTANCE.renderOnHud()
			&& SlotIdsModule.INSTANCE.labelTextColor().argb() == SlotIdBadgeLayout.LABEL_COLOR,
			"Move Elements uses a non-HUD sample with the configurable label's default color");

		List<SlotIdBadgeLayout.Badge> largeIndex = SlotIdBadgeLayout.layout(
			List.of(new SlotIdBadgeLayout.SlotPosition(123, 0, 0)), 0, 0, label -> label.length() * 6);
		check(largeIndex.getFirst().scale() == SlotIdBadgeLayout.SMALL_LABEL_SCALE,
			"long slot ids keep the live overlay's reduced text scale");
	}

	private static List<SlotIdBadgeLayout.SlotPosition> playerInventorySlots() {
		List<SlotIdBadgeLayout.SlotPosition> slots = new ArrayList<>(46);
		slots.add(new SlotIdBadgeLayout.SlotPosition(0, 154, 28));
		for (int index = 1; index <= 4; index++) {
			slots.add(new SlotIdBadgeLayout.SlotPosition(index,
				98 + (index - 1) % 2 * 18, 18 + (index - 1) / 2 * 18));
		}
		for (int index = 5; index <= 8; index++) {
			slots.add(new SlotIdBadgeLayout.SlotPosition(index, 8, 8 + (index - 5) * 18));
		}
		for (int index = 9; index <= 35; index++) {
			int offset = index - 9;
			slots.add(new SlotIdBadgeLayout.SlotPosition(index,
				8 + offset % 9 * 18, 84 + offset / 9 * 18));
		}
		for (int index = 36; index <= 44; index++) {
			slots.add(new SlotIdBadgeLayout.SlotPosition(index, 8 + (index - 36) * 18, 142));
		}
		slots.add(new SlotIdBadgeLayout.SlotPosition(45, 77, 62));
		return List.copyOf(slots);
	}

	private static void checkBadge(SlotIdBadgeLayout.Badge badge, int x, int y, int width,
		String label, String name) {
		check(badge.x() == x && badge.y() == y && badge.width() == width && badge.label().equals(label),
			name + " uses the vanilla InventoryMenu slot location and shared badge style");
	}

	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}
}
