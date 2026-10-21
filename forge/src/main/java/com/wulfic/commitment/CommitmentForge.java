package com.wulfic.commitment;

import static com.wulfic.commitment.Commitment.MOD_ID;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.versions.forge.ForgeVersion;

/** Forge's entrypoint. */
@Mod(MOD_ID)
public final class CommitmentForge {
	public CommitmentForge(FMLJavaModLoadingContext context) {
		Commitment.logLoaded(context.getContainer().getModInfo().getVersion().toString(), "Forge", ForgeVersion.getVersion());
	}
}
