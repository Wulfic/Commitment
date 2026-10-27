package com.wulfic.commitment.gametest;

import com.wulfic.commitment.Commitment;
import com.wulfic.commitment.client.LockedScreen;
import com.wulfic.commitment.lock.LockStore;
import com.wulfic.commitment.server.CommitmentServer;
import java.nio.file.Files;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.server.MinecraftServer;

/**
 * Stage E's pack driver (TODO E.1a): plays a real modpack's client with no one at the keyboard, for
 * {@code scripts/pack-smoke.sh}. Vanilla's Quick Play opens the world; this types {@code /commit} and
 * {@code /commit confirm} ({@code -Dcommitment.packDriver=commit}) or just plays a while ({@code reopen}), then quits
 * through {@link Minecraft#stop()}, the way the window's close button does, so mods write their configs on the way out
 * as they would for a player. Inert without the property. It lives in the GameTest mod, never in the shipped jar.
 * Each loader's GameTest mod calls {@link #tick} from its client tick event.
 */
public final class PackDriver {
	public static final String PROPERTY = "commitment.packDriver";
	/** Ticks in the world before the driver acts: long enough for the world to settle and the mods' join work to run. */
	static final int SETTLE_TICKS = 100;
	/**
	 * {@code /commit}'s preview scans the whole setup first (about 2 s on a 148-mod pack), and a confirm before it's done
	 * gets "nothing to confirm". So the confirm is sent every 10 s until the lock record is on disk, 6 times at most.
	 */
	static final int CONFIRM_EVERY = 200;
	static final int CONFIRM_TRIES = 6;
	static final int REOPEN_TICKS = 200;
	/** A locked screen this long has been drawn and logged; a clean quit then beats the script ending the JVM. */
	static final int LOCKED_TICKS = 100;
	/** Out of a world this long (5 min) means something holds Quick Play: a mod's first-launch screen, a crash dialog. */
	static final int STUCK_TICKS = 6000;
	static final int SCREEN_LOG_EVERY = 400;

	private static final String PHASE = phase();
	private static int inWorld;
	private static int outOfWorld;
	private static int locked;
	private static int committedAt;
	private static boolean stopping;

	private PackDriver() {
	}

	public static boolean active() {
		return PHASE != null;
	}

	private static String phase() {
		String phase = System.getProperty(PROPERTY);
		if (phase == null) {
			return null;
		}
		if (!phase.equals("commit") && !phase.equals("reopen")) {
			throw new IllegalArgumentException("-D" + PROPERTY + " must be commit or reopen, not '" + phase + "'");
		}
		Commitment.LOGGER.info("Commitment pack driver: phase {}", phase);
		return phase;
	}

	public static void tick(Minecraft minecraft) {
		if (PHASE == null || stopping) {
			return;
		}
		if (minecraft.level == null || minecraft.player == null) {
			inWorld = 0;
			outOfWorld++;
			Screen screen = screen(minecraft);
			// A fresh game dir shows vanilla's accessibility onboarding first and holds Quick Play behind it. Continue is
			// what a player presses; it records the onboarding as done in options.txt, as it would for them.
			if (screen instanceof AccessibilityOnboardingScreen) {
				Commitment.LOGGER.info("Commitment pack driver: pressing Continue on the accessibility onboarding");
				screen.onClose();
				return;
			}
			if (screen instanceof LockedScreen) {
				if (++locked == LOCKED_TICKS) {
					stop(minecraft, "the locked screen is up");
				}
				return;
			}
			if (outOfWorld % SCREEN_LOG_EVERY == 0) {
				Commitment.LOGGER.info("Commitment pack driver: not in a world after {} ticks; screen {}", outOfWorld,
						screen == null ? "none" : screen.getClass().getName());
			}
			if (outOfWorld >= STUCK_TICKS) {
				stop(minecraft, "stuck out of the world at " + (screen == null ? "no screen" : screen.getClass().getName()));
			}
			return;
		}
		inWorld++;
		if (PHASE.equals("commit")) {
			if (inWorld == SETTLE_TICKS) {
				command(minecraft, "commit");
			} else if (committedAt > 0) {
				if (inWorld == committedAt + SETTLE_TICKS) {
					stop(minecraft, "commit done");
				}
			} else if (inWorld > SETTLE_TICKS && (inWorld - SETTLE_TICKS) % CONFIRM_EVERY == 0) {
				if (committed(minecraft)) {
					committedAt = inWorld;
				} else if ((inWorld - SETTLE_TICKS) / CONFIRM_EVERY > CONFIRM_TRIES) {
					stop(minecraft, "commit never confirmed");
				} else {
					command(minecraft, "commit confirm");
				}
			}
		} else if (inWorld == REOPEN_TICKS) {
			stop(minecraft, "reopen done");
		}
	}

	/** The integrated server's world holds its lock record: {@code /commit confirm} went through. */
	private static boolean committed(Minecraft minecraft) {
		MinecraftServer server = minecraft.getSingleplayerServer();
		return server != null && Files.isRegularFile(CommitmentServer.worldDir(server).resolve(LockStore.WORLD_RECORD));
	}

	private static void command(Minecraft minecraft, String command) {
		Commitment.LOGGER.info("Commitment pack driver: /{}", command);
		minecraft.player.connection.sendCommand(command);
	}

	private static void stop(Minecraft minecraft, String why) {
		stopping = true;
		Commitment.LOGGER.info("Commitment pack driver: {} phase over ({}), stopping", PHASE, why);
		minecraft.stop();
	}

	private static Screen screen(Minecraft minecraft) {
		//? if >=26.2 {
		/*return minecraft.gui.screen();
		*///?} else {
		return minecraft.screen;
		//?}
	}
}
