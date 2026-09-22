package geiler.addons.client.module.impl;

import com.mojang.blaze3d.vertex.PoseStack;
import geiler.addons.client.collections.FolderTree;
import geiler.addons.client.entity.ClientEntitySnapshot;
import geiler.addons.client.config.ModConfig;
import geiler.addons.client.gui.FolderManagerScreen;
import geiler.addons.client.entity.Nameplates;
import geiler.addons.client.location.HypixelModApi;
import geiler.addons.client.location.Island;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.ModuleAction;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.render.EspRenderer;
import geiler.addons.client.render.GeilerAddonsRenderTypes;
import geiler.addons.client.render.WorldToScreen;
import geiler.addons.client.render.ProjectedLabelRenderer;
import geiler.addons.client.tree.ChatText;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Boxes any mob whose name or type matches text the user typed.
 *
 * <p>Holds a list of highlights rather than one set of settings, because the whole point is
 * running several at once with different colours. The list is built in the settings panel itself -
 * each highlight is a section of it - so there is no separate management screen to keep in step.
 */
public final class MobHighlightModule extends Module {
	public static final MobHighlightModule INSTANCE = new MobHighlightModule();

	/** Beyond this the entity list stops being worth walking every scan, whatever the view distance. */
	private static final int MAX_RANGE = 64;
	/** Prevents a hand-edited config from turning every client tick into an unbounded matcher pass. */
	public static final int MAX_HIGHLIGHTS = 32;
	private static final float BOX_LINE_WIDTH = 2.0f;
	/** Clear of the mob's own nameplate rather than fighting it for the same pixels. */
	private static final double LABEL_HEIGHT = 0.4;
	private static final int HUD_LABEL_HALF_HEIGHT = 4;

	private final List<MobHighlight> highlights = new ArrayList<>();
	private final ModuleAction create;
	private final ModuleAction foldersAction;
	private final FolderTree folders = new FolderTree();
	private final NumberSetting labelSize;
	private int nextId;
	private ClientLevel lastLevel;

	private MobHighlightModule() {
		this(new ModuleAction("Create Mob Highlight", () -> INSTANCE.create()),
			new ModuleAction("Manage Folders", "Organize highlights into nested folders.", () -> INSTANCE.openFolders()),
			new NumberSetting("Label Size", 0.5f, 4.0f, 1.0f));
	}

	private MobHighlightModule(ModuleAction create, ModuleAction foldersAction, NumberSetting labelSize) {
		super("Mob Highlight", "Boxes mobs whose name or type matches your text.", Category.VISUAL,
			create, foldersAction, labelSize);
		this.create = create;
		this.foldersAction = foldersAction;
		this.labelSize = labelSize;
	}

	public FolderTree folders() { return folders; }

	// ---- highlight list -----------------------------------------------------------------

	public List<MobHighlight> highlights() {
		return List.copyOf(highlights);
	}

	/** Adds an empty highlight, which the user then fills in - there is no dialog to answer. */
	private void create() {
		if (highlights.size() >= MAX_HIGHLIGHTS) return;
		highlights.add(new MobHighlight(nextId++));
		ModConfig.markDirty();
	}

	void remove(MobHighlight highlight) {
		highlights.remove(highlight);
		ModConfig.markDirty();
	}

	/** Replaces the list from the config; ids come back as saved so collapsed sections still match. */
	public void restore(List<MobHighlight> restored) {
		highlights.clear();
		if (restored != null) {
			for (MobHighlight highlight : restored) {
				if (highlight == null || highlights.size() >= MAX_HIGHLIGHTS) break;
				highlights.add(highlight);
			}
		}
		nextId = 0;
		for (MobHighlight highlight : highlights) {
			nextId = Math.max(nextId, highlight.id() + 1);
		}
	}

	/** Builds a highlight the config can fill in, without handing out the constructor. */
	public MobHighlight blank(int id) {
		return new MobHighlight(id);
	}

	/**
	 * Rebuilt on every call rather than declared once, since the sections are the highlights and
	 * those come and go while the panel is open.
	 */
	@Override
	public List<SettingGroup> groups() {
		List<SettingGroup> groups = new ArrayList<>(highlights.size() + folders.folders().size() + 1);
		groups.add(new SettingGroup(null, create, foldersAction, labelSize));
		for (FolderTree.Folder folder : folders.childrenOf(null)) groups.add(folderGroup(folder));
		for (MobHighlight highlight : highlights) if (!folders.contains(highlight.folderId())) groups.add(highlight.group());
		return groups;
	}

	private SettingGroup folderGroup(FolderTree.Folder folder) {
		List<SettingGroup> children = new ArrayList<>();
		for (FolderTree.Folder child : folders.childrenOf(folder.id())) children.add(folderGroup(child));
		for (MobHighlight highlight : highlights) if (folder.id().equals(highlight.folderId())) children.add(highlight.group());
		return new SettingGroup(folder.name(), null, false, List.of(), children, false,
			"mob-highlight-folder:" + folder.id());
	}

	private void openFolders() {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.setScreen(new FolderManagerScreen(minecraft.screen, "Mob Highlight", folders,
			() -> highlights.stream().map(highlight -> new FolderManagerScreen.Entry(
				Integer.toString(highlight.id()), "#" + highlight.id() + " " + highlight.displayName().value(),
				highlight.folderId())).toList(),
			(entryId, folderId) -> {
				try {
					int id = Integer.parseInt(entryId);
					for (MobHighlight highlight : highlights) if (highlight.id() == id) highlight.setFolderId(folderId);
				} catch (NumberFormatException ignored) { }
			}, ModConfig::markDirty));
	}

	// ---- scanning -----------------------------------------------------------------------

	@Override
	protected void onDisable() {
		clearMatches();
	}

	public void tick() {
		if (!isEnabled() || highlights.isEmpty()) return;

		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		LocalPlayer player = mc.player;
		if (level != lastLevel) {
			lastLevel = level;
			clearMatches();
		}
		if (level == null || player == null) return;

		double range = Math.min(mc.options.getEffectiveRenderDistance() * 16.0, MAX_RANGE);
		Island current = HypixelModApi.currentIsland();
		List<MobHighlight> active = new ArrayList<>();
		Map<MobHighlight, String> needles = new IdentityHashMap<>();
		for (MobHighlight highlight : highlights) {
			// Cleared rather than simply skipped, so switching one off or walking off its island
			// takes its boxes down now instead of leaving them until whatever it last saw despawns.
			if (!highlight.enabled().value() || !highlight.appliesOn(current)) {
				highlight.clearMatches();
				continue;
			}
			if (!highlight.dueForScan()) continue;
			String needle = ChatText.plain(highlight.matchText().value()).trim().toLowerCase(Locale.ROOT);
			if (needle.isEmpty()) {
				highlight.clearMatches();
				continue;
			}
			active.add(highlight);
			needles.put(highlight, needle);
		}
		if (active.isEmpty()) return;

		// One nearby query and one entity pass serve every due highlight. Matching strings and body
		// resolution are cached for this pass so adding a second highlight does not repeat world work.
		List<Entity> nearby = ClientEntitySnapshot.nearby(level, player, range);
		List<LivingEntity> nearbyBodies = nearby.stream().filter(LivingEntity.class::isInstance)
			.map(LivingEntity.class::cast).toList();
		Map<MobHighlight, List<Entity>> matches = new IdentityHashMap<>();
		for (MobHighlight highlight : active) matches.put(highlight, new ArrayList<>());
		Map<Entity, Entity> resolvedBodies = new IdentityHashMap<>();
		Set<Entity> unresolvedBodies = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Entity entity : nearby) {
			if (entity == player || !entity.isAlive()) continue;
			for (MobHighlight highlight : active) {
				if (!matches(entity, needles.get(highlight), highlight.matchName().value())) continue;
				if (unresolvedBodies.contains(entity)) continue;
				Entity target = resolvedBodies.get(entity);
				if (target == null && !resolvedBodies.containsKey(entity)) {
					target = Nameplates.resolveBody(entity, nearbyBodies, false);
					if (target == null) unresolvedBodies.add(entity);
					else resolvedBodies.put(entity, target);
				}
				if (target != null) {
					List<Entity> targets = matches.get(highlight);
					if (!targets.contains(target)) targets.add(target);
				}
			}
		}
		for (MobHighlight highlight : active) highlight.setMatches(matches.get(highlight));
	}


	/**
	 * @param byName true to match the mob's own name, false to match what kind of thing it is
	 */
	private static boolean matches(Entity entity, String needle, boolean byName) {
		if (byName) {
			Component custom = entity.getCustomName();
			return plainLower(custom != null ? custom : entity.getName()).contains(needle);
		}
		// Both spellings, so "zombie" works whether the user is thinking of the id or the label
		// they see in game - and so a non-English client still matches the English id.
		EntityType<?> type = entity.getType();
		String descriptionId = type.getDescriptionId();
		String suffix = descriptionId.substring(descriptionId.lastIndexOf('.') + 1);
		return suffix.toLowerCase(Locale.ROOT).contains(needle)
			|| plainLower(type.getDescription()).contains(needle);
	}

	private static String plainLower(Component component) {
		return ChatText.plain(component.getString()).toLowerCase(Locale.ROOT);
	}

	private void clearMatches() {
		for (MobHighlight highlight : highlights) {
			highlight.clearMatches();
		}
	}

	// ---- rendering ----------------------------------------------------------------------

	public void render(LevelRenderContext context) {
		if (!isEnabled() || highlights.isEmpty()) return;

		Vec3 camPos = Minecraft.getInstance().gameRenderer.getMainCamera().position();
		PoseStack poseStack = context.poseStack();
		MultiBufferSource.BufferSource bufferSource = context.bufferSource();
		boolean drew = false;

		for (MobHighlight highlight : highlights) {
			if (highlight.matches().isEmpty()) continue;
			int fill = highlight.fillColor().argb();
			int outline = highlight.outlineColor().argb();
			boolean depth = highlight.depthCheck().value();
			for (Entity entity : highlight.matches()) {
				if (entity.isRemoved()) continue;
				AABB box = entity.getBoundingBox();
				EspRenderer.renderBox(poseStack, bufferSource,
					box.minX - camPos.x, box.minY - camPos.y, box.minZ - camPos.z,
					box.getXsize(), box.getYsize(), box.getZsize(), fill, outline, BOX_LINE_WIDTH, depth);
				drew = true;
			}
		}
		if (drew) {
			GeilerAddonsRenderTypes.endBatches(bufferSource);
		}
	}

	/**
	 * The highlight's name above each match, in the real font.
	 *
	 * <p>Projected onto the HUD rather than drawn in the world for the same reason the tiki solver
	 * labels are: in-world text does not render from any level stage reachable here, and the
	 * stroked glyph table {@link EspRenderer} falls back on has no letters in it.
	 */
	public void renderHud(GuiGraphicsExtractor graphics) {
		if (!isEnabled() || highlights.isEmpty()) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || mc.options.hideGui) return;

		Camera camera = mc.gameRenderer.getMainCamera();
		float scale = labelSize.value();
		for (MobHighlight highlight : highlights) {
			String label = highlight.displayName().value();
			if (label.isBlank() || highlight.matches().isEmpty()) continue;
			int color = highlight.outlineColor().argb();
			for (Entity entity : highlight.matches()) {
				if (entity.isRemoved()) continue;
				AABB box = entity.getBoundingBox();
				Vec3 world = new Vec3(box.getCenter().x, box.maxY + LABEL_HEIGHT, box.getCenter().z);
				ProjectedLabelRenderer.draw(graphics, camera, mc.font, world, label, color, scale);
			}
		}
	}
}
