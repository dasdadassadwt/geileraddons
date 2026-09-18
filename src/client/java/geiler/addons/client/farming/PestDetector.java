package geiler.addons.client.farming;

import com.mojang.authlib.properties.Property;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.Objects;
import java.util.Optional;

/** Reads Hypixel's decorative player-head marker and identifies known Garden pests. */
public final class PestDetector {
	private PestDetector() {
	}

	/**
	 * Matches only ArmorStands with a player head in the head slot and a known texture property.
	 * Missing profile data is deliberately treated as unavailable rather than as a match.
	 */
	public static Optional<PestKind> detect(ArmorStand entity) {
		if (entity == null || !entity.hasItemInSlot(EquipmentSlot.HEAD)) return Optional.empty();
		return PestKind.fromTexture(headTexture(entity.getItemBySlot(EquipmentSlot.HEAD)));
	}

	static String headTexture(ItemStack stack) {
		if (stack == null || !stack.is(Items.PLAYER_HEAD)) return "";

		ResolvableProfile profile = stack.get(DataComponents.PROFILE);
		if (profile == null) return "";

		return profile.partialProfile().properties().get("textures").stream()
			.filter(Objects::nonNull)
			.map(Property::value)
			.filter(Objects::nonNull)
			.findFirst()
			.orElse("");
	}
}
