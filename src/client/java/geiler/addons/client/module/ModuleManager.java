package geiler.addons.client.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

public final class ModuleManager {
	private static final List<Module> MODULES = new ArrayList<>();
	private static final Set<Module> REGISTERED_MODULES = Collections.newSetFromMap(new IdentityHashMap<>());

	private ModuleManager() {
	}

	public static void register(Module module) {
		if (module == null) throw new IllegalArgumentException("module must not be null");
		for (Module existing : MODULES) {
			if (existing == module || existing.name().equals(module.name())) {
				throw new IllegalArgumentException("Module already registered: " + module.name());
			}
		}
		MODULES.add(module);
		REGISTERED_MODULES.add(module);
	}

	public static List<Module> modules() {
		return List.copyOf(MODULES);
	}

	public static List<Module> modules(Category category) {
		List<Module> result = new ArrayList<>();
		for (Module module : MODULES) {
			if (module.category() == category) {
				result.add(module);
			}
		}
		return result;
	}

	/** Tests registry membership without rebuilding and scanning a category list. */
	public static boolean contains(Category category, Module module) {
		return module != null && REGISTERED_MODULES.contains(module) && module.category() == category;
	}
}
