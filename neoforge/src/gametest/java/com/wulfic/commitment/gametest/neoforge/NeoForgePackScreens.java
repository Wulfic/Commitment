package com.wulfic.commitment.gametest.neoforge;

import com.wulfic.commitment.Commitment;
import com.wulfic.commitment.gametest.PackDriver;
import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.gui.LoadingErrorScreen;

/**
 * NeoForge's own screen in a pack run (TODO E.1): a pack whose mods load with warnings (another mod's deprecated
 * {@code logoFile}) gets NeoForge's warnings screen at every launch, and a player presses Continue. This does the same,
 * only when there are no errors: an errors screen has no Continue, stays up, and the pack run reports it as stuck.
 * Called from the client tick only, so a GameTest server never loads it.
 */
final class NeoForgePackScreens {
	private NeoForgePackScreens() {
	}

	static void tick(Minecraft minecraft) {
		if (PackDriver.screen(minecraft) instanceof LoadingErrorScreen screen) {
			try {
				List<?> errors = (List<?>) field("modLoadErrors").get(screen);
				if (errors.isEmpty()) {
					List<?> warnings = (List<?>) field("modLoadWarnings").get(screen);
					Commitment.LOGGER.info("Commitment pack driver: pressing Continue on NeoForge's warnings screen ({} warnings)", warnings.size());
					((Runnable) field("nextScreenTask").get(screen)).run();
				}
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException("NeoForge's LoadingErrorScreen changed: " + e, e);
			}
		}
		PackDriver.tick(minecraft);
	}

	private static Field field(String name) throws NoSuchFieldException {
		Field field = LoadingErrorScreen.class.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}
}
