package geiler.addons.client.tiki;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Shortest click plan for a tiki: three stacked heads on a 16-slot rotation, solved when all three
 * rotations match.
 *
 * <p>Only the bottom and middle heads are ever offered. Every reachable state is solvable in at
 * most five clicks that way, and allowing top clicks does not shorten a single one.
 */
public final class TikiSolver {
	public static final int SLOTS = 16;
	public static final int STEP = 2;
	/** {@link Plan#clicks()} when the count is unknowable because the slot can't be read. */
	public static final int UNKNOWN_CLICKS = -1;

	/** {head index, direction} - deliberately excludes the top head. */
	private static final int[][] MOVES = {{0, 1}, {0, -1}, {1, 1}, {1, -1}};
	/** Rotations are always even, so each head has 8 distinct values. */
	private static final int VALUES = SLOTS / STEP;
	private static final int STATES = VALUES * VALUES * VALUES;

	/** Which reading of the puzzle's rules to plan against. */
	public enum Rule {
		/**
		 * Derived from recorded play and matching every one of the 80-odd verified rotations: a
		 * click turns the clicked head, and the head above it unless that head already matches the
		 * head above <em>it</em>.
		 */
		OBSERVED {
			@Override
			public int[] apply(int[] rotations, int index, int direction) {
				int[] next = rotations.clone();
				next[index] = wrap(next[index] + STEP * direction);
				int companion = (index + 1) % 3;
				if (rotations[companion] != rotations[(companion + 1) % 3]) {
					next[companion] = wrap(next[companion] + STEP * direction);
				}
				return next;
			}

			@Override
			public boolean locked(int[] rotations, int index) {
				return rotations[index] == rotations[(index + 1) % 3];
			}
		},
		/**
		 * The wiki's wording taken literally: "if the top 2 heads are facing the same direction,
		 * they will stop rotating", i.e. heads 1 and 2 freeze outright once they match.
		 *
		 * <p>Kept as an option for comparison, but the recorded logs contradict it - clicking the
		 * middle head of {@code [10, 4, 10]} moved only the middle, while this rule says the top
		 * should have moved too.
		 */
		WIKI {
			@Override
			public int[] apply(int[] rotations, int index, int direction) {
				int[] next = rotations.clone();
				boolean topPairFrozen = rotations[1] == rotations[2];
				if (!(topPairFrozen && index >= 1)) {
					next[index] = wrap(next[index] + STEP * direction);
				}
				int companion = (index + 1) % 3;
				if (!(topPairFrozen && companion >= 1)) {
					next[companion] = wrap(next[companion] + STEP * direction);
				}
				return next;
			}

			@Override
			public boolean locked(int[] rotations, int index) {
				return index >= 1 && rotations[1] == rotations[2];
			}
		};

		public abstract int[] apply(int[] rotations, int index, int direction);

		public abstract boolean locked(int[] rotations, int index);

		private static int wrap(int rotation) {
			return Math.floorMod(rotation, SLOTS);
		}
	}

	private record Tables(int[][] forward, byte[] bestMove, byte[] distance) {
	}

	private static final Map<Rule, Tables> TABLES = new EnumMap<>(Rule.class);

	static {
		for (Rule rule : Rule.values()) {
			TABLES.put(rule, build(rule));
		}
	}

	/** Breadth-first outward from every solved state, walking the move graph backwards. */
	private static Tables build(Rule rule) {
		int[][] forward = new int[STATES][MOVES.length];
		for (int state = 0; state < STATES; state++) {
			int[] rotations = decode(state);
			for (int move = 0; move < MOVES.length; move++) {
				forward[state][move] = encode(rule.apply(rotations, MOVES[move][0], MOVES[move][1]));
			}
		}

		List<List<int[]>> reverse = new ArrayList<>(STATES);
		for (int state = 0; state < STATES; state++) {
			reverse.add(new ArrayList<>());
		}
		for (int state = 0; state < STATES; state++) {
			for (int move = 0; move < MOVES.length; move++) {
				reverse.get(forward[state][move]).add(new int[]{state, move});
			}
		}

		byte[] distance = new byte[STATES];
		byte[] bestMove = new byte[STATES];
		Arrays.fill(distance, (byte) -1);
		Arrays.fill(bestMove, (byte) -1);
		Deque<Integer> queue = new ArrayDeque<>();
		for (int value = 0; value < VALUES; value++) {
			int solved = value + value * VALUES + value * VALUES * VALUES;
			distance[solved] = 0;
			queue.add(solved);
		}
		while (!queue.isEmpty()) {
			int target = queue.poll();
			for (int[] edge : reverse.get(target)) {
				int from = edge[0];
				if (distance[from] != -1) continue;
				distance[from] = (byte) (distance[target] + 1);
				bestMove[from] = (byte) edge[1];
				queue.add(from);
			}
		}
		return new Tables(forward, bestMove, distance);
	}

	private TikiSolver() {
	}

	/**
	 * A run of identical clicks at the head of a shortest solution.
	 *
	 * @param index     which head to click, 0 = bottom
	 * @param direction +1 for left click, -1 for right click
	 * @param clicks    how many times to click it before the plan changes
	 * @param remaining total clicks left to solve, this run included
	 */
	public record Plan(int index, int direction, int clicks, int remaining) {
	}

	/** @return the next run of clicks, or null if solved, unsolvable or not a valid tiki state */
	public static Plan solve(int[] rotations, Rule rule) {
		if (!isSolvableState(rotations)) return null;
		Tables tables = TABLES.get(rule);
		int state = encode(rotations);
		int move = tables.bestMove()[state];
		if (move < 0) return null;

		// Collapse the leading identical moves into one instruction - "click the bottom 3 times"
		// beats making the player re-read the label after every single click.
		int clicks = 0;
		int cursor = state;
		while (tables.bestMove()[cursor] == move) {
			clicks++;
			cursor = tables.forward()[cursor][move];
		}
		return new Plan(MOVES[move][0], MOVES[move][1], clicks, tables.distance()[state]);
	}

	/**
	 * Plan for a tiki where one slot's rotation can't be read - the server sometimes puts an
	 * ordinary block in a slot, and it still rotates and still counts.
	 *
	 * <p>Solvable without ever knowing the hidden value, in two phases. Writing H for the hidden
	 * slot, K for H's companion and J for the slot whose companion is H: click J until it matches
	 * K (a click on J moves J and conditionally H, so among the readable pair only J moves), then
	 * click H until the tiki completes (J and K now match, so they are locked).
	 *
	 * <p>Only defined for {@link Rule#OBSERVED}; the wiki reading gives no such guarantee.
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

	/** Applies the click rule; exposed so the debug log can check reality against it. */
	public static int[] applyMove(int[] rotations, int index, int direction, Rule rule) {
		return rule.apply(rotations, index, direction);
	}

	/** A head that further clicks will no longer move. */
	public static boolean isLocked(int[] rotations, int index, Rule rule) {
		return rule.locked(rotations, index);
	}

	public static boolean isSolved(int[] rotations) {
		return rotations[0] == rotations[1] && rotations[1] == rotations[2];
	}

	/** Tiki heads always sit on even rotations; anything else isn't part of the puzzle. */
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
