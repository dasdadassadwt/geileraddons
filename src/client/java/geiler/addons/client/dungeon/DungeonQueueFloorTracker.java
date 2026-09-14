package geiler.addons.client.dungeon;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Captures the floor selected in Hypixel's Group Builder and activates it only after the server
 * confirms that the party was queued. This deliberately does not inspect the tab list: lines such
 * as "Catacombs Floor VII" there can describe profile progression instead of the active listing.
 */
public final class DungeonQueueFloorTracker {
	private static final long CANDIDATE_MAX_AGE_NANOS = Duration.ofSeconds(15).toNanos();
	private static final Pattern CURRENTLY_SELECTED = Pattern.compile("(?i)^Currently Selected:\\s*(.+)$");
	private static final Pattern FLOOR = Pattern.compile(
		"(?i)^(?:Floor\\s*)?(VII|VI|IV|V|III|II|I|[1-7])$");
	private static final Pattern LISTING_FLOOR = Pattern.compile(
		"(?i)^Floor:?\\s*(?:Floor\\s*)?(VII|VI|IV|V|III|II|I|[1-7])$");

	private static DungeonFloor candidate;
	private static long candidateCapturedAt;
	private static DungeonFloor joinedListingCandidate;
	private static long joinedListingCapturedAt;
	private static DungeonFloor current;
	private static Object trackedLevel;

	private DungeonQueueFloorTracker() { }

	/** Reads a validated Group Builder confirmation click before the menu closes. */
	public static CaptureResult captureConfirmation(AbstractContainerScreen<?> screen, Slot clickedSlot,
		int button, ContainerInput input) {
		if (screen == null || clickedSlot == null || button != 0 || input != ContainerInput.PICKUP) {
			return CaptureResult.ignored();
		}
		if (!clean(screen.getTitle().getString()).equalsIgnoreCase("Group Builder")) {
			return CaptureResult.ignored();
		}

		List<MenuEntry> entries = menuEntries(Minecraft.getInstance(), screen);
		MenuEntry clicked = entries.stream().filter(entry -> entry.slot() == clickedSlot.index).findFirst().orElse(null);
		if (clicked == null || !clicked.emeraldBlock()
			|| !clicked.name().equalsIgnoreCase("Confirm Group")) {
			return CaptureResult.ignored();
		}
		if (clicked.tooltip().stream().noneMatch(line -> line.equalsIgnoreCase("Click to confirm!"))) {
			clearCandidate();
			return CaptureResult.failed("the Confirm Group item did not contain its confirmation lore");
		}

		String dungeonType = selectedValue(entries, "Select Dungeon Type");
		String floorValue = selectedValue(entries, "Select Floor");
		if (dungeonType == null || floorValue == null) {
			clearCandidate();
			return CaptureResult.failed("the selected dungeon type or floor was missing from the menu lore");
		}
		String normalizedType = clean(dungeonType).toLowerCase(Locale.ROOT);
		if (!normalizedType.contains("catacombs")) {
			clearCandidate();
			return CaptureResult.failed("the selected dungeon type was not Catacombs");
		}

		Matcher floorMatcher = FLOOR.matcher(clean(floorValue));
		if (!floorMatcher.matches()) {
			clearCandidate();
			return CaptureResult.failed("the selected floor was not F1-F7 or M1-M7: " + clean(floorValue));
		}
		int floorNumber = romanFloor(floorMatcher.group(1));
		boolean master = normalizedType.contains("master mode") || normalizedType.contains("master catacombs");
		DungeonFloor captured = DungeonFloor.parse((master ? "M" : "F") + floorNumber);
		if (captured == null) {
			clearCandidate();
			return CaptureResult.failed("the selected floor could not be normalized");
		}

		candidate = captured;
		candidateCapturedAt = System.nanoTime();
		return CaptureResult.captured(captured);
	}

	/** Captures the advertised floor from the Party Finder listing the local player clicks. */
	public static CaptureResult captureJoinedListing(AbstractContainerScreen<?> screen, Slot clickedSlot,
		int button, ContainerInput input) {
		if (screen == null || clickedSlot == null || button != 0 || input != ContainerInput.PICKUP) {
			return CaptureResult.ignored();
		}
		String title = clean(screen.getTitle().getString()).toLowerCase(Locale.ROOT);
		if (!title.contains("party finder")) return CaptureResult.ignored();

		List<MenuEntry> entries = menuEntries(Minecraft.getInstance(), screen);
		MenuEntry clicked = entries.stream().filter(entry -> entry.slot() == clickedSlot.index).findFirst().orElse(null);
		if (clicked == null || !clicked.playerHead()) return CaptureResult.ignored();

		boolean catacombs = false;
		boolean master = false;
		int number = 0;
		for (String line : clicked.tooltip()) {
			String lower = line.toLowerCase(Locale.ROOT);
			if (lower.startsWith("dungeon:") && lower.contains("catacombs")) {
				catacombs = true;
				master = lower.contains("master mode") || lower.contains("master catacombs");
			}
			Matcher floorMatcher = LISTING_FLOOR.matcher(line);
			if (floorMatcher.matches()) number = romanFloor(floorMatcher.group(1));
		}
		if (!catacombs || number == 0) {
			clearJoinedListingCandidate();
			return CaptureResult.failed("the clicked listing did not contain a Catacombs floor");
		}
		DungeonFloor captured = DungeonFloor.parse((master ? "M" : "F") + number);
		if (captured == null) {
			clearJoinedListingCandidate();
			return CaptureResult.failed("the clicked listing floor could not be normalized");
		}
		joinedListingCandidate = captured;
		joinedListingCapturedAt = System.nanoTime();
		return CaptureResult.captured(captured);
	}

	/** Promotes only a fresh Group Builder selection after the exact queue-success chat message. */
	public static DungeonFloor confirmQueued() {
		long now = System.nanoTime();
		DungeonFloor promoted = candidate;
		if (promoted == null || candidateCapturedAt <= 0L || now < candidateCapturedAt
			|| now - candidateCapturedAt > CANDIDATE_MAX_AGE_NANOS) {
			promoted = null;
		}
		current = promoted;
		clearCandidate();
		clearJoinedListingCandidate();
		return current;
	}

	/** Promotes a freshly clicked listing when the server says the local player joined it. */
	public static DungeonFloor confirmJoinedListing() {
		long now = System.nanoTime();
		DungeonFloor promoted = joinedListingCandidate;
		if (promoted == null || joinedListingCapturedAt <= 0L || now < joinedListingCapturedAt
			|| now - joinedListingCapturedAt > CANDIDATE_MAX_AGE_NANOS) {
			promoted = null;
		}
		current = promoted;
		clearJoinedListingCandidate();
		clearCandidate();
		return current;
	}

	public static DungeonFloor currentFloor() {
		return current;
	}

	/** Prevents a floor from leaking into another server/world. */
	public static boolean tick(Minecraft client) {
		Object level = client == null ? null : client.level;
		if (level == trackedLevel) return false;
		trackedLevel = level;
		boolean changed = current != null || candidate != null || joinedListingCandidate != null;
		current = null;
		clearCandidate();
		clearJoinedListingCandidate();
		return changed;
	}

	public static boolean clear() {
		boolean changed = current != null || candidate != null || joinedListingCandidate != null;
		current = null;
		clearCandidate();
		clearJoinedListingCandidate();
		return changed;
	}

	private static List<MenuEntry> menuEntries(Minecraft client, AbstractContainerScreen<?> screen) {
		if (client == null || client.player == null) return List.of();
		Item.TooltipContext context = client.level == null
			? Item.TooltipContext.EMPTY : Item.TooltipContext.of(client.level);
		List<MenuEntry> entries = new ArrayList<>();
		for (Slot slot : screen.getMenu().slots) {
			if (slot.container instanceof Inventory || slot.getItem().isEmpty()) continue;
			List<String> tooltip = new ArrayList<>();
			try {
				slot.getItem().getTooltipLines(context, client.player, TooltipFlag.NORMAL)
					.forEach(line -> tooltip.add(clean(line.getString())));
			} catch (RuntimeException ignored) {
				continue;
			}
			entries.add(new MenuEntry(slot.index, clean(slot.getItem().getHoverName().getString()),
				List.copyOf(tooltip), slot.getItem().is(Items.EMERALD_BLOCK),
				slot.getItem().is(Items.PLAYER_HEAD)));
		}
		return entries;
	}

	private static String selectedValue(List<MenuEntry> entries, String itemName) {
		for (MenuEntry entry : entries) {
			if (!entry.name().equalsIgnoreCase(itemName)) continue;
			for (String line : entry.tooltip()) {
				Matcher matcher = CURRENTLY_SELECTED.matcher(line);
				if (matcher.matches()) return matcher.group(1).trim();
			}
		}
		return null;
	}

	private static void clearCandidate() {
		candidate = null;
		candidateCapturedAt = 0L;
	}

	private static void clearJoinedListingCandidate() {
		joinedListingCandidate = null;
		joinedListingCapturedAt = 0L;
	}

	private static int romanFloor(String value) {
		return switch (value.toUpperCase(Locale.ROOT)) {
			case "I", "1" -> 1;
			case "II", "2" -> 2;
			case "III", "3" -> 3;
			case "IV", "4" -> 4;
			case "V", "5" -> 5;
			case "VI", "6" -> 6;
			case "VII", "7" -> 7;
			default -> 0;
		};
	}

	private static String clean(String value) {
		String stripped = ChatFormatting.stripFormatting(value == null ? "" : value);
		return stripped == null ? "" : stripped.trim();
	}

	private record MenuEntry(int slot, String name, List<String> tooltip, boolean emeraldBlock,
		boolean playerHead) { }

	public record CaptureResult(boolean attempted, DungeonFloor floor, String error) {
		private static CaptureResult ignored() {
			return new CaptureResult(false, null, null);
		}

		private static CaptureResult failed(String error) {
			return new CaptureResult(true, null, error);
		}

		private static CaptureResult captured(DungeonFloor floor) {
			return new CaptureResult(true, floor, null);
		}
	}
}
