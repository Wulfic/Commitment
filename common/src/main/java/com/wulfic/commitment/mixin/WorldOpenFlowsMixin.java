package com.wulfic.commitment.mixin;

import com.wulfic.commitment.client.ClientGate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The client's world-open gate (C.2): every saved world the client opens comes through here, before its session lock. */
@Mixin(WorldOpenFlows.class)
public abstract class WorldOpenFlowsMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	@Inject(method = "openWorld(Ljava/lang/String;Ljava/lang/Runnable;)V", at = @At("HEAD"), cancellable = true)
	private void commitment$gate(String levelId, Runnable onCancel, CallbackInfo ci) {
		if (ClientGate.consumePass(levelId)) {
			return;
		}
		ci.cancel();
		ClientGate.check((WorldOpenFlows) (Object) this, minecraft, levelId, onCancel);
	}
}
