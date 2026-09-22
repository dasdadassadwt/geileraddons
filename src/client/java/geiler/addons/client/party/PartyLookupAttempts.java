package geiler.addons.client.party;

import geiler.addons.client.dungeon.DungeonStats;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Tracks one automatic stats attempt per player for the lifetime of a party session. */
public final class PartyLookupAttempts {
	public static final long MANUAL_RETRY_COOLDOWN_TICKS = 20L * 30L;

	private long generation = Long.MIN_VALUE;
	private long nextToken;
	private final Map<String, Entry> entries = new HashMap<>();

	public BeginResult beginAutomatic(long partyGeneration, PartyMember member) {
		if (member == null || member.name() == null || member.name().isBlank()) {
			return new BeginResult(null, null);
		}
		syncGeneration(partyGeneration);
		String key = key(member.name());
		Entry existing = entries.get(key);
		if (existing != null && compatible(existing.uuid, member.uuid())) {
			if (existing.uuid == null && member.uuid() != null) existing.uuid = member.uuid();
			return new BeginResult(null, view(existing, 0));
		}
		Entry entry = new Entry(member.name(), member.uuid(), ++nextToken);
		entries.put(key, entry);
		return new BeginResult(new RequestHandle(partyGeneration, key, entry.token), view(entry, 0));
	}

	public RetryResult beginManualRetry(long partyGeneration, PartyMember member, long currentTick) {
		if (member == null || member.name() == null || member.name().isBlank()) {
			return new RetryResult(null, null, RetryBlockReason.NOT_TRACKED, 0);
		}
		syncGeneration(partyGeneration);
		String key = key(member.name());
		Entry entry = entries.get(key);
		if (entry == null || !compatible(entry.uuid, member.uuid())) {
			return new RetryResult(null, null, RetryBlockReason.NOT_TRACKED, 0);
		}
		if (entry.state == State.PENDING) {
			return new RetryResult(null, view(entry, 0), RetryBlockReason.PENDING, 0);
		}
		if (entry.state == State.SUCCESS && (entry.stats == null || entry.stats.cacheable())) {
			return new RetryResult(null, view(entry, 0), RetryBlockReason.NOT_RETRYABLE, 0);
		}
		if (entry.lastManualRetryTick != Long.MIN_VALUE) {
			long elapsed = Math.max(0, currentTick - entry.lastManualRetryTick);
			long remaining = MANUAL_RETRY_COOLDOWN_TICKS - elapsed;
			if (remaining > 0) {
				return new RetryResult(null, view(entry, remaining), RetryBlockReason.COOLDOWN, remaining);
			}
		}
		entry.state = State.PENDING;
		entry.stats = null;
		entry.error = null;
		entry.unavailableReported = false;
		entry.lastManualRetryTick = currentTick;
		entry.token = ++nextToken;
		return new RetryResult(new RequestHandle(partyGeneration, key, entry.token), view(entry, 0), null, 0);
	}

	/** Completes only the still-current attempt; stale callbacks cannot replace a newer identity. */
	public boolean complete(RequestHandle handle, long currentPartyGeneration, DungeonStats stats, String error) {
		if (handle == null) return false;
		syncGeneration(currentPartyGeneration);
		if (handle.partyGeneration() != generation) return false;
		Entry entry = entries.get(handle.key());
		if (entry == null || entry.token != handle.token() || entry.state != State.PENDING) return false;
		if (stats != null) {
			entry.state = State.SUCCESS;
			entry.stats = stats;
			if (stats.uuid() != null) entry.uuid = stats.uuid();
			entry.error = null;
		} else {
			entry.state = State.UNAVAILABLE;
			entry.stats = null;
			entry.error = error == null || error.isBlank() ? "unavailable" : error;
			entry.unavailableReported = false;
		}
		return true;
	}

	public AttemptView get(long partyGeneration, PartyMember member) {
		if (member == null || member.name() == null) return null;
		syncGeneration(partyGeneration);
		Entry entry = entries.get(key(member.name()));
		if (entry == null || !compatible(entry.uuid, member.uuid())) return null;
		if (entry.uuid == null && member.uuid() != null) entry.uuid = member.uuid();
		return view(entry, 0);
	}

	/** Clears state for a departed membership so a later rejoin is treated as a new member. */
	public boolean forgetMember(long partyGeneration, String playerName) {
		if (playerName == null || playerName.isBlank()) return false;
		syncGeneration(partyGeneration);
		return entries.remove(key(playerName)) != null;
	}

	/** Ensures one unavailable card per failed attempt, including failures hidden by a list refresh. */
	public boolean markUnavailableReported(long partyGeneration, PartyMember member) {
		if (member == null || member.name() == null) return false;
		syncGeneration(partyGeneration);
		Entry entry = entries.get(key(member.name()));
		if (entry == null || entry.state != State.UNAVAILABLE || !compatible(entry.uuid, member.uuid())
			|| entry.unavailableReported) return false;
		entry.unavailableReported = true;
		return true;
	}

	public void reset(long partyGeneration) {
		generation = partyGeneration;
		nextToken = 0;
		entries.clear();
	}

	private void syncGeneration(long partyGeneration) {
		if (generation != partyGeneration) reset(partyGeneration);
	}

	private static AttemptView view(Entry entry, long retryTicks) {
		return new AttemptView(entry.name, entry.uuid, entry.state, entry.stats, entry.error, retryTicks);
	}

	private static boolean compatible(UUID stored, UUID current) {
		return stored == null || current == null || stored.equals(current);
	}

	private static String key(String name) {
		return name.trim().toLowerCase(Locale.ROOT);
	}

	private static final class Entry {
		final String name;
		UUID uuid;
		State state = State.PENDING;
		DungeonStats stats;
		String error;
		long token;
		long lastManualRetryTick = Long.MIN_VALUE;
		boolean unavailableReported;

		Entry(String name, UUID uuid, long token) {
			this.name = name;
			this.uuid = uuid;
			this.token = token;
		}
	}

	public enum State {
		PENDING,
		SUCCESS,
		UNAVAILABLE
	}

	public enum RetryBlockReason {
		NOT_TRACKED,
		PENDING,
		NOT_RETRYABLE,
		COOLDOWN
	}

	public record RequestHandle(long partyGeneration, String key, long token) {
	}

	public record BeginResult(RequestHandle handle, AttemptView attempt) {
		public boolean started() { return handle != null; }
	}

	public record RetryResult(RequestHandle handle, AttemptView attempt, RetryBlockReason blockReason,
		long cooldownTicksRemaining) {
		public boolean started() { return handle != null; }
	}

	public record AttemptView(String name, UUID uuid, State state, DungeonStats stats, String error,
		long retryTicksRemaining) {
	}
}
