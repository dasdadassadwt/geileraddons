package geiler.addons.client.location;

import geiler.addons.client.config.ModConfig;
import net.minecraft.client.Minecraft;
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
	private static volatile Island island = Island.NONE;
	private static volatile boolean located;
	private static volatile long connectionEpoch;
	private static boolean initialized;

	private HypixelModApi() {
	}

	/** The island named by the latest valid location event, or {@link Island#NONE} if unknown. */
	public static Island currentIsland() {
		if (!ModConfig.hypixelModApi()) {
			reset();
			return Island.NONE;
		}
		return island;
	}

	/** Whether the official API has supplied a location event for the current connection. */
	public static boolean hasLocation() {
		if (!ModConfig.hypixelModApi()) {
			reset();
			return false;
		}
		return located;
	}

	/** Why the player is not on {@code wanted}, or null if they are. */
	public static String reasonNotOn(Island wanted) {
		if (!ModConfig.hypixelModApi()) {
			reset();
			return "Island detection is off in the config";
		}
		if (island == wanted) return null;
		if (!located) return "Waiting for the server to name the island";
		return "Not " + wanted.displayName();
	}

	/** Subscribes once; the official API sends the shared register packet when appropriate. */
	public static void init() {
		if (!initialized) {
			HypixelModAPI api = HypixelModAPI.getInstance();
			api.createHandler(ClientboundLocationPacket.class, HypixelModApi::onLocation);
			api.subscribeToEventPacket(ClientboundLocationPacket.class);
			ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
			ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
			initialized = true;
		}
	}

	private static void reset() {
		connectionEpoch++;
		island = Island.NONE;
		located = false;
	}

	private static void onLocation(ClientboundLocationPacket packet) {
		if (!ModConfig.hypixelModApi()) return;
		long eventEpoch = connectionEpoch;
		ServerType serverType = packet.getServerType().orElse(null);
		String serverTypeName = serverType == null ? null : serverType.name();
		String mode = packet.getMode().orElse(null);
		// A missing or unknown server type is not proof that the mode belongs to SkyBlock.
		Minecraft.getInstance().execute(() -> {
			if (eventEpoch != connectionEpoch || !ModConfig.hypixelModApi()) return;
			located = true;
			if (!"SKYBLOCK".equalsIgnoreCase(serverTypeName)) {
				island = Island.OTHER;
				return;
			}
			island = Island.fromMode(mode);
		});
	}
}
