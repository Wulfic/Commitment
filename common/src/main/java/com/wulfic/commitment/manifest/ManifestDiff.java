package com.wulfic.commitment.manifest;

import java.util.List;

/** The changes between a committed setup and the current one, in key order. Empty means the world may open. */
public record ManifestDiff(List<Change> changes) {
	public enum Kind {
		ADDED, REMOVED, CHANGED
	}

	/** {@code committed} is null for an added entry, {@code current} for a removed one. */
	public record Change(Kind kind, String key, String committed, String current) {
	}

	public ManifestDiff {
		changes = List.copyOf(changes);
	}

	public boolean isEmpty() {
		return changes.isEmpty();
	}
}
