package com.wulfic.commitment;

import com.wulfic.commitment.manifest.GameEnvironment;
import com.wulfic.commitment.platform.Platform;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.ModOrigin;

/** Fabric's (and Quilt's) mod list, for the manifest. */
record FabricPlatform(String name, String version) implements Platform.Loader {
	@Override
	public boolean development() {
		return FabricLoader.getInstance().isDevelopmentEnvironment();
	}

	@Override
	public boolean physicalClient() {
		return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
	}

	@Override
	public List<GameEnvironment.ModFile> mods() {
		return FabricLoader.getInstance().getAllMods().stream().map(FabricPlatform::modFile).toList();
	}

	/**
	 * A mod's own jar when it has exactly one. Built-in mods (Minecraft, Java, the loader) have their own manifest entries,
	 * and a nested mod's file lives inside another jar, so those are locked by version.
	 */
	private static GameEnvironment.ModFile modFile(ModContainer mod) {
		ModMetadata meta = mod.getMetadata();
		ModOrigin origin = mod.getOrigin();
		Path jar = !"builtin".equals(meta.getType()) && origin.getKind() == ModOrigin.Kind.PATH && origin.getPaths().size() == 1
				? origin.getPaths().getFirst() : null;
		return new GameEnvironment.ModFile(meta.getId(), meta.getVersion().getFriendlyString(), jar);
	}
}
