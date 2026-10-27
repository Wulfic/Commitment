package com.wulfic.commitment.gametest.fabric;

import com.wulfic.commitment.gametest.PackDriver;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/** Runs {@link PackDriver} on Fabric's client tick, in a pack run only. NeoForge and Forge don't compile this package. */
public final class PackDriverFabric implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		if (PackDriver.active()) {
			ClientTickEvents.END_CLIENT_TICK.register(PackDriver::tick);
		}
	}
}
