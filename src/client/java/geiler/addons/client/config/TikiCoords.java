package geiler.addons.client.config;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Tiki Helper's coordinate list. Kept outside the module itself so the config layer and the
 * manager screen can reach it without going through a module lookup.
 *
 * <p>Only ever touched from the client thread (tick, render and screens all run there), so a
 * plain ArrayList is enough.
 */
public final class TikiCoords {
	/** Seeded on first run, i.e. whenever the config has no coordinate list saved yet. */
	private static final int[][] DEFAULTS = {
		{-731, 134, 153}, {-731, 130, 172}, {-729, 143, 179}, {-742, 136, 231},
		{-759, 125, 245}, {-735, 128, 268}, {-714, 135, 290}, {-700, 132, 288},
		{-667, 133, 263}, {-661, 125, 245}, {-625, 133, 283}, {-614, 124, 281},
		{-596, 128, 284}, {-687, 131, 219}, {-637, 142, 161}, {-615, 143, 187},
		{-591, 139, 175}, {-573, 136, 184}, {-560, 144, 191}, {-544, 139, 208},
		{-545, 131, 213}, {-600, 137, 235}, {-587, 152, 239}, {-603, 172, 231},
		{-674, 143, 146}
	};

	private static final List<BlockPos> COORDS = new ArrayList<>();

	private TikiCoords() {
	}

	public static List<BlockPos> all() {
		return Collections.unmodifiableList(COORDS);
	}

	public static int size() {
		return COORDS.size();
	}

	/** @return false if that exact position is already in the list */
	public static boolean add(BlockPos pos) {
		if (COORDS.contains(pos)) return false;
		COORDS.add(pos);
		return true;
	}

	public static void remove(int index) {
		if (index >= 0 && index < COORDS.size()) {
			COORDS.remove(index);
		}
	}

	public static void replaceAll(List<BlockPos> coords) {
		COORDS.clear();
		COORDS.addAll(coords);
	}

	public static void seedDefaults() {
		COORDS.clear();
		for (int[] coord : DEFAULTS) {
			COORDS.add(new BlockPos(coord[0], coord[1], coord[2]));
		}
	}
}
