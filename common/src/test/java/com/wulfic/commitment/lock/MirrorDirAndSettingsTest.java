package com.wulfic.commitment.lock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class MirrorDirAndSettingsTest {
	@Test
	void devRunsPointTheMirrorInsideTheirRunDir() {
		String before = System.getProperty(MirrorDir.OVERRIDE);
		try {
			System.setProperty(MirrorDir.OVERRIDE, "run/fabric/26.3/mirror");
			assertEquals(Path.of("run/fabric/26.3/mirror").toAbsolutePath(), MirrorDir.forThisMachine(true));
		} finally {
			restore(before);
		}
	}

	/** Otherwise a player's JVM flag would hide every world's mirror. */
	@Test
	void aReleasedGameIgnoresTheOverride() {
		String before = System.getProperty(MirrorDir.OVERRIDE);
		try {
			System.setProperty(MirrorDir.OVERRIDE, "somewhere/else");
			assertEquals(MirrorDir.resolve(System.getProperty("os.name", ""), System.getenv(), System.getProperty("user.home")),
					MirrorDir.forThisMachine(false));
		} finally {
			restore(before);
		}
	}

	private static void restore(String before) {
		if (before == null) {
			System.clearProperty(MirrorDir.OVERRIDE);
		} else {
			System.setProperty(MirrorDir.OVERRIDE, before);
		}
	}

	// The Windows cases need a Windows host: resolve() asks the host's Path whether a dir is absolute, which is right in
	// a game (os.name is the host), but on Linux "C:\Users\p" isn't absolute and resolve() gives null (CI, 2026-10-29).
	@Test
	@EnabledOnOs(OS.WINDOWS)
	void windowsUsesLocalAppData() {
		assertEquals(Path.of("C:\\Users\\p\\AppData\\Local", "Commitment", "locks"),
				MirrorDir.resolve("Windows 11", Map.of("LOCALAPPDATA", "C:\\Users\\p\\AppData\\Local"), "C:\\Users\\p"));
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void windowsWithoutLocalAppDataFallsBackToTheHomeDir() {
		assertEquals(Path.of("C:\\Users\\p", "AppData", "Local", "Commitment", "locks"),
				MirrorDir.resolve("Windows 11", Map.of(), "C:\\Users\\p"));
	}

	@Test
	void macUsesApplicationSupport() {
		assertEquals(Path.of("/Users/p", "Library", "Application Support", "Commitment", "locks"),
				MirrorDir.resolve("Mac OS X", Map.of(), "/Users/p"));
	}

	@Test
	void linuxUsesXdgDataHome() {
		assertEquals(Path.of("/data/p", "commitment", "locks"), MirrorDir.resolve("Linux", Map.of("XDG_DATA_HOME", "/data/p"), "/home/p"));
	}

	@Test
	void aRelativeXdgDataHomeIsIgnored() {
		assertEquals(Path.of("/home/p", ".local", "share", "commitment", "locks"),
				MirrorDir.resolve("Linux", Map.of("XDG_DATA_HOME", "relative/dir"), "/home/p"));
	}

	@Test
	void noHomeNoMirror() {
		assertNull(MirrorDir.resolve("Linux", Map.of(), ""));
		assertNull(MirrorDir.resolve("Windows 11", Map.of(), null));
	}

	@Test
	void oneChangedSettingIsOneRevert() {
		TreeMap<String, String> committed = new TreeMap<>(Map.of("difficulty", "hard", "gamerule/keep_inventory", "false"));
		assertEquals(List.of(new WorldSettings.Revert("difficulty", "hard", "peaceful")),
				WorldSettings.reverts(committed, Map.of("difficulty", "peaceful", "gamerule/keep_inventory", "false")));
	}

	@Test
	void aRuleAddedOrRemovedSinceTheCommitIsLeftAlone() {
		TreeMap<String, String> committed = new TreeMap<>(Map.of("difficulty", "hard", "gamerule/gone", "1"));
		assertEquals(List.of(), WorldSettings.reverts(committed, Map.of("difficulty", "hard", "gamerule/new_from_a_mod", "true")));
	}
}
