package com.wulfic.commitment.command;

import com.wulfic.commitment.Commitment;
import com.wulfic.commitment.lock.GuardPack;
import com.wulfic.commitment.lock.LockRecord;
import com.wulfic.commitment.lock.LockStore;
import com.wulfic.commitment.lock.TrustedClock;
import com.wulfic.commitment.lock.UnlockTimer;
import com.wulfic.commitment.lock.WorldLock;
import com.wulfic.commitment.manifest.GameEnvironment;
import com.wulfic.commitment.manifest.Manifest;
import com.wulfic.commitment.manifest.ManifestScanner;
import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * What {@code /commit} and {@code /unlock} do to a world, apart from Minecraft: read and write Commitment's own files
 * (through {@link WorldLock}, {@link LockStore} and {@link GuardPack}) and say what happened. They scan the setup and
 * may read internet time, so {@link CommitmentCommands} runs them off the server thread.
 */
public final class CommitActions {
	public static final String NOT_COMMITTED = "Commitment: this world isn't committed. /commit locks its setup";
	public static final String ALREADY_COMMITTED = "Commitment: this world is already committed";
	public static final String COMMITTED = "Commitment: world committed (%s)";
	public static final String NO_TIMER = "Commitment: no unlock is running; nothing to cancel";
	public static final String CANCELLED = "Commitment: unlock cancelled; the world stays committed";
	public static final String OVER = "Commitment: the 24-hour unlock is over; the lock lifts the next time the world opens";

	/** What the caller does next, besides sending the lines. */
	public enum Effect {
		NONE,
		/** Open the 60-second confirm window. */
		ASK,
		/** The world was committed just now: start the heartbeat and enable the guard pack. */
		COMMITTED
	}

	/** @param failure the lines say why nothing was done (sent as an error) */
	public record Reply(List<String> lines, boolean failure, Effect effect) {
		public Reply {
			lines = List.copyOf(lines);
		}

		static Reply info(String... lines) {
			return new Reply(List.of(lines), false, Effect.NONE);
		}

		static Reply fail(String... lines) {
			return new Reply(List.of(lines), true, Effect.NONE);
		}
	}

	private CommitActions() {
	}

	/** {@code /commit}: what would be locked, and the confirm step. */
	public static Reply preview(GameEnvironment env, LockStore store, long seed, TrustedClock clock, ZoneId zone) throws IOException {
		if (open(env, store, seed) instanceof LockStore.Loaded.Locked locked) {
			return Reply.info(ALREADY_COMMITTED, status(locked.record(), clock, zone));
		}
		Manifest setup = ManifestScanner.scan(env);
		return new Reply(List.of(
				"Commitment: /commit locks this world's setup: " + setup.counts().describe() + ".",
				"Changing any of it later takes a 24-hour unlock, timed by internet time.",
				"Run /commit confirm within 60 s to lock it."), false, Effect.ASK);
	}

	/** {@code /commit confirm}: scans the setup again (it may have changed since {@code /commit}) and locks it. */
	public static Reply commit(GameEnvironment env, LockStore store, long seed, Supplier<UUID> newLockId, long now) throws IOException {
		Path world = env.worldDir();
		if (open(env, store, seed) instanceof LockStore.Loaded.Locked) {
			return Reply.fail(ALREADY_COMMITTED);
		}
		Manifest setup = ManifestScanner.scan(env);
		// World settings are snapshotted here once C.6 reverts them; until then the record holds none.
		LockRecord record = LockRecord.committed(newLockId.get(), seed, world.getFileName().toString(), now, setup, new TreeMap<>());
		store.create(world, record);
		String done = COMMITTED.formatted(setup.counts().describe());
		Commitment.LOGGER.info("{} in {} (lock {})", done, world, record.lockId());
		// The record is written, so the world is committed whatever happens to the pack: the mod enforces the lock, and
		// the next server start tries the pack again.
		String packTrouble;
		try {
			packTrouble = GuardPack.write(world, record.lockId()) instanceof GuardPack.Written.Refused refused ? refused.reason() : null;
		} catch (IOException e) {
			Commitment.LOGGER.error("Commitment: committed {}, but writing its guard pack failed", world, e);
			packTrouble = e.getMessage();
		}
		if (packTrouble != null) {
			return new Reply(List.of(done, "Commitment: but the guard pack couldn't be written (" + packTrouble
					+ "); the lock holds while Commitment is installed, and the next world start tries again"), false, Effect.COMMITTED);
		}
		return new Reply(List.of(done), false, Effect.COMMITTED);
	}

	/** {@code /commit status}. Reads internet time only while a timer runs. */
	public static Reply status(GameEnvironment env, LockStore store, long seed, TrustedClock clock, ZoneId zone) throws IOException {
		if (!(open(env, store, seed) instanceof LockStore.Loaded.Locked locked)) {
			return Reply.info(NOT_COMMITTED);
		}
		return Reply.info(status(locked.record(), clock, zone));
	}

	/** {@code /unlock}: what the unlock does, and the confirm step; with a timer running, where it stands. */
	public static Reply unlockPreview(GameEnvironment env, LockStore store, long seed, TrustedClock clock, ZoneId zone) throws IOException {
		if (!(open(env, store, seed) instanceof LockStore.Loaded.Locked locked)) {
			return Reply.fail(NOT_COMMITTED);
		}
		if (locked.record().unlockRequestedAt().isPresent()) {
			return Reply.info(status(locked.record(), clock, zone));
		}
		return new Reply(List.of(
				"Commitment: /unlock confirm starts the 24-hour unlock. It's timed by internet time, and once it's over the"
						+ " lock lifts the next time the world opens. /unlock cancel stops it.",
				"Run /unlock confirm within 60 s to start it."), false, Effect.ASK);
	}

	/** {@code /unlock confirm}: starts the timer by internet time; without it, nothing starts. */
	public static Reply unlock(GameEnvironment env, LockStore store, long seed, TrustedClock clock, ZoneId zone) throws IOException {
		if (!(open(env, store, seed) instanceof LockStore.Loaded.Locked locked)) {
			return Reply.fail(NOT_COMMITTED);
		}
		UnlockTimer.Start start = UnlockTimer.start(locked.record(), clock);
		if (start instanceof UnlockTimer.Start.Started started) {
			store.save(env.worldDir(), started.record());
		}
		return start instanceof UnlockTimer.Start.NoInternetTime
				? Reply.fail(UnlockTimer.message(start, zone))
				: Reply.info(UnlockTimer.message(start, zone));
	}

	/** {@code /unlock cancel}. */
	public static Reply cancel(GameEnvironment env, LockStore store, long seed) throws IOException {
		if (!(open(env, store, seed) instanceof LockStore.Loaded.Locked locked)) {
			return Reply.fail(NOT_COMMITTED);
		}
		if (locked.record().unlockRequestedAt().isEmpty()) {
			return Reply.fail(NO_TIMER);
		}
		store.save(env.worldDir(), UnlockTimer.cancel(locked.record()));
		return Reply.info(CANCELLED);
	}

	/** The world is running, so the server holds its guard pack open: only a missing one is written. */
	private static LockStore.Loaded open(GameEnvironment env, LockStore store, long seed) throws IOException {
		return WorldLock.open(env.worldDir(), store, OptionalLong.of(seed), WorldLock.Repair.ONLY_IF_MISSING);
	}

	/** In a running world the lift waits for the next open, so "lifted" reads as "over". */
	private static String status(LockRecord record, TrustedClock clock, ZoneId zone) {
		UnlockTimer.Status status = UnlockTimer.status(record, clock);
		return status instanceof UnlockTimer.Status.Lifted ? OVER : UnlockTimer.message(status, zone);
	}
}
