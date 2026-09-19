package geiler.addons.client.module.impl;

import geiler.addons.GeilerAddons;
import geiler.addons.client.dungeon.DungeonClass;
import geiler.addons.client.dungeon.DungeonFloor;
import geiler.addons.client.dungeon.DungeonStats;
import geiler.addons.client.dungeon.DungeonStatsService;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.module.TextSetting;
import geiler.addons.client.party.PartyListBackend;
import geiler.addons.client.party.PartyMember;
import geiler.addons.client.party.PartySnapshot;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** Per-floor Party Finder requirements and optional leader-only enforcement. */
public final class AutoKickModule extends Module {
	public static final AutoKickModule INSTANCE = new AutoKickModule();
	private static final long MIN_DELAY_MILLIS = 1_000;
	private static final long MAX_DELAY_MILLIS = 2_000;
	private static final int CHAT_NOTICE_GAP_TICKS = 5;

	private final EnumMap<DungeonFloor, FloorPolicy> policies;
	private final BooleanSetting debug;
	private final NumberSetting profileRetryWindow;
	private final NumberSetting profileRetryInterval;
	private final Set<String> handled = new HashSet<>();
	private final Map<String, ProfileRetry> profileRetries = new HashMap<>();
	private final Set<String> manualFallbacks = new HashSet<>();
	private final List<PendingKick> pendingKicks = new ArrayList<>();
	private final Deque<Component> pendingNotices = new ArrayDeque<>();
	private long markerGeneration = Long.MIN_VALUE;
	private int noticeCooldown;

	private AutoKickModule() {
		this(new Settings());
	}

	private AutoKickModule(Settings settings) {
		super("Auto Kick", "Checks Party Finder requirements.", Category.F7,
			settings.allSettings().toArray(Setting[]::new));
		policies = settings.policies;
		debug = settings.debug;
		profileRetryWindow = settings.profileRetryWindow;
		profileRetryInterval = settings.profileRetryInterval;
		group(
			SettingGroup.debug("Diagnostics", settings.debug),
			new SettingGroup("Safety", settings.profileRetryWindow, settings.profileRetryInterval),
			new SettingGroup("Normal")
				.containing(settings.normal.stream().map(FloorPolicy::group).toArray(SettingGroup[]::new)),
			new SettingGroup("Master")
				.containing(settings.master.stream().map(FloorPolicy::group).toArray(SettingGroup[]::new))
		);
	}

	public boolean wantsData() {
		return isEnabled() && policies.values().stream().anyMatch(policy -> policy.autoKick.value());
	}

	public void onStats(DungeonFloor floor, DungeonStats stats) {
		onStats(floor, PartyListBackend.snapshot().generation(), stats);
	}

	public void onStats(DungeonFloor floor, long expectedGeneration, DungeonStats stats) {
		if (stats == null) return;
		if (!isEnabled()) {
			debug("Ignoring stats for %s because Auto Kick is disabled", stats.name());
			return;
		}
		if (floor == null) {
			debug("Cannot evaluate %s: the queued dungeon floor is unknown", stats.name());
			return;
		}
		PartySnapshot snapshot = PartyListBackend.snapshot();
		syncGeneration(snapshot.generation());
		if (expectedGeneration >= 0 && snapshot.generation() != expectedGeneration) {
			debug("Ignoring stats for %s because party generation changed while it was loading", stats.name());
			return;
		}
		PartyMember member = findMember(snapshot, stats.name());
		if (member == null) {
			debug("Cannot evaluate %s: they are no longer in the tracked party", stats.name());
			return;
		}
		if (!snapshot.isLeader(localName())) {
			debug("Cannot evaluate %s: local player is not party leader (leader=%s)", stats.name(), snapshot.leaderName());
			return;
		}
		FloorPolicy policy = policies.get(floor);
		if (policy == null || !policy.autoKick.value()) {
			debug("Skipping %s: the %s policy is disabled", member.name(), floor.displayName());
			return;
		}
		String actionKey = actionKey(snapshot.generation(), floor, member);
		if (!handled.add(actionKey)) {
			debug("Already evaluated %s for %s in party generation %d", member.name(), floor.displayName(), snapshot.generation());
			return;
		}

		debug("Using %s policy for %s: mode=%s; checks=%s", floor.displayName(), member.name(),
			policy.askBeforeKick.value() ? "ASK BEFORE KICK" : "AUTOMATIC", policy.describeChecks());
		// Unknown profile fields are never failures. Remove the provisional handled marker and retry
		// with a fresh profile for a bounded period before asking the user to decide manually.
		handled.remove(actionKey);
		Evaluation evaluation = policy.evaluate(member, snapshot, stats, floor);
		debug("Evaluation for %s on %s: %s", member.name(), floor.displayName(),
			evaluation.hasUnknown() ? "waiting for " + String.join(", ", evaluation.unknowns)
				: evaluation.hasAction() ? String.join(", ", evaluation.failures) : "passed");
		if (evaluation.hasUnknown()) {
			scheduleProfileRetry(actionKey, floor, snapshot.generation(), member.name(), evaluation.unknowns, null);
			return;
		}
		profileRetries.remove(actionKey);
		if (!evaluation.hasAction()) {
			handled.add(actionKey);
			return;
		}
		handled.add(actionKey);
		if (policy.askBeforeKick.value()) {
			debug("Asking before kicking %s because manual confirmation is enabled for %s",
				member.name(), floor.displayName());
			Component message = Component.literal("[Auto Kick] ").withStyle(ChatFormatting.YELLOW)
				.append(Component.literal(member.name() + " failed: " + String.join(", ", evaluation.failures) + " ")
					.withStyle(ChatFormatting.GRAY))
				.append(Component.literal("[Kick]").withStyle(style -> style.withColor(ChatFormatting.RED)
					.withUnderlined(true)
					.withClickEvent(new ClickEvent.RunCommand("/party kick " + member.name()))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal("/party kick " + member.name())))));
			queueNotice(message);
		} else {
			scheduleKick(floor, snapshot.generation(), member.name(), evaluation.failures);
			queueNotice(Component.literal("[Auto Kick] ").withStyle(ChatFormatting.RED)
				.append(Component.literal(member.name() + " failed: " + String.join(", ", evaluation.failures) + " (scheduled)")
					.withStyle(ChatFormatting.GRAY)));
		}
	}

	/** Starts the same bounded retry path when no profile object was returned at all. */
	public void onStatsUnavailable(DungeonFloor floor, long generation, String name, String error) {
		if (floor == null || name == null || name.isBlank() || !isEnabled()) return;
		PartySnapshot snapshot = PartyListBackend.snapshot();
		syncGeneration(snapshot.generation());
		if (snapshot.generation() != generation || !snapshot.isLeader(localName())) return;
		PartyMember member = findMember(snapshot, name);
		if (member == null) return;
		FloorPolicy policy = policies.get(floor);
		if (policy == null || !policy.autoKick.value()) return;
		String key = actionKey(generation, floor, member);
		scheduleProfileRetry(key, floor, generation, name, List.of("profile data"), error);
	}

	public void tick() {
		tickNotices();
		syncGeneration(PartyListBackend.snapshot().generation());
		if (!isEnabled()) return;
		tickProfileRetries();
		if (pendingKicks.isEmpty()) return;
		long now = System.currentTimeMillis();
		Iterator<PendingKick> iterator = pendingKicks.iterator();
		while (iterator.hasNext()) {
			PendingKick pending = iterator.next();
			if (!stillAllowed(pending)) {
				debug("Cancelled scheduled kick for %s because party state or leadership changed", pending.name);
				iterator.remove();
				continue;
			}
			Minecraft mc = Minecraft.getInstance();
			if (!pending.messageSent && now >= pending.messageAt) {
				mc.player.connection.sendCommand("pc " + pending.partyMessage);
				pending.messageSent = true;
				debug("Sent party reason for %s; kick follows in %d ms", pending.name, pending.kickAt - now);
			}
			if (pending.messageSent && now >= pending.kickAt) {
				mc.player.connection.sendCommand("party kick " + pending.name);
				debug("Sent delayed party kick for %s", pending.name);
				iterator.remove();
			}
		}
	}

	@Override
	protected void onDisable() {
		pendingKicks.clear();
		pendingNotices.clear();
		noticeCooldown = 0;
		handled.clear();
		profileRetries.clear();
		manualFallbacks.clear();
		markerGeneration = Long.MIN_VALUE;
	}

	/** Evaluation markers are meaningful only inside one party generation. */
	private void syncGeneration(long generation) {
		if (markerGeneration == generation) return;
		markerGeneration = generation;
		handled.clear();
		profileRetries.clear();
		manualFallbacks.clear();
	}

	private void tickProfileRetries() {
		if (profileRetries.isEmpty()) return;
		long now = System.currentTimeMillis();
		for (ProfileRetry retry : new ArrayList<>(profileRetries.values())) {
			if (profileRetries.get(retry.key) != retry) continue;
			PartySnapshot snapshot = PartyListBackend.snapshot();
			FloorPolicy policy = policies.get(retry.floor);
			PartyMember member = findMember(snapshot, retry.name);
			if (snapshot.generation() != retry.generation || !snapshot.isLeader(localName())
				|| member == null || !actionKey(retry.generation, retry.floor, member).equals(retry.key)
				|| policy == null || !policy.autoKick.value()) {
				profileRetries.remove(retry.key);
				continue;
			}
			long window = profileRetryWindowMillis();
			if (now - retry.startedAt >= window) {
				profileRetries.remove(retry.key);
				if (manualFallbacks.add(retry.key)) queueManualFallback(retry);
				continue;
			}
			if (retry.inFlight || now < retry.nextAttemptAt) continue;
			retry.inFlight = true;
			retry.attempts++;
			retry.nextAttemptAt = now + profileRetryIntervalMillis();
			DungeonStatsService.fetchFresh(retry.name, result -> {
				ProfileRetry current = profileRetries.get(retry.key);
				if (current != retry) return;
				retry.inFlight = false;
				// A response that crossed the retry deadline is still useful for the next
				// manual decision, but must never turn an expired safety window into an
				// automatic kick.
				if (System.currentTimeMillis() - retry.startedAt >= profileRetryWindowMillis()) {
					profileRetries.remove(retry.key);
					if (manualFallbacks.add(retry.key)) queueManualFallback(retry);
					return;
				}
				if (!result.available()) {
					retry.lastError = result.error();
					debug("Profile retry %d for %s failed: %s", retry.attempts, retry.name, retry.lastError);
					return;
				}
				onStats(retry.floor, retry.generation, result.stats());
			});
		}
	}

	private void scheduleProfileRetry(String key, DungeonFloor floor, long generation, String name,
		List<String> unknowns, String error) {
		long now = System.currentTimeMillis();
		ProfileRetry retry = profileRetries.get(key);
		if (retry == null) {
			retry = new ProfileRetry(key, floor, generation, name, now);
			profileRetries.put(key, retry);
		}
		retry.unknowns = List.copyOf(unknowns == null || unknowns.isEmpty() ? List.of("profile data") : unknowns);
		if (error != null && !error.isBlank()) retry.lastError = error;
		retry.inFlight = false;
		retry.nextAttemptAt = Math.min(retry.nextAttemptAt, now + profileRetryIntervalMillis());
		debug("Delaying enforcement for %s; retrying incomplete profile (%s)", name,
			String.join(", ", retry.unknowns));
	}

	private void queueManualFallback(ProfileRetry retry) {
		String reason = String.join(", ", retry.unknowns);
		String suffix = retry.lastError == null || retry.lastError.isBlank() ? "" : " (" + retry.lastError + ")";
		Component message = Component.literal("[Auto Kick] ").withStyle(ChatFormatting.YELLOW)
			.append(Component.literal(retry.name + " could not be verified after "
				+ Math.round(profileRetryWindowMillis() / 1000.0) + "s; automatic kick skipped. "
				+ "Missing: " + reason + suffix + ". Choose Kick or manually ban/ignore them: ")
				.withStyle(ChatFormatting.GRAY))
			.append(kickAction(retry.name));
		queueNotice(message);
	}

	private static String actionKey(long generation, DungeonFloor floor, PartyMember member) {
		String uuid = member.uuid() == null ? "?" : member.uuid().toString();
		String dungeonClass = member.dungeonClass() == null ? "?" : member.dungeonClass().name();
		return generation + ":" + floor.name() + ":" + member.name().toLowerCase(Locale.ROOT)
			+ ":" + uuid + ":" + dungeonClass;
	}

	private long profileRetryWindowMillis() {
		return Math.max(1, profileRetryWindow.intValue()) * 1000L;
	}

	private long profileRetryIntervalMillis() {
		return Math.max(1, profileRetryInterval.intValue()) * 1000L;
	}

	private static Component kickAction(String name) {
		String command = "/party kick " + name;
		return Component.literal("[Kick]").withStyle(style -> style.withColor(ChatFormatting.RED)
			.withUnderlined(true).withClickEvent(new ClickEvent.RunCommand(command))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
	}

	private void scheduleKick(DungeonFloor floor, long generation, String name, List<String> failures) {
		long messageDelay = randomDelay();
		long kickDelay = randomDelay();
		long now = System.currentTimeMillis();
		long queueAfter = pendingKicks.stream().mapToLong(pending -> pending.kickAt).max().orElse(now);
		long startAt = Math.max(now, queueAfter);
		String reasons = String.join(", ", failures);
		String partyMessage = limitChat(name + " will be kicked for: " + reasons);
		pendingKicks.add(new PendingKick(floor, generation, name, partyMessage, startAt + messageDelay,
			startAt + messageDelay + kickDelay));
		debug("Scheduled %s after the existing action queue: party reason in %d ms, kick %d ms later",
			name, startAt - now + messageDelay, kickDelay);
	}

	private boolean stillAllowed(PendingKick pending) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.player.connection == null) return false;
		PartySnapshot snapshot = PartyListBackend.snapshot();
		FloorPolicy policy = policies.get(pending.floor);
		return snapshot.generation() == pending.generation && snapshot.isLeader(localName())
			&& findMember(snapshot, pending.name) != null && policy != null
			&& policy.autoKick.value() && !policy.askBeforeKick.value();
	}

	private static long randomDelay() {
		return ThreadLocalRandom.current().nextLong(MIN_DELAY_MILLIS, MAX_DELAY_MILLIS + 1);
	}

	private static String limitChat(String message) {
		return message.length() <= 220 ? message : message.substring(0, 217) + "...";
	}

	private static final class PendingKick {
		final DungeonFloor floor;
		final long generation;
		final String name;
		final String partyMessage;
		final long messageAt;
		final long kickAt;
		boolean messageSent;

		PendingKick(DungeonFloor floor, long generation, String name, String partyMessage, long messageAt, long kickAt) {
			this.floor = floor;
			this.generation = generation;
			this.name = name;
			this.partyMessage = partyMessage;
			this.messageAt = messageAt;
			this.kickAt = kickAt;
		}
	}

	private static PartyMember findMember(PartySnapshot snapshot, String name) {
		for (PartyMember member : snapshot.members()) {
			if (member.name().equalsIgnoreCase(name)) return member;
		}
		return null;
	}

	private static String localName() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player == null ? null : mc.player.getGameProfile().name();
	}

	private void queueNotice(Component message) {
		if (message == null) return;
		if (pendingNotices.size() >= 16) pendingNotices.removeFirst();
		if (pendingNotices.isEmpty() && noticeCooldown == 0) noticeCooldown = CHAT_NOTICE_GAP_TICKS;
		pendingNotices.addLast(message);
	}

	private void tickNotices() {
		if (noticeCooldown > 0) noticeCooldown--;
		if (noticeCooldown > 0 || pendingNotices.isEmpty()) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui == null) return;
		mc.gui.getChat().addClientSystemMessage(pendingNotices.removeFirst());
		noticeCooldown = CHAT_NOTICE_GAP_TICKS;
	}

	private static final class Evaluation {
		final List<String> failures = new ArrayList<>();
		final List<String> unknowns = new ArrayList<>();
		boolean hasAction() { return !failures.isEmpty(); }
		boolean hasUnknown() { return !unknowns.isEmpty(); }
	}

	private static final class ProfileRetry {
		final String key;
		final DungeonFloor floor;
		final long generation;
		final String name;
		final long startedAt;
		long nextAttemptAt;
		int attempts;
		boolean inFlight;
		String lastError;
		List<String> unknowns = List.of("profile data");

		ProfileRetry(String key, DungeonFloor floor, long generation, String name, long startedAt) {
			this.key = key;
			this.floor = floor;
			this.generation = generation;
			this.name = name;
			this.startedAt = startedAt;
			this.nextAttemptAt = startedAt;
		}
	}

	private static final class Settings {
		final BooleanSetting debug = BooleanSetting.debug("Debug", false);
		final NumberSetting profileRetryWindow = new NumberSetting("Profile Retry Window", "Retry Window (s)", 1, 60, 15, true);
		final NumberSetting profileRetryInterval = new NumberSetting("Profile Retry Interval", "Retry Every (s)", 1, 10, 2, true);
		final EnumMap<DungeonFloor, FloorPolicy> policies = new EnumMap<>(DungeonFloor.class);
		final List<FloorPolicy> normal = new ArrayList<>();
		final List<FloorPolicy> master = new ArrayList<>();

		Settings() {
			for (DungeonFloor floor : DungeonFloor.values()) {
				FloorPolicy policy = new FloorPolicy(floor);
				policies.put(floor, policy);
				(floor.master() ? master : normal).add(policy);
			}
		}

		List<Setting> allSettings() {
			List<Setting> settings = new ArrayList<>();
			settings.add(debug);
			settings.add(profileRetryWindow);
			settings.add(profileRetryInterval);
			for (FloorPolicy policy : policies.values()) settings.addAll(policy.settings());
			return settings;
		}
	}

	private static final class FloorPolicy {
		final DungeonFloor floor;
		final BooleanSetting autoKick;
		final BooleanSetting askBeforeKick;
		final BooleanSetting dupeCheck;
		final TextSetting minCata;
		final TextSetting minClass;
		final TextSetting minClassAverage;
		final TextSetting minSecrets;
		final TextSetting minSecretAverage;
		final TextSetting minMagicalPower;
		final TextSetting personalBestLimit;
		final TextSetting minBank;
		final BooleanSetting terminator;
		final BooleanSetting hyperion;
		final BooleanSetting goldenDragon;

		FloorPolicy(DungeonFloor floor) {
			this.floor = floor;
			String prefix = floor.displayName() + " ";
			autoKick = new BooleanSetting(prefix + "Auto Kick", "Auto Kick", false);
			askBeforeKick = new BooleanSetting(prefix + "Ask Before", "Ask Before", false);
			dupeCheck = new BooleanSetting(prefix + "Dupe", "Dupe", false);
			minCata = new TextSetting(prefix + "Cata", "Min Cata Level", "0", 6);
			minClass = new TextSetting(prefix + "Class", "Min Class Level", "0", 6);
			minClassAverage = new TextSetting(prefix + "Class Avg", "Min Class Avg", "0", 8);
			minSecrets = new TextSetting(prefix + "Secrets", "Min Secrets", "0", 12);
			minSecretAverage = new TextSetting(prefix + "Secret Avg", "Min Secret Avg", "0", 8);
			minMagicalPower = new TextSetting(prefix + "MP", "Min MP", "0", 8);
			personalBestLimit = new TextSetting(prefix + "Minimum PB", "Minimum PB", "0", 8);
			minBank = new TextSetting(prefix + "Bank", "Min Bank", "0", 14);
			terminator = new BooleanSetting(prefix + "Terminator", "Terminator", false);
			hyperion = new BooleanSetting(prefix + "Hyperion", "Hyperion", false);
			goldenDragon = new BooleanSetting(prefix + "GDrag", "GDrag", false);
		}

		List<Setting> settings() {
			return List.of(autoKick, askBeforeKick, dupeCheck, minCata, minClass, minClassAverage, minSecrets,
				minSecretAverage, minMagicalPower, personalBestLimit, minBank, terminator, hyperion, goldenDragon);
		}

		SettingGroup group() {
			return SettingGroup.switchedFolded(floor.displayName(), autoKick, askBeforeKick, dupeCheck, minCata, minClass,
				minClassAverage, minSecrets, minSecretAverage, minMagicalPower, personalBestLimit,
				minBank, terminator, hyperion, goldenDragon);
		}

		Evaluation evaluate(PartyMember member, PartySnapshot snapshot, DungeonStats stats, DungeonFloor floor) {
			Evaluation result = new Evaluation();
			int requiredCata = positive(minCata.intValue(0));
			int requiredClass = positive(minClass.intValue(0));
			double requiredClassAverage = positive(minClassAverage.doubleValue(0));
			long requiredSecrets = positive(minSecrets.longValue(0));
			double requiredSecretAverage = positive(minSecretAverage.doubleValue(0));
			int requiredMagicalPower = positive(minMagicalPower.intValue(0));
			int requiredPersonalBest = positive(personalBestLimit.intValue(0));
			if (requiredCata > 0 && !stats.has(DungeonStats.DataField.CATACOMBS_LEVEL)) result.unknowns.add("Cata");
			else if (requiredCata > 0 && stats.catacombsLevel() < requiredCata) result.failures.add("Cata " + stats.catacombsLevel() + "/" + requiredCata);
			DungeonClass dungeonClass = member.dungeonClass() != null ? member.dungeonClass() : stats.selectedClass();
			if (dupeCheck.value() && dungeonClass == null) result.unknowns.add("Class");
			if (dupeCheck.value() && dungeonClass != null) {
				for (PartyMember other : snapshot.members()) {
					if (other.name().equalsIgnoreCase(member.name())) continue;
					if (other.dungeonClass() == null) {
						if (!result.unknowns.contains("Party classes")) result.unknowns.add("Party classes");
						continue;
					}
					if (dungeonClass == other.dungeonClass()) {
						result.failures.add("duplicate " + dungeonClass.displayName());
						break;
					}
				}
			}
			if (requiredClass > 0 && dungeonClass == null) result.unknowns.add("Class");
			else if (requiredClass > 0 && !stats.hasClassLevel(dungeonClass)) result.unknowns.add(dungeonClass.displayName());
			else if (requiredClass > 0 && stats.classLevel(dungeonClass) < requiredClass) result.failures.add(dungeonClass.displayName() + " " + stats.classLevel(dungeonClass) + "/" + requiredClass);
			if (requiredClassAverage > 0 && !stats.has(DungeonStats.DataField.CLASS_AVERAGE)) result.unknowns.add("CA");
			else if (requiredClassAverage > 0 && stats.classAverage() < requiredClassAverage) result.failures.add("CA " + format(stats.classAverage()) + "/" + format(requiredClassAverage));
			if (requiredSecrets > 0 && !stats.has(DungeonStats.DataField.SECRETS)) result.unknowns.add("Secrets");
			else if (requiredSecrets > 0 && stats.totalSecrets() < requiredSecrets) result.failures.add("Secrets " + stats.totalSecrets() + "/" + requiredSecrets);
			if (requiredSecretAverage > 0 && (!stats.has(DungeonStats.DataField.SECRETS) || !stats.has(DungeonStats.DataField.RUNS))) result.unknowns.add("SA");
			else if (requiredSecretAverage > 0 && stats.secretAverage() < requiredSecretAverage) result.failures.add("SA " + format(stats.secretAverage()) + "/" + format(requiredSecretAverage));
			if (requiredMagicalPower > 0 && !stats.has(DungeonStats.DataField.MAGICAL_POWER)) result.unknowns.add("MP");
			else if (requiredMagicalPower > 0 && stats.magicalPower() < requiredMagicalPower) result.failures.add("MP " + stats.magicalPower() + "/" + requiredMagicalPower);
			if (requiredPersonalBest > 0 && !stats.hasPersonalBest(floor)) result.unknowns.add("PB");
			else if (requiredPersonalBest > 0 && !AutoKickRules.personalBestPasses(stats.fastestSPlusSeconds(floor), requiredPersonalBest)) result.failures.add("PB " + DungeonStatsService.formatTime(stats.fastestSPlusSeconds(floor)) + "/" + DungeonStatsService.formatTime(requiredPersonalBest));
			long bank = minBank.longValue(0);
			if (bank > 0 && !stats.has(DungeonStats.DataField.BANK)) result.unknowns.add("Bank");
			else if (bank > 0 && stats.bank() < bank) result.failures.add("bank " + stats.bank());
			if (terminator.value() && !stats.hasGearData(DungeonStats.Gear.TERMINATOR)) result.unknowns.add("Term");
			else if (terminator.value() && !stats.has(DungeonStats.Gear.TERMINATOR)) result.failures.add("no Term");
			if (hyperion.value() && !stats.hasGearData(DungeonStats.Gear.HYPERION)) result.unknowns.add("Hype");
			else if (hyperion.value() && !stats.has(DungeonStats.Gear.HYPERION)) result.failures.add("no Hype");
			if (goldenDragon.value() && !stats.hasGearData(DungeonStats.Gear.GOLDEN_DRAGON)) result.unknowns.add("GDrag");
			else if (goldenDragon.value() && !stats.has(DungeonStats.Gear.GOLDEN_DRAGON)) result.failures.add("no GDrag");
			return result;
		}

		String describeChecks() {
			List<String> checks = new ArrayList<>();
			int cata = positive(minCata.intValue(0));
			int joinedClass = positive(minClass.intValue(0));
			double classAverage = positive(minClassAverage.doubleValue(0));
			long secrets = positive(minSecrets.longValue(0));
			double secretAverage = positive(minSecretAverage.doubleValue(0));
			int magicalPower = positive(minMagicalPower.intValue(0));
			int personalBest = positive(personalBestLimit.intValue(0));
			long bank = positive(minBank.longValue(0));
			if (cata > 0) checks.add("Cata >= " + cata);
			if (joinedClass > 0) checks.add("class >= " + joinedClass);
			if (classAverage > 0) checks.add("CA >= " + format(classAverage));
			if (secrets > 0) checks.add("secrets >= " + secrets);
			if (secretAverage > 0) checks.add("SA >= " + format(secretAverage));
			if (magicalPower > 0) checks.add("MP >= " + magicalPower);
			if (personalBest > 0) checks.add("PB <= " + DungeonStatsService.formatTime(personalBest));
			if (bank > 0) checks.add("bank >= " + bank);
			if (dupeCheck.value()) checks.add("no class dupe");
			if (terminator.value()) checks.add("Term");
			if (hyperion.value()) checks.add("Hype");
			if (goldenDragon.value()) checks.add("GDrag");
			return checks.isEmpty() ? "none configured" : String.join(", ", checks);
		}

		private static String format(double value) { return String.format(Locale.ROOT, "%.2f", value); }
		private static int positive(int value) { return Math.max(0, value); }
		private static long positive(long value) { return Math.max(0, value); }
		private static double positive(double value) { return Math.max(0, value); }
	}

	private void debug(String format, Object... arguments) {
		String message = String.format(Locale.ROOT, format, arguments);
		GeilerAddons.LOGGER.debug("[Auto Kick] {}", message);
		if (!debug.value()) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui != null) {
			mc.gui.getChat().addClientSystemMessage(Component.literal("[Auto Kick Debug] ").withStyle(ChatFormatting.AQUA)
				.append(Component.literal(message).withStyle(ChatFormatting.GRAY)));
		}
	}
}
