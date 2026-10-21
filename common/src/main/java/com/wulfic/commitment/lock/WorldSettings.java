package com.wulfic.commitment.lock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

/**
 * The world's game settings (difficulty, game rules, cheats, default gamemode) as name → value strings, so common never
 * touches the game-rule API. Each loader reads and sets them; this only decides what to put back.
 */
public final class WorldSettings {
	/** One setting to set back to its committed value. */
	public record Revert(String name, String committed, String current) {
	}

	private WorldSettings() {
	}

	/**
	 * The committed settings that now hold another value, in name order. A setting that isn't in both is left alone: a
	 * rule a mod added after the commit wasn't committed, and a rule that's gone can't be set.
	 */
	public static List<Revert> reverts(SortedMap<String, String> committed, Map<String, String> current) {
		List<Revert> reverts = new ArrayList<>();
		committed.forEach((name, value) -> {
			String now = current.get(name);
			if (now != null && !now.equals(value)) {
				reverts.add(new Revert(name, value, now));
			}
		});
		return reverts;
	}
}
