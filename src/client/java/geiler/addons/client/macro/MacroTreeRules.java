package geiler.addons.client.macro;

import java.util.IdentityHashMap;
import java.util.List;

/** Pure insertion and movement rules shared by the macro editor and offline checks. */
public final class MacroTreeRules {
	/** The config and clipboard codecs stop reading or writing workflow nodes beyond this depth. */
	public static final int MAX_DEPTH = 8;

	private MacroTreeRules() {
	}

	public static boolean canInsert(MacroDefinition macro, MacroStep step, List<MacroStep> target) {
		if (macro == null || step == null || target == null) return false;
		int targetDepth = findListDepth(macro.steps(), target, 0, new IdentityHashMap<>());
		if (targetDepth < 0 || containsChildList(step, target, new IdentityHashMap<>())) return false;
		return targetDepth + subtreeDepth(step, new IdentityHashMap<>()) <= MAX_DEPTH;
	}

	public static boolean canMove(MacroDefinition macro, List<MacroStep> source, MacroStep step,
		List<MacroStep> target, int targetIndex) {
		if (source == null || source.indexOf(step) < 0 || !canInsert(macro, step, target)) return false;
		int sourceIndex = source.indexOf(step);
		int insertionIndex = clamp(targetIndex, target.size());
		if (source == target && sourceIndex < insertionIndex) insertionIndex--;
		return source != target || sourceIndex != insertionIndex;
	}

	/** Moves the original node object, retaining its values and any nested child lists. */
	public static boolean move(MacroDefinition macro, List<MacroStep> source, MacroStep step,
		List<MacroStep> target, int targetIndex) {
		if (!canMove(macro, source, step, target, targetIndex)) return false;
		int sourceIndex = source.indexOf(step);
		int insertionIndex = clamp(targetIndex, target.size());
		if (source == target && sourceIndex < insertionIndex) insertionIndex--;
		source.remove(sourceIndex);
		target.add(insertionIndex, step);
		return true;
	}

	private static int findListDepth(List<MacroStep> steps, List<MacroStep> target, int depth,
		IdentityHashMap<List<MacroStep>, Boolean> visited) {
		if (steps == target) return depth;
		if (steps == null || visited.put(steps, Boolean.TRUE) != null) return -1;
		for (MacroStep step : steps) {
			if (step == null) continue;
			for (List<MacroStep> children : childLists(step)) {
				if (children == target) return depth + 1;
				int found = findListDepth(children, target, depth + 1, visited);
				if (found >= 0) return found;
			}
		}
		return -1;
	}

	private static boolean containsChildList(MacroStep step, List<MacroStep> target,
		IdentityHashMap<MacroStep, Boolean> visited) {
		if (step == null || visited.put(step, Boolean.TRUE) != null) return false;
		for (List<MacroStep> children : childLists(step)) {
			if (children == target) return true;
			for (MacroStep child : children) {
				if (containsChildList(child, target, visited)) return true;
			}
		}
		return false;
	}

	private static int subtreeDepth(MacroStep step, IdentityHashMap<MacroStep, Boolean> active) {
		if (step == null) return 0;
		if (active.put(step, Boolean.TRUE) != null) return MAX_DEPTH + 1;
		int deepest = 0;
		for (List<MacroStep> children : childLists(step)) {
			for (MacroStep child : children) {
				deepest = Math.max(deepest, 1 + subtreeDepth(child, active));
			}
		}
		active.remove(step);
		return deepest;
	}

	private static List<List<MacroStep>> childLists(MacroStep step) {
		if (step instanceof MacroStep.IfElse branch) return List.of(branch.thenSteps(), branch.elseSteps());
		if (step instanceof MacroStep.Repeat repeat) return List.of(repeat.steps());
		if (step instanceof MacroStep.RepeatUntil repeatUntil) return List.of(repeatUntil.steps());
		return List.of();
	}

	private static int clamp(int index, int size) {
		return Math.max(0, Math.min(index, size));
	}
}
