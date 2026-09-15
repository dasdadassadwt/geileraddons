package geiler.addons.client.location;

import geiler.addons.client.config.ModConfig;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.hypixel.data.type.ServerType;
import net.hypixel.modapi.HypixelModAPI;
import net.hypixel.modapi.packet.impl.clientbound.event.ClientboundLocationPacket;

/**
 * Island state backed by the official shared Hypixel Mod API.
 *
 * <p>The official Fabric implementation owns payload registration and dispatch. GeilerAddons only
 * subscribes to location packets, which lets SkyHanni and other API consumers share one protocol
 * owner instead of competing for the same custom-payload identifiers.
 */
public final class HypixelModApi {
	private static Island island = Island.NONE;
	private static boolean located;
	private static boolean initialized;

	private HypixelModApi() {
	}

	/** The island named by the latest valid location event, or {@link Island#NONE} if unknown. */
	public static Island currentIsland() {
		return ModConfig.hypixelModApi() ? island : Island.NONE;
	}

	/** Whether the official API has supplied a location event for the current connection. */
	public static boolean hasLocation() {
		return ModConfig.hypixelModApi() && located;
	}

	/** Why the player is not on {@code wanted}, or null if they are. */
	public static String reasonNotOn(Island wanted) {
		if (island == wanted) return null;
		if (!ModConfig.hypixelModApi()) return "Island detection is off in the config";
		if (!located) return "Waiting for the server to name the island";
		return "Not " + wanted.displayName();
	}

	/** Subscribes once; the official API sends the shared register packet when appropriate. */
	public static void init() {
		if (!initialized) {
			HypixelModAPI api = HypixelModAPI.getInstance();
			api.createHandler(ClientboundLocationPacket.class, HypixelModApi::onLocation);
			api.subscribeToEventPacket(ClientboundLocationPacket.class);
			initialized = true;
		}
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
	}

	private static void reset() {
		island = Island.NONE;
		located = false;
	}

	private static void onLocation(ClientboundLocationPacket packet) {
		if (!ModConfig.hypixelModApi()) return;
		located = true;
		ServerType serverType = packet.getServerType().orElse(null);
		// A missing or unknown server type is not proof that the mode belongs to SkyBlock.
		if (serverType == null || !"SKYBLOCK".equalsIgnoreCase(serverType.name())) {
			island = Island.OTHER;
			return;
		}
		island = Island.fromMode(packet.getMode().orElse(null));
	}
}
