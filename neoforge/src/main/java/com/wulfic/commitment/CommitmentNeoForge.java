package com.wulfic.commitment;

import static com.wulfic.commitment.Commitment.MOD_ID;

import com.wulfic.commitment.manifest.GameEnvironment;
import com.wulfic.commitment.platform.Platform;
import com.wulfic.commitment.server.CommitmentServer;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforgespi.language.IModInfo;

/** NeoForge's entrypoint. */
@Mod(MOD_ID)
public final class CommitmentNeoForge {
	public CommitmentNeoForge(ModContainer container) {
		String neoforge = ModList.get().getModContainerById("neoforge")
				.orElseThrow(() -> new IllegalStateException("NeoForge lists no mod neoforge"))
				.getModInfo().getVersion().toString();
		Platform.install(new NeoForgePlatform(neoforge));
		NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, event -> CommitmentServer.started(event.getServer()));
		NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> CommitmentServer.stopped(event.getServer()));
		NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> CommitmentServer.tick(event.getServer()));
		Commitment.logLoaded(container.getModInfo().getVersion().toString(), "NeoForge", neoforge);
	}

	/** NeoForge's mod list, for the manifest. */
	private record NeoForgePlatform(String version) implements Platform.Loader {
		/** These have manifest entries of their own (Minecraft and the loader). */
		private static final Set<String> VERSION_ONLY = Set.of("minecraft", "neoforge");

		@Override
		public String name() {
			return "NeoForge";
		}

		@Override
		public boolean development() {
			return !FMLEnvironment.isProduction();
		}

		@Override
		public List<GameEnvironment.ModFile> mods() {
			return ModList.get().getMods().stream().map(NeoForgePlatform::modFile).toList();
		}

		/** A jar-in-jar mod's path is inside another jar's file system: it's locked by version, like a nested Fabric mod. */
		private static GameEnvironment.ModFile modFile(IModInfo mod) {
			Path file = mod.getOwningFile().getFile().getFilePath();
			Path jar = VERSION_ONLY.contains(mod.getModId()) || file.getFileSystem() != FileSystems.getDefault() ? null : file;
			return new GameEnvironment.ModFile(mod.getModId(), mod.getVersion().toString(), jar);
		}
	}
}
