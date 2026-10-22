package com.wulfic.commitment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * A player picks a jar by the band in its name, and Fabric loads it by the range in fabric.mod.json. Both are set
 * per band in gradle.properties, so this holds them to each other: the name's band {@code X.Y} means exactly
 * {@code >=X.Y <X.(Y+1)}, a one-version band {@code X.Y.Z} (1.21.11) means {@code >=X.Y.Z <X.Y.(Z+1)}, and the version
 * the jar was compiled against is in that band.
 */
class BandLabelTest {
	/** Group 1: the label up to and including its last dot; group 2: its last number. */
	private static final Pattern JAR = Pattern.compile("commitment-[0-9][^+]*\\+fabric-mc((?:\\d+\\.)+)(\\d+)\\.jar");

	private static String property(String name) {
		String value = System.getProperty(name);
		assertNotNull(value, name + " is not set; run this through Gradle");
		return value;
	}

	@Test
	void jarNameAndMinecraftRangeNameTheSameBand() throws IOException {
		String jar = property("commitment.jarName");
		Matcher m = JAR.matcher(jar);
		assertTrue(m.matches(), "jar name " + jar + " doesn't match " + JAR.pattern());
		String prefix = m.group(1);
		int last = Integer.parseInt(m.group(2));
		String range = ModMetadataTest.ours().getAsJsonObject("depends").get("minecraft").getAsString();
		assertEquals(">=" + prefix + last + " <" + prefix + (last + 1), range,
				jar + " is for band " + prefix + last + ", but fabric.mod.json declares " + range);
	}

	@Test
	void compiledVersionIsInTheBand() {
		String jar = property("commitment.jarName");
		Matcher m = JAR.matcher(jar);
		assertTrue(m.matches(), "jar name " + jar + " doesn't match " + JAR.pattern());
		String band = m.group(1) + m.group(2);
		String compiled = property("commitment.minecraft");
		assertTrue(compiled.equals(band) || compiled.startsWith(band + "."),
				jar + " is for band " + band + ", but it was compiled against " + compiled);
	}
}
