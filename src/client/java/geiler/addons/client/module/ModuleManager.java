package geiler.addons.client.module;

import java.util.ArrayList;
import java.util.List;

public final class ModuleManager {
	private static final List<Module> MODULES = new ArrayList<>();

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
}
