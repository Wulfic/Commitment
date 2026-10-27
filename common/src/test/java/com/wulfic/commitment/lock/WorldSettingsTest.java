package com.wulfic.commitment.lock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorldSettingsTest {
	private static final UUID HOST = UUID.fromString("00000000-0000-0000-0000-00000000000a");
	private static final UUID GUEST = UUID.fromString("00000000-0000-0000-0000-00000000000b");

	private static TreeMap<String, String> committed(String... pairs) {
		TreeMap<String, String> map = new TreeMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			map.put(pairs[i], pairs[i + 1]);
		}
		return map;
	}

	@Test
	void eachKindOfSettingIsPutBack() {
		TreeMap<String, String> committed = committed(
				WorldSettings.DIFFICULTY, "hard",
				WorldSettings.DIFFICULTY_LOCKED, "true",
				WorldSettings.DEFAULT_GAMEMODE, "survival",
				WorldSettings.FORCE_GAMEMODE, "false",
				WorldSettings.ALLOW_COMMANDS, "false",
				WorldSettings.GUEST_COMMANDS, "false",
				WorldSettings.gamerule("minecraft:keep_inventory"), "false");
		Map<String, String> current = Map.of(
				WorldSettings.DIFFICULTY, "peaceful",
				WorldSettings.DIFFICULTY_LOCKED, "false",
				WorldSettings.DEFAULT_GAMEMODE, "creative",
				WorldSettings.FORCE_GAMEMODE, "true",
				WorldSettings.ALLOW_COMMANDS, "true",
				WorldSettings.GUEST_COMMANDS, "true",
				WorldSettings.gamerule("minecraft:keep_inventory"), "true");
		assertEquals(List.of(
				new WorldSettings.Revert(WorldSettings.ALLOW_COMMANDS, "false", "true"),
				new WorldSettings.Revert(WorldSettings.DEFAULT_GAMEMODE, "survival", "creative"),
				new WorldSettings.Revert(WorldSettings.DIFFICULTY, "hard", "peaceful"),
				new WorldSettings.Revert(WorldSettings.DIFFICULTY_LOCKED, "true", "false"),
				new WorldSettings.Revert(WorldSettings.FORCE_GAMEMODE, "false", "true"),
				new WorldSettings.Revert("gamerule/minecraft:keep_inventory", "false", "true"),
				new WorldSettings.Revert(WorldSettings.GUEST_COMMANDS, "false", "true")),
				WorldSettings.reverts(committed, current));
	}

	@Test
	void unchangedSettingsNeedNothing() {
		TreeMap<String, String> committed = committed(WorldSettings.DIFFICULTY, "hard", WorldSettings.gamemode(HOST), "survival");
		assertEquals(List.of(), WorldSettings.reverts(committed, Map.of(WorldSettings.DIFFICULTY, "hard", WorldSettings.gamemode(HOST), "survival")));
	}

	@Test
	void aPlayerOnlineAtTheCommitIsHeldAtTheirOwnGamemode() {
		TreeMap<String, String> committed = committed(WorldSettings.gamemode(HOST), "adventure", WorldSettings.ANY_PLAYER, "survival");
		assertEquals(List.of(new WorldSettings.Revert(WorldSettings.gamemode(HOST), "adventure", "survival")),
				WorldSettings.reverts(committed, Map.of(WorldSettings.gamemode(HOST), "survival")));
	}

	@Test
	void anyoneElseIsHeldAtTheCommittedDefault() {
		TreeMap<String, String> committed = committed(WorldSettings.gamemode(HOST), "survival", WorldSettings.ANY_PLAYER, "survival");
		assertEquals(List.of(new WorldSettings.Revert(WorldSettings.gamemode(GUEST), "survival", "creative")),
				WorldSettings.reverts(committed, Map.of(WorldSettings.gamemode(HOST), "survival", WorldSettings.gamemode(GUEST), "creative")));
	}

	@Test
	void withoutAPlayerHoldNoGamemodeIsTouched() {
		// A dedicated server, or a world committed with cheats on: no player keys were snapshotted.
		TreeMap<String, String> committed = committed(WorldSettings.DIFFICULTY, "easy", WorldSettings.DEFAULT_GAMEMODE, "survival");
		assertEquals(List.of(), WorldSettings.reverts(committed, Map.of(WorldSettings.DIFFICULTY, "easy", WorldSettings.gamemode(GUEST), "creative")));
	}

	@Test
	void aHardcoreDeathsSpectatorIsLeftAlone() {
		TreeMap<String, String> committed = committed(WorldSettings.HARDCORE, "true", WorldSettings.gamemode(HOST), "survival",
				WorldSettings.ANY_PLAYER, "survival");
		assertEquals(List.of(), WorldSettings.reverts(committed, Map.of(WorldSettings.gamemode(HOST), "spectator", WorldSettings.gamemode(GUEST), "spectator")));
		assertEquals(List.of(new WorldSettings.Revert(WorldSettings.gamemode(HOST), "survival", "creative")),
				WorldSettings.reverts(committed, Map.of(WorldSettings.gamemode(HOST), "creative")));
	}

	@Test
	void spectatorIsPutBackOutsideHardcore() {
		TreeMap<String, String> committed = committed(WorldSettings.HARDCORE, "false", WorldSettings.gamemode(HOST), "survival");
		assertEquals(List.of(new WorldSettings.Revert(WorldSettings.gamemode(HOST), "survival", "spectator")),
				WorldSettings.reverts(committed, Map.of(WorldSettings.gamemode(HOST), "spectator")));
	}

	@Test
	void hardcoreIsNeverPutBackLive() {
		// It has no setter: the world-open gate refuses a changed flag instead.
		TreeMap<String, String> committed = committed(WorldSettings.HARDCORE, "true");
		assertEquals(List.of(), WorldSettings.reverts(committed, Map.of(WorldSettings.HARDCORE, "false")));
	}

	@Test
	void theAnyPlayerKeyIsNeverAPlayer() {
		TreeMap<String, String> committed = committed(WorldSettings.ANY_PLAYER, "survival");
		assertEquals(List.of(), WorldSettings.reverts(committed, Map.of(WorldSettings.ANY_PLAYER, "creative")));
	}

	@Test
	void anEmptySnapshotHoldsNothing() {
		assertEquals(List.of(), WorldSettings.reverts(new TreeMap<>(), Map.of(WorldSettings.DIFFICULTY, "hard", WorldSettings.gamemode(HOST), "creative")));
	}

	@Test
	void labelsNameTheSettingForTheMessage() {
		assertEquals("Commitment: difficulty is locked", WorldSettings.message(WorldSettings.DIFFICULTY));
		assertEquals("Commitment: the difficulty lock is locked", WorldSettings.message(WorldSettings.DIFFICULTY_LOCKED));
		assertEquals("Commitment: the default gamemode is locked", WorldSettings.message(WorldSettings.DEFAULT_GAMEMODE));
		assertEquals("Commitment: forced gamemode is locked", WorldSettings.message(WorldSettings.FORCE_GAMEMODE));
		assertEquals("Commitment: cheats are locked", WorldSettings.message(WorldSettings.ALLOW_COMMANDS));
		assertEquals("Commitment: LAN guests' commands are locked", WorldSettings.message(WorldSettings.GUEST_COMMANDS));
		assertEquals("Commitment: game rule minecraft:keep_inventory is locked",
				WorldSettings.message(WorldSettings.gamerule("minecraft:keep_inventory")));
		assertEquals("Commitment: gamemode is locked", WorldSettings.message(WorldSettings.gamemode(HOST)));
	}

	@Test
	void theThrottleSaysEachSettingOncePerWindow() {
		WorldSettings.Throttle throttle = new WorldSettings.Throttle();
		assertTrue(throttle.allow(WorldSettings.DIFFICULTY, 1000));
		assertFalse(throttle.allow(WorldSettings.DIFFICULTY, 1000 + WorldSettings.Throttle.WINDOW_TICKS - 1));
		assertTrue(throttle.allow(WorldSettings.gamerule("minecraft:keep_inventory"), 1001), "another setting has its own window");
		assertTrue(throttle.allow(WorldSettings.DIFFICULTY, 1000 + WorldSettings.Throttle.WINDOW_TICKS));
	}

	@Test
	void everyPlayersGamemodeSharesOneWindow() {
		WorldSettings.Throttle throttle = new WorldSettings.Throttle();
		assertTrue(throttle.allow(WorldSettings.gamemode(HOST), 0));
		assertFalse(throttle.allow(WorldSettings.gamemode(GUEST), 1), "one 'gamemode is locked' line, not one per player");
	}
}
