package geiler.addons.client.module.impl;

import com.mojang.blaze3d.vertex.PoseStack;
import geiler.addons.client.collections.FolderTree;
import geiler.addons.client.config.ModConfig;
import geiler.addons.client.gui.BlockPickerScreen;
import geiler.addons.client.gui.FolderManagerScreen;
import geiler.addons.client.location.HypixelModApi;
import geiler.addons.client.location.Island;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.ModuleAction;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.render.EspRenderer;
import geiler.addons.client.render.GeilerAddonsRenderTypes;
import geiler.addons.client.render.ProjectedLabelRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

import java.util.ArrayList;
import java.util.List;

/** Exact registered-block ESP with incremental loaded-section scanning and connected voxel rendering. */
public final class BlockEspModule extends Module {
	public static final BlockEspModule INSTANCE = new BlockEspModule();
	public static final int MAX_ENTRIES = 32;
	private static final float OUTLINE_WIDTH = 1.3f;
	private static final float TRACER_WIDTH = 1.4f;
	private static final double TRACER_START_DISTANCE = 0.5;
	private static final double LABEL_HEIGHT = 0.45;
	private static final int LABEL_HALF_HEIGHT = 4;

	private final List<BlockEspEntry> entries = new ArrayList<>();
	private final FolderTree folders = new FolderTree();
	private final ModuleAction create;
	private final ModuleAction manageFolders;
	private final NumberSetting labelSize;
	private final BlockEspScanner scanner = new BlockEspScanner();
	private int nextId;

	private BlockEspModule() {
		this(new ModuleAction("Create Block ESP Entry", "Add one exact block type to highlight.", () -> INSTANCE.create()),
			new ModuleAction("Manage Folders", "Organize Block ESP entries into nested folders.", () -> INSTANCE.openFolders()),
			new NumberSetting("Label Size", 0.5f, 3.0f, 1.0f));
	}

	private BlockEspModule(ModuleAction create, ModuleAction manageFolders, NumberSetting labelSize) {
		super("Block ESP", "Highlights selected registered block types within loaded chunks.", Category.VISUAL,
			create, manageFolders, labelSize);
		this.create = create;
		this.manageFolders = manageFolders;
		this.labelSize = labelSize;
	}

	public List<BlockEspEntry> entries() { return List.copyOf(entries); }
	public FolderTree folders() { return folders; }

	private void create() {
		if (entries.size() >= MAX_ENTRIES) return;
		entries.add(new BlockEspEntry(nextId++));
		ModConfig.markDirty();
	}

	void remove(BlockEspEntry entry) {
		entries.remove(entry);
		scanner.clear(entries);
		ModConfig.markDirty();
	}

	public BlockEspEntry blank(int id) { return new BlockEspEntry(id); }

	public void restore(List<BlockEspEntry> restored) {
		entries.clear();
		if (restored != null) {
			for (BlockEspEntry entry : restored) {
				if (entry == null || entries.size() >= MAX_ENTRIES) break;
				entries.add(entry);
			}
		}
		nextId = 0;
		for (BlockEspEntry entry : entries) nextId = Math.max(nextId, entry.id() + 1);
		scanner.clear(entries);
	}

	@Override
	public List<SettingGroup> groups() {
		List<SettingGroup> result = new ArrayList<>(entries.size() + folders.folders().size() + 1);
		result.add(new SettingGroup(null, create, manageFolders, labelSize));
		for (FolderTree.Folder folder : folders.childrenOf(null)) result.add(folderGroup(folder));
		for (BlockEspEntry entry : entries) if (!folders.contains(entry.folderId())) result.add(entry.group());
		return result;
	}

	private SettingGroup folderGroup(FolderTree.Folder folder) {
		List<SettingGroup> children = new ArrayList<>();
		for (FolderTree.Folder child : folders.childrenOf(folder.id())) children.add(folderGroup(child));
		for (BlockEspEntry entry : entries) if (folder.id().equals(entry.folderId())) children.add(entry.group());
		return new SettingGroup(folder.name(), null, false, List.of(), children, false,
			"block-esp-folder:" + folder.id());
	}

	void openPicker(BlockEspEntry entry) {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.setScreen(new BlockPickerScreen(minecraft.screen, id -> {
			entry.setBlockId(id);
			ModConfig.markDirty();
		}));
	}

	private void openFolders() {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.setScreen(new FolderManagerScreen(minecraft.screen, "Block ESP", folders,
			() -> entries.stream().map(entry -> new FolderManagerScreen.Entry(Integer.toString(entry.id()),
				"#" + entry.id() + " " + (entry.displayName().value().isBlank() ? entry.blockId() : entry.displayName().value()),
				entry.folderId())).toList(),
			(entryId, folderId) -> {
				try {
					int id = Integer.parseInt(entryId);
					for (BlockEspEntry entry : entries) if (entry.id() == id) entry.setFolderId(folderId);
				} catch (NumberFormatException ignored) { }
			}, ModConfig::markDirty));
	}

	@Override
	protected void onDisable() {
		scanner.clear(entries);
	}

	public void tick() {
		Minecraft minecraft = Minecraft.getInstance();
		if (!isEnabled()) {
			scanner.clear(entries);
			return;
		}
		ClientLevel level = minecraft.level;
		LocalPlayer player = minecraft.player;
		int renderDistance = minecraft.options.getEffectiveRenderDistance() * 16;
		scanner.tick(level, player, renderDistance, entries);
	}

	public void render(LevelRenderContext context) {
		if (!isEnabled()) return;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || minecraft.player == null) return;
		BlockEspScanner.Snapshot snapshot = scanner.snapshot();
		if (snapshot.results().isEmpty()) return;
		Camera mainCamera = minecraft.gameRenderer.getMainCamera();
		Vec3 camera = mainCamera.position();
		Island currentIsland = HypixelModApi.currentIsland();
		PoseStack pose = context.poseStack();
		MultiBufferSource.BufferSource buffers = context.bufferSource();
		boolean drew = false;
		for (BlockEspScanner.EntryResult result : snapshot.results()) {
			BlockEspEntry entry = entry(result.entryId());
			if (entry == null || !entry.enabled().value() || !entry.appliesOn(currentIsland)) continue;
			int radius = BlockEspScanner.effectiveRadius(minecraft.options.getEffectiveRenderDistance() * 16,
				entry.useCustomRange().value(), entry.customRange().intValue());
			if (result.island() != currentIsland || !result.blockId().equals(entry.blockId()) || result.radius() != radius
				|| result.connectTouching() != entry.connectTouching().value()) continue;
			if (entry.box().value() && (entry.fill().value() || entry.outline().value())) {
				if (entry.connectTouching().value()) {
					EspRenderer.renderBlockUnionPrepared(pose, buffers, result.blocks(),
						result.renderCache().membership(), entry.outline().value()
							? result.renderCache().outline() : List.of(),
						camera.x, camera.y, camera.z, entry.fill().value(), entry.outline().value(),
						entry.fillColor().argb(), entry.outlineColor().argb(), OUTLINE_WIDTH,
						entry.depthCheck().value());
				} else {
					if (!result.blocks().isEmpty()) {
						EspRenderer.renderIndividualBlocks(pose, buffers, result.blocks(), camera.x, camera.y, camera.z,
							entry.fill().value(), entry.outline().value(), entry.fillColor().argb(),
							entry.outlineColor().argb(), OUTLINE_WIDTH, entry.depthCheck().value());
					}
				}
			}
			if (result.connectTouching()) {
				for (BlockClusterer.Cluster cluster : result.clusters()) {
					if (entry.tracer().value()) {
						BlockEspTracerGeometry.Endpoints endpoints = BlockEspTracerGeometry.endpoints(
							camera, mainCamera.forwardVector(), cluster.center(), TRACER_START_DISTANCE);
						EspRenderer.renderLine(pose, buffers,
							endpoints.start().x, endpoints.start().y, endpoints.start().z,
							endpoints.end().x, endpoints.end().y, endpoints.end().z,
							entry.outlineColor().argb(), TRACER_WIDTH, entry.depthCheck().value());
					}
					drew = true;
				}
			} else if (!result.blocks().isEmpty()) {
				drew = true;
				if (entry.tracer().value()) for (BlockPos block : result.blocks()) {
					Vec3 center = new Vec3(block.getX() + 0.5, block.getY() + 0.5, block.getZ() + 0.5);
					BlockEspTracerGeometry.Endpoints endpoints = BlockEspTracerGeometry.endpoints(
						camera, mainCamera.forwardVector(), center, TRACER_START_DISTANCE);
					EspRenderer.renderLine(pose, buffers,
						endpoints.start().x, endpoints.start().y, endpoints.start().z,
						endpoints.end().x, endpoints.end().y, endpoints.end().z,
						entry.outlineColor().argb(), TRACER_WIDTH, entry.depthCheck().value());
				}
			}
		}
		if (drew) GeilerAddonsRenderTypes.endBatches(buffers);
	}

	public void renderHud(GuiGraphicsExtractor graphics) {
		if (!isEnabled() || Minecraft.getInstance().options.hideGui) return;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || minecraft.player == null) return;
		Camera camera = minecraft.gameRenderer.getMainCamera();
		Island currentIsland = HypixelModApi.currentIsland();
		float scale = labelSize.value();
		for (BlockEspScanner.EntryResult result : scanner.snapshot().results()) {
			BlockEspEntry entry = entry(result.entryId());
			if (entry == null || !entry.enabled().value() || !entry.showLabel().value()
				|| !entry.appliesOn(currentIsland)) continue;
			int radius = BlockEspScanner.effectiveRadius(minecraft.options.getEffectiveRenderDistance() * 16,
				entry.useCustomRange().value(), entry.customRange().intValue());
			if (result.island() != currentIsland || !result.blockId().equals(entry.blockId()) || result.radius() != radius
				|| result.connectTouching() != entry.connectTouching().value()) continue;
			String text = label(entry);
			if (text.isBlank()) continue;
			int color = entry.outlineColor().argb();
			if (result.connectTouching()) {
				for (BlockClusterer.Cluster cluster : result.clusters()) {
					Vec3 center = cluster.center().add(0, LABEL_HEIGHT, 0);
					ProjectedLabelRenderer.draw(graphics, camera, minecraft.font, center, text, color, scale);
				}
			} else {
				for (BlockPos block : result.blocks()) {
					Vec3 center = new Vec3(block.getX() + 0.5, block.getY() + 0.5 + LABEL_HEIGHT,
						block.getZ() + 0.5);
					ProjectedLabelRenderer.draw(graphics, camera, minecraft.font, center, text, color, scale);
				}
			}
		}
	}

	private BlockEspEntry entry(int id) {
		for (BlockEspEntry entry : entries) if (entry.id() == id) return entry;
		return null;
	}

	private static String label(BlockEspEntry entry) {
		if (!entry.displayName().value().isBlank() && !entry.displayName().value().equals("New Block")) {
			return entry.displayName().value();
		}
		Block block = BlockEspRules.resolve(entry.blockId());
		return block == null ? entry.blockId() : block.getName().getString();
	}

}
