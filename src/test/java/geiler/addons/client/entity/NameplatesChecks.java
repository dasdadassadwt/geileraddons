package geiler.addons.client.entity;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

/** Offline checks for nameplate-to-body selection and nearby-player exclusion. */
public final class NameplatesChecks {
	private NameplatesChecks() {
	}

	public static void run() {
		ArmorStand body = new ArmorStand((Level) null, 1, 0, 0);
		Entity floatingLabel = markerLikeEntity(1, 2, 0);
		assertSame(body, Nameplates.resolveBody(floatingLabel, List.of(body), false),
			"a floating label resolves to the nearby mob body");

		Player nearbyPlayer = testPlayer(0.5, 0, 0);
		assertSame(body, Nameplates.resolveBody(floatingLabel, List.of(nearbyPlayer, body), false),
			"ordinary floating labels exclude nearby players");
		assertSame(nearbyPlayer, Nameplates.resolveBody(nearbyPlayer, List.of(body), true),
			"the direct fake-player path keeps the exact player body");
	}

	private static Entity markerLikeEntity(double x, double y, double z) {
		ArmorStand label = new ArmorStand((Level) null, x, y, z);
		label.setBoundingBox(AABB.ofSize(label.position(), 0, 0, 0));
		return label;
	}

	private static Player testPlayer(double x, double y, double z) {
		TestPlayer player = allocate(TestPlayer.class);
		setPosition(player, new Vec3(x, y, z));
		player.setBoundingBox(AABB.ofSize(player.position(), 0.6, 1.8, 0.6));
		return player;
	}

	private static final class TestPlayer extends Player {
		private TestPlayer() {
			super(null, new GameProfile(UUID.randomUUID(), "nearby"));
		}

		@Override
		public Component getName() {
			return Component.literal("nearby");
		}

		@Override
		public boolean isAlive() {
			return true;
		}

		@Override
		public boolean isSpectator() {
			return false;
		}

		@Override
		public boolean isLocalPlayer() {
			return false;
		}

		@Override
		public net.minecraft.world.level.GameType gameMode() {
			return net.minecraft.world.level.GameType.SURVIVAL;
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
			throw new AssertionError("could not create an offline player fixture", e);
		}
	}

	private static void setPosition(Entity entity, Vec3 position) {
		try {
			Field field = Entity.class.getDeclaredField("position");
			field.setAccessible(true);
			field.set(entity, position);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("could not initialize an offline entity fixture", e);
		}
	}

	private static void assertSame(Object expected, Object actual, String message) {
		if (expected != actual) {
			throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
		}
	}
}
