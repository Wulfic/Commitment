package com.wulfic.commitment.lock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wulfic.commitment.lock.UnlockTimer.Start;
import com.wulfic.commitment.lock.UnlockTimer.Status;
import com.wulfic.commitment.manifest.Manifest;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** B.4: the 24-hour unlock against a fake internet clock. */
class UnlockTimerTest {
	/** 2026-10-21 12:00 UTC. */
	private static final long T0 = 1_792_584_000_000L;
	private static final long DAY = Duration.ofHours(24).toMillis();
	private static final ZoneId UTC = ZoneOffset.UTC;

	private final FakeClock internet = new FakeClock();

	/** Internet time the test sets; counts reads so a test can say the network wasn't touched. */
	static final class FakeClock implements TrustedClock {
		Long now;
		String down;
		int reads;

		@Override
		public Reading read() {
			reads++;
			return down != null ? new Reading.Unavailable(down) : new Reading.At(now);
		}
	}

	private static LockRecord committed(long committedAtOnTheSystemClock) {
		return LockRecord.committed(UUID.randomUUID(), 1, "w", committedAtOnTheSystemClock, new Manifest(new TreeMap<>()), new TreeMap<>());
	}

	private LockRecord started(LockRecord r) {
		return assertInstanceOf(Start.Started.class, UnlockTimer.start(r, internet)).record();
	}

	@Test
	void startRecordsInternetTimeAndSaysWhen() {
		internet.now = T0;
		Start s = UnlockTimer.start(committed(T0), internet);
		Start.Started started = assertInstanceOf(Start.Started.class, s);
		assertEquals(OptionalLong.of(T0), started.record().unlockRequestedAt());
		assertEquals(T0 + DAY, started.availableAt());
		assertEquals("Commitment: unlock available at 2026-10-22 12:00 Z (24h)", UnlockTimer.message(s, UTC));
	}

	@Test
	void aSecondStartKeepsTheFirstTimer() {
		internet.now = T0;
		LockRecord r = started(committed(T0));
		internet.now = T0 + 3_600_000;
		Start again = UnlockTimer.start(r, internet);
		assertEquals(new Start.AlreadyRunning(T0 + DAY), again);
		assertTrue(UnlockTimer.message(again, UTC).contains("already running"));
	}

	@Test
	void withNoInternetTimeTheUnlockCantStart() {
		internet.down = "time.example: connect timed out";
		Start s = UnlockTimer.start(committed(T0), internet);
		assertEquals(new Start.NoInternetTime("time.example: connect timed out"), s);
		assertEquals("Commitment: can't start the unlock without internet time (time.example: connect timed out). "
				+ "Connect to the internet and run /unlock confirm again", UnlockTimer.message(s, UTC));
	}

	@Test
	void oneMillisecondShortOf24HoursIsStillLocked() {
		internet.now = T0;
		LockRecord r = started(committed(T0));
		internet.now = T0 + DAY - 1;
		Status s = UnlockTimer.status(r, internet);
		assertEquals(new Status.Pending(T0 + DAY, Duration.ofMillis(1)), s);
		assertEquals("Commitment: unlock available at 2026-10-22 12:00 Z (1m left)", UnlockTimer.message(s, UTC));
		internet.now = T0 + DAY - 1000;
		assertInstanceOf(Status.Pending.class, UnlockTimer.status(r, internet), "23:59:59");
	}

	@Test
	void at24HoursTheLockLifts() {
		internet.now = T0;
		LockRecord r = started(committed(T0));
		internet.now = T0 + DAY;
		Status s = UnlockTimer.status(r, internet);
		assertEquals(new Status.Lifted(), s);
		assertEquals("Commitment: lock lifted, run /commit to lock again", UnlockTimer.message(s, UTC));
	}

	@Test
	void movingTheSystemClockForwardChangesNothing() {
		// committedAt is the only system-clock value the record holds; put it two days ahead. Internet time says 1 h.
		internet.now = T0;
		LockRecord r = started(committed(T0 + 2 * DAY));
		internet.now = T0 + 3_600_000;
		assertEquals(new Status.Pending(T0 + DAY, Duration.ofHours(23)), UnlockTimer.status(r, internet));
	}

	@Test
	void anExpiredTimerWithoutInternetTimeStaysLockedAndSaysWhy() {
		internet.now = T0;
		LockRecord r = started(committed(T0));
		internet.now = null;
		internet.down = "offline";
		Status s = UnlockTimer.status(r, internet);
		assertEquals(new Status.Unverified(T0 + DAY, "offline"), s);
		assertEquals("Commitment: the unlock was due at 2026-10-22 12:00 Z, but internet time can't be checked (offline), "
				+ "so the world stays locked", UnlockTimer.message(s, UTC));
	}

	@Test
	void withNoTimerTheClockIsNeverRead() {
		internet.down = "would fail if asked";
		assertEquals(new Status.Locked(), UnlockTimer.status(committed(T0), internet));
		assertEquals(0, internet.reads);
	}

	@Test
	void cancelStopsTheTimerAndTheWorldStaysLocked() {
		internet.now = T0;
		LockRecord r = UnlockTimer.cancel(started(committed(T0)));
		assertEquals(OptionalLong.empty(), r.unlockRequestedAt());
		internet.now = T0 + 2 * DAY;
		assertEquals(new Status.Locked(), UnlockTimer.status(r, internet));
	}

	@Test
	void cancelWithNoTimerChangesNothing() {
		LockRecord r = committed(T0);
		assertEquals(r, UnlockTimer.cancel(r));
	}

	@Test
	void restartingAfterACancelStartsAFresh24Hours() {
		internet.now = T0;
		LockRecord r = UnlockTimer.cancel(started(committed(T0)));
		internet.now = T0 + 20 * 3_600_000L;
		LockRecord again = started(r);
		internet.now = T0 + DAY;
		assertInstanceOf(Status.Pending.class, UnlockTimer.status(again, internet), "the cancelled timer's 20 h don't count");
		internet.now = T0 + 20 * 3_600_000L + DAY;
		assertEquals(new Status.Lifted(), UnlockTimer.status(again, internet));
	}

	@Test
	void theTimerSurvivesBeingWrittenAndReadBack() {
		internet.now = T0;
		LockRecord r = started(committed(T0));
		LockRecord back = assertInstanceOf(LockCodec.Decoded.Valid.class, LockCodec.decode(LockCodec.encode(r))).record();
		internet.now = T0 + DAY;
		assertEquals(new Status.Lifted(), UnlockTimer.status(back, internet));
	}

	@Test
	void remainingRoundsUpToTheMinute() {
		assertEquals("1m", UnlockTimer.remaining(Duration.ofSeconds(30)));
		assertEquals("59m", UnlockTimer.remaining(Duration.ofMinutes(59)));
		assertEquals("1h 0m", UnlockTimer.remaining(Duration.ofMinutes(60)));
		assertEquals("24h 0m", UnlockTimer.remaining(Duration.ofHours(24).minusSeconds(1)));
		assertEquals("23h 59m", UnlockTimer.remaining(Duration.ofHours(24).minusSeconds(61)));
	}

	@Test
	void theLockedMessagePointsAtUnlock() {
		assertSame(Status.Locked.class, UnlockTimer.status(committed(T0), internet).getClass());
		assertTrue(UnlockTimer.message(new Status.Locked(), UTC).contains("/unlock"));
	}
}
