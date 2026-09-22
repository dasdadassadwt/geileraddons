package geiler.addons.client.module.impl;

/** Small, client-independent eligibility rules for inventory macro buttons. */
public final class InventoryButtonRules {
	private InventoryButtonRules() { }

	public static Eligibility evaluate(boolean macroSystemActive, boolean macroExists,
		boolean macroEnabled, boolean hasSteps, boolean triggerContextAllowed, boolean islandAllowed) {
		if (!macroSystemActive) return new Eligibility(false, "Macro system is disabled");
		if (!macroExists) return new Eligibility(false, "Assigned macro no longer exists");
		if (!macroEnabled) return new Eligibility(false, "Macro is disabled");
		if (!hasSteps) return new Eligibility(false, "Macro has no steps");
		if (!triggerContextAllowed) return new Eligibility(false, "Trigger context does not allow inventory screens");
		if (!islandAllowed) return new Eligibility(false, "Island filter does not allow this island");
		return new Eligibility(true, "Ready");
	}

	public record Eligibility(boolean eligible, String reason) { }
}
