package com.wulfic.commitment.manifest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A world's setup as a sorted map of entry key → fingerprint: {@code minecraft}, {@code loader}, {@code mod/<id>},
 * {@code config/<path>}, {@code defaultconfigs/<path>}, {@code serverconfig/<path>}, {@code options/resourcePacks},
 * {@code options/incompatibleResourcePacks}, {@code resourcepack/<name>}, {@code datapack/<name>} and
 * {@code server.properties/<key>}. Comparing two setups is a plain map compare, in a stable order.
 */
public record Manifest(SortedMap<String, String> entries) {
	public Manifest {
		entries = Collections.unmodifiableSortedMap(new TreeMap<>(entries));
	}

	/** How much a setup holds, for {@code /commit}'s "42 mods, 3 resource packs, 118 config files". */
	public record Counts(int mods, int resourcePacks, int configFiles, int datapacks) {
		/** The datapacks only when there are any: most worlds have none but the guard, which isn't counted. */
		public String describe() {
			String text = plural(mods, "mod") + ", " + plural(resourcePacks, "resource pack") + ", " + plural(configFiles, "config file");
			return datapacks == 0 ? text : text + ", " + plural(datapacks, "datapack");
		}

		private static String plural(int n, String what) {
			return n + " " + what + (n == 1 ? "" : "s");
		}
	}

	public Counts counts() {
		int mods = 0;
		int resourcePacks = 0;
		int configFiles = 0;
		int datapacks = 0;
		for (String key : entries.keySet()) {
			if (key.startsWith("mod/")) {
				mods++;
			} else if (key.startsWith("resourcepack/")) {
				resourcePacks++;
			} else if (key.startsWith("config/") || key.startsWith("defaultconfigs/") || key.startsWith("serverconfig/")) {
				configFiles++;
			} else if (key.startsWith("datapack/")) {
				datapacks++;
			}
		}
		return new Counts(mods, resourcePacks, configFiles, datapacks);
	}

	/** What changed going from this (committed) setup to {@code current}, in key order. */
	public ManifestDiff diff(Manifest current) {
		List<ManifestDiff.Change> changes = new ArrayList<>();
		TreeMap<String, String> keys = new TreeMap<>(entries);
		keys.putAll(current.entries);
		for (Map.Entry<String, String> e : keys.entrySet()) {
			String key = e.getKey();
			String was = entries.get(key);
			String now = current.entries.get(key);
			if (was == null) {
				changes.add(new ManifestDiff.Change(ManifestDiff.Kind.ADDED, key, null, now));
			} else if (now == null) {
				changes.add(new ManifestDiff.Change(ManifestDiff.Kind.REMOVED, key, was, null));
			} else if (!was.equals(now)) {
				changes.add(new ManifestDiff.Change(ManifestDiff.Kind.CHANGED, key, was, now));
			}
		}
		return new ManifestDiff(changes);
	}
}
