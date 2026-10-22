package com.wulfic.commitment.platform;

import com.wulfic.commitment.manifest.GameEnvironment;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import net.minecraft.SharedConstants;

/**
 * The one thing common needs from a loader: its name, its version and its mod list. Each loader's entrypoint installs
 * its own {@link Loader} before anything else of Commitment's runs.
 */
public final class Platform {
	public interface Loader {
		/** As the boot line and the manifest name it: Fabric, Quilt, NeoForge or Forge. */
		String name();

		String version();

		/** A development run (Loom, ModDevGradle, ForgeGradle), not a released game. Gates the dev-only overrides. */
		boolean development();

		/** Every mod the loader runs. A mod without a jar of its own (nested, built in) is locked by version only. */
		List<GameEnvironment.ModFile> mods();
	}

	private static volatile Loader loader;

	private Platform() {
	}

	public static void install(Loader newLoader) {
		Objects.requireNonNull(newLoader, "loader");
		if (loader != null && loader != newLoader) {
			throw new IllegalStateException("a loader is already installed (" + loader.name() + "); two entrypoints ran");
		}
		loader = newLoader;
	}

	public static Loader loader() {
		Loader installed = loader;
		if (installed == null) {
			throw new IllegalStateException("no loader installed: Commitment's entrypoint hasn't run");
		}
		return installed;
	}

	/** The setup the scanner reads, for a world in {@code worldDir} of the instance or server in {@code gameDir}. */
	public static GameEnvironment environment(GameEnvironment.Side side, Path gameDir, Path worldDir) {
		Loader installed = loader();
		return new GameEnvironment(side, SharedConstants.getCurrentVersion().name(), installed.name(), installed.version(),
				installed.mods(), gameDir, worldDir);
	}
}
