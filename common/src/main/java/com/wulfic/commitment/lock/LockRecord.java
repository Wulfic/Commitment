package com.wulfic.commitment.lock;

import com.wulfic.commitment.manifest.Manifest;
import java.util.Collections;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Everything a committed world carries: the setup it was committed with, its game settings and the unlock timer. The
 * same record lives in the world ({@code data/commitment.dat}) and in the OS mirror; {@link LockStore} keeps them in step.
 *
 * @param revision          +1 on every write, so when both copies are valid the newer one wins
 * @param seed              with {@code worldFolder}, finds the mirror when the world's copy and the guard pack are gone
 * @param committedAt       epoch ms of {@code /commit confirm} on the system clock; shown, never trusted
 * @param unlockRequestedAt epoch ms of {@code /unlock confirm} in internet time, or empty with no timer running
 * @param tampered          the record was rebuilt after every copy was found damaged: the world stays locked whatever its
 *                          setup, and only the 24-hour timer gets out
 * @param worldSettings     game setting name → value at commit time, reverted live (plan step C.6)
 */
public record LockRecord(
		UUID lockId, long revision, long seed, String worldFolder, long committedAt, OptionalLong unlockRequestedAt,
		boolean tampered, Manifest manifest, SortedMap<String, String> worldSettings) {
	public LockRecord {
		Objects.requireNonNull(lockId, "lockId");
		Objects.requireNonNull(worldFolder, "worldFolder");
		Objects.requireNonNull(unlockRequestedAt, "unlockRequestedAt");
		Objects.requireNonNull(manifest, "manifest");
		worldSettings = Collections.unmodifiableSortedMap(new TreeMap<>(worldSettings));
	}

	/** A fresh lock, as {@code /commit confirm} writes it. */
	public static LockRecord committed(UUID lockId, long seed, String worldFolder, long committedAt, Manifest manifest,
			SortedMap<String, String> worldSettings) {
		return new LockRecord(lockId, 1, seed, worldFolder, committedAt, OptionalLong.empty(), false, manifest, worldSettings);
	}

	/** What's left when no copy could be trusted: locked against everything, timer not started. */
	static LockRecord tampered(UUID lockId, long revision, long seed, String worldFolder, long now) {
		return new LockRecord(lockId, revision, seed, worldFolder, now, OptionalLong.empty(), true,
				new Manifest(new TreeMap<>()), new TreeMap<>());
	}

	public LockRecord withRevision(long newRevision) {
		return new LockRecord(lockId, newRevision, seed, worldFolder, committedAt, unlockRequestedAt, tampered, manifest, worldSettings);
	}

	public LockRecord withUnlockRequestedAt(OptionalLong requestedAt) {
		return new LockRecord(lockId, revision, seed, worldFolder, committedAt, requestedAt, tampered, manifest, worldSettings);
	}
}
