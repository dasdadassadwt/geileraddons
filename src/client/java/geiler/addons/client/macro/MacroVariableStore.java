package geiler.addons.client.macro;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Client-owned definitions and live values for variables shared by every macro run. */
public final class MacroVariableStore {
	public static final int MAX_VARIABLES = 256;
	private static final int MAX_ID_LENGTH = 64;
	private static final int MAX_NAME_LENGTH = 32;

	private final Runnable onConfigChanged;
	private final Map<String, Definition> definitions = new LinkedHashMap<>();
	private final Map<String, Object> values = new LinkedHashMap<>();

	public MacroVariableStore() { this(() -> { }); }

	public MacroVariableStore(Runnable onConfigChanged) {
		this.onConfigChanged = onConfigChanged == null ? () -> { } : onConfigChanged;
	}

	public synchronized List<Definition> definitions() {
		return List.copyOf(definitions.values());
	}

	public synchronized Definition definition(String id) {
		return id == null ? null : definitions.get(id);
	}

	/** Creates a globally shared variable with a stable id and the type's default live value. */
	public synchronized Definition create(String name, MacroValue.Type type) {
		if (definitions.size() >= MAX_VARIABLES) return null;
		String cleanName = cleanName(name);
		if (cleanName.isEmpty() || nameExists(cleanName, null)) return null;
		Definition definition = new Definition(UUID.randomUUID().toString(), cleanName, type, false);
		definitions.put(definition.id(), definition);
		values.put(definition.id(), MacroValue.defaultValue(definition.type()));
		onConfigChanged.run();
		return definition;
	}

	public synchronized boolean remove(String id) {
		if (id == null || definitions.remove(id) == null) return false;
		values.remove(id);
		onConfigChanged.run();
		return true;
	}

	public synchronized boolean rename(String id, String name) {
		Definition current = definition(id);
		String cleanName = cleanName(name);
		if (current == null || cleanName.isEmpty() || nameExists(cleanName, id)) return false;
		definitions.put(id, new Definition(current.id(), cleanName, current.type(), current.persistValue()));
		onConfigChanged.run();
		return true;
	}

	public synchronized boolean setType(String id, MacroValue.Type type) {
		Definition current = definition(id);
		if (current == null) return false;
		MacroValue.Type cleanType = type == null ? MacroValue.Type.TEXT : type;
		if (current.type() == cleanType) return true;
		definitions.put(id, new Definition(current.id(), current.name(), cleanType, current.persistValue()));
		values.put(id, MacroValue.defaultValue(cleanType));
		onConfigChanged.run();
		return true;
	}

	public synchronized boolean setPersistValue(String id, boolean persist) {
		Definition current = definition(id);
		if (current == null) return false;
		if (current.persistValue() != persist) {
			definitions.put(id, new Definition(current.id(), current.name(), current.type(), persist));
			onConfigChanged.run();
		}
		return true;
	}

	public synchronized Object value(String id) {
		Definition definition = definition(id);
		if (definition == null) return null;
		return values.getOrDefault(id, MacroValue.defaultValue(definition.type()));
	}

	/** Stores a type-coerced value; local and imported definitions live only in client memory. */
	public synchronized boolean setValue(String id, Object value) {
		Definition definition = definition(id);
		if (definition == null) return false;
		Object cleanValue = coerce(definition.type(), value);
		Object previous = values.put(id, cleanValue);
		if (definition.persistValue() && !cleanValue.equals(previous)) onConfigChanged.run();
		return true;
	}

	/** Returns only opted-in values, keeping all other runtime values out of local config. */
	public synchronized List<SavedVariable> savedVariables() {
		List<SavedVariable> result = new ArrayList<>(definitions.size());
		for (Definition definition : definitions.values()) {
			String savedValue = definition.persistValue()
				? value(definition.id()).toString() : null;
			result.add(new SavedVariable(definition, savedValue));
		}
		return List.copyOf(result);
	}

	/** Replaces all definitions from local config. Non-persistent values restart at their type defaults. */
	public synchronized void restore(Collection<SavedVariable> saved) {
		definitions.clear();
		values.clear();
		if (saved == null) return;
		for (SavedVariable entry : saved) {
			if (definitions.size() >= MAX_VARIABLES || entry == null || entry.definition() == null) break;
			Definition definition = sanitize(entry.definition());
			if (definition == null || definitions.containsKey(definition.id())) continue;
			if (nameExists(definition.name(), null)) {
				definition = new Definition(definition.id(), uniqueName(definition.name()), definition.type(), definition.persistValue());
			}
			definitions.put(definition.id(), definition);
			Object value = definition.persistValue() && entry.savedValue() != null
				? coerce(definition.type(), entry.savedValue()) : MacroValue.defaultValue(definition.type());
			values.put(definition.id(), value);
		}
	}

	/** Adds package definitions without importing any live values or persistence preference. */
	public synchronized void importDefinitions(Collection<Definition> imported) {
		if (imported == null) return;
		for (Definition candidate : imported) {
			if (definitions.size() >= MAX_VARIABLES) break;
			Definition clean = sanitize(candidate);
			if (clean == null || definitions.containsKey(clean.id())) continue;
			String name = nameExists(clean.name(), null) ? uniqueName(clean.name()) : clean.name();
			Definition definition = new Definition(clean.id(), name, clean.type(), false);
			definitions.put(definition.id(), definition);
			values.put(definition.id(), MacroValue.defaultValue(definition.type()));
			onConfigChanged.run();
		}
	}

	private boolean nameExists(String name, String exceptId) {
		String normalized = name.toLowerCase(Locale.ROOT);
		for (Definition definition : definitions.values()) {
			if (!definition.id().equals(exceptId) && definition.name().toLowerCase(Locale.ROOT).equals(normalized)) return true;
		}
		return false;
	}

	private String uniqueName(String base) {
		for (int suffix = 2; suffix < 10_000; suffix++) {
			String end = " " + suffix;
			String candidate = base.substring(0, Math.min(base.length(), MAX_NAME_LENGTH - end.length())) + end;
			if (!nameExists(candidate, null)) return candidate;
		}
		return UUID.randomUUID().toString().substring(0, MAX_NAME_LENGTH);
	}

	private static String cleanName(String value) {
		if (value == null) return "";
		String clean = value.strip();
		return clean.substring(0, Math.min(MAX_NAME_LENGTH, clean.length()));
	}

	private static Definition sanitize(Definition source) {
		if (source == null || source.id() == null || source.id().isBlank()
			|| source.id().length() > MAX_ID_LENGTH) return null;
		String name = cleanName(source.name());
		if (name.isEmpty()) return null;
		return new Definition(source.id(), name, source.type(), source.persistValue());
	}

	private static Object coerce(MacroValue.Type type, Object value) {
		if (value == null) return MacroValue.defaultValue(type);
		return switch (type == null ? MacroValue.Type.TEXT : type) {
			case NUMBER -> {
				double number;
				try { number = value instanceof Number n ? n.doubleValue() : Double.parseDouble(value.toString().trim()); }
				catch (RuntimeException ignored) { number = 0; }
				yield Double.isFinite(number) ? number : 0.0d;
			}
			case BOOLEAN -> value instanceof Boolean bool ? bool : Boolean.parseBoolean(value.toString());
			case TEXT -> value.toString().substring(0, Math.min(256, value.toString().length()));
		};
	}

	public record Definition(String id, String name, MacroValue.Type type, boolean persistValue) {
		public Definition {
			if (type == null) type = MacroValue.Type.TEXT;
		}
	}

	public record SavedVariable(Definition definition, String savedValue) { }
}
