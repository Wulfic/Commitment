package com.wulfic.commitment.lock;

import com.wulfic.commitment.Commitment;
import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * The committed game settings each running server puts back. Weak keys, so a stopped integrated server isn't kept
 * alive by this. {@code S} is the server; common's server code passes vanilla's, the tests anything.
 */
public final class HeldSettings<S> {
	public record Held(SortedMap<String, String> settings, WorldSettings.Throttle throttle) {
	}

	private final Map<S, Held> held = Collections.synchronizedMap(new WeakHashMap<>());

	public void hold(S server, SortedMap<String, String> settings) {
		held.put(server, new Held(settings, new WorldSettings.Throttle()));
	}

	public boolean holding(S server) {
		return held.containsKey(server);
	}

	public void release(S server) {
		held.remove(server);
	}

	/**
	 * Runs {@code check} on what {@code server} holds, if anything. Runs inside the server tick, so nothing may escape
	 * it: a world that crashes every second is worse than one whose settings go unheld until the next start, which is
	 * what happens, logged, if the settings can't even be read.
	 */
	public void check(S server, String world, Consumer<Held> check) {
		Held settings = held.get(server);
		if (settings == null) {
			return;
		}
		try {
			check.accept(settings);
		} catch (RuntimeException e) {
			held.remove(server);
			Commitment.LOGGER.error("Commitment: can't read the game settings of world {}; they aren't held until the next start", world, e);
		}
	}
}
