package geiler.addons.client.macro;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** User-authored reusable block stack. Each call receives an isolated variable frame. */
public final class MacroFunction {
	private final String id;
	private String name = "My Block";
	private final List<Parameter> parameters = new ArrayList<>();
	private final List<MacroStep> steps = new ArrayList<>();
	private float canvasX;
	private float canvasY;

	public MacroFunction() { this(UUID.randomUUID().toString()); }
	public MacroFunction(String id) { this.id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id; }
	public String id() { return id; }
	public String name() { return name; }
	public void setName(String value) {
		String clean = value == null ? "" : value.strip();
		name = clean.isEmpty() ? "My Block" : clean.substring(0, Math.min(48, clean.length()));
	}
	public List<Parameter> parameters() { return parameters; }
	public List<MacroStep> steps() { return steps; }

	/** Builds isolated, type-coerced locals for one function invocation. */
	public Map<String, Object> bindArguments(List<MacroValue> arguments, Map<String, Object> callerVariables) {
		return bindArguments(arguments, callerVariables, null);
	}

	/** Builds function locals while allowing arguments to read the shared global-variable store. */
	public Map<String, Object> bindArguments(List<MacroValue> arguments, Map<String, Object> callerVariables,
		MacroVariableStore globals) {
		Map<String, Object> locals = new HashMap<>();
		for (int index = 0; index < parameters.size(); index++) {
			Parameter parameter = parameters.get(index);
			if (parameter == null) continue;
			MacroValue argument = arguments != null && index < arguments.size() ? arguments.get(index) : null;
			if (argument == null) argument = MacroValue.literal(parameter.type(), parameter.defaultValue());
			MacroValue typedArgument = new MacroValue(parameter.type(), argument.variableReference(),
				argument.value(), argument.scope());
			locals.put(parameter.name(), typedArgument.resolve(callerVariables, globals));
		}
		return locals;
	}

	public float canvasX() { return canvasX; }
	public float canvasY() { return canvasY; }
	public void setCanvasPosition(float x, float y) {
		canvasX = Float.isFinite(x) ? Math.max(-100_000, Math.min(100_000, x)) : 0;
		canvasY = Float.isFinite(y) ? Math.max(-100_000, Math.min(100_000, y)) : 0;
	}

	public record Parameter(String name, MacroValue.Type type, String defaultValue) {
		public Parameter {
			name = name == null ? "value" : name.strip().substring(0, Math.min(32, name.strip().length()));
			if (name.isEmpty()) name = "value";
			if (type == null) type = MacroValue.Type.TEXT;
			defaultValue = defaultValue == null ? "" : defaultValue.substring(0, Math.min(128, defaultValue.length()));
		}
	}
}
