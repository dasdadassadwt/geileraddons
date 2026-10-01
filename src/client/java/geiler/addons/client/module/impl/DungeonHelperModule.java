package geiler.addons.client.module.impl;

import com.mojang.blaze3d.vertex.PoseStack;
import geiler.addons.client.dungeon.DungeonFloor;
import geiler.addons.client.dungeon.DungeonContextTracker;
import geiler.addons.client.dungeon.DungeonGuideNode;
import geiler.addons.client.dungeon.DungeonGuidePhaseTracker;
import geiler.addons.client.dungeon.DungeonGuideProgress;
import geiler.addons.client.dungeon.DungeonGuideProgress.RouteStep;
import geiler.addons.client.dungeon.DungeonGuideSegments;
import geiler.addons.client.dungeon.DungeonGuideStore;
import geiler.addons.client.gui.DungeonGuideEditorScreen;
import geiler.addons.client.gui.DungeonGuideStagesScreen;
import geiler.addons.client.gui.DungeonFloorPickerScreen;
import geiler.addons.client.hud.HudElement;
import geiler.addons.client.config.GeilerAddonsLog;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.ModuleAction;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.Setting;
import geiler.addons.client.render.EspRenderer;
import geiler.addons.client.render.GeilerAddonsRenderTypes;
import geiler.addons.client.render.ProjectedLabelRenderer;
import geiler.addons.client.farming.PestAnimation;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;

import java.util.List;

	/** Independent player-authored dungeon guide and route runner for Catacombs floors. */
public final class DungeonHelperModule extends Module implements HudElement {
	public static final DungeonHelperModule INSTANCE = new DungeonHelperModule(new Settings());
	private final BooleanSetting labels;
	private final BooleanSetting alwaysDisplaySteps;
	private final BooleanSetting connectors;
	private final BooleanSetting throughWalls;
	private final NumberSetting renderRange;
	private final NumberSetting markerSize;
	private final NumberSetting ringCount;
	private final NumberSetting ringHeight;
	private final NumberSetting ringRadius;
	private final NumberSetting ringSpeed;
	private final NumberSetting ringWidth;
	private final BooleanSetting debug;
	private final DungeonGuideProgress progress = new DungeonGuideProgress();
	private final DungeonGuidePhaseTracker phases = new DungeonGuidePhaseTracker();
	private String lastDebugState = "";
	private long lastDebugTick = Long.MIN_VALUE;
	private DungeonGuideProgress.RouteSnapshot publishedRoute;
	private long lastUpdateNanos;
	private String lastUpdateState = "not-built";
	private long lastRenderNanos;
	private String lastRenderState = "not-rendered";

	private DungeonHelperModule(Settings settings) {
		super("Dungeon Guide", "One ordered route per floor with condition-driven guide steps and linked macros. Bind this module to open its editor.",
			Category.F7, settings.labels, settings.alwaysDisplaySteps, settings.connectors, settings.throughWalls, settings.range, settings.size,
			settings.ringCount, settings.ringHeight, settings.ringRadius, settings.ringSpeed, settings.ringWidth,
			settings.debug,
			new ModuleAction("Choose Dungeon Floor", "Set the floor used by Dungeon Guide.",
				() -> INSTANCE.openFloorPicker()),
			new ModuleAction("Edit Dungeon Guide", "Edit one named, phase-ordered route for each floor.",
				() -> INSTANCE.openEditor()),
			new ModuleAction("Edit Guide Stages", "Manage built-in and custom Guide stages and manual stage selection.",
				() -> INSTANCE.openStages()));
		this.labels = settings.labels;
		this.alwaysDisplaySteps = settings.alwaysDisplaySteps;
		this.connectors = settings.connectors;
		this.throughWalls = settings.throughWalls;
		this.renderRange = settings.range;
		this.markerSize = settings.size;
		this.ringCount = settings.ringCount;
		this.ringHeight = settings.ringHeight;
		this.ringRadius = settings.ringRadius;
		this.ringSpeed = settings.ringSpeed;
		this.ringWidth = settings.ringWidth;
		this.debug = settings.debug;
		DungeonGuideStore.load();
	}

	private void openEditor() {
		Minecraft client = Minecraft.getInstance();
		client.setScreen(new DungeonGuideEditorScreen(client.screen));
	}

	/** The module's standard keybind opens this editor without toggling the route runner. */
	public void openEditorFromKeybind() { openEditor(); }
	private void openFloorPicker() {
		Minecraft client = Minecraft.getInstance();
		client.setScreen(new DungeonFloorPickerScreen(client.screen));
	}
	private void openStages() {
		Minecraft client = Minecraft.getInstance();
		client.setScreen(new DungeonGuideStagesScreen(client.screen));
	}

	@Override public String configName() { return "Dungeon Helper"; }

	public void tick() {
		GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.F7, name(), debug.value());
		migrateLegacyRingAppearance();
		DungeonFloor floor = DungeonContextTracker.currentFloor();
		long now = System.nanoTime();
		phases.setFloor(floor, now);
		progress.setFloor(floor, now);
		if (floor == null || !isEnabled()) {
			reportDebug();
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.player != null) phases.tickCustom(client.player.getX(), client.player.getY(), client.player.getZ(), now);
		syncPhase(now);
		DungeonGuideProgress.RouteSnapshot route = routeSnapshot();
		if (route.current() != null) advanceIfReady(route.current(), "", now, client, route);
		reportDebug();
	}

	public void onChatMessage(String message) {
		if (!isEnabled() || message == null || message.isBlank()) return;
		DungeonFloor floor = DungeonContextTracker.currentFloor();
		if (floor == null) return;
		long now = System.nanoTime();
		phases.setFloor(floor, now);
		progress.setFloor(floor, now);
		DungeonGuideSegments.Event event = DungeonGuideSegments.fromChat(floor, message);
		DungeonGuideStore.TransitionTiming timing = DungeonGuideStore.routeTiming(
			floor.displayName(), progress.phase(), DungeonGuideStore.activeRoute(floor.displayName(), progress.phase()));
		phases.observe(event, timing, now);
		phases.observeCustomChat(message, now);
		syncPhase(now);
		DungeonGuideProgress.RouteSnapshot route = routeSnapshot();
		if (route.current() != null) advanceIfReady(route.current(), message, now, Minecraft.getInstance(), route);
	}

	private void advanceIfReady(DungeonGuideProgress.RouteStep node, String chatMessage, long now,
		Minecraft client, DungeonGuideProgress.RouteSnapshot route) {
		double distanceSquared = Double.POSITIVE_INFINITY;
		if (client != null && client.player != null)
			distanceSquared = client.player.distanceToSqr(node.x() + 0.5, node.y() + 0.5, node.z() + 0.5);
		if (DungeonGuideProgress.triggerReady(node, progress.elapsedNanos(now), distanceSquared,
			chatMessage, phases.phase())) triggerLinkedMacro(progress.advance(route, now));
	}

	private void syncPhase(long now) {
		DungeonFloor floor = phases.floor();
		if (floor == null) return;
		String routePhase = phases.phase();
		if (!routePhase.equals(progress.phase())) progress.setPhase(routePhase, now);
		progress.setRoute(DungeonGuideStore.routeName(floor.displayName()), now);
	}

	private void reportDebug() {
		if (!debug.value()) {
			lastDebugState = "";
			return;
		}
		DungeonFloor floor = DungeonContextTracker.currentFloor();
		DungeonGuideProgress.RouteSnapshot route = routeSnapshot();
		String state = "enabled=" + isEnabled() + ", dungeon=" + DungeonContextTracker.inDungeon()
			+ ", floor=" + (floor == null ? "unknown" : floor.displayName())
			+ ", phase=" + phases.phase() + ", phaseSource=" + phases.evidence()
			+ ", phaseManual=" + phases.manual()
			+ ", route=" + (floor == null ? "none" : DungeonGuideStore.routeName(floor.displayName()))
			+ ", step=" + route.stepIndex() + "/" + route.nodeCount()
			+ ", remaining=" + route.remaining() + ", complete=" + route.complete()
			+ ", routeNodes=" + route.nodeCount();
		Minecraft client = Minecraft.getInstance();
		long tick = client.level == null ? 0 : client.level.getGameTime();
		if (state.equals(lastDebugState) && tick - lastDebugTick < 100) return;
		lastDebugState = state;
		lastDebugTick = tick;
		GeilerAddonsLog.write(Category.F7, name(), tick, "guide: " + state
			+ ", update=" + lastUpdateState + "/" + lastUpdateNanos + "ns"
			+ ", render=" + lastRenderState + "/" + lastRenderNanos + "ns");
	}

	public void nextStep() { triggerLinkedMacro(progress.advanceManually(routeSnapshot(), System.nanoTime())); }

	public void previousStep() { progress.previous(System.nanoTime()); }

	public boolean setPhase(String phase) {
		long now = System.nanoTime();
		if (!phases.choose(phase, now)) return false;
		syncPhase(now);
		return true;
	}
	public void resumeAutomaticPhase() { phases.resumeAuto(); }
	public List<String> availablePhases() { return phases.orderedSegments(); }
	public String phaseEvidence() { return phases.evidence(); }
	public boolean phaseIsManual() { return phases.manual(); }

	public String activePhase() { return phases.phase(); }
	public int remaining() { return routeSnapshot().remaining(); }
	public List<DungeonGuideNode> activeGroup() { routeSnapshot(); return progress.cachedGroup(); }

	/** Restores old shared ring values into any imported or loaded ring nodes that still lack them. */
	public void migrateLegacyRingAppearance() {
		DungeonGuideStore.migrateLegacyRingAppearance(ringCount.intValue(), ringHeight.value(),
			ringRadius.value(), ringSpeed.value(), ringWidth.value());
	}

	/** Keep legacy shared values persisted for migration, but no longer present them as active controls. */
	@Override public boolean isSettingVisible(Setting setting) {
		return setting != ringCount && setting != ringHeight && setting != ringRadius
			&& setting != ringSpeed && setting != ringWidth;
	}

	private DungeonGuideProgress.RouteSnapshot routeSnapshot() {
		DungeonGuideProgress.RouteSnapshot route = progress.snapshot(DungeonGuideStore.all(),
			DungeonGuideStore.guideRevision());
		if (route != publishedRoute) {
			publishedRoute = route;
			lastUpdateState = route.rebuildReason();
			lastUpdateNanos = route.buildNanos();
		}
		return route;
	}

	/** Shared route ring controls, also exposed in the route editor's advanced appearance section. */
	public NumberSetting guideRingCountSetting() { return ringCount; }
	public NumberSetting guideRingHeightSetting() { return ringHeight; }
	public NumberSetting guideRingRadiusSetting() { return ringRadius; }
	public NumberSetting guideRingSpeedSetting() { return ringSpeed; }
	public NumberSetting guideRingWidthSetting() { return ringWidth; }

	@Override public String inactiveReason() {
		if (!isEnabled()) return null;
		if (!DungeonContextTracker.inDungeon()) return "Waiting for dungeon detection";
		return DungeonContextTracker.currentFloor() == null ? "Floor unknown; choose a manual floor" : null;
	}

	public void render(LevelRenderContext context) {
		long startNanos = debug.value() ? System.nanoTime() : 0L;
		if (!isEnabled()) { finishRender(startNanos, "disabled"); return; }
		DungeonGuideProgress.RouteSnapshot route = routeSnapshot();
		if (route.nodeCount() == 0) { finishRender(startNanos, "empty-route"); return; }
		if (route.complete() && !alwaysDisplaySteps.value()) { finishRender(startNanos, "complete"); return; }
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) { finishRender(startNanos, "no-player"); return; }
		float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		double elapsedTicks = client.level == null ? 0 : client.level.getGameTime() + partialTick;
		Vec3 camera = client.gameRenderer.getMainCamera().position();
		PoseStack pose = context.poseStack();
		MultiBufferSource.BufferSource buffers = context.bufferSource();
		boolean drew = false;
		boolean showAll = alwaysDisplaySteps.value();
		if (showAll) {
			for (RouteStep step : route.steps()) drew |= renderStep(step, client, camera, pose, buffers, elapsedTicks);
		} else if (route.current() != null) {
			drew = renderStep(route.current(), client, camera, pose, buffers, elapsedTicks);
		}
		RouteStep active = route.current();
		if (connectors.value() && active != null) {
			Vec3 eye = client.player.getEyePosition(partialTick);
			EspRenderer.renderLine(pose, buffers, eye.x - camera.x, eye.y - camera.y, eye.z - camera.z,
				active.x() + 0.5 - camera.x, active.y() + 0.5 - camera.y, active.z() + 0.5 - camera.z,
				active.color(), 1.5f, !throughWalls.value());
			drew = true;
		}
		if (drew) GeilerAddonsRenderTypes.endBatches(buffers);
		finishRender(startNanos, drew ? "drawn" : "no-geometry");
	}

	private boolean renderStep(RouteStep node, Minecraft client, Vec3 camera, PoseStack pose,
		MultiBufferSource.BufferSource buffers, double elapsedTicks) {
		int range = node.visibilityDistance() > 0 ? node.visibilityDistance() : renderRange.intValue();
		if (client.player.distanceToSqr(node.x() + 0.5, node.y() + 0.5, node.z() + 0.5) > (double) range * range)
			return false;
		float size = Math.max(0.25f, node.size() * markerSize.value());
		double x = node.x() + 0.5 - camera.x;
		double y = node.y() - camera.y;
		double z = node.z() + 0.5 - camera.z;
		int color = node.color();
		int fill = node.fillColor();
		switch (node.shape()) {
			case BOX -> {
				if (node.rotation() == 0) EspRenderer.renderBox(pose, buffers, x - size / 2, y, z - size / 2,
					size, size, size, fill, color, 1.5f, !throughWalls.value());
				else EspRenderer.renderRotatedBox(pose, buffers, x, y, z, size, node.rotation(), fill, color, 1.5f, !throughWalls.value());
				return true;
			}
			case BEACON -> {
				EspRenderer.renderLine(pose, buffers, x, y, z, x, y + Math.max(1.0, size * 2), z, color, 2.0f, !throughWalls.value());
				EspRenderer.renderSphere(pose, buffers, x, y + size * 0.2, z, Math.max(0.15f, size * 0.18f), color);
				return true;
			}
			case RING -> {
				float radius = node.ringRadius() * size;
				double[] heights = PestAnimation.ringPositions(node.y(), node.y() + node.ringHeight() * size,
					elapsedTicks, node.ringSpeed(), node.ringCount());
				for (double height : heights) {
					if (node.ringFill() && (fill >>> 24) != 0) {
						EspRenderer.renderFlatArea(pose, buffers, x, height - camera.y, z,
							radius, 0.0, false, fill, !throughWalls.value());
					}
					EspRenderer.renderRing(pose, buffers, x, height - camera.y, z,
						radius, color, node.ringWidth(), !throughWalls.value());
				}
				return true;
			}
			case TEXT_ONLY -> { return false; }
		}
		return false;
	}

	private void finishRender(long startNanos, String state) {
		if (startNanos == 0L) return;
		lastRenderNanos = Math.max(0L, System.nanoTime() - startNanos);
		lastRenderState = state;
	}

	public void renderHud(GuiGraphicsExtractor graphics) {
		if (!visible()) return;
		Minecraft client = Minecraft.getInstance();
		DungeonGuideProgress.RouteSnapshot route = routeSnapshot();
		String[] status = statusLines(route);
		graphics.text(client.font, status[0], hudX(graphics), hudY(graphics), 0xFFFFD24A);
		graphics.text(client.font, status[1], hudX(graphics), hudY(graphics) + 12, 0xFFB8C5DB);
		renderUpcomingLabels(graphics, client, route.current());
	}

	@Override public String id() { return "dungeon_guide_status"; }
	@Override public String displayName() { return "Dungeon Guide Status"; }
	@Override public int width(Font font) {
		String[] status = statusLines(routeSnapshot());
		return Math.max(1, Math.max(font.width(status[0]), font.width(status[1])));
	}
	@Override public int height(Font font) { return font.lineHeight * 2 + 3; }
	@Override public boolean visible() {
		if (!isEnabled() || !labels.value() || DungeonContextTracker.currentFloor() == null) return false;
		DungeonGuideProgress.RouteSnapshot route = routeSnapshot();
		return route.nodeCount() > 0 && route.remaining() > 0;
	}
	@Override public void render(GuiGraphicsExtractor graphics, Font font, int x, int y) {
		if (!visible()) return;
		DungeonGuideProgress.RouteSnapshot route = routeSnapshot();
		String[] status = statusLines(route);
		graphics.text(font, status[0], x, y, 0xFFFFD24A);
		graphics.text(font, status[1], x, y + 12, 0xFFB8C5DB);
		renderUpcomingLabels(graphics, Minecraft.getInstance(), route.current());
	}

	private int hudX(GuiGraphicsExtractor graphics) {
		return geiler.addons.client.hud.HudManager.x(this, Minecraft.getInstance().font, graphics.guiWidth());
	}
	private int hudY(GuiGraphicsExtractor graphics) {
		return geiler.addons.client.hud.HudManager.y(this, Minecraft.getInstance().font, graphics.guiHeight());
	}
	private String[] statusLines(DungeonGuideProgress.RouteSnapshot route) {
		DungeonFloor floor = DungeonContextTracker.currentFloor();
		RouteStep currentNode = route.current();
		String current = floor == null || currentNode == null ? "" : " · " + currentNode.label();
		String first = floor == null ? "Dungeon Guide" : "Dungeon Guide · "
			+ DungeonGuideSegments.label(floor, activePhase()) + current + " · " + route.remaining() + " left";
		return new String[]{first, (phases.manual() ? "Manual" : "Auto") + " · " + phases.evidence()};
	}

	private void renderUpcomingLabels(GuiGraphicsExtractor graphics, Minecraft client, RouteStep node) {
		if (client.player == null) return;
		if (node == null) return;
		int range = node.visibilityDistance() > 0 ? node.visibilityDistance() : renderRange.intValue();
		if (client.player.distanceToSqr(node.x() + 0.5, node.y() + 0.5, node.z() + 0.5) > (double) range * range) return;
		Vec3 world = new Vec3(node.x() + 0.5, node.y() + Math.max(1.0f, node.size() * markerSize.value()) + 0.25, node.z() + 0.5);
		ProjectedLabelRenderer.drawFloatingText(graphics, client.gameRenderer.getMainCamera(), client.font,
			world, node.label(), node.labelColor(), node.labelScale());
	}

	private static void triggerLinkedMacro(RouteStep node) {
		if (node != null && node.macroId() >= 0) geiler.addons.client.macro.MacroRunner.triggerGuideMacro(node.macroId());
	}

	private static final class Settings {
		final BooleanSetting labels = new BooleanSetting("Show Guide Status", false);
		final BooleanSetting alwaysDisplaySteps = new BooleanSetting("Always Display All Route Steps", false);
		final BooleanSetting connectors = new BooleanSetting("Connect Guide Steps", true);
		final BooleanSetting throughWalls = new BooleanSetting("Draw Through Walls", "Draw Through Walls", true);
		final NumberSetting range = new NumberSetting("Render Range", 8, 128, 64, true);
		final NumberSetting size = new NumberSetting("Marker Scale", 0.25f, 4.0f, 1.0f);
		final NumberSetting ringCount = new NumberSetting("Guide Ring Count", 1, 8, 3, true);
		final NumberSetting ringHeight = new NumberSetting("Guide Ring Height", 0.25f, 8.0f, 2.5f);
		final NumberSetting ringRadius = new NumberSetting("Guide Ring Radius", 0.25f, 8.0f, 1.2f);
		final NumberSetting ringSpeed = new NumberSetting("Guide Ring Speed", 0.05f, 2.0f, 0.35f);
		final NumberSetting ringWidth = new NumberSetting("Guide Ring Width", 0.5f, 5.0f, 1.5f);
		final BooleanSetting debug = new BooleanSetting("Debug Route Progress", false);
	}
}
