package com.wulfic.commitment.gametest;

import com.wulfic.commitment.Commitment;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTestHelper;

/** Runs on a live dedicated server with the mod loaded, so it sees real initialisation order. */
public class CommitmentGameTest {
	@GameTest
	public void modIsLoadedOnTheServer(GameTestHelper helper) {
		helper.assertTrue(FabricLoader.getInstance().isModLoaded(Commitment.MOD_ID), Commitment.MOD_ID + " is loaded");
		helper.succeed();
	}
}
