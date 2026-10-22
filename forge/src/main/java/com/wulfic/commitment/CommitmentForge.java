package com.wulfic.commitment;

import static com.wulfic.commitment.Commitment.MOD_ID;

import com.wulfic.commitment.manifest.GameEnvironment;
import com.wulfic.commitment.platform.Platform;
import com.wulfic.commitment.server.CommitmentServer;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.versions.forge.ForgeVersion;

/** Forge's entrypoint. */
@Mod(MOD_ID)
public final class CommitmentForge {
	public CommitmentForge(FMLJavaModLoadingContext context) {
		Platform.install(new ForgePlatform(ForgeVersion.getVersion()));
		ServerStartedEvent.BUS.addListener(event -> CommitmentServer.started(event.getServer()));
		ServerStoppedEvent.BUS.addListener(event -> CommitmentServer.stopped(event.getServer()));
		TickEvent.ServerTickEvent.Post.BUS.addListener(event -> CommitmentServer.tick(event.server()));
		Commitment.logLoaded(context.getContainer().getModInfo().getVersion().toString(), "Forge", ForgeVersion.getVersion());
	}

	/** Forge's mod list, for the manifest. Forge 26.x's ModList is all static (gotchas.md 2026-10-21). */
	private record ForgePlatform(String version) implements Platform.Loader {
		/** These have manifest entries of their own (Minecraft and the loader). */
		private static final Set<String> VERSION_ONLY = Set.of("minecraft", "forge");

		@Override
		public String name() {
			return "Forge";
		}

		@Override
		public boolean development() {
			return !FMLEnvironment.production;
		}

		@Override
		public List<GameEnvironment.ModFile> mods() {
			return ModList.getMods().stream().map(ForgePlatform::modFile).toList();
		}

		/** A jar-in-jar mod's path is inside another jar's file system: it's locked by version, like a nested Fabric mod. */
		private static GameEnvironment.ModFile modFile(IModInfo mod) {
			Path file = mod.getOwningFile().getFile().getFilePath();
			Path jar = VERSION_ONLY.contains(mod.getModId()) || file.getFileSystem() != FileSystems.getDefault() ? null : file;
			return new GameEnvironment.ModFile(mod.getModId(), mod.getVersion().toString(), jar);
		}
	}
}
