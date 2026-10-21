package com.wulfic.commitment;

import net.minecraft.SharedConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The mod's identity, shared by every loader. Nothing here may touch a loader's API. */
public final class Commitment {
	public static final String MOD_ID = "commitment";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private Commitment() {
	}

	/**
	 * Logs the boot fingerprint every loader's entrypoint ends with. The dev-server and real-server smokes expect it,
	 * so a jar that loaded on the wrong loader or Minecraft version shows it in the log.
	 */
	public static void logLoaded(String modVersion, String loader, String loaderVersion) {
		LOGGER.info(loadedLine(modVersion, loader, loaderVersion, SharedConstants.getCurrentVersion().name()));
	}

	static String loadedLine(String modVersion, String loader, String loaderVersion, String minecraft) {
		return "Commitment " + modVersion + " loaded on " + loader + " " + loaderVersion + ", Minecraft " + minecraft;
	}
}
