package com.wulfic.commitment.gametest;

import com.wulfic.commitment.Commitment;
import com.wulfic.commitment.gate.WorldSeed;
import com.wulfic.commitment.lock.LockRecord;
import com.wulfic.commitment.lock.LockStore;
import com.wulfic.commitment.manifest.GameEnvironment;
import com.wulfic.commitment.manifest.ManifestScanner;
import com.wulfic.commitment.platform.Platform;
import com.wulfic.commitment.server.CommitmentServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.TitleScreen;

/**
 * The client's world-open gate (C.2) on a real client: a committed world whose setup matches opens through the world
 * list's own {@code openWorld}; one config file added, the same call shows the refusal and no world loads. Until C.3
 * adds {@code /commit}, the test writes the lock record itself, the way {@code /commit confirm} will.
 */
public class WorldGateClientGameTest implements FabricClientGameTest {
	static final String PASS = "Commitment client gate check: PASS";

	@Override
	public void runTest(ClientGameTestContext context) {
		TestWorldSave save;
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			save = world.getWorldSave();
		}
		Path worldDir = save.getSaveDirectory().toAbsolutePath().normalize();
		String levelId = worldDir.getFileName().toString();
		Path gameDir = context.computeOnClient(client -> client.gameDirectory.toPath().toAbsolutePath().normalize());
		LockStore store = CommitmentServer.store();
		LockRecord record = commit(store, gameDir, worldDir);
		Path extraConfig = gameDir.resolve("config").resolve("commitment-client-gate-test.json");
		try {
			try (TestSingleplayerContext world = save.open()) {
				context.waitTicks(20);
				check(context.computeOnClient(client -> client.level != null) && world.getServer().computeOnServer(server -> server.isRunning()),
						"the committed world with its setup unchanged didn't open");
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
			throw new AssertionError("can't write " + extraConfig, e);
		} finally {
			cleanUp(store, record, extraConfig);
		}
		Commitment.LOGGER.info(PASS);
	}

	/** What {@code /commit confirm} will write (C.3): the record and its mirror, for the setup as it is now. */
	private static LockRecord commit(LockStore store, Path gameDir, Path worldDir) {
		try {
			GameEnvironment env = Platform.environment(GameEnvironment.Side.CLIENT, gameDir, worldDir);
			long seed = WorldSeed.read(worldDir).orElseThrow(() -> new AssertionError("no seed in " + worldDir));
			LockRecord record = LockRecord.committed(UUID.randomUUID(), seed, worldDir.getFileName().toString(),
					System.currentTimeMillis(), ManifestScanner.scan(env), new TreeMap<>());
			store.create(worldDir, record);
			return record;
		} catch (IOException e) {
			throw new AssertionError("can't commit " + worldDir, e);
		}
	}

	/** The world sits in the disposable client-test run dir; the mirror and the config file are outside it. */
	private static void cleanUp(LockStore store, LockRecord record, Path extraConfig) {
		try {
			Files.deleteIfExists(extraConfig);
			Path mirror = store.mirrorFile(record.lockId());
			if (mirror != null && Files.deleteIfExists(mirror)) {
				Commitment.LOGGER.info("Commitment client gate check: removed the test mirror {}", mirror);
			}
		} catch (IOException e) {
			Commitment.LOGGER.error("Commitment client gate check: cleanup failed, remove {} and the mirror of {} by hand",
					extraConfig, record.lockId(), e);
		}
	}

	private static void check(boolean ok, String message) {
		if (!ok) {
			Commitment.LOGGER.error("Commitment client gate check failed: {}", message);
			throw new AssertionError(message);
		}
	}
}
