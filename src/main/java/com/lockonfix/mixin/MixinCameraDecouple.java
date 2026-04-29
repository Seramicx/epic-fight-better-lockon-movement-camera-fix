package com.lockonfix.mixin;

import com.lockonfix.handler.LockOnMovementHandler;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Redirects {@code Camera.setup}'s reads of the entity's view yaw/pitch to
 * the decoupled camera state. When mount-rotate is active and the camera
 * entity is the local player, the camera renders from
 * {@link LockOnMovementHandler#getDecoupledCameraYaw()} /
 * {@link LockOnMovementHandler#getDecoupledCameraXRot()} instead of
 * {@code player.viewYRot / viewXRot}, so the camera direction is independent
 * of the player's (now body-aligned) yRot.
 *
 * <p>Both {@code setRotation} and the subsequent {@code move(-zoom, 0, 0)}
 * for 3rd person consume the redirected rotation, so the camera position
 * also ends up behind the camera direction (correct over-the-shoulder
 * geometry).</p>
 */
@Mixin(Camera.class)
public abstract class MixinCameraDecouple {

    @Redirect(
        method = "setup",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getViewYRot(F)F"
        )
    )
    private float lockonfix$redirectViewYRot(Entity entity, float partialTick) {
        if (LockOnMovementHandler.isDecoupleActive()
                && entity == Minecraft.getInstance().player) {
            return LockOnMovementHandler.getDecoupledCameraYaw();
        }
        return entity.getViewYRot(partialTick);
    }

    @Redirect(
        method = "setup",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getViewXRot(F)F"
        )
    )
    private float lockonfix$redirectViewXRot(Entity entity, float partialTick) {
        if (LockOnMovementHandler.isDecoupleActive()
                && entity == Minecraft.getInstance().player) {
            return LockOnMovementHandler.getDecoupledCameraXRot();
        }
        return entity.getViewXRot(partialTick);
    }
}
