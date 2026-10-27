package com.wulfic.commitment.server;

import com.wulfic.commitment.Commitment;
import com.wulfic.commitment.client.IntegratedSettings;
import com.wulfic.commitment.lock.WorldSettings;
import com.wulfic.commitment.platform.Platform;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * The world's game settings on a running server ({@link WorldSettings} names them): read, snapshot at {@code /commit
 * confirm}, and put back through vanilla's own setters, which save them the way they always do. Server thread only.
 */
public final class SettingsLock {
	private SettingsLock() {
	}

	/**
	 * What {@code /commit confirm} locks. On a singleplayer or LAN world committed with cheats off, each player's own
	 * gamemode too: the players online now at theirs, anyone else at the default gamemode. Not on a dedicated server,
	 * where an operator's {@code /gamemode} is the admin's to make, nor with cheats on, where anyone may make it.
	 */
	public static SortedMap<String, String> snapshot(MinecraftServer server) {
		SortedMap<String, String> settings = current(server);
		boolean holdPlayers = integrated(server) && !IntegratedSettings.cheats(server);
		if (holdPlayers) {
			settings.put(WorldSettings.ANY_PLAYER, server.getDefaultGameType().getName());
		} else {
			settings.keySet().removeIf(name -> WorldSettings.player(name) != null || name.equals(WorldSettings.GUEST_COMMANDS));
		}
		return settings;
	}

	/** Every setting as it is now, players included. */
	public static SortedMap<String, String> current(MinecraftServer server) {
		SortedMap<String, String> settings = new TreeMap<>();
		settings.put(WorldSettings.DIFFICULTY, server.getWorldData().getDifficulty().getSerializedName());
		settings.put(WorldSettings.DIFFICULTY_LOCKED, Boolean.toString(server.getWorldData().isDifficultyLocked()));
		settings.put(WorldSettings.DEFAULT_GAMEMODE, server.getDefaultGameType().getName());
		//? if >=26.3 {
		/*settings.put(WorldSettings.FORCE_GAMEMODE, Boolean.toString(server.forceGameMode()));
		*///?}
		settings.put(WorldSettings.HARDCORE, Boolean.toString(server.isHardcore()));
		GameRules rules = server.overworld().getGameRules();
		rules.availableRules().forEach(rule -> settings.put(WorldSettings.gamerule(rule.getIdentifier().toString()), rules.getAsString(rule)));
		if (integrated(server)) {
			if (IntegratedSettings.allowCommandsSettable()) {
				settings.put(WorldSettings.ALLOW_COMMANDS, Boolean.toString(IntegratedSettings.allowCommands(server)));
			}
			settings.put(WorldSettings.GUEST_COMMANDS, Boolean.toString(IntegratedSettings.guestCommands(server)));
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			settings.put(WorldSettings.gamemode(player.getUUID()), player.gameMode().getName());
		}
		return settings;
	}

	/**
	 * Puts back every committed setting that drifted, logs each, and tells everyone (at most once per setting per
	 * {@link WorldSettings.Throttle#WINDOW_TICKS}). Returns how many it put back.
	 */
	public static int check(MinecraftServer server, SortedMap<String, String> committed, WorldSettings.Throttle throttle) {
		List<WorldSettings.Revert> reverts = WorldSettings.reverts(committed, current(server));
		int done = 0;
		for (WorldSettings.Revert revert : reverts) {
			try {
				if (!apply(server, revert)) {
					continue;
				}
			} catch (RuntimeException e) {
				// One setting that can't be set (another mod's rule, say) mustn't stop the others, or the server.
				Commitment.LOGGER.error("Commitment: putting back {} to {} failed; left as it is", revert.name(), revert.committed(), e);
				continue;
			}
			done++;
			Commitment.LOGGER.info("Commitment: put back {}: {} -> {}", revert.name(), revert.current(), revert.committed());
			if (throttle.allow(revert.name(), server.getTickCount())) {
				String message = WorldSettings.message(revert.name());
				Commitment.LOGGER.info(message);
				server.getPlayerList().broadcastSystemMessage(Component.literal(message), false);
			}
		}
		return done;
	}

	/** Sets one setting back. A committed value this version can't read is logged and left, never guessed at. */
	private static boolean apply(MinecraftServer server, WorldSettings.Revert revert) {
		String name = revert.name();
		String value = revert.committed();
		String rule = WorldSettings.ruleId(name);
		if (rule != null) {
			return setRule(server, rule, value);
		}
		UUID playerId = WorldSettings.player(name);
		if (playerId != null) {
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			GameType mode = gameType(value);
			if (player == null || mode == null) {
				return skip(name, value, player == null ? "the player left" : "not a gamemode");
			}
			player.setGameMode(mode);
			return true;
		}
		switch (name) {
			case WorldSettings.DIFFICULTY -> {
				Difficulty difficulty = difficulty(value);
				if (difficulty == null) {
					return skip(name, value, "not a difficulty");
				}
				server.setDifficulty(difficulty, true);
			}
			case WorldSettings.DEFAULT_GAMEMODE -> {
				GameType mode = gameType(value);
				if (mode == null) {
					return skip(name, value, "not a gamemode");
				}
				server.setDefaultGameType(mode);
			}
			default -> {
				Boolean flag = flag(value);
				if (flag == null) {
					return skip(name, value, "not true or false");
				}
				return setFlag(server, name, flag);
			}
		}
		return true;
	}

	private static boolean setFlag(MinecraftServer server, String name, boolean flag) {
		switch (name) {
			case WorldSettings.DIFFICULTY_LOCKED -> server.setDifficultyLocked(flag);
			//? if >=26.3 {
			/*case WorldSettings.FORCE_GAMEMODE -> server.setForceGameMode(flag);
			*///?}
			case WorldSettings.ALLOW_COMMANDS -> {
				if (!integrated(server) || !IntegratedSettings.allowCommandsSettable()) {
					return skip(name, Boolean.toString(flag), "this server can't change it");
				}
				IntegratedSettings.setAllowCommands(server, flag);
			}
			case WorldSettings.GUEST_COMMANDS -> {
				if (!integrated(server)) {
					return skip(name, Boolean.toString(flag), "not a singleplayer or LAN world");
				}
				IntegratedSettings.setGuestCommands(server, flag);
			}
			default -> {
				return skip(name, Boolean.toString(flag), "not a setting this version has");
			}
		}
		return true;
	}

	private static boolean setRule(MinecraftServer server, String id, String value) {
		GameRules rules = server.overworld().getGameRules();
		for (GameRule<?> rule : rules.availableRules().toList()) {
			if (rule.getIdentifier().toString().equals(id)) {
				return setRule(server, rules, rule, value);
			}
		}
		return skip(WorldSettings.gamerule(id), value, "no such rule now");
	}

	private static <T> boolean setRule(MinecraftServer server, GameRules rules, GameRule<T> rule, String value) {
		return rule.deserialize(value).result().map(parsed -> {
			rules.set(rule, parsed, server);
			return true;
		}).orElseGet(() -> skip(WorldSettings.gamerule(rule.getIdentifier().toString()), value, "not a value of that rule"));
	}

	private static boolean skip(String name, String value, String why) {
		Commitment.LOGGER.warn("Commitment: can't put back {} to {} ({}); left as it is", name, value, why);
		return false;
	}

	/** A singleplayer or LAN world. {@link IntegratedSettings} is a client class: only reached on a physical client. */
	private static boolean integrated(MinecraftServer server) {
		return Platform.loader().physicalClient() && !server.isDedicatedServer() && IntegratedSettings.integrated(server);
	}

	private static Difficulty difficulty(String name) {
		for (Difficulty difficulty : Difficulty.values()) {
			if (difficulty.getSerializedName().equals(name)) {
				return difficulty;
			}
		}
		return null;
	}

	private static GameType gameType(String name) {
		for (GameType mode : GameType.values()) {
			if (mode.getName().equals(name)) {
				return mode;
			}
		}
		return null;
	}

	private static Boolean flag(String value) {
		return switch (value) {
			case "true" -> true;
			case "false" -> false;
			default -> null;
		};
	}
}
