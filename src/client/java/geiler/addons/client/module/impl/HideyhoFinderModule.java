package geiler.addons.client.module.impl;

import com.mojang.blaze3d.vertex.PoseStack;
import geiler.addons.client.entity.Nameplates;
import geiler.addons.client.location.HypixelModApi;
import geiler.addons.client.location.Island;
import geiler.addons.client.location.SafariBiome;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.render.EspRenderer;
import geiler.addons.client.render.GeilerAddonsRenderTypes;
import geiler.addons.client.tree.ChatText;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.ClientAsset;
import net.minecraft.util.ARGB;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Boxes the Hideyho anywhere it turns up in the Critter Safari.
 *
 * <p>Looks for the Hideyho itself rather than for the places it is known to hide. The list of
 * hiding spots this replaced was wrong often enough to be worse than useless - it goes stale every
 * time Hypixel adds one, it can only ever check spots whose chunks happen to be loaded, and a
 * Hideyho standing three blocks off a listed spot was invisible to it. A sweep of what is actually
 * there has none of those failure modes and needs no chat line to start it.
 */
public final class HideyhoFinderModule extends Module {
	public static final HideyhoFinderModule INSTANCE = new HideyhoFinderModule();

	/**
	 * The Mojang texture hash of the Hideyho's skin, matched against the URL the skin was fetched
	 * from rather than against its {@code Identifier}.
	 *
	 * <p>The identifier is not this hash and never contains it: the client names a downloaded skin
	 * by the SHA-1 of its URL, so comparing identifier paths would silently never match.
	 */
	private static final String TEXTURE_HASH = "3504f1f2327a5110e643bb8667082512815fa434a29ed37f4ca83bb16d2db533";
	/** Catches a reskin, which would otherwise turn the finder off with no sign of why. */
	private static final String NAME_FALLBACK = "hideyho";

	/** Past this there is nothing to look at anyway, whatever the view distance is set to. */
	private static final int MAX_RANGE = 128;
	private static final float BOX_LINE_WIDTH = 2.0f;
	/** Debug boxes are faded so the real find never has to be picked out from among them. */
	private static final int DEBUG_ALPHA = 70;

	private final ColorSetting color;
	private final NumberSetting scanInterval;
	private final BooleanSetting debugMode;

	/** The Hideyho as of the last sweep; cleared by the sweep that no longer finds it. */
	private Entity found;
	/** Every fake player the last sweep looked at, kept only while Debug Mode is on. */
	private final List<Entity> candidates = new ArrayList<>();
	private int ticksSinceScan;
	private boolean wasActive;
	private ClientLevel lastLevel;

	private HideyhoFinderModule() {
		this(new Settings());
	}

	private HideyhoFinderModule(Settings s) {
		super("Hideyho Finder", "Finds the Hideyho in the Critter Safari.", Category.HUNTING,
			s.color, s.scanInterval, s.debugMode);
		this.color = s.color;
		this.scanInterval = s.scanInterval;
		this.debugMode = s.debugMode;
	}

	/** Just a carrier so the constructor can both build the settings and keep references to them. */
	private static final class Settings {
		final ColorSetting color = new ColorSetting("Color", 255, 0, 255, 200);
		/** Ticks between sweeps; the default is one a second. */
		final NumberSetting scanInterval = new NumberSetting("Scan Interval", 1, 200, 20, true);
		/** Boxes every fake player instead, which is how to tell a miss from a bad skin match. */
		final BooleanSetting debugMode = new BooleanSetting("Debug Mode", false);
	}

	// ---- lifecycle ----------------------------------------------------------------------

	/**
	 * Only the Haunted side of the Safari, since that is the only place a Hideyho spawns.
	 *
	 * <p>A biome the client cannot name counts as "carry on" rather than "stop". Failing shut there
	 * would turn a Safari whose biomes never resolved into a finder that silently never runs, which
	 * is the failure this module was rewritten to get away from - and sweeping three biomes too many
	 * costs a fraction of a millisecond, while missing the Hideyho costs the run.
	 */
	@Override
	public boolean isActive() {
		if (!isEnabled() || HypixelModApi.currentIsland() != Island.SAFARI) return false;
		SafariBiome biome = SafariBiome.current();
		return biome == null || biome == SafariBiome.HAUNTED;
	}

	@Override
	public String inactiveReason() {
		if (!isEnabled() || isActive()) return null;
		String island = HypixelModApi.reasonNotOn(Island.SAFARI);
		return island != null ? island : "Not in the " + SafariBiome.HAUNTED.displayName();
	}

	@Override
	protected void onDisable() {
		reset();
	}

	private void reset() {
		found = null;
		candidates.clear();
		ticksSinceScan = 0;
		wasActive = false;
	}

	public void tick() {
		if (!isActive()) {
			if (wasActive) {
				wasActive = false;
				reset();
			}
			return;
		}
		wasActive = true;

		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		LocalPlayer player = mc.player;
		if (level != lastLevel) {
			lastLevel = level;
			reset();
		}
		if (level == null || player == null) return;

		// Killing it removes the entity, so the box goes without waiting for the next sweep.
		if (found != null && (found.isRemoved() || !found.isAlive())) {
			found = null;
		}
		if (++ticksSinceScan < scanInterval.intValue()) return;
		ticksSinceScan = 0;
		scan(level, player);
	}

	// ---- scanning -----------------------------------------------------------------------

	private void scan(ClientLevel level, LocalPlayer player) {
		double range = Math.min(Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0, MAX_RANGE);
		Vec3 at = player.position();
		AABB area = AABB.ofSize(at, range * 2, range * 2, range * 2);

		candidates.clear();
		if (debugMode.value()) {
			candidates.addAll(level.getEntities(EntityTypeTest.forClass(AbstractClientPlayer.class),
				area, candidate -> candidate != player));
		}

		List<Entity> matches = level.getEntities(EntityTypeTest.forClass(Entity.class), area,
			entity -> entity != player && entity.isAlive() && isHideyho(entity));

		Entity best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Entity match : matches) {
			// The name fallback can land on the floating label rather than on the Hideyho.
			Entity body = Nameplates.resolveBody(level, match);
			if (body == null) continue;
			double distance = body.position().distanceToSqr(at);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = body;
			}
		}
		found = best;
	}

	private static boolean isHideyho(Entity entity) {
		if (entity instanceof AbstractClientPlayer skinned) {
			ClientAsset.Texture body = skinned.getSkin().body();
			if (body instanceof ClientAsset.DownloadedTexture downloaded
				&& downloaded.url().contains(TEXTURE_HASH)) {
				return true;
			}
		}
		return ChatText.plain(entity.getName().getString()).toLowerCase(Locale.ROOT).contains(NAME_FALLBACK);
	}

	// ---- rendering ----------------------------------------------------------------------

	/** Drawn through walls on purpose - a Hideyho that isn't hidden behind something isn't hidden. */
	public void render(LevelRenderContext context) {
		if (!isActive()) return;
		if (found == null && candidates.isEmpty()) return;

		Vec3 camPos = Minecraft.getInstance().gameRenderer.getMainCamera().position();
		PoseStack poseStack = context.poseStack();
		MultiBufferSource.BufferSource bufferSource = context.bufferSource();
		int argb = color.argb();

		for (Entity candidate : candidates) {
			if (candidate == found || candidate.isRemoved()) continue;
			box(poseStack, bufferSource, camPos, candidate, ARGB.color(DEBUG_ALPHA, argb));
		}
		if (found != null) {
			box(poseStack, bufferSource, camPos, found, argb);
		}
		GeilerAddonsRenderTypes.endBatches(bufferSource);
	}

	private static void box(PoseStack poseStack, MultiBufferSource bufferSource, Vec3 camPos, Entity entity, int argb) {
		AABB box = entity.getBoundingBox();
		EspRenderer.renderBox(poseStack, bufferSource,
			box.minX - camPos.x, box.minY - camPos.y, box.minZ - camPos.z,
			box.getXsize(), box.getYsize(), box.getZsize(), argb, argb, BOX_LINE_WIDTH);
	}
}
