package com.wulfic.commitment;

import static com.wulfic.commitment.Commitment.MOD_ID;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

/** Fabric's entrypoint. Quilt runs this jar too, so the boot line names whichever loader is actually running. */
public final class CommitmentFabric implements ModInitializer {
	private static final String QUILT_LOADER = "quilt_loader";

	@Override
	public void onInitialize() {
		FabricLoader loader = FabricLoader.getInstance();
		String loaderId = loader.isModLoaded(QUILT_LOADER) ? QUILT_LOADER : "fabricloader";
		Commitment.logLoaded(version(loader, MOD_ID), loaderId.equals(QUILT_LOADER) ? "Quilt" : "Fabric",
				version(loader, loaderId));
	}

	private static String version(FabricLoader loader, String modId) {
		return loader.getModContainer(modId).map(ModContainer::getMetadata)
				.orElseThrow(() -> new IllegalStateException("Fabric lists no mod " + modId))
				.getVersion().getFriendlyString();
	}
}
