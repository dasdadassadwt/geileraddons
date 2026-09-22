package geiler.addons.client.module.impl;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

/** Registered-block identity rules; block-state properties intentionally do not participate. */
public final class BlockEspRules {
	private BlockEspRules() { }

	public static Block resolve(String selectedId) {
		Identifier id = Identifier.tryParse(selectedId == null ? "" : selectedId);
		if (id == null || !BuiltInRegistries.BLOCK.keySet().contains(id)) return null;
		return BuiltInRegistries.BLOCK.getValue(id);
	}

	public static boolean matches(String selectedId, Block actualBlock) {
		if (selectedId == null || selectedId.isBlank() || actualBlock == null) return false;
		if (resolve(selectedId) == null) return false;
		var actualId = BuiltInRegistries.BLOCK.getKey(actualBlock);
		return actualId != null && selectedId.equals(actualId.toString());
	}
}
