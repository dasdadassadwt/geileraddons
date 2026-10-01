package geiler.addons.client.mixin;

import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/**
 * Exposes vanilla's own ordered tab list.
 *
 * <p>{@code ClientPacketListener.getListedOnlinePlayers()} is a {@code HashMap}-backed
 * {@code Collection} with no defined order, and the ordering Hypixel's info widgets depend on lives
 * in this private method, which sorts by the tab-list objective's score and caps the list at the 80
 * rows vanilla is willing to draw. Reimplementing that sort is not possible from public API: the
 * comparator and the objective it reads are both private, and the sidebar can share an objective
 * name with the tab list, so a wrong guess would silently read the widgets out of order. Invoking
 * the real method means the widget reader sees exactly the lines the client renders.
 */
@Mixin(PlayerTabOverlay.class)
public interface PlayerTabOverlayInvoker {
	@Invoker("getPlayerInfos")
	List<PlayerInfo> geileraddons$getPlayerInfos();
}
