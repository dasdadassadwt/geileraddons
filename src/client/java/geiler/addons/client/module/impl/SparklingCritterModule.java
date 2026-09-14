package geiler.addons.client.module.impl;

import com.mojang.blaze3d.vertex.PoseStack;
import geiler.addons.client.entity.Nameplates;
import geiler.addons.client.hunting.CritterSpecies;
import geiler.addons.client.location.HypixelModApi;
import geiler.addons.client.location.Island;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.module.TextSetting;
import geiler.addons.client.render.EspRenderer;
import geiler.addons.client.render.GeilerAddonsRenderTypes;
import geiler.addons.client.render.WorldToScreen;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Boxes sparkling critters in the Critter Safari, and can call one out to the party.
 *
 * <p>A sparkling critter is found by reading name tags, not by any hidden state: Hypixel names the
 * label above one {@code Sparkling Rockmite} rather than {@code Rockmite}, and a name tag already
 * renders through terrain in vanilla. So this points out something that was on screen anyway.
 *
 * <p>The label is not the critter. Hypixel floats it on a separate entity, so every sighting is
 * resolved back to the mob underneath before anything is drawn round it - and the search for that
 * mob deliberately skips players, or a sparkling standing next to someone would box the player.
 */
public final class SparklingCritterModule extends Module {
	public static final SparklingCritterModule INSTANCE = new SparklingCritterModule();

	/** Past this there is nothing to look at anyway, whatever the view distance is set to. */
	private static final int MAX_RANGE = 128;
	/** The label sits directly above its mob, so the pairing radius can be tight. */
	private static final double LABEL_TO_MOB_RADIUS = 3.0;
	private static final float BOX_LINE_WIDTH = 2.0f;
	private static final float TRACER_WIDTH = 2.0f;
	/** Started a little ahead of the camera, or the line is a dot in the middle of the screen. */
	private static final double TRACER_START_DISTANCE = 1.0;
	/** Clear of the critter's own nameplate rather than fighting it for the same pixels. */
	private static final double LABEL_HEIGHT = 0.4;
	private static final int HUD_LABEL_HALF_HEIGHT = 4;
	/** What an unreadable "Range to Send" falls back to, matching the setting's own default. */
	private static final double DEFAULT_RANGE = 5;
	private static final int MAX_RANGE_TEXT_LENGTH = 6;

	private final ColorSetting color;
	private final NumberSetting textSize;
	private final BooleanSetting tracer;
	private final NumberSetting rescanTime;
	private final BooleanSetting debug;
	private final BooleanSetting announce;
	private final TextSetting rangeToSend;

	/** What the last sweep found; rebuilt wholesale rather than diffed. */
	private final List<Sighting> sightings = new ArrayList<>();
	/**
	 * Labels already called out, so a critter is announced once rather than every sweep.
	 *
	 * <p>Keyed on the label rather than on the mob, because the label is the thing that named it
	 * and it survives the pairing underneath turning out to be ambiguous.
	 */
	private final Set<UUID> announced = new HashSet<>();
	private int ticksSinceScan;
	private boolean wasActive;
	private ClientLevel lastLevel;

	private SparklingCritterModule() {
		this(new Settings());
	}

	private SparklingCritterModule(Settings s) {
		super("Sparkling Critter", "Finds sparkling critters in the Critter Safari.", Category.HUNTING,
			s.color, s.textSize, s.tracer, s.rescanTime, s.debug, s.announce, s.rangeToSend);
		this.color = s.color;
		this.textSize = s.textSize;
		this.tracer = s.tracer;
		this.rescanTime = s.rescanTime;
		this.debug = s.debug;
		this.announce = s.announce;
		this.rangeToSend = s.rangeToSend;
		group(
			new SettingGroup("Highlight", s.color, s.textSize, s.tracer, s.rescanTime),
			new SettingGroup("Announcements", s.debug, s.announce, s.rangeToSend)
		);
	}

	/** Just a carrier so the constructor can both build the settings and keep references to them. */
	private static final class Settings {
		final ColorSetting color = new ColorSetting("Color", 255, 200, 0, 255);
		final NumberSetting textSize = new NumberSetting("Text Size", 0.5f, 4.0f, 1.0f);
		final BooleanSetting tracer = new BooleanSetting("Tracer", false);
		/** Ticks between sweeps; the default is one a second. */
		final NumberSetting rescanTime = new NumberSetting("Rescan Time", 1, 200, 20, true);
		/** Prints what would be sent instead of sending it, and never sends while it is on. */
		final BooleanSetting debug = new BooleanSetting("Debug", false);
		final BooleanSetting announce = new BooleanSetting("Announce to Party Chat", false);
		/** How close a sparkling has to be before the party is told, in blocks. */
		final TextSetting rangeToSend = new TextSetting("Range to Send", "5", MAX_RANGE_TEXT_LENGTH);
	}

	/** One sparkling critter: the label that named it, and the mob it belongs to. */
	private record Sighting(String species, Entity label, Entity body) {
	}

	// ---- lifecycle ----------------------------------------------------------------------

	@Override
	public boolean isActive() {
		return isEnabled() && HypixelModApi.currentIsland() == Island.SAFARI;
	}

	@Override
	public String inactiveReason() {
		if (!isEnabled() || isActive()) return null;
		return HypixelModApi.reasonNotOn(Island.SAFARI);
	}

	@Override
	protected void onDisable() {
		reset();
	}

	private void reset() {
		sightings.clear();
		announced.clear();
		ticksSinceScan = 0;
		wasActive = false;
	}

	public void tick() {
		if (!isActive()) {
			// Leaving the Safari ends the run, and the next one's sparklings are its own.
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

		// Catching one removes the entity, so its box goes without waiting for the next sweep.
		sightings.removeIf(sighting -> sighting.body.isRemoved() || !sighting.body.isAlive());

		if (++ticksSinceScan < rescanTime.intValue()) return;
		ticksSinceScan = 0;
		scan(level, player);
		callOut(player);
	}

	// ---- scanning -----------------------------------------------------------------------

	private void scan(ClientLevel level, LocalPlayer player) {
		double range = Math.min(Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0, MAX_RANGE);
		AABB area = AABB.ofSize(player.position(), range * 2, range * 2, range * 2);
		List<Entity> nearby = level.getEntities(EntityTypeTest.forClass(Entity.class), area,
			entity -> entity != player);

		List<Entity> labels = new ArrayList<>();
		List<String> species = new ArrayList<>();
		List<Entity> bodies = new ArrayList<>();
		for (Entity entity : nearby) {
			if (entity.hasCustomName()) {
				String rare = CritterSpecies.sparkling(entity.getCustomName().getString());
				if (rare != null) {
					labels.add(entity);
					species.add(rare);
					continue;
				}
			}
			if (isMobLike(entity)) {
				bodies.add(entity);
			}
		}

		sightings.clear();
		for (int i = 0; i < labels.size(); i++) {
			Entity label = labels.get(i);
			Entity body = pair(bodies, label);
			if (body != null) {
				sightings.add(new Sighting(species.get(i), label, body));
			}
		}
	}

	/**
	 * The scaffolding Hypixel builds its props out of, and players.
	 *
	 * <p>Players are excluded as bodies but not as labels: a Hideyho arrives as a fake player and
	 * names itself, so it has to be able to be its own body while never being borrowed as one by
	 * the critter standing next to it.
	 */
	private static boolean isMobLike(Entity entity) {
		EntityType<?> type = entity.getType();
		return type != EntityType.ARMOR_STAND
			&& type != EntityType.INTERACTION
			&& type != EntityType.ITEM_DISPLAY
			&& type != EntityType.BLOCK_DISPLAY
			&& type != EntityType.TEXT_DISPLAY
			&& type != EntityType.PLAYER
			&& type != EntityType.ITEM
			&& Nameplates.hasBody(entity);
	}

	/** The nearest mob under a label, or the label itself when it is the mob. */
	private static Entity pair(List<Entity> bodies, Entity label) {
		Entity best = null;
		double bestDistance = LABEL_TO_MOB_RADIUS * LABEL_TO_MOB_RADIUS;
		for (Entity body : bodies) {
			double distance = body.position().distanceToSqr(label.position());
			if (distance >= bestDistance) continue;
			bestDistance = distance;
			best = body;
		}
		if (best != null) return best;
		// Nothing paired: only worth pointing at if the label has a hitbox of its own, since a box
		// around a marker entity encloses nothing and renders as nothing.
		return Nameplates.hasBody(label) ? label : null;
	}

	// ---- announcing ---------------------------------------------------------------------

	/**
	 * Tells the party about anything new that is close enough.
	 *
	 * <p>An out-of-range sparkling is deliberately left unannounced rather than marked as done, so
	 * walking towards one still calls it out when it comes within range.
	 */
	private void callOut(LocalPlayer player) {
		if (!announce.value() && !debug.value()) return;

		double range = rangeToSend();
		for (Sighting sighting : sightings) {
			if (player.position().distanceTo(sighting.body.position()) > range) continue;
			if (!announced.add(sighting.label.getUUID())) continue;

			BlockPos pos = sighting.body.blockPosition();
			String message = "Sparkling %s found at x:%d y:%d z:%d"
				.formatted(sighting.species, pos.getX(), pos.getY(), pos.getZ());
			if (debug.value()) {
				// Debug never sends, whether or not the party announcement is switched on. That
				// makes it a safe way to watch what this would say without messaging anybody.
				chat(message);
			} else {
				player.connection.sendCommand("pc " + message);
			}
		}
	}

	/** Falls back to the default rather than refusing to announce, if the box holds nonsense. */
	private double rangeToSend() {
		try {
			return Math.max(0, Double.parseDouble(rangeToSend.value().trim()));
		} catch (NumberFormatException e) {
			return DEFAULT_RANGE;
		}
	}

	private static void chat(String message) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui == null) return;
		mc.gui.getChat().addClientSystemMessage(Component.literal("[Sparkling] ")
			.withStyle(ChatFormatting.GOLD)
			.append(Component.literal(message).withStyle(ChatFormatting.WHITE)));
	}

	// ---- rendering ----------------------------------------------------------------------

	/** Drawn through walls: a sparkling behind a rock is exactly the one worth pointing at. */
	public void render(LevelRenderContext context) {
		if (!isActive() || sightings.isEmpty()) return;

		Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
		Vec3 camPos = camera.position();
		PoseStack poseStack = context.poseStack();
		MultiBufferSource.BufferSource bufferSource = context.bufferSource();
		int argb = color.argb();
		Vector3fc forward = camera.forwardVector();

		for (Sighting sighting : sightings) {
			AABB box = sighting.body.getBoundingBox();
			EspRenderer.renderBox(poseStack, bufferSource,
				box.minX - camPos.x, box.minY - camPos.y, box.minZ - camPos.z,
				box.getXsize(), box.getYsize(), box.getZsize(), argb, argb, BOX_LINE_WIDTH);

			if (tracer.value()) {
				Vec3 center = box.getCenter();
				EspRenderer.renderLine(poseStack, bufferSource,
					forward.x() * TRACER_START_DISTANCE, forward.y() * TRACER_START_DISTANCE,
					forward.z() * TRACER_START_DISTANCE,
					center.x - camPos.x, center.y - camPos.y, center.z - camPos.z, argb, TRACER_WIDTH);
			}
		}
		GeilerAddonsRenderTypes.endBatches(bufferSource);
	}

	/**
	 * The species name above each sighting, in the real font.
	 *
	 * <p>Projected onto the HUD rather than drawn in the world for the same reason the tiki solver
	 * labels are: in-world text does not render from any level stage reachable here.
	 */
	public void renderHud(GuiGraphicsExtractor graphics) {
		if (!isActive() || sightings.isEmpty()) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || mc.options.hideGui) return;

		Camera camera = mc.gameRenderer.getMainCamera();
		Font font = mc.font;
		float scale = textSize.value();
		int argb = color.argb();

		for (Sighting sighting : sightings) {
			if (sighting.body.isRemoved()) continue;
			String label = "Sparkling " + sighting.species;
			AABB box = sighting.body.getBoundingBox();
			Vec3 world = new Vec3(box.getCenter().x, box.maxY + LABEL_HEIGHT, box.getCenter().z);
			float[] screen = WorldToScreen.project(camera, world);
			if (screen == null) continue;

			// Measured at the scaled size so the label stays centred on the critter as it grows.
			float halfWidth = font.width(label) * scale / 2;
			float halfHeight = HUD_LABEL_HALF_HEIGHT * scale;
			graphics.pose().pushMatrix();
			graphics.pose().translate(screen[0] - halfWidth, screen[1] - halfHeight);
			graphics.pose().scale(scale, scale);
			graphics.text(font, label, 0, 0, argb);
			graphics.pose().popMatrix();
		}
	}
}
