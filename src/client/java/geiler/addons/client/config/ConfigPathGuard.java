package geiler.addons.client.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/** No-follow and canonical containment checks for persistent config files on Windows and Unix. */
public final class ConfigPathGuard {
	private ConfigPathGuard() { }

	public record Root(Path lexical, Path real) { }

	/** Rejects a symbolic link or any other reparse point in the existing part of a path. */
	public static void rejectReparseChain(Path path) throws IOException {
		if (path == null) throw new IOException("Configuration path is missing.");
		Path absolute = path.toAbsolutePath().normalize();
		Path current = absolute.getRoot();
		if (current == null) throw new IOException("Configuration path has no filesystem root.");
		assertSafe(current, readIfExists(current));
		for (Path part : absolute) {
			current = current.resolve(part);
			BasicFileAttributes attributes = readIfExists(current);
			if (attributes == null) break;
			assertSafe(current, attributes);
		}
	}

	public static BasicFileAttributes readExisting(Path path) throws IOException {
		BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		assertSafe(path, attributes);
		return attributes;
	}

	public static void assertSafe(Path path, BasicFileAttributes attributes) throws IOException {
		if (attributes == null) throw new IOException("Configuration path is missing: " + path);
		// On Windows, a junction is a directory with FILE_ATTRIBUTE_REPARSE_POINT but is not
		// IO_REPARSE_TAG_SYMLINK; the JDK exposes that case through isOther().
		if (attributes.isSymbolicLink() || attributes.isOther())
			throw new IOException("Symbolic links and filesystem reparse points are not allowed: " + path);
	}

	public static Root checkedDirectory(Path directory) throws IOException {
		Path lexical = directory.toAbsolutePath().normalize();
		rejectReparseChain(lexical);
		BasicFileAttributes attributes = readExisting(lexical);
		if (!attributes.isDirectory()) throw new IOException("Configuration path is not a directory: " + lexical);
		return new Root(lexical, lexical.toRealPath());
	}

	/** Verifies both lexical ownership and the filesystem-resolved path of an existing child. */
	public static void assertContained(Root root, Path candidate) throws IOException {
		Path lexical = candidate.toAbsolutePath().normalize();
		if (!lexical.startsWith(root.lexical())) throw new IOException("Configuration path escapes its owned directory: " + candidate);
		rejectReparseChain(lexical);
		readExisting(lexical);
		Path real = lexical.toRealPath();
		if (!real.startsWith(root.real())) throw new IOException("Resolved configuration path escapes its owned directory: " + candidate);
	}

	private static BasicFileAttributes readIfExists(Path path) throws IOException {
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
		return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
	}
}
