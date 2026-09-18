package geiler.addons.client.macro;

import com.mojang.blaze3d.platform.InputConstants;
import geiler.addons.client.config.GeilerAddonsLog;
import geiler.addons.client.location.HypixelModApi;
import geiler.addons.client.location.Island;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ModuleKeybind;
import geiler.addons.client.module.ModuleManager;
import geiler.addons.client.module.impl.MacrosModule;
import geiler.addons.client.gui.ClickGuiScreen;
import geiler.addons.client.gui.MacroEditorScreen;
import geiler.addons.client.mixin.AbstractContainerScreenInvoker;
import geiler.addons.client.tree.ChatText;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

/** Client-thread workflow executor for the Macros module.
 *
 * <p>Every action goes through the same screen/key APIs that a real user action reaches. In
 * particular, container clicks are dispatched through {@link AbstractContainerScreen#mouseClicked}
 * and therefore keep vanilla's slot-id and server synchronisation rules intact.</p>
 */
public final class MacroRunner {
	private static final long FAILURE_TIMEOUT_NANOS = 5_000_000_000L;
	private static final int MAX_STRUCTURAL_STEPS = 32;
	private static final int MAX_CHAT_LENGTH = 256;
	private static final SplittableRandom RANDOM = new SplittableRandom();
	private static String lastChat = "";
	private static long chatSequence;
	private static long lastChatSequence;
	private static Level lastLevel;
	private static Run active;

	private MacroRunner() {
	}

	public static boolean isRunning() {
		return active != null;
	}

	public static MacroDefinition activeMacro() {
		return active == null ? null : active.macro;
	}

	/** Invoked on the client thread before Minecraft forwards a raw key event. */
	public static boolean handleKeyEvent(Minecraft minecraft, int action, KeyEvent event) {
		if (action != InputConstants.PRESS || minecraft == null || minecraft.level == null
			|| minecraft.player == null || !MacrosModule.INSTANCE.isActive()) return false;
		if (isTextOrEditorScreen(minecraft.screen)) return false;

		if (active != null && active.macro.keybind().matches(event)) {
			cancel("cancelled by its hotkey");
			return true;
		}

		List<MacroDefinition> matches = new ArrayList<>();
		for (MacroDefinition macro : MacrosModule.INSTANCE.macros()) {
			if (!macro.enabled() || !macro.keybind().matches(event)) continue;
			if (!contextAllowed(macro.triggerContext(), minecraft.screen)) continue;
			if (!macro.allowsIsland(HypixelModApi.currentIsland())) continue;
			matches.add(macro);
		}
		if (matches.isEmpty()) return false;
		if (matches.size() > 1) {
			message(minecraft, "Macro hotkey is assigned to more than one macro; nothing started.");
			return true;
		}
		for (geiler.addons.client.module.Module module : ModuleManager.modules()) {
			if (module.keybind().matches(event)) {
				message(minecraft, "Macro hotkey conflicts with module '" + module.name() + "'; nothing started.");
				return true;
			}
		}

		if (active != null) cancel("replaced by " + matches.getFirst().name());
		start(matches.getFirst());
		return true;
	}

	public static void tick() {
		Run run = active;
		if (run == null) return;
		Minecraft minecraft = Minecraft.getInstance();
		if (lastLevel != minecraft.level) {
			if (lastLevel != null) run.markLevelChanged();
			lastLevel = minecraft.level;
			lastChat = "";
			lastChatSequence = chatSequence;
		}
		if (!MacrosModule.INSTANCE.isActive() || minecraft.level == null
			|| (minecraft.player == null && !run.waitingForWorldSwitch())) {
			cancel("context ended");
			return;
		}
		// Trigger context is checked when the hotkey starts. A running workflow is allowed to open
		// or close a container; cancelling merely because the screen changed would make legitimate
		// "click, wait, close" workflows impossible.
		Island currentIsland = HypixelModApi.currentIsland();
		if (!run.macro.allowsIsland(currentIsland)
			&& !(run.waitingForWorldSwitch() && currentIsland == Island.NONE)) {
			cancel("context no longer allowed");
			return;
		}
		run.tick(minecraft);
	}

	public static void onChatMessage(String content) {
		if (content == null) return;
		String normalized = ChatText.stripForMatch(content);
		lastChatSequence = ++chatSequence;
		lastChat = normalized.length() > MAX_CHAT_LENGTH
			? normalized.substring(normalized.length() - MAX_CHAT_LENGTH) : normalized;
	}

	public static void cancel(String reason) {
		Run run = active;
		if (run == null) return;
		run.releaseHeldKey();
		active = null;
		log(run.macro, "STOP " + reason);
	}

	private static void start(MacroDefinition macro) {
		if (macro.steps().isEmpty()) {
			message(Minecraft.getInstance(), "Macro '" + macro.name() + "' has no steps.");
			return;
		}
		active = new Run(macro);
		log(macro, "START");
	}

	private static boolean contextAllowed(MacroTriggerContext context, Screen screen) {
		if (isTextOrEditorScreen(screen)) return false;
		boolean world = screen == null;
		boolean container = screen instanceof AbstractContainerScreen<?>;
		return switch (context) {
			case WORLD_ONLY -> world;
			case CONTAINER_ONLY -> container;
			case WORLD_AND_CONTAINER -> world || container;
			case ANY_NON_TEXT_SCREEN -> true;
		};
	}

	private static boolean isTextOrEditorScreen(Screen screen) {
		return screen instanceof ChatScreen || screen instanceof ClickGuiScreen || screen instanceof MacroEditorScreen;
	}

	private static void message(Minecraft minecraft, String text) {
		if (minecraft != null && minecraft.gui != null) {
			minecraft.gui.getChat().addClientSystemMessage(Component.literal("[Macros] " + text));
		}
	}

	private static void log(MacroDefinition macro, String text) {
		long tick = 0;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level != null) tick = minecraft.level.getGameTime();
		// Keep chat/command payloads useful for diagnosis without allowing a pasted token or an
		// accidentally huge hand-edited message to turn a per-macro log into a data dump.
		if (text != null && text.length() > 192) text = text.substring(0, 189) + "…";
		GeilerAddonsLog.write(Category.MISCELLANEOUS, "Macros-" + macro.id(), tick,
			macro.name() + ": " + text);
	}

	private static InputConstants.Key parseKey(String value) {
		if (value == null || value.isBlank()) return InputConstants.UNKNOWN;
		try {
			InputConstants.Key key = InputConstants.getKey(value.trim());
			if (key == null || key.equals(InputConstants.UNKNOWN)) {
				String shorthand = value.trim().toLowerCase(Locale.ROOT);
				if (shorthand.length() == 1 || shorthand.indexOf('.') < 0) {
					key = InputConstants.getKey("key.keyboard." + shorthand);
				}
			}
			return key == null ? InputConstants.UNKNOWN : key;
		} catch (RuntimeException ignored) {
			return InputConstants.UNKNOWN;
		}
	}

	private static boolean itemMatches(ItemStack stack, String wanted, boolean contains) {
		if (stack == null || stack.isEmpty()) return false;
		String actual = ChatText.stripForMatch(stack.getHoverName().getString()).toLowerCase(Locale.ROOT);
		String needle = ChatText.stripForMatch(wanted == null ? "" : wanted).toLowerCase(Locale.ROOT);
		if (needle.isEmpty()) return false;
		return contains ? actual.contains(needle) : actual.equals(needle);
	}

	private static boolean condition(MacroCondition condition, Minecraft minecraft, long minimumChatSequence) {
		if (condition == null) return true;
		if (condition instanceof MacroCondition.Always always) return always.expected();
		if (condition instanceof MacroCondition.World world) {
			return (minecraft.level != null && minecraft.player != null) == world.mustBeInWorld();
		}
		if (condition instanceof MacroCondition.Screen screen) {
			boolean open = minecraft.screen != null;
			if (open != screen.mustBeOpen()) return false;
			if (!open || screen.title() == null || screen.title().isBlank()) return true;
			String title = ChatText.stripForMatch(minecraft.screen.getTitle().getString()).toLowerCase(Locale.ROOT);
			String wanted = ChatText.stripForMatch(screen.title()).toLowerCase(Locale.ROOT);
			return screen.contains() ? title.contains(wanted) : title.equals(wanted);
		}
		if (condition instanceof MacroCondition.Slot slot) {
			boolean exists = findSlot(minecraft.screen, slot.slotId()) != null;
			return exists == slot.mustExist();
		}
		if (condition instanceof MacroCondition.Item item) {
			boolean found = findItem(minecraft, item.name(), item.contains(),
				item.includePlayerInventory(), "all", 0) != null;
			return found == item.mustExist();
		}
		if (condition instanceof MacroCondition.Chat chat) {
			if (lastChatSequence <= minimumChatSequence) return false;
			String wanted = ChatText.stripForMatch(chat.text()).toLowerCase(Locale.ROOT);
			String actual = lastChat.toLowerCase(Locale.ROOT);
			return chat.contains() ? actual.contains(wanted) : actual.equals(wanted);
		}
		if (condition instanceof MacroCondition.All all) {
			for (MacroCondition child : all.children()) if (!condition(child, minecraft, minimumChatSequence)) return false;
			return true;
		}
		if (condition instanceof MacroCondition.Any any) {
			for (MacroCondition child : any.children()) if (condition(child, minecraft, minimumChatSequence)) return true;
			return false;
		}
		if (condition instanceof MacroCondition.Not not) return !condition(not.child(), minecraft, minimumChatSequence);
		return false;
	}

	private static Slot findSlot(Screen screen, int slotId) {
		if (!(screen instanceof AbstractContainerScreen<?> container)) return null;
		for (Slot slot : container.getMenu().slots) if (slot.index == slotId) return slot;
		return null;
	}

	private static Slot findItem(Minecraft minecraft, String name, boolean contains, boolean includePlayer,
		String scope, int occurrence) {
		if (!(minecraft.screen instanceof AbstractContainerScreen<?> container)) return null;
		Inventory playerInventory = minecraft.player == null ? null : minecraft.player.getInventory();
		int found = 0;
		for (Slot slot : container.getMenu().slots) {
			boolean playerSlot = playerInventory != null && slot.container == playerInventory;
			boolean allowed = switch (scope == null ? "all" : scope.toLowerCase(Locale.ROOT)) {
				case "player" -> playerSlot;
				case "container" -> !playerSlot;
				default -> includePlayer || !playerSlot;
			};
			if (!allowed || !itemMatches(slot.getItem(), name, contains)) continue;
			if (found++ == Math.max(0, occurrence)) return slot;
		}
		return null;
	}

	private static int delay(int min, int max) {
		min = Math.max(0, min);
		max = Math.max(min, max);
		return min == max ? min : RANDOM.nextInt(min, max + 1);
	}

	private static final class Run {
		private final MacroDefinition macro;
		private final Deque<Frame> frames = new ArrayDeque<>();
		private final long chatStartSequence;
		private long nextActionNanos;
		private long blockedSinceNanos;
		private String blockedReason;
		private HeldKey heldKey;
		/** The node whose explicit pre-node delay is currently being waited out. */
		private MacroStep pendingDelayStep;
		private Frame worldSwitchFrame;
		private Island worldSwitchTarget;
		private boolean levelChanged;

		private Run(MacroDefinition macro) {
			this.macro = macro;
			this.chatStartSequence = chatSequence;
			frames.push(new Frame(macro.steps(), 0, 1, false));
			nextActionNanos = System.nanoTime();
		}

		private void markLevelChanged() {
			levelChanged = true;
		}

		private boolean waitingForWorldSwitch() {
			return worldSwitchFrame != null || levelChanged;
		}

		private void tick(Minecraft minecraft) {
			if (levelChanged) {
				if (worldSwitchFrame == null) {
					abort("world changed without a World Switch node");
					return;
				}
				Island current = HypixelModApi.currentIsland();
				if (current == Island.NONE) {
					blocked(minecraft, "waiting for destination " + worldSwitchTarget.label());
					return;
				}
				if (current != worldSwitchTarget) {
					abort("world changed to " + current.label() + ", expected " + worldSwitchTarget.label());
					return;
				}
				MacroStep step = worldSwitchFrame.steps.get(worldSwitchFrame.index);
				worldSwitchFrame.index++;
				worldSwitchFrame = null;
				worldSwitchTarget = null;
				levelChanged = false;
				clearBlocked();
				pendingDelayStep = null;
				nextActionNanos = System.nanoTime();
				return;
			}
			long now = System.nanoTime();
			if (heldKey != null) {
				if (now < heldKey.releaseAtNanos) return;
				releaseHeldKey();
				nextActionNanos = now;
				return;
			}
			if (now < nextActionNanos) return;

			int structural = 0;
			while (structural++ < MAX_STRUCTURAL_STEPS) {
				Cursor cursor = nextCursor();
				if (cursor == null) {
					active = null;
					log(macro, "COMPLETE");
					return;
				}
				MacroStep step = cursor.step();
				if (step instanceof MacroStep.WaitUntil waitUntil
					&& !condition(waitUntil.condition(), minecraft, chatStartSequence)) {
					if (!blocked(minecraft, "waiting for condition")) return;
					return;
				}
				if (step instanceof MacroStep.WorldSwitch worldSwitch && worldSwitchFrame == null) {
					if (!delayReady(step, now)) return;
					worldSwitchFrame = cursor.frame();
					worldSwitchTarget = worldSwitch.target();
				}
				if (!(step instanceof MacroStep.WorldSwitch) && !delayReady(step, now)) return;
				if (step instanceof MacroStep.IfElse branch) {
					cursor.frame().index++;
					List<MacroStep> selected = condition(branch.condition(), minecraft, chatStartSequence)
						? branch.thenSteps() : branch.elseSteps();
					if (!selected.isEmpty()) frames.push(new Frame(selected, 0, 1, false));
					continue;
				}
				if (step instanceof MacroStep.Repeat repeat) {
					cursor.frame().index++;
					if (!repeat.steps().isEmpty()) {
						frames.push(new Frame(repeat.steps(), 0, repeat.forever() ? -1 : repeat.count(), true));
					}
					continue;
				}
				if (step instanceof MacroStep.WaitUntil waitUntil) {
					cursor.frame().index++;
					clearBlocked();
					return;
				}
				if (step instanceof MacroStep.WorldSwitch worldSwitch) {
					blocked(minecraft, "waiting for world switch to " + worldSwitchTarget.label());
					return;
				}

				if (!execute(step, minecraft, now)) {
					blocked(minecraft, "step " + step.type());
					return;
				}
				cursor.frame().index++;
				clearBlocked();
				return;
			}
			abort("workflow contains too many empty structural steps");
		}

		private Cursor nextCursor() {
			while (!frames.isEmpty()) {
				Frame frame = frames.peek();
				if (frame.index < frame.steps.size()) return new Cursor(frame, frame.steps.get(frame.index));
				if (frame.repeatFrame && (frame.repeatsRemaining < 0 || frame.repeatsRemaining > 1)) {
					if (frame.repeatsRemaining > 1) frame.repeatsRemaining--;
					frame.index = 0;
					continue;
				}
				frames.pop();
			}
			return null;
		}

		private boolean execute(MacroStep step, Minecraft minecraft, long now) {
			if (step instanceof MacroStep.Command command) {
				if (minecraft.player == null || command.command().isBlank()) return false;
				String value = command.command().strip();
				minecraft.player.connection.sendCommand(value.startsWith("/") ? value.substring(1) : value);
				log(macro, "COMMAND " + value);
				return true;
			}
			if (step instanceof MacroStep.Chat chat) {
				if (minecraft.player == null || chat.message().isBlank()) return false;
				minecraft.player.connection.sendChat(chat.message());
				log(macro, "CHAT " + chat.message());
				return true;
			}
			if (step instanceof MacroStep.Wait wait) {
				nextActionNanos = now + delay(wait.minMillis(), wait.maxMillis()) * 1_000_000L;
				return true;
			}
			if (step instanceof MacroStep.Key key) return pressKey(key, minecraft, now);
			if (step instanceof MacroStep.ClickSlot click) return clickSlot(click, minecraft);
			if (step instanceof MacroStep.ClickItem click) return clickItem(click, minecraft);
			if (step instanceof MacroStep.CloseScreen) {
				if (minecraft.screen == null) return true;
				Screen old = minecraft.screen;
				if (!old.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0))) minecraft.setScreen(null);
				return true;
			}
			return false;
		}

		private boolean pressKey(MacroStep.Key step, Minecraft minecraft, long now) {
			InputConstants.Key key = parseKey(step.key());
			if (key.equals(InputConstants.UNKNOWN)) return false;
			KeyEvent event = new KeyEvent(key.getValue(), 0, 0);
			Screen screen = minecraft.screen;
			if (screen != null) {
				if (!screen.keyPressed(event)) return false;
				if (step.hold()) {
					heldKey = new HeldKey(screen, key, now + step.holdMillis() * 1_000_000L);
				} else {
					((GuiEventListener) screen).keyReleased(event);
				}
			} else {
				KeyMapping.click(key);
				if (step.hold()) {
					heldKey = new HeldKey(null, key, now + step.holdMillis() * 1_000_000L);
				} else {
					KeyMapping.set(key, false);
				}
			}
			return true;
		}

		private boolean clickSlot(MacroStep.ClickSlot step, Minecraft minecraft) {
			if (!(minecraft.screen instanceof AbstractContainerScreen<?> screen)) return false;
			Slot slot = findSlot(screen, step.slotId());
			if (slot == null) return false;
			return dispatchClick(screen, slot, step.button(), step.shift());
		}

		private boolean clickItem(MacroStep.ClickItem step, Minecraft minecraft) {
			if (!(minecraft.screen instanceof AbstractContainerScreen<?> screen)) return false;
			Slot slot = findItem(minecraft, step.name(), step.contains(), true, step.scope(), step.occurrence());
			return slot != null && dispatchClick(screen, slot, step.button(), step.shift());
		}

		private boolean dispatchClick(AbstractContainerScreen<?> screen, Slot slot, int button, boolean shift) {
			int left = ((AbstractContainerScreenInvoker) (Object) screen).geileraddons$getLeftPos();
			int top = ((AbstractContainerScreenInvoker) (Object) screen).geileraddons$getTopPos();
			int modifiers = shift ? InputConstants.MOD_SHIFT : 0;
			MouseButtonEvent event = new MouseButtonEvent(left + slot.x + 8, top + slot.y + 8,
				new MouseButtonInfo(button, modifiers));
			boolean accepted = screen.mouseClicked(event, false);
			screen.mouseReleased(event);
			return accepted;
		}

		private boolean blocked(Minecraft minecraft, String reason) {
			long now = System.nanoTime();
			if (!reason.equals(blockedReason)) {
				blockedReason = reason;
				blockedSinceNanos = now;
			}
			if (now - blockedSinceNanos < FAILURE_TIMEOUT_NANOS) return false;
			message(minecraft, "Macro '" + macro.name() + "' stopped after 5 seconds: " + reason + ".");
			abort(reason);
			return true;
		}

		private void clearBlocked() {
			blockedReason = null;
			blockedSinceNanos = 0;
		}

		private boolean delayReady(MacroStep step, long now) {
			if (pendingDelayStep != step) {
				pendingDelayStep = step;
				nextActionNanos = now + delay(step.delayMin(), step.delayMax()) * 1_000_000L;
				return false;
			}
			if (now < nextActionNanos) return false;
			pendingDelayStep = null;
			return true;
		}

		private void releaseHeldKey() {
			if (heldKey == null) return;
			KeyEvent event = new KeyEvent(heldKey.key.getValue(), 0, 0);
			if (heldKey.screen != null) ((GuiEventListener) heldKey.screen).keyReleased(event);
			else KeyMapping.set(heldKey.key, false);
			heldKey = null;
		}

		private void abort(String reason) {
			releaseHeldKey();
			if (active == this) active = null;
			log(macro, "ABORT " + reason);
		}
	}

	private static final class Frame {
		private final List<MacroStep> steps;
		private int index;
		private int repeatsRemaining;
		private final boolean repeatFrame;

		private Frame(List<MacroStep> steps, int index, int repeatsRemaining, boolean repeatFrame) {
			this.steps = steps;
			this.index = index;
			this.repeatsRemaining = repeatsRemaining;
			this.repeatFrame = repeatFrame;
		}
	}

	private record Cursor(Frame frame, MacroStep step) {
	}

	private static final class HeldKey {
		private final Screen screen;
		private final InputConstants.Key key;
		private final long releaseAtNanos;

		private HeldKey(Screen screen, InputConstants.Key key, long releaseAtNanos) {
			this.screen = screen;
			this.key = key;
			this.releaseAtNanos = releaseAtNanos;
		}
	}
}
