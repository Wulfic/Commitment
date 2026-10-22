package com.wulfic.commitment.lock;

import com.wulfic.commitment.Commitment;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Where the lock mirror lives: outside the game instance, so deleting {@code config/}, the mod or the instance leaves it.
 * Windows {@code %LOCALAPPDATA%\Commitment\locks}, macOS {@code ~/Library/Application Support/Commitment/locks}, other
 * systems {@code $XDG_DATA_HOME/commitment/locks} (default {@code ~/.local/share}).
 */
public final class MirrorDir {
	/**
	 * Set by the dev and GameTest runs (each loader's build.gradle), so development never writes into the developer's
	 * own user data dir. Honoured in a development run only: in a released game one JVM flag would hide every world's
	 * mirror, and a world whose record and guard pack were deleted would no longer be found by its seed.
	 */
	static final String OVERRIDE = "commitment.mirrorDir";

	private MirrorDir() {
	}

	/** @param development whether the loader says this is a development run ({@link #OVERRIDE} works only then) */
	public static Path forThisMachine(boolean development) {
		String override = System.getProperty(OVERRIDE);
		if (override != null && !override.isBlank()) {
			if (development) {
				return Path.of(override).toAbsolutePath();
			}
			Commitment.LOGGER.warn("Commitment: ignoring -D{}: it only works in a development run", OVERRIDE);
		}
		return resolve(System.getProperty("os.name", ""), System.getenv(), System.getProperty("user.home"));
	}

	/** Null when there's no home dir to put it in: the caller keeps the lock in the world only, and logs that. */
	static Path resolve(String osName, Map<String, String> env, String userHome) {
		String os = osName.toLowerCase(Locale.ROOT);
		if (os.startsWith("windows")) {
			String local = env.get("LOCALAPPDATA");
			if (absolute(local)) {
				return Path.of(local, "Commitment", "locks");
			}
			return absolute(userHome) ? Path.of(userHome, "AppData", "Local", "Commitment", "locks") : null;
		}
		if (os.startsWith("mac")) {
			return absolute(userHome) ? Path.of(userHome, "Library", "Application Support", "Commitment", "locks") : null;
		}
		String xdg = env.get("XDG_DATA_HOME");
		if (absolute(xdg)) {
			// The XDG spec: a relative value is invalid and must be ignored.
			return Path.of(xdg, "commitment", "locks");
		}
		return absolute(userHome) ? Path.of(userHome, ".local", "share", "commitment", "locks") : null;
	}

	private static boolean absolute(String dir) {
		return dir != null && !dir.isBlank() && (dir.startsWith("/") || Path.of(dir).isAbsolute());
	}
}
