package com.wulfic.commitment.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wulfic.commitment.lock.GuardPack;
import com.wulfic.commitment.lock.LockRecord;
import com.wulfic.commitment.lock.LockStore;
import com.wulfic.commitment.lock.TrustedClock;
import com.wulfic.commitment.lock.UnlockTimer;
import com.wulfic.commitment.lock.WorldSettings;
import com.wulfic.commitment.manifest.GameEnvironment;
import com.wulfic.commitment.manifest.ManifestScanner;
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

/** C.2: the world-open decision for every state a world can be in. */
class WorldGateTest {
	private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
	private static final long HOUR = 3_600_000L;
	private static final long REQUESTED = 1_000_000_000_000L;
	/** A committed world with no timer must never go online. */
	private static final TrustedClock NEVER = () -> {
		throw new AssertionError("the clock was read with no unlock timer running");
	};

	@TempDir
	Path dir;

	private GameEnvironment env;
	private LockStore store;

	@BeforeEach
	void setUp() throws IOException {
		Path game = Files.createDirectories(dir.resolve("instance"));
		Files.createDirectories(game.resolve("config"));
		Files.writeString(game.resolve("config/mod.json"), "{\"a\":1}");
		env = new GameEnvironment(GameEnvironment.Side.CLIENT, "26.3", "Fabric", "0.19.5",
				List.of(new GameEnvironment.ModFile("somemod", "1.0", null)), game, game.resolve("saves/World"));
		Files.createDirectories(env.worldDir());
		store = new LockStore(dir.resolve("locks"), () -> 1L, UUID::randomUUID);
	}

	@Test
	void anUncommittedWorldOpensAndNothingIsWritten() throws IOException {
		assertInstanceOf(WorldGate.Verdict.Uncommitted.class, WorldGate.evaluate(env, store, NEVER));
		try (Stream<Path> files = Files.walk(env.worldDir())) {
			assertEquals(List.of(), files.filter(Files::isRegularFile).toList());
		}
	}

	@Test
	void theCommittedSetupOpensWithoutGoingOnline() throws IOException {
		commit(OptionalLong.empty());
		WorldGate.Verdict.Open open = assertInstanceOf(WorldGate.Verdict.Open.class, WorldGate.evaluate(env, store, NEVER));
		assertInstanceOf(UnlockTimer.Status.Locked.class, open.timer());
	}

	@Test
	void aChangedConfigFileLocksTheWorldAndIsNamed() throws IOException {
		commit(OptionalLong.empty());
		Files.writeString(env.gameDir().resolve("config/mod.json"), "{\"a\":2}");
		WorldGate.Verdict.Locked locked = assertInstanceOf(WorldGate.Verdict.Locked.class, WorldGate.evaluate(env, store, NEVER));
		assertTrue(locked.changes().stream().anyMatch(l -> l.contains("config/mod.json")), locked.changes().toString());
	}

	@Test
	void aMissingGuardPackIsPutBackAtTheGate() throws IOException {
		commit(OptionalLong.empty());
		Files.delete(env.worldDir().resolve(GuardPack.FILE));
		assertInstanceOf(WorldGate.Verdict.Open.class, WorldGate.evaluate(env, store, NEVER));
		assertTrue(Files.exists(env.worldDir().resolve(GuardPack.FILE)));
	}

	@Test
	void after24HoursOfInternetTimeTheLockLiftsWhateverTheSetup() throws IOException {
		commit(OptionalLong.of(REQUESTED));
		Files.writeString(env.gameDir().resolve("config/mod.json"), "{\"a\":2}");
		TrustedClock later = () -> new TrustedClock.Reading.At(REQUESTED + 24 * HOUR);
		assertInstanceOf(WorldGate.Verdict.Lift.class, WorldGate.evaluate(env, store, later));
	}

	@Test
	void beforeThe24HoursAChangedSetupStaysLockedAndShowsTheTimer() throws IOException {
		commit(OptionalLong.of(REQUESTED));
		Files.writeString(env.gameDir().resolve("config/mod.json"), "{\"a\":2}");
		TrustedClock sooner = () -> new TrustedClock.Reading.At(REQUESTED + 23 * HOUR);
		WorldGate.Verdict.Locked locked = assertInstanceOf(WorldGate.Verdict.Locked.class, WorldGate.evaluate(env, store, sooner));
		assertInstanceOf(UnlockTimer.Status.Pending.class, locked.timer());
	}

	@Test
	void withNoInternetTimeAnExpiredTimerDoesntLiftButTheCommittedSetupStillOpens() throws IOException {
		commit(OptionalLong.of(REQUESTED));
		TrustedClock offline = () -> new TrustedClock.Reading.Unavailable("offline");
		WorldGate.Verdict.Open open = assertInstanceOf(WorldGate.Verdict.Open.class, WorldGate.evaluate(env, store, offline));
		assertInstanceOf(UnlockTimer.Status.Unverified.class, open.timer());
	}

	@Test
	void aWorldWithOnlyItsGuardPackLeftIsLockedWhateverItsSetup() throws IOException {
		Files.createDirectories(env.worldDir().resolve("datapacks"));
		Files.write(env.worldDir().resolve(GuardPack.FILE), GuardPack.build(ID));
		WorldGate.Verdict.Locked locked = assertInstanceOf(WorldGate.Verdict.Locked.class, WorldGate.evaluate(env, store, NEVER));
		assertEquals(List.of(WorldGate.TAMPERED_LINE), locked.changes());
	}

	@Test
	void aLockRecordThatCantBeReadKeepsTheWorldShut() throws IOException {
		commit(OptionalLong.empty());
		Path record = env.worldDir().resolve(LockStore.WORLD_RECORD);
		Files.delete(record);
		Files.createDirectories(record.resolve("not-a-file"));
		WorldGate.Verdict.Failed failed = assertInstanceOf(WorldGate.Verdict.Failed.class, WorldGate.evaluate(env, store, NEVER));
		assertTrue(failed.reason().contains("can't open the lock"), failed.reason());
	}

	@Test
	void theCommittedHardcoreFlagOpens() throws IOException {
		Files.write(env.worldDir().resolve(WorldSeed.LEVEL_DAT), WorldSeedTest.fixture("26.3-level.dat"));
		commit(OptionalLong.empty(), new TreeMap<>(Map.of(WorldSettings.HARDCORE, "false")));
		assertInstanceOf(WorldGate.Verdict.Open.class, WorldGate.evaluate(env, store, NEVER));
	}

	@Test
	void aChangedHardcoreFlagLocksTheWorldAndIsNamedFirst() throws IOException {
		Files.write(env.worldDir().resolve(WorldSeed.LEVEL_DAT), WorldSeedTest.fixture("1.21.11-level.dat"));
		commit(OptionalLong.empty(), new TreeMap<>(Map.of(WorldSettings.HARDCORE, "false")));
		Files.write(env.worldDir().resolve(WorldSeed.LEVEL_DAT), WorldSeedTest.withHardcore(WorldSeedTest.fixture("1.21.11-level.dat"), true));
		Files.writeString(env.gameDir().resolve("config/mod.json"), "{\"a\":2}");
		WorldGate.Verdict.Locked locked = assertInstanceOf(WorldGate.Verdict.Locked.class, WorldGate.evaluate(env, store, NEVER));
		assertEquals("Hardcore changed since /commit (was off, now on)", locked.changes().getFirst());
		assertTrue(locked.changes().stream().anyMatch(l -> l.contains("config/mod.json")), locked.changes().toString());
	}

	@Test
	void aLevelDatThatCantBeReadKeepsAHardcoreLockedWorldShut() throws IOException {
		commit(OptionalLong.empty(), new TreeMap<>(Map.of(WorldSettings.HARDCORE, "true")));
		WorldGate.Verdict.Failed failed = assertInstanceOf(WorldGate.Verdict.Failed.class, WorldGate.evaluate(env, store, NEVER));
		assertTrue(failed.reason().contains("can't read level.dat"), failed.reason());
	}

	@Test
	void aRecordWithoutTheFlagDoesntReadLevelDat() throws IOException {
		// Committed before C.6, or a damaged record rebuilt empty: no level.dat here, and the world still opens.
		commit(OptionalLong.empty());
		assertInstanceOf(WorldGate.Verdict.Open.class, WorldGate.evaluate(env, store, NEVER));
	}

	/** Commits the world as it is now, with an unlock timer started at {@code requestedAt} when given. */
	private void commit(OptionalLong requestedAt) throws IOException {
		commit(requestedAt, new TreeMap<>());
	}

	private void commit(OptionalLong requestedAt, TreeMap<String, String> settings) throws IOException {
		LockRecord record = LockRecord.committed(ID, 0, "World", 1L, ManifestScanner.scan(env), settings)
				.withUnlockRequestedAt(requestedAt);
		store.create(env.worldDir(), record);
		GuardPack.write(env.worldDir(), ID);
	}
}
