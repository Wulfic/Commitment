package com.wulfic.commitment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The boot line is what every smoke greps for, so its shape is pinned here. */
class CommitmentTest {
	/** scripts/checks/boot.txt expects this; change both together. */
	private static final Pattern BOOT_EXPECT = Pattern.compile("Commitment .* loaded on (Fabric|Quilt|NeoForge|Forge) ");

	@Test
	void loadedLineNamesVersionLoaderAndMinecraft() {
		String line = Commitment.loadedLine("0.1.0", "Fabric", "0.19.5", "26.3");
		assertEquals("Commitment 0.1.0 loaded on Fabric 0.19.5, Minecraft 26.3", line);
		assertTrue(BOOT_EXPECT.matcher(line).find(), line + " doesn't match the smoke's " + BOOT_EXPECT.pattern());
	}
}
