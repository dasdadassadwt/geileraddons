package geiler.addons.client.tiki;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * Shortest click plan for a tiki: three stacked skulls on a 16-slot rotation, solved when all
 * three rotations match.
 *
 * <p>The move rule, derived from recorded play (see the Tiki Debug module):
 * <pre>
 *   click(i, dir):                dir = +1 left click, -1 right click
 *     r[i] += 2*dir                                   (mod 16)
 *     if r[(i+1)%3] != r[(i+2)%3]:                    tested on the pre-move state
 *         r[(i+1)%3] += 2*dir                         (mod 16)
 * </pre>
 * A click drags the skull above it along, unless that skull already matches the one above
 * <em>it</em> - at which point it is locked and stops moving.
 *
 * <p>Only the bottom and middle skulls are ever offered. Every reachable state is solvable in at
 * most 5 clicks that way, and allowing top clicks does not shorten a single one - which is
 * convenient, because clicking the top was never observed and its wrap-around is only inferred.
 */
public final class TikiSolver {
	public static final int SLOTS = 16;
	public static final int STEP = 2;
	/** {@link Plan#clicks()} when the count is unknowable because the slot can't be read. */
	public static final int UNKNOWN_CLICKS = -1;

	/** {skull index, direction} - deliberately excludes the top skull. */
	private static final int[][] MOVES = {{0, 1}, {0, -1}, {1, 1}, {1, -1}};
	/** Rotations are always even, so each skull has 8 distinct values. */
	private static final int VALUES = SLOTS / STEP;
	private static final int STATES = VALUES * VALUES * VALUES;

	private static final int[][] FORWARD = new int[STATES][MOVES.length];
	private static final byte[] BEST_MOVE = new byte[STATES];
	private static final byte[] DISTANCE = new byte[STATES];

	static {
		for (int state = 0; state < STATES; state++) {
			int[] rotations = decode(state);
			for (int move = 0; move < MOVES.length; move++) {
				FORWARD[state][move] = encode(applyMove(rotations, MOVES[move][0], MOVES[move][1]));
			}
		}

		List<List<int[]>> reverse = new ArrayList<>(STATES);
		for (int state = 0; state < STATES; state++) {
			reverse.add(new ArrayList<>());
		}
		for (int state = 0; state < STATES; state++) {
			for (int move = 0; move < MOVES.length; move++) {
				reverse.get(FORWARD[state][move]).add(new int[]{state, move});
			}
		}

		// Breadth-first outward from every solved state, walking edges backwards, so each state
		// ends up with the first move of a shortest path to "all three equal".
		Arrays.fill(DISTANCE, (byte) -1);
		Arrays.fill(BEST_MOVE, (byte) -1);
		Deque<Integer> queue = new ArrayDeque<>();
		for (int value = 0; value < VALUES; value++) {
			int solved = value + value * VALUES + value * VALUES * VALUES;
			DISTANCE[solved] = 0;
			queue.add(solved);
		}
		while (!queue.isEmpty()) {
			int target = queue.poll();
			for (int[] edge : reverse.get(target)) {
				int from = edge[0];
				if (DISTANCE[from] != -1) continue;
				DISTANCE[from] = (byte) (DISTANCE[target] + 1);
				BEST_MOVE[from] = (byte) edge[1];
				queue.add(from);
			}
		}
	}

	private TikiSolver() {
	}

	/**
	 * A run of identical clicks at the head of a shortest solution.
	 *
	 * @param index     which skull to click, 0 = bottom
	 * @param direction +1 for left click, -1 for right click
	 * @param clicks    how many times to click it before the plan changes
	 * @param remaining total clicks left to solve, this run included
	 */
	public record Plan(int index, int direction, int clicks, int remaining) {
	}

	/** @return the next run of clicks, or null if solved, unsolvable or not a valid tiki state */
	public static Plan solve(int[] rotations) {
		if (!isSolvableState(rotations)) return null;
		int state = encode(rotations);
		int move = BEST_MOVE[state];
		if (move < 0) return null;

		// Collapse the leading identical moves into one instruction - "click the bottom 3 times"
		// beats making the player re-read the label after every single click.
		int clicks = 0;
		int cursor = state;
		while (BEST_MOVE[cursor] == move) {
			clicks++;
			cursor = FORWARD[cursor][move];
		}
		return new Plan(MOVES[move][0], MOVES[move][1], clicks, DISTANCE[state]);
	}

	/**
	 * Plan for a tiki where one slot's rotation can't be read - the server sometimes puts an
	 * ordinary block (weeping vines, say) in a slot, and it still rotates and still counts.
	 *
	 * <p>Solvable without ever knowing the hidden value, in two phases. Writing H for the hidden
	 * slot, K for H's companion and J for the slot whose companion is H:
	 * <ol>
	 *   <li>Click J until it matches K. A click on J moves J and conditionally H, so among the two
	 *       readable slots only J moves - the unknown never enters the arithmetic.</li>
	 *   <li>Click H until the tiki completes. J and K now match, so they are locked and a click on
	 *       H moves nothing but H. At most {@code SLOTS / STEP - 1} clicks.</li>
	 * </ol>
	 *
	 * @param rotations the three slot rotations, with the hidden one's value ignored
	 * @return the plan, or null if the two readable slots aren't valid tiki rotations
	 */
	public static Plan solveWithHidden(int[] rotations, int hidden) {
		if (rotations == null || rotations.length != 3 || hidden < 0 || hidden > 2) return null;
		int companion = (hidden + 1) % 3;
		int feeder = (hidden + 2) % 3;
		for (int index : new int[]{companion, feeder}) {
			int rotation = rotations[index];
			if (rotation < 0 || rotation >= SLOTS || rotation % STEP != 0) return null;
		}

		if (rotations[feeder] == rotations[companion]) {
			return new Plan(hidden, 1, UNKNOWN_CLICKS, UNKNOWN_CLICKS);
		}
		int forward = Math.floorMod(rotations[companion] - rotations[feeder], SLOTS) / STEP;
		int backward = SLOTS / STEP - forward;
		boolean left = forward <= backward;
		int clicks = left ? forward : backward;
		return new Plan(feeder, left ? 1 : -1, clicks, clicks);
	}

	/** Applies the click rule; exposed so the debug module can check reality against it. */
	public static int[] applyMove(int[] rotations, int index, int direction) {
		int[] next = rotations.clone();
		next[index] = Math.floorMod(next[index] + STEP * direction, SLOTS);
		int companion = (index + 1) % 3;
		if (rotations[companion] != rotations[(companion + 1) % 3]) {
			next[companion] = Math.floorMod(next[companion] + STEP * direction, SLOTS);
		}
		return next;
	}

	/** A skull is locked once it matches the one above it: further clicks no longer move it. */
	public static boolean isLocked(int[] rotations, int index) {
		return rotations[index] == rotations[(index + 1) % 3];
	}

	public static boolean isSolved(int[] rotations) {
		return rotations[0] == rotations[1] && rotations[1] == rotations[2];
	}

	/** Tiki skulls always sit on even rotations; anything else isn't part of the puzzle. */
	public static boolean isSolvableState(int[] rotations) {
		if (rotations == null || rotations.length != 3) return false;
		for (int rotation : rotations) {
			if (rotation < 0 || rotation >= SLOTS || rotation % STEP != 0) return false;
		}
		return true;
	}

	private static int encode(int[] rotations) {
		return (rotations[0] / STEP) + (rotations[1] / STEP) * VALUES + (rotations[2] / STEP) * VALUES * VALUES;
	}

	private static int[] decode(int state) {
		return new int[]{
			(state % VALUES) * STEP,
			((state / VALUES) % VALUES) * STEP,
			(state / (VALUES * VALUES)) * STEP
		};
	}
}
