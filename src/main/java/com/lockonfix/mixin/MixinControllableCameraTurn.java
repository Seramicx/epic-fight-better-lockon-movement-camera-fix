package com.lockonfix.mixin;

import com.lockonfix.handler.LockOnMovementHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Extends the {@code processingMouseTurn} gate to cover Controllable's camera
 * path. Controllable's {@code CameraHandler.updateCamera} calls
 * {@code mc.player.turn} directly from a {@code START_RENDER} callback,
 * bypassing {@code MouseHandler.turnPlayer}. Without this mixin the gate
 * would see right-stick turns as synthetic and let vanilla write straight to
 * {@code player.yRot}, which mount-rotate then overwrites: the right-stick
 * would have no camera effect during decouple.
 *
 * <p>{@link Pseudo} silently skips this mixin when Controllable isn't on the
 * classpath. {@code require = 0} skips on version mismatch instead of
 * crashing mod load.
 */
@Pseudo
@Mixin(targets = "com.mrcrayfish.controllable.client.CameraHandler", remap = false)
public abstract class MixinControllableCameraTurn {

    @Inject(method = "updateCamera", at = @At("HEAD"), remap = false, require = 0)
    private void lockonfix$beforeUpdateCamera(CallbackInfo ci) {
        LockOnMovementHandler.setProcessingMouseTurn(true);
    }

    @Inject(method = "updateCamera", at = @At("RETURN"), remap = false, require = 0)
    private void lockonfix$afterUpdateCamera(CallbackInfo ci) {
        LockOnMovementHandler.setProcessingMouseTurn(false);
    }
}
