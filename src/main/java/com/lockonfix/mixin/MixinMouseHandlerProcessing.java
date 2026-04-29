package com.lockonfix.mixin;

import com.lockonfix.handler.LockOnMovementHandler;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sets a flag for the duration of {@code MouseHandler.turnPlayer()} so that
 * {@link MixinEntityTurnDecouple} can distinguish mouse-driven calls to
 * {@code Entity.turn(double, double)} from synthetic calls made by other
 * mods (Epic Fight's lock-off transition is the canonical example: it
 * smoothly rotates the player back to mouse direction over ~2 seconds via
 * repeated {@code Entity.turn} calls). Only mouse-driven deltas should
 * update {@code decoupledCameraYaw}; Epic Fight's synthetic deltas would
 * rotate our decoupled camera toward the mount direction, producing the
 * "camera rotates back to mount direction for 2 sec" feel after lock-off.
 *
 * <p>Mirrors Better Third Person's
 * {@code startPlayerTurning}/{@code stopPlayerTurning} pattern.</p>
 */
@Mixin(MouseHandler.class)
public abstract class MixinMouseHandlerProcessing {

    @Inject(method = "turnPlayer", at = @At("HEAD"))
    private void lockonfix$beforeTurnPlayer(CallbackInfo ci) {
        LockOnMovementHandler.setProcessingMouseTurn(true);
    }

    @Inject(method = "turnPlayer", at = @At("RETURN"))
    private void lockonfix$afterTurnPlayer(CallbackInfo ci) {
        LockOnMovementHandler.setProcessingMouseTurn(false);
    }
}
