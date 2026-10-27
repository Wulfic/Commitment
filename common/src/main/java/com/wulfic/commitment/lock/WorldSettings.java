package com.wulfic.commitment.lock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The world's game settings (difficulty, game rules, cheats, gamemodes) as name → value strings, so common never
 * touches the game-rule API. Each server reads and sets them; this only decides what to put back, and what to say.
 */
public final class WorldSettings {
	public static final String DIFFICULTY = "difficulty";
	public static final String DIFFICULTY_LOCKED = "difficulty_locked";
	public static final String DEFAULT_GAMEMODE = "default_gamemode";
	public static final String FORCE_GAMEMODE = "force_gamemode";
	/** The world's own cheats setting. */
	public static final String ALLOW_COMMANDS = "allow_commands";
	/** Open to LAN's commands for players other than the host (on 1.21.11 and 26.1.2, for everyone, host included). */
	public static final String GUEST_COMMANDS = "guest_commands";
	/** Recorded, never put back live: it has no setter, so the world-open gate refuses a changed flag. */
	public static final String HARDCORE = "hardcore";
	private static final String GAMERULE = "gamerule/";
	private static final String GAMEMODE = "gamemode/";
	/** The gamemode a player without an entry of their own is held at. Absent: no player's gamemode is held. */
	public static final String ANY_PLAYER = GAMEMODE + "*";
	/** Where a hardcore death puts a player. */
	private static final String SPECTATOR = "spectator";

	/** One setting to set back to its committed value. */
	public record Revert(String name, String committed, String current) {
	}

	private WorldSettings() {
	}

	public static String gamerule(String id) {
		return GAMERULE + id;
	}

	public static String gamemode(UUID player) {
		return GAMEMODE + player;
	}

	/** The rule id of a {@link #gamerule} key, or null for any other key. */
	public static String ruleId(String name) {
		return name.startsWith(GAMERULE) ? name.substring(GAMERULE.length()) : null;
	}

	/** The player of a {@link #gamemode(UUID)} key, or null for any other key. */
	public static UUID player(String name) {
		return name.startsWith(GAMEMODE) && !name.equals(ANY_PLAYER) ? UUID.fromString(name.substring(GAMEMODE.length())) : null;
	}

	/**
	 * The current settings that differ from the committed ones, in name order. A setting that isn't in both is left
	 * alone: a rule a mod added after the commit wasn't committed, and a rule that's gone can't be set. A player is held
	 * at their own committed gamemode, else at {@link #ANY_PLAYER}'s, else not at all; a committed hardcore world's
	 * spectators are left alone, since that's where dying puts them.
	 */
	public static List<Revert> reverts(SortedMap<String, String> committed, Map<String, String> current) {
		boolean hardcore = Boolean.parseBoolean(committed.get(HARDCORE));
		List<Revert> reverts = new ArrayList<>();
		new TreeMap<>(current).forEach((name, now) -> {
			if (name.equals(HARDCORE) || name.equals(ANY_PLAYER)) {
				return;
			}
			String want;
			if (name.startsWith(GAMEMODE)) {
				want = committed.getOrDefault(name, committed.get(ANY_PLAYER));
				if (hardcore && SPECTATOR.equals(now)) {
					return;
				}
			} else {
				want = committed.get(name);
			}
			if (want != null && !want.equals(now)) {
				reverts.add(new Revert(name, want, now));
			}
		});
		return reverts;
	}

	/** What the players are told when {@code name} was put back. */
	public static String message(String name) {
		return "Commitment: " + label(name) + (name.equals(ALLOW_COMMANDS) || name.equals(GUEST_COMMANDS) ? " are locked" : " is locked");
	}

	private static String label(String name) {
		String rule = ruleId(name);
		if (rule != null) {
			return "game rule " + rule;
		}
		if (name.startsWith(GAMEMODE)) {
			return "gamemode";
		}
		return switch (name) {
			case DIFFICULTY -> "difficulty";
			case DIFFICULTY_LOCKED -> "the difficulty lock";
			case DEFAULT_GAMEMODE -> "the default gamemode";
			case FORCE_GAMEMODE -> "forced gamemode";
			case ALLOW_COMMANDS -> "cheats";
			case GUEST_COMMANDS -> "LAN guests' commands";
			default -> name;
		};
	}

	/**
	 * At most one message per setting per {@link #WINDOW_TICKS}, so a datapack or mod that keeps setting a rule doesn't
	 * flood the chat. Every player's gamemode shares one window. Game ticks, so it reads the same at any tick rate.
	 */
	public static final class Throttle {
		public static final long WINDOW_TICKS = 200;
		private final Map<String, Long> lastSaid = new HashMap<>();

		public boolean allow(String name, long tick) {
			String key = label(name);
			Long last = lastSaid.get(key);
			if (last != null && tick - last < WINDOW_TICKS) {
				return false;
			}
			lastSaid.put(key, tick);
			return true;
		}
	}
}
