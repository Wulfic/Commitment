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
 * The threat-model row "Move the clock forward" (TODO.md D.1): the 24-hour unlock runs on internet time only. Every
 * read of the system's wall clock in shipped code is pinned here, so a new one, say in {@code UnlockTimer}, fails
 * until it's reviewed. {@code System.nanoTime} is monotonic (moving the clock doesn't move it) and isn't counted.
 * UnlockTimerTest.movingTheSystemClockForwardChangesNothing shows the pinned stamps don't feed the timer.
 */
class ClockSourcesTest {
	/** The shipped sources, as FileWritersTest scans them. */
	private static final List<String> ROOTS =
			List.of("common/src/main/java", "fabric/src/main/java", "neoforge/src/main/java", "forge/src/main/java");

	private static final Pattern WALL_CLOCK = Pattern.compile("\\b(System(?:\\.|::)currentTimeMillis"
			+ "|(?:Instant|LocalDate|LocalDateTime|LocalTime|ZonedDateTime|OffsetDateTime|OffsetTime|Year|YearMonth|MonthDay)\\.now"
			+ "|Clock\\.(?:system\\w*|tick\\w*)|InstantSource\\.system|new (?:java\\.util\\.)?Date\\(|Calendar\\.getInstance"
			+ "|LocalDate::now|Instant::now)");

	/** Every wall-clock read in shipped code, in file order. Both are stamps for display, never a timer input. */
	private static final List<String> PINNED_READS = List.of(
			// /commit confirm: LockRecord.committedAt, shown by /commit status ("on the system clock; shown, never trusted").
			"common/src/main/java/com/wulfic/commitment/command/CommitmentCommands.java: System.currentTimeMillis",
			// LockStore's clock: the committedAt of a record rebuilt as tampered.
			"common/src/main/java/com/wulfic/commitment/server/CommitmentServer.java: System::currentTimeMillis");

	@Test
	void shippedCodeReadsTheWallClockOnlyWherePinned() throws IOException {
		Path repo = repoRoot();
		List<String> reads = new ArrayList<>();
		for (Path source : sources(repo)) {
			String name = repo.relativize(source).toString().replace('\\', '/');
			for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
				String code = line.strip();
				// Javadoc and line comments; a version block's commented-out code is still scanned.
				if (code.startsWith("*") || code.startsWith("/**") || code.startsWith("//")) {
					continue;
				}
				Matcher m = WALL_CLOCK.matcher(code);
				while (m.find()) {
					reads.add(name + ": " + m.group(1));
				}
			}
		}
		assertEquals(PINNED_READS, reads, "a wall-clock read in shipped code that isn't pinned (or a pinned one is gone);"
				+ " the unlock must run on TrustedClock (internet time), never the system clock");
	}

	private static Path repoRoot() {
		String root = System.getProperty("commitment.repoRoot");
		assertNotNull(root, "commitment.repoRoot is not set; run this through Gradle");
		return Path.of(root);
	}

	/** Every .java file under the roots, sorted, so the pinned list has a stable order. */
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
		assertTrue(sources.stream().anyMatch(p -> p.endsWith(Path.of("com", "wulfic", "commitment", "lock", "UnlockTimer.java"))),
				"UnlockTimer.java is not among the " + sources.size() + " sources scanned");
		return sources;
	}
}
