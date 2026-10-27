package com.wulfic.commitment.gametest;

import com.wulfic.commitment.command.CommitActions;
import com.wulfic.commitment.command.CommitmentCommands;
import com.wulfic.commitment.lock.GuardPack;
import com.wulfic.commitment.lock.LockRecord;
import com.wulfic.commitment.lock.LockStore;
import com.wulfic.commitment.lock.TrustedClock;
import com.wulfic.commitment.lock.WorldSettings;
import com.wulfic.commitment.server.CommitmentServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * C.3: {@code /commit} and {@code /unlock} through the server's own command dispatcher, as the console types them. C.6:
 * {@code /difficulty} and {@code /gamerule} left alone before the commit and put back within a check after it.
 * One sequential test: every GameTest shares the server's world, and committing it is one-way for the rest of the
 * run. The guard pack it enables stays loaded, so the heartbeat stays on after the test too: the other tests' mock
 * players must not be blinded.
 */
public class CommandsGameTest {
	/** Internet time, as the test clock reads it. */
	private static final long NOW = 1_800_000_000_000L;
	private static final long SECOND = 1_000_000_000L;
	private static final GameRule<Integer> RULE = GameRules.RANDOM_TICK_SPEED;

	@GameTest(maxTicks = 2400)
	public void commitAndUnlockFromTheConsole(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		Path world = CommitmentServer.worldDir(server);
		Console console = new Console();
		CommandSourceStack source = server.createCommandSourceStack().withSource(console);
		AtomicLong nanos = new AtomicLong();
		CommitmentCommands.useTestClocks(() -> new TrustedClock.Reading.At(NOW), nanos::get);
		Difficulty difficulty = server.getWorldData().getDifficulty();
		int speed = server.overworld().getGameRules().get(RULE);
		helper.startSequence()
				.thenExecute(() -> run(server, source, "commit status"))
				.thenWaitUntil(() -> console.next(helper, CommitActions.NOT_COMMITTED))
				.thenExecute(() -> change(server, source, difficulty, speed))
				.thenExecuteAfter(2 * CommitmentServer.CHECK_TICKS + 1, () -> {
					helper.assertFalse(CommitmentServer.holding(server), "an uncommitted world holds no settings");
					helper.assertTrue(server.getWorldData().getDifficulty() == other(difficulty) && server.overworld().getGameRules().get(RULE) == speed + 4,
							"an uncommitted world's /difficulty and /gamerule stay as set");
					run(server, source, "difficulty " + difficulty.getSerializedName());
					run(server, source, "gamerule " + RULE.id() + " " + speed);
				})
				.thenExecute(() -> run(server, source, "commit confirm"))
				.thenWaitUntil(() -> console.next(helper, "Commitment: nothing to confirm; run /commit first"))
				.thenExecute(() -> run(server, source, "commit"))
				.thenWaitUntil(() -> console.next(helper, "Commitment: /commit locks this world's setup: "))
				.thenExecute(() -> run(server, source, "commit confirm"))
				.thenWaitUntil(() -> console.next(helper, "Commitment: world committed ("))
				.thenExecute(() -> {
					helper.assertTrue(Files.isRegularFile(world.resolve(LockStore.WORLD_RECORD)), "the world's lock record is written");
					helper.assertTrue(guardLock(world) != null, "the guard pack is written");
					helper.assertTrue(Files.isRegularFile(CommitmentServer.store().mirrorFile(guardLock(world))), "the mirror is written");
					helper.assertTrue(CommitmentServer.beating(server), "the heartbeat runs");
					helper.assertTrue(CommitmentServer.holding(server), "the committed settings are held");
					helper.assertTrue(String.valueOf(speed).equals(record(server, world).worldSettings().get(WorldSettings.gamerule(RULE.getIdentifier().toString()))),
							"the record holds the rule as it was at /commit confirm");
					change(server, source, difficulty, speed);
				})
				.thenExecuteAfter(CommitmentServer.CHECK_TICKS + 1, () -> {
					helper.assertTrue(server.getWorldData().getDifficulty() == difficulty, "/difficulty is put back within a check, got "
							+ server.getWorldData().getDifficulty());
					helper.assertTrue(server.overworld().getGameRules().get(RULE) == speed, "/gamerule is put back within a check, got "
							+ server.overworld().getGameRules().get(RULE));
				})
				.thenWaitUntil(() -> helper.assertTrue(server.getPackRepository().getSelectedIds().contains(CommitmentServer.GUARD_PACK_ID),
						"the guard pack is enabled at once, not at the next start"))
				.thenExecute(() -> run(server, source, "commit"))
				.thenWaitUntil(() -> console.next(helper, CommitActions.ALREADY_COMMITTED))
				.thenExecute(() -> run(server, source, "unlock"))
				.thenWaitUntil(() -> console.next(helper, "Commitment: /unlock confirm starts the 24-hour unlock"))
				.thenExecute(() -> {
					nanos.addAndGet(61 * SECOND);
					run(server, source, "unlock confirm");
				})
				.thenWaitUntil(() -> console.next(helper, "Commitment: that confirm came more than 60 s after /unlock; run /unlock again"))
				.thenExecute(() -> helper.assertTrue(record(server, world).unlockRequestedAt().isEmpty(), "an expired confirm starts nothing"))
				.thenExecute(() -> run(server, source, "unlock"))
				.thenWaitUntil(() -> console.next(helper, "Commitment: /unlock confirm starts the 24-hour unlock"))
				.thenExecute(() -> run(server, source, "unlock confirm"))
				.thenWaitUntil(() -> console.next(helper, "Commitment: unlock available at "))
				.thenExecute(() -> helper.assertTrue(record(server, world).unlockRequestedAt().equals(OptionalLong.of(NOW)),
						"the timer is saved at internet time"))
				.thenExecute(() -> run(server, source, "unlock cancel"))
				.thenWaitUntil(() -> console.next(helper, CommitActions.CANCELLED))
				.thenExecute(() -> {
					helper.assertTrue(record(server, world).unlockRequestedAt().isEmpty(), "the timer is gone");
					// A command block or a datapack function runs at gamemaster level: not enough.
					Console function = new Console();
					run(server, server.createCommandSourceStack().withPermission(LevelBasedPermissionSet.GAMEMASTER).withSource(function), "unlock confirm");
					helper.assertTrue(!function.lines.isEmpty() && function.lines.stream().noneMatch(l -> l.startsWith("Commitment:")),
							"a gamemaster-level source can't run /unlock, got " + function.lines);
				})
				// The world and its mirror are the GameTest run's own, cleared before the next run (each loader's build).
				.thenExecute(() -> CommitmentCommands.useTestClocks(null, null))
				.thenSucceed();
	}

	/** Another difficulty and the rule +4, through the commands a player would type. */
	private static void change(MinecraftServer server, CommandSourceStack source, Difficulty difficulty, int speed) {
		run(server, source, "difficulty " + other(difficulty).getSerializedName());
		run(server, source, "gamerule " + RULE.id() + " " + (speed + 4));
	}

	private static Difficulty other(Difficulty difficulty) {
		return difficulty == Difficulty.HARD ? Difficulty.EASY : Difficulty.HARD;
	}

	private static void run(MinecraftServer server, CommandSourceStack source, String command) {
		server.getCommands().performPrefixedCommand(source, command);
	}

	private static UUID guardLock(Path world) {
		try {
			return GuardPack.read(world).orElse(null);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static LockRecord record(MinecraftServer server, Path world) {
		try {
			return ((LockStore.Loaded.Locked) CommitmentServer.store().load(world, GuardPack.read(world),
					OptionalLong.of(server.overworld().getSeed()))).record();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** What the console was told, in order; {@link #next} reads past each line it matched. */
	private static final class Console implements CommandSource {
		final List<String> lines = new CopyOnWriteArrayList<>();
		private int read;

		/** Waits (inside {@code thenWaitUntil}) for a line starting with {@code start} after the last one matched. */
		void next(GameTestHelper helper, String start) {
			for (int i = read; i < lines.size(); i++) {
				if (lines.get(i).startsWith(start)) {
					read = i + 1;
					return;
				}
			}
			helper.assertTrue(false, "the console got no '" + start + "' after line " + read + ": " + lines);
		}

		@Override
		public void sendSystemMessage(Component message) {
			lines.add(message.getString());
		}

		@Override
		public boolean acceptsSuccess() {
			return true;
		}

		@Override
		public boolean acceptsFailure() {
			return true;
		}

		@Override
		public boolean shouldInformAdmins() {
			return false;
		}
	}
}
