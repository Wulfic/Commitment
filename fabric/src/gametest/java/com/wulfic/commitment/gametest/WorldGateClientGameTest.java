package com.wulfic.commitment.gametest;

import com.wulfic.commitment.Commitment;
import com.wulfic.commitment.client.ClientGate;
import com.wulfic.commitment.client.LockedScreen;
import com.wulfic.commitment.lock.GuardPack;
import com.wulfic.commitment.lock.LockCodec;
import com.wulfic.commitment.lock.LockStore;
import com.wulfic.commitment.lock.TrustedClock;
import com.wulfic.commitment.lock.UnlockTimer;
import com.wulfic.commitment.server.CommitmentServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.server.MinecraftServer;

/**
 * {@code /commit} and the client's world-open gate (C.2, C.3c) on a real client. The host commits a world with cheats
 * off by typing {@code /commit} and {@code /commit confirm} in chat; the world reopens through the world list's own
 * {@code openWorld}, which proves the setup {@code /commit} scanned is the one the gate scans. One config file added,
 * the same call shows the {@link LockedScreen} (C.4) and no world loads. Its buttons, on a test internet clock: start
 * the 24-hour unlock, cancel it, start it again; seconds before it's due the countdown runs out and its button turns
 * into Open world, which still refuses; after the 24 hours the next open lifts the lock and the world loads.
 */
public class WorldGateClientGameTest implements FabricClientGameTest {
	static final String PASS = "Commitment client gate check: PASS";
	/** The scan runs off the server thread; a minute is far more than a dev instance needs. */
	private static final int REPLY_TICKS = 20 * 60;

	/** The test's internet time (ClientGate's test clock), in epoch millis. */
	private static final AtomicLong INTERNET = new AtomicLong();
	private static final AtomicBoolean OFFLINE = new AtomicBoolean();
	private static final long NO_TIMER = Long.MIN_VALUE;

	/** Every system message the client got, in order. Fabric events can't be unregistered, so it's registered once. */
	private static final List<String> RECEIVED = new CopyOnWriteArrayList<>();

	static {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) {
				RECEIVED.add(message.getString());
			}
		});
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		Path gameDir = context.computeOnClient(client -> client.gameDirectory.toPath().toAbsolutePath().normalize());
		// Committed with the world: a /commit that scanned any other dir than the gate's wouldn't see it, and the reopen
		// below would be refused.
		Path committedConfig = gameDir.resolve("config").resolve("commitment-client-commit-test.json");
		Path extraConfig = gameDir.resolve("config").resolve("commitment-client-gate-test.json");
		Path worldDir = null;
		UUID lockId = null;
		try {
			Files.createDirectories(committedConfig.getParent());
			Files.writeString(committedConfig, "{}");
			TestWorldSave save;
			try (TestSingleplayerContext world = context.worldBuilder().adjustSettings(ui -> ui.setAllowCommands(false)).create()) {
				worldDir = world.getServer().computeOnServer(CommitmentServer::worldDir);
				check(!world.getServer().computeOnServer(server -> server.getWorldData().isAllowCommands()), "the test world has cheats on");
				command(context, "commit", line -> line.matches("Commitment: /commit locks this world's setup: .*, [1-9][0-9]* config files?\\."));
				command(context, "commit confirm", line -> line.startsWith("Commitment: world committed ("));
				check(Files.isRegularFile(worldDir.resolve(LockStore.WORLD_RECORD)), "/commit confirm wrote no lock record");
				check(Files.isRegularFile(worldDir.resolve(GuardPack.FILE)), "/commit confirm wrote no guard pack");
				waitOnServer(context, world, server -> server.getPackRepository().getSelectedIds().contains(CommitmentServer.GUARD_PACK_ID),
						"the guard pack wasn't enabled after /commit confirm");
				check(world.getServer().computeOnServer(CommitmentServer::beating), "no heartbeat after /commit confirm");
				save = world.getWorldSave();
			}
			String levelId = worldDir.getFileName().toString();

			try (TestSingleplayerContext world = save.open()) {
				context.waitTicks(20);
				check(context.computeOnClient(client -> client.level != null) && world.getServer().computeOnServer(server -> server.isRunning()),
						"the world /commit committed didn't open with its setup unchanged");
			}

			Files.createDirectories(extraConfig.getParent());
			Files.writeString(extraConfig, "{}");
			lockId = lockIdOf(worldDir).orElseThrow(() -> new AssertionError("no lock id in the committed world"));
			ClientGate.useTestClock(() -> OFFLINE.get() ? new TrustedClock.Reading.Unavailable("test clock offline")
					: new TrustedClock.Reading.At(INTERNET.get()));
			INTERNET.set(System.currentTimeMillis());
			openLocked(context, levelId);
			check(context.computeOnClient(client -> client.level == null && client.getSingleplayerServer() == null),
					"a world was loaded behind the refusal");
			context.takeScreenshot("commitment-locked");
			checkShows(context, "commitment-client-gate-test.json");
			checkShows(context, "No unlock is running");

			// The way out: start the unlock, cancel it, start it again.
			startUnlock(context);
			context.takeScreenshot("commitment-pending");
			checkShows(context, "Unlock available at");
			check(timerOf(worldDir) == INTERNET.get(), "Start 24-hour unlock didn't save the timer at internet time");
			context.clickScreenButton(LockedScreen.CANCEL);
			context.waitForScreen(LockedScreen.class);
			check(timerOf(worldDir) == NO_TIMER, "Cancel unlock didn't clear the timer");
			long startedAt = INTERNET.addAndGet(60_000);
			startUnlock(context);
			check(timerOf(worldDir) == startedAt, "the second Start 24-hour unlock didn't save the timer");

			// No internet time while the timer runs: the world stays shut, the screen says why, and Try again checks again.
			OFFLINE.set(true);
			context.clickScreenButton("gui.back");
			context.waitForScreen(TitleScreen.class);
			openLocked(context, levelId);
			checkShows(context, "internet time can't be checked");
			OFFLINE.set(false);
			context.clickScreenButton(LockedScreen.RETRY);
			context.waitForScreen(LockedScreen.class);
			checkShows(context, "Unlock available at");

			// Seconds before the 24 hours are up, the countdown runs out on screen and its button turns into Open world;
			// internet time still says it's early, so the world stays shut.
			INTERNET.set(startedAt + UnlockTimer.WAIT.toMillis() - 2_000);
			context.clickScreenButton("gui.back");
			context.waitForScreen(TitleScreen.class);
			openLocked(context, levelId);
			context.waitTicks(20 * 4);
			context.takeScreenshot("commitment-due");
			checkShows(context, "The 24 hours are up");
			context.clickScreenButton(LockedScreen.OPEN);
			context.waitForScreen(LockedScreen.class);
			check(context.computeOnClient(client -> client.level == null), "the world opened before internet time said the 24 hours were up");

			// Once they're up, the next open lifts the lock: Commitment's three files are renamed away and the world opens.
			INTERNET.set(startedAt + UnlockTimer.WAIT.toMillis() + 1_000);
			context.clickScreenButton("gui.back");
			context.waitForScreen(TitleScreen.class);
			try (TestSingleplayerContext world = save.open()) {
				context.waitTicks(20);
				check(context.computeOnClient(client -> client.level != null), "the world didn't open once the unlock was over");
			}
			check(!Files.exists(worldDir.resolve(LockStore.WORLD_RECORD)) && !Files.exists(worldDir.resolve(GuardPack.FILE))
					&& Files.isRegularFile(worldDir.resolve(LockStore.WORLD_RECORD + "." + lockId + ".lifted"))
					&& Files.isRegularFile(worldDir.resolve(GuardPack.FILE + "." + lockId + ".lifted")), "the lift didn't rename the lock's files");
		} catch (IOException e) {
			throw new AssertionError("can't write the test config files " + committedConfig + ", " + extraConfig, e);
		} finally {
			ClientGate.useTestClock(null);
			OFFLINE.set(false);
			cleanUp(CommitmentServer.store(), worldDir, lockId, List.of(committedConfig, extraConfig));
		}
		Commitment.LOGGER.info(PASS);
	}

	/** Opens the world from the world list's own call; the gate refuses it with the locked screen. */
	private static void openLocked(ClientGameTestContext context, String levelId) {
		context.runOnClient(client -> client.createWorldOpenFlows().openWorld(levelId, () -> client.setScreenAndShow(new TitleScreen())));
		context.waitForScreen(LockedScreen.class);
	}

	/**
	 * Is a line holding {@code text} on the locked screen where the player can read it: laid out inside the scroll area,
	 * and the area has room? (A body arranged too late once left the area 0 high: every button worked, nothing showed.)
	 */
	private static void checkShows(ClientGameTestContext context, String text) {
		boolean shown = context.computeOnClient(client -> {
			if (!(screenOf(client) instanceof LockedScreen screen)) {
				return false;
			}
			for (GuiEventListener child : screen.children()) {
				if (child instanceof AbstractWidget area && child instanceof ContainerEventHandler container && area.getHeight() > 0) {
					for (GuiEventListener inner : container.children()) {
						if (inner instanceof MultiLineTextWidget line && line.getMessage().getString().contains(text)
								&& line.getY() >= area.getY() && line.getY() < area.getY() + area.getHeight()) {
							return true;
						}
					}
				}
			}
			return false;
		});
		check(shown, "the locked screen doesn't show a line with \"" + text + "\"");
	}

	/** The screen on show: a field of the client's up to 26.1, the Gui's since 26.2. */
	private static Screen screenOf(Minecraft client) {
		//? if >=26.2 {
		/*return client.gui.screen();
		*///?} else {
		return client.screen;
		//?}
	}

	private static void startUnlock(ClientGameTestContext context) {
		context.clickScreenButton(LockedScreen.START);
		context.waitForScreen(ConfirmScreen.class);
		context.takeScreenshot("commitment-confirm");
		context.clickScreenButton("gui.yes");
		context.waitForScreen(LockedScreen.class);
	}

	/** When the world's lock record says the unlock was started, or {@link #NO_TIMER}. Read only. */
	private static long timerOf(Path worldDir) throws IOException {
		if (!(LockCodec.decode(Files.readAllBytes(worldDir.resolve(LockStore.WORLD_RECORD))) instanceof LockCodec.Decoded.Valid valid)) {
			throw new AssertionError("the lock record of " + worldDir + " isn't valid");
		}
		return valid.record().unlockRequestedAt().orElse(NO_TIMER);
	}

	/** Types {@code /<command>} in chat as the host and waits for the reply it should get. */
	private static void command(ClientGameTestContext context, String command, Predicate<String> reply) {
		int from = RECEIVED.size();
		context.runOnClient(client -> client.player.connection.sendCommand(command));
		try {
			context.waitFor(client -> RECEIVED.subList(from, RECEIVED.size()).stream().anyMatch(reply), REPLY_TICKS);
		} catch (AssertionError e) {
			Commitment.LOGGER.error("Commitment client gate check failed: no expected reply to /{}; got {}", command,
					RECEIVED.subList(from, RECEIVED.size()));
			throw e;
		}
	}

	private static void waitOnServer(ClientGameTestContext context, TestSingleplayerContext world, Predicate<MinecraftServer> ok,
			String message) {
		for (int tick = 0; tick < REPLY_TICKS; tick++) {
			if (world.getServer().computeOnServer(ok::test)) {
				return;
			}
			context.waitTicks(1);
		}
		check(false, message);
	}

	/**
	 * The world sits in the disposable client-test run dir; the mirror and the config files are outside it. A mirror
	 * left behind would commit the next run's world: the test world has the same folder name and seed every run.
	 *
	 * @param knownLock the lock id read once the commit was done, or null to look for it now (a run that failed sooner)
	 */
	private static void cleanUp(LockStore store, Path worldDir, UUID knownLock, List<Path> configFiles) {
		for (Path file : configFiles) {
			try {
				Files.deleteIfExists(file);
			} catch (IOException e) {
				Commitment.LOGGER.error("Commitment client gate check: cleanup failed, remove {} by hand", file, e);
			}
		}
		if (worldDir == null) {
			return;
		}
		Optional<UUID> lockId = Optional.ofNullable(knownLock);
		try {
			if (lockId.isEmpty()) {
				lockId = lockIdOf(worldDir);
			}
			Path mirror = lockId.map(store::mirrorFile).orElse(null);
			if (mirror == null) {
				return;
			}
			// The mirror as written, and as the lift renames it (LockLifter).
			for (Path file : List.of(mirror, mirror.resolveSibling(mirror.getFileName() + ".lifted"))) {
				if (Files.deleteIfExists(file)) {
					Commitment.LOGGER.info("Commitment client gate check: removed the test mirror {}", file);
				}
			}
		} catch (IOException e) {
			Commitment.LOGGER.error("Commitment client gate check: cleanup failed, remove the mirror of {} ({}) by hand", worldDir,
					lockId.map(UUID::toString).orElse("lock unknown"), e);
		}
	}

	/** Read only: the guard pack's lock id, or else the world record's, for a /commit confirm that stopped halfway. */
	private static Optional<UUID> lockIdOf(Path worldDir) throws IOException {
		Optional<UUID> fromPack = GuardPack.read(worldDir);
		if (fromPack.isPresent()) {
			return fromPack;
		}
		Path record = worldDir.resolve(LockStore.WORLD_RECORD);
		if (Files.isRegularFile(record) && LockCodec.decode(Files.readAllBytes(record)) instanceof LockCodec.Decoded.Valid valid) {
			return Optional.of(valid.record().lockId());
		}
		return Optional.empty();
	}

	private static void check(boolean ok, String message) {
		if (!ok) {
			Commitment.LOGGER.error("Commitment client gate check failed: {}", message);
			throw new AssertionError(message);
		}
	}
}
