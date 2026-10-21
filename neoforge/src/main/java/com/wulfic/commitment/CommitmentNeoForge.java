package com.wulfic.commitment;

import static com.wulfic.commitment.Commitment.MOD_ID;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;

/** NeoForge's entrypoint. */
@Mod(MOD_ID)
public final class CommitmentNeoForge {
	public CommitmentNeoForge(ModContainer container) {
		String neoforge = ModList.get().getModContainerById("neoforge")
				.orElseThrow(() -> new IllegalStateException("NeoForge lists no mod neoforge"))
				.getModInfo().getVersion().toString();
		Commitment.logLoaded(container.getModInfo().getVersion().toString(), "NeoForge", neoforge);
	}
}
