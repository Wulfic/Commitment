package com.wulfic.commitment.lock;

/**
 * Time the player can't move: internet time (B.5), never the system clock. Unlocking needs it (owner, 2026-10-21), so
 * every reading either has a time or says why it hasn't.
 */
@FunctionalInterface
public interface TrustedClock {
	sealed interface Reading {
		record At(long epochMillis) implements Reading {
		}

		record Unavailable(String reason) implements Reading {
		}
	}

	Reading read();
}
