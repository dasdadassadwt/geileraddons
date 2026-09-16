package geiler.addons.client.enchanting;

/** Renderer-independent state for one experiment board cell. */
public record ExperimentCell(int slotId, String value, int number, boolean revealed,
	boolean highlighted, boolean removed) {
	public ExperimentCell {
		if (slotId < 0) throw new IllegalArgumentException("slotId must be non-negative");
		value = normalize(value);
	}

	public ExperimentCell(int slotId, String value, boolean revealed, boolean highlighted) {
		this(slotId, value, -1, revealed, highlighted, false);
	}

	public static ExperimentCell token(int slotId, String value, boolean highlighted) {
		return new ExperimentCell(slotId, value, -1, true, highlighted, false);
	}

	public static ExperimentCell number(int slotId, int number) {
		return new ExperimentCell(slotId, Integer.toString(number), number, true, false, false);
	}

	public boolean hasValue() {
		return value != null && !value.isBlank();
	}

	public int numericValue() {
		if (number > 0) return number;
		if (!hasValue()) return -1;
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException ignored) {
			return -1;
		}
	}

	private static String normalize(String value) {
		if (value == null) return null;
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
