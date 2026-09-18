package geiler.addons.client.module.impl;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.UUID;

/** Offline checks for the exact fake-player branch used by Hideyho Finder. */
public final class HideyhoChecks {
	private HideyhoChecks() {
	}

	public static void run() {
		AbstractClientPlayer hideyho = allocate(TestClientPlayer.class);
		assertTrue(HideyhoFinderModule.isDirectHideyhoMatch(hideyho),
			"an exact named fake player uses direct body resolution");
	}

	private static final class TestClientPlayer extends AbstractClientPlayer {
		private TestClientPlayer() {
			super(null, new GameProfile(UUID.randomUUID(), "Hideyho"));
		}

		@Override
		public PlayerSkin getSkin() {
			return new PlayerSkin(null, null, null, PlayerModelType.WIDE, false);
		}

		@Override
		public Component getName() {
			return Component.literal("Hideyho");
		}

		@Override
		public boolean isAlive() {
			return true;
		}

		@Override
		public HumanoidArm getMainArm() {
			return HumanoidArm.RIGHT;
		}
	}

	@SuppressWarnings("unchecked")
	private static <T> T allocate(Class<T> type) {
		try {
			Field field = Unsafe.class.getDeclaredField("theUnsafe");
			field.setAccessible(true);
			return (T) ((Unsafe) field.get(null)).allocateInstance(type);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("could not create an offline fake player fixture", e);
		}
	}

	private static void assertTrue(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}
}
