package geiler.addons.client.module;

import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.Font;

import java.util.List;

/** Optional, read-only content shown above a module's settings rows in the Click GUI. */
public interface ModulePreview {
	/**
	 * Returns display-only lines for the current settings. The caller owns clipping and wrapping;
	 * implementations must not enqueue chat, execute commands, or query live services.
	 */
	List<Component> previewLines(Font font, int width);
}
