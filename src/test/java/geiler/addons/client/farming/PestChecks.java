package geiler.addons.client.farming;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.impl.PestHighlighterModule;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.UUID;
import java.util.Optional;
import java.util.Set;

/** Offline checks for the pest roster, animation boundaries and user-facing defaults. */
public final class PestChecks {
	private PestChecks() {
	}

	public static void run() {
		checkTextureRoster();
		checkHeadProfiles();
		checkDetectionCache();
		checkAnimation();
		checkSettings();
	}

	private static void checkTextureRoster() {
		assertEquals(17, PestKind.textureCount(), "the full 26.1 pest texture roster is present");
		assertEquals(2, PestKind.EARTHWORM.textures().size(), "Earthworm head and tail are aliases");
		assertEquals(2, PestKind.FIREFLY.textures().size(), "Firefly head and flash are aliases");
		for (String texture : PestKind.knownTextures()) {
			assertTrue(PestKind.fromTexture(texture).isPresent(), "known texture resolves");
		}
		assertSame(PestKind.EARTHWORM,
			PestKind.fromTexture(PestKind.EARTHWORM.textures().get(1)).orElseThrow(),
			"Earthworm tail resolves to Earthworm");
		assertSame(PestKind.FIREFLY,
			PestKind.fromTexture(PestKind.FIREFLY.textures().get(1)).orElseThrow(),
			"Firefly flash resolves to Firefly");
		assertFalse(PestKind.fromTexture(null).isPresent(), "null texture is unavailable");
		assertFalse(PestKind.fromTexture(" ").isPresent(), "blank texture is unavailable");
		assertFalse(PestKind.fromTexture("unknown-texture").isPresent(), "unknown texture is ignored");
		assertFalse(PestKind.fromTexture(PestKind.FLY.textures().getFirst() + "x").isPresent(),
			"partial texture matches are rejected");
	}

	private static void checkHeadProfiles() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		BuiltInRegistries.ITEM.listElements().forEach(holder -> {
			if (!holder.areComponentsBound()) holder.bindComponents(DataComponents.COMMON_ITEM_COMPONENTS);
		});
		assertEquals("", PestDetector.headTexture(new ItemStack(Items.STONE)),
			"non-player-head items are ignored");
		assertEquals("", PestDetector.headTexture(new ItemStack(Items.PLAYER_HEAD)),
			"player heads without a profile are unavailable");

		String texture = PestKind.FLY.textures().getFirst();
		PropertyMap properties = new PropertyMap(ImmutableMultimap.of("textures", new Property("textures", texture)));
		GameProfile profile = new GameProfile(UUID.randomUUID(), "pest", properties);
		ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
		stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile));
		assertEquals(texture, PestDetector.headTexture(stack), "profile texture is read from the head item");
	}

	private static void checkAnimation() {
		assertEquals(10.0, PestAnimation.verticalPosition(10, 20, 0, 1), "animation starts at feet");
		assertEquals(15.0, PestAnimation.verticalPosition(10, 20, 5, 1), "animation reaches midpoint");
		assertEquals(20.0, PestAnimation.verticalPosition(10, 20, 10, 1), "animation reaches head");
		assertEquals(15.0, PestAnimation.verticalPosition(10, 20, 15, 1), "animation returns through midpoint");
		assertEquals(10.0, PestAnimation.verticalPosition(10, 20, 20, 1), "animation returns to feet");
		assertEquals(15.0, PestAnimation.verticalPosition(20, 10, 5, 1), "reversed endpoints are normalized");
		double[] rings = PestAnimation.ringPositions(10, 20, 0, 1, 3);
		assertEquals(3, rings.length, "ring count is preserved");
		assertClose(10.0, rings[0], "first ring starts at the feet");
		assertClose(16.6666666667, rings[1], "second ring is phase shifted");
		assertClose(16.6666666667, rings[2], "third ring is phase shifted");
		PestAnimation.RingTarget target = PestAnimation.closestRingTarget(0, 0, 5, 12, 0, 1,
			new double[]{10, 20});
		assertClose(1.0, target.x(), "tracer lands on the camera-facing ring edge");
		assertClose(10.0, target.y(), "nearest ring is selected");
		assertClose(0.0, target.z(), "ring edge stays on the ring plane");
	}

	private static void checkDetectionCache() {
		PestDetectionCache cache = new PestDetectionCache();
		UUID entityId = UUID.randomUUID();
		ItemStack playerHead = new ItemStack(Items.PLAYER_HEAD);
		int[] detections = {0};

		assertSame(PestKind.FLY, cache.get(entityId, playerHead, () -> {
			detections[0]++;
			return Optional.of(PestKind.FLY);
		}).orElseThrow(), "a new head is detected");
		assertSame(PestKind.FLY, cache.get(entityId, playerHead.copy(), () -> {
			detections[0]++;
			return Optional.of(PestKind.FLY);
		}).orElseThrow(), "an unchanged head reuses its detection");
		assertEquals(1, detections[0], "unchanged pest heads are detected once");

		ItemStack changedHead = new ItemStack(Items.STONE);
		assertSame(PestKind.FLY, cache.get(entityId, changedHead, () -> {
			detections[0]++;
			return Optional.of(PestKind.FLY);
		}).orElseThrow(), "a changed head invalidates detection");
		assertEquals(2, detections[0], "head changes trigger a fresh detection");

		cache.retainAll(Set.of());
		cache.get(entityId, changedHead, () -> {
			detections[0]++;
			return Optional.empty();
		});
		assertEquals(3, detections[0], "removed entities are evicted from the detection cache");
	}

	private static void checkSettings() {
		PestHighlighterModule module = PestHighlighterModule.INSTANCE;
		assertSame(Category.FARMING, module.category(), "Pest Highlighter is in Farming");
		assertTrue(booleanSetting(module, "Box").value(), "box is enabled by default");
		assertFalse(booleanSetting(module, "Circle").value(), "circle is optional by default");
		assertFalse(booleanSetting(module, "Tracer").value(), "tracer is optional by default");
		assertTrue(booleanSetting(module, "Show Name").value(), "name is enabled by default");
		assertFalse(booleanSetting(module, "Depth Check").value(), "depth check is off by default");
		assertEquals(1, numberSetting(module, "Scan Interval").intValue(), "scan interval default");
		assertEquals("B62F00FF", colorSetting(module, "Outline Color").hex(), "outline default color");
		assertEquals("B62F003C", colorSetting(module, "Fill Color").hex(), "fill default color");
		assertClose(2.0, numberSetting(module, "Outline Width").value(), "outline width default");
		assertClose(0.6, numberSetting(module, "Circle Radius").value(), "circle radius default");
		assertClose(0.8, numberSetting(module, "Circle Speed").value(), "circle speed default");
		assertEquals(3, numberSetting(module, "Ring Count").intValue(), "ring count default");

		BooleanSetting box = booleanSetting(module, "Box");
		BooleanSetting circle = booleanSetting(module, "Circle");
		BooleanSetting tracer = booleanSetting(module, "Tracer");
		try {
			box.setValue(false);
			circle.setValue(true);
			tracer.setValue(true);
			assertFalse(box.value(), "box switch is independent");
			assertTrue(circle.value(), "circle switch is independent");
			assertTrue(tracer.value(), "tracer switch is independent");
		} finally {
			box.setValue(true);
			circle.setValue(false);
			tracer.setValue(false);
		}
	}

	private static BooleanSetting booleanSetting(PestHighlighterModule module, String name) {
		return module.booleanSettings().stream().filter(setting -> setting.name().equals(name)).findFirst().orElseThrow();
	}

	private static NumberSetting numberSetting(PestHighlighterModule module, String name) {
		return module.numberSettings().stream().filter(setting -> setting.name().equals(name)).findFirst().orElseThrow();
	}

	private static ColorSetting colorSetting(PestHighlighterModule module, String name) {
		return module.colorSettings().stream().filter(setting -> setting.name().equals(name)).findFirst().orElseThrow();
	}

	private static void assertTrue(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static void assertFalse(boolean condition, String message) {
		assertTrue(!condition, message);
	}

	private static void assertSame(Object expected, Object actual, String message) {
		if (expected != actual) throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
	}

	private static void assertEquals(Object expected, Object actual, String message) {
		if (!expected.equals(actual)) throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
	}

	private static void assertEquals(double expected, double actual, String message) {
		if (Double.compare(expected, actual) != 0) {
			throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
		}
	}

	private static void assertClose(double expected, double actual, String message) {
		if (Math.abs(expected - actual) > 0.0001) {
			throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
		}
	}
}
