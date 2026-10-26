package com.wulfic.commitment.client;

import com.wulfic.commitment.Commitment;
import com.wulfic.commitment.command.CommitActions;
import com.wulfic.commitment.gate.WorldGate;
import com.wulfic.commitment.gate.WorldSeed;
import com.wulfic.commitment.lock.InternetTime;
import com.wulfic.commitment.lock.LockRecord;
import com.wulfic.commitment.lock.TrustedClock;
import com.wulfic.commitment.lock.UnlockTimer;
import com.wulfic.commitment.manifest.GameEnvironment;
import com.wulfic.commitment.platform.Platform;
import com.wulfic.commitment.server.CommitmentServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.network.chat.Component;

/**
 * The world-open gate on the client: runs when the world list opens a world, before vanilla takes its session lock.
 * The scan and internet time can take seconds, so the verdict is worked out on a thread of its own behind a "checking"
 * screen, then acted on back on the render thread: open, lift and open, or the {@link LockedScreen}. The locked screen's
 * unlock buttons work the same way, then check the world again, so the screen always shows what was saved.
 */
public final class ClientGate {
	public static final String LOCKED_LOG = "Commitment: locked screen ({} changes)";

	/** The world the gate just let through: {@code openWorld} re-entered for it goes ahead once. Render thread only. */
	private static String passing;
	private static volatile TrustedClock testClock;

	private ClientGate() {
	}

	/** True when the gate already ran for this open of {@code levelId}. */
	public static boolean consumePass(String levelId) {
		if (levelId.equals(passing)) {
			passing = null;
			return true;
		}
		return false;
	}

	/**
	 * Client GameTests only: replaces internet time for the gate and the locked screen's buttons; null puts it back.
	 * Refused outside a development run, so no other mod can fake the 24 hours in a released game.
	 */
	public static void useTestClock(TrustedClock clock) {
		useTestClock(clock, Platform.loader().development());
	}

	static void useTestClock(TrustedClock clock, boolean development) {
		if (!development) {
			throw new IllegalStateException("Commitment's test clock works in development runs only");
		}
		testClock = clock;
	}

	public static void check(WorldOpenFlows flows, Minecraft minecraft, String levelId, Runnable onCancel) {
		check(flows, minecraft, levelId, onCancel, List.of());
	}

	/** @param notices lines shown above the changes if the world stays shut: the last button's outcome */
	private static void check(WorldOpenFlows flows, Minecraft minecraft, String levelId, Runnable onCancel, List<String> notices) {
		Path world = worldDir(minecraft, levelId);
		GameEnvironment env = Platform.environment(GameEnvironment.Side.CLIENT, ClientGameDir.get(), world);
		offThread(minecraft, "commitment.screen.checking", () -> WorldGate.evaluate(env, CommitmentServer.store(), clock()),
				verdict -> act(flows, minecraft, levelId, onCancel, world, verdict, notices),
				error -> {
					Commitment.LOGGER.error("Commitment: checking {} failed", levelId, error);
					minecraft.setScreenAndShow(LockedScreen.failed(String.valueOf(error.getMessage()), onCancel));
				});
	}

	private static void act(WorldOpenFlows flows, Minecraft minecraft, String levelId, Runnable onCancel, Path world,
			WorldGate.Verdict verdict, List<String> notices) {
		switch (verdict) {
			case WorldGate.Verdict.Uncommitted() -> open(flows, levelId, onCancel);
			case WorldGate.Verdict.Open matches -> {
				Commitment.LOGGER.info("Commitment: {} is committed and its setup matches", levelId);
				open(flows, levelId, onCancel);
			}
			case WorldGate.Verdict.Lift lift -> {
				Optional<String> failure = WorldGate.lift(world, CommitmentServer.store(), lift.record());
				if (failure.isPresent()) {
					minecraft.setScreenAndShow(LockedScreen.failed("The 24-hour unlock is over, but " + failure.get(), onCancel));
				} else {
					Commitment.LOGGER.info(UnlockTimer.message(new UnlockTimer.Status.Lifted(), ZoneId.systemDefault()));
					open(flows, levelId, onCancel);
				}
			}
			case WorldGate.Verdict.Locked locked -> {
				Commitment.LOGGER.info(LOCKED_LOG, locked.changes().size());
				minecraft.setScreenAndShow(LockedScreen.locked(locked.changes(), notices, locked.timer(), onCancel,
						actions(flows, minecraft, levelId, onCancel, world, locked.record())));
			}
			case WorldGate.Verdict.Failed failed -> minecraft.setScreenAndShow(LockedScreen.failed(failed.reason(), onCancel));
		}
	}

	/** The locked screen's buttons: each saves through the same code as {@code /unlock}, then the gate runs again. */
	private static LockedScreen.Actions actions(WorldOpenFlows flows, Minecraft minecraft, String levelId, Runnable onCancel, Path world,
			LockRecord record) {
		GameEnvironment env = Platform.environment(GameEnvironment.Side.CLIENT, ClientGameDir.get(), world);
		long seed = WorldSeed.read(world).orElse(record.seed());
		return new LockedScreen.Actions() {
			@Override
			public void startUnlock() {
				run("start the unlock", () -> CommitActions.unlock(env, CommitmentServer.store(), seed, clock(), ZoneId.systemDefault()));
			}

			@Override
			public void cancelUnlock() {
				run("cancel the unlock", () -> CommitActions.cancel(env, CommitmentServer.store(), seed));
			}

			@Override
			public void recheck() {
				check(flows, minecraft, levelId, onCancel, List.of());
			}

			private void run(String what, IoAction action) {
				Commitment.LOGGER.info("Commitment: the locked screen asked to {} for {}", what, levelId);
				offThread(minecraft, "commitment.screen.working", () -> {
							try {
								return action.run();
							} catch (IOException e) {
								throw new UncheckedIOException(e);
							}
						}, reply -> {
							reply.lines().forEach(line -> Commitment.LOGGER.info("Commitment: locked screen: {}", line));
							check(flows, minecraft, levelId, onCancel, reply.failure() ? reply.lines() : List.of());
						},
						error -> {
							Commitment.LOGGER.error("Commitment: the locked screen couldn't {} for {}", what, levelId, error);
							check(flows, minecraft, levelId, onCancel, List.of("Commitment couldn't " + what + ": " + error.getMessage()));
						});
			}
		};
	}

	@FunctionalInterface
	private interface IoAction {
		CommitActions.Reply run() throws IOException;
	}

	/** Shows {@code waitingKey}, does {@code work} on a Commitment daemon thread, then hands the result to the render thread. */
	private static <T> void offThread(Minecraft minecraft, String waitingKey, Supplier<T> work, Consumer<T> done,
			Consumer<Throwable> failed) {
		minecraft.setScreenAndShow(new GenericMessageScreen(Component.translatable(waitingKey)));
		CompletableFuture.supplyAsync(work, task -> Thread.ofPlatform().daemon().name("Commitment world gate").start(task))
				.whenCompleteAsync((result, error) -> {
					if (error != null) {
						failed.accept(error.getCause() != null ? error.getCause() : error);
					} else {
						done.accept(result);
					}
				}, minecraft);
	}

	private static Path worldDir(Minecraft minecraft, String levelId) {
		return minecraft.getLevelSource().getBaseDir().resolve(levelId).toAbsolutePath().normalize();
	}

	private static TrustedClock clock() {
		TrustedClock test = testClock;
		return test != null ? test : new InternetTime();
	}

	private static void open(WorldOpenFlows flows, String levelId, Runnable onCancel) {
		passing = levelId;
		flows.openWorld(levelId, onCancel);
	}
}
