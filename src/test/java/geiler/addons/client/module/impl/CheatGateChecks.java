package geiler.addons.client.module.impl;

import geiler.addons.client.module.BooleanSetting;

/** Offline checks that formerly gate-controlled settings are now direct user choices. */
public final class CheatGateChecks {
	private CheatGateChecks() { }

	public static void run() {
		for (BooleanSetting setting : GeneralModule.INSTANCE.booleanSettings()) {
			if ("Cheats".equals(setting.name())) throw new AssertionError("General still exposes the removed Cheats gate");
		}
		BooleanSetting depthCheck = new BooleanSetting("Depth Check", true);
		if (!depthCheck.value()) throw new AssertionError("depth check should retain its module default");
		depthCheck.setValue(false);
		if (depthCheck.value()) throw new AssertionError("depth check must honor the user's direct choice");
	}
}
