package geiler.addons.client.collections;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Persistent folder hierarchy shared by configurable, multi-entry feature lists. */
public final class FolderTree {
	private final List<Folder> folders = new ArrayList<>();
	private long nextId;

	public List<Folder> folders() {
		return List.copyOf(folders);
	}

	public Folder create(String name, String parentId) {
		String cleanName = cleanName(name);
		if (cleanName.isEmpty()) return null;
		String parent = contains(parentId) ? parentId : null;
		String id;
		do {
			id = "folder-" + nextId++;
		} while (contains(id));
		Folder folder = new Folder(id, cleanName, parent);
		folders.add(folder);
		return folder;
	}

	public boolean rename(String id, String name) {
		String cleanName = cleanName(name);
		if (cleanName.isEmpty()) return false;
		int index = indexOf(id);
		if (index < 0) return false;
		Folder old = folders.get(index);
		folders.set(index, new Folder(old.id(), cleanName, old.parentId()));
		return true;
	}

	public boolean move(String id, String newParentId) {
		int index = indexOf(id);
		if (index < 0) return false;
		String parent = newParentId == null || newParentId.isBlank() ? null : newParentId;
		if (parent != null && (!contains(parent) || parent.equals(id) || isDescendant(parent, id))) return false;
		Folder old = folders.get(index);
		if (java.util.Objects.equals(old.parentId(), parent)) return true;
		folders.set(index, new Folder(old.id(), old.name(), parent));
		return true;
	}

	/** Removes a folder and promotes its direct children. Returns the folder's former parent. */
	public String delete(String id) {
		int index = indexOf(id);
		if (index < 0) return null;
		Folder removed = folders.remove(index);
		for (int i = 0; i < folders.size(); i++) {
			Folder child = folders.get(i);
			if (id.equals(child.parentId())) {
				folders.set(i, new Folder(child.id(), child.name(), removed.parentId()));
			}
		}
		return removed.parentId();
	}

	public boolean contains(String id) {
		return indexOf(id) >= 0;
	}

	public Folder folder(String id) {
		int index = indexOf(id);
		return index < 0 ? null : folders.get(index);
	}

	public List<Folder> childrenOf(String parentId) {
		List<Folder> result = new ArrayList<>();
		for (Folder folder : folders) {
			if (java.util.Objects.equals(folder.parentId(), parentId)) result.add(folder);
		}
		return List.copyOf(result);
	}

	public boolean isDescendant(String candidateId, String ancestorId) {
		if (candidateId == null || ancestorId == null) return false;
		Folder current = folder(candidateId);
		Set<String> visited = new HashSet<>();
		while (current != null && current.parentId() != null && visited.add(current.id())) {
			if (ancestorId.equals(current.parentId())) return true;
			current = folder(current.parentId());
		}
		return false;
	}

	/** Restores known-good folders while ignoring duplicate ids, invalid parents, and cycles. */
	public void restore(List<Folder> saved) {
		folders.clear();
		nextId = 0;
		if (saved == null) return;
		Map<String, Folder> unique = new LinkedHashMap<>();
		for (Folder folder : saved) {
			if (folder == null || folder.id() == null || folder.id().isBlank()) continue;
			String name = cleanName(folder.name());
			if (name.isEmpty() || unique.containsKey(folder.id())) continue;
			unique.put(folder.id(), new Folder(folder.id(), name, folder.parentId()));
			if (folder.id().startsWith("folder-")) {
				try { nextId = Math.max(nextId, Long.parseLong(folder.id().substring(7)) + 1); }
				catch (NumberFormatException ignored) { }
			}
		}
		for (Folder candidate : unique.values()) {
			String parent = candidate.parentId();
			if (parent != null && (!unique.containsKey(parent) || parent.equals(candidate.id())
				|| savedCycle(candidate.id(), parent, unique))) parent = null;
			folders.add(new Folder(candidate.id(), candidate.name(), parent));
		}
	}

	private static boolean savedCycle(String id, String parent, Map<String, Folder> saved) {
		Set<String> visited = new HashSet<>();
		String current = parent;
		while (current != null && visited.add(current)) {
			if (id.equals(current)) return true;
			Folder folder = saved.get(current);
			current = folder == null ? null : folder.parentId();
		}
		return current != null;
	}

	private int indexOf(String id) {
		if (id == null) return -1;
		for (int i = 0; i < folders.size(); i++) if (id.equals(folders.get(i).id())) return i;
		return -1;
	}

	private static String cleanName(String name) {
		return name == null ? "" : name.trim().substring(0, Math.min(48, name.trim().length()));
	}

	public record Folder(String id, String name, String parentId) { }
}
