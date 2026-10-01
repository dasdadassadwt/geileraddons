package geiler.addons.client.config;

import java.util.HashSet;
import java.util.Set;

/** Pure reset-scope plan: exclusions preserve both module state and collection references. */
public final class ConfigResetPlan {
	private ConfigResetPlan() { }
	public record Plan(boolean resetAllModules, Set<String> excludedModules, Set<String> namedModules,
		boolean resetMacros, boolean resetMobEsp) {
		public Plan { excludedModules = Set.copyOf(excludedModules); namedModules = Set.copyOf(namedModules); }
	}

	public static Plan create(boolean resetModules, boolean resetMacros, boolean resetMobEsp,
		String macroModule, Set<String> mobEspModules) {
		Set<String> esp = mobEspModules == null ? Set.of() : Set.copyOf(mobEspModules);
		Set<String> excluded = new HashSet<>();
		Set<String> named = new HashSet<>();
		if (resetModules) {
			if (!resetMacros && macroModule != null) excluded.add(macroModule);
			if (!resetMobEsp) excluded.addAll(esp);
		} else {
			if (resetMacros && macroModule != null) named.add(macroModule);
			if (resetMobEsp) named.addAll(esp);
		}
		return new Plan(resetModules, excluded, named, resetMacros, resetMobEsp);
	}
}
