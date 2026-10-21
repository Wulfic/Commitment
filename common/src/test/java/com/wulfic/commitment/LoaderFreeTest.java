package com.wulfic.commitment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
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
 * Every loader compiles common's sources into its own jar, so one loader API reference here breaks every other
 * loader's build. Fabric Loader is on common's classpath (its tests need it), so the compiler can't catch this.
 */
class LoaderFreeTest {
	private static final Pattern LOADER_API =
			Pattern.compile("\\b(net\\.fabricmc|net\\.neoforged|net\\.minecraftforge|org\\.quiltmc)\\.");

	@Test
	void commonSourcesNameNoLoaderApi() throws IOException {
		String dirs = System.getProperty("commitment.commonSources");
		assertNotNull(dirs, "commitment.commonSources is not set; run this through Gradle");
		// Every source root of this band's node; a band's own overlay folder may not exist.
		List<Path> sources = new ArrayList<>();
		for (String dir : dirs.split(File.pathSeparator)) {
			Path root = Path.of(dir);
			if (Files.isDirectory(root)) {
				try (Stream<Path> walk = Files.walk(root)) {
					walk.filter(p -> p.toString().endsWith(".java")).forEach(sources::add);
				}
			}
		}
		// The scan has to see the real sources: an empty walk would pass with nothing checked.
		assertTrue(sources.stream().anyMatch(p -> p.endsWith(Path.of("com", "wulfic", "commitment", "Commitment.java"))),
				"Commitment.java is not among the " + sources.size() + " sources under " + dirs);
		List<String> hits = new ArrayList<>();
		for (Path source : sources) {
			List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
			for (int i = 0; i < lines.size(); i++) {
				Matcher m = LOADER_API.matcher(lines.get(i));
				if (m.find()) {
					hits.add(source + ":" + (i + 1) + " names " + m.group(1));
				}
			}
		}
		assertEquals(List.of(), hits, "loader APIs in common; move that code into the loader's entrypoint");
	}
}
