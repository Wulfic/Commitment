package com.wulfic.commitment.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.wulfic.commitment.command.Confirmations.Kind;
import com.wulfic.commitment.command.Confirmations.Taken;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** C.3: the 60-second confirm window of {@code /commit} and {@code /unlock}. */
class ConfirmationsTest {
	private static final long SECOND = 1_000_000_000L;

	private final AtomicLong now = new AtomicLong(5 * SECOND);
	private final Confirmations confirmations = new Confirmations(now::get);

	@Test
	void aConfirmWithin60SecondsGoesThroughOnce() {
		confirmations.ask("w", "host", Kind.COMMIT);
		now.addAndGet(60 * SECOND);
		assertEquals(Taken.CONFIRMED, confirmations.take("w", "host", Kind.COMMIT));
		assertEquals(Taken.NOTHING_PENDING, confirmations.take("w", "host", Kind.COMMIT));
	}

	@Test
	void aConfirmAfter60SecondsHasExpiredAndIsUsedUp() {
		confirmations.ask("w", "host", Kind.COMMIT);
		now.addAndGet(60 * SECOND + 1);
		assertEquals(Taken.EXPIRED, confirmations.take("w", "host", Kind.COMMIT));
		assertEquals(Taken.NOTHING_PENDING, confirmations.take("w", "host", Kind.COMMIT));
	}

	@Test
	void nothingAskedNothingToConfirm() {
		assertEquals(Taken.NOTHING_PENDING, confirmations.take("w", "host", Kind.UNLOCK));
	}

	@Test
	void aWindowBelongsToOneSenderWorldAndCommand() {
		confirmations.ask("w", "host", Kind.COMMIT);
		assertEquals(Taken.NOTHING_PENDING, confirmations.take("w", "guest", Kind.COMMIT));
		assertEquals(Taken.NOTHING_PENDING, confirmations.take("other world", "host", Kind.COMMIT));
		assertEquals(Taken.NOTHING_PENDING, confirmations.take("w", "host", Kind.UNLOCK));
		assertEquals(Taken.CONFIRMED, confirmations.take("w", "host", Kind.COMMIT));
	}

	@Test
	void askingAgainRestartsTheWindow() {
		confirmations.ask("w", "host", Kind.UNLOCK);
		now.addAndGet(50 * SECOND);
		confirmations.ask("w", "host", Kind.UNLOCK);
		now.addAndGet(50 * SECOND);
		assertEquals(Taken.CONFIRMED, confirmations.take("w", "host", Kind.UNLOCK));
	}
}
