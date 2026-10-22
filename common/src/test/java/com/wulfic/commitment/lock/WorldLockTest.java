package com.wulfic.commitment.lock;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.wulfic.commitment.manifest.Manifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** C.1: the gate and server start open a lock the same way, and a committed world gets its guard pack back. */
class WorldLockTest {
	private static final UUID ID = GuardPackTest.GOLDEN_ID;
	private static final long SEED = 42L;

	@TempDir
	Path dir;

	private Path world;
	private LockStore store;
	private LockRecord record;

	@BeforeEach
	void setUp() {
		world = dir.resolve("saves/World");
		store = new LockStore(dir.resolve("locks"), () -> 1L, UUID::randomUUID);
		record = LockRecord.committed(ID, SEED, "World", 1L, new Manifest(new TreeMap<>(Map.of("minecraft", "26.3"))), new TreeMap<>());
	}

	@Test
	void aCommittedWorldGetsItsGuardPackBack() throws IOException {
		store.create(world, record);
		assertFalse(Files.exists(world.resolve(GuardPack.FILE)));
		assertInstanceOf(LockStore.Loaded.Locked.class, WorldLock.open(world, store, OptionalLong.of(SEED), WorldLock.Repair.REPLACE_STALE));
		assertArrayEquals(GuardPack.build(ID), Files.readAllBytes(world.resolve(GuardPack.FILE)));
	}

	@Test
	void aWorldWithOnlyItsMirrorLeftGetsEverythingBack() throws IOException {
		store.create(world, record);
		Files.delete(world.resolve(LockStore.WORLD_RECORD));
		assertInstanceOf(LockStore.Loaded.Locked.class, WorldLock.open(world, store, OptionalLong.of(SEED), WorldLock.Repair.REPLACE_STALE));
		assertArrayEquals(GuardPack.build(ID), Files.readAllBytes(world.resolve(GuardPack.FILE)));
		assertEquals(LockCodec.decode(Files.readAllBytes(world.resolve(LockStore.WORLD_RECORD))), new LockCodec.Decoded.Valid(record));
	}

	@Test
	void aRunningServerOnlyWritesAMissingPack() throws IOException {
		store.create(world, record);
		assertInstanceOf(LockStore.Loaded.Locked.class, WorldLock.open(world, store, OptionalLong.of(SEED), WorldLock.Repair.ONLY_IF_MISSING));
		assertArrayEquals(GuardPack.build(ID), Files.readAllBytes(world.resolve(GuardPack.FILE)), "a missing pack is written");

		// An older Commitment's pack for this lock: replaced before the world loads, left alone while a server holds it.
		TreeMap<String, String> older = new TreeMap<>(GuardPack.files(ID));
		older.put("data/commitment_guard/function/penalty.mcfunction", "say old\n");
		byte[] stale = GuardPackTest.zip(older);
		Files.write(world.resolve(GuardPack.FILE), stale);
		WorldLock.open(world, store, OptionalLong.of(SEED), WorldLock.Repair.ONLY_IF_MISSING);
		assertArrayEquals(stale, Files.readAllBytes(world.resolve(GuardPack.FILE)));
		WorldLock.open(world, store, OptionalLong.of(SEED), WorldLock.Repair.REPLACE_STALE);
		assertArrayEquals(GuardPack.build(ID), Files.readAllBytes(world.resolve(GuardPack.FILE)));
	}

	@Test
	void anUncommittedWorldIsLeftAlone() throws IOException {
		Files.createDirectories(world);
		assertInstanceOf(LockStore.Loaded.Uncommitted.class, WorldLock.open(world, store, OptionalLong.of(SEED), WorldLock.Repair.REPLACE_STALE));
		try (Stream<Path> files = Files.walk(dir)) {
			assertEquals(List.of(), files.filter(Files::isRegularFile).toList(), "nothing written for an uncommitted world");
		}
	}

	@Test
	void aStrangersFileAtTheGuardPathIsLeftAloneAndTheWorldStaysLocked() throws IOException {
		store.create(world, record);
		Path guard = world.resolve(GuardPack.FILE);
		Files.createDirectories(guard.getParent());
		Files.writeString(guard, "not a pack");
		assertInstanceOf(LockStore.Loaded.Locked.class, WorldLock.open(world, store, OptionalLong.of(SEED), WorldLock.Repair.REPLACE_STALE));
		assertEquals("not a pack", Files.readString(guard));
	}
}
