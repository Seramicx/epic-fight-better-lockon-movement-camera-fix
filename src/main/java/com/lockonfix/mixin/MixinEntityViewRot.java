package com.lockonfix.mixin;

import com.lockonfix.compat.IntegrationRegistry;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import yesman.epicfight.api.client.camera.EpicFightCameraAPI;

/**
 * Override {@code LocalPlayer.getViewYRot/getViewXRot} when locked-on in
 * 1st person, returning BLO's {@code cameraYRot/cameraXRot} partial-tick
 * lerp directly.
 *
 * <p>Why: BLO's {@code setupCamera} per-frame writes {@code player.yRot} to
 * the cameraYRot lerp value. Vanilla {@code Camera.setup} then renders
 * using {@code entity.getViewYRot(partialTick)}. For LocalPlayer this
 * dispatches to LocalPlayer's OVERRIDE of getViewYRot (NOT the Entity
 * default), which uses {@code Mth.lerp(yRotO, yRot, partialTick)}. If yRot
 * gets a wild value (mouse rotation, sprint-backward) and yRotO gets
 * unwrapped by 360, the lerp sweeps across that gap each render frame
 * -- visible camera jitter.
 *
 * <p>By bypassing the lerp and returning BLO's already-lerped cameraYRot,
 * the camera always renders at the target-tracking value regardless of
 * what player.yRot is. yRot stays free for vanilla travel() to use during
 * the tick.
 */
@Mixin(LocalPlayer.class)
public abstract class MixinEntityViewRot {

    @Inject(method = "getViewYRot(F)F", at = @At("HEAD"), cancellable = true)
    private void lockonfix$overrideYRotForCamera(float partialTick, CallbackInfoReturnable<Float> cir) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer self = (LocalPlayer)(Object)this;
        if (self != mc.player) return;
        if (mc.options.getCameraType() != CameraType.FIRST_PERSON) return;
        if (!IntegrationRegistry.isBetterLockOn()) return;
        try {
            EpicFightCameraAPI api = EpicFightCameraAPI.getInstance();
            if (api == null || !api.isLockingOnTarget()) return;
            cir.setReturnValue(Mth.rotLerp(partialTick, api.getCameraYRotO(), api.getCameraYRot()));
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "getViewXRot(F)F", at = @At("HEAD"), cancellable = true)
    private void lockonfix$overrideXRotForCamera(float partialTick, CallbackInfoReturnable<Float> cir) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer self = (LocalPlayer)(Object)this;
        if (self != mc.player) return;
        if (mc.options.getCameraType() != CameraType.FIRST_PERSON) return;
        if (!IntegrationRegistry.isBetterLockOn()) return;
        try {
            EpicFightCameraAPI api = EpicFightCameraAPI.getInstance();
            if (api == null || !api.isLockingOnTarget()) return;
            cir.setReturnValue(Mth.lerp(partialTick, api.getCameraXRotO(), api.getCameraXRot()));
        } catch (Throwable ignored) {
        }
    }
}
