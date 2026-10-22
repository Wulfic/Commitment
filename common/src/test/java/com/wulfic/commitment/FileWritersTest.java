package com.wulfic.commitment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Save safety (TODO.md C.2b): every place shipped code can change a file is pinned here. A new one, anywhere, even a
 * second write inside {@code LockStore}, fails until this list is changed on purpose and reviewed. Scans the raw
 * sources of every loader, other nodes' commented-out version blocks included.
 */
class FileWritersTest {
	/** The shipped sources. Gametest and test sources don't ship (C.0 checked the jars). */
	private static final List<String> ROOTS =
			List.of("common/src/main/java", "fabric/src/main/java", "neoforge/src/main/java", "forge/src/main/java");

	private static final Pattern WRITE = Pattern.compile("\\b(Files\\.(?:write\\w*|move|copy|delete\\w*|create\\w*"
			+ "|newOutputStream|newBufferedWriter|newByteChannel|setAttribute|setLastModifiedTime|setOwner"
			+ "|setPosixFilePermissions)|new (?:FileOutputStream|FileWriter|RandomAccessFile|PrintWriter|PrintStream)"
			+ "|FileChannel\\.open|FileSystems\\.newFileSystem|\\.renameTo|\\.mkdirs?|\\.delete(?:OnExit)?\\(\\))");
	private static final Pattern WRITE_ATOMICALLY = Pattern.compile("\\bwriteAtomically\\(");

	/** Every filesystem write in shipped code, in file order. */
	private static final List<String> PINNED_WRITES = List.of(
			// LockLifter.lift: renames Commitment's own three files, after every guard in its plan (B.6).
			"common/src/main/java/com/wulfic/commitment/lock/LockLifter.java: Files.move",
			// LockStore.writeAtomically: the one writer of a whole file; it writes a temp beside, then moves it over.
			"common/src/main/java/com/wulfic/commitment/lock/LockStore.java: Files.createDirectories",
			"common/src/main/java/com/wulfic/commitment/lock/LockStore.java: Files.write",
			"common/src/main/java/com/wulfic/commitment/lock/LockStore.java: Files.move",
			"common/src/main/java/com/wulfic/commitment/lock/LockStore.java: Files.move",
			"common/src/main/java/com/wulfic/commitment/lock/LockStore.java: Files.deleteIfExists");

	/** Who hands {@code writeAtomically} a file: the world record and the mirror (LockStore), the guard pack (GuardPack). */
	private static final List<String> PINNED_WRITE_ATOMICALLY_CALLERS = List.of(
			"common/src/main/java/com/wulfic/commitment/lock/GuardPack.java",
			"common/src/main/java/com/wulfic/commitment/lock/LockStore.java",
			"common/src/main/java/com/wulfic/commitment/lock/LockStore.java",
			"common/src/main/java/com/wulfic/commitment/lock/LockStore.java");

	@Test
	void shippedCodeWritesFilesOnlyWherePinned() throws IOException {
		Path repo = repoRoot();
		List<String> writes = new ArrayList<>();
		List<String> atomicCallers = new ArrayList<>();
		for (Path source : sources(repo)) {
			String name = repo.relativize(source).toString().replace('\\', '/');
			for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
				String code = line.strip();
				// Javadoc and line comments; a version block's commented-out code is still scanned.
				if (code.startsWith("*") || code.startsWith("/**") || code.startsWith("//")) {
					continue;
				}
				Matcher m = WRITE.matcher(code);
				while (m.find()) {
					writes.add(name + ": " + m.group(1));
				}
				Matcher a = WRITE_ATOMICALLY.matcher(code);
				while (a.find()) {
					atomicCallers.add(name);
				}
			}
		}
		assertEquals(PINNED_WRITES, writes, "a filesystem write in shipped code that isn't pinned (or a pinned one is gone);"
				+ " route it through LockStore.writeAtomically, or change the pin on purpose");
		// LockStore's own entry is the declaration of writeAtomically; the other three are its callers.
		assertEquals(PINNED_WRITE_ATOMICALLY_CALLERS, atomicCallers,
				"LockStore.writeAtomically has a caller that isn't pinned (or a pinned one is gone)");
	}

	private static Path repoRoot() {
		String root = System.getProperty("commitment.repoRoot");
		assertNotNull(root, "commitment.repoRoot is not set; run this through Gradle");
		return Path.of(root);
	}

	/** Every .java file under the roots, sorted, so the pinned lists have a stable order. */
	private static List<Path> sources(Path repo) throws IOException {
		List<Path> sources = new ArrayList<>();
		for (String root : ROOTS) {
			Path dir = repo.resolve(root);
			// A missing root would pass with nothing checked.
			assertTrue(Files.isDirectory(dir), dir + " is not a directory");
			try (Stream<Path> walk = Files.walk(dir)) {
				walk.filter(p -> p.toString().endsWith(".java")).forEach(sources::add);
			}
		}
		sources.sort(null);
		assertTrue(sources.stream().anyMatch(p -> p.endsWith(Path.of("com", "wulfic", "commitment", "lock", "LockStore.java"))),
				"LockStore.java is not among the " + sources.size() + " sources scanned");
		return sources;
	}
}
