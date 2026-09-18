package geiler.addons.client.macro;

import geiler.addons.client.location.Island;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.ModuleKeybind;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.module.TextSetting;
import geiler.addons.client.module.impl.MacrosModule;

import java.util.List;

/** Pure workflow checks that do not need a running client or server. */
public final class MacroChecks {
	private MacroChecks() {
	}

	public static void run() {
		MacroDefinition macro = new MacroDefinition(7);
		assertSame(MacroTriggerContext.ANY_NON_TEXT_SCREEN, macro.triggerContext(),
			"new macros are allowed in non-text contexts by default");
		MacroStep.Command delayed = new MacroStep.Command("/example");
		assertEquals(0, delayed.delayMin(), "new nodes have no hidden minimum delay");
		assertEquals(0, delayed.delayMax(), "new nodes have no hidden maximum delay");
		delayed.setDelay(250, 90_000);
		assertEquals(250, delayed.delayMin(), "node delay lower bound");
		assertEquals(60_000, delayed.delayMax(), "node delay upper bound");
		delayed.setDelay(-1, -1);
		assertEquals(0, delayed.delayMin(), "clearing a node delay is immediate");
		assertEquals(0, delayed.delayMax(), "clearing a node delay removes the range");

		MacroStep.Repeat repeat = new MacroStep.Repeat(true, 0);
		repeat.steps().add(new MacroStep.Wait(10, 20));
		macro.steps().add(new MacroStep.Command("/example"));
		macro.steps().add(repeat);
		assertTrue(repeat.forever(), "repeat supports an endless loop");
		assertEquals(1, repeat.count(), "finite repeat count clamps to one");
		assertEquals(2, macro.steps().size(), "workflow keeps ordered top-level steps");

		MacroStep.WorldSwitch switchStep = new MacroStep.WorldSwitch(Island.GARDEN);
		assertSame(Island.GARDEN, switchStep.target(), "world switch keeps its selected destination");
		switchStep.setTarget(Island.NONE);
		assertSame(Island.HUB, switchStep.target(), "world switch fails safe for unknown destinations");
		assertEquals("world_switch", switchStep.type(), "world switch has a stable persisted type");

		macro.setIslands(List.of(Island.GARDEN));
		macro.setIslandRestricted(true);
		assertTrue(macro.allowsIsland(Island.GARDEN), "selected island passes the filter");
		assertFalse(macro.allowsIsland(Island.HUB), "unselected island is blocked");
		macro.setIslandRestricted(false);
		assertTrue(macro.allowsIsland(Island.HUB), "disabled island filter allows every island");
		macro.setIslands(List.of(Island.GARDEN, Island.OTHER, Island.NONE));
		assertEquals(1, macro.islands().size(), "non-selectable islands are ignored");
		macro.setName("Shared routine");
		macro.setTriggerContext(MacroTriggerContext.CONTAINER_ONLY);
		macro.steps().getFirst().setDelay(125, 350);
		String packageText = MacroTransfer.encode(List.of(macro));
		MacroTransfer.ImportResult imported = MacroTransfer.decode(packageText, 100);
		assertTrue(imported.success(), "macro package can be decoded");
		assertEquals(1, imported.macros().size(), "macro package keeps its selection count");
		MacroDefinition copy = imported.macros().getFirst();
		assertEquals(100, copy.id(), "import assigns a fresh macro id");
		assertEquals("Shared routine", copy.name(), "import keeps the macro name");
		assertSame(MacroTriggerContext.CONTAINER_ONLY, copy.triggerContext(), "import keeps trigger context");
		assertEquals(125, copy.steps().getFirst().delayMin(), "import keeps node delay minimum");
		assertEquals(350, copy.steps().getFirst().delayMax(), "import keeps node delay maximum");
		assertFalse(MacroTransfer.decode("not a macro package", 0).success(),
			"invalid clipboard data is rejected");

		MacroDefinition selectable = new MacroDefinition(8);
		selectable.setIslands(List.of(Island.GARDEN));
		MacrosModule.INSTANCE.restore(List.of(selectable));
		SettingGroup macroGroup = MacrosModule.INSTANCE.groups().get(1);
		TextSetting rename = null;
		for (Setting setting : macroGroup.settings()) {
			if (setting instanceof TextSetting text && "Rename Macro".equals(text.displayName())) rename = text;
		}
		assertTrue(rename != null, "macro exposes an explicit rename field");
		rename.setValue("Garden Routine");
		MacrosModule.INSTANCE.syncSettings();
		assertEquals("Garden Routine", selectable.name(), "renaming updates the macro");
		assertEquals(1, macroGroup.children().size(), "macro has a nested island group");
		SettingGroup islands = macroGroup.children().getFirst();
		long selectableCount = java.util.Arrays.stream(Island.values()).filter(Island::selectable).count();
		assertEquals((int) selectableCount, islands.settings().size(), "island group exposes every selectable island");
		BooleanSetting garden = null;
		for (Setting setting : islands.settings()) {
			if (setting instanceof BooleanSetting toggle && toggle.name().equals(Island.GARDEN.label())) garden = toggle;
		}
		assertTrue(garden != null && garden.value(), "saved island is checked in the island group");
		garden.setValue(false);
		MacrosModule.INSTANCE.syncSettings();
		assertFalse(selectable.islands().contains(Island.GARDEN), "unchecking an island updates the macro");

		assertEquals("None", ModuleKeybind.NONE.displayName(), "macro keybind uses the shared display format");
	}

	private static void assertSame(Object expected, Object actual, String label) {
		if (expected != actual) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static void assertFalse(boolean value, String label) {
		if (value) throw new AssertionError(label);
	}
}
