package com.wulfic.commitment.manifest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The changes between a committed setup and the current one, in key order. Empty means the world may open. */
public record ManifestDiff(List<Change> changes) {
	public enum Kind {
		ADDED, REMOVED, CHANGED
	}

	/** {@code committed} is null for an added entry, {@code current} for a removed one. */
	public record Change(Kind kind, String key, String committed, String current) {
	}

	/**
	 * Display order: what the player is most likely to have changed on purpose comes first. Entries of one kind keep
	 * their key order. Keys are matched by prefix, so "mod/" sorts mods, not "minecraft".
	 */
	private static final List<String> RANK = List.of(
			"minecraft", "loader", "mod/", "options/", "resourcepack/", "datapack/",
			"config/", "defaultconfigs/", "serverconfig/", "server.properties/");

	public ManifestDiff {
		changes = List.copyOf(changes);
	}

	public boolean isEmpty() {
		return changes.isEmpty();
	}

	/**
	 * One readable line per change, most significant first, never more than {@code max} lines: past the cap, the last
	 * line says how many more there are. Lines never carry a config value or a server property's value, only names.
	 */
	public List<String> lines(int max) {
		if (max < 2) {
			throw new IllegalArgumentException("max must leave room for a change and the \"more\" line, got " + max);
		}
		List<Change> ordered = new ArrayList<>(changes);
		ordered.sort(Comparator.comparingInt((Change c) -> rank(c.key())).thenComparing(Change::key));
		int shown = ordered.size() <= max ? ordered.size() : max - 1;
		List<String> lines = new ArrayList<>(max);
		for (Change c : ordered.subList(0, shown)) {
			lines.add(line(c));
		}
		// Past the cap, the "more" line takes a change's place, so it always stands for two or more.
		if (shown < ordered.size()) {
			lines.add("...and " + (ordered.size() - shown) + " more changes");
		}
		return lines;
	}

	private static int rank(String key) {
		for (int i = 0; i < RANK.size(); i++) {
			String prefix = RANK.get(i);
			if (prefix.endsWith("/") ? key.startsWith(prefix) : key.equals(prefix)) {
				return i;
			}
		}
		return RANK.size();
	}

	static String line(Change c) {
		String key = c.key();
		if (key.equals("minecraft")) {
			return "Minecraft " + c.committed() + " -> " + c.current();
		}
		if (key.equals("loader")) {
			return "Loader " + c.committed() + " -> " + c.current();
		}
		if (key.startsWith("mod/")) {
			return mod(c, key.substring("mod/".length()));
		}
		if (key.startsWith("options/")) {
			return packList(c);
		}
		if (key.startsWith("resourcepack/")) {
			String name = key.substring("resourcepack/".length());
			return "missing".equals(c.current()) ? "Resource pack missing: " + name : "Resource pack " + verb(c) + ": " + name;
		}
		if (key.startsWith("datapack/")) {
			return "Datapack " + verb(c) + ": " + key.substring("datapack/".length());
		}
		if (key.startsWith("server.properties/")) {
			return "server.properties: " + key.substring("server.properties/".length()) + " " + verb(c);
		}
		if (key.startsWith("config/") || key.startsWith("defaultconfigs/") || key.startsWith("serverconfig/")) {
			return "Config " + verb(c) + ": " + key;
		}
		return key + " " + verb(c);
	}

	private static String mod(Change c, String id) {
		return switch (c.kind()) {
			case ADDED -> "Mod added: " + id + " " + version(c.current());
			case REMOVED -> "Mod removed: " + id + " " + version(c.committed());
			case CHANGED -> {
				String was = version(c.committed());
				String now = version(c.current());
				yield was.equals(now) ? "Mod changed: " + id + " " + now + " (a different jar)" : "Mod changed: " + id + " " + was + " -> " + now;
			}
		};
	}

	/** A mod's fingerprint is {@code <version>} or {@code <version> sha256:<hex>}; the version may hold spaces. */
	private static String version(String fingerprint) {
		int hash = fingerprint.lastIndexOf(" sha256:");
		return hash < 0 ? fingerprint : fingerprint.substring(0, hash);
	}

	private static String packList(Change c) {
		String which = c.key().equals("options/incompatibleResourcePacks") ? "Incompatible resource packs" : "Resource packs";
		Set<String> was = packs(c.committed());
		Set<String> now = packs(c.current());
		if (was == null || now == null) {
			return which + " " + verb(c);
		}
		Set<String> enabled = new LinkedHashSet<>(now);
		enabled.removeAll(was);
		Set<String> disabled = new LinkedHashSet<>(was);
		disabled.removeAll(now);
		if (enabled.isEmpty() && disabled.isEmpty()) {
			return which + ": order changed";
		}
		List<String> parts = new ArrayList<>();
		if (!enabled.isEmpty()) {
			parts.add("enabled " + String.join(", ", enabled));
		}
		if (!disabled.isEmpty()) {
			parts.add("disabled " + String.join(", ", disabled));
		}
		return which + ": " + String.join("; ", parts);
	}

	/** The pack ids in a canonical JSON list, or null when the value is absent or wasn't a list. */
	private static Set<String> packs(String value) {
		if (value == null) {
			return null;
		}
		try {
			JsonElement json = JsonParser.parseString(value);
			if (!json.isJsonArray()) {
				return null;
			}
			Set<String> ids = new LinkedHashSet<>();
			for (JsonElement e : json.getAsJsonArray()) {
				String id = e.isJsonPrimitive() ? e.getAsString() : e.toString();
				// A player knows a pack by its file name; built-in packs ("vanilla", a mod's) have no prefix.
				ids.add(id.startsWith("file/") ? id.substring("file/".length()) : id);
			}
			return ids;
		} catch (JsonParseException e) {
			// The scanner already logged it when it fell back to the raw text; the line just says "changed".
			return null;
		}
	}

	private static String verb(Change c) {
		return switch (c.kind()) {
			case ADDED -> "added";
			case REMOVED -> "removed";
			case CHANGED -> "changed";
		};
	}
}
