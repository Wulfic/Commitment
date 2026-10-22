package com.wulfic.commitment.command;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The confirm step of {@code /commit} and {@code /unlock}: asking opens a 60-second window for that sender, and a
 * confirm inside it goes through once. Keyed by world as well, so a pending {@code /commit} doesn't follow the host into
 * another world. Server thread only.
 */
final class Confirmations {
	static final Duration WINDOW = Duration.ofSeconds(60);

	enum Kind {
		COMMIT, UNLOCK
	}

	enum Taken {
		CONFIRMED, NOTHING_PENDING, EXPIRED
	}

	private record Key(String world, String sender, Kind kind) {
	}

	private final Map<Key, Long> askedAt = new HashMap<>();
	/** Monotonic nanoseconds ({@link System#nanoTime()}): a wall-clock jump can't stretch or shrink the window. */
	private final LongSupplier nanos;

	Confirmations(LongSupplier nanos) {
		this.nanos = nanos;
	}

	void ask(String world, String sender, Kind kind) {
		askedAt.put(new Key(world, sender, kind), nanos.getAsLong());
	}

	/** Uses up the window: a second confirm needs a second ask, whatever the first one's result. */
	Taken take(String world, String sender, Kind kind) {
		Long asked = askedAt.remove(new Key(world, sender, kind));
		if (asked == null) {
			return Taken.NOTHING_PENDING;
		}
		return nanos.getAsLong() - asked > WINDOW.toNanos() ? Taken.EXPIRED : Taken.CONFIRMED;
	}
}
