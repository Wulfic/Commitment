package com.wulfic.commitment.client;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.wulfic.commitment.lock.TrustedClock;
import org.junit.jupiter.api.Test;

/** C.4: the client GameTests' clock seam can't fake the 24 hours in a released game. */
class ClientGateTest {
	@Test
	void aReleasedGameRefusesTheTestClock() {
		TrustedClock aYearLater = () -> new TrustedClock.Reading.At(Long.MAX_VALUE / 2);
		assertThrows(IllegalStateException.class, () -> ClientGate.useTestClock(aYearLater, false));
	}
}
