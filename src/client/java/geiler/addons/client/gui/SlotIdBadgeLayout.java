package geiler.addons.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/** Shared badge geometry for the live slot overlay and its HUD-editor preview. */
public final class SlotIdBadgeLayout {
	public static final int BADGE_HEIGHT = 9;
	public static final int BADGE_PADDING = 2;
	public static final int BADGE_COLOR = 0xB0000000;
	public static final float SMALL_LABEL_SCALE = 0.66f;

	private SlotIdBadgeLayout() { }

	public static List<Badge> layout(List<SlotPosition> slots, int left, int top,
		ToIntFunction<String> textWidth) {
		List<Badge> result = new ArrayList<>(slots.size());
		for (SlotPosition slot : slots) {
			String label = Integer.toString(slot.index());
			float scale = label.length() > 2 ? SMALL_LABEL_SCALE : 1.0f;
			int measuredWidth = Math.round(textWidth.applyAsInt(label) * scale);
			int width = Math.max(8, Math.min(15, measuredWidth + BADGE_PADDING));
			int x = left + slot.x() + 1;
			int y = top + slot.y() + 2;
			result.add(new Badge(x, y, width, label, scale, x + 1, y));
		}
		return List.copyOf(result);
	}

	public record SlotPosition(int index, int x, int y) { }
	public record Badge(int x, int y, int width, String label, float scale, int textX, int textY) { }
}
