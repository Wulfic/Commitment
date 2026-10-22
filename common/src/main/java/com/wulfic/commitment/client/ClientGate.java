package com.wulfic.commitment.client;

import com.wulfic.commitment.Commitment;
import com.wulfic.commitment.gate.WorldGate;
import com.wulfic.commitment.lock.InternetTime;
import com.wulfic.commitment.lock.UnlockTimer;
import com.wulfic.commitment.manifest.GameEnvironment;
import com.wulfic.commitment.platform.Platform;
import com.wulfic.commitment.server.CommitmentServer;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.network.chat.Component;

/**
 * The world-open gate on the client: runs when the world list opens a world, before vanilla takes its session lock.
 * The scan and internet time can take seconds, so the verdict is worked out on a thread of its own behind a "checking"
 * screen, then acted on back on the render thread: open, lift and open, or the locked screen.
 */
public final class ClientGate {
	public static final String LOCKED_LOG = "Commitment: locked screen ({} changes)";

	/** The world the gate just let through: {@code openWorld} re-entered for it goes ahead once. Render thread only. */
	private static String passing;

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

	public static void check(WorldOpenFlows flows, Minecraft minecraft, String levelId, Runnable onCancel) {
		Path world = minecraft.getLevelSource().getBaseDir().resolve(levelId).toAbsolutePath().normalize();
		GameEnvironment env = Platform.environment(GameEnvironment.Side.CLIENT, minecraft.gameDirectory.toPath().toAbsolutePath(), world);
		minecraft.setScreenAndShow(new GenericMessageScreen(Component.literal("Commitment: checking this world's setup...")));
		CompletableFuture.supplyAsync(() -> WorldGate.evaluate(env, CommitmentServer.store(), new InternetTime()),
						task -> Thread.ofPlatform().daemon().name("Commitment world gate").start(task))
				.whenCompleteAsync((verdict, error) -> {
					if (error != null) {
						Commitment.LOGGER.error("Commitment: checking {} failed", levelId, error);
						refuse(minecraft, onCancel, "Commitment can't check this world", List.of(String.valueOf(error.getMessage())));
						return;
					}
					act(flows, minecraft, levelId, onCancel, world, verdict);
				}, minecraft);
	}

	private static void act(WorldOpenFlows flows, Minecraft minecraft, String levelId, Runnable onCancel, Path world,
			WorldGate.Verdict verdict) {
		switch (verdict) {
			case WorldGate.Verdict.Uncommitted() -> open(flows, levelId, onCancel);
			case WorldGate.Verdict.Open matches -> open(flows, levelId, onCancel);
			case WorldGate.Verdict.Lift lift -> {
				Optional<String> failure = WorldGate.lift(world, CommitmentServer.store(), lift.record());
				if (failure.isPresent()) {
					refuse(minecraft, onCancel, "This world is committed", List.of("The 24-hour unlock is over, but " + failure.get()));
				} else {
					Commitment.LOGGER.info(UnlockTimer.message(new UnlockTimer.Status.Lifted(), ZoneId.systemDefault()));
					open(flows, levelId, onCancel);
				}
			}
			case WorldGate.Verdict.Locked locked -> {
				Commitment.LOGGER.info(LOCKED_LOG, locked.changes().size());
				List<String> lines = new ArrayList<>(locked.changes());
				lines.add("");
				lines.add(UnlockTimer.message(locked.timer(), ZoneId.systemDefault()));
				refuse(minecraft, onCancel, "This world is committed: its setup changed", lines);
			}
			case WorldGate.Verdict.Failed failed -> refuse(minecraft, onCancel, "Commitment can't check this world",
					List.of(failed.reason(), "", "The world stays closed until it can be checked."));
		}
	}

	private static void open(WorldOpenFlows flows, String levelId, Runnable onCancel) {
		passing = levelId;
		flows.openWorld(levelId, onCancel);
	}

	/** Until C.4's LockedScreen: vanilla's alert, whose one button goes back to the world list. */
	private static void refuse(Minecraft minecraft, Runnable onCancel, String title, List<String> lines) {
		minecraft.setScreenAndShow(new AlertScreen(onCancel, Component.literal(title), Component.literal(String.join("\n", lines))));
	}
}
