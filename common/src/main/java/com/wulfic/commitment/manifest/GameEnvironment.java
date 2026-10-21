package com.wulfic.commitment.manifest;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * What a loader hands the scanner: its mod list and the instance's paths. Each loader's entrypoint builds one, so
 * nothing in {@link ManifestScanner} needs a loader API.
 *
 * @param gameDir  the instance (client) or server directory: {@code config/}, {@code options.txt}, {@code resourcepacks/}
 * @param worldDir the world being opened or committed: {@code datapacks/}, {@code serverconfig/}
 */
public record GameEnvironment(
		Side side, String minecraftVersion, String loaderName, String loaderVersion, List<ModFile> mods,
		Path gameDir, Path worldDir) {
	public enum Side {
		CLIENT, DEDICATED_SERVER
	}

	/**
	 * One entry of the loader's mod list.
	 *
	 * @param jar the mod's own jar, or null for a nested (jar-in-jar) or built-in mod, which is locked by version only
	 */
	public record ModFile(String id, String version, Path jar) {
		public ModFile {
			Objects.requireNonNull(id, "id");
			Objects.requireNonNull(version, "version");
		}
	}

	public GameEnvironment {
		Objects.requireNonNull(side, "side");
		Objects.requireNonNull(minecraftVersion, "minecraftVersion");
		Objects.requireNonNull(loaderName, "loaderName");
		Objects.requireNonNull(loaderVersion, "loaderVersion");
		Objects.requireNonNull(gameDir, "gameDir");
		Objects.requireNonNull(worldDir, "worldDir");
		mods = List.copyOf(mods);
	}
}
