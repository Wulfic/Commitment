package com.wulfic.commitment.lock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HeldSettingsTest {
	private static TreeMap<String, String> settings() {
		TreeMap<String, String> map = new TreeMap<>();
		map.put(WorldSettings.DIFFICULTY, "hard");
		return map;
	}

	@Test
	void aCheckThatThrowsEndsTheHoldAndNeverEscapesTheTick() {
		HeldSettings<Object> held = new HeldSettings<>();
		Object server = new Object();
		held.hold(server, settings());
		AtomicInteger runs = new AtomicInteger();
		held.check(server, "world", h -> {
			runs.incrementAndGet();
			throw new IllegalStateException("another mod's rule can't be read");
		});
		assertFalse(held.holding(server), "a hold whose settings can't be read ends, so it isn't logged every second");
		held.check(server, "world", h -> runs.incrementAndGet());
		assertEquals(1, runs.get(), "nothing is checked after the hold ended");
	}

	@Test
	void aCheckThatWorksKeepsTheHold() {
		HeldSettings<Object> held = new HeldSettings<>();
		Object server = new Object();
		held.hold(server, settings());
		AtomicInteger runs = new AtomicInteger();
		held.check(server, "world", h -> {
			assertEquals(settings(), h.settings());
			runs.incrementAndGet();
		});
		held.check(server, "world", h -> runs.incrementAndGet());
		assertTrue(held.holding(server));
		assertEquals(2, runs.get());
	}

	@Test
	void aServerNeverHeldOrReleasedChecksNothing() {
		HeldSettings<Object> held = new HeldSettings<>();
		Object server = new Object();
		AtomicInteger runs = new AtomicInteger();
		held.check(server, "world", h -> runs.incrementAndGet());
		held.hold(server, settings());
		held.release(server);
		held.check(server, "world", h -> runs.incrementAndGet());
		assertFalse(held.holding(server));
		assertEquals(0, runs.get());
	}
}
