package com.wulfic.commitment.lock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The README's "Emergency removal" steps against the files Commitment really writes. A player follows them when the
 * unlock can't help, so a renamed file or folder must fail the build instead of leaving a world locked by a copy the
 * steps didn't name. Lives in the lock package to read {@link MirrorDir#resolve} and {@link GuardPack#MARKER_ENTRY}.
 */
class ReadmeRemovalTest {
	private static final String SECTION = "### Emergency removal";
	private static final UUID ID = UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100");

	private static String readme;
	/** The section's text, from its heading to the next heading. */
	private static String section;

	@BeforeAll
	static void load() throws IOException {
		String root = System.getProperty("commitment.repoRoot");
		assertNotNull(root, "commitment.repoRoot is not set; run this through Gradle");
		List<String> lines = Files.readAllLines(Path.of(root, "README.md"), StandardCharsets.UTF_8);
		readme = String.join("\n", lines);
		int start = lines.indexOf(SECTION);
		assertTrue(start >= 0, "README.md has no '" + SECTION + "' heading");
		int end = start + 1;
		while (end < lines.size() && !lines.get(end).startsWith("#")) {
			end++;
		}
		section = String.join("\n", lines.subList(start, end));
	}

	@Test
	void namesTheGuardPackAndTheLockRecord() {
		assertNamed("`<world>/" + GuardPack.FILE + "`");
		assertNamed("`<world>/" + LockStore.WORLD_RECORD + "`");
	}

	@Test
	void theLockIdIsWhereTheStepsSayToReadIt() {
		String marker = GuardPack.files(ID).get(GuardPack.MARKER_ENTRY);
		assertNotNull(marker, "the guard pack has no " + GuardPack.MARKER_ENTRY + " entry");
		assertTrue(marker.lines().anyMatch(("lock=" + ID)::equals), "the marker has no 'lock=<id>' line: " + marker);
		assertNamed("`" + GuardPack.MARKER_ENTRY + "`");
		assertNamed("`lock=`");
	}

	@Test
	void namesTheMirrorFileAndEveryMirrorFolder() {
		Path dir = Path.of("/mirror");
		assertEquals(ID + ".dat", new LockStore(dir, () -> 0L, () -> ID).mirrorFile(ID).getFileName().toString());
		assertNamed("`<lock id>.dat`");

		// Strings, not Path.toString(): on Windows that turns "/home/p" into "\home\p", which resolve() rejects.
		String local = "/appdata/local";
		String home = "/home/p";
		String xdg = "/xdg";
		Path windows = MirrorDir.resolve("Windows 11", Map.of("LOCALAPPDATA", local), home);
		assertNamed("`%LOCALAPPDATA%\\" + under(local, windows, "\\") + "`");
		assertNamed("`~/" + under(home, MirrorDir.resolve("Mac OS X", Map.of(), home), "/") + "`");
		Path xdgSet = MirrorDir.resolve("Linux", Map.of("XDG_DATA_HOME", xdg), home);
		assertNamed("`$XDG_DATA_HOME/" + under(xdg, xdgSet, "/") + "`");
		assertNamed("`~/" + under(home, MirrorDir.resolve("Linux", Map.of(), home), "/") + "`");
	}

	@Test
	void theRestOfTheReadmeLinksToIt() {
		long links = readme.lines().filter(l -> l.contains("(#emergency-removal)")).count();
		assertTrue(links >= 2, "the 'Don't remove' paragraph and 'What still works' should link to the section, found "
				+ links);
	}

	/** {@code full} below {@code base}, its names joined with {@code separator}, as the README writes it. */
	private static String under(String base, Path full, String separator) {
		assertNotNull(full, "no mirror folder under " + base);
		assertTrue(full.startsWith(Path.of(base)), full + " isn't under " + base);
		return StreamSupport.stream(Path.of(base).relativize(full).spliterator(), false).map(Path::toString)
				.collect(Collectors.joining(separator));
	}

	private static void assertNamed(String text) {
		assertTrue(section.contains(text), "README's '" + SECTION + "' doesn't name " + text);
	}
}
