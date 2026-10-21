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
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * B.6, the destructive path: the lift renames Commitment's three files, all or nothing. Every refusal must leave every
 * file on disk exactly as it was, so each refusal test compares the whole tree before and after.
 */
class LockLifterTest {
	private static final UUID ID = GuardPackTest.GOLDEN_ID;
	private static final UUID OTHER = GuardPackTest.OTHER_ID;
	private static final long SEED = 123456789L;

	@TempDir
	Path dir;

	private Path world;
	private Path mirrorDir;
	private LockStore store;
	private LockRecord record;

	@BeforeEach
	void setUp() {
		world = dir.resolve("saves/My World");
		mirrorDir = dir.resolve("LocalAppData/Commitment/locks");
		store = new LockStore(mirrorDir, () -> 42L, UUID::randomUUID);
		TreeMap<String, String> manifest = new TreeMap<>();
		manifest.put("minecraft", "26.3");
		record = LockRecord.committed(ID, SEED, "My World", 1000L, new Manifest(manifest), new TreeMap<>(Map.of("difficulty", "hard")));
	}

	@Test
	void liftRenamesAllThreeAndTheWorldIsNoLongerCommitted() throws IOException {
		commit();
		Map<String, String> before = snapshot();
		LockLifter.Plan plan = LockLifter.plan(world, store, ID);
		assertTrue(plan.allowed(), plan.toString());
		assertEquals(3, plan.toRename().size());

		assertEquals(3, LockLifter.lift(plan));

		Path guard = world.resolve(GuardPack.FILE);
		Path worldRecord = world.resolve(LockStore.WORLD_RECORD);
		Path mirror = mirrorDir.resolve(ID + ".dat");
		for (Path gone : List.of(guard, worldRecord, mirror)) {
			assertFalse(Files.exists(gone), gone + " is still there");
		}
		assertEquals(before.get(key(guard)), read(world.resolve("datapacks/commitment_guard.zip." + ID + ".lifted")));
		assertEquals(before.get(key(worldRecord)), read(world.resolve("data/commitment.dat." + ID + ".lifted")));
		assertEquals(before.get(key(mirror)), read(mirrorDir.resolve(ID + ".dat.lifted")));
		assertEquals(3, snapshot().size(), "nothing but the three renamed files: " + snapshot().keySet());

		// The renamed files are inert: no guard pack, and LockStore (seed scan included) finds no lock.
		assertEquals(Optional.empty(), GuardPack.read(world));
		assertInstanceOf(LockStore.Loaded.Uncommitted.class, store.load(world, GuardPack.read(world), OptionalLong.of(SEED)));
	}

	@Test
	void planIsADryRun() throws IOException {
		commit();
		Map<String, String> before = snapshot();
		LockLifter.plan(world, store, ID);
		assertEquals(before, snapshot());
	}

	@Test
	void lookAlikeGuardPackIsRefused() throws IOException {
		commit();
		TreeMap<String, String> files = new TreeMap<>(GuardPack.files(ID));
		files.remove(GuardPack.MARKER_ENTRY);
		Files.write(world.resolve(GuardPack.FILE), GuardPackTest.zip(files));
		assertRefusedAndUntouched("guard pack");
		assertReason("guard pack", "isn't Commitment's");
	}

	@Test
	void anotherLocksGuardPackIsRefused() throws IOException {
		commit();
		Files.write(world.resolve(GuardPack.FILE), GuardPack.build(OTHER));
		assertRefusedAndUntouched("guard pack");
	}

	@Test
	void anotherLocksRecordIsRefused() throws IOException {
		commit();
		Files.write(world.resolve(LockStore.WORLD_RECORD), LockCodec.encode(otherLock()));
		assertRefusedAndUntouched("lock record");
	}

	@Test
	void editedRecordIsRefused() throws IOException {
		commit();
		Path file = world.resolve(LockStore.WORLD_RECORD);
		byte[] bytes = Files.readAllBytes(file);
		bytes[bytes.length / 2] ^= 1;
		Files.write(file, bytes);
		assertRefusedAndUntouched("lock record");
	}

	@Test
	void mirrorHoldingAnotherLockIsRefused() throws IOException {
		commit();
		Files.write(mirrorDir.resolve(ID + ".dat"), LockCodec.encode(otherLock()));
		assertRefusedAndUntouched("lock mirror");
	}

	@Test
	void takenLiftedNameIsRefused() throws IOException {
		commit();
		Files.writeString(world.resolve("data/commitment.dat." + ID + ".lifted"), "the player's own file");
		assertRefusedAndUntouched("lock record");
		assertReason("lock record", "already exists");
	}

	@Test
	void aFolderAtTheGuardPathIsRefused() throws IOException {
		commit();
		Files.delete(world.resolve(GuardPack.FILE));
		Files.writeString(Files.createDirectories(world.resolve(GuardPack.FILE)).resolve("pack.mcmeta"), "{}");
		assertRefusedAndUntouched("guard pack");
		assertReason("guard pack", "isn't a regular file");
	}

	@Test
	void anOversizedFileIsRefusedUnread() throws IOException {
		commit();
		Files.write(world.resolve(GuardPack.FILE), new byte[GuardPack.MAX_FILE + 1]);
		assertRefusedAndUntouched("guard pack");
		assertReason("guard pack", "too large");
	}

	@Test
	void guardPackReachedThroughALinkOutOfTheWorldIsRefused() throws IOException, InterruptedException {
		commit();
		Path outside = Files.createDirectories(dir.resolve("elsewhere"));
		Files.move(world.resolve(GuardPack.FILE), outside.resolve("commitment_guard.zip"));
		Files.delete(world.resolve("datapacks"));
		link(world.resolve("datapacks"), outside);
		assertTrue(Files.exists(world.resolve(GuardPack.FILE)), "the link doesn't lead to the pack");
		assertEquals(Optional.of(ID), GuardPack.read(world), "through the link it reads as ours, so only the path check stops it");
		assertRefusedAndUntouched("guard pack");
		assertReason("guard pack", "outside");
	}

	@Test
	void withNoMirrorDirTheWorldsFilesStillLift() throws IOException {
		LockStore worldOnly = new LockStore(null, () -> 42L, UUID::randomUUID);
		worldOnly.create(world, record);
		GuardPack.write(world, ID);
		LockLifter.Plan plan = LockLifter.plan(world, worldOnly, ID);
		assertEquals(2, plan.items().size());
		assertEquals(2, LockLifter.lift(plan));
	}

	@Test
	void missingFilesAreSkipped() throws IOException {
		store.create(world, record);
		LockLifter.Plan plan = LockLifter.plan(world, store, ID);
		assertTrue(plan.allowed());
		assertInstanceOf(LockLifter.Check.Missing.class, plan.items().getFirst().check());
		assertEquals(2, LockLifter.lift(plan));
		assertEquals(0, LockLifter.lift(LockLifter.plan(world, store, ID)), "a second lift finds nothing");
	}

	@Test
	void noLockIdOrWorldIsRefusedOutright() {
		assertThrows(NullPointerException.class, () -> LockLifter.plan(world, store, null));
		assertThrows(NullPointerException.class, () -> LockLifter.plan(null, store, ID));
		assertThrows(NullPointerException.class, () -> LockLifter.lift(null));
	}

	@Test
	void aRenameThatFailsMidwayIsUndone() throws IOException {
		commit();
		Map<String, String> before = snapshot();
		LockLifter.Plan plan = LockLifter.plan(world, store, ID);
		AtomicInteger moves = new AtomicInteger();
		IOException e = assertThrows(IOException.class, () -> LockLifter.lift(plan, (from, to) -> {
			if (moves.incrementAndGet() == 2) {
				throw new IOException("disk full");
			}
			Files.move(from, to);
		}));
		assertTrue(e.getMessage().contains("disk full"), e.getMessage());
		assertEquals(before, snapshot());
	}

	@Test
	void aFileThatChangedAfterThePlanStopsTheLiftAndTheRestIsUndone() throws IOException {
		commit();
		LockLifter.Plan plan = LockLifter.plan(world, store, ID);
		assertTrue(plan.allowed());
		// The world's record goes last, so the guard pack and the mirror are already renamed when it fails its re-check.
		Files.write(world.resolve(LockStore.WORLD_RECORD), LockCodec.encode(otherLock()));
		Map<String, String> before = snapshot();
		assertThrows(IOException.class, () -> LockLifter.lift(plan));
		assertEquals(before, snapshot());
	}

	private void commit() throws IOException {
		store.create(world, record);
		assertInstanceOf(GuardPack.Written.Wrote.class, GuardPack.write(world, ID));
	}

	private LockRecord otherLock() {
		return LockRecord.committed(OTHER, SEED, "My World", 1000L, record.manifest(), record.worldSettings());
	}

	private void assertRefusedAndUntouched(String what) {
		Map<String, String> before = snapshot();
		LockLifter.Plan plan = LockLifter.plan(world, store, ID);
		assertFalse(plan.allowed(), plan.toString());
		assertEquals(List.of(what), plan.refused().stream().map(i -> i.target().what()).toList());
		assertThrows(IOException.class, () -> LockLifter.lift(plan));
		assertEquals(before, snapshot());
	}

	/** Pins which guard refused, so a guard that's switched off can't hide behind a later one. */
	private void assertReason(String what, String reasonPart) {
		LockLifter.Check check = LockLifter.plan(world, store, ID).items().stream()
				.filter(i -> i.target().what().equals(what)).findFirst().orElseThrow().check();
		assertInstanceOf(LockLifter.Check.Refused.class, check);
		String reason = ((LockLifter.Check.Refused) check).reason();
		assertTrue(reason.contains(reasonPart), reason);
	}

	/** Every file under the temp dir → its bytes, so "untouched" covers names, contents and anything new. */
	private Map<String, String> snapshot() {
		try (Stream<Path> files = Files.walk(dir)) {
			return files.filter(Files::isRegularFile).collect(Collectors.toMap(this::key, LockLifterTest::read, (a, b) -> a, TreeMap::new));
		} catch (IOException e) {
			throw new AssertionError("can't list " + dir, e);
		}
	}

	private String key(Path file) {
		return dir.relativize(file).toString().replace('\\', '/');
	}

	private static String read(Path file) {
		try {
			return Base64.getEncoder().encodeToString(Files.readAllBytes(file));
		} catch (IOException e) {
			throw new AssertionError("can't read " + file, e);
		}
	}

	/** A symbolic link, or on Windows without the privilege for one, a junction (which needs none). */
	private static void link(Path link, Path target) throws IOException, InterruptedException {
		try {
			Files.createSymbolicLink(link, target);
			return;
		} catch (IOException | UnsupportedOperationException e) {
			if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
				throw e;
			}
		}
		Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString()).redirectErrorStream(true).start();
		String out = new String(p.getInputStream().readAllBytes());
		assertEquals(0, p.waitFor(), "mklink /J failed: " + out);
	}
}
