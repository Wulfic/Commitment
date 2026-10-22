package com.wulfic.commitment.lock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Opens a world's lock the way both the world-open gate and server start need it: reads the records and repairs them
 * ({@link LockStore#load}), then puts the guard pack back if it's gone. Any surviving piece restores the others.
 */
public final class WorldLock {
	/** What may be done to a guard pack that's there but stale (an older Commitment wrote it). */
	public enum Repair {
		/** Before the world loads (the gate): nothing has the pack open, so a stale one is replaced. */
		REPLACE_STALE,
		/**
		 * On a running server, which holds the pack open: Windows can't replace it then, so only a missing pack is
		 * written. The next world open replaces a stale one.
		 */
		ONLY_IF_MISSING
	}

	private WorldLock() {
	}

	/**
	 * @param seed the world's seed when known, so a world whose record and guard pack were both deleted still finds its
	 *             mirror
	 * @throws IOException a piece exists but can't be read or repaired; the caller keeps the world closed
	 */
	public static LockStore.Loaded open(Path worldDir, LockStore store, OptionalLong seed, Repair repair) throws IOException {
		Optional<UUID> guard = GuardPack.read(worldDir);
		LockStore.Loaded loaded = store.load(worldDir, guard, seed);
		if (loaded instanceof LockStore.Loaded.Locked locked
				&& (repair == Repair.REPLACE_STALE || !Files.exists(worldDir.resolve(GuardPack.FILE), LinkOption.NOFOLLOW_LINKS))) {
			// A refusal (a stranger's file at the guard path) is logged by GuardPack; the world stays locked either way.
			GuardPack.write(worldDir, locked.record().lockId());
		}
		return loaded;
	}
}
