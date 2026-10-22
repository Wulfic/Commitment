package com.wulfic.commitment;

import static com.wulfic.commitment.Commitment.MOD_ID;

import com.wulfic.commitment.command.CommitmentCommands;
import com.wulfic.commitment.platform.Platform;
import com.wulfic.commitment.server.CommitmentServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

/** Fabric's entrypoint. Quilt runs this jar too, so the boot line names whichever loader is actually running. */
public final class CommitmentFabric implements ModInitializer {
	private static final String QUILT_LOADER = "quilt_loader";

	@Override
	public void onInitialize() {
		FabricLoader loader = FabricLoader.getInstance();
		boolean quilt = loader.isModLoaded(QUILT_LOADER);
		FabricPlatform platform = new FabricPlatform(quilt ? "Quilt" : "Fabric", version(loader, quilt ? QUILT_LOADER : "fabricloader"));
		Platform.install(platform);
		ServerLifecycleEvents.SERVER_STARTED.register(CommitmentServer::started);
		ServerLifecycleEvents.SERVER_STOPPED.register(CommitmentServer::stopped);
		ServerTickEvents.END_SERVER_TICK.register(CommitmentServer::tick);
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> CommitmentCommands.register(dispatcher));
		Commitment.logLoaded(version(loader, MOD_ID), platform.name(), platform.version());
	}

	private static String version(FabricLoader loader, String modId) {
		return loader.getModContainer(modId).map(ModContainer::getMetadata)
				.orElseThrow(() -> new IllegalStateException("Fabric lists no mod " + modId))
				.getVersion().getFriendlyString();
	}
}
