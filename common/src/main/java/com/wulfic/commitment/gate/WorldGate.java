package com.wulfic.commitment.gate;

import com.wulfic.commitment.Commitment;
import com.wulfic.commitment.lock.LockLifter;
import com.wulfic.commitment.lock.LockRecord;
import com.wulfic.commitment.lock.LockStore;
import com.wulfic.commitment.lock.TrustedClock;
import com.wulfic.commitment.lock.UnlockTimer;
import com.wulfic.commitment.lock.WorldLock;
import com.wulfic.commitment.lock.WorldSettings;
import com.wulfic.commitment.manifest.GameEnvironment;
import com.wulfic.commitment.manifest.Manifest;
import com.wulfic.commitment.manifest.ManifestDiff;
import com.wulfic.commitment.manifest.ManifestScanner;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Whether a world may open, decided before any of it loads: the same on the client (the world list) and on a dedicated
 * server (its {@code Main}). Reads the setup and, while an unlock timer runs, internet time, so it can take seconds:
 * the client calls it off the render thread.
 */
public final class WorldGate {
	/** Changes shown on the locked screen or in the server log before "...and K more changes". */
	public static final int MAX_LINES = 12;
	static final String TAMPERED_LINE = "The lock record was damaged or deleted, so the world stays locked whatever its setup";
	/** Hardcore can't be set back while the world runs (no node has a setter), so a changed flag is a setup change. */
	static final String HARDCORE_LINE = "Hardcore changed since /commit (was %s, now %s)";

	public sealed interface Verdict {
		/** Never committed: opens as vanilla would. */
		record Uncommitted() implements Verdict {
		}

		/** Committed, and the setup is the committed one: opens. An unlock timer may be running. */
		record Open(LockRecord record, UnlockTimer.Status timer) implements Verdict {
		}

		/** The setup changed since {@code /commit}, or the record was damaged: stays shut. */
		record Locked(LockRecord record, List<String> changes, UnlockTimer.Status timer) implements Verdict {
		}

		/** The 24 hours are up by internet time: the lock lifts, then the world opens. */
		record Lift(LockRecord record) implements Verdict {
		}

		/** Something couldn't be read, so nothing is known: stays shut (fail closed), and says why. */
		record Failed(String reason) implements Verdict {
		}
	}

	private WorldGate() {
	}

	public static Verdict evaluate(GameEnvironment env, LockStore store, TrustedClock clock) {
		Path world = env.worldDir();
		LockStore.Loaded loaded;
		try {
			// Before the world loads, nothing holds the guard pack open, so a stale one can be replaced.
			loaded = WorldLock.open(world, store, WorldSeed.read(world), WorldLock.Repair.REPLACE_STALE);
		} catch (IOException e) {
			Commitment.LOGGER.error("Commitment: can't open the lock of {}: {}", world, e.getMessage());
			return new Verdict.Failed("can't open the lock: " + e.getMessage());
		}
		if (!(loaded instanceof LockStore.Loaded.Locked locked)) {
			return new Verdict.Uncommitted();
		}
		LockRecord record = locked.record();
		UnlockTimer.Status timer = UnlockTimer.status(record, clock);
		if (timer instanceof UnlockTimer.Status.Lifted) {
			return new Verdict.Lift(record);
		}
		if (record.tampered()) {
			return new Verdict.Locked(record, List.of(TAMPERED_LINE), timer);
		}
		List<String> changes = new ArrayList<>();
		String committedHardcore = record.worldSettings().get(WorldSettings.HARDCORE);
		if (committedHardcore != null) {
			boolean was = Boolean.parseBoolean(committedHardcore);
			boolean now;
			try {
				now = WorldSeed.hardcore(world);
			} catch (IOException e) {
				Commitment.LOGGER.error("Commitment: can't read whether {} is hardcore from its {}: {}", world, WorldSeed.LEVEL_DAT, e.getMessage());
				return new Verdict.Failed("can't read " + WorldSeed.LEVEL_DAT + ": " + e.getMessage());
			}
			if (now != was) {
				changes.add(HARDCORE_LINE.formatted(onOff(was), onOff(now)));
			}
		}
		Manifest current;
		try {
			current = ManifestScanner.scan(env);
		} catch (IOException | RuntimeException e) {
			Commitment.LOGGER.error("Commitment: can't read the setup of {}: {}", world, e.getMessage());
			return new Verdict.Failed("can't read the setup: " + e.getMessage());
		}
		ManifestDiff diff = record.manifest().diff(current);
		if (!diff.isEmpty()) {
			changes.addAll(diff.lines(MAX_LINES - changes.size()));
		}
		return changes.isEmpty() ? new Verdict.Open(record, timer) : new Verdict.Locked(record, changes, timer);
	}

	private static String onOff(boolean flag) {
		return flag ? "on" : "off";
	}

	/**
	 * Lifts a lock whose 24 hours are up ({@link Verdict.Lift}): renames its files away, all or nothing (B.6).
	 *
	 * @return empty when lifted; otherwise why not, and then nothing was renamed (LockLifter logs each file, and refuses
	 *         a plan that isn't allowed)
	 */
	public static Optional<String> lift(Path worldDir, LockStore store, LockRecord record) {
		try {
			LockLifter.lift(LockLifter.plan(worldDir, store, record.lockId()));
			return Optional.empty();
		} catch (IOException e) {
			return Optional.of(e.getMessage());
		}
	}
}
