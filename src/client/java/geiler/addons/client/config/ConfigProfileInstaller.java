package geiler.addons.client.config;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/** Transactional staged file replacement with an injectable seam for isolated failure checks. */
public final class ConfigProfileInstaller {
	private ConfigProfileInstaller() { }

	@FunctionalInterface public interface FailureInjector {
		void beforeMove(boolean restoring, int index, Path target) throws IOException;
	}
	@FunctionalInterface public interface PayloadValidator { void validate(Map<String, byte[]> files) throws IOException; }
	public record Result(boolean installed, boolean recoveryRestored) { }

	public static Result install(Path root, Map<String, byte[]> incoming, Map<String, byte[]> backup,
		Predicate<String> safePath, PayloadValidator validator, FailureInjector injector) {
		try {
			replace(root, incoming, safePath, validator, injector, false);
			return new Result(true, true);
		} catch (IOException failedInstall) {
			if (backup == null || backup.isEmpty()) return new Result(false, false);
			try {
				replace(root, backup, safePath, validator, injector, true);
				return new Result(false, true);
			} catch (IOException failedRestore) { return new Result(false, false); }
		}
	}

	private static void replace(Path root, Map<String, byte[]> files, Predicate<String> safePath,
		PayloadValidator validator, FailureInjector injector, boolean restoring) throws IOException {
		Path dataRoot = root.toAbsolutePath().normalize();
		ConfigPathGuard.rejectReparseChain(dataRoot);
		if (files == null || files.isEmpty()) throw new IOException("Replacement profile is empty.");
		Set<String> foldedPaths = new HashSet<>();
		for (Map.Entry<String, byte[]> entry : files.entrySet()) {
			if (safePath == null || !safePath.test(entry.getKey())
				|| !foldedPaths.add(entry.getKey().toLowerCase(Locale.ROOT)) || entry.getValue() == null
				|| entry.getValue().length > ConfigProfileCodec.MAX_ENTRY_BYTES) throw new IOException("Replacement contains an unsafe or oversized file.");
		}
		validator.validate(Map.copyOf(files));
		Map<String, byte[]> oldFiles = collectOwnedFiles(dataRoot, safePath);
		Files.createDirectories(dataRoot);
		ConfigPathGuard.Root dataGuard = ConfigPathGuard.checkedDirectory(dataRoot);
		Path stage = Files.createTempDirectory(dataRoot, ".ga-profile-stage-");
		ConfigPathGuard.assertContained(dataGuard, stage);
		try {
			for (Map.Entry<String, byte[]> entry : files.entrySet()) {
				Path staged = stage.resolve(entry.getKey()).normalize();
				if (!staged.startsWith(stage)) throw new IOException("Staged path escapes its temporary root.");
				Files.createDirectories(staged.getParent());
				ConfigPathGuard.assertContained(dataGuard, staged.getParent());
				Files.write(staged, entry.getValue());
				ConfigPathGuard.assertContained(dataGuard, staged);
			}
			int index = 0;
			for (String relative : files.keySet()) {
				Path target = dataRoot.resolve(relative).normalize();
				if (!target.startsWith(dataRoot)) throw new IOException("Target path is unsafe.");
				ConfigPathGuard.rejectReparseChain(target);
				ensureNoLinks(dataRoot, target.getParent());
				Files.createDirectories(target.getParent());
				ConfigPathGuard.assertContained(dataGuard, target.getParent());
				if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) ConfigPathGuard.assertContained(dataGuard, target);
				if (injector != null) injector.beforeMove(restoring, index, target);
				Path staged = stage.resolve(relative).normalize();
				ConfigPathGuard.assertContained(dataGuard, staged);
				move(staged, target);
				ConfigPathGuard.assertContained(dataGuard, target);
				index++;
			}
			for (String old : oldFiles.keySet()) {
				if (files.containsKey(old)) continue;
				Path target = dataRoot.resolve(old).normalize();
				if (!target.startsWith(dataRoot)) throw new IOException("Cleanup path is unsafe.");
				ConfigPathGuard.rejectReparseChain(target);
				ensureNoLinks(dataRoot, target.getParent());
				if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) ConfigPathGuard.assertContained(dataGuard, target);
				Files.deleteIfExists(target);
			}
		} finally { deleteTree(stage); }
	}

	private static Map<String, byte[]> collectOwnedFiles(Path root, Predicate<String> safePath) throws IOException {
		Map<String, byte[]> files = new LinkedHashMap<>();
		ConfigPathGuard.rejectReparseChain(root);
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return files;
		ConfigPathGuard.Root rootGuard = ConfigPathGuard.checkedDirectory(root);
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				ConfigPathGuard.assertSafe(dir, attrs);
				ConfigPathGuard.assertContained(rootGuard, dir);
				if (dir.equals(root.toAbsolutePath().normalize().resolve("exports"))) return FileVisitResult.SKIP_SUBTREE;
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				ConfigPathGuard.assertSafe(file, attrs);
				ConfigPathGuard.assertContained(rootGuard, file);
				if (!attrs.isRegularFile()) throw new IOException("Config profile contains a non-regular file.");
				String relative = root.relativize(file).toString().replace('\\', '/');
				if (!safePath.test(relative)) throw new IOException("Unknown active config path: " + relative);
				long size = attrs.size();
				if (size < 0 || size > ConfigProfileCodec.MAX_ENTRY_BYTES) throw new IOException("Existing config member is oversized.");
				byte[] bytes = Files.readAllBytes(file);
				if (bytes.length != size) throw new IOException("Existing config member changed while reading.");
				files.put(relative, bytes);
				if (files.size() > ConfigProfileCodec.MAX_ENTRIES) throw new IOException("Too many active config files.");
				return FileVisitResult.CONTINUE;
			}
		});
		return files;
	}

	private static void ensureNoLinks(Path root, Path directory) throws IOException {
		if (directory == null || !directory.normalize().startsWith(root)) throw new IOException("Config directory escapes the root.");
		ConfigPathGuard.rejectReparseChain(directory);
	}
	private static void move(Path source, Path target) throws IOException {
		try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
		catch (java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
	}
	private static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
		ConfigPathGuard.rejectReparseChain(root);
		ConfigPathGuard.Root rootGuard = ConfigPathGuard.checkedDirectory(root);
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				ConfigPathGuard.assertSafe(dir, attrs); ConfigPathGuard.assertContained(rootGuard, dir); return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				ConfigPathGuard.assertSafe(file, attrs); ConfigPathGuard.assertContained(rootGuard, file); Files.delete(file); return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
				if (error != null) throw error; ConfigPathGuard.assertContained(rootGuard, dir); Files.delete(dir); return FileVisitResult.CONTINUE;
			}
		});
	}
}
