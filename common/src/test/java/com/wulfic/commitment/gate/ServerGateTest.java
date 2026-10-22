package com.wulfic.commitment.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wulfic.commitment.lock.GuardPack;
import com.wulfic.commitment.lock.LockRecord;
import com.wulfic.commitment.lock.LockStore;
import com.wulfic.commitment.lock.TrustedClock;
import com.wulfic.commitment.manifest.GameEnvironment;
import com.wulfic.commitment.manifest.ManifestScanner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** C.2: what a dedicated server does with each kind of world, and the {@code -Dcommitment.unlock} flag. */
class ServerGateTest {
	private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-0000000000b2");
	private static final long HOUR = 3_600_000L;
	private static final long NOW = 1_000_000_000_000L;
	private static final TrustedClock NEVER = () -> {
		throw new AssertionError("the clock was read with no unlock timer running and no flag");
	};
	private static final TrustedClock ONLINE = () -> new TrustedClock.Reading.At(NOW);
	private static final TrustedClock OFFLINE = () -> new TrustedClock.Reading.Unavailable("offline");

	@TempDir
	Path dir;

	private GameEnvironment env;
	private LockStore store;

	@BeforeEach
	void setUp() throws IOException {
		Path server = Files.createDirectories(dir.resolve("server"));
		Files.createDirectories(server.resolve("config"));
		Files.writeString(server.resolve("config/mod.json"), "{\"a\":1}");
		Files.writeString(server.resolve("server.properties"), "motd=A server\n");
		env = new GameEnvironment(GameEnvironment.Side.DEDICATED_SERVER, "26.3", "Fabric", "0.19.5",
				List.of(new GameEnvironment.ModFile("somemod", "1.0", null)), server, server.resolve("world"));
		Files.createDirectories(env.worldDir());
		store = new LockStore(dir.resolve("locks"), () -> 1L, UUID::randomUUID);
	}

	@Test
	void anUncommittedWorldLoads() {
		ServerGate.Outcome outcome = decide(NEVER, null);
		assertTrue(outcome.load());
		assertNull(outcome.refusal());
	}

	@Test
	void theCommittedSetupLoads() throws IOException {
		commit(OptionalLong.empty());
		ServerGate.Outcome outcome = decide(NEVER, null);
		assertTrue(outcome.load());
		assertEquals(List.of("Commitment: world is committed and its setup matches"), outcome.lines());
	}

	@Test
	void aChangedSetupIsRefusedWithWhatChangedAndTheWayOut() throws IOException {
		commit(OptionalLong.empty());
		Files.writeString(env.gameDir().resolve("server.properties"), "motd=Another server\n");
		ServerGate.Outcome outcome = decide(NEVER, null);
		assertFalse(outcome.load());
		assertTrue(outcome.refusal().matches("Commitment: setup changed .* refusing to load world"), outcome.refusal());
		assertTrue(outcome.lines().stream().anyMatch(l -> l.contains("motd")), outcome.lines().toString());
		assertFalse(String.join("\n", outcome.lines()).contains("Another server"), "a property's value is never logged");
		assertTrue(last(outcome).contains("put the setup back, or start the 24-hour unlock")
				&& last(outcome).contains("-Dcommitment.unlock=start"), last(outcome));
	}

	@Test
	void theStartFlagStartsTheTimerAndTheWorldStaysShut() throws IOException {
		commit(OptionalLong.empty());
		Files.writeString(env.gameDir().resolve("config/mod.json"), "{\"a\":2}");
		ServerGate.Outcome outcome = decide(ONLINE, "start");
		assertFalse(outcome.load());
		assertTrue(last(outcome).startsWith("Commitment: unlock available at "), last(outcome));
		assertEquals(OptionalLong.of(NOW), saved().unlockRequestedAt());
	}

	@Test
	void withoutInternetTimeTheStartFlagSavesNothingAndSaysWhy() throws IOException {
		commit(OptionalLong.empty());
		Files.writeString(env.gameDir().resolve("config/mod.json"), "{\"a\":2}");
		ServerGate.Outcome outcome = decide(OFFLINE, "start");
		assertFalse(outcome.load());
		assertTrue(last(outcome).contains("without internet time") && last(outcome).contains("-Dcommitment.unlock=start"), last(outcome));
		assertEquals(OptionalLong.empty(), saved().unlockRequestedAt());
	}

	@Test
	void theCancelFlagStopsARunningTimer() throws IOException {
		commit(OptionalLong.of(NOW - HOUR));
		Files.writeString(env.gameDir().resolve("config/mod.json"), "{\"a\":2}");
		ServerGate.Outcome outcome = decide(ONLINE, "cancel");
		assertFalse(outcome.load());
		assertEquals("Commitment: unlock cancelled; the world stays committed", last(outcome));
		assertEquals(OptionalLong.empty(), saved().unlockRequestedAt());
	}

	@Test
	void anUnknownFlagValueIsIgnoredAndSaidSo() throws IOException {
		commit(OptionalLong.empty());
		Files.writeString(env.gameDir().resolve("config/mod.json"), "{\"a\":2}");
		ServerGate.Outcome outcome = decide(NEVER, "yes");
		assertFalse(outcome.load());
		assertTrue(outcome.lines().contains("Commitment: -Dcommitment.unlock=yes isn't start or cancel; ignored"), outcome.lines().toString());
		assertEquals(OptionalLong.empty(), saved().unlockRequestedAt());
	}

	@Test
	void after24HoursTheLockLiftsAndTheWorldLoads() throws IOException {
		commit(OptionalLong.of(NOW - 24 * HOUR));
		Files.writeString(env.gameDir().resolve("config/mod.json"), "{\"a\":2}");
		ServerGate.Outcome outcome = decide(ONLINE, null);
		assertTrue(outcome.load(), String.valueOf(outcome.refusal()));
		assertEquals("Commitment: lock lifted, run /commit to lock again", last(outcome));
		assertFalse(Files.exists(env.worldDir().resolve(LockStore.WORLD_RECORD)));
		assertFalse(Files.exists(env.worldDir().resolve(GuardPack.FILE)));
		assertTrue(Files.exists(env.worldDir().resolve(LockStore.WORLD_RECORD + "." + ID + ".lifted")));
	}

	/** The lift is all-or-nothing (B.6): a file it can't vouch for keeps every piece in place and the world shut. */
	@Test
	void aLiftThatCantFinishKeepsTheWorldShutAndRenamesNothing() throws IOException {
		commit(OptionalLong.of(NOW - 24 * HOUR));
		Files.writeString(env.worldDir().resolve(GuardPack.FILE), "not a pack");
		ServerGate.Outcome outcome = decide(ONLINE, null);
		assertFalse(outcome.load());
		assertTrue(outcome.refusal().contains("can't lift"), outcome.refusal());
		assertTrue(Files.exists(env.worldDir().resolve(LockStore.WORLD_RECORD)));
		assertEquals("not a pack", Files.readString(env.worldDir().resolve(GuardPack.FILE)));
		try (Stream<Path> files = Files.walk(dir)) {
			assertEquals(List.of(), files.filter(p -> p.toString().endsWith(".lifted")).toList());
		}
	}

	@Test
	void aDamagedRecordOnlyOffersTheUnlock() throws IOException {
		Files.createDirectories(env.worldDir().resolve("datapacks"));
		Files.write(env.worldDir().resolve(GuardPack.FILE), GuardPack.build(ID));
		ServerGate.Outcome outcome = decide(NEVER, null);
		assertFalse(outcome.load());
		assertTrue(last(outcome).startsWith("Commitment: start the 24-hour unlock"), last(outcome));
	}

	@Test
	void aLockThatCantBeReadIsRefused() throws IOException {
		commit(OptionalLong.empty());
		Path record = env.worldDir().resolve(LockStore.WORLD_RECORD);
		Files.delete(record);
		Files.createDirectories(record.resolve("not-a-file"));
		ServerGate.Outcome outcome = decide(NEVER, null);
		assertFalse(outcome.load());
		assertTrue(outcome.refusal().startsWith("Commitment: can't check the lock of world"), outcome.refusal());
	}

	private ServerGate.Outcome decide(TrustedClock clock, String flag) {
		return ServerGate.decide(env, store, clock, flag, ZoneOffset.UTC);
	}

	private static String last(ServerGate.Outcome outcome) {
		return outcome.lines().getLast();
	}

	private LockRecord saved() throws IOException {
		LockStore.Loaded loaded = store.load(env.worldDir(), GuardPack.read(env.worldDir()), OptionalLong.empty());
		return ((LockStore.Loaded.Locked) loaded).record();
	}

	private void commit(OptionalLong requestedAt) throws IOException {
		LockRecord record = LockRecord.committed(ID, 0, "world", 1L, ManifestScanner.scan(env), new TreeMap<>())
				.withUnlockRequestedAt(requestedAt);
		store.create(env.worldDir(), record);
		GuardPack.write(env.worldDir(), ID);
	}
}
