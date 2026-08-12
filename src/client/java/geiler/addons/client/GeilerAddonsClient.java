package geiler.addons.client;

import geiler.addons.GeilerAddons;
import geiler.addons.client.config.ModConfig;
import geiler.addons.client.hud.HudManager;
import geiler.addons.client.location.TorrhusPresence;
import geiler.addons.client.module.ModuleManager;
import geiler.addons.client.module.impl.I4HelperModule;
import geiler.addons.client.module.impl.TikiHelperModule;
import geiler.addons.client.module.impl.TreeNotifierModule;
import geiler.addons.client.module.impl.TreeTrackerModule;
import geiler.addons.client.module.impl.VisualModule;
import geiler.addons.client.update.UpdateChecker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

public class GeilerAddonsClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ModuleManager.register(I4HelperModule.INSTANCE);
		ModuleManager.register(TikiHelperModule.INSTANCE);
		ModuleManager.register(TreeTrackerModule.INSTANCE);
		ModuleManager.register(TreeNotifierModule.INSTANCE);
		ModuleManager.register(VisualModule.INSTANCE);
		HudManager.register(TreeTrackerModule.INSTANCE, 0.01f, 0.10f);
		HudManager.register(TreeNotifierModule.INSTANCE, 0.5f, 0.28f);
		// Must come after registration: this is what restores saved settings, HUD positions and
		// gift counts onto the things that were just registered.
		ModConfig.load();
		// After the config, which is what decides whether the check is allowed to run at all.
		UpdateChecker.start();

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			// First: the modules gate themselves on the presence this resolves.
			TorrhusPresence.tick();
			UpdateChecker.tick();
			I4HelperModule.INSTANCE.tick();
			TikiHelperModule.INSTANCE.tick();
			// Releases any chat line held back while the mod worked out whether it opened a gift
			// block, so nothing can be withheld for longer than a tick.
			TreeNotifierModule.INSTANCE.tick();
			ModConfig.flushIfDirty();
		});
		// Quitting cleanly must not drop the gifts counted since the last debounced write.
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ModConfig.flushNow());
		LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> {
			I4HelperModule.INSTANCE.render(context);
			TikiHelperModule.INSTANCE.render(context);
		});
		// Solver labels are drawn here rather than in the world: in-world text does not render
		// from any level stage reachable on 26.1, so they are projected onto the HUD instead.
		HudElementRegistry.addLast(GeilerAddons.id("tiki_labels"),
			(graphics, tickCounter) -> TikiHelperModule.INSTANCE.renderHud(graphics));
		HudElementRegistry.addLast(GeilerAddons.id("hud_elements"),
			(graphics, tickCounter) -> HudManager.render(graphics));
	}
}
