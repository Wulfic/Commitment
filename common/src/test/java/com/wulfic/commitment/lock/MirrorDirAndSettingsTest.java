package com.wulfic.commitment.lock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class MirrorDirAndSettingsTest {
	@Test
	void windowsUsesLocalAppData() {
		assertEquals(Path.of("C:\\Users\\p\\AppData\\Local", "Commitment", "locks"),
				MirrorDir.resolve("Windows 11", Map.of("LOCALAPPDATA", "C:\\Users\\p\\AppData\\Local"), "C:\\Users\\p"));
	}

	@Test
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
