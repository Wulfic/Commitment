package com.wulfic.commitment.command;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wulfic.commitment.gate.WorldGate;
import com.wulfic.commitment.lock.GuardPack;
import com.wulfic.commitment.lock.LockRecord;
import com.wulfic.commitment.lock.LockStore;
import com.wulfic.commitment.lock.TrustedClock;
import com.wulfic.commitment.manifest.GameEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** C.3: what each of {@code /commit} and {@code /unlock}'s steps does to a world, and says. */
class CommitActionsTest {
	private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-0000000000c3");
	private static final long SEED = 7878231855728691869L;
	private static final long HOUR = 3_600_000L;
	private static final long NOW = 1_000_000_000_000L;
	private static final TrustedClock NEVER = () -> {
		throw new AssertionError("the clock was read with no unlock timer running");
	};
	private static final TrustedClock ONLINE = () -> new TrustedClock.Reading.At(NOW);
	private static final TrustedClock OFFLINE = () -> new TrustedClock.Reading.Unavailable("offline");

	@TempDir
	Path dir;

	private GameEnvironment env;
	private LockStore store;
	private Path mirrors;

	@BeforeEach
	void setUp() throws IOException {
		Path server = Files.createDirectories(dir.resolve("server"));
		Files.createDirectories(server.resolve("config"));
		Files.writeString(server.resolve("config/mod.json"), "{\"a\":1}");
		Files.writeString(server.resolve("server.properties"), "motd=A server\n");
		env = new GameEnvironment(GameEnvironment.Side.DEDICATED_SERVER, "26.3", "Fabric", "0.19.5",
				List.of(new GameEnvironment.ModFile("somemod", "1.0", null)), server, server.resolve("world"));
		Files.createDirectories(env.worldDir());
		mirrors = dir.resolve("locks");
		store = new LockStore(mirrors, () -> 1L, UUID::randomUUID);
	}

	@Test
	void commitPreviewsTheSetupAndAsksWithoutWritingAnything() throws IOException {
		CommitActions.Reply reply = CommitActions.preview(env, store, SEED, NEVER, ZoneOffset.UTC);
		assertEquals(CommitActions.Effect.ASK, reply.effect());
		assertFalse(reply.failure());
		assertEquals("Commitment: /commit locks this world's setup: 1 mod, 0 resource packs, 1 config file.", reply.lines().getFirst());
		assertTrue(reply.lines().getLast().contains("/commit confirm within 60 s"), reply.lines().toString());
		assertNothingWritten();
	}

	@Test
	void commitConfirmWritesTheThreePiecesAndTheGateThenOpensTheWorld() throws IOException {
		CommitActions.Reply reply = commit();
		assertEquals(List.of("Commitment: world committed (1 mod, 0 resource packs, 1 config file)"), reply.lines());
		assertEquals(CommitActions.Effect.COMMITTED, reply.effect());
		assertTrue(Files.isRegularFile(env.worldDir().resolve(LockStore.WORLD_RECORD)));
		assertEquals(ID, GuardPack.read(env.worldDir()).orElseThrow());
		assertTrue(Files.isRegularFile(mirrors.resolve(ID + ".dat")));
		// The same setup the gate will scan at the next open: what /commit locked must open.
		assertInstanceOf(WorldGate.Verdict.Open.class, WorldGate.evaluate(env, store, NEVER));
	}

	@Test
	void theSetupIsReadAgainAtConfirmNotTakenFromThePreview() throws IOException {
		CommitActions.preview(env, store, SEED, NEVER, ZoneOffset.UTC);
		Files.writeString(env.gameDir().resolve("config/second.json"), "{}");
		assertEquals(List.of("Commitment: world committed (1 mod, 0 resource packs, 2 config files)"), commit().lines());
		assertInstanceOf(WorldGate.Verdict.Open.class, WorldGate.evaluate(env, store, NEVER));
	}

	@Test
	void aSecondCommitIsRefusedAndTheLockIsUntouched() throws IOException {
		commit();
		byte[] before = Files.readAllBytes(env.worldDir().resolve(LockStore.WORLD_RECORD));
		CommitActions.Reply again = CommitActions.commit(env, store, SEED, UUID::randomUUID, NOW);
		assertTrue(again.failure());
		assertEquals(List.of(CommitActions.ALREADY_COMMITTED), again.lines());
		assertEquals(CommitActions.Effect.NONE, again.effect());
		assertArrayEquals(before, Files.readAllBytes(env.worldDir().resolve(LockStore.WORLD_RECORD)));
	}

	@Test
	void aStrangersFileAtTheGuardPathIsLeftAloneAndTheCommitSaysSo() throws IOException {
		Path guard = env.worldDir().resolve(GuardPack.FILE);
		Files.createDirectories(guard.getParent());
		Files.writeString(guard, "not Commitment's");
		CommitActions.Reply reply = commit();
		assertEquals(CommitActions.Effect.COMMITTED, reply.effect());
		assertEquals(2, reply.lines().size(), reply.lines().toString());
		assertTrue(reply.lines().get(1).contains("the guard pack couldn't be written"), reply.lines().get(1));
		assertEquals("not Commitment's", Files.readString(guard));
	}

	@Test
	void commitInACommittedWorldShowsWhereItStandsAndDoesntAsk() throws IOException {
		commit();
		CommitActions.Reply reply = CommitActions.preview(env, store, SEED, NEVER, ZoneOffset.UTC);
		assertEquals(CommitActions.Effect.NONE, reply.effect());
		assertEquals(List.of(CommitActions.ALREADY_COMMITTED, "Commitment: this world is committed. /unlock starts the 24-hour unlock"),
				reply.lines());
	}

	@Test
	void statusOfAnUncommittedAndACommittedWorld() throws IOException {
		assertEquals(List.of(CommitActions.NOT_COMMITTED), CommitActions.status(env, store, SEED, NEVER, ZoneOffset.UTC).lines());
		commit();
		assertEquals(List.of("Commitment: this world is committed. /unlock starts the 24-hour unlock"),
				CommitActions.status(env, store, SEED, NEVER, ZoneOffset.UTC).lines());
	}

	@Test
	void unlockNeedsACommittedWorld() throws IOException {
		CommitActions.Reply preview = CommitActions.unlockPreview(env, store, SEED, NEVER, ZoneOffset.UTC);
		assertTrue(preview.failure());
		assertEquals(List.of(CommitActions.NOT_COMMITTED), preview.lines());
		assertTrue(CommitActions.unlock(env, store, SEED, ONLINE, ZoneOffset.UTC).failure());
		assertTrue(CommitActions.cancel(env, store, SEED).failure());
		assertNothingWritten();
	}

	@Test
	void unlockAsksThenConfirmStartsTheTimerByInternetTime() throws IOException {
		commit();
		CommitActions.Reply preview = CommitActions.unlockPreview(env, store, SEED, NEVER, ZoneOffset.UTC);
		assertEquals(CommitActions.Effect.ASK, preview.effect());
		assertTrue(preview.lines().getLast().contains("/unlock confirm within 60 s"), preview.lines().toString());

		CommitActions.Reply started = CommitActions.unlock(env, store, SEED, ONLINE, ZoneOffset.UTC);
		assertFalse(started.failure());
		assertTrue(started.lines().getFirst().matches("Commitment: unlock available at .* \\(24h\\)"), started.lines().toString());
		assertEquals(OptionalLong.of(NOW), record().unlockRequestedAt());

		// With the timer running, /unlock says where it stands instead of asking again.
		TrustedClock anHourLater = () -> new TrustedClock.Reading.At(NOW + HOUR);
		CommitActions.Reply running = CommitActions.unlockPreview(env, store, SEED, anHourLater, ZoneOffset.UTC);
		assertEquals(CommitActions.Effect.NONE, running.effect());
		assertTrue(running.lines().getFirst().contains("(23h 0m left)"), running.lines().toString());
		assertTrue(CommitActions.unlock(env, store, SEED, ONLINE, ZoneOffset.UTC).lines().getFirst().contains("already running"));
	}

	@Test
	void withoutInternetTimeTheUnlockDoesntStart() throws IOException {
		commit();
		CommitActions.Reply reply = CommitActions.unlock(env, store, SEED, OFFLINE, ZoneOffset.UTC);
		assertTrue(reply.failure());
		assertTrue(reply.lines().getFirst().contains("without internet time (offline)"), reply.lines().toString());
		assertEquals(OptionalLong.empty(), record().unlockRequestedAt());
	}

	@Test
	void cancelStopsARunningTimerAndSaysSoWhenThereIsNone() throws IOException {
		commit();
		assertEquals(List.of(CommitActions.NO_TIMER), CommitActions.cancel(env, store, SEED).lines());
		CommitActions.unlock(env, store, SEED, ONLINE, ZoneOffset.UTC);
		CommitActions.Reply cancelled = CommitActions.cancel(env, store, SEED);
		assertFalse(cancelled.failure());
		assertEquals(List.of(CommitActions.CANCELLED), cancelled.lines());
		assertEquals(OptionalLong.empty(), record().unlockRequestedAt());
	}

	@Test
	void after24HoursStatusSaysTheLockLiftsAtTheNextOpen() throws IOException {
		commit();
		CommitActions.unlock(env, store, SEED, ONLINE, ZoneOffset.UTC);
		TrustedClock aDayLater = () -> new TrustedClock.Reading.At(NOW + 24 * HOUR);
		assertEquals(List.of(CommitActions.OVER), CommitActions.status(env, store, SEED, aDayLater, ZoneOffset.UTC).lines());
		// Still committed until the world is opened again: the running world isn't lifted under the player.
		assertTrue(Files.isRegularFile(env.worldDir().resolve(LockStore.WORLD_RECORD)));
	}

	private CommitActions.Reply commit() throws IOException {
		return CommitActions.commit(env, store, SEED, () -> ID, NOW);
	}

	private LockRecord record() throws IOException {
		return ((LockStore.Loaded.Locked) store.load(env.worldDir(), GuardPack.read(env.worldDir()), OptionalLong.of(SEED))).record();
	}

	private void assertNothingWritten() throws IOException {
		assertFalse(Files.exists(env.worldDir().resolve(LockStore.WORLD_RECORD)));
		assertFalse(Files.exists(env.worldDir().resolve(GuardPack.FILE)));
		if (Files.isDirectory(mirrors)) {
			try (Stream<Path> files = Files.list(mirrors)) {
				assertEquals(List.of(), files.toList());
			}
		}
	}
}
