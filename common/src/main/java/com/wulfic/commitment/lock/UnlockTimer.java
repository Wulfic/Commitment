package com.wulfic.commitment.lock;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * The 24-hour unlock: started with {@code /unlock confirm}, cancelled with {@code /unlock cancel}, and finished at the
 * next world open once internet time says 24 hours have passed. With no internet time it can't start or finish, and
 * an expired timer stays locked until internet time confirms it (owner, 2026-10-21: fail closed).
 */
public final class UnlockTimer {
	public static final Duration WAIT = Duration.ofHours(24);

	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z", Locale.ROOT);

	/** What {@code /unlock confirm} did. */
	public sealed interface Start {
		record Started(LockRecord record, long availableAt) implements Start {
		}

		record AlreadyRunning(long availableAt) implements Start {
		}

		record NoInternetTime(String reason) implements Start {
		}
	}

	/** Where a committed world's lock stands. */
	public sealed interface Status {
		/** No timer: the lock holds. */
		record Locked() implements Status {
		}

		record Pending(long availableAt, Duration remaining) implements Status {
		}

		/** A timer runs, but internet time can't be read, so it's not known to be over: still locked. */
		record Unverified(long availableAt, String reason) implements Status {
		}

		/** 24 hours of internet time have passed: the lock lifts. */
		record Lifted() implements Status {
		}
	}

	private UnlockTimer() {
	}

	public static Start start(LockRecord record, TrustedClock clock) {
		if (record.unlockRequestedAt().isPresent()) {
			return new Start.AlreadyRunning(availableAt(record.unlockRequestedAt().getAsLong()));
		}
		return switch (clock.read()) {
			case TrustedClock.Reading.At at -> new Start.Started(
					record.withUnlockRequestedAt(OptionalLong.of(at.epochMillis())), availableAt(at.epochMillis()));
			case TrustedClock.Reading.Unavailable u -> new Start.NoInternetTime(u.reason());
		};
	}

	/** The record with no timer; the same record when none was running. */
	public static LockRecord cancel(LockRecord record) {
		return record.withUnlockRequestedAt(OptionalLong.empty());
	}

	/** Reads the clock only while a timer runs, so a locked world with no timer never goes online. */
	public static Status status(LockRecord record, TrustedClock clock) {
		if (record.unlockRequestedAt().isEmpty()) {
			return new Status.Locked();
		}
		long availableAt = availableAt(record.unlockRequestedAt().getAsLong());
		return switch (clock.read()) {
			case TrustedClock.Reading.At at -> at.epochMillis() >= availableAt
					? new Status.Lifted()
					: new Status.Pending(availableAt, Duration.ofMillis(availableAt - at.epochMillis()));
			case TrustedClock.Reading.Unavailable u -> new Status.Unverified(availableAt, u.reason());
		};
	}

	public static String message(Start start, ZoneId zone) {
		return switch (start) {
			case Start.Started s -> "Commitment: unlock available at " + when(s.availableAt(), zone) + " (24h)";
			case Start.AlreadyRunning r -> "Commitment: an unlock is already running, available at " + when(r.availableAt(), zone);
			case Start.NoInternetTime n -> "Commitment: can't start the unlock without internet time (" + n.reason()
					+ "). Connect to the internet and run /unlock confirm again";
		};
	}

	public static String message(Status status, ZoneId zone) {
		return switch (status) {
			case Status.Locked() -> "Commitment: this world is committed. /unlock starts the 24-hour unlock";
			case Status.Pending p -> "Commitment: unlock available at " + when(p.availableAt(), zone) + " (" + remaining(p.remaining()) + " left)";
			case Status.Unverified u -> "Commitment: the unlock was due at " + when(u.availableAt(), zone)
					+ ", but internet time can't be checked (" + u.reason() + "), so the world stays locked";
			case Status.Lifted() -> "Commitment: lock lifted, run /commit to lock again";
		};
	}

	private static long availableAt(long requestedAt) {
		return requestedAt + WAIT.toMillis();
	}

	public static String when(long epochMillis, ZoneId zone) {
		return WHEN.format(Instant.ofEpochMilli(epochMillis).atZone(zone));
	}

	/**
	 * What's left of a timer {@code elapsedNanos} after a reading said {@code atReading} was left, never below zero.
	 * For a countdown on screen only: the lock itself lifts on a fresh internet-time reading.
	 */
	public static Duration left(Duration atReading, long elapsedNanos) {
		Duration left = atReading.minusNanos(Math.max(0, elapsedNanos));
		return left.isNegative() ? Duration.ZERO : left;
	}

	/** Rounded up to the minute, so the last 30 seconds don't read as "0m left". */
	public static String remaining(Duration d) {
		long minutes = (d.toMillis() + 59_999) / 60_000;
		return minutes >= 60 ? (minutes / 60) + "h " + (minutes % 60) + "m" : minutes + "m";
	}
}
