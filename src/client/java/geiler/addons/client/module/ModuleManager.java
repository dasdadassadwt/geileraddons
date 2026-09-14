package geiler.addons.client.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ModuleManager {
	private static final List<Module> MODULES = new ArrayList<>();

	private ModuleManager() {
	}

	public static void register(Module module) {
		MODULES.add(module);
	}

	public static List<Module> modules() {
		return Collections.unmodifiableList(MODULES);
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
