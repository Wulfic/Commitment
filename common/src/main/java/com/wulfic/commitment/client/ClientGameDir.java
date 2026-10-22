package com.wulfic.commitment.client;

import java.nio.file.Path;
import net.minecraft.client.Minecraft;

/**
 * The instance directory the client's setup is read from: {@code --gameDir}, not the JVM's working directory (which is
 * what {@code MinecraftServer.getServerDirectory()} returns, even on the integrated server). The world-open gate and
 * {@code /commit} both read it here, so a commit and the next open scan the same {@code config/}. Client only: call it
 * only when {@code Platform.loader().physicalClient()}.
 */
public final class ClientGameDir {
	private ClientGameDir() {
	}

	public static Path get() {
		return Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath();
	}
}
