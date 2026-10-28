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
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The NeoForge and Forge jars declare a loader floor below the pin they compile against (decisions.md 2026-10-28), so a
 * pack doesn't have to update its loader. This holds each floor at or below its pin and on the pin's line (a NeoForge
 * floor for the same Minecraft version, a Forge floor of the same major), and the mods.toml templates to the floor.
 * Running on Fabric's test task, it reads the repo's files, as {@link ReadmeBandsTest} does.
 */
class LoaderFloorTest {
	private static final String NEOFORGE_TOML = "neoforge/src/main/resources/META-INF/neoforge.mods.toml";
	private static final String FORGE_TOML = "forge/src/main/resources/META-INF/mods.toml";

	private static Path root;
	private static Properties gradle;

	@BeforeAll
	static void load() throws IOException {
		String dir = System.getProperty("commitment.repoRoot");
		assertNotNull(dir, "commitment.repoRoot is not set; run this through Gradle");
		root = Path.of(dir);
		gradle = new Properties();
		try (Reader reader = Files.newBufferedReader(root.resolve("gradle.properties"), StandardCharsets.UTF_8)) {
			gradle.load(reader);
		}
	}

	/**
	 * Maven's order for the versions these loaders publish: dot-separated numbers, compared as numbers, with an optional
	 * {@code -beta} that sorts below the same numbers without it. Anything else is refused, so a new qualifier gets a look.
	 */
	static int compare(String a, String b) {
		List<Integer> x = numbers(a);
		List<Integer> y = numbers(b);
		for (int i = 0; i < Math.max(x.size(), y.size()); i++) {
			int c = Integer.compare(i < x.size() ? x.get(i) : 0, i < y.size() ? y.get(i) : 0);
			if (c != 0) {
				return c;
			}
		}
		return Boolean.compare(!a.endsWith("-beta"), !b.endsWith("-beta"));
	}

	private static List<Integer> numbers(String version) {
		String plain = version.endsWith("-beta") ? version.substring(0, version.length() - "-beta".length()) : version;
		assertTrue(plain.matches("\\d+(\\.\\d+)*"), "'" + version + "' isn't a version this test knows how to order");
		List<Integer> numbers = new ArrayList<>();
		for (String part : plain.split("\\.")) {
			numbers.add(Integer.parseInt(part));
		}
		return numbers;
	}

	/** "26.3.0.95" → "26.3.0", "66.1.0" → "66": the part a floor has to share with its pin. */
	private static String line(String version, int parts) {
		return String.join(".", List.of(version.replace("-beta", "").split("\\.")).subList(0, parts));
	}

	/** The bands that have a {@code <prefix>_version.<band>} pin. */
	private static List<String> bands(String prefix) {
		List<String> bands = gradle.stringPropertyNames().stream().filter(k -> k.startsWith(prefix + "_version."))
				.map(k -> k.substring((prefix + "_version.").length())).sorted().toList();
		assertFalse(bands.isEmpty(), "gradle.properties has no " + prefix + "_version.<band> keys");
		return bands;
	}

	private static void assertFloors(String prefix, int lineParts) {
		for (String band : bands(prefix)) {
			String pin = gradle.getProperty(prefix + "_version." + band);
			String floor = gradle.getProperty(prefix + "_floor." + band);
			assertNotNull(floor, "gradle.properties has " + prefix + "_version." + band + " but no " + prefix + "_floor." + band);
			assertTrue(compare(floor, pin) <= 0, prefix + " floor " + floor + " is above the pin " + pin + " (band " + band + ")");
			assertEquals(line(pin, lineParts), line(floor, lineParts),
					prefix + " floor " + floor + " isn't on the pin " + pin + "'s line (band " + band + ")");
		}
	}

	@Test
	void neoForgeFloorsAreAtOrBelowTheirPinsOnTheSameMinecraftVersion() {
		assertFloors("neoforge", 3);
	}

	@Test
	void forgeFloorsAreAtOrBelowTheirPinsInTheSameMajor() {
		assertFloors("forge", 1);
	}

	@Test
	void theModsTomlTemplatesDeclareTheFloorNotThePin() throws IOException {
		String neoforge = Files.readString(root.resolve(NEOFORGE_TOML), StandardCharsets.UTF_8);
		assertTrue(neoforge.contains("versionRange = \"[${neoforgeFloor},)\""), NEOFORGE_TOML + " doesn't declare [${neoforgeFloor},)");
		assertFalse(neoforge.contains("${neoforgeVersion}"), NEOFORGE_TOML + " still names the pin");
		String forge = Files.readString(root.resolve(FORGE_TOML), StandardCharsets.UTF_8);
		assertTrue(forge.contains("versionRange = \"[${forgeFloor},)\""), FORGE_TOML + " doesn't declare [${forgeFloor},)");
		assertFalse(forge.contains("${forgeVersion}"), FORGE_TOML + " still names the pin");
	}

	@Test
	void theOrderIsMavensForTheseVersions() {
		assertTrue(compare("26.3.0.51-beta", "26.3.0.51") < 0);
		assertTrue(compare("26.3.0.51-beta", "26.3.0.76") < 0);
		assertTrue(compare("26.3.0.76", "26.3.0.51-beta") > 0);
		assertTrue(compare("26.1.2.71", "26.1.2.122") < 0);
		assertTrue(compare("64.0.0", "64.1.5") < 0);
		assertEquals(0, compare("66.1.0", "66.1.0"));
		assertThrows(AssertionError.class, () -> compare("26.3.0.51-alpha", "26.3.0.95"));
		assertEquals("26.3.0", line("26.3.0.51-beta", 3));
		assertEquals("66", line("66.1.0", 1));
	}
}
