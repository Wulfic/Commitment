package com.wulfic.commitment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** CHANGELOG.md against gradle.properties' version, so a release can't ship without its entry. */
class ChangelogTest {
	/** The version of the changelog's newest entry: its first "## " heading, up to the first space. */
	static String newest(List<String> lines) {
		for (String line : lines) {
			if (line.startsWith("## ")) {
				String heading = line.substring(3).trim();
				int space = heading.indexOf(' ');
				return space < 0 ? heading : heading.substring(0, space);
			}
		}
		throw new AssertionError("CHANGELOG.md has no '## <version>' heading");
	}

	@Test
	void theNewestEntryIsTheBuildsVersion() throws IOException {
		String root = System.getProperty("commitment.repoRoot");
		assertNotNull(root, "commitment.repoRoot is not set; run this through Gradle");
		Properties gradle = new Properties();
		try (Reader reader = Files.newBufferedReader(Path.of(root, "gradle.properties"), StandardCharsets.UTF_8)) {
			gradle.load(reader);
		}
		String version = gradle.getProperty("version");
		assertNotNull(version, "gradle.properties has no version");
		Path changelog = Path.of(root, "CHANGELOG.md");
		assertTrue(Files.isRegularFile(changelog), "there is no CHANGELOG.md");
		assertEquals(version, newest(Files.readAllLines(changelog, StandardCharsets.UTF_8)),
				"CHANGELOG.md's newest entry should be the build's version " + version);
	}

	@Test
	void theHeadingParserReadsTheFirstEntryOnly() {
		assertEquals("1.0.0", newest(List.of("# Changelog", "", "## 1.0.0 (2026-10-28)", "", "## 0.9.0")));
		assertEquals("1.1.0", newest(List.of("## 1.1.0")));
		assertThrows(AssertionError.class, () -> newest(List.of("# Changelog", "### 1.0.0")));
	}
}
