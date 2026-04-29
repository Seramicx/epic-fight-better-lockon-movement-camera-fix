package com.lockonfix.mixin;

import com.lockonfix.handler.LockOnMovementHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forces {@code input.forwardImpulse}/{@code input.leftImpulse} to mount-rotate's
 * desired values at HEAD of {@code LocalPlayer.serverAiStep}.
 *
 * <p>Why: vanilla {@code LocalPlayer.serverAiStep} copies {@code input.*} into
 * {@code this.xxa/zza}, which the mount's {@code getRiddenInput} reads to
 * compute mount movement. Other mods (BetterLockOn especially) modify the
 * input during {@code MovementInputUpdateEvent} subscribers, and may run
 * at a lower priority than us, overriding our writes. By the time
 * {@code serverAiStep} runs, all event subscribers are done, so we get the
 * last word and the mount sees forward-only motion regardless of what BLO
 * tried to do.</p>
 *
 * <p>Gated on {@link LockOnMovementHandler#isMountRotateActive()}; when
 * mount-rotate isn't driving (locked-on, on foot, etc.) this is a no-op.</p>
 */
@Mixin(LocalPlayer.class)
public abstract class MixinLocalPlayerInputOverride {

    @Inject(method = "serverAiStep", at = @At("HEAD"))
    private void lockonfix$forceMountInput(CallbackInfo ci) {
        if (!LockOnMovementHandler.isMountRotateActive()) return;
        LocalPlayer self = (LocalPlayer) (Object) this;
        if (self.input == null) return;
        self.input.forwardImpulse = LockOnMovementHandler.getMountInputMagnitude();
        self.input.leftImpulse = 0F;
    }
}
