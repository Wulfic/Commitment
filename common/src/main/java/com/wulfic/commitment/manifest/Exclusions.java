package com.wulfic.commitment.manifest;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Config files the lock never looks at. Patterns are relative to {@code config/} (and {@code defaultconfigs/},
 * {@code serverconfig/}), with {@code /} separators: a trailing {@code /} is a folder, {@code *} matches anything.
 */
final class Exclusions {
	/**
	 * Video, shader and audio settings stay free (owner, 2026-10-21), and so does the language, as options.txt's
	 * {@code lang} does. Names read from each mod's newest 26.3 jar (plan step 0.4) and from Stage E's packs.
	 * Shader packs live in {@code shaderpacks/}, which the scanner never reads.
	 */
	static final List<String> NEVER_LOCKED = List.of(
			"sodium-options.json", "sodium-mixins.properties", "sodium-fingerprint.json",
			"iris.properties", "iris-excluded.json",
			"DistantHorizons.toml",
			"immediatelyfast.json",
			"entityculling.json",
			// Dynamic FPS builds the name at runtime; Stage E confirms it.
			"dynamic_fps*",
			"sound_physics_remastered/",
			"presencefootsteps/",
			"ambientsounds/",
			// Language Reload's language choice; it also rewrites the file at startup (Fabulously Optimized 26.1.2).
			"languagereload.json");

	/** Files that change without the player touching the setup. Stage E grows this from real modpacks (TODO E.1). */
	static final List<String> IGNORED = List.of(
			// A running mod's process lock, held open while the game runs, so it can't even be read: Controlify's
			// controlify/profile-1.lock (Fabulously Optimized) failed /commit for every player of the pack.
			"*.lock",
			// Crash Assistant's own UI text: it prunes the pack's overrides when the first game quits (Fabulously
			// Optimized 26.1.2: 70 keys shipped, 50 kept), so a world committed in a pack's first session locked itself.
			"crash_assistant/crash_assistant_localization_overrides/");

	/** OS clutter, matched by file name in any folder. */
	static final Set<String> OS_JUNK = Set.of(".DS_Store", "Thumbs.db", "desktop.ini");

	private static final List<Pattern> PATTERNS =
			Stream.concat(NEVER_LOCKED.stream(), IGNORED.stream()).map(Exclusions::toRegex).toList();

	private Exclusions() {
	}

	static boolean excluded(String relPath) {
		String name = relPath.substring(relPath.lastIndexOf('/') + 1);
		if (OS_JUNK.contains(name)) {
			return true;
		}
		for (Pattern p : PATTERNS) {
			if (p.matcher(relPath).matches()) {
				return true;
			}
		}
		return false;
	}

	private static Pattern toRegex(String glob) {
		StringBuilder re = new StringBuilder();
		for (String part : glob.split("\\*", -1)) {
			if (!re.isEmpty()) {
				re.append(".*");
			}
			re.append(Pattern.quote(part));
		}
		if (glob.endsWith("/")) {
			re.append(".*");
		}
		return Pattern.compile(re.toString());
	}
}
