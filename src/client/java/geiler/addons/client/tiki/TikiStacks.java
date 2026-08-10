package geiler.addons.client.tiki;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Finding and reading the three-skull columns a tiki is made of. */
public final class TikiStacks {
	public static final int STACK_HEIGHT = 3;

	private TikiStacks() {
	}

	public static boolean isSkull(ClientLevel level, BlockPos pos) {
		return level.isLoaded(pos) && level.getBlockState(pos).getBlock() instanceof AbstractSkullBlock;
	}

	/** Exactly three skulls tall, so the single head the player drops nearby never matches. */
	public static boolean isStackBase(ClientLevel level, BlockPos base) {
		if (isSkull(level, base.below())) return false;
		for (int i = 0; i < STACK_HEIGHT; i++) {
			if (!isSkull(level, base.above(i))) return false;
		}
		return !isSkull(level, base.above(STACK_HEIGHT));
	}

	/** @return rotations bottom-to-top, or null if any skull is a wall head or missing */
	public static int[] readRotations(ClientLevel level, BlockPos base) {
		int[] rotations = new int[STACK_HEIGHT];
		for (int i = 0; i < STACK_HEIGHT; i++) {
			BlockPos pos = base.above(i);
			if (!level.isLoaded(pos)) return null;
			BlockState state = level.getBlockState(pos);
			if (!(state.getBlock() instanceof AbstractSkullBlock) || !state.hasProperty(SkullBlock.ROTATION)) return null;
			rotations[i] = state.getValue(SkullBlock.ROTATION);
		}
		return rotations;
	}

	/** @return the base of the nearest three-skull column within {@code range}, or null */
	public static BlockPos findNearestStackBase(ClientLevel level, Vec3 from, BlockPos center, int range) {
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (int dx = -range; dx <= range; dx++) {
			for (int dy = -range; dy <= range; dy++) {
				for (int dz = -range; dz <= range; dz++) {
					BlockPos pos = center.offset(dx, dy, dz);
					if (!isSkull(level, pos) || !isStackBase(level, pos)) continue;
					double distance = from.distanceToSqr(pos.getCenter());
					if (distance < bestDistance) {
						bestDistance = distance;
						best = pos.immutable();
					}
				}
			}
		}
		return best;
	}
}
