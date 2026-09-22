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

		List<SlotIdBadgeLayout.Badge> live = SlotIdBadgeLayout.layout(playerInventory,
			0, 0, label -> label.length() * 6);
		List<SlotIdBadgeLayout.Badge> preview = SlotIdBadgeLayout.layout(playerInventory,
			31, 47, label -> label.length() * 6);
		check(live.size() == 46 && preview.size() == 46,
			"live overlay and editor preview use every player menu slot");
		for (int i = 0; i < live.size(); i++) {
			SlotIdBadgeLayout.Badge liveBadge = live.get(i);
			SlotIdBadgeLayout.Badge previewBadge = preview.get(i);
			check(liveBadge.label().equals(Integer.toString(i)), "menu slot labels retain runtime indices");
			check(previewBadge.x() == liveBadge.x() + 31 && previewBadge.y() == liveBadge.y() + 47,
				"preview badges translate the live slot geometry without changing its layout");
			check(previewBadge.width() == liveBadge.width() && previewBadge.scale() == liveBadge.scale(),
				"preview and live badges share sizing and text scaling");
		}

		checkBadge(live.get(0), 155, 30, 8, "0", "crafting result slot");
		checkBadge(live.get(5), 9, 10, 8, "5", "first armor slot");
		checkBadge(live.get(9), 9, 86, 8, "9", "first main-inventory slot");
		checkBadge(live.get(36), 9, 144, 14, "36", "first hotbar slot");
		checkBadge(live.get(45), 78, 64, 14, "45", "offhand slot");
		check(SlotIdBadgeLayout.BADGE_COLOR == 0xB0000000 && SlotIdBadgeLayout.BADGE_HEIGHT == 9,
			"preview and live rendering share badge background and height");
		check(SlotIdOverlay.PLAYER_INVENTORY_WIDTH == 176
			&& SlotIdsModule.INSTANCE.width(null) == 176
			&& SlotIdsModule.INSTANCE.height(null) == 166,
			"HUD editor preview uses the standard 176 by 166 inventory footprint");

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
