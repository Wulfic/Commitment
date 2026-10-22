package com.wulfic.commitment.gate;

import com.wulfic.commitment.Commitment;
import com.wulfic.commitment.lock.InternetTime;
import com.wulfic.commitment.lock.LockRecord;
import com.wulfic.commitment.lock.LockStore;
import com.wulfic.commitment.lock.TrustedClock;
import com.wulfic.commitment.lock.UnlockTimer;
import com.wulfic.commitment.manifest.GameEnvironment;
import com.wulfic.commitment.platform.Platform;
import com.wulfic.commitment.server.CommitmentServer;
import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;

/**
 * The world-open gate on a dedicated server: runs in {@code Main.main} before the level data or any datapack loads. A
 * world whose setup changed since {@code /commit} is refused with a log of what changed, and the server exits 1. An
 * admin starts the 24-hour unlock with {@code -Dcommitment.unlock=start} (decisions.md 2026-10-21).
 */
public final class ServerGate {
	/** {@code start} begins the 24-hour unlock, {@code cancel} stops it. */
	public static final String UNLOCK_FLAG = "commitment.unlock";

	/**
	 * @param load    whether the server goes on to load the world
	 * @param refusal the first line of a refusal (logged as an error), or null when the world loads
	 * @param lines   everything else to log, in order
	 */
	public record Outcome(boolean load, String refusal, List<String> lines) {
		public Outcome {
			lines = List.copyOf(lines);
		}
	}

	private ServerGate() {
	}

	/**
	 * From the server's {@code Main}, holding the world's session lock. A refused world's storage is closed and the
	 * process exits 1: {@code Main.main} catches every throwable and returns, which would exit 0.
	 */
	public static void atServerStart(LevelStorageSource.LevelStorageAccess access) {
		Path world = access.getLevelPath(LevelResource.ROOT).toAbsolutePath().normalize();
		GameEnvironment env = Platform.environment(GameEnvironment.Side.DEDICATED_SERVER, Path.of("").toAbsolutePath(), world);
		Outcome outcome = decide(env, CommitmentServer.store(), new InternetTime(), System.getProperty(UNLOCK_FLAG), ZoneId.systemDefault());
		log(outcome);
		if (!outcome.load()) {
			access.safeClose();
			System.exit(1);
		}
	}

	/** Logs the outcome: a refusal as an error, the rest as info. */
	public static void log(Outcome outcome) {
		if (outcome.refusal() != null) {
			Commitment.LOGGER.error(outcome.refusal());
		}
		for (String line : outcome.lines()) {
			Commitment.LOGGER.info(line);
		}
	}

	/** @param unlockFlag the value of {@value #UNLOCK_FLAG}, or null when it isn't set */
	public static Outcome decide(GameEnvironment env, LockStore store, TrustedClock clock, String unlockFlag, ZoneId zone) {
		String world = env.worldDir().getFileName().toString();
		List<String> lines = new ArrayList<>();
		return switch (WorldGate.evaluate(env, store, clock)) {
			case WorldGate.Verdict.Uncommitted _ -> {
				if (unlockFlag != null) {
					lines.add("Commitment: -D" + UNLOCK_FLAG + "=" + unlockFlag + " ignored: " + world + " isn't committed");
				}
				yield new Outcome(true, null, lines);
			}
			case WorldGate.Verdict.Open open -> {
				lines.add("Commitment: " + world + " is committed and its setup matches");
				if (!applyFlag(env, store, clock, open.record(), unlockFlag, zone, lines)
						&& !(open.timer() instanceof UnlockTimer.Status.Locked)) {
					lines.add(UnlockTimer.message(open.timer(), zone));
				}
				yield new Outcome(true, null, lines);
			}
			case WorldGate.Verdict.Locked locked -> {
				for (String change : locked.changes()) {
					lines.add("Commitment:   " + change);
				}
				if (!applyFlag(env, store, clock, locked.record(), unlockFlag, zone, lines)) {
					lines.add(locked.timer() instanceof UnlockTimer.Status.Locked
							? "Commitment: " + (locked.record().tampered() ? "" : "put the setup back, or ")
									+ "start the 24-hour unlock: start the server once with -D" + UNLOCK_FLAG
									+ "=start (-D" + UNLOCK_FLAG + "=cancel stops it)"
							: UnlockTimer.message(locked.timer(), zone));
				}
				yield new Outcome(false, "Commitment: setup changed since /commit, refusing to load " + world, lines);
			}
			case WorldGate.Verdict.Lift lift -> lift(env, store, lift.record(), world, zone, lines);
			case WorldGate.Verdict.Failed failed ->
					new Outcome(false, "Commitment: can't check the lock of " + world + " (" + failed.reason() + "), refusing to load it", lines);
		};
	}

	/** Renames the lock's files away, then the world loads. A lift that can't finish keeps the world shut. */
	private static Outcome lift(GameEnvironment env, LockStore store, LockRecord record, String world, ZoneId zone,
			List<String> lines) {
		Optional<String> failure = WorldGate.lift(env.worldDir(), store, record);
		if (failure.isPresent()) {
			return new Outcome(false, "Commitment: the 24-hour unlock of " + world + " is over, but " + failure.get() + "; refusing to load it",
					lines);
		}
		lines.add(UnlockTimer.message(new UnlockTimer.Status.Lifted(), zone));
		return new Outcome(true, null, lines);
	}

	/** Applies {@value #UNLOCK_FLAG}; true when it was set and said something about the timer. */
	private static boolean applyFlag(GameEnvironment env, LockStore store, TrustedClock clock, LockRecord record, String flag, ZoneId zone,
			List<String> lines) {
		if (flag == null) {
			return false;
		}
		try {
			switch (flag.strip().toLowerCase(Locale.ROOT)) {
				case "start" -> {
					UnlockTimer.Start start = UnlockTimer.start(record, clock);
					if (start instanceof UnlockTimer.Start.Started started) {
						store.save(env.worldDir(), started.record());
					}
					lines.add(UnlockTimer.message(start, zone).replace("run /unlock confirm", "start with -D" + UNLOCK_FLAG + "=start"));
				}
				case "cancel" -> {
					if (record.unlockRequestedAt().isEmpty()) {
						lines.add("Commitment: no unlock is running; nothing to cancel");
					} else {
						store.save(env.worldDir(), UnlockTimer.cancel(record));
						lines.add("Commitment: unlock cancelled; the world stays committed");
					}
				}
				default -> {
					lines.add("Commitment: -D" + UNLOCK_FLAG + "=" + flag + " isn't start or cancel; ignored");
					return false;
				}
			}
		} catch (IOException e) {
			Commitment.LOGGER.warn("Commitment: can't save the unlock timer of {} ({}); nothing changed", env.worldDir(), e.getMessage());
			lines.add("Commitment: the unlock timer couldn't be saved; nothing changed");
		}
		return true;
	}
}
