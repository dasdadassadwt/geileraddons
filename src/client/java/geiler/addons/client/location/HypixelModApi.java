package geiler.addons.client.location;

import geiler.addons.GeilerAddons;
import geiler.addons.client.config.ModConfig;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Which island the player is on, from the Hypixel Mod API's location event.
 *
 * <p>Hypixel sends {@code hypixel:hello} on login; a client that wants events answers with
 * {@code hypixel:register} naming the ones it wants, and is then sent {@code hyevent:location}
 * every time it changes server. Nothing is polled - the island is whatever the last location
 * event said, so a warp is reflected the moment it happens.
 *
 * <p>Unlike {@link TorrhusPresence} this needs no terrain to have been seen before, which is what
 * makes it usable on a fresh install. It is a strictly better signal where it answers at all, and
 * silent everywhere else - on a non-Hypixel server nothing is ever sent, because the register
 * packet only goes out in reply to a hello that proves the Mod API is there.
 */
public final class HypixelModApi {
	private static final Identifier HELLO = Identifier.fromNamespaceAndPath("hypixel", "hello");
	/** Not {@code hypixel:location_update}: the event lives in its own namespace. */
	private static final Identifier LOCATION = Identifier.fromNamespaceAndPath("hyevent", "location");
	private static final Identifier REGISTER = Identifier.fromNamespaceAndPath("hypixel", "register");

	/** Protocol version of the register packet itself, and of the location event we ask for. */
	private static final int REGISTER_VERSION = 1;
	private static final int LOCATION_VERSION = 1;

	/** The only server type whose modes this mod knows; anything else is decisively elsewhere. */
	private static final String SKYBLOCK = "SKYBLOCK";

	private static final CustomPacketPayload.Type<HelloPayload> HELLO_TYPE = new CustomPacketPayload.Type<>(HELLO);
	private static final CustomPacketPayload.Type<LocationPayload> LOCATION_TYPE = new CustomPacketPayload.Type<>(LOCATION);
	private static final CustomPacketPayload.Type<RegisterPayload> REGISTER_TYPE = new CustomPacketPayload.Type<>(REGISTER);

	private static Island island = Island.NONE;
	/** Set once the server has said hello, which is what makes it safe to send anything back. */
	private static boolean greeted;
	private static boolean located;
	private static int ticksSinceRegister;

	private HypixelModApi() {
	}

	/** The island the last location event named, or {@link Island#NONE} if there hasn't been one. */
	public static Island currentIsland() {
		return island;
	}

	/**
	 * Whether the API has actually answered, as opposed to simply not naming a known island.
	 *
	 * <p>The difference decides whether a "not here" reading may be trusted: without it, being on
	 * an unrecognised island and not being on Hypixel at all look identical.
	 */
	public static boolean hasLocation() {
		return located;
	}

	/**
	 * Why the player isn't on {@code wanted}, or null if they are - the Click GUI's idle text.
	 *
	 * <p>Distinguishes "somewhere else" from "no idea yet", because a module that is quiet because
	 * the handshake never happened looks broken if it claims to know where you are standing.
	 */
	public static String reasonNotOn(Island wanted) {
		if (island == wanted) return null;
		if (!ModConfig.hypixelModApi()) return "Island detection is off in the config";
		if (!located) return "Waiting for the server to name the island";
		return "Not " + wanted.displayName();
	}

	/** Registers the payload types and handlers. Must run before joining a server. */
	public static void init() {
		try {
			PayloadTypeRegistry.clientboundPlay().register(HELLO_TYPE, HelloPayload.CODEC);
			PayloadTypeRegistry.clientboundPlay().register(LOCATION_TYPE, LocationPayload.CODEC);
			PayloadTypeRegistry.serverboundPlay().register(REGISTER_TYPE, RegisterPayload.CODEC);
			ClientPlayNetworking.registerGlobalReceiver(HELLO_TYPE, (payload, context) -> onHello());
			ClientPlayNetworking.registerGlobalReceiver(LOCATION_TYPE, (payload, context) -> onLocation(payload));
		} catch (RuntimeException e) {
			// Another mod speaking the same protocol will have claimed these channels first. That
			// costs island detection, not the game - everything gated on it falls back or idles.
			GeilerAddons.LOGGER.warn("Hypixel Mod API channels unavailable, island detection is off", e);
		}
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
	}

	/**
	 * Call once per client tick. Re-asks for the location event if the greeting arrived but no
	 * location ever did, which is the one failure this side can do anything about.
	 */
	public static void tick() {
		if (!ModConfig.hypixelModApi() || !greeted || located) return;
		if (++ticksSinceRegister < ModConfig.islandCheckIntervalSeconds() * 20) return;
		ticksSinceRegister = 0;
		sendRegister();
	}

	private static void reset() {
		island = Island.NONE;
		greeted = false;
		located = false;
		ticksSinceRegister = 0;
	}

	private static void onHello() {
		greeted = true;
		ticksSinceRegister = 0;
		if (!ModConfig.hypixelModApi()) return;
		sendRegister();
	}

	private static void onLocation(LocationPayload payload) {
		if (!ModConfig.hypixelModApi()) return;
		located = true;
		// A server type that isn't SkyBlock has its own unrelated mode names, so reading an island
		// out of one would be a coincidence rather than a fact.
		boolean skyblock = payload.serverType == null || SKYBLOCK.equalsIgnoreCase(payload.serverType);
		island = skyblock ? Island.fromMode(payload.mode) : Island.OTHER;
	}

	private static void sendRegister() {
		if (Minecraft.getInstance().getConnection() == null) return;
		ClientPlayNetworking.send(new RegisterPayload());
	}

	// ---- protocol -----------------------------------------------------------------------

	/**
	 * Reads whatever is left so the buffer ends fully consumed.
	 *
	 * <p>Not tidiness: Hypixel has shipped trailing bytes on these packets before, and a payload
	 * that decodes without draining is a disconnect rather than a warning.
	 */
	private static void drain(FriendlyByteBuf buf) {
		buf.readerIndex(buf.writerIndex());
	}

	private static String optionalString(FriendlyByteBuf buf) {
		return buf.readBoolean() ? buf.readUtf() : null;
	}

	/** Only its arrival matters - it is the cue that this server speaks the Mod API. */
	private record HelloPayload() implements CustomPacketPayload {
		static final StreamCodec<FriendlyByteBuf, HelloPayload> CODEC = StreamCodec.of(
			(buf, value) -> {
				throw new UnsupportedOperationException();
			},
			buf -> {
				drain(buf);
				return new HelloPayload();
			});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return HELLO_TYPE;
		}
	}

	/**
	 * The fields ahead of {@code mode} are read only to reach it - the format is positional, so
	 * there is no skipping to the one field this mod cares about.
	 */
	private record LocationPayload(String serverType, String mode) implements CustomPacketPayload {
		static final StreamCodec<FriendlyByteBuf, LocationPayload> CODEC = StreamCodec.of(
			(buf, value) -> {
				throw new UnsupportedOperationException();
			},
			LocationPayload::decode);

		private static LocationPayload decode(FriendlyByteBuf buf) {
			// Every S2C packet leads with a success flag, and an unsuccessful one carries an error
			// in place of the fields below rather than the fields themselves.
			if (!buf.readBoolean() || buf.readVarInt() != LOCATION_VERSION) {
				drain(buf);
				return new LocationPayload(null, null);
			}
			buf.readUtf();
			String serverType = optionalString(buf);
			optionalString(buf);
			String mode = optionalString(buf);
			drain(buf);
			return new LocationPayload(serverType, mode);
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return LOCATION_TYPE;
		}
	}

	/** Asks for the location event; the map is channel to wanted version. */
	private record RegisterPayload() implements CustomPacketPayload {
		static final StreamCodec<FriendlyByteBuf, RegisterPayload> CODEC = StreamCodec.of(
			(buf, value) -> {
				buf.writeVarInt(REGISTER_VERSION);
				buf.writeVarInt(1);
				Identifier.STREAM_CODEC.encode(buf, LOCATION);
				buf.writeVarInt(LOCATION_VERSION);
			},
			buf -> {
				throw new UnsupportedOperationException();
			});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return REGISTER_TYPE;
		}
	}
}
