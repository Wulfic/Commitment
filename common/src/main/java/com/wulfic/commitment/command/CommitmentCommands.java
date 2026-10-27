package com.wulfic.commitment.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.wulfic.commitment.Commitment;
import com.wulfic.commitment.lock.InternetTime;
import com.wulfic.commitment.lock.LockStore;
import com.wulfic.commitment.lock.TrustedClock;
import com.wulfic.commitment.manifest.GameEnvironment;
import com.wulfic.commitment.platform.Platform;
import com.wulfic.commitment.server.CommitmentServer;
import com.wulfic.commitment.server.SettingsLock;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Set;
import java.util.SortedMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

/**
 * {@code /commit [confirm|status]} and {@code /unlock [confirm|cancel]}, registered by each loader. Only the console,
 * an owner-level operator or the singleplayer host may run them; cheats needn't be on. The work ({@link CommitActions})
 * scans the setup and may read internet time, so it runs on a thread of its own and its result is applied back on the
 * server thread.
 */
public final class CommitmentCommands {
	static final String BUSY = "Commitment: still working on the last /commit or /unlock in this world; try again in a moment";
	static final String NOTHING_TO_CONFIRM = "Commitment: nothing to confirm; run %s first";
	static final String EXPIRED = "Commitment: that confirm came more than 60 s after %s; run %s again";

	/** Worlds with an action under way, so two can't race on one lock record. */
	private static final Set<Path> WORKING = ConcurrentHashMap.newKeySet();
	private static final Confirmations CONFIRMATIONS = new Confirmations(CommitmentCommands::nanos);

	private static volatile TrustedClock testClock;
	private static volatile LongSupplier testNanos;

	/** One subcommand's work, off the server thread. */
	@FunctionalInterface
	interface Action {
		CommitActions.Reply run(GameEnvironment env, LockStore store, long seed) throws IOException;
	}

	private CommitmentCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("commit").requires(CommitmentCommands::allowed)
				.executes(c -> work(c.getSource(), "/commit", Confirmations.Kind.COMMIT,
						(env, store, seed) -> CommitActions.preview(env, store, seed, clock(), ZoneId.systemDefault())))
				.then(Commands.literal("confirm").executes(c -> {
					// The game settings are read here, on the server thread; the setup scan runs off it.
					SortedMap<String, String> settings = SettingsLock.snapshot(c.getSource().getServer());
					return confirm(c.getSource(), "/commit", Confirmations.Kind.COMMIT,
							(env, store, seed) -> CommitActions.commit(env, store, seed, settings, UUID::randomUUID, System.currentTimeMillis()));
				}))
				.then(Commands.literal("status").executes(c -> work(c.getSource(), "/commit status", null,
						(env, store, seed) -> CommitActions.status(env, store, seed, clock(), ZoneId.systemDefault())))));
		dispatcher.register(Commands.literal("unlock").requires(CommitmentCommands::allowed)
				.executes(c -> work(c.getSource(), "/unlock", Confirmations.Kind.UNLOCK,
						(env, store, seed) -> CommitActions.unlockPreview(env, store, seed, clock(), ZoneId.systemDefault())))
				.then(Commands.literal("confirm").executes(c -> confirm(c.getSource(), "/unlock", Confirmations.Kind.UNLOCK,
						(env, store, seed) -> CommitActions.unlock(env, store, seed, clock(), ZoneId.systemDefault()))))
				.then(Commands.literal("cancel").executes(c -> work(c.getSource(), "/unlock cancel", null, CommitActions::cancel))));
	}

	/**
	 * The console and owner-level operators (a command block or a datapack function runs below that), or the
	 * singleplayer host, who has no operator level with cheats off.
	 */
	static boolean allowed(CommandSourceStack source) {
		if (source.permissions().hasPermission(Permissions.COMMANDS_OWNER)) {
			return true;
		}
		ServerPlayer player = source.getPlayer();
		return player != null && source.getServer().isSingleplayerOwner(player.nameAndId());
	}

	/**
	 * GameTests only: replaces internet time and the confirm window's clock; nulls put the real ones back. Refused
	 * outside a development run, so no other mod can fake the 24 hours in a released game.
	 */
	public static void useTestClocks(TrustedClock clock, LongSupplier nanos) {
		useTestClocks(clock, nanos, Platform.loader().development());
	}

	static void useTestClocks(TrustedClock clock, LongSupplier nanos, boolean development) {
		if (!development) {
			throw new IllegalStateException("Commitment's test clocks work in development runs only");
		}
		testClock = clock;
		testNanos = nanos;
	}

	private static TrustedClock clock() {
		TrustedClock test = testClock;
		return test != null ? test : new InternetTime();
	}

	private static long nanos() {
		LongSupplier test = testNanos;
		return test != null ? test.getAsLong() : System.nanoTime();
	}

	private static int confirm(CommandSourceStack source, String ask, Confirmations.Kind kind, Action action) {
		MinecraftServer server = source.getServer();
		String world = CommitmentServer.worldDir(server).toString();
		switch (CONFIRMATIONS.take(world, sender(source), kind)) {
			case NOTHING_PENDING -> {
				reply(source, true, NOTHING_TO_CONFIRM.formatted(ask));
				return 0;
			}
			case EXPIRED -> {
				reply(source, true, EXPIRED.formatted(ask, ask));
				return 0;
			}
			case CONFIRMED -> {
				return work(source, ask + " confirm", null, action);
			}
		}
		throw new IllegalStateException("unreachable");
	}

	/** @param ask the confirm window a successful reply opens, or null */
	private static int work(CommandSourceStack source, String what, Confirmations.Kind ask, Action action) {
		MinecraftServer server = source.getServer();
		Path world = CommitmentServer.worldDir(server);
		if (!WORKING.add(world)) {
			reply(source, true, BUSY);
			return 0;
		}
		GameEnvironment env;
		long seed;
		try {
			env = CommitmentServer.environment(server);
			seed = server.overworld().getSeed();
		} catch (RuntimeException e) {
			WORKING.remove(world);
			throw e;
		}
		LockStore store = CommitmentServer.store();
		String sender = sender(source);
		Commitment.LOGGER.info("Commitment: {} ran {} in {}", source.getTextName(), what, world);
		CompletableFuture.supplyAsync(() -> {
					try {
						return action.run(env, store, seed);
					} catch (IOException e) {
						throw new UncheckedIOException(e);
					} finally {
						WORKING.remove(world);
					}
				}, task -> Thread.ofPlatform().daemon().name("Commitment command").start(task))
				.whenComplete((result, error) -> {
					if (error != null) {
						Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
						Commitment.LOGGER.error("Commitment: {} in {} failed", what, world, cause);
					}
					server.execute(() -> {
						if (error != null) {
							reply(source, true, "Commitment: " + what + " failed (" + rootMessage(error) + "); see the log");
							return;
						}
						apply(source, server, world, sender, ask, result);
					});
				});
		return Command.SINGLE_SUCCESS;
	}

	/** Back on the server thread. */
	private static void apply(CommandSourceStack source, MinecraftServer server, Path world, String sender, Confirmations.Kind ask,
			CommitActions.Reply result) {
		for (String line : result.lines()) {
			reply(source, result.failure(), line);
		}
		switch (result.effect()) {
			case ASK -> CONFIRMATIONS.ask(world.toString(), sender, ask);
			case COMMITTED -> {
				CommitmentServer.beat(server);
				CommitmentServer.select(server);
				CommitmentServer.hold(server, result.settings());
			}
			case NONE -> {
			}
		}
	}

	/** Every reply is logged too, so a run's log says what each player was told. */
	private static void reply(CommandSourceStack source, boolean failure, String line) {
		if (failure) {
			Commitment.LOGGER.warn("Commitment: to {}: {}", source.getTextName(), line);
			source.sendFailure(Component.literal(line));
		} else {
			Commitment.LOGGER.info("Commitment: to {}: {}", source.getTextName(), line);
			source.sendSuccess(() -> Component.literal(line), false);
		}
	}

	private static String sender(CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		return player != null ? player.getUUID().toString() : "server";
	}

	private static String rootMessage(Throwable error) {
		Throwable t = error;
		while (t.getCause() != null && (t instanceof CompletionException || t instanceof UncheckedIOException)) {
			t = t.getCause();
		}
		return String.valueOf(t.getMessage());
	}
}
