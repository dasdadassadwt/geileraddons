package geiler.addons.client.collections;

/** Offline checks for independent, stable-ID nested folder operations. */
public final class FolderTreeChecks {
	private FolderTreeChecks() { }

	public static void run() {
		FolderTree tree = new FolderTree();
		FolderTree.Folder first = tree.create("Repeated name", null);
		FolderTree.Folder second = tree.create("Repeated name", null);
		check(first != null && second != null && !first.id().equals(second.id()),
			"same-name folders have distinct stable ids");
		check(tree.rename(first.id(), "Renamed"), "folder can be renamed");
		check(first.id().equals(tree.folder(first.id()).id()), "rename preserves the folder id");
		FolderTree.Folder child = tree.create("Child", first.id());
		check(child != null && tree.move(child.id(), second.id()), "folder can be reparented");
		check(second.id().equals(tree.folder(child.id()).parentId()), "reparent updates the child parent id");
		check(!tree.move(second.id(), child.id()), "moving a folder below its own descendant is rejected");
		check(tree.move(child.id(), first.id()), "folder can be moved back under another parent");

		String formerParent = tree.delete(first.id());
		check(formerParent == null, "deleting a root folder promotes its contents to root");
		check(tree.folder(child.id()) != null && tree.folder(child.id()).parentId() == null,
			"deleting a populated folder promotes its child folders");
		check(tree.contains(second.id()), "deleting a folder does not delete a sibling with the same name");

		tree.restore(java.util.List.of(
			new FolderTree.Folder("legacy-root", "Legacy", null),
			new FolderTree.Folder("cycle-a", "A", "cycle-b"),
			new FolderTree.Folder("cycle-b", "B", "cycle-a")));
		check(tree.folder("legacy-root").parentId() == null, "legacy entries without a folder remain at root");
		check(tree.folder("cycle-a").parentId() == null || tree.folder("cycle-b").parentId() == null,
			"malformed saved folder cycles are repaired");
	}

	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}
}
