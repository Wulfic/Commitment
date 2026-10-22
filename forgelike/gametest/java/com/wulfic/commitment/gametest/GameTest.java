package com.wulfic.commitment.gametest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import net.minecraft.world.level.block.Rotation;

/**
 * The server GameTest annotation on NeoForge and Forge (plan step C.0, after ../enchanting-2). The tests are written
 * against Fabric's {@code net.fabricmc.fabric.api.gametest.v1.GameTest}; these nodes compile them with that one import
 * swapped for this class, and their registrar reads it. Fabric's attributes and defaults, except the structure: Fabric's
 * empty template isn't there, so ours is (the same 8x8x8 of air, from ../enchanting-2's make-empty-structure.py).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface GameTest {
	String environment() default "minecraft:default";

	/** The level the test runs in; 26.3 added it to TestData (and Fabric's annotation). Ignored before 26.3. */
	String dimension() default "minecraft:overworld";

	String structure() default "commitment_gametest:empty";

	int maxTicks() default 20;

	int setupTicks() default 0;

	boolean required() default true;

	Rotation rotation() default Rotation.NONE;

	boolean manualOnly() default false;

	int maxAttempts() default 1;

	int requiredSuccesses() default 1;

	boolean skyAccess() default false;

	int padding() default 1;
}
