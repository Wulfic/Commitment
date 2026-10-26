package com.wulfic.commitment.gametest;

import com.wulfic.commitment.Commitment;
import com.wulfic.commitment.lock.GuardPack;
import com.wulfic.commitment.lock.LockCodec;
import com.wulfic.commitment.lock.LockStore;
import com.wulfic.commitment.server.CommitmentServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.server.MinecraftServer;

/**
 * {@code /commit} and the client's world-open gate (C.2, C.3c) on a real client. The host commits a world with cheats
 * off by typing {@code /commit} and {@code /commit confirm} in chat; the world reopens through the world list's own
 * {@code openWorld}, which proves the setup {@code /commit} scanned is the one the gate scans. One config file added,
 * the same call shows the refusal and no world loads.
 */
public class WorldGateClientGameTest implements FabricClientGameTest {
	static final String PASS = "Commitment client gate check: PASS";
	/** The scan runs off the server thread; a minute is far more than a dev instance needs. */
	private static final int REPLY_TICKS = 20 * 60;

	/** Every system message the client got, in order. Fabric events can't be unregistered, so it's registered once. */
	private static final List<String> RECEIVED = new CopyOnWriteArrayList<>();

	static {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) {
				RECEIVED.add(message.getString());
			}
		});
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		Path gameDir = context.computeOnClient(client -> client.gameDirectory.toPath().toAbsolutePath().normalize());
		// Committed with the world: a /commit that scanned any other dir than the gate's wouldn't see it, and the reopen
		// below would be refused.
		Path committedConfig = gameDir.resolve("config").resolve("commitment-client-commit-test.json");
		Path extraConfig = gameDir.resolve("config").resolve("commitment-client-gate-test.json");
		Path worldDir = null;
		try {
			Files.createDirectories(committedConfig.getParent());
			Files.writeString(committedConfig, "{}");
			TestWorldSave save;
			try (TestSingleplayerContext world = context.worldBuilder().adjustSettings(ui -> ui.setAllowCommands(false)).create()) {
				worldDir = world.getServer().computeOnServer(CommitmentServer::worldDir);
				check(!world.getServer().computeOnServer(server -> server.getWorldData().isAllowCommands()), "the test world has cheats on");
				command(context, "commit", line -> line.matches("Commitment: /commit locks this world's setup: .*, [1-9][0-9]* config files?\\."));
				command(context, "commit confirm", line -> line.startsWith("Commitment: world committed ("));
				check(Files.isRegularFile(worldDir.resolve(LockStore.WORLD_RECORD)), "/commit confirm wrote no lock record");
				check(Files.isRegularFile(worldDir.resolve(GuardPack.FILE)), "/commit confirm wrote no guard pack");
				waitOnServer(context, world, server -> server.getPackRepository().getSelectedIds().contains(CommitmentServer.GUARD_PACK_ID),
						"the guard pack wasn't enabled after /commit confirm");
				check(world.getServer().computeOnServer(CommitmentServer::beating), "no heartbeat after /commit confirm");
				save = world.getWorldSave();
			}
			String levelId = worldDir.getFileName().toString();

			try (TestSingleplayerContext world = save.open()) {
				context.waitTicks(20);
				check(context.computeOnClient(client -> client.level != null) && world.getServer().computeOnServer(server -> server.isRunning()),
						"the world /commit committed didn't open with its setup unchanged");
			}

			Files.createDirectories(extraConfig.getParent());
			Files.writeString(extraConfig, "{}");
			context.runOnClient(client -> client.createWorldOpenFlows().openWorld(levelId, () -> client.setScreenAndShow(new TitleScreen())));
			context.waitForScreen(AlertScreen.class);
			check(context.computeOnClient(client -> client.level == null && client.getSingleplayerServer() == null),
					"a world was loaded behind the refusal");
			context.clickScreenButton("gui.back");
			context.waitForScreen(TitleScreen.class);
		} catch (IOException e) {
			throw new AssertionError("can't write the test config files " + committedConfig + ", " + extraConfig, e);
		} finally {
			cleanUp(CommitmentServer.store(), worldDir, List.of(committedConfig, extraConfig));
		}
		Commitment.LOGGER.info(PASS);
	}

	/** Types {@code /<command>} in chat as the host and waits for the reply it should get. */
	private static void command(ClientGameTestContext context, String command, Predicate<String> reply) {
		int from = RECEIVED.size();
		context.runOnClient(client -> client.player.connection.sendCommand(command));
		try {
			context.waitFor(client -> RECEIVED.subList(from, RECEIVED.size()).stream().anyMatch(reply), REPLY_TICKS);
		} catch (AssertionError e) {
			Commitment.LOGGER.error("Commitment client gate check failed: no expected reply to /{}; got {}", command,
					RECEIVED.subList(from, RECEIVED.size()));
			throw e;
		}
	}

	private static void waitOnServer(ClientGameTestContext context, TestSingleplayerContext world, Predicate<MinecraftServer> ok,
			String message) {
		for (int tick = 0; tick < REPLY_TICKS; tick++) {
			if (world.getServer().computeOnServer(ok::test)) {
				return;
			}
			context.waitTicks(1);
		}
		check(false, message);
	}

	/**
	 * The world sits in the disposable client-test run dir; the mirror and the config file are outside it. A mirror
	 * left behind would commit the next run's world: the test world has the same folder name and seed every run.
	 */
	private static void cleanUp(LockStore store, Path worldDir, List<Path> configFiles) {
		for (Path file : configFiles) {
			try {
				Files.deleteIfExists(file);
			} catch (IOException e) {
				Commitment.LOGGER.error("Commitment client gate check: cleanup failed, remove {} by hand", file, e);
			}
		}
		if (worldDir == null) {
			return;
		}
		Optional<UUID> lockId = Optional.empty();
		try {
			lockId = lockIdOf(worldDir);
			Path mirror = lockId.map(store::mirrorFile).orElse(null);
			if (mirror != null && Files.deleteIfExists(mirror)) {
				Commitment.LOGGER.info("Commitment client gate check: removed the test mirror {}", mirror);
			}
		} catch (IOException e) {
			Commitment.LOGGER.error("Commitment client gate check: cleanup failed, remove the mirror of {} ({}) by hand", worldDir,
					lockId.map(UUID::toString).orElse("lock unknown"), e);
		}
	}

	/** Read only: the guard pack's lock id, or else the world record's, for a /commit confirm that stopped halfway. */
	private static Optional<UUID> lockIdOf(Path worldDir) throws IOException {
		Optional<UUID> fromPack = GuardPack.read(worldDir);
		if (fromPack.isPresent()) {
			return fromPack;
		}
		Path record = worldDir.resolve(LockStore.WORLD_RECORD);
		if (Files.isRegularFile(record) && LockCodec.decode(Files.readAllBytes(record)) instanceof LockCodec.Decoded.Valid valid) {
			return Optional.of(valid.record().lockId());
		}
		return Optional.empty();
	}

	private static void check(boolean ok, String message) {
		if (!ok) {
			Commitment.LOGGER.error("Commitment client gate check failed: {}", message);
			throw new AssertionError(message);
		}
	}
}
