package com.wulfic.commitment.gametest;

import com.wulfic.commitment.Commitment;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * Runs on a live dedicated server with the mod loaded, so it sees real initialisation order. Server GameTests run on
 * every loader: NeoForge and Forge compile this file with the {@code GameTest} import swapped for their own (plan
 * step C.0), so nothing else here may name a loader API.
 */
public class CommitmentGameTest {
	@GameTest
	public void modIsLoadedOnTheServer(GameTestHelper helper) {
		String line = Commitment.loaded().orElse("");
		helper.assertTrue(line.contains(" loaded on "), "Commitment's entrypoint logged its boot line (got '" + line + "')");
		helper.succeed();
	}
}
