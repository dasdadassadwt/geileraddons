package geiler.addons.client;

import geiler.addons.client.config.ModConfig;
import geiler.addons.client.module.ModuleManager;
import geiler.addons.client.module.impl.I4HelperModule;
import geiler.addons.client.module.impl.TikiDebugModule;
import geiler.addons.client.module.impl.TikiHelperModule;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

public class GeilerAddonsClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ModuleManager.register(I4HelperModule.INSTANCE);
		ModuleManager.register(TikiHelperModule.INSTANCE);
		ModuleManager.register(TikiDebugModule.INSTANCE);
		// Must come after registration: this is what restores saved settings onto the modules.
		ModConfig.load();

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			I4HelperModule.INSTANCE.tick();
			TikiHelperModule.INSTANCE.tick();
			TikiDebugModule.INSTANCE.tick();
		});
		LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> {
			I4HelperModule.INSTANCE.render(context);
			TikiHelperModule.INSTANCE.render(context);
			TikiDebugModule.INSTANCE.render(context);
		});
	}
}
