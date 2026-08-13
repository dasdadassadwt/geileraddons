package geiler.addons.client.module.impl;

import com.mojang.blaze3d.vertex.PoseStack;
import geiler.addons.client.location.HypixelModApi;
import geiler.addons.client.location.Island;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.render.EspRenderer;
import geiler.addons.client.render.GeilerAddonsRenderTypes;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Boxes the floor drops in the Critter Safari, found by the particle that announces them.
 *
 * <p>There is no coordinate list to check against: a drop is spotted when it spawns, from the
 * happy-villager particle the server sends at it, and confirmed by the three string item displays
 * that make up the model. That is what makes it work on spots nobody has mapped, and why a drop
 * someone else already took stops being marked on its own.
 */
public final class SafariFloorDropsModule extends Module {
	public static final SafariFloorDropsModule INSTANCE = new SafariFloorDropsModule();

	/** The model is three separate string item displays in one block; fewer is something else. */
	private static final int REQUIRED_STRINGS = 3;
	/** Dropped this long after the last confirmation, so a taken drop clears itself. */
	private static final long LIFETIME_MILLIS = 5000;
	/** A node confirmed this recently is left alone, so a short scan delay can't thrash the lookup. */
	private static final long RECONFIRM_MILLIS = 2000;
	private static final float BOX_LINE_WIDTH = 2.0f;

	private final NumberSetting scanDelay;
	private final ColorSetting color;
	private final BooleanSetting depthCheck;

	/** Marked spots against the time each was last confirmed to still hold a drop. */
	private final Map<BlockPos, Long> nodes = new HashMap<>();
	private int ticksSinceScan;
	private ClientLevel lastLevel;

	private SafariFloorDropsModule() {
		this(new Settings());
	}

	private SafariFloorDropsModule(Settings s) {
		super("Safari Floor Drops", "Highlights floor drops in the Critter Safari.", Category.HUNTING,
			s.scanDelay, s.color, s.depthCheck);
		this.scanDelay = s.scanDelay;
		this.color = s.color;
		this.depthCheck = s.depthCheck;
	}

	/** Just a carrier so the constructor can both build the settings and keep references to them. */
	private static final class Settings {
		final NumberSetting scanDelay = new NumberSetting("Scan Delay", 1, 200, 20, true);
		final ColorSetting color = new ColorSetting("Color", 0, 255, 0, 128);
		final BooleanSetting depthCheck = new BooleanSetting("Depth Check", true);
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
		nodes.clear();
	}

	public void tick() {
		if (!isActive()) {
			if (!nodes.isEmpty()) {
				nodes.clear();
			}
			return;
		}

		ClientLevel level = Minecraft.getInstance().level;
		if (level != lastLevel) {
			lastLevel = level;
			nodes.clear();
		}
		if (level == null) return;

		if (++ticksSinceScan < scanDelay.intValue()) return;
		ticksSinceScan = 0;
		reconfirm(level);
	}

	/**
	 * Re-reads each marked spot and forgets the ones that have gone quiet.
	 *
	 * <p>A spot too far away to have its entities loaded simply stops being confirmed and ages out,
	 * which is the wanted behaviour - unlike the tiki waypoints there is nothing worth remembering
	 * about a drop that is no longer in front of you.
	 */
	private void reconfirm(ClientLevel level) {
		long now = System.currentTimeMillis();
		Iterator<Map.Entry<BlockPos, Long>> iterator = nodes.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<BlockPos, Long> entry = iterator.next();
			if (now - entry.getValue() < RECONFIRM_MILLIS) continue;
			if (countStrings(level, entry.getKey()) == REQUIRED_STRINGS) {
				entry.setValue(now);
			} else if (now - entry.getValue() >= LIFETIME_MILLIS) {
				iterator.remove();
			}
		}
	}

	// ---- events -------------------------------------------------------------------------

	/** The particle lands one block above the drop, so the marker is the block below it. */
	public void onParticle(ClientboundLevelParticlesPacket packet) {
		if (!isActive()) return;
		if (!ParticleTypes.HAPPY_VILLAGER.getType().equals(packet.getParticle().getType())) return;

		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) return;

		BlockPos pos = BlockPos.containing(packet.getX(), packet.getY() - 1, packet.getZ());
		if (countStrings(level, pos) != REQUIRED_STRINGS) return;
		nodes.putIfAbsent(pos.immutable(), System.currentTimeMillis());
	}

	/** Clears the marker the moment the player acts on that block, rather than waiting it out. */
	public void onClick() {
		if (!isActive() || nodes.isEmpty()) return;
		HitResult hit = Minecraft.getInstance().hitResult;
		if (hit instanceof BlockHitResult blockHit) {
			nodes.remove(blockHit.getBlockPos());
		}
	}

	private static int countStrings(ClientLevel level, BlockPos pos) {
		List<Display.ItemDisplay> displays = level.getEntities(
			EntityTypeTest.forClass(Display.ItemDisplay.class),
			AABB.ofSize(Vec3.atCenterOf(pos), 1.0, 1.0, 1.0),
			display -> true);

		int count = 0;
		for (Display.ItemDisplay display : displays) {
			Display.ItemDisplay.ItemRenderState state = display.itemRenderState();
			if (state == null) continue;
			ItemStack stack = state.itemStack();
			if (!stack.isEmpty() && stack.is(Items.STRING)) {
				count++;
			}
		}
		return count;
	}

	// ---- rendering ----------------------------------------------------------------------

	public void render(LevelRenderContext context) {
		if (!isActive() || nodes.isEmpty()) return;

		Vec3 camPos = Minecraft.getInstance().gameRenderer.getMainCamera().position();
		PoseStack poseStack = context.poseStack();
		MultiBufferSource.BufferSource bufferSource = context.bufferSource();
		boolean depth = depthCheck.value();
		int argb = color.argb();
		long now = System.currentTimeMillis();

		for (Map.Entry<BlockPos, Long> entry : nodes.entrySet()) {
			if (now - entry.getValue() >= LIFETIME_MILLIS) continue;
			BlockPos pos = entry.getKey();
			poseStack.pushPose();
			poseStack.translate(pos.getX() - camPos.x, pos.getY() - camPos.y, pos.getZ() - camPos.z);
			EspRenderer.renderBox(poseStack, bufferSource, 0, 0, 0, 1, 1, 1, argb, argb, BOX_LINE_WIDTH, depth);
			poseStack.popPose();
		}
		GeilerAddonsRenderTypes.endBatches(bufferSource);
	}
}
