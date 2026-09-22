package geiler.addons.client.party;

/** Limits {@code /p list} to one bootstrap plus one delayed recovery per party session. */
public final class PartyListRequestGate {
	public static final long RECOVERY_COOLDOWN_TICKS = 20L * 5L;
	public static final int MAX_REQUESTS_PER_SESSION = 2;

	private long generation = Long.MIN_VALUE;
	private long lastRequestTick = Long.MIN_VALUE;
	private int requestCount;

	public boolean shouldRequest(long partyGeneration, long currentTick, boolean stableListKnown) {
		syncGeneration(partyGeneration);
		if (stableListKnown || requestCount >= MAX_REQUESTS_PER_SESSION) return false;
		if (lastRequestTick != Long.MIN_VALUE
			&& currentTick - lastRequestTick < RECOVERY_COOLDOWN_TICKS) return false;
		lastRequestTick = currentTick;
		requestCount++;
		return true;
	}

	public int requestCount(long partyGeneration) {
		syncGeneration(partyGeneration);
		return requestCount;
	}

	private void syncGeneration(long partyGeneration) {
		if (generation == partyGeneration) return;
		generation = partyGeneration;
		lastRequestTick = Long.MIN_VALUE;
		requestCount = 0;
	}
}
