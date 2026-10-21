package com.wulfic.commitment.gametest;

import com.wulfic.commitment.Commitment;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The client world check (AGENTS.md, rung 5): a real client creates a <b>new</b> singleplayer world through the
 * create-world flow, joins it, and plays a few ticks with Commitment loaded. The harness's verdict is the PASS line
 * this logs, not Gradle's exit code.
 */
public class CommitmentClientGameTest implements FabricClientGameTest {
	static final String PASS = "Commitment client world check: PASS";

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			//? if >=26.2 {
			/*world.getConnection().waitForChunksDownload();
			*///?} else {
			world.getClientLevel().waitForChunksDownload();
			//?}
			context.waitTicks(20);
			boolean inWorld = context.computeOnClient(client -> client.level != null && client.player != null);
			check(inWorld, "the client is not in the world it created");
			boolean serverHasMod = world.getServer().computeOnServer(server -> server.isRunning()
					&& FabricLoader.getInstance().isModLoaded(Commitment.MOD_ID));
			check(serverHasMod, "the integrated server isn't running with " + Commitment.MOD_ID);
		}
		Commitment.LOGGER.info(PASS);
	}

	private static void check(boolean ok, String message) {
		if (!ok) {
			Commitment.LOGGER.error("Commitment client world check failed: {}", message);
			throw new AssertionError(message);
		}
	}
}
