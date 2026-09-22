package geiler.addons.client.module.impl;

import geiler.addons.client.dungeon.DungeonClass;
import geiler.addons.client.dungeon.DungeonRequestLimiter;
import geiler.addons.client.dungeon.DungeonStats;
import geiler.addons.client.party.PartyListRequestGate;
import geiler.addons.client.party.PartyListBackend;
import geiler.addons.client.party.PartyLookupAttempts;
import geiler.addons.client.party.PartyMember;

import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;

/** Offline checks for per-party lookup retention, retries, request pacing, and fail-closed enforcement. */
public final class PartyFinderReliabilityChecks {
	private PartyFinderReliabilityChecks() {
	}

	public static void run() {
		checkLookupAttempts();
		checkPartyListRequestGate();
		checkDungeonRequestLimiter();
		checkUnavailableDataCannotAuthorizeKick();
	}

	private static void checkLookupAttempts() {
		PartyLookupAttempts attempts = new PartyLookupAttempts();
		long generation = 41;
		PartyMember first = new PartyMember("AlreadyChecked", null, DungeonClass.MAGE);
		PartyLookupAttempts.BeginResult initial = attempts.beginAutomatic(generation, first);
		check(initial.started() && initial.attempt().state() == PartyLookupAttempts.State.PENDING,
			"a new party member starts one pending lookup");
		PartyLookupAttempts.BeginResult duplicate = attempts.beginAutomatic(generation,
			first.withClass(DungeonClass.ARCHER));
		check(!duplicate.started() && duplicate.attempt().state() == PartyLookupAttempts.State.PENDING,
			"repeated joins and class changes do not duplicate a pending request");

		UUID firstUuid = UUID.fromString("12345678-1234-1234-1234-123456789012");
		DungeonStats partial = incompleteStats("AlreadyChecked", firstUuid);
		check(attempts.complete(initial.handle(), generation, partial, null),
			"a profile response completes its current attempt");
		PartyMember refreshed = new PartyMember("AlreadyChecked", firstUuid, DungeonClass.ARCHER);
		PartyLookupAttempts.AttemptView successful = attempts.get(generation, refreshed);
		check(successful != null && successful.state() == PartyLookupAttempts.State.SUCCESS
			&& successful.stats() == partial,
			"prior successful stats are reused when roster records or classes refresh");
		check(!attempts.beginAutomatic(generation, refreshed).started(),
			"an already checked member is not fetched again");
		check(attempts.beginAutomatic(generation, new PartyMember("NewJoin", null, null)).started(),
			"a genuinely new party member gets a lookup");

		PartyMember failedMember = new PartyMember("RateLimited", null, null);
		PartyLookupAttempts.RequestHandle failedHandle = attempts.beginAutomatic(generation, failedMember).handle();
		check(attempts.complete(failedHandle, generation, null, "HTTP 429"),
			"a failed API request is retained as an unavailable attempt");
		PartyLookupAttempts.AttemptView unavailable = attempts.get(generation, failedMember);
		check(unavailable != null && unavailable.state() == PartyLookupAttempts.State.UNAVAILABLE
			&& "HTTP 429".equals(unavailable.error()),
			"rate-limit errors remain visible in the session attempt state");
		check(!attempts.beginAutomatic(generation, failedMember).started(),
			"roster refreshes do not automatically retry a failed lookup");
		check(attempts.markUnavailableReported(generation, failedMember)
			&& !attempts.markUnavailableReported(generation, failedMember),
			"an unavailable attempt surfaces one status card instead of spamming chat");

		long retryTick = 500;
		PartyLookupAttempts.RetryResult retry = attempts.beginManualRetry(generation, failedMember, retryTick);
		check(retry.started(), "the explicit per-player retry starts an unavailable lookup");
		check(attempts.complete(retry.handle(), generation, null, "request timed out"),
			"manual retry failures remain unavailable");
		PartyLookupAttempts.RetryResult cooldown = attempts.beginManualRetry(generation, failedMember, retryTick + 1);
		check(cooldown.blockReason() == PartyLookupAttempts.RetryBlockReason.COOLDOWN
			&& cooldown.cooldownTicksRemaining() == PartyLookupAttempts.MANUAL_RETRY_COOLDOWN_TICKS - 1,
			"manual retry cooldown is enforced per player");
		check(!attempts.beginAutomatic(generation, failedMember).started(),
			"a failed manual retry is not retried by ordinary roster updates");
		check(attempts.beginManualRetry(generation, failedMember,
			retryTick + PartyLookupAttempts.MANUAL_RETRY_COOLDOWN_TICKS).started(),
			"an explicit retry is available again after its cooldown");
		check(attempts.beginAutomatic(generation + 1, first).started(),
			"a new party generation starts a fresh lookup session");

		PartyLookupAttempts identityAttempts = new PartyLookupAttempts();
		PartyMember oldIdentity = new PartyMember("ReusedName", firstUuid, null);
		PartyLookupAttempts.RequestHandle stale = identityAttempts.beginAutomatic(generation, oldIdentity).handle();
		UUID replacementUuid = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
		PartyLookupAttempts.BeginResult replacement = identityAttempts.beginAutomatic(generation,
			new PartyMember("ReusedName", replacementUuid, null));
		check(replacement.started() && !identityAttempts.complete(stale, generation, partial, null),
			"a same-name identity replacement does not inherit or overwrite another request");

		checkLeaveKickRejoin();
	}

	private static void checkLeaveKickRejoin() {
		long generation = 91;
		String memberName = "QuickReturn";
		UUID uuid = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff");
		PartyMember member = new PartyMember(memberName, uuid, DungeonClass.MAGE);
		PartyLookupAttempts attempts = new PartyLookupAttempts();
		PartyLookupAttempts.BeginResult beforeKick = attempts.beginAutomatic(generation, member);
		DungeonStats priorStats = incompleteStats(memberName, uuid);
		check(beforeKick.started() && attempts.complete(beforeKick.handle(), generation, priorStats, null),
			"the original membership can complete its lookup before the kick");
		check("QuickReturn".equals(PartyListBackend.departedMemberName("QuickReturn has left the party.")),
			"a confirmed voluntary leave identifies which membership ended");
		String kickedMember = PartyListBackend.departedMemberName(
			"Party > [MVP+] QuickReturn has been removed from the party.");
		check(memberName.equals(kickedMember) && attempts.forgetMember(generation, kickedMember),
			"a kick confirmation clears that member's prior successful attempt");
		check(PartyListBackend.departedMemberName("Party Members (2)") == null,
			"an unchanged roster refresh does not clear membership lookup state");

		PartyLookupAttempts.BeginResult afterRejoin = attempts.beginAutomatic(generation,
			new PartyMember(memberName, uuid, DungeonClass.MAGE));
		DungeonStats rejoinStats = incompleteStats(memberName, uuid);
		check(afterRejoin.started() && afterRejoin.handle().token() != beforeKick.handle().token(),
			"a quick rejoin starts a fresh lookup rather than reusing the kicked membership");
		check(attempts.complete(afterRejoin.handle(), generation, rejoinStats, null)
			&& attempts.get(generation, member).stats() == rejoinStats,
			"fresh rejoin stats can complete and be retained for the stats message");
		check(PartyFinderStatsModule.laterJoinDisposition(7, 7, true, false)
			== PartyFinderStatsModule.LaterJoinDisposition.DISPLAY,
			"a successful rejoin callback remains eligible to display its stats message");
	}

	private static void checkPartyListRequestGate() {
		PartyListRequestGate gate = new PartyListRequestGate();
		check(gate.shouldRequest(1, 0, false), "the first incomplete roster requests a bootstrap snapshot");
		check(!gate.shouldRequest(1, 1, false), "a repeated join is debounced during bootstrap");
		check(!gate.shouldRequest(1, PartyListRequestGate.RECOVERY_COOLDOWN_TICKS - 1, false),
			"recovery waits for the debounce interval");
		check(gate.shouldRequest(1, PartyListRequestGate.RECOVERY_COOLDOWN_TICKS, false),
			"a missing snapshot can trigger one delayed recovery");
		check(!gate.shouldRequest(1, 2 * PartyListRequestGate.RECOVERY_COOLDOWN_TICKS, false),
			"list requests are capped after bootstrap and one recovery");
		check(!gate.shouldRequest(2, 500, true), "a known stable roster needs no list command");
		check(gate.shouldRequest(3, 500, false), "a new party session receives a fresh bootstrap");
	}

	private static void checkDungeonRequestLimiter() {
		DungeonRequestLimiter limiter = new DungeonRequestLimiter(2, 1_000_000_000L);
		DungeonRequestLimiter.Ticket first = limiter.tryAcquire();
		DungeonRequestLimiter.Ticket second = limiter.tryAcquire();
		check(first != null && second != null && limiter.tryAcquire() == null,
			"outstanding profile work is bounded");
		check(limiter.reserveStartDelayNanos(0) == 0
			&& limiter.reserveStartDelayNanos(0) == 1_000_000_000L,
			"successive player lookups reserve starts at the configured interval");
		first.close();
		first.close();
		DungeonRequestLimiter.Ticket reused = limiter.tryAcquire();
		check(limiter.outstanding() == 2 && reused != null,
			"request slots are released once and can be reused");
		reused.close();
		second.close();
		check(limiter.outstanding() == 0, "completed requests release every outstanding slot");
	}

	private static void checkUnavailableDataCannotAuthorizeKick() {
		DungeonStats partial = incompleteStats("Incomplete", UUID.randomUUID());
		check(!partial.cacheable(), "an incomplete profile fixture stays identifiable as incomplete");
		check(!AutoKickRules.canApplyAction(true, true),
			"unknown profile fields suppress otherwise-failing kick requirements");
		check(!AutoKickRules.canApplyAction(true, false)
			&& AutoKickRules.canApplyAction(false, true)
			&& !AutoKickRules.canApplyAction(false, false),
			"only a known failure can authorize Auto Kick enforcement");
		check("/ga pfretry Incomplete".equals(PartyFinderStatsModule.retryCommand("Incomplete")),
			"unavailable states expose a local retry action rather than a kick command");
	}

	private static DungeonStats incompleteStats(String name, UUID uuid) {
		return new DungeonStats(name, uuid, 0, null, Map.of(), 0, 0, 0, 0, 0,
			false, false, EnumSet.noneOf(DungeonStats.Gear.class), Map.of());
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
