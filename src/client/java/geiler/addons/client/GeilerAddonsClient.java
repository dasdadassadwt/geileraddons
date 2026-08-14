package geiler.addons.client;

import geiler.addons.GeilerAddons;
import geiler.addons.client.config.ModConfig;
import geiler.addons.client.hud.HudManager;
import geiler.addons.client.location.HypixelModApi;
import geiler.addons.client.location.SafariBiome;
import geiler.addons.client.location.TorrhusPresence;
import geiler.addons.client.module.ModuleManager;
import geiler.addons.client.module.impl.HideyhoFinderModule;
import geiler.addons.client.module.impl.I4HelperModule;
import geiler.addons.client.module.impl.MobHighlightModule;
import geiler.addons.client.module.impl.SafariFloorDropsModule;
import geiler.addons.client.module.impl.SparklingCritterModule;
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
		ModuleManager.register(SafariFloorDropsModule.INSTANCE);
		ModuleManager.register(HideyhoFinderModule.INSTANCE);
		ModuleManager.register(SparklingCritterModule.INSTANCE);
		ModuleManager.register(TreeTrackerModule.INSTANCE);
		ModuleManager.register(TreeNotifierModule.INSTANCE);
		ModuleManager.register(VisualModule.INSTANCE);
		ModuleManager.register(MobHighlightModule.INSTANCE);
		HudManager.register(TreeTrackerModule.INSTANCE, 0.01f, 0.10f);
		HudManager.register(TreeNotifierModule.INSTANCE, 0.5f, 0.28f);
		// Must come after registration: this is what restores saved settings, HUD positions and
		// gift counts onto the things that were just registered.
		ModConfig.load();
		// After the config, which is what decides whether the check is allowed to run at all.
		UpdateChecker.start();
		// Channels have to be claimed before any server is joined, so this cannot wait for a tick.
		HypixelModApi.init();

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			// First: the modules gate themselves on the presence these resolve.
			HypixelModApi.tick();
			// After the island, which is what decides whether a biome lookup is worth doing.
			SafariBiome.tick();
			TorrhusPresence.tick();
			UpdateChecker.tick();
			I4HelperModule.INSTANCE.tick();
			TikiHelperModule.INSTANCE.tick();
			SafariFloorDropsModule.INSTANCE.tick();
			HideyhoFinderModule.INSTANCE.tick();
			SparklingCritterModule.INSTANCE.tick();
			MobHighlightModule.INSTANCE.tick();
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
			SafariFloorDropsModule.INSTANCE.render(context);
			HideyhoFinderModule.INSTANCE.render(context);
			SparklingCritterModule.INSTANCE.render(context);
			MobHighlightModule.INSTANCE.render(context);
		});
		// Solver labels are drawn here rather than in the world: in-world text does not render
		// from any level stage reachable on 26.1, so they are projected onto the HUD instead.
		HudElementRegistry.addLast(GeilerAddons.id("tiki_labels"),
			(graphics, tickCounter) -> TikiHelperModule.INSTANCE.renderHud(graphics));
		HudElementRegistry.addLast(GeilerAddons.id("mob_highlight_labels"),
			(graphics, tickCounter) -> MobHighlightModule.INSTANCE.renderHud(graphics));
		HudElementRegistry.addLast(GeilerAddons.id("sparkling_labels"),
			(graphics, tickCounter) -> SparklingCritterModule.INSTANCE.renderHud(graphics));
		HudElementRegistry.addLast(GeilerAddons.id("hud_elements"),
			(graphics, tickCounter) -> HudManager.render(graphics));
	}
}
