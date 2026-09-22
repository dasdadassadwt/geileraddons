package geiler.addons.client.macro;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import geiler.addons.client.config.MacroScriptConfigCodec;

import java.util.ArrayList;
import java.util.List;

/**
 * Offline checks for chat-triggered event stacks.
 *
 * <p>The matching rules are where the feature can go quietly wrong - a pattern that matches too
 * much turns every chat line into a start, and one that matches the macro's own echo loops forever -
 * so both halves are pinned down here rather than in game.
 */
public final class MacroChatTriggerChecks {
	private MacroChatTriggerChecks() {
	}

	public static void run() {
		checkMatching();
		checkCooldown();
		checkEchoWindow();
		checkScriptConfiguration();
		checkRoundTrip();
	}

	private static void checkMatching() {
		assertTrue(MacroChatTrigger.matches("hello", true, "Party > [MVP+] Player: hello there"),
			"a contained pattern tolerates the channel and rank prefix");
		assertTrue(MacroChatTrigger.matches("HELLO", true, "party > player: hello there"),
			"chat matching ignores case");
		assertTrue(MacroChatTrigger.matches("\u00a77Wave 3", true, "\u00a7eWave 3"),
			"legacy formatting codes are dropped from both sides");
		assertFalse(MacroChatTrigger.matches("hello", false, "Party > Player: hello there"),
			"an exact pattern rejects a prefixed line");
		assertTrue(MacroChatTrigger.matches("Party > Player: hello there", false, "Party > Player: hello there"),
			"an exact pattern accepts the whole line");
		assertFalse(MacroChatTrigger.matches("", true, "anything at all"),
			"a blank pattern never matches, so a new stack is inert");
		assertFalse(MacroChatTrigger.matches("   ", true, "anything at all"),
			"a whitespace-only pattern never matches");
		assertFalse(MacroChatTrigger.matches("hello", true, ""), "a blank line never matches");
		assertFalse(MacroChatTrigger.matches(null, true, "hello"), "a null pattern never matches");
	}

	private static void checkCooldown() {
		long now = 1_000_000_000L;
		assertTrue(MacroChatTrigger.cooldownElapsed(0L, now, 500), "a stack that never ran may fire");
		assertFalse(MacroChatTrigger.cooldownElapsed(now - 100_000_000L, now, 500),
			"a stack inside its repeat delay does not fire");
		assertTrue(MacroChatTrigger.cooldownElapsed(now - 500_000_000L, now, 500),
			"the repeat delay boundary itself fires");
		assertTrue(MacroChatTrigger.cooldownElapsed(now - 1L, now, 0), "a zero delay always fires");
		assertTrue(MacroChatTrigger.cooldownElapsed(now - 1L, now, -5), "a negative delay is treated as zero");
	}

	private static void checkEchoWindow() {
		long now = 10_000_000_000L;
		List<MacroChatTrigger.SentLine> sent = List.of(
			new MacroChatTrigger.SentLine("running the farm now", now - 1_000_000_000L));
		assertTrue(MacroChatTrigger.isOwnEcho("Party > [MVP+] Player: running the farm now", sent, now),
			"an echoed macro line is recognised through its prefix");
		assertFalse(MacroChatTrigger.isOwnEcho("Party > Player: something else", sent, now),
			"an unrelated line is not treated as an echo");
		assertFalse(MacroChatTrigger.isOwnEcho("running the farm now", sent, now + MacroChatTrigger.ECHO_WINDOW_NANOS + 1L),
			"a line older than the echo window stops suppressing triggers");
		assertFalse(MacroChatTrigger.isOwnEcho("running the farm now", List.of(), now),
			"with nothing sent recently, nothing is suppressed");

		List<MacroChatTrigger.SentLine> many = new ArrayList<>();
		for (int i = 0; i < MacroChatTrigger.MAX_SENT_LINES + 5; i++) {
			many.add(new MacroChatTrigger.SentLine("line " + i, now - i));
		}
		List<MacroChatTrigger.SentLine> pruned = MacroChatTrigger.pruneSent(many, now);
		assertSame(MacroChatTrigger.MAX_SENT_LINES, pruned.size(), "the sent-line window is bounded");
		assertEquals("line " + (many.size() - 1), pruned.getLast().text(), "the newest sent lines are kept");
		assertSame(0, MacroChatTrigger.pruneSent(null, now).size(), "a missing window prunes to empty");
		assertSame(0, MacroChatTrigger.pruneSent(many, now + MacroChatTrigger.ECHO_WINDOW_NANOS + 1L).size(),
			"an expired window prunes to empty");
	}

	private static void checkScriptConfiguration() {
		MacroScript chat = new MacroScript(MacroScript.Trigger.CHAT);
		assertSame(MacroScript.Trigger.CHAT, chat.trigger(), "the chat trigger is stored on the stack");
		assertEquals("", chat.chatPattern(), "a new chat stack has no pattern yet");
		assertTrue(chat.chatContains(), "chat stacks match anywhere in the line by default");
		assertEquals(500, chat.chatCooldownMillis(), "chat stacks default to a 500 ms repeat delay");
		assertFalse(chat.keybind().isBound(), "a new chat stack has no manual replay key");

		chat.setChatPattern("x".repeat(MacroChatTrigger.MAX_PATTERN_LENGTH + 40));
		assertEquals(MacroChatTrigger.MAX_PATTERN_LENGTH, chat.chatPattern().length(),
			"a pattern is truncated to its bound");
		chat.setChatPattern(null);
		assertEquals("", chat.chatPattern(), "a null pattern becomes empty rather than throwing");
		chat.setChatCooldownMillis(-1);
		assertEquals(0, chat.chatCooldownMillis(), "a negative repeat delay clamps to zero");
		chat.setChatCooldownMillis(999_999);
		assertEquals(60_000, chat.chatCooldownMillis(), "an oversized repeat delay clamps to a minute");
		chat.setChatContains(false);
		assertFalse(chat.chatContains(), "the exact-match toggle is stored");
	}

	private static void checkRoundTrip() {
		MacroScript chat = new MacroScript("chat-stack", MacroScript.Trigger.CHAT);
		chat.setChatPattern("wave cleared");
		chat.setChatContains(false);
		chat.setChatCooldownMillis(1_250);

		MacroScript decoded = MacroScriptConfigCodec.decode(
			MacroScriptConfigCodec.encode(List.of(chat))).getFirst();
		assertSame(MacroScript.Trigger.CHAT, decoded.trigger(), "the config keeps the chat trigger kind");
		assertEquals("wave cleared", decoded.chatPattern(), "the config keeps the chat pattern");
		assertFalse(decoded.chatContains(), "the config keeps the exact-match toggle");
		assertEquals(1_250, decoded.chatCooldownMillis(), "the config keeps the repeat delay");

		MacroDefinition macro = new MacroDefinition(41);
		macro.restoreScripts(List.of(chat));
		JsonObject packageRoot = JsonParser.parseString(MacroTransfer.encode(List.of(macro))).getAsJsonObject();
		assertEquals(5, packageRoot.get("version").getAsInt(),
			"the transfer format is version 5 now that chat stacks exist");
		MacroDefinition imported = MacroTransfer.decode(packageRoot.toString(), 100).macros().getFirst();
		MacroScript importedChat = imported.scripts().stream()
			.filter(script -> script.trigger() == MacroScript.Trigger.CHAT).findFirst().orElseThrow();
		assertEquals("wave cleared", importedChat.chatPattern(), "an imported chat stack keeps its pattern");

		// A version-4 package predates chat stacks: its scripts must decode inert, not throw.
		JsonObject legacy = packageRoot.deepCopy();
		legacy.addProperty("version", 4);
		for (var entry : legacy.getAsJsonArray("macros")) {
			JsonObject script = entry.getAsJsonObject().getAsJsonArray("scripts").get(0).getAsJsonObject();
			script.remove("chatPattern");
			script.remove("chatContains");
			script.remove("chatCooldownMillis");
		}
		MacroScript legacyChat = MacroTransfer.decode(legacy.toString(), 200).macros().getFirst()
			.scripts().getFirst();
		assertEquals("", legacyChat.chatPattern(), "a legacy package decodes with an inert pattern");
		assertTrue(legacyChat.chatContains(), "a legacy package decodes with the default matching mode");
		assertEquals(500, legacyChat.chatCooldownMillis(), "a legacy package decodes with the default delay");
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static void assertFalse(boolean value, String label) {
		if (value) throw new AssertionError(label);
	}

	private static void assertSame(Object expected, Object actual, String label) {
		if (expected != actual) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected " + expected + ", got " + actual);
		}
	}
}
