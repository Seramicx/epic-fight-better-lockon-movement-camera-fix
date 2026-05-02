package com.lockonfix.mixin;

import com.lockonfix.handler.AutoLockOnHandler;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the raw horizontal cursor delta at HEAD of {@code turnPlayer},
 * before vanilla zeros it. The reflection-based read at
 * {@code ClientTickEvent.START} can race with the zeroing depending on the
 * tick/frame interleaving (especially in 1st person where BLO does not
 * cancel the player.turn call), so this hook gives the flick handler a
 * deterministic source of truth.
 */
@Mixin(MouseHandler.class)
public abstract class MixinMouseHandler {

    @Shadow private double accumulatedDX;

    @Inject(method = "turnPlayer()V", at = @At("HEAD"))
    private void lockonfix$captureMouseDx(CallbackInfo ci) {
        AutoLockOnHandler.recordMouseDx(this.accumulatedDX);
    }
}
