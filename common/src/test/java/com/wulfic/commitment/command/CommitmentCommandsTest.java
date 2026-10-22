package com.wulfic.commitment.command;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.wulfic.commitment.lock.TrustedClock;
import org.junit.jupiter.api.Test;

/** C.3: the GameTests' clock seam can't fake the 24 hours in a released game. */
class CommitmentCommandsTest {
	@Test
	void aReleasedGameRefusesTheTestClocks() {
		TrustedClock aYearLater = () -> new TrustedClock.Reading.At(Long.MAX_VALUE / 2);
		assertThrows(IllegalStateException.class, () -> CommitmentCommands.useTestClocks(aYearLater, () -> 0L, false));
	}
}
