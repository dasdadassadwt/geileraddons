package geiler.addons.client.update;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import geiler.addons.GeilerAddons;
import geiler.addons.client.config.ModConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Notices when a newer release has been published on GitHub.
 *
 * <p>Deliberately passive: it reads the releases API, compares tags, and says so once. It never
 * downloads or installs anything, and the whole thing is skipped when the user turns it off in
 * the config. One request per launch, on a background thread, failing silently - a rate limit or
 * an offline machine must never be something the player notices.
 */
public final class UpdateChecker {
	private static final String RELEASES_URL =
		"https://api.github.com/repos/dasdadassadwt/geileraddons/releases/latest";
	private static final String RELEASES_PAGE =
		"https://github.com/dasdadassadwt/geileraddons/releases/latest";
	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	/** Non-null only when a strictly newer release exists. Written off-thread, so volatile. */
	private static volatile String newerVersion;
	private static String currentVersion = "";
	private static boolean announced;

	private UpdateChecker() {
	}

	/** Fires the one background request. Safe to call before the player has joined anything. */
	public static void start() {
		currentVersion = FabricLoader.getInstance().getModContainer(GeilerAddons.MOD_ID)
			.map(container -> container.getMetadata().getVersion().getFriendlyString())
			.orElse("");
		if (!ModConfig.checkForUpdates()) return;

		Thread thread = new Thread(UpdateChecker::check, "GeilerAddons update check");
		// Daemon: a hung request must never hold the game open on exit.
		thread.setDaemon(true);
		thread.start();
	}

	/** Called each client tick; announces in chat once, as soon as there is a player to tell. */
	public static void tick() {
		if (announced) return;
		String version = newerVersion;
		if (version == null) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.gui == null) return;

		announced = true;
		Component message = Component.literal("[GeilerAddons] ").withStyle(ChatFormatting.LIGHT_PURPLE)
			.append(Component.literal("v" + version + " is available").withStyle(ChatFormatting.WHITE))
			.append(Component.literal(" (you have v" + currentVersion + ") ").withStyle(ChatFormatting.GRAY))
			.append(Component.literal("[open releases]").withStyle(style -> style
				.withColor(ChatFormatting.AQUA)
				.withUnderlined(true)
				.withClickEvent(new ClickEvent.OpenUrl(URI.create(RELEASES_PAGE)))));
		// Client system message: this came from the mod, not the server, and the chat log should
		// say so rather than dressing it up as something Hypixel sent.
		mc.gui.getChat().addClientSystemMessage(message);
	}

	/** Short notice for the Click GUI, or null when there is nothing to report. */
	public static String bannerText() {
		String version = newerVersion;
		return version == null ? null : "Update available: v" + version;
	}

	private static void check() {
		try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
			HttpRequest request = HttpRequest.newBuilder(URI.create(RELEASES_URL))
				.timeout(TIMEOUT)
				// GitHub rejects requests without a User-Agent outright.
				.header("User-Agent", GeilerAddons.MOD_ID)
				.header("Accept", "application/vnd.github+json")
				.GET()
				.build();

			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				GeilerAddons.LOGGER.debug("Update check got HTTP {}", response.statusCode());
				return;
			}

			JsonObject json = new Gson().fromJson(response.body(), JsonObject.class);
			if (json == null || !json.has("tag_name")) return;
			String tag = stripPrefix(json.get("tag_name").getAsString());
			if (isNewer(tag, currentVersion)) {
				newerVersion = tag;
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Exception e) {
			// Offline, rate-limited, DNS-blocked, malformed body - none of it is worth a warning.
			GeilerAddons.LOGGER.debug("Update check failed", e);
		}
	}

	private static String stripPrefix(String tag) {
		String trimmed = tag.trim();
		return trimmed.startsWith("v") || trimmed.startsWith("V") ? trimmed.substring(1) : trimmed;
	}

	/** Numeric compare per dot-separated part, so 1.10.0 correctly beats 1.9.0. */
	static boolean isNewer(String candidate, String current) {
		int[] a = parse(candidate);
		int[] b = parse(current);
		for (int i = 0; i < Math.max(a.length, b.length); i++) {
			int left = i < a.length ? a[i] : 0;
			int right = i < b.length ? b[i] : 0;
			if (left != right) return left > right;
		}
		return false;
	}

	private static int[] parse(String version) {
		// Everything from a pre-release or build suffix on is dropped: "1.4.0-rc1" compares as 1.4.0.
		int cut = version.length();
		for (int i = 0; i < version.length(); i++) {
			char c = version.charAt(i);
			if (c == '-' || c == '+') {
				cut = i;
				break;
			}
		}
		String[] parts = version.substring(0, cut).split("\\.");
		int[] numbers = new int[parts.length];
		for (int i = 0; i < parts.length; i++) {
			try {
				numbers[i] = Integer.parseInt(parts[i].trim());
			} catch (NumberFormatException e) {
				numbers[i] = 0;
			}
		}
		return numbers;
	}
}
