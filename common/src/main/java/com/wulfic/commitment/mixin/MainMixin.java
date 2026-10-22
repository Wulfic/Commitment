package com.wulfic.commitment.mixin;

import com.wulfic.commitment.gate.ServerGate;
import java.io.IOException;
import net.minecraft.server.Main;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.validation.ContentValidationException;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** The dedicated server's world-open gate (C.2). Main.main opens the world's storage once, before its data loads. */
@Mixin(Main.class)
public abstract class MainMixin {
	@Redirect(method = "main", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/LevelStorageSource;"
			+ "validateAndCreateAccess(Ljava/lang/String;)Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;"))
	private static LevelStorageSource.LevelStorageAccess commitment$gate(LevelStorageSource source, String levelName)
			throws IOException, ContentValidationException {
		LevelStorageSource.LevelStorageAccess access = source.validateAndCreateAccess(levelName);
		ServerGate.atServerStart(access);
		return access;
	}
}
