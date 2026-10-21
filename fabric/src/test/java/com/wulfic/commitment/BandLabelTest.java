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
 * {@code >=X.Y <X.(Y+1)}, and the version the jar was compiled against is in that band.
 */
class BandLabelTest {
	private static final Pattern JAR = Pattern.compile("commitment-[0-9][^+]*\\+fabric-mc(\\d+)\\.(\\d+)\\.jar");

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
		int major = Integer.parseInt(m.group(1));
		int minor = Integer.parseInt(m.group(2));
		String range = ModMetadataTest.ours().getAsJsonObject("depends").get("minecraft").getAsString();
		assertEquals(">=" + major + "." + minor + " <" + major + "." + (minor + 1), range,
				jar + " is for band " + major + "." + minor + ", but fabric.mod.json declares " + range);
	}

	@Test
	void compiledVersionIsInTheBand() {
		String jar = property("commitment.jarName");
		Matcher m = JAR.matcher(jar);
		assertTrue(m.matches(), "jar name " + jar + " doesn't match " + JAR.pattern());
		String band = m.group(1) + "." + m.group(2);
		String compiled = property("commitment.minecraft");
		assertTrue(compiled.equals(band) || compiled.startsWith(band + "."),
				jar + " is for band " + band + ", but it was compiled against " + compiled);
	}
}
