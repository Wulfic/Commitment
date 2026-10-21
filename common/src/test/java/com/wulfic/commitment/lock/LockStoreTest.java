package com.wulfic.commitment.lock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wulfic.commitment.manifest.Manifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** B.3: every missing/valid/tampered combination of the world record, the mirror and the guard pack. */
class LockStoreTest {
	private static final long SEED = 123456789L;
	private static final UUID REBUILT_ID = UUID.fromString("00000000-0000-0000-0000-00000000beef");

	@TempDir
	Path dir;

	private Path world;
	private Path mirrorDir;
	private LockStore store;
	private LockRecord committed;

	enum State {
		MISSING, VALID, TAMPERED
	}

	enum Outcome {
		/** Locked with the committed record, and both copies hold it afterwards. */
		LOCKED,
		/** Locked against everything with a rebuilt record in both places: only the timer gets out. */
		TAMPERED,
		/** Never committed: nothing written. */
		UNCOMMITTED
	}

	@BeforeEach
	void setUp() {
		world = dir.resolve("saves/My World");
		mirrorDir = dir.resolve("LocalAppData/Commitment/locks");
		store = new LockStore(mirrorDir, () -> 42L, () -> REBUILT_ID);
		TreeMap<String, String> manifest = new TreeMap<>();
		manifest.put("minecraft", "26.3");
		committed = LockRecord.committed(LockCodecTest.ID, SEED, "My World", 1000L, new Manifest(manifest),
				new TreeMap<>(java.util.Map.of("difficulty", "hard")));
	}

	static Stream<Arguments> matrix() {
		List<Arguments> rows = new ArrayList<>();
		for (State w : State.values()) {
			for (State m : State.values()) {
				for (boolean guard : new boolean[] {false, true}) {
					rows.add(Arguments.of(w, m, guard, expected(w, m, guard)));
				}
			}
		}
		return rows.stream();
	}

	/**
	 * The rules from the plan, stated independently of LockStore. A tampered mirror can only be found through the guard
	 * pack's lock id (the seed scan skips damaged files), so with no guard pack it counts as missing.
	 */
	private static Outcome expected(State world, State mirror, boolean guard) {
		if (world == State.VALID || mirror == State.VALID) {
			return Outcome.LOCKED;
		}
		return world == State.TAMPERED || guard ? Outcome.TAMPERED : Outcome.UNCOMMITTED;
	}

	@ParameterizedTest(name = "world {0}, mirror {1}, guard pack {2} -> {3}")
	@MethodSource("matrix")
	void everyCombination(State w, State m, boolean guard, Outcome outcome) throws IOException {
		place(worldFile(), w);
		place(mirrorFile(committed.lockId()), m);
		byte[] mirrorBefore = Files.exists(mirrorFile(committed.lockId())) ? Files.readAllBytes(mirrorFile(committed.lockId())) : null;

		LockStore.Loaded loaded = store.load(world, guard ? Optional.of(committed.lockId()) : Optional.empty(), OptionalLong.of(SEED));

		switch (outcome) {
			case LOCKED -> {
				assertEquals(new LockStore.Loaded.Locked(committed), loaded);
				assertEquals(committed, decode(worldFile()), "the world's copy is restored");
				assertEquals(committed, decode(mirrorFile(committed.lockId())), "the mirror is restored");
			}
			case TAMPERED -> {
				LockRecord r = assertInstanceOf(LockStore.Loaded.Locked.class, loaded).record();
				assertTrue(r.tampered());
				assertEquals(List.of(), List.copyOf(r.manifest().entries().keySet()));
				assertTrue(r.unlockRequestedAt().isEmpty(), "the timer is not started for the player");
				assertEquals(guard ? committed.lockId() : REBUILT_ID, r.lockId(), "the guard pack's id is kept when there is one");
				assertEquals(r, decode(worldFile()));
				assertEquals(r, decode(mirrorFile(r.lockId())));
			}
			case UNCOMMITTED -> {
				assertEquals(new LockStore.Loaded.Uncommitted(), loaded);
				assertEquals(w == State.MISSING, !Files.exists(worldFile()), "nothing is written to an uncommitted world");
				byte[] mirrorAfter = Files.exists(mirrorFile(committed.lockId())) ? Files.readAllBytes(mirrorFile(committed.lockId())) : null;
				assertTrue(java.util.Arrays.equals(mirrorBefore, mirrorAfter), "an unreachable mirror is left as it was");
			}
		}
		try (Stream<Path> files = Files.walk(dir)) {
			assertEquals(List.of(), files.filter(p -> p.toString().endsWith(".tmp")).toList(), "no temp file is left behind");
		}
	}

	@Test
	void theNewerRevisionWinsEitherWay() throws IOException {
		LockRecord newer = committed.withRevision(5).withUnlockRequestedAt(OptionalLong.of(777));
		write(worldFile(), committed);
		write(mirrorFile(committed.lockId()), newer);
		assertEquals(new LockStore.Loaded.Locked(newer), store.load(world, Optional.empty(), OptionalLong.of(SEED)));
		assertEquals(newer, decode(worldFile()));

		LockRecord newest = newer.withRevision(9).withUnlockRequestedAt(OptionalLong.empty());
		write(worldFile(), newest);
		assertEquals(new LockStore.Loaded.Locked(newest), store.load(world, Optional.empty(), OptionalLong.of(SEED)));
		assertEquals(newest, decode(mirrorFile(committed.lockId())));
	}

	@Test
	void aRecordCopiedInFromAnotherLockIsTampered() throws IOException {
		UUID guardId = UUID.fromString("11111111-1111-1111-1111-111111111111");
		write(worldFile(), committed);
		LockRecord r = ((LockStore.Loaded.Locked) store.load(world, Optional.of(guardId), OptionalLong.of(SEED))).record();
		assertTrue(r.tampered());
		assertEquals(guardId, r.lockId());
	}

	@Test
	void aRecordCopiedInFromAnotherSeedIsTampered() throws IOException {
		write(worldFile(), committed);
		LockRecord r = ((LockStore.Loaded.Locked) store.load(world, Optional.empty(), OptionalLong.of(SEED + 1))).record();
		assertTrue(r.tampered());
	}

	@Test
	void aMirrorNamedForOneLockHoldingAnotherIsTampered() throws IOException {
		LockRecord other = LockRecord.committed(UUID.randomUUID(), SEED, "My World", 1, committed.manifest(), committed.worldSettings());
		write(mirrorFile(committed.lockId()), other);
		LockRecord r = ((LockStore.Loaded.Locked) store.load(world, Optional.of(committed.lockId()), OptionalLong.of(SEED))).record();
		assertTrue(r.tampered());
	}

	@Test
	void withoutASeedTheMirrorIsNotGuessedByFolderName() throws IOException {
		write(mirrorFile(committed.lockId()), committed);
		assertEquals(new LockStore.Loaded.Uncommitted(), store.load(world, Optional.empty(), OptionalLong.empty()));
		assertFalse(Files.exists(worldFile()));
	}

	@Test
	void aMirrorOfAnotherWorldWithTheSameFolderNameIsNotThisOne() throws IOException {
		write(mirrorFile(committed.lockId()), committed);
		assertEquals(new LockStore.Loaded.Uncommitted(), store.load(world, Optional.empty(), OptionalLong.of(SEED + 1)));
	}

	@Test
	void anUnwritableMirrorKeepsTheLockInTheWorld() throws IOException {
		Files.createDirectories(mirrorDir.getParent());
		Files.writeString(mirrorDir, "a file where the mirror dir should be");
		store.create(world, committed);
		assertEquals(committed, decode(worldFile()));
		assertEquals(new LockStore.Loaded.Locked(committed), store.load(world, Optional.empty(), OptionalLong.of(SEED)));
	}

	@Test
	void noMirrorDirKeepsTheLockInTheWorld() throws IOException {
		LockStore worldOnly = new LockStore(null, () -> 0L, UUID::randomUUID);
		worldOnly.create(world, committed);
		assertEquals(new LockStore.Loaded.Locked(committed), worldOnly.load(world, Optional.empty(), OptionalLong.of(SEED)));
		assertFalse(Files.exists(mirrorDir));
	}

	@Test
	void saveWritesTheNextRevisionToBothCopies() throws IOException {
		store.create(world, committed);
		LockRecord saved = store.save(world, committed.withUnlockRequestedAt(OptionalLong.of(5)));
		assertEquals(2, saved.revision());
		assertEquals(saved, decode(worldFile()));
		assertEquals(saved, decode(mirrorFile(committed.lockId())));
	}

	@Test
	void anUnreadableWorldRecordFailsClosed() throws IOException {
		Files.createDirectories(worldFile());
		IOException e = assertThrows(IOException.class, () -> store.load(world, Optional.empty(), OptionalLong.of(SEED)));
		assertTrue(e.getMessage().contains("commitment.dat"), e.getMessage());
	}

	private Path worldFile() {
		return world.resolve(LockStore.WORLD_RECORD);
	}

	private Path mirrorFile(UUID id) {
		return store.mirrorFile(id);
	}

	private void place(Path file, State state) throws IOException {
		switch (state) {
			case MISSING -> {
			}
			case VALID -> write(file, committed);
			case TAMPERED -> {
				byte[] bytes = LockCodecTest.gunzip(LockCodec.encode(committed));
				bytes[bytes.length / 2] ^= 0x20;
				Files.createDirectories(file.getParent());
				Files.write(file, LockCodecTest.gzip(bytes));
			}
		}
	}

	private static void write(Path file, LockRecord r) throws IOException {
		Files.createDirectories(file.getParent());
		Files.write(file, LockCodec.encode(r));
	}

	private static LockRecord decode(Path file) throws IOException {
		return assertInstanceOf(LockCodec.Decoded.Valid.class, LockCodec.decode(Files.readAllBytes(file))).record();
	}
}
