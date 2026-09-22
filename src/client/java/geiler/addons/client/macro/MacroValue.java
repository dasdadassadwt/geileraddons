package geiler.addons.client.macro;

import java.util.Locale;
import java.util.Map;

/** A typed literal or a reference to a local or globally shared macro variable. */
public record MacroValue(Type type, boolean variableReference, String value, Scope scope) {
	public enum Type { NUMBER, TEXT, BOOLEAN }
	public enum Scope { NONE, LOCAL, GLOBAL }

	/** Keeps the pre-global-variable constructor source and config format compatible. */
	public MacroValue(Type type, boolean variableReference, String value) {
		this(type, variableReference, value, variableReference ? Scope.LOCAL : Scope.NONE);
	}

	public MacroValue {
		if (type == null) type = Type.TEXT;
		value = value == null ? "" : value.substring(0, Math.min(256, value.length()));
		if (!variableReference) scope = Scope.NONE;
		else if (scope == null || scope == Scope.NONE) scope = Scope.LOCAL;
	}

	public static MacroValue literal(Type type, String value) { return new MacroValue(type, false, value); }
	public static MacroValue variable(Type type, String name) { return new MacroValue(type, true, name); }
	/** References a global variable by stable id, so renaming it cannot break saved blocks. */
	public static MacroValue globalVariable(Type type, String id) {
		return new MacroValue(type, true, id, Scope.GLOBAL);
	}

	public Object resolve(Map<String, Object> variables) {
		return resolve(variables, null);
	}

	public Object resolve(Map<String, Object> variables, MacroVariableStore globals) {
		String raw = value;
		if (variableReference) {
			Object found = scope == Scope.GLOBAL
				? globals == null ? null : globals.value(value)
				: variables == null ? null : variables.get(value);
			if (found == null) return defaultValue(type);
			raw = found.toString();
		}
		return switch (type) {
			case NUMBER -> parseNumber(raw);
			case BOOLEAN -> Boolean.parseBoolean(raw);
			case TEXT -> raw;
		};
	}

	public static Object defaultValue(Type type) {
		return switch (type == null ? Type.TEXT : type) {
			case NUMBER -> 0.0d;
			case BOOLEAN -> false;
			case TEXT -> "";
		};
	}

	private static double parseNumber(String value) {
		try {
			double parsed = Double.parseDouble(value == null ? "" : value.trim());
			return Double.isFinite(parsed) ? parsed : 0;
		} catch (NumberFormatException ignored) {
			return 0;
		}
	}
}
