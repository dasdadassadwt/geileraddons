package geiler.addons.client.module.impl;

import geiler.addons.client.enchanting.*;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerListener;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Regression traces for the upstream event contracts and the actual vanilla listener boundary. */
final class SequenceSolverChecks {
	static void run() {
		checkRenderDoesNotLearn();
		checkUltraRounds();
		checkVanillaListenerBridge();
	}

	private static void checkRenderDoesNotLearn() {
		ExperimentSolverEngine engine = new ExperimentSolverEngine();
		String title = "Chronomatron (High)";
		List<ExperimentCell> red = List.of(ExperimentCell.token(17, "red", true));
		for (int i = 0; i < 20; i++) engine.observe(new ExperimentSnapshot(title, "Remember the pattern!", red));
		require(engine.view().sequence().isEmpty(), "render frames cannot invent reveal events");
		engine.observe(new ExperimentSnapshot(title, "Timer: 3s", red), ChronomatronEvent.board(17, "red", true));
		require(engine.view().phase() == ExperimentPhase.WAITING, "board events cannot synthesize timer events");
		engine.observe(new ExperimentSnapshot(title, "Timer: 3s", red), ChronomatronEvent.status("Timer: 3s"));
		require(engine.view().phase() == ExperimentPhase.SOLVE, "real timer opens solve");
		engine.confirmClick(17);
		for (int i = 0; i < 20; i++) engine.observe(new ExperimentSnapshot(title, "Remember the pattern!", red));
		require(engine.view().phase() == ExperimentPhase.ROUND_COMPLETE, "stale render cannot reopen click one");
		require(engine.view().visualIndex() == 1, "render cannot rewind completed cursor");
	}

	private static void checkUltraRounds() {
		UltrasequencerModel model = new UltrasequencerModel();
		model.markDirty(List.of("gray"));
		for (int round = 1; round <= 8; round++) {
			List<ExperimentCell> cells = new ArrayList<>();
			for (int i = 1; i <= round; i++) cells.add(ExperimentCell.number(9 + i, i));
			model.observe("Remember the pattern!", cells, null);
			model.observe("Remember the pattern!", List.of(ExperimentCell.number(44, 99)), null);
			require(model.sequence().size() == round, "WAIT freezes tick-captured number map");
			model.observe("Timer: 3s", List.of(), null);
			for (int i = 1; i <= round; i++) {
				require(model.sequence().get(i - 1).slotIds().equals(List.of(9 + i)), "number order retained");
				require(model.currentIndex() == i - 1, "cursor follows the remembered click order");
				require(model.click(9 + i), "expected Ultra click accepted");
				model.observe("Timer: 2s", List.of(), null);
			}
			require(model.currentIndex() == round - 1,
				"last selection waits for the server colour edge, as upstream");
			if (round < 8) {
				model.markDirty(List.of(round % 2 == 0 ? "blue" : "orange"));
				require(model.state() == UltrasequencerModel.State.END,
					"a pane boundary after the final click ends the round");
				model.observe("Remember the pattern!", List.of(), null);
				require(model.state() == UltrasequencerModel.State.REMEMBER,
					"tick starts the next round without a status callback");
				require(model.completedRounds() == round, "each real boundary counts exactly one round");
			}
		}
		require(model.completedRounds() == 7, "all eight Ultra rounds retained");

		UltrasequencerModel interrupted = new UltrasequencerModel();
		interrupted.markDirty(List.of("gray"));
		interrupted.observe("Remember the pattern!", List.of(ExperimentCell.number(30, 1),
			ExperimentCell.number(31, 2), ExperimentCell.number(32, 3)), null);
		interrupted.observe("Timer: 3s", List.of(), null);
		require(interrupted.click(30), "first click of the round is accepted");
		interrupted.markDirty(List.of("blue"));
		interrupted.markDirty(List.of("gray"));
		require(interrupted.state() == UltrasequencerModel.State.SHOW,
			"pressed and released button panes cannot end an unfinished round");
		require(interrupted.currentIndex() == 1, "the same panes cannot rewind or freeze the cursor");
		require(interrupted.click(31) && interrupted.click(32),
			"the rest of the round stays clickable after the pane churn");
		interrupted.observe("Remember the pattern!", List.of(ExperimentCell.number(30, 1),
			ExperimentCell.number(31, 2), ExperimentCell.number(32, 3),
			ExperimentCell.number(33, 4)), null);
		require(interrupted.sequence().size() == 4,
			"the next memory notice resets and captures the longer round");
		require(interrupted.completedRounds() == 1,
			"an interrupted solve counts its round exactly once");

		UltrasequencerModel counted = new UltrasequencerModel();
		counted.markDirty(List.of("white"));
		counted.observe("Remember the pattern!", List.of(ExperimentCell.number(30, 1)), null);
		counted.observe("Timer: 3s", List.of(), null);
		require(counted.click(30), "single-click round accepted before its boundary");
		counted.markDirty(List.of("red"));
		require(counted.state() == UltrasequencerModel.State.END,
			"a completed round still ends through its pane boundary");
		counted.observe("Remember the pattern!", List.of(ExperimentCell.number(30, 1),
			ExperimentCell.number(31, 2)), null);
		require(counted.completedRounds() == 1,
			"a round that already ended through a pane is not counted twice");

		model.reset();
		model.observe("Remember the pattern!", List.of(
			new ExperimentCell(20, "1", 4, true, false, false),
			new ExperimentCell(21, "9", 5, true, false, false)), null);
		model.observe("Timer: 3s", List.of(), null);
		require(model.click(20) && model.currentIndex() == 1, "Ultra uses saved stack count for successor");
	}

	private static void checkVanillaListenerBridge() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		// 26.1 binds item defaults while loading data packs. This headless fixture needs only
		// the common components; names and glints used below are explicit per-stack overrides.
		net.minecraft.core.registries.BuiltInRegistries.ITEM.listElements().forEach(holder -> {
			if (!holder.areComponentsBound()) holder.bindComponents(DataComponents.COMMON_ITEM_COMPONENTS);
		});
		TestMenu menu = new TestMenu();
		ChronomatronModel model = new ChronomatronModel();
		int[] events = {0};
		menu.addSlotListener(new ContainerListener() {
			@Override public void slotChanged(AbstractContainerMenu source, int slot, ItemStack stack) {
				events[0]++;
				if (slot == 49) model.observe(ChronomatronEvent.status(stack.getHoverName().getString()));
				else if (slot >= 17 && slot <= 25) model.observe(ChronomatronEvent.board(slot,
					stack.is(Items.RED_TERRACOTTA) || stack.is(Items.RED_STAINED_GLASS)
						? "red" : "blue", stack.hasFoil()));
			}
			@Override public void dataChanged(AbstractContainerMenu source, int property, int value) { }
		});
		for (int round = 1; round <= 6; round++) {
			set(menu, 49, status("Remember the pattern!"));
			if (round > 1) set(menu, 17 + (round - 2) % 2, tile((round - 2) % 2 == 0, false));
			for (int i = 0; i < round; i++) {
				set(menu, 17 + i % 2, tile(i % 2 == 0, true));
				if (i + 1 < round) set(menu, 17 + i % 2, tile(i % 2 == 0, false));
			}
			for (int i = 0; i < Math.min(round, 2); i++) set(menu, 17 + i, glass(i == 0, false));
			set(menu, 49, status("Timer: 3s"));
			require(model.items().size() == round, "real listener captures exactly one new item each round");
			for (int i = 0; i < round; i++) {
				require(model.click(i % 2 == 0 ? "red" : "blue"), "real listener trace retains click order");
				// Player click glints are delivered too, but may not grow memory or rewind progress.
				set(menu, 17 + i % 2, glass(i % 2 == 0, true));
				set(menu, 17 + i % 2, glass(i % 2 == 0, false));
			}
			require(model.state() == ChronomatronModel.State.END, "final click remains ended");
		}
		int before = events[0];
		menu.broadcastChanges();
		ExperimentMenuUpdates.afterServerUpdate(menu, () -> {});
		require(events[0] == before, "two mods broadcasting cannot duplicate unchanged slot events");
		List<ItemStack> full = new ArrayList<>();
		for (int i = 0; i < 54; i++) full.add(ItemStack.EMPTY);
		full.set(49, status("Remember the pattern!"));
		menu.initializeContents(100, full, ItemStack.EMPTY);
		ExperimentMenuUpdates.afterServerUpdate(menu, () -> {});
		require(model.state() == ChronomatronModel.State.REMEMBER, "full-content update reaches listener");
	}
	private static void set(TestMenu menu, int slot, ItemStack stack) {
		menu.setItem(slot, menu.getStateId() + 1, stack);
		ExperimentMenuUpdates.afterServerUpdate(menu, () -> {});
	}
	private static ItemStack tile(boolean red, boolean glint) {
		ItemStack stack = new ItemStack(red ? Items.RED_TERRACOTTA : Items.BLUE_TERRACOTTA);
		stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, glint);
		return stack;
	}
	private static ItemStack status(String text) {
		ItemStack stack = new ItemStack(Items.CLOCK);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(text));
		return stack;
	}
	private static ItemStack glass(boolean red, boolean glint) {
		ItemStack stack = new ItemStack(red ? Items.RED_STAINED_GLASS : Items.BLUE_STAINED_GLASS);
		stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, glint);
		return stack;
	}
	private static final class TestMenu extends AbstractContainerMenu {
		TestMenu() {
			super(null, 1);
			SimpleContainer inventory = new SimpleContainer(54);
			for (int i = 0; i < 54; i++) addSlot(new Slot(inventory, i, 0, 0));
		}
		@Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
		@Override public boolean stillValid(Player player) { return true; }
	}
	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
