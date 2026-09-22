package geiler.addons.client.macro;

import com.mojang.blaze3d.platform.InputConstants;
import geiler.addons.client.config.GeilerAddonsLog;
import geiler.addons.client.hud.MacroTitleOverlay;
import geiler.addons.client.location.HypixelModApi;
import geiler.addons.client.location.Island;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ModuleKeybind;
import geiler.addons.client.module.ModuleManager;
import geiler.addons.client.module.impl.MacrosModule;
import geiler.addons.client.module.impl.InventoryButtonRules;
import geiler.addons.client.module.impl.InventoryButtonPlacement;
import geiler.addons.client.gui.ClickGuiScreen;
import geiler.addons.client.gui.MacroEditorScreen;
import geiler.addons.client.gui.ScratchMacroEditorScreen;
import geiler.addons.client.gui.InventoryButtonPickerScreen;
import geiler.addons.client.gui.InventoryButtonTextScreen;
import geiler.addons.client.mixin.AbstractContainerScreenInvoker;
import geiler.addons.client.tree.ChatText;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.world.phys.Vec3;
import geiler.addons.client.render.EspRenderer;
import geiler.addons.client.render.GeilerAddonsRenderTypes;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
	private static final List<Run> activeRuns = new ArrayList<>();
	private static final Set<Run> inputBlockOwners = new HashSet<>();
	private static final Map<Integer, Set<Run>> heldKeyOwners = new HashMap<>();
	private static final Map<String, WorldTriggerState> worldTriggerStates = new HashMap<>();
	private static final Map<String, Long> finishedScripts = new HashMap<>();
	private static String triggerWorldKey = "";
	private static Level triggerLevel;
	private static long worldInstanceSequence;
	private static boolean applyingSyntheticInput;
	private static final int MAX_ACTIVE_RUNS = 64;
	private static final int MAX_CALL_DEPTH = 16;

	private MacroRunner() {
	}

	public static boolean isRunning() {
		return !activeRuns.isEmpty();
	}

	/** Supplies held macro keys to vanilla input polling while a workflow owns the hold. */
	public static boolean isSyntheticKeyDown(int keyCode) {
		long now = System.nanoTime();
		for (Run run : activeRuns) if (run.isSyntheticKeyDown(keyCode, now)) return true;
		return false;
	}

	public static MacroDefinition activeMacro() {
		return activeRuns.isEmpty() ? null : activeRuns.getFirst().macro;
	}

	public static boolean isPlayerInputBlocked() { return !inputBlockOwners.isEmpty(); }
	public static boolean isApplyingSyntheticInput() { return applyingSyntheticInput; }
	public static boolean isScriptRunning(String scriptId) {
		if (scriptId == null) return false;
		for (Run run : activeRuns) if (run.isExecutingScript(scriptId)) return true;
		return false;
	}
	public enum RunState { IDLE, RUNNING, WAITING, FINISHED }
	public static RunState scriptState(String scriptId) {
		if (scriptId == null) return RunState.IDLE;
		for (Run run : activeRuns) if (run.isExecutingScript(scriptId)) return run.isWaiting() ? RunState.WAITING : RunState.RUNNING;
		Long finished = finishedScripts.get(scriptId);
		return finished != null && System.nanoTime() - finished < 1_500_000_000L ? RunState.FINISHED : RunState.IDLE;
	}
	public static MacroStep activeStep(String scriptId) {
		if (scriptId == null) return null;
		for (Run run : activeRuns) if (run.isExecutingScript(scriptId)) return run.activeStepFor(scriptId);
		return null;
	}

	/** Invoked on the client thread before Minecraft forwards a raw key event. */
	public static boolean handleKeyEvent(Minecraft minecraft, int action, KeyEvent event) {
		if (action != InputConstants.PRESS || minecraft == null || minecraft.level == null
			|| minecraft.player == null || !MacrosModule.INSTANCE.isActive()) return false;
		if (isTextOrEditorScreen(minecraft.screen)) return false;

		List<MacroScriptOwner> matches = new ArrayList<>();
		for (MacroDefinition macro : MacrosModule.INSTANCE.macros()) {
			if (!macro.enabled()) continue;
			if (!contextAllowed(macro.triggerContext(), minecraft.screen)) continue;
			if (!macro.allowsIsland(HypixelModApi.currentIsland())) continue;
			for (MacroScript script : macro.scripts()) {
				if (script.trigger() == MacroScript.Trigger.KEY_PRESS && script.keybind().matches(event)) {
					if (!isScriptRunning(script.id())) matches.add(new MacroScriptOwner(macro, script));
				}
			}
		}
		if (matches.isEmpty()) return false;
		for (geiler.addons.client.module.Module module : ModuleManager.modules()) {
			if (module.keybind().matches(event)) {
				message(minecraft, "Macro hotkey conflicts with module '" + module.name() + "'; nothing started.");
				return true;
			}
		}

		for (MacroScriptOwner owner : matches) start(owner.macro, owner.script);
		return true;
	}

	public static void tick() {
		Minecraft minecraft = Minecraft.getInstance();
		if (lastLevel != minecraft.level) {
			if (lastLevel != null) for (Run run : List.copyOf(activeRuns)) run.markLevelChanged();
			lastLevel = minecraft.level;
			lastChat = "";
			lastChatSequence = chatSequence;
		}
		for (Run run : List.copyOf(activeRuns)) {
			if (MacroFlowRules.contextEnded(MacrosModule.INSTANCE.isActive(), minecraft.level != null,
				minecraft.player != null, run.waitingForWorldSwitch())) {
				finishRun(run, "context ended", false);
				continue;
			}
			Island currentIsland = HypixelModApi.currentIsland();
			if (!run.macro.allowsIsland(currentIsland)
				&& !(run.waitingForWorldSwitch() && currentIsland == Island.NONE)) {
				finishRun(run, "context no longer allowed", false);
				continue;
			}
			run.tick(minecraft);
		}
		tickWorldTriggers(minecraft);
		finishedScripts.entrySet().removeIf(entry -> System.nanoTime() - entry.getValue() > 2_000_000_000L);
	}

	public static void onChatMessage(String content) {
		if (content == null) return;
		String normalized = ChatText.stripForMatch(content);
		lastChatSequence = ++chatSequence;
		lastChat = normalized.length() > MAX_CHAT_LENGTH
			? normalized.substring(normalized.length() - MAX_CHAT_LENGTH) : normalized;
	}

	public static void cancel(String reason) {
		for (Run run : List.copyOf(activeRuns)) finishRun(run, reason, false);
	}

	public static void cancelMacro(MacroDefinition macro, String reason) {
		if (macro == null) return;
		for (Run run : List.copyOf(activeRuns)) if (run.macro == macro) finishRun(run, reason, false);
	}

	private static void start(MacroDefinition macro) {
		if (macro == null) return;
		for (MacroScript script : macro.scripts()) {
			if (script.trigger() == MacroScript.Trigger.KEY_PRESS && script == macro.primaryKeyScript()) {
				start(macro, script);
				return;
			}
		}
	}

	private static boolean start(MacroDefinition macro, MacroScript script) {
		if (macro == null || script == null) return false;
		if (script.steps().isEmpty()) {
			message(Minecraft.getInstance(), "Macro '" + macro.name() + "' has no blocks in this event stack.");
			return false;
		}
		if (!MacroRuntimeRules.canStart(isScriptRunning(script.id()), activeRuns.size(), MAX_ACTIVE_RUNS)) {
			if (activeRuns.size() >= MAX_ACTIVE_RUNS) {
				message(Minecraft.getInstance(), "Macro runner is at its 64-stack safety limit; this trigger was ignored.");
			}
			return false;
		}
		finishedScripts.remove(script.id());
		activeRuns.add(new Run(macro, script));
		log(macro, "START");
		return true;
	}

	private static void finishRun(Run run, String reason, boolean completed) {
		if (run == null || !activeRuns.remove(run)) return;
		run.releaseAllInputs();
		if (completed) finishedScripts.put(run.script.id(), System.nanoTime());
		if (run.script.trigger() == MacroScript.Trigger.WORLD_REGION) {
			WorldTriggerState state = worldTriggerStates.get(run.script.id());
			if (state != null) state.lastCompletedNanos = System.nanoTime();
		}
		log(run.macro, (completed ? "COMPLETE" : "STOP ") + (reason == null ? "" : reason));
	}

	private static void acquireHeldKey(Run owner, InputConstants.Key key) {
		if (owner == null || key == null || key.equals(InputConstants.UNKNOWN)) return;
		Set<Run> owners = heldKeyOwners.computeIfAbsent(key.getValue(), ignored -> new HashSet<>());
		if (owners.add(owner)) setKeyDown(key, true);
	}

	private static void releaseHeldKey(Run owner, InputConstants.Key key) {
		if (owner == null || key == null) return;
		Set<Run> owners = heldKeyOwners.get(key.getValue());
		if (owners == null) return;
		owners.remove(owner);
		if (owners.isEmpty()) {
			heldKeyOwners.remove(key.getValue());
			setKeyDown(key, false);
		}
	}

	private static void setKeyDown(InputConstants.Key key, boolean down) {
		boolean previous = applyingSyntheticInput;
		applyingSyntheticInput = true;
		try { KeyMapping.set(key, down); }
		finally { applyingSyntheticInput = previous; }
	}

	private static void clickSyntheticKey(InputConstants.Key key) {
		boolean previous = applyingSyntheticInput;
		applyingSyntheticInput = true;
		try { KeyMapping.click(key); }
		finally { applyingSyntheticInput = previous; }
	}

	private static void clearPhysicalGameInputs() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.screen != null) return;
		String[] names = {"key.forward", "key.back", "key.left", "key.right", "key.jump", "key.sneak",
			"key.sprint", "key.attack", "key.use", "key.pickItem",
			"key.hotbar.1", "key.hotbar.2", "key.hotbar.3", "key.hotbar.4", "key.hotbar.5",
			"key.hotbar.6", "key.hotbar.7", "key.hotbar.8", "key.hotbar.9"};
		for (String name : names) {
			KeyMapping mapping = KeyMapping.get(name);
			if (mapping != null) mapping.setDown(false);
		}
	}

	private static void tickWorldTriggers(Minecraft minecraft) {
		if (!MacrosModule.INSTANCE.isActive() || minecraft == null || minecraft.level == null || minecraft.player == null) {
			if (triggerLevel != null) {
				triggerLevel = null;
				triggerWorldKey = "";
				worldTriggerStates.clear();
				worldInstanceSequence++;
			}
			return;
		}
		String worldKey = currentWorldKey(minecraft);
		if (triggerLevel != minecraft.level || !triggerWorldKey.equals(worldKey)) {
			triggerLevel = minecraft.level;
			triggerWorldKey = worldKey;
			worldTriggerStates.clear();
			worldInstanceSequence++;
		}
		if (minecraft.screen != null) return;
		long now = System.nanoTime();
		Island island = HypixelModApi.currentIsland();
		for (MacroDefinition macro : MacrosModule.INSTANCE.macros()) {
			if (!macro.enabled() || !macro.allowsIsland(island)) continue;
			for (MacroScript script : macro.scripts()) {
				if (script.trigger() != MacroScript.Trigger.WORLD_REGION) continue;
				MacroWorldRegion region = script.worldRegion();
				WorldTriggerState state = worldTriggerStates.computeIfAbsent(script.id(), ignored -> new WorldTriggerState());
				boolean inside = region.contains(worldKey, minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ());
				if (!inside) {
					state.inside = false;
					continue;
				}
				boolean entered = !state.inside;
				state.inside = true;
				boolean shouldStart = MacroRuntimeRules.shouldStartWorldRun(true, entered,
					isScriptRunning(script.id()), region.oncePerWorld(), state.firedThisWorld,
					state.lastCompletedNanos, now, region.repeatDelayMillis() * 1_000_000L);
				if (shouldStart && start(macro, script) && region.oncePerWorld()) state.firedThisWorld = true;
			}
		}
	}

	public static String currentWorldKey() {
		return currentWorldKey(Minecraft.getInstance());
	}

	private static String currentWorldKey(Minecraft minecraft) {
		if (minecraft == null || minecraft.level == null) return "";
		String owner;
		if (minecraft.getCurrentServer() != null) owner = minecraft.getCurrentServer().ip;
		else if (minecraft.getSingleplayerServer() != null) {
			owner = "singleplayer:" + minecraft.getSingleplayerServer().getWorldData().getLevelName();
		} else owner = "singleplayer";
		return owner + "|" + minecraft.level.dimension().identifier();
	}

	public static boolean captureWorldRegion(MacroScript script) {
		Minecraft minecraft = Minecraft.getInstance();
		if (script == null || minecraft.level == null || minecraft.player == null) return false;
		script.worldRegion().place(currentWorldKey(minecraft), minecraft.player.getX(),
			minecraft.player.getY(), minecraft.player.getZ());
		return script.worldRegion().placed();
	}

	public static void renderWorld(LevelRenderContext context) {
		Minecraft minecraft = Minecraft.getInstance();
		if (context == null || !MacrosModule.INSTANCE.isActive() || minecraft.level == null || minecraft.player == null) return;
		String current = currentWorldKey(minecraft);
		Vec3 camera = minecraft.gameRenderer.getMainCamera().position();
		boolean drew = false;
		for (MacroDefinition macro : MacrosModule.INSTANCE.macros()) {
			if (!macro.enabled()) continue;
			for (MacroScript script : macro.scripts()) {
				if (script.trigger() != MacroScript.Trigger.WORLD_REGION) continue;
				MacroWorldRegion region = script.worldRegion();
				if (!region.placed() || !region.worldKey().equals(current)) continue;
				renderWorldRegion(context, camera, region);
				drew = true;
			}
		}
		if (drew) GeilerAddonsRenderTypes.endBatches(context.bufferSource());
	}

	private static void renderWorldRegion(LevelRenderContext context, Vec3 camera, MacroWorldRegion region) {
		if (region.fillOpacity() > 0) {
			double innerRadius = region.shape() == MacroWorldRegion.Shape.RING ? region.innerSize() : 0;
			EspRenderer.renderFlatArea(context.poseStack(), context.bufferSource(),
				region.x() - camera.x, region.y() + 0.008 - camera.y, region.z() - camera.z,
				region.size(), innerRadius, region.shape() == MacroWorldRegion.Shape.SQUARE,
				MacroRuntimeRules.colorWithOpacity(region.color(), region.fillOpacity()), true);
		}
		double y = region.y() + 0.015;
		double half = region.size();
		if (region.shape() == MacroWorldRegion.Shape.SQUARE) {
			double x0 = region.x() - half, x1 = region.x() + half;
			double z0 = region.z() - half, z1 = region.z() + half;
			worldLine(context, camera, x0, y, z0, x1, y, z0, region);
			worldLine(context, camera, x1, y, z0, x1, y, z1, region);
			worldLine(context, camera, x1, y, z1, x0, y, z1, region);
			worldLine(context, camera, x0, y, z1, x0, y, z0, region);
			return;
		}
		drawCircle(context, camera, region, half);
		if (region.shape() == MacroWorldRegion.Shape.RING) drawCircle(context, camera, region, region.innerSize());
	}

	private static void drawCircle(LevelRenderContext context, Vec3 camera, MacroWorldRegion region, double radius) {
		if (radius <= 0) return;
		int segments = 64;
		for (int index = 0; index < segments; index++) {
			double a = Math.PI * 2 * index / segments;
			double b = Math.PI * 2 * (index + 1) / segments;
			worldLine(context, camera, region.x() + Math.cos(a) * radius, region.y() + 0.015,
				region.z() + Math.sin(a) * radius, region.x() + Math.cos(b) * radius,
				region.y() + 0.015, region.z() + Math.sin(b) * radius, region);
		}
	}

	private static void worldLine(LevelRenderContext context, Vec3 camera, double x0, double y0, double z0,
		double x1, double y1, double z1, MacroWorldRegion region) {
		EspRenderer.renderLine(context.poseStack(), context.bufferSource(), x0 - camera.x, y0 - camera.y, z0 - camera.z,
			x1 - camera.x, y1 - camera.y, z1 - camera.z, region.color(), region.lineWidth(), true);
	}

	private static final class WorldTriggerState {
		private boolean inside;
		private boolean firedThisWorld;
		private long lastCompletedNanos;
	}

	private record MacroScriptOwner(MacroDefinition macro, MacroScript script) { }

	/** Applies the same live macro, trigger-context and island gates to an inventory button. */
	public static InventoryButtonRules.Eligibility inventoryButtonEligibility(MacroDefinition macro,
		Minecraft minecraft, Screen screen) {
		if (minecraft == null) minecraft = Minecraft.getInstance();
		boolean exists = macro != null && MacrosModule.INSTANCE.macro(macro.id()) == macro;
		boolean inPlayerContext = minecraft.player != null && minecraft.level != null;
		boolean active = MacrosModule.INSTANCE.isActive() && inPlayerContext;
		boolean triggerAllowed = screen instanceof InventoryScreen
			&& macro != null && contextAllowed(macro.triggerContext(), screen);
		boolean islandAllowed = macro != null && macro.allowsIsland(HypixelModApi.currentIsland());
		return InventoryButtonRules.evaluate(active, exists, macro != null && macro.enabled(),
			macro != null && !macro.steps().isEmpty(), triggerAllowed, islandAllowed);
	}

	/** Starts a button-bound macro only after all current module and macro rules pass. */
	public static InventoryButtonRules.Eligibility activateInventoryButton(int macroId) {
		Minecraft minecraft = Minecraft.getInstance();
		MacrosModule.INSTANCE.macros(); // Pull the latest Click GUI setting values before the click is evaluated.
		MacroDefinition macro = MacrosModule.INSTANCE.macro(macroId);
		InventoryButtonRules.Eligibility eligibility = inventoryButtonEligibility(macro, minecraft, minecraft.screen);
		if (eligibility.eligible()) {
			start(macro);
		}
		return eligibility;
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
		return screen instanceof ChatScreen || screen instanceof ClickGuiScreen || screen instanceof MacroEditorScreen
			|| screen instanceof ScratchMacroEditorScreen
			|| screen instanceof InventoryButtonPickerScreen || screen instanceof InventoryButtonTextScreen;
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

	private static String expandVariables(String source, Map<String, Object> variables) {
		if (source == null || source.isEmpty()) return source == null ? "" : source;
		StringBuilder result = new StringBuilder(Math.min(512, source.length() + 32));
		for (int index = 0; index < source.length() && result.length() < 512;) {
			if (source.charAt(index) == '$' && index + 1 < source.length() && source.charAt(index + 1) == '{') {
				int end = source.indexOf('}', index + 2);
				if (end > index + 2) {
					String name = source.substring(index + 2, end);
					Object value = name.startsWith("global:")
						? MacrosModule.INSTANCE.globalVariables().value(name.substring("global:".length()))
						: variables == null ? null : variables.get(name);
					result.append(value == null ? source.substring(index, end + 1) : value.toString());
					index = end + 1;
					continue;
				}
			}
			result.append(source.charAt(index++));
		}
		return result.toString();
	}

	private static boolean itemMatches(ItemStack stack, String wanted, boolean contains) {
		if (stack == null || stack.isEmpty()) return false;
		String actual = ChatText.stripForMatch(stack.getHoverName().getString());
		String needle = ChatText.stripForMatch(wanted == null ? "" : wanted);
		return MacroFlowRules.itemNameMatchesAny(actual, needle, contains);
	}

	private static boolean condition(MacroCondition condition, Minecraft minecraft, long minimumChatSequence) {
		return condition(condition, minecraft, minimumChatSequence, Map.of());
	}

	private static boolean condition(MacroCondition condition, Minecraft minecraft, long minimumChatSequence,
		Map<String, Object> variables) {
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
				item.scope().serializedName(), 0) != null;
			return item.matches(found);
		}
		if (condition instanceof MacroCondition.Chat chat) {
			if (lastChatSequence <= minimumChatSequence) return false;
			String wanted = ChatText.stripForMatch(chat.text()).toLowerCase(Locale.ROOT);
			String actual = lastChat.toLowerCase(Locale.ROOT);
			return chat.contains() ? actual.contains(wanted) : actual.equals(wanted);
		}
		if (condition instanceof MacroCondition.Variable variable) {
			MacroVariableStore globals = MacrosModule.INSTANCE.globalVariables();
			Object left = variable.comparesGlobal()
				? globals.value(variable.globalVariableId())
				: variables == null ? null : variables.get(variable.name());
			Object right = variable.value().resolve(variables, globals);
			if (left == null) left = MacroValue.defaultValue(variable.value().type());
			int comparison;
			if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
				comparison = Double.compare(leftNumber.doubleValue(), rightNumber.doubleValue());
			} else if (left instanceof Boolean leftBoolean && right instanceof Boolean rightBoolean) {
				comparison = Boolean.compare(leftBoolean, rightBoolean);
			} else comparison = left.toString().compareTo(String.valueOf(right));
			return switch (variable.operator()) {
				case EQUALS -> comparison == 0;
				case NOT_EQUALS -> comparison != 0;
				case GREATER_THAN -> comparison > 0;
				case GREATER_OR_EQUAL -> comparison >= 0;
				case LESS_THAN -> comparison < 0;
				case LESS_OR_EQUAL -> comparison <= 0;
			};
		}
		if (condition instanceof MacroCondition.All all) {
			for (MacroCondition child : all.children()) if (!condition(child, minecraft, minimumChatSequence, variables)) return false;
			return true;
		}
		if (condition instanceof MacroCondition.Any any) {
			for (MacroCondition child : any.children()) if (condition(child, minecraft, minimumChatSequence, variables)) return true;
			return false;
		}
		if (condition instanceof MacroCondition.Not not) return !condition(not.child(), minecraft, minimumChatSequence, variables);
		return false;
	}

	private static Slot findSlot(Screen screen, int slotId) {
		if (!(screen instanceof AbstractContainerScreen<?> container)) return null;
		for (Slot slot : container.getMenu().slots) if (slot.index == slotId) return slot;
		return null;
	}

	private static Slot findItem(Minecraft minecraft, String name, boolean contains, String scope, int occurrence) {
		if (!(minecraft.screen instanceof AbstractContainerScreen<?> container)) return null;
		Inventory playerInventory = minecraft.player == null ? null : minecraft.player.getInventory();
		MacroCondition.ItemScope searchScope = MacroCondition.ItemScope.fromSerialized(scope,
			MacroCondition.ItemScope.CONTAINER_AND_PLAYER);
		int found = 0;
		for (Slot slot : container.getMenu().slots) {
			boolean playerSlot = playerInventory != null && slot.container == playerInventory;
			if (!searchScope.includesPlayerSlot(playerSlot) || !itemMatches(slot.getItem(), name, contains)) continue;
			if (found++ == Math.max(0, occurrence)) return slot;
		}
		return null;
	}

	private static int delay(int min, int max) {
		return MacroFlowRules.randomDelay(min, max, RANDOM);
	}

	private static final class Run {
		private final MacroDefinition macro;
		private final MacroScript script;
		private final Deque<Frame> frames = new ArrayDeque<>();
		private final long chatStartSequence;
		private long nextActionNanos;
		private long blockedSinceNanos;
		private String blockedReason;
		private final MacroKeyHoldState heldKeyState = new MacroKeyHoldState();
		private InputConstants.Key heldKeyInput;
		private Screen heldKeyScreen;
		private boolean heldKeyDeliveredToScreen;
		private InputConstants.Key heldMouseInput;
		private long heldMouseUntilNanos;
		private long inputBlockUntilNanos;
		private boolean indefiniteInputBlock;
		/** The node whose explicit pre-node delay is currently being waited out. */
		private MacroStep pendingDelayStep;
		private Frame worldSwitchFrame;
		private Island worldSwitchTarget;
		private boolean levelChanged;

		private Run(MacroDefinition macro, MacroScript script) {
			this.macro = macro;
			this.script = script;
			this.chatStartSequence = chatSequence;
			frames.push(new Frame(script.steps(), 0, 1, false, null, new HashMap<>(), Set.of(),
				Set.of(macro.id()), 0, script.id(), null));
			nextActionNanos = System.nanoTime();
		}

		private void markLevelChanged() {
			releaseAllInputs();
			levelChanged = true;
		}

		private boolean isSyntheticKeyDown(int keyCode, long nowNanos) {
			return heldKeyInput != null && heldKeyInput.getValue() == keyCode
				&& heldKeyState.isDown(keyCode, nowNanos)
				|| heldMouseInput != null && heldMouseInput.getValue() == keyCode
				&& nowNanos < heldMouseUntilNanos;
		}

		private boolean waitingForWorldSwitch() {
			return worldSwitchFrame != null || levelChanged;
		}

		private boolean isWaiting() {
			long now = System.nanoTime();
			return blockedReason != null || now < nextActionNanos || heldKeyState.isScheduled()
				|| heldMouseInput != null;
		}

		private boolean isExecutingScript(String scriptId) {
			for (Frame frame : frames) if (scriptId.equals(frame.eventScriptId)) return true;
			return false;
		}

		private MacroStep activeStepFor(String scriptId) {
			for (Frame frame : frames) {
				if (!scriptId.equals(frame.eventScriptId)) continue;
				if (frame.pendingCall != null) return frame.pendingCall;
				if (frame.index >= 0 && frame.index < frame.steps.size()) return frame.steps.get(frame.index);
			}
			return null;
		}

		private boolean callFunction(MacroStep.FunctionCall call, Frame caller, Minecraft minecraft) {
			MacroFunction function = MacrosModule.INSTANCE.function(call.functionId());
			if (function == null) {
				message(minecraft, "Macro '" + macro.name() + "' stopped: a called function is missing.");
				abort("missing function " + call.functionId());
				return false;
			}
			if (!MacroRuntimeRules.canEnterCall(caller.callDepth, MAX_CALL_DEPTH,
				caller.functionPath.contains(function.id()))) {
				message(minecraft, "Macro '" + macro.name() + "' stopped: recursive function call limit reached.");
				abort("function recursion limit");
				return false;
			}
			Map<String, Object> locals = function.bindArguments(call.arguments(), caller.variables,
				MacrosModule.INSTANCE.globalVariables());
			Set<String> functionPath = new HashSet<>(caller.functionPath);
			functionPath.add(function.id());
			caller.index++;
			if (!function.steps().isEmpty()) {
				caller.pendingCall = call;
				frames.push(new Frame(function.steps(), 0, 1, false, null, locals,
					Set.copyOf(functionPath), caller.macroPath, caller.callDepth + 1, null, caller));
			}
			return true;
		}

		private boolean callMacro(MacroStep.MacroCall call, Frame caller, Minecraft minecraft) {
			MacroDefinition target = MacrosModule.INSTANCE.macro(call.macroId());
			if (target == null || !target.enabled()) {
				message(minecraft, "Macro '" + macro.name() + "' stopped: the called macro is missing or disabled.");
				abort("missing or disabled macro " + call.macroId());
				return false;
			}
			if (!MacroRuntimeRules.canEnterCall(caller.callDepth, MAX_CALL_DEPTH,
				caller.macroPath.contains(target.id()))) {
				message(minecraft, "Macro '" + macro.name() + "' stopped: recursive macro call limit reached.");
				abort("macro recursion limit");
				return false;
			}
			if (!target.allowsIsland(HypixelModApi.currentIsland())) {
				message(minecraft, "Macro '" + macro.name() + "' stopped: the called macro is not allowed on this island.");
				abort("called macro island restriction");
				return false;
			}
			MacroScript onCall = null;
			for (MacroScript script : target.scripts()) {
				if (script.trigger() == MacroScript.Trigger.ON_CALL) { onCall = script; break; }
			}
			if (onCall == null) {
				message(minecraft, "Macro '" + macro.name() + "' stopped: target macro has no On Call stack.");
				abort("missing On Call stack");
				return false;
			}
			if (!onCall.steps().isEmpty() && MacroRunner.isScriptRunning(onCall.id())) {
				message(minecraft, "Macro '" + macro.name() + "' skipped a call because that On Call stack is already running.");
				caller.index++;
				return true;
			}
			caller.index++;
			Set<Integer> macroPath = new HashSet<>(caller.macroPath);
			macroPath.add(target.id());
			if (!onCall.steps().isEmpty()) {
				caller.pendingCall = call;
				frames.push(new Frame(onCall.steps(), 0, 1, false, null, new HashMap<>(),
					caller.functionPath, Set.copyOf(macroPath), caller.callDepth + 1, onCall.id(), caller));
			}
			return true;
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
			if (inputBlockUntilNanos > 0 && now >= inputBlockUntilNanos) releaseInputBlock();
			if (heldMouseInput != null) {
				if (MacroRuntimeRules.shouldReleaseHeldMouse(minecraft.screen != null, now, heldMouseUntilNanos)) {
					releaseHeldMouse();
					nextActionNanos = now;
					return;
				}
				return;
			}
			if (heldKeyState.isScheduled()) {
				if (heldKeyScreen != minecraft.screen) {
					releaseHeldKey();
					nextActionNanos = now;
				} else if (!heldKeyState.isDue(now)) {
					return;
				} else {
					releaseHeldKey();
					nextActionNanos = now;
					return;
				}
			}
			if (now < nextActionNanos) return;

			int structural = 0;
			while (structural++ < MAX_STRUCTURAL_STEPS) {
				Cursor cursor = nextCursor(minecraft);
				if (cursor == null) {
					finishRun(this, "", true);
					return;
				}
				MacroStep step = cursor.step();
				if (step instanceof MacroStep.WaitUntil waitUntil
					&& !condition(waitUntil.condition(), minecraft, chatStartSequence, cursor.frame().variables)) {
					if (!blocked(minecraft, "waiting for condition")) return;
					return;
				}
				if (step instanceof MacroStep.WorldSwitch worldSwitch && worldSwitchFrame == null) {
					if (!delayReady(step, now)) return;
					worldSwitchFrame = cursor.frame();
					worldSwitchTarget = worldSwitch.target();
				}
				if (!(step instanceof MacroStep.WorldSwitch) && !(step instanceof MacroStep.RepeatUntil)
					&& !delayReady(step, now)) return;
				if (step instanceof MacroStep.IfElse branch) {
					cursor.frame().index++;
					List<MacroStep> selected = condition(branch.condition(), minecraft, chatStartSequence, cursor.frame().variables)
						? branch.thenSteps() : branch.elseSteps();
					if (!selected.isEmpty()) frames.push(new Frame(selected, 0, 1, false, null, cursor.frame()));
					continue;
				}
				if (step instanceof MacroStep.Repeat repeat) {
					cursor.frame().index++;
					if (!repeat.steps().isEmpty()) {
						frames.push(new Frame(repeat.steps(), 0, repeat.forever() ? -1 : repeat.count(), true,
							null, cursor.frame()));
					}
					continue;
				}
				if (step instanceof MacroStep.RepeatUntil repeatUntil) {
					MacroFlowRules.UntilDecision decision = MacroFlowRules.repeatUntil(
						condition(repeatUntil.condition(), minecraft, chatStartSequence, cursor.frame().variables), !repeatUntil.steps().isEmpty());
					if (decision == MacroFlowRules.UntilDecision.EXIT) {
						cursor.frame().index++;
						clearBlocked();
						continue;
					}
					if (decision == MacroFlowRules.UntilDecision.EMPTY_BODY) {
						abort("Repeat Until has no steps");
						return;
					}
					if (!delayReady(step, now)) return;
					cursor.frame().index++;
					frames.push(new Frame(repeatUntil.steps(), 0, 1, false, repeatUntil, cursor.frame()));
					continue;
				}
				if (step instanceof MacroStep.FunctionCall functionCall) {
					if (!callFunction(functionCall, cursor.frame(), minecraft)) return;
					continue;
				}
				if (step instanceof MacroStep.MacroCall macroCall) {
					if (!callMacro(macroCall, cursor.frame(), minecraft)) return;
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

				if (!execute(step, minecraft, now, cursor.frame().variables)) {
					if (step instanceof MacroStep.ClickItem
						&& finishRepeatUntilWhenItemIsMissing(minecraft)) continue;
					if (step instanceof MacroStep.Key key) {
						message(minecraft, "Macro '" + macro.name() + "' stopped: could not apply key '"
							+ keyDisplayName(key.key()) + "'.");
						abort("could not apply key " + keyDisplayName(key.key()));
						return;
					}
					blocked(minecraft, "step " + step.type());
					return;
				}
				cursor.frame().index++;
				clearBlocked();
				return;
			}
			abort("workflow contains too many empty structural steps");
		}

		private Cursor nextCursor(Minecraft minecraft) {
			while (!frames.isEmpty()) {
				Frame frame = frames.peek();
				if (frame.index < frame.steps.size()) return new Cursor(frame, frame.steps.get(frame.index));
				if (frame.repeatUntil != null) {
					MacroFlowRules.UntilDecision decision = MacroFlowRules.repeatUntil(
					condition(frame.repeatUntil.condition(), minecraft, chatStartSequence, frame.variables), !frame.steps.isEmpty());
					if (decision == MacroFlowRules.UntilDecision.EXIT) {
						popFrame(true);
						clearBlocked();
						continue;
					}
					if (decision == MacroFlowRules.UntilDecision.EMPTY_BODY) {
						abort("Repeat Until has no steps");
						return null;
					}
					frame.index = 0;
					continue;
				}
				if (frame.repeatFrame && (frame.repeatsRemaining < 0 || frame.repeatsRemaining > 1)) {
					if (frame.repeatsRemaining > 1) frame.repeatsRemaining--;
					frame.index = 0;
					continue;
				}
				popFrame(true);
			}
			return null;
		}

		/** A missing Click Item is the normal final iteration when the Repeat Until stop condition is item-missing. */
		private boolean finishRepeatUntilWhenItemIsMissing(Minecraft minecraft) {
			for (Frame frame : frames) {
				if (frame.repeatUntil == null) continue;
				boolean stopConditionTrue = condition(frame.repeatUntil.condition(), minecraft, chatStartSequence, frame.variables);
				if (!MacroFlowRules.missingItemEndsUntil(true, stopConditionTrue)) return false;
				while (frames.peek() != frame) popFrame(false);
				popFrame(false);
				pendingDelayStep = null;
				clearBlocked();
				nextActionNanos = System.nanoTime();
				return true;
			}
			return false;
		}

		private void popFrame(boolean completed) {
			Frame popped = frames.pop();
			if (popped.returnCaller == null) return;
			popped.returnCaller.pendingCall = null;
			if (completed && popped.eventScriptId != null) {
				finishedScripts.put(popped.eventScriptId, System.nanoTime());
			}
		}

		private boolean execute(MacroStep step, Minecraft minecraft, long now, Map<String, Object> variables) {
			if (step instanceof MacroStep.Command command) {
				if (minecraft.player == null || command.command().isBlank()) return false;
				String value = expandVariables(command.command().strip(), variables);
				minecraft.player.connection.sendCommand(value.startsWith("/") ? value.substring(1) : value);
				log(macro, "COMMAND " + value);
				return true;
			}
			if (step instanceof MacroStep.Chat chat) {
				if (minecraft.player == null || chat.message().isBlank()) return false;
				String value = expandVariables(chat.message(), variables);
				minecraft.player.connection.sendChat(value);
				log(macro, "CHAT " + value);
				return true;
			}
			if (step instanceof MacroStep.Title title) {
				MacroTitleOverlay.show(title);
				return true;
			}
			if (step instanceof MacroStep.Sound sound) {
				Identifier id = Identifier.tryParse(sound.soundId());
				SoundEvent event = id == null || !BuiltInRegistries.SOUND_EVENT.keySet().contains(id)
					? null : BuiltInRegistries.SOUND_EVENT.getValue(id);
				if (event != null) minecraft.getSoundManager().play(SimpleSoundInstance.forUI(event, 1.0f, 1.0f));
				return true;
			}
			if (step instanceof MacroStep.Wait wait) {
				nextActionNanos = now + delay(wait.minMillis(), wait.maxMillis()) * 1_000_000L;
				return true;
			}
			if (step instanceof MacroStep.Key key) return pressKey(key, minecraft, now);
			if (step instanceof MacroStep.SelectHotbarSlot select) {
				if (minecraft.player == null) return false;
				if (!MacroRuntimeRules.canSelectHotbar(minecraft.screen instanceof AbstractContainerScreen<?>)) {
					if (notices.add("hotbar-screen")) message(minecraft,
						"Hotbar selection skipped while an inventory or container is open.");
					return true;
				}
				minecraft.player.getInventory().setSelectedSlot(select.slot() - 1);
				return true;
			}
			if (step instanceof MacroStep.MouseButton mouse) return pressMouse(mouse, minecraft, now);
			if (step instanceof MacroStep.BlockPlayerInput block) {
				beginTimedInputBlock(now + block.durationMillis() * 1_000_000L);
				return true;
			}
			if (step instanceof MacroStep.StartBlockPlayerInput) {
				beginIndefiniteInputBlock();
				return true;
			}
			if (step instanceof MacroStep.StopBlockPlayerInput) {
				releaseInputBlock();
				return true;
			}
			if (step instanceof MacroStep.SetVariable set) {
				Object value = set.value().resolve(variables, MacrosModule.INSTANCE.globalVariables());
				if (set.targetsGlobal()) MacrosModule.INSTANCE.globalVariables().setValue(set.globalVariableId(), value);
				else variables.put(set.name(), value);
				return true;
			}
			if (step instanceof MacroStep.ChangeVariable change) {
				if (change.targetsGlobal()) {
					MacroVariableStore globals = MacrosModule.INSTANCE.globalVariables();
					Object current = globals.value(change.globalVariableId());
					if (current instanceof Number number) globals.setValue(change.globalVariableId(), number.doubleValue() + change.amount());
					return true;
				}
				Object current = variables.get(change.name());
				double number = current instanceof Number value ? value.doubleValue() : 0;
				variables.put(change.name(), number + change.amount());
				return true;
			}
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
			KeyEvent event = new KeyEvent(key.getValue(), 0, modifierMask(key));
			Screen screen = minecraft.screen;
			if (screen != null) {
				if (step.hold()) {
					boolean delivered = screen.keyPressed(event);
					if (!delivered && modifierMask(key) == 0) return false;
					beginHeldKey(screen, key, delivered, now, step);
				} else {
					if (!screen.keyPressed(event)) return false;
					((GuiEventListener) screen).keyReleased(event);
				}
			} else {
				if (step.hold()) {
					beginHeldKey(null, key, false, now, step);
				} else {
					clickSyntheticKey(key);
				}
			}
			return true;
		}

		private void beginHeldKey(Screen screen, InputConstants.Key key, boolean delivered,
			long now, MacroStep.Key step) {
			int holdMillis = delay(step.holdMinMillis(), step.holdMaxMillis());
			heldKeyInput = key;
			heldKeyScreen = screen;
			heldKeyDeliveredToScreen = delivered;
			if (screen == null) acquireHeldKey(this, key);
			heldKeyState.begin(key.getValue(), now + holdMillis * 1_000_000L);
		}

		private boolean pressMouse(MacroStep.MouseButton step, Minecraft minecraft, long now) {
			if (minecraft.player == null || minecraft.screen != null) {
				if (notices.add("mouse-screen")) message(minecraft,
					"Mouse macro blocks run only in the world; close the current screen first.");
				return true;
			}
			int mouseButton = switch (step.button()) {
				case LEFT -> 0;
				case RIGHT -> 1;
				case MIDDLE -> 2;
			};
			InputConstants.Key key = InputConstants.Type.MOUSE.getOrCreate(mouseButton);
			if (step.hold()) {
				heldMouseInput = key;
				heldMouseUntilNanos = now + step.holdMillis() * 1_000_000L;
				acquireHeldKey(this, key);
			} else clickSyntheticKey(key);
			return true;
		}

		private static String keyDisplayName(String value) {
			InputConstants.Key key = parseKey(value);
			return key.equals(InputConstants.UNKNOWN) ? String.valueOf(value) : key.getDisplayName().getString();
		}

		private static int modifierMask(InputConstants.Key key) {
			int code = key.getValue();
			if (code == InputConstants.KEY_LSHIFT || code == InputConstants.KEY_RSHIFT) return InputConstants.MOD_SHIFT;
			if (code == InputConstants.KEY_LCONTROL || code == InputConstants.KEY_RCONTROL) return InputConstants.MOD_CONTROL;
			if (code == InputConstants.KEY_LALT || code == InputConstants.KEY_RALT) return InputConstants.MOD_ALT;
			if (code == InputConstants.KEY_LSUPER || code == InputConstants.KEY_RSUPER) return InputConstants.MOD_SUPER;
			return 0;
		}

		private boolean clickSlot(MacroStep.ClickSlot step, Minecraft minecraft) {
			if (!(minecraft.screen instanceof AbstractContainerScreen<?> screen)) return false;
			Slot slot = findSlot(screen, step.slotId());
			if (slot == null) return false;
			return dispatchClick(screen, slot, step.button(), step.shift());
		}

		private boolean clickItem(MacroStep.ClickItem step, Minecraft minecraft) {
			if (!(minecraft.screen instanceof AbstractContainerScreen<?> screen)) return false;
			Slot slot = findItem(minecraft, step.name(), step.contains(), step.scope(), step.occurrence());
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

		private void beginTimedInputBlock(long untilNanos) {
			if (inputBlockOwners.isEmpty()) clearPhysicalGameInputs();
			inputBlockOwners.add(this);
			indefiniteInputBlock = false;
			inputBlockUntilNanos = Math.max(System.nanoTime() + 1_000_000L, untilNanos);
			nextActionNanos = inputBlockUntilNanos;
		}

		private void beginIndefiniteInputBlock() {
			if (inputBlockOwners.isEmpty()) clearPhysicalGameInputs();
			inputBlockOwners.add(this);
			indefiniteInputBlock = true;
			inputBlockUntilNanos = 0;
		}

		private void releaseInputBlock() {
			inputBlockOwners.remove(this);
			inputBlockUntilNanos = 0;
			indefiniteInputBlock = false;
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
			if (!heldKeyState.isScheduled()) return;
			InputConstants.Key key = heldKeyInput;
			Screen screen = heldKeyScreen;
			boolean delivered = heldKeyDeliveredToScreen;
			heldKeyState.clear();
			heldKeyInput = null;
			heldKeyScreen = null;
			heldKeyDeliveredToScreen = false;
			if (key == null) return;
			KeyEvent event = new KeyEvent(key.getValue(), 0, modifierMask(key));
			if (screen != null && delivered) ((GuiEventListener) screen).keyReleased(event);
			if (screen == null) MacroRunner.releaseHeldKey(this, key);
		}

		private void releaseHeldMouse() {
			InputConstants.Key key = heldMouseInput;
			heldMouseInput = null;
			heldMouseUntilNanos = 0;
			if (key != null) MacroRunner.releaseHeldKey(this, key);
		}

		private void releaseAllInputs() {
			releaseHeldKey();
			releaseHeldMouse();
			releaseInputBlock();
		}

		private void abort(String reason) {
			finishRun(this, reason, false);
		}

		private final Set<String> notices = new HashSet<>();
	}

	private static final class Frame {
		private final List<MacroStep> steps;
		private int index;
		private int repeatsRemaining;
		private final boolean repeatFrame;
		private final MacroStep.RepeatUntil repeatUntil;
		private final Map<String, Object> variables;
		private final Set<String> functionPath;
		private final Set<Integer> macroPath;
		private final int callDepth;
		private final String eventScriptId;
		private final Frame returnCaller;
		private MacroStep pendingCall;

		private Frame(List<MacroStep> steps, int index, int repeatsRemaining, boolean repeatFrame) {
			this(steps, index, repeatsRemaining, repeatFrame, null,
				new HashMap<>(), Set.of(), Set.of(), 0, null, null);
		}

		private Frame(List<MacroStep> steps, int index, int repeatsRemaining, boolean repeatFrame,
			MacroStep.RepeatUntil repeatUntil) {
			this(steps, index, repeatsRemaining, repeatFrame, repeatUntil,
				new HashMap<>(), Set.of(), Set.of(), 0, null, null);
		}

		private Frame(List<MacroStep> steps, int index, int repeatsRemaining, boolean repeatFrame,
			MacroStep.RepeatUntil repeatUntil, Frame parent) {
			this(steps, index, repeatsRemaining, repeatFrame, repeatUntil,
				parent.variables, parent.functionPath, parent.macroPath, parent.callDepth,
				parent.eventScriptId, null);
		}

		private Frame(List<MacroStep> steps, int index, int repeatsRemaining, boolean repeatFrame,
			MacroStep.RepeatUntil repeatUntil, Map<String, Object> variables, Set<String> functionPath,
			Set<Integer> macroPath, int callDepth, String eventScriptId, Frame returnCaller) {
			this.steps = steps;
			this.index = index;
			this.repeatsRemaining = repeatsRemaining;
			this.repeatFrame = repeatFrame;
			this.repeatUntil = repeatUntil;
			this.variables = variables;
			this.functionPath = functionPath;
			this.macroPath = macroPath;
			this.callDepth = Math.max(0, callDepth);
			this.eventScriptId = eventScriptId;
			this.returnCaller = returnCaller;
		}
	}

	private record Cursor(Frame frame, MacroStep step) {
	}

}
