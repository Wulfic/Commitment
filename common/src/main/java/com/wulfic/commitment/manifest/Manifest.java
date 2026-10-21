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
