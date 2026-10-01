package geiler.addons.client.farming;

import geiler.addons.GeilerAddons;
import geiler.addons.client.mixin.PlayerTabOverlayInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Reads the Pests widget out of the tab list. Client thread only.
 *
 * <p>The tab list is read through {@link PlayerTabOverlay} because only that path is ordered the way
 * the client draws it; {@link ClientPacketListener#getListedOnlinePlayers()} returns a hash-backed
 * collection whose order is unspecified. A test can supply its own line supplier instead.
 */
public final class PestWidgetReader {
	private static boolean warnedAboutOrdering;
	private final Supplier<List<String>> lines;

	public PestWidgetReader() {
		this(PestWidgetReader::currentTabList);
	}

	/** Test seam: the parser's ordering contract is the caller's, so the lines are injectable. */
	public PestWidgetReader(Supplier<List<String>> lines) {
		this.lines = lines;
	}

	/**
	 * Reads the widget once.
	 *
	 * @return the infested plot ids, or empty when the widget is absent, unreadable, or was not fully
	 *     rendered. Empty means "unknown": it must not be turned into a clear observation.
	 */
	public Optional<Set<Integer>> read() {
		return TabWidgetText.parse(lines.get());
	}

	private static List<String> currentTabList() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || minecraft.player.connection == null || minecraft.gui == null) {
			return List.of();
		}
		PlayerTabOverlay overlay = minecraft.gui.getTabList();
		if (overlay == null) return List.of();
		List<PlayerInfo> listed = orderedPlayers(overlay);
		if (listed.isEmpty()) return List.of();
		List<String> lines = new ArrayList<>(listed.size());
		for (PlayerInfo info : listed) {
			lines.add(overlay.getNameForDisplay(info).getString());
		}
		return lines;
	}

	/**
	 * Vanilla's own ordered tab list, falling back to the unordered one if the invoker is unavailable.
	 *
	 * <p>The fallback is the honest degradation: the widget parser fails closed on an out-of-order tab
	 * list, so a missing invoker costs the feature rather than corrupting it, and the warning says why
	 * instead of leaving a silently useless module.
	 */
	private static List<PlayerInfo> orderedPlayers(PlayerTabOverlay overlay) {
		try {
			List<PlayerInfo> ordered = ((PlayerTabOverlayInvoker) overlay).geileraddons$getPlayerInfos();
			if (ordered != null) return ordered;
		} catch (RuntimeException | LinkageError failure) {
			if (!warnedAboutOrdering) {
				warnedAboutOrdering = true;
				GeilerAddons.LOGGER.warn(
					"[Infested plot] Tab list ordering is unavailable; the Pests widget cannot be read",
					failure);
			}
		}
		Minecraft minecraft = Minecraft.getInstance();
		List<PlayerInfo> unordered = new ArrayList<>(minecraft.player.connection.getListedOnlinePlayers());
		return List.copyOf(unordered);
	}
}
