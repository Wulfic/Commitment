package com.wulfic.commitment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * A player picks a jar by the Minecraft versions in its name, and Fabric loads it by the range in fabric.mod.json.
 * Both are set per band in gradle.properties, so this holds them to each other: the name's label is the lowest
 * version {@code X.Y} (or a one-version band's {@code X.Y.Z}, 1.21.11), optionally followed by {@code -HIGH}, the
 * highest. The lowest means exactly {@code >=X.Y <X.(Y+1)} ({@code >=X.Y.Z <X.Y.(Z+1)}), and the jar was compiled
 * against the highest, so a band that runs on several versions can't hide them behind its lowest.
 */
class BandLabelTest {
	/** Group 1: the lowest version up to and including its last dot; group 2: its last number; group 3: the highest. */
	private static final Pattern JAR = Pattern
			.compile("commitment-[0-9][^+]*\\+fabric(?:-quilt)?-mc((?:\\d+\\.)+)(\\d+)(?:-((?:\\d+\\.)+\\d+))?\\.jar");

	private static String property(String name) {
		String value = System.getProperty(name);
		assertNotNull(value, name + " is not set; run this through Gradle");
		return value;
	}

	private static Matcher jar() {
		String jar = property("commitment.jarName");
		Matcher m = JAR.matcher(jar);
		assertTrue(m.matches(), "jar name " + jar + " doesn't match " + JAR.pattern());
		return m;
	}

	@Test
	void jarNameAndMinecraftRangeNameTheSameBand() throws IOException {
		Matcher m = jar();
		String prefix = m.group(1);
		int last = Integer.parseInt(m.group(2));
		String range = ModMetadataTest.ours().getAsJsonObject("depends").get("minecraft").getAsString();
		assertEquals(">=" + prefix + last + " <" + prefix + (last + 1), range,
				m.group() + " is for band " + prefix + last + ", but fabric.mod.json declares " + range);
	}

	@Test
	void compiledVersionIsTheHighestInTheName() {
		Matcher m = jar();
		String lowest = m.group(1) + m.group(2);
		String highest = m.group(3) == null ? lowest : m.group(3);
		String compiled = property("commitment.minecraft");
		assertEquals(highest, compiled, m.group() + " names " + highest + " as its highest Minecraft, but it was compiled"
				+ " against " + compiled + " (a band that runs on several versions names them: LOW-HIGH)");
		assertTrue(highest.startsWith(lowest + ".") || highest.equals(lowest),
				m.group() + " names " + highest + ", which isn't in band " + lowest);
	}
}
