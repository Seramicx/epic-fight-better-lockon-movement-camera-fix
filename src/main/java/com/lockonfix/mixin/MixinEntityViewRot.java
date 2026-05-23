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
