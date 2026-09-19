package geiler.addons.client.dungeon;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;

/** Profile data used by Party Finder Stats and Auto Kick. */
public final class DungeonStats {
	/** Individual profile facts that can be unavailable even when the profile object exists. */
	public enum DataField {
		CATACOMBS_LEVEL,
		SELECTED_CLASS,
		CLASS_AVERAGE,
		SECRETS,
		RUNS,
		MAGICAL_POWER,
		BANK,
		GEAR
	}

	private final String name;
	private final UUID uuid;
	private final int catacombsLevel;
	private final DungeonClass selectedClass;
	private final Map<DungeonClass, Integer> classLevels;
	private final double classAverage;
	private final long totalSecrets;
	private final long totalRuns;
	private final double secretAverage;
	private final int magicalPower;
	private final long bank;
	private final boolean bankKnown;
	private final EnumSet<Gear> knownGear;
	private final EnumSet<Gear> gear;
	private final Map<DungeonFloor, Long> fastestSPlusSeconds;
	private final EnumSet<DataField> availableFields;

	public enum Gear { TERMINATOR, HYPERION, GOLDEN_DRAGON }

	public DungeonStats(String name, UUID uuid, int catacombsLevel, DungeonClass selectedClass,
		Map<DungeonClass, Integer> classLevels,
		double classAverage, long totalSecrets, long totalRuns, int magicalPower, long bank, boolean bankKnown,
		boolean gearKnown, EnumSet<Gear> gear, Map<DungeonFloor, Long> fastestSPlusSeconds) {
		this(name, uuid, catacombsLevel, selectedClass, classLevels, classAverage, totalSecrets, totalRuns,
			magicalPower, bank, bankKnown, gearKnown ? EnumSet.allOf(Gear.class) : EnumSet.noneOf(Gear.class),
			gear, fastestSPlusSeconds, EnumSet.noneOf(DataField.class));
	}

	public DungeonStats(String name, UUID uuid, int catacombsLevel, DungeonClass selectedClass,
		Map<DungeonClass, Integer> classLevels,
		double classAverage, long totalSecrets, long totalRuns, int magicalPower, long bank, boolean bankKnown,
		EnumSet<Gear> knownGear, EnumSet<Gear> gear, Map<DungeonFloor, Long> fastestSPlusSeconds,
		EnumSet<DataField> availableFields) {
		this.name = name;
		this.uuid = uuid;
		this.catacombsLevel = catacombsLevel;
		this.selectedClass = selectedClass;
		this.classLevels = Map.copyOf(classLevels);
		this.classAverage = classAverage;
		this.totalSecrets = Math.max(0, totalSecrets);
		this.totalRuns = Math.max(0, totalRuns);
		this.secretAverage = this.totalRuns == 0 ? 0 : (double) this.totalSecrets / this.totalRuns;
		this.magicalPower = Math.max(0, magicalPower);
		this.bank = Math.max(0, bank);
		this.bankKnown = bankKnown;
		this.knownGear = knownGear == null ? EnumSet.noneOf(Gear.class) : knownGear.clone();
		this.gear = gear.clone();
		this.fastestSPlusSeconds = Map.copyOf(fastestSPlusSeconds);
		this.availableFields = availableFields.clone();
	}

	public String name() { return name; }
	public UUID uuid() { return uuid; }
	public int catacombsLevel() { return catacombsLevel; }
	public DungeonClass selectedClass() { return selectedClass; }
	public Map<DungeonClass, Integer> classLevels() { return classLevels; }
	public int classLevel(DungeonClass dungeonClass) { return classLevels.getOrDefault(dungeonClass, 0); }
	public double classAverage() { return classAverage; }
	public long totalSecrets() { return totalSecrets; }
	public long totalRuns() { return totalRuns; }
	public double secretAverage() { return secretAverage; }
	public int magicalPower() { return magicalPower; }
	public long bank() { return bank; }
	public boolean bankKnown() { return bankKnown; }
	/** True only when every supported gear check has authoritative inventory/pet evidence. */
	public boolean gearKnown() { return knownGear.size() == Gear.values().length; }
	public boolean hasGearData(Gear item) { return item != null && knownGear.contains(item); }
	public boolean has(Gear item) { return gear.contains(item); }
	public boolean has(DataField field) { return availableFields.contains(field); }
	public boolean hasClassLevel(DungeonClass dungeonClass) {
		return dungeonClass != null && classLevels.containsKey(dungeonClass);
	}
	public boolean hasPersonalBest(DungeonFloor floor) {
		return floor != null && fastestSPlusSeconds.containsKey(floor);
	}
	public boolean cacheable() {
		return has(DataField.CATACOMBS_LEVEL) && has(DataField.CLASS_AVERAGE)
			&& has(DataField.SECRETS) && has(DataField.RUNS) && has(DataField.MAGICAL_POWER)
			&& has(DataField.BANK) && has(DataField.GEAR);
	}

	public long fastestSPlusSeconds(DungeonFloor floor) {
		return fastestSPlusSeconds.getOrDefault(floor, 0L);
	}

	public String selectedClassLine(DungeonClass selected) {
		if (selected == null) return "Class ?";
		return selected.displayName() + " " + classLevel(selected);
	}

	public String allClassLevels() {
		StringBuilder out = new StringBuilder();
		for (DungeonClass dungeonClass : DungeonClass.values()) {
			if (out.length() > 0) out.append('\n');
			out.append(dungeonClass.displayName()).append(' ').append(classLevel(dungeonClass));
		}
		return out.toString();
	}
}
