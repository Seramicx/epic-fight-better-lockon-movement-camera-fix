package com.lockonfix.mixin;

import com.lockonfix.handler.LockOnMovementHandler;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts mouse-driven rotation deltas on the local player while
 * mount-rotate decouple is active. Saves yRot/xRot/yRotO/xRotO at HEAD,
 * computes the delta vanilla {@code Entity.turn} applied at RETURN, routes
 * that delta to {@link LockOnMovementHandler#addCameraDelta(float, float)},
 * and restores the body rotation. Net effect: mouse moves the decoupled
 * camera, not the player's body.
 *
 * <p>Gated by {@link LockOnMovementHandler#isDecoupleActive()}: when not
 * active, this is a no-op and vanilla mouse-to-player.yRot proceeds. Other
 * entities (mobs, server-side player) are skipped via the
 * {@code instanceof LocalPlayer} check.</p>
 */
@Mixin(Entity.class)
public abstract class MixinEntityTurnDecouple {

    @Unique private float lockonfix$savedYRot;
    @Unique private float lockonfix$savedXRot;
    @Unique private float lockonfix$savedYRotO;
    @Unique private float lockonfix$savedXRotO;
    @Unique private boolean lockonfix$shouldRestore;

    @Inject(method = "turn(DD)V", at = @At("HEAD"))
    private void lockonfix$beforeTurn(double yaw, double pitch, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        // Only intercept calls made on the local player WHILE MouseHandler.turnPlayer
        // is executing. This filters out Epic Fight's / BLO's synthetic Entity.turn
        // calls (e.g., the lock-off camera-return transition) so they don't get
        // routed into decoupledCameraYaw and visibly rotate the decoupled camera.
        lockonfix$shouldRestore = self instanceof LocalPlayer
                && LockOnMovementHandler.isDecoupleActive()
                && LockOnMovementHandler.isProcessingMouseTurn();
        if (lockonfix$shouldRestore) {
            lockonfix$savedYRot = self.getYRot();
            lockonfix$savedXRot = self.getXRot();
            lockonfix$savedYRotO = self.yRotO;
            lockonfix$savedXRotO = self.xRotO;
        }
    }

    @Inject(method = "turn(DD)V", at = @At("RETURN"))
    private void lockonfix$afterTurn(double yaw, double pitch, CallbackInfo ci) {
        if (!lockonfix$shouldRestore) return;
        Entity self = (Entity) (Object) this;
        // Vanilla turn() applied (yaw*0.15, pitch*0.15) to yRot/xRot and added
        // the same delta to yRotO/xRotO. Capture the resulting delta and
        // route it to the decoupled camera, then restore body rotation.
        float dyaw = self.getYRot() - lockonfix$savedYRot;
        float dpitch = self.getXRot() - lockonfix$savedXRot;
        LockOnMovementHandler.addCameraDelta(dyaw, dpitch);
        self.setYRot(lockonfix$savedYRot);
        self.setXRot(lockonfix$savedXRot);
        self.yRotO = lockonfix$savedYRotO;
        self.xRotO = lockonfix$savedXRotO;
        lockonfix$shouldRestore = false;
    }
}
