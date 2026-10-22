package com.wulfic.commitment.lock;

import com.wulfic.commitment.Commitment;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * The world's lock record and its OS mirror. Either valid copy restores the other; when neither can be trusted but
 * something says the world was committed, the world stays locked and only the 24-hour timer gets out.
 */
public final class LockStore {
	/** The world's copy, relative to the world folder. */
	public static final String WORLD_RECORD = "data/commitment.dat";

	public sealed interface Loaded {
		record Uncommitted() implements Loaded {
		}

		record Locked(LockRecord record) implements Loaded {
		}
	}

	private sealed interface Copy {
		record Missing() implements Copy {
		}

		record Valid(LockRecord record) implements Copy {
		}

		record Tampered(String reason) implements Copy {
		}
	}

	/** Null when the OS gave no place for it: the lock is then kept in the world only. */
	private final Path mirrorDir;
	private final LongSupplier clock;
	private final Supplier<UUID> newLockId;

	public LockStore(Path mirrorDir, LongSupplier clock, Supplier<UUID> newLockId) {
		this.mirrorDir = mirrorDir;
		this.clock = clock;
		this.newLockId = newLockId;
	}

	/**
	 * Reads both copies, repairs whichever is stale or damaged, and says whether the world is committed.
	 *
	 * @param guardLockId the lock id inside the world's guard pack, if the pack is there
	 * @param seed        the world's seed when known; a record from another seed is not this world's
	 * @throws IOException the world's record exists but can't be read, or can't be repaired; the caller keeps the
	 *                     world closed (fail closed)
	 */
	public Loaded load(Path worldDir, Optional<UUID> guardLockId, OptionalLong seed) throws IOException {
		Path worldFile = worldDir.resolve(WORLD_RECORD);
		String folder = worldDir.getFileName().toString();
		Copy world = foreignCheck(readWorld(worldFile), guardLockId, seed);

		Optional<UUID> lookFor = world instanceof Copy.Valid v ? Optional.of(v.record().lockId()) : guardLockId;
		Copy mirror = lookFor.map(this::readMirror).orElseGet(() -> scanMirror(folder, seed));

		if (world instanceof Copy.Valid w && mirror instanceof Copy.Valid m) {
			LockRecord winner = m.record().revision() > w.record().revision() ? m.record() : w.record();
			if (winner != w.record()) {
				Commitment.LOGGER.info("Commitment: the mirror's lock record is newer (revision {} over {}); restoring {} from it",
						m.record().revision(), w.record().revision(), worldFile);
				writeWorld(worldFile, winner);
			} else if (m.record().revision() != w.record().revision()) {
				Commitment.LOGGER.info("Commitment: the mirror's lock record is older; updating it");
				writeMirror(winner);
			}
			return new Loaded.Locked(winner);
		}
		if (world instanceof Copy.Valid w) {
			Commitment.LOGGER.info("Commitment: the lock record's mirror is {}; rewriting it from {}", describe(mirror), worldFile);
			writeMirror(w.record());
			return new Loaded.Locked(w.record());
		}
		if (mirror instanceof Copy.Valid m) {
			Commitment.LOGGER.info("Commitment: the world's lock record is {}; restoring {} from the mirror", describe(world), worldFile);
			writeWorld(worldFile, m.record());
			return new Loaded.Locked(m.record());
		}
		boolean evidence = !(world instanceof Copy.Missing) || !(mirror instanceof Copy.Missing) || guardLockId.isPresent();
		if (!evidence) {
			return new Loaded.Uncommitted();
		}
		LockRecord rebuilt = LockRecord.tampered(guardLockId.orElseGet(newLockId), 1, seed.orElse(0), folder, clock.getAsLong());
		Commitment.LOGGER.warn("Commitment: no lock record can be trusted (world: {}, mirror: {}, guard pack: {}); the world stays locked "
				+ "and only the 24-hour unlock opens it", describe(world), describe(mirror), guardLockId.isPresent() ? "present" : "absent");
		writeWorld(worldFile, rebuilt);
		writeMirror(rebuilt);
		return new Loaded.Locked(rebuilt);
	}

	/** Writes the next revision of {@code record} to the world and the mirror, and returns what was written. */
	public LockRecord save(Path worldDir, LockRecord record) throws IOException {
		LockRecord next = record.withRevision(record.revision() + 1);
		writeWorld(worldDir.resolve(WORLD_RECORD), next);
		writeMirror(next);
		return next;
	}

	/** Writes a brand-new lock as is (revision 1): {@code /commit confirm}. */
	public void create(Path worldDir, LockRecord record) throws IOException {
		writeWorld(worldDir.resolve(WORLD_RECORD), record);
		writeMirror(record);
	}

	/** Where the mirror of {@code lockId} lives, or null with no mirror dir. The lift-lock deleter needs it. */
	public Path mirrorFile(UUID lockId) {
		return mirrorDir == null ? null : mirrorDir.resolve(lockId + ".dat");
	}

	private static Copy readWorld(Path file) throws IOException {
		byte[] bytes;
		try {
			bytes = Files.readAllBytes(file);
		} catch (NoSuchFileException e) {
			return new Copy.Missing();
		} catch (IOException e) {
			throw new IOException("can't read the lock record " + file + ": " + e.getMessage(), e);
		}
		return decoded(LockCodec.decode(bytes));
	}

	/** A valid record that belongs to another lock or another world counts as tampered: it was copied in. */
	private static Copy foreignCheck(Copy copy, Optional<UUID> guardLockId, OptionalLong seed) {
		if (copy instanceof Copy.Valid v) {
			if (guardLockId.isPresent() && !guardLockId.get().equals(v.record().lockId())) {
				return new Copy.Tampered("lock " + v.record().lockId() + ", but the guard pack is lock " + guardLockId.get());
			}
			if (seed.isPresent() && seed.getAsLong() != v.record().seed()) {
				return new Copy.Tampered("from a world with another seed");
			}
		}
		return copy;
	}

	private Copy readMirror(UUID lockId) {
		Path file = mirrorFile(lockId);
		if (file == null) {
			return new Copy.Missing();
		}
		try {
			Copy copy = decoded(LockCodec.decode(Files.readAllBytes(file)));
			if (copy instanceof Copy.Valid v && !v.record().lockId().equals(lockId)) {
				return new Copy.Tampered("named " + lockId + " but holds lock " + v.record().lockId());
			}
			return copy;
		} catch (NoSuchFileException e) {
			return new Copy.Missing();
		} catch (IOException e) {
			Commitment.LOGGER.warn("Commitment: can't read the lock mirror {} ({}); treating it as missing", file, e.getMessage());
			return new Copy.Missing();
		}
	}

	/**
	 * With no lock id to go on (the world's record and the guard pack are both gone), finds this world's mirror by seed
	 * and folder name. Without a seed it doesn't guess: folder names like "New World" are reused all the time, and a
	 * wrong match would lock a world that was never committed.
	 */
	private Copy scanMirror(String folder, OptionalLong seed) {
		if (mirrorDir == null || seed.isEmpty() || !Files.isDirectory(mirrorDir)) {
			return new Copy.Missing();
		}
		try (Stream<Path> files = Files.list(mirrorDir)) {
			List<LockRecord> matches = files.filter(p -> p.getFileName().toString().endsWith(".dat"))
					.map(LockStore::readQuietly)
					.flatMap(Optional::stream)
					.filter(r -> r.worldFolder().equals(folder) && r.seed() == seed.getAsLong())
					.toList();
			return matches.stream().max(Comparator.comparingLong(LockRecord::revision))
					.<Copy>map(Copy.Valid::new).orElseGet(Copy.Missing::new);
		} catch (IOException | UncheckedIOException e) {
			Commitment.LOGGER.warn("Commitment: can't list the lock mirror dir {} ({})", mirrorDir, e.getMessage());
			return new Copy.Missing();
		}
	}

	/** A valid mirror whose file name is its own lock id; anything else is skipped. */
	private static Optional<LockRecord> readQuietly(Path file) {
		try {
			return LockCodec.decode(Files.readAllBytes(file)) instanceof LockCodec.Decoded.Valid v
					&& file.getFileName().toString().equals(v.record().lockId() + ".dat")
					? Optional.of(v.record()) : Optional.empty();
		} catch (IOException e) {
			Commitment.LOGGER.warn("Commitment: can't read the lock mirror {} ({}); skipping it", file, e.getMessage());
			return Optional.empty();
		}
	}

	private static Copy decoded(LockCodec.Decoded d) {
		return switch (d) {
			case LockCodec.Decoded.Valid v -> new Copy.Valid(v.record());
			case LockCodec.Decoded.Tampered t -> new Copy.Tampered(t.reason());
		};
	}

	private static String describe(Copy copy) {
		return switch (copy) {
			case Copy.Missing _ -> "missing";
			case Copy.Valid _ -> "valid";
			case Copy.Tampered t -> "damaged: " + t.reason();
		};
	}

	private static void writeWorld(Path file, LockRecord record) throws IOException {
		try {
			writeAtomically(file, LockCodec.encode(record));
		} catch (IOException e) {
			throw new IOException("can't write the lock record " + file + ": " + e.getMessage(), e);
		}
		Commitment.LOGGER.info("Commitment: wrote lock record {} (lock {}, revision {})", file, record.lockId(), record.revision());
	}

	/** The mirror is the backup: a failure is logged, and the lock carries on in the world alone. */
	private void writeMirror(LockRecord record) {
		Path file = mirrorFile(record.lockId());
		if (file == null) {
			Commitment.LOGGER.warn("Commitment: no mirror dir on this OS; lock {} is kept in the world only", record.lockId());
			return;
		}
		try {
			writeAtomically(file, LockCodec.encode(record));
			Commitment.LOGGER.info("Commitment: wrote lock mirror {} (revision {})", file, record.revision());
		} catch (IOException e) {
			Commitment.LOGGER.warn("Commitment: can't write the lock mirror {} ({}); lock {} is kept in the world only",
					file, e.getMessage(), record.lockId());
		}
	}

	/** Write beside, then move over, so a crash mid-write leaves the old record rather than half a new one. */
	static void writeAtomically(Path file, byte[] bytes) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.write(tmp, bytes);
		try {
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Commitment.LOGGER.info("Commitment: {} can't be replaced atomically ({}); replacing it in place", file, e.getMessage());
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			// The temp file is the one written just above, never anyone else's. Left behind, vanilla lists a .zip.tmp as
			// a "non-pack entry" in datapacks/ (Windows can't replace a pack the server holds open).
			Files.deleteIfExists(tmp);
			Commitment.LOGGER.warn("Commitment: replacing {} failed ({}); removed the temp file {}", file, e.getMessage(), tmp);
			throw e;
		}
	}
}
