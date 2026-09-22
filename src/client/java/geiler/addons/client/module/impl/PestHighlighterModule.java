package geiler.addons.client.module.impl;

import com.mojang.blaze3d.vertex.PoseStack;
import geiler.addons.client.entity.ClientEntitySnapshot;
import geiler.addons.client.farming.PestAnimation;
import geiler.addons.client.farming.PestDetector;
import geiler.addons.client.farming.PestDetectionCache;
import geiler.addons.client.farming.PestKind;
import geiler.addons.client.location.HypixelModApi;
import geiler.addons.client.location.Island;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.render.EspRenderer;
import geiler.addons.client.render.GeilerAddonsRenderTypes;
import geiler.addons.client.render.WorldToScreen;
import geiler.addons.client.render.ProjectedLabelRenderer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Highlights all known Hypixel Garden pests, which are represented by textured head ArmorStands. */
public final class PestHighlighterModule extends Module {
	public static final PestHighlighterModule INSTANCE = new PestHighlighterModule();

	/** Beyond this distance a pest is not useful to the player, whatever the view distance is. */
	private static final int MAX_RANGE = 64;
	private static final double TRACER_START_DISTANCE = 1.0;
	private static final double RING_ENDPOINT_PADDING = 0.05;
	private static final double LABEL_HEIGHT = 0.4;
	private static final int HUD_LABEL_HALF_HEIGHT = 4;

	private final BooleanSetting box;
	private final BooleanSetting circle;
	private final BooleanSetting tracer;
	private final BooleanSetting showName;
	private final BooleanSetting depthCheck;
	private final NumberSetting scanInterval;
	private final ColorSetting outlineColor;
	private final NumberSetting outlineWidth;
	private final ColorSetting fillColor;
	private final ColorSetting circleColor;
	private final NumberSetting circleWidth;
	private final NumberSetting circleRadius;
	private final NumberSetting circleSpeed;
	private final NumberSetting ringCount;
	private final ColorSetting tracerColor;
	private final NumberSetting tracerWidth;
	private final ColorSetting nameColor;
	private final NumberSetting labelSize;

	/** Rebuilt wholesale by the client-thread scan and consumed by world/HUD rendering. */
	private final List<Sighting> sightings = new ArrayList<>();
	/** Negative detections are cached too, so ordinary ArmorStands are cheap on every tick. */
	private final PestDetectionCache detectionCache = new PestDetectionCache();
	private int ticksSinceScan;
	private boolean wasActive;
	private ClientLevel lastLevel;

	private PestHighlighterModule() {
		this(new Settings());
	}

	private PestHighlighterModule(Settings s) {
		super("Pest Highlighter", "Highlights all known Garden pests by their head texture.", Category.FARMING,
			s.box, s.circle, s.tracer, s.showName, s.depthCheck, s.scanInterval,
			s.outlineColor, s.outlineWidth, s.fillColor,
			s.circleColor, s.circleWidth, s.circleRadius, s.circleSpeed, s.ringCount,
			s.tracerColor, s.tracerWidth, s.nameColor, s.labelSize);
		this.box = s.box;
		this.circle = s.circle;
		this.tracer = s.tracer;
		this.showName = s.showName;
		this.depthCheck = s.depthCheck;
		this.scanInterval = s.scanInterval;
		this.outlineColor = s.outlineColor;
		this.outlineWidth = s.outlineWidth;
		this.fillColor = s.fillColor;
		this.circleColor = s.circleColor;
		this.circleWidth = s.circleWidth;
		this.circleRadius = s.circleRadius;
		this.circleSpeed = s.circleSpeed;
		this.ringCount = s.ringCount;
		this.tracerColor = s.tracerColor;
		this.tracerWidth = s.tracerWidth;
		this.nameColor = s.nameColor;
		this.labelSize = s.labelSize;

		group(
			new SettingGroup("Display", s.box, s.circle, s.tracer, s.showName, s.depthCheck, s.scanInterval),
			new SettingGroup("Box", s.outlineColor, s.outlineWidth, s.fillColor),
			new SettingGroup("Circle", s.circleColor, s.circleWidth, s.circleRadius, s.circleSpeed, s.ringCount),
			new SettingGroup("Tracer", s.tracerColor, s.tracerWidth),
			new SettingGroup("Name", s.nameColor, s.labelSize)
		);
	}

	/** Carrier used to construct the settings once and pass the same instances to Module and groups. */
	private static final class Settings {
		final BooleanSetting box = new BooleanSetting("Box", true);
		// Keep the persisted key "Circle" for existing configs while naming the feature the way it
		// behaves: this is the animated ring mode users connect the tracer to.
		final BooleanSetting circle = new BooleanSetting("Circle", "Ring Mode", false);
		final BooleanSetting tracer = new BooleanSetting("Tracer", false);
		final BooleanSetting showName = new BooleanSetting("Show Name", true);
		final BooleanSetting depthCheck = new BooleanSetting("Depth Check", false);
		final NumberSetting scanInterval = new NumberSetting("Scan Interval", 1, 200, 1, true);

		final ColorSetting outlineColor = new ColorSetting("Outline Color", 182, 47, 0, 255);
		final NumberSetting outlineWidth = new NumberSetting("Outline Width", 0.5f, 5.0f, 2.0f);
		final ColorSetting fillColor = new ColorSetting("Fill Color", 182, 47, 0, 60);

		final ColorSetting circleColor = new ColorSetting("Circle Color", 182, 47, 0, 255);
		final NumberSetting circleWidth = new NumberSetting("Circle Width", 0.5f, 5.0f, 2.0f);
		final NumberSetting circleRadius = new NumberSetting("Circle Radius", 0.1f, 3.0f, 0.6f);
		final NumberSetting circleSpeed = new NumberSetting("Circle Speed", 0.1f, 4.0f, 0.8f);
		final NumberSetting ringCount = new NumberSetting("Ring Count", 1, 8, 3, true);

		final ColorSetting tracerColor = new ColorSetting("Tracer Color", 182, 47, 0, 255);
		final NumberSetting tracerWidth = new NumberSetting("Tracer Width", 0.5f, 5.0f, 2.0f);

		final ColorSetting nameColor = new ColorSetting("Name Color", 182, 47, 0, 255);
		final NumberSetting labelSize = new NumberSetting("Label Size", 0.5f, 4.0f, 1.0f);
	}

	private record Sighting(PestKind kind, ArmorStand entity) {
	}

	// ---- lifecycle ----------------------------------------------------------------------

	@Override
	public boolean isActive() {
		return isEnabled() && HypixelModApi.currentIsland() == Island.GARDEN;
	}

	@Override
	public String inactiveReason() {
		if (!isEnabled() || isActive()) return null;
		return HypixelModApi.reasonNotOn(Island.GARDEN);
	}

	@Override
	protected void onDisable() {
		reset();
	}

	private void reset() {
		sightings.clear();
		detectionCache.clear();
		ticksSinceScan = 0;
		wasActive = false;
		lastLevel = null;
	}

	/** Runs on the client tick and never performs network or blocking work. */
	public void tick() {
		if (!isActive()) {
			if (wasActive || !sightings.isEmpty()) reset();
			return;
		}
		wasActive = true;

		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		LocalPlayer player = mc.player;
		if (level != lastLevel) {
			lastLevel = level;
			sightings.clear();
			detectionCache.clear();
			ticksSinceScan = 0;
		}
		if (level == null || player == null) return;

		// A pest disappears as soon as it is caught; do not keep its last box until the next sweep.
		sightings.removeIf(sighting -> sighting.entity().isRemoved() || !sighting.entity().isAlive());
		if (++ticksSinceScan < scanInterval.intValue()) return;
		ticksSinceScan = 0;
		scan(level, player);
	}

	private void scan(ClientLevel level, LocalPlayer player) {
		Minecraft mc = Minecraft.getInstance();
		double range = Math.min(mc.options.getEffectiveRenderDistance() * 16.0, MAX_RANGE);
		List<Entity> nearby = ClientEntitySnapshot.nearby(level, player, range);

		List<Sighting> found = new ArrayList<>();
		Set<UUID> visible = new HashSet<>();
		for (Entity candidate : nearby) {
			if (!(candidate instanceof ArmorStand entity) || !entity.isAlive()) continue;
			UUID id = entity.getUUID();
			visible.add(id);
			ItemStack head = entity.getItemBySlot(EquipmentSlot.HEAD);
			detectionCache.get(id, head, () -> PestDetector.detect(entity))
				.ifPresent(kind -> found.add(new Sighting(kind, entity)));
		}
		detectionCache.retainAll(visible);
		sightings.clear();
		sightings.addAll(found);
	}

	// ---- rendering ----------------------------------------------------------------------

	public void render(LevelRenderContext context) {
		if (!isActive() || sightings.isEmpty()) return;

		Minecraft mc = Minecraft.getInstance();
		Camera camera = mc.gameRenderer.getMainCamera();
		Vec3 camPos = camera.position();
		PoseStack poseStack = context.poseStack();
		MultiBufferSource.BufferSource bufferSource = context.bufferSource();
		Vector3fc forward = camera.forwardVector();
		boolean depth = depthCheck.value();
		float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		double elapsedTicks = mc.level == null ? 0 : mc.level.getGameTime() + partialTick;
		boolean drew = false;

		for (Sighting sighting : sightings) {
			ArmorStand entity = sighting.entity();
			if (entity.isRemoved() || !entity.isAlive()) continue;
			AABB bounds = entity.getBoundingBox();
			Vec3 center = bounds.getCenter();

			if (box.value()) {
				EspRenderer.renderBox(poseStack, bufferSource,
					bounds.minX - camPos.x, bounds.minY - camPos.y, bounds.minZ - camPos.z,
					bounds.getXsize(), bounds.getYsize(), bounds.getZsize(),
					fillColor.argb(), outlineColor.argb(), outlineWidth.value(), depth);
				drew = true;
			}

			double[] ringHeights = null;
			if (circle.value()) {
				double feet = bounds.minY + RING_ENDPOINT_PADDING;
				double head = Math.max(feet, bounds.maxY - RING_ENDPOINT_PADDING);
				ringHeights = PestAnimation.ringPositions(feet, head, elapsedTicks, circleSpeed.value(), ringCount.intValue());
				for (double ringY : ringHeights) {
					EspRenderer.renderRing(poseStack, bufferSource,
						center.x - camPos.x, ringY - camPos.y, center.z - camPos.z,
						circleRadius.value(), circleColor.argb(), circleWidth.value(), depth);
				}
				drew = true;
			}

			if (tracer.value()) {
				double targetX = center.x;
				double targetY = center.y;
				double targetZ = center.z;
				if (ringHeights != null) {
					PestAnimation.RingTarget target = PestAnimation.closestRingTarget(
						center.x, center.z, camPos.x, camPos.y, camPos.z, circleRadius.value(), ringHeights);
					targetX = target.x();
					targetY = target.y();
					targetZ = target.z();
				}
				EspRenderer.renderLine(poseStack, bufferSource,
					forward.x() * TRACER_START_DISTANCE, forward.y() * TRACER_START_DISTANCE,
					forward.z() * TRACER_START_DISTANCE,
					targetX - camPos.x, targetY - camPos.y, targetZ - camPos.z,
					tracerColor.argb(), tracerWidth.value(), depth);
				drew = true;
			}
		}

		if (drew) GeilerAddonsRenderTypes.endBatches(bufferSource);
	}

	/** Projects pest names onto the HUD because 26.1's reachable level stages cannot draw Font text. */
	public void renderHud(GuiGraphicsExtractor graphics) {
		if (!isActive() || !showName.value() || sightings.isEmpty()) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || mc.options.hideGui) return;

		Camera camera = mc.gameRenderer.getMainCamera();
		float scale = labelSize.value();
		for (Sighting sighting : sightings) {
			ArmorStand entity = sighting.entity();
			if (entity.isRemoved() || !entity.isAlive()) continue;
			String label = "Pest: " + sighting.kind().displayName();
			AABB bounds = entity.getBoundingBox();
			Vec3 world = new Vec3(bounds.getCenter().x, bounds.maxY + LABEL_HEIGHT, bounds.getCenter().z);
			ProjectedLabelRenderer.draw(graphics, camera, mc.font, world, label, nameColor.argb(), scale);
		}
	}
}
