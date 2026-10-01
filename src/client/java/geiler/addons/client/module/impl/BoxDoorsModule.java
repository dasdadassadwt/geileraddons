package geiler.addons.client.module.impl;

import com.mojang.blaze3d.vertex.PoseStack;
import geiler.addons.client.config.GeilerAddonsLog;
import geiler.addons.client.dungeon.BloodDoorTraceRules;
import geiler.addons.client.dungeon.DungeonContextTracker;
import geiler.addons.client.dungeon.DungeonDoorTracker;
import geiler.addons.client.dungeon.DungeonDoorTraceGrace;
import geiler.addons.client.dungeon.DungeonGridRules;
import geiler.addons.client.dungeon.DungeonKeyRules;
import geiler.addons.client.dungeon.DungeonMapGeometry;
import geiler.addons.client.dungeon.DungeonDoorAdjacency;
import geiler.addons.client.dungeon.DungeonMapRoomDetector;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.render.EspRenderer;
import geiler.addons.client.render.GeilerAddonsRenderTypes;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Boxes detected dungeon doorways and traces the next known Wither door or Blood entrance. */
public final class BoxDoorsModule extends Module {
	public static final BoxDoorsModule INSTANCE = new BoxDoorsModule(new Settings());
	private final Settings settings;
	private int observedWitherKeys;
	private boolean observedBloodKey;
	private DungeonKeyRules.KeyOwnership keyOwnership = DungeonKeyRules.KeyOwnership.NONE;
	private boolean keyStateInDungeon;
	private String keyStateFloor;
	private ClientLevel keyStateLevel;
	private int traceCell = -1;
	private int traceFromCell = -1;
	private int traceToCell = -1;
	private boolean enteredThroughWither;
	private final DungeonDoorTraceGrace witherTraceGrace = new DungeonDoorTraceGrace();
	private final DungeonDoorTraceGrace bloodTraceGrace = new DungeonDoorTraceGrace();
	private String lastTraceDiagnostic = "";
	private long lastTraceDiagnosticTick = Long.MIN_VALUE;

	private BoxDoorsModule(Settings settings) {
		super("Box Doors", "Highlight dungeon doorways and trace the next known Wither door.", Category.F7,
			settings.normal, settings.normalFill, settings.normalOutline, settings.normalFillColor,
			settings.normalOutlineColor, settings.normalThroughWalls,
			settings.wither, settings.witherFill, settings.witherOutline, settings.witherFillColor,
			settings.witherOutlineColor, settings.witherKeyFillColor, settings.witherKeyOutlineColor,
			settings.witherThroughWalls, settings.blood, settings.bloodFill, settings.bloodOutline,
			settings.bloodFillColor, settings.bloodOutlineColor, settings.bloodKeyFillColor,
			settings.bloodKeyOutlineColor, settings.bloodThroughWalls, settings.visibleWitherDoors,
			settings.tracer, settings.traceBloodDoor, settings.tracerColor, settings.tracerWidth,
			settings.tracerThroughWalls, settings.range, settings.debug);
		this.settings = settings;
		group(new SettingGroup("Door Highlights").containing(
			SettingGroup.switched("Normal doors", settings.normal,
				settings.normalFill, settings.normalFillColor, settings.normalOutline,
				settings.normalOutlineColor, settings.normalThroughWalls),
			SettingGroup.switched("Wither doors", settings.wither,
				settings.witherFill, settings.witherFillColor, settings.witherKeyFillColor,
				settings.witherOutline, settings.witherOutlineColor, settings.witherKeyOutlineColor,
				settings.witherThroughWalls),
			SettingGroup.switched("Blood doors", settings.blood,
				settings.bloodFill, settings.bloodFillColor, settings.bloodKeyFillColor,
				settings.bloodOutline, settings.bloodOutlineColor, settings.bloodKeyOutlineColor,
				settings.bloodThroughWalls)),
			new SettingGroup("Routes & Tracers", settings.tracer, settings.traceBloodDoor,
				settings.visibleWitherDoors, settings.tracerColor, settings.tracerWidth,
				settings.tracerThroughWalls),
			new SettingGroup("General", settings.range),
			new SettingGroup("Diagnostics", settings.debug));
	}

	@Override public boolean isSettingVisible(geiler.addons.client.module.Setting setting) {
		if (setting == settings.normalFillColor) return settings.normal.value() && settings.normalFill.value();
		if (setting == settings.normalOutlineColor) return settings.normal.value() && settings.normalOutline.value();
		if (setting == settings.witherFillColor) return settings.wither.value() && settings.witherFill.value();
		if (setting == settings.witherOutlineColor) return settings.wither.value() && settings.witherOutline.value();
		if (setting == settings.witherKeyFillColor) return settings.wither.value() && settings.witherFill.value();
		if (setting == settings.witherKeyOutlineColor) return settings.wither.value() && settings.witherOutline.value();
		if (setting == settings.bloodFillColor) return settings.blood.value() && settings.bloodFill.value();
		if (setting == settings.bloodOutlineColor) return settings.blood.value() && settings.bloodOutline.value();
		if (setting == settings.bloodKeyFillColor) return settings.blood.value() && settings.bloodFill.value();
		if (setting == settings.bloodKeyOutlineColor) return settings.blood.value() && settings.bloodOutline.value();
		if (setting == settings.tracerColor || setting == settings.tracerWidth
			|| setting == settings.tracerThroughWalls) return settings.tracer.value() || settings.traceBloodDoor.value();
		return true;
	}

	@Override public boolean isActive() { return isEnabled() && DungeonContextTracker.inDungeon(); }

	@Override public String inactiveReason() {
		return isEnabled() && !DungeonContextTracker.inDungeon() ? "Waiting for a dungeon" : null;
	}

	public void tick() {
		GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.F7, name(), settings.debug.value());
		Minecraft client = Minecraft.getInstance();
		boolean inDungeon = DungeonContextTracker.inDungeon();
		String floor = DungeonContextTracker.currentFloor() == null ? null
			: DungeonContextTracker.currentFloor().displayName();
		if (!inDungeon || !isEnabled() || client.level == null || client.player == null) {
			resetKeyState();
		} else if (!keyStateInDungeon || client.level != keyStateLevel
			|| !java.util.Objects.equals(floor, keyStateFloor)) {
			resetKeyState();
			keyStateInDungeon = true;
			keyStateFloor = floor;
			keyStateLevel = client.level;
		}
		if (inDungeon && isEnabled() && client.level != null && client.player != null) {
			DungeonKeyRules.KeyOwnership inventoryKeys = scanKeyOwnership(client);
			keyOwnership = DungeonKeyRules.combineOwnership(observedWitherKeys > 0, observedBloodKey,
				inventoryKeys.wither(), inventoryKeys.blood());
		}
		DungeonDoorTracker.tick(isEnabled() ? client : null);
		updateBloodTraceContext(client, inDungeon, floor);
		reportTraceDebug(client);
	}

	private void updateBloodTraceContext(Minecraft client, boolean inDungeon, String floor) {
		if (!inDungeon || client == null || client.player == null || client.level == null
			|| client.level != keyStateLevel || !java.util.Objects.equals(floor, keyStateFloor)) {
			traceCell = traceFromCell = traceToCell = -1;
			enteredThroughWither = false;
			return;
		}
		DungeonGridRules.Cell cell = DungeonGridRules.cellAt(client.player.blockPosition().getX(),
			client.player.blockPosition().getZ());
		int nextCell = cell == null ? -1 : cell.componentZ() * 6 + cell.componentX();
		if (nextCell != traceCell) {
			traceFromCell = traceCell;
			traceToCell = nextCell;
			traceCell = nextCell;
		}
		enteredThroughWither = traceFromCell >= 0 && traceToCell >= 0
			&& DungeonDoorTracker.doors().stream().anyMatch(door -> door.type() == DungeonDoorTracker.Type.WITHER
				&& (door.firstCell() == traceFromCell && door.secondCell() == traceToCell
					|| door.firstCell() == traceToCell && door.secondCell() == traceFromCell));
	}

	/** Local-player pickup/open events are a fallback when the inventory item name is hidden. */
	public void onChatMessage(String line) {
		if (!DungeonContextTracker.inDungeon() || line == null) return;
		String message = geiler.addons.client.tree.ChatText.plain(line).trim();
		Minecraft client = Minecraft.getInstance();
		String localName = client.player == null ? "" : client.player.getName().getString();
		if (DungeonKeyRules.isPickupForPlayer(message, localName, DungeonDoorTracker.Type.WITHER)) {
			observedWitherKeys++;
		} else if (DungeonKeyRules.isPickupForPlayer(message, localName, DungeonDoorTracker.Type.BLOOD)) {
			observedBloodKey = true;
		} else if (DungeonKeyRules.isDoorOpenedByPlayer(message, localName, DungeonDoorTracker.Type.WITHER)) {
			observedWitherKeys = Math.max(0, observedWitherKeys - 1);
		} else if (message.equalsIgnoreCase("The BLOOD DOOR has been opened!")) {
			observedBloodKey = false;
		}
	}

	private void resetKeyState() {
		observedWitherKeys = 0;
		observedBloodKey = false;
		keyOwnership = DungeonKeyRules.KeyOwnership.NONE;
		keyStateInDungeon = false;
		keyStateFloor = null;
		keyStateLevel = null;
		traceCell = traceFromCell = traceToCell = -1;
		enteredThroughWither = false;
		witherTraceGrace.reset();
		bloodTraceGrace.reset();
	}

	private void reportTraceDebug(Minecraft client) {
		if (!settings.debug.value() || !isEnabled()) {
			lastTraceDiagnostic = "";
			return;
		}
		long witherDoors = DungeonDoorTracker.doors().stream()
			.filter(door -> door.type() == DungeonDoorTracker.Type.WITHER).count();
		long bloodDoors = DungeonDoorTracker.doors().stream()
			.filter(door -> door.type() == DungeonDoorTracker.Type.BLOOD).count();
		DungeonDoorTracker.Door target = client == null || client.player == null ? null
			: DungeonDoorTracker.nextBloodDoor(client.player.blockPosition());
		BloodDoorTraceRules.DoorPresence bloodPresence = target == null || client == null
			? BloodDoorTraceRules.DoorPresence.UNKNOWN : doorPresence(client.level, target);
		boolean closedBloodTarget = bloodPresence == BloodDoorTraceRules.DoorPresence.CLOSED;
		DungeonGridRules.Cell playerCell = physicalPlayerCell(client);
		List<DungeonMapGeometry.CellOffset> footprintOffsets = roomFootprintOffsets(client, playerCell);
		boolean bloodBoundary = DungeonDoorAdjacency.bordersCurrentRoom(target, playerCell, footprintOffsets);
		boolean traceDecision = settings.traceBloodDoor.value() && closedBloodTarget && bloodBoundary;
		double targetDistance = target == null || client == null || client.player == null ? -1.0
			: Math.sqrt(client.player.distanceToSqr(target.centerX(), target.centerY(), target.centerZ()));
		String state = "dungeon=" + DungeonContextTracker.inDungeon() + ", cell=" + traceCell
			+ ", transition=" + traceFromCell + "->" + traceToCell + ", enteredThroughWither=" + enteredThroughWither
			+ ", doors(wither=" + witherDoors + ", blood=" + bloodDoors + ")"
			+ ", bloodTracer=" + settings.traceBloodDoor.value()
			+ ", bloodTarget=" + (target == null ? "none" : bloodPresence.toString().toLowerCase(java.util.Locale.ROOT))
			+ ", physicalCell=" + (playerCell == null ? "unknown" : playerCell.componentX() + "," + playerCell.componentZ())
			+ ", roomFootprint=" + DungeonDoorAdjacency.footprint(playerCell, footprintOffsets)
			+ ", connectedBloodBoundary=" + bloodBoundary
			+ ", traceDecision=" + traceDecision
			+ (target == null ? "" : " (about " + Math.round(targetDistance / 5.0) * 5 + " blocks)");
		long tick = client == null || client.level == null ? 0 : client.level.getGameTime();
		if (state.equals(lastTraceDiagnostic) && tick - lastTraceDiagnosticTick < 100) return;
		lastTraceDiagnostic = state;
		lastTraceDiagnosticTick = tick;
		GeilerAddonsLog.write(Category.F7, name(), tick, "door trace: " + state);
	}

	public void render(LevelRenderContext context) {
		if (!isActive()) return;
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.level == null) return;
		Vec3 camera = client.gameRenderer.getMainCamera().position();
		PoseStack pose = context.poseStack();
		MultiBufferSource.BufferSource buffers = context.bufferSource();
		int range = settings.range.intValue();
		boolean hasWitherKey = keyOwnership.wither();
		boolean hasBloodKey = keyOwnership.blood();
		Set<DungeonDoorTracker.Door> visibleWither = new HashSet<>(DungeonDoorTracker.orderedWitherDoors(
			client.player.blockPosition(), settings.visibleWitherDoors.intValue()));
		DungeonGridRules.Cell currentCell = DungeonGridRules.cellAt(
			client.player.blockPosition().getX(), client.player.blockPosition().getZ());
		boolean drew = false;
		for (DungeonDoorTracker.Door door : DungeonDoorTracker.doors()) {
			boolean normal = door.type() == DungeonDoorTracker.Type.NORMAL;
			boolean blood = door.type() == DungeonDoorTracker.Type.BLOOD;
			if (normal && (!settings.normal.value() || !DungeonDoorTracker.touchesCell(door, currentCell))) continue;
			if (door.type() == DungeonDoorTracker.Type.WITHER && (!settings.wither.value() || !visibleWither.contains(door))) continue;
			if (blood && !settings.blood.value()) continue;
			if (client.player.distanceToSqr(door.centerX(), door.centerY(), door.centerZ()) > (double) range * range) continue;
			boolean fill = normal ? settings.normalFill.value() : blood ? settings.bloodFill.value() : settings.witherFill.value();
			boolean outline = normal ? settings.normalOutline.value() : blood ? settings.bloodOutline.value() : settings.witherOutline.value();
			if (!fill && !outline) continue;
			int fillColor = fill ? (normal ? settings.normalFillColor.argb()
				: blood ? (hasBloodKey ? settings.bloodKeyFillColor.argb() : settings.bloodFillColor.argb())
				: (hasWitherKey ? settings.witherKeyFillColor.argb() : settings.witherFillColor.argb())) : 0;
			int outlineColor = outline ? (normal ? settings.normalOutlineColor.argb()
				: blood ? (hasBloodKey ? settings.bloodKeyOutlineColor.argb() : settings.bloodOutlineColor.argb())
				: (hasWitherKey ? settings.witherKeyOutlineColor.argb() : settings.witherOutlineColor.argb())) : 0;
			boolean throughWalls = normal ? settings.normalThroughWalls.value()
				: blood ? settings.bloodThroughWalls.value() : settings.witherThroughWalls.value();
			EspRenderer.renderBox(pose, buffers, door.x() - camera.x, door.y() - camera.y,
				door.z() - camera.z, door.width(), door.height(), door.depth(),
				fillColor, outlineColor, 1.5f, !throughWalls);
			drew = true;
		}
		DungeonDoorTracker.Door activeWitherTrace = null;
		if (settings.tracer.value()) {
			DungeonDoorTracker.Door selected = DungeonDoorTracker.nextWitherDoor(client.player.blockPosition());
			BloodDoorTraceRules.DoorPresence presence = doorPresence(client.level, selected);
			boolean eligible = eligibleRoomForDoor(client, selected,
				presence == BloodDoorTraceRules.DoorPresence.CLOSED);
			DungeonDoorTracker.Door target = witherTraceGrace.update(DungeonContextTracker.inDungeon(),
				client.level, DungeonContextTracker.currentFloor(), selected, presence, eligible,
				retained -> doorPresence(client.level, retained), client.level.getGameTime());
			if (target != null) {
				activeWitherTrace = target;
				drew |= renderTracer(client, pose, buffers, camera, target);
			}
		} else {
			witherTraceGrace.reset();
		}
		if (settings.traceBloodDoor.value()) {
			if (!BloodDoorTraceRules.bloodSequenceAllowsTrace(activeWitherTrace)) {
				// Do not let Blood's leave-room grace keep drawing it while a Wither is still next in sequence.
				bloodTraceGrace.reset();
			} else {
				DungeonDoorTracker.Door selected = DungeonDoorTracker.nextBloodDoor(client.player.blockPosition());
				BloodDoorTraceRules.DoorPresence presence = doorPresence(client.level, selected);
				boolean eligible = eligibleRoomForDoor(client, selected,
					presence == BloodDoorTraceRules.DoorPresence.CLOSED);
				DungeonDoorTracker.Door target = bloodTraceGrace.update(DungeonContextTracker.inDungeon(),
					client.level, DungeonContextTracker.currentFloor(), selected, presence, eligible,
					retained -> doorPresence(client.level, retained), client.level.getGameTime());
				// The Blood entrance may be far across the grid, so its tracer intentionally ignores box range.
				if (target != null) drew |= renderTracer(client, pose, buffers, camera, target, false);
			}
		} else {
			bloodTraceGrace.reset();
		}
		if (drew) GeilerAddonsRenderTypes.endBatches(buffers);
	}

	private static boolean eligibleRoomForDoor(Minecraft client, DungeonDoorTracker.Door target, boolean closed) {
		if (client == null || client.player == null || target == null || !closed) return false;
		DungeonGridRules.Cell playerCell = physicalPlayerCell(client);
		return DungeonDoorAdjacency.bordersCurrentRoom(target, playerCell,
			roomFootprintOffsets(client, playerCell));
	}

	private static DungeonGridRules.Cell physicalPlayerCell(Minecraft client) {
		return client == null || client.player == null ? null
			: DungeonGridRules.cellAt(client.player.blockPosition().getX(), client.player.blockPosition().getZ());
	}

	private static List<DungeonMapGeometry.CellOffset> roomFootprintOffsets(Minecraft client,
		DungeonGridRules.Cell physicalCell) {
		if (client == null || physicalCell == null) return List.of();
		DungeonMapRoomDetector.Reading map = DungeonMapRoomDetector.current();
		if (map == null || !map.hasCurrentCell() || map.geometry() == null
			|| map.currentCell().componentX() != physicalCell.componentX()
			|| map.currentCell().componentZ() != physicalCell.componentZ()) {
			return List.of(new DungeonMapGeometry.CellOffset(0, 0));
		}
		return map.geometry().connectedCells();
	}

	private boolean renderTracer(Minecraft client, PoseStack pose, MultiBufferSource.BufferSource buffers,
		Vec3 camera, DungeonDoorTracker.Door target) {
		return renderTracer(client, pose, buffers, camera, target, true);
	}

	private boolean renderTracer(Minecraft client, PoseStack pose, MultiBufferSource.BufferSource buffers,
		Vec3 camera, DungeonDoorTracker.Door target, boolean enforceRange) {
		if (enforceRange && client.player.distanceToSqr(target.centerX(), target.centerY(), target.centerZ())
			> (double) settings.range.intValue() * settings.range.intValue()) return false;
		var endpoints = BlockEspTracerGeometry.endpoints(camera,
			client.gameRenderer.getMainCamera().forwardVector(),
			new Vec3(target.centerX(), target.centerY(), target.centerZ()), 0.5);
		EspRenderer.renderLine(pose, buffers,
			endpoints.start().x, endpoints.start().y, endpoints.start().z,
			endpoints.end().x, endpoints.end().y, endpoints.end().z,
			settings.tracerColor.argb(), settings.tracerWidth.value(),
			!settings.tracerThroughWalls.value());
		return true;
	}

	private static BloodDoorTraceRules.DoorPresence doorPresence(ClientLevel level, DungeonDoorTracker.Door target) {
		if (level == null || target == null) return BloodDoorTraceRules.DoorPresence.UNKNOWN;
		if (target.type() == DungeonDoorTracker.Type.BLOOD) {
			return BloodDoorTraceRules.doorPresence(target, DungeonDoorTracker.Type.BLOOD, level::hasChunkAt,
				position -> level.getBlockState(position).is(Blocks.RED_TERRACOTTA));
		}
		if (target.type() == DungeonDoorTracker.Type.WITHER) {
			return BloodDoorTraceRules.doorPresence(target, DungeonDoorTracker.Type.WITHER, level::hasChunkAt,
				position -> level.getBlockState(position).is(Blocks.COAL_BLOCK));
		}
		return BloodDoorTraceRules.DoorPresence.UNKNOWN;
	}

	private static DungeonKeyRules.KeyOwnership scanKeyOwnership(Minecraft client) {
		if (client == null || client.player == null) return DungeonKeyRules.KeyOwnership.NONE;
		boolean wither = false;
		boolean blood = false;
		for (int slot = 0; slot < client.player.getInventory().getContainerSize(); slot++) {
			var stack = client.player.getInventory().getItem(slot);
			if (stack.isEmpty()) continue;
			String displayName = stack.getHoverName().getString();
			if (!wither) wither = DungeonKeyRules.isMatchingKey(displayName, DungeonDoorTracker.Type.WITHER);
			if (!blood) blood = DungeonKeyRules.isMatchingKey(displayName, DungeonDoorTracker.Type.BLOOD);
			if (wither && blood) break;
		}
		// A key moved with the cursor is still in the player's possession, even though its slot is
		// temporarily empty. Include it in this same once-per-tick ownership snapshot.
		if (!wither || !blood) {
			var carried = client.player.containerMenu.getCarried();
			if (!carried.isEmpty()) {
				String displayName = carried.getHoverName().getString();
				if (!wither) wither = DungeonKeyRules.isMatchingKey(displayName, DungeonDoorTracker.Type.WITHER);
				if (!blood) blood = DungeonKeyRules.isMatchingKey(displayName, DungeonDoorTracker.Type.BLOOD);
			}
		}
		return new DungeonKeyRules.KeyOwnership(wither, blood);
	}

	private static final class Settings {
		final BooleanSetting normal = new BooleanSetting("Show Normal Doors", true);
		final BooleanSetting normalFill = new BooleanSetting("Normal Door Fill", true);
		final BooleanSetting normalOutline = new BooleanSetting("Normal Door Outline", true);
		final ColorSetting normalFillColor = new ColorSetting("Normal Door Fill Color", 69, 217, 255, 42);
		final ColorSetting normalOutlineColor = new ColorSetting("Normal Door Outline Color", 69, 217, 255, 255);
		final BooleanSetting normalThroughWalls = new BooleanSetting("Normal Doors Through Walls", "Draw Through Walls", true);
		final BooleanSetting wither = new BooleanSetting("Show Wither Doors", true);
		final BooleanSetting witherFill = new BooleanSetting("Wither Door Fill", true);
		final BooleanSetting witherOutline = new BooleanSetting("Wither Door Outline", true);
		final ColorSetting witherFillColor = new ColorSetting("Wither Door Fill Color", 255, 107, 107, 72);
		final ColorSetting witherOutlineColor = new ColorSetting("Wither Door Outline Color", 255, 107, 107, 255);
		final ColorSetting witherKeyFillColor = new ColorSetting("Wither Door Fill Color · Key", 0, 220, 88, 72);
		final ColorSetting witherKeyOutlineColor = new ColorSetting("Wither Door Outline Color · Key", 0, 255, 110, 255);
		final BooleanSetting witherThroughWalls = new BooleanSetting("Wither Doors Through Walls", "Draw Through Walls", true);
		final BooleanSetting blood = new BooleanSetting("Show Blood Doors", true);
		final BooleanSetting bloodFill = new BooleanSetting("Blood Door Fill", true);
		final BooleanSetting bloodOutline = new BooleanSetting("Blood Door Outline", true);
		final ColorSetting bloodFillColor = new ColorSetting("Blood Door Fill Color", 255, 55, 60, 72);
		final ColorSetting bloodOutlineColor = new ColorSetting("Blood Door Outline Color", 255, 55, 60, 255);
		final ColorSetting bloodKeyFillColor = new ColorSetting("Blood Door Fill Color · Key", 0, 220, 88, 72);
		final ColorSetting bloodKeyOutlineColor = new ColorSetting("Blood Door Outline Color · Key", 0, 255, 110, 255);
		final BooleanSetting bloodThroughWalls = new BooleanSetting("Blood Doors Through Walls", "Draw Through Walls", true);
		final NumberSetting visibleWitherDoors = new NumberSetting("Wither Doors Ahead", 1, 6, 2, true);
		final BooleanSetting tracer = new BooleanSetting("Trace Next Wither Door", true);
		final BooleanSetting traceBloodDoor = new BooleanSetting("Trace Blood Door", true);
		final ColorSetting tracerColor = new ColorSetting("Wither Door Tracer Color", 12, 0, 0, 220);
		final NumberSetting tracerWidth = new NumberSetting("Wither Door Tracer Width", 0.5f, 5.0f, 3.0441177f);
		final BooleanSetting tracerThroughWalls = new BooleanSetting("Door Tracers Through Walls", "Draw Through Walls", true);
		final NumberSetting range = new NumberSetting("Door Render Range", 16, 256, 192, true);
		final BooleanSetting debug = new BooleanSetting("Debug Door Tracing", false);
	}
}
