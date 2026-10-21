package com.wulfic.commitment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The README's "Supported versions" table against the build's band table (gradle.properties, the same keys every
 * node and the release guard read), so the README can't promise a band the release doesn't ship, or miss one it does.
 */
class ReadmeBandsTest {
	private static final String SECTION = "## Supported versions";
	/** A row that isn't a band of this build has to say it's a frozen line. */
	private static final String FROZEN = "Frozen on branch";

	private static Properties gradle;
	/** The table's rows, keyed by the Minecraft cell without its bold markers. */
	private static Map<String, List<String>> rows;

	@BeforeAll
	static void load() throws IOException {
		String root = System.getProperty("commitment.repoRoot");
		assertNotNull(root, "commitment.repoRoot is not set; run this through Gradle");
		gradle = new Properties();
		try (Reader reader = Files.newBufferedReader(Path.of(root, "gradle.properties"), StandardCharsets.UTF_8)) {
			gradle.load(reader);
		}
		rows = table(Files.readAllLines(Path.of(root, "README.md"), StandardCharsets.UTF_8));
	}

	/** The first Markdown table after {@link #SECTION}: header and separator skipped, cells trimmed. */
	static Map<String, List<String>> table(List<String> lines) {
		int i = lines.indexOf(SECTION);
		assertTrue(i >= 0, "README.md has no '" + SECTION + "' heading");
		while (i < lines.size() && !lines.get(i).startsWith("|")) {
			i++;
		}
		Map<String, List<String>> table = new LinkedHashMap<>();
		for (i += 2; i < lines.size() && lines.get(i).startsWith("|"); i++) {
			String line = lines.get(i);
			List<String> cells = Arrays.stream(line.substring(1, line.length() - 1).split("\\|")).map(String::trim)
					.toList();
			String minecraft = cells.getFirst().replace("**", "");
			assertTrue(table.put(minecraft, cells) == null, "README lists " + minecraft + " twice");
		}
		assertFalse(table.isEmpty(), "README's '" + SECTION + "' has no table rows");
		return table;
	}

	private static String property(String key) {
		String value = gradle.getProperty(key);
		assertNotNull(value, "gradle.properties has no " + key);
		return value;
	}

	/** The bands, as their band_label.<band> keys name them. */
	private static TreeSet<String> bands() {
		TreeSet<String> bands = new TreeSet<>();
		for (String key : gradle.stringPropertyNames()) {
			if (key.startsWith("band_label.")) {
				bands.add(key.substring("band_label.".length()));
			}
		}
		assertFalse(bands.isEmpty(), "gradle.properties lists no band_label.<band> keys");
		return bands;
	}

	/** "26.2", or "26.1 – 26.1.2" when the band's Fabric jar runs from an older floor than it compiles against. */
	private static String versions(String band) {
		String floor = property("smoke_floor." + band);
		return floor.equals(band) ? band : floor + " – " + band;
	}

	@Test
	void everyBandHasARowAndEveryRowIsABand() {
		List<String> expected = bands().stream().map(ReadmeBandsTest::versions).toList();
		for (String minecraft : expected) {
			assertTrue(rows.containsKey(minecraft), "README has no row for band " + minecraft + ", rows: " + rows.keySet());
		}
		for (Map.Entry<String, List<String>> row : rows.entrySet()) {
			assertTrue(expected.contains(row.getKey()) || String.join(" ", row.getValue()).contains(FROZEN),
					"README row " + row.getKey() + " is no band of this build, and isn't marked '" + FROZEN + "'");
		}
	}

	@Test
	void eachRowNamesItsBandsJars() {
		for (String band : bands()) {
			List<String> cells = rows.get(versions(band));
			assertNotNull(cells, "README has no row for band " + band);
			assertEquals(4, cells.size(), "README row " + cells + " should have 4 cells: Minecraft, Fabric and Quilt, NeoForge, Forge");
			assertJar(cells.get(1), "Fabric", property("band_label." + band));
			assertJar(cells.get(2), "NeoForge", property("neoforge_band_label." + band));
			assertJar(cells.get(3), "Forge", property("forge_band_label." + band));
			for (String cell : cells) {
				assertFalse(cell.contains("Planned"), "README row for band " + band + " still says Planned: " + cells);
			}
		}
	}

	private static void assertJar(String cell, String loader, String label) {
		assertTrue(cell.equals("`mc" + label + "`") || cell.startsWith("`mc" + label + "`:"),
				"README's " + loader + " cell '" + cell + "' should name the jar's band `mc" + label + "`");
	}

	@Test
	void theTableParserReadsRowsAndRefusesDuplicates() {
		List<String> lines = List.of("# x", SECTION, "", "| A | B |", "|---|---|", "| **26.2** | `mc26.2` |", "",
				"| not | this |");
		assertEquals(Map.of("26.2", List.of("**26.2**", "`mc26.2`")), table(lines));
		List<String> twice = List.of(SECTION, "| A |", "|---|", "| 26.2 |", "| **26.2** |");
		assertThrows(AssertionError.class, () -> table(twice));
	}
}
