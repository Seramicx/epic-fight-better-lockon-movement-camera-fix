package com.lockonfix.mixin;

import com.lockonfix.compat.IntegrationRegistry;
import com.lockonfix.compat.ValkyrienSkiesIntegration;
import com.lockonfix.handler.CameraOffsetHandler;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Re-applies the shoulder/overhead offset after VS2 resets the camera to
 * ship-mounted center.
 *
 * <p>VS2's {@code MixinGameRenderer.setupCameraWithMountedShip} is a
 * {@code @WrapOperation} on {@code LevelRenderer.prepareCullFrustum}. Its
 * wrap calls {@code setupWithShipMounted} (wiping our offset) before
 * invoking the wrapped method. This HEAD inject fires inside the real
 * {@code prepareCullFrustum} body, after VS2's reset, so we can re-apply the
 * offset before the cull frustum is prepared.
 *
 * <p>No-op unless VS2 is loaded, the player is mounted on a ship, and the
 * camera is in third-person back view. On land, the
 * {@code ViewportEvent.ComputeCameraAngles} path in
 * {@link CameraOffsetHandler#onCameraAngles} handles the offset.
 */
@Mixin(LevelRenderer.class)
public abstract class MixinLevelRendererForVsCamera {

    @Inject(method = "prepareCullFrustum", at = @At("HEAD"))
    private void lockonfix$reapplyShoulderOffsetWhenVsMounted(CallbackInfo ci) {
        if (!IntegrationRegistry.isValkyrienSkies()) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (mc.options.getCameraType() != CameraType.THIRD_PERSON_BACK) return;
        if (!ValkyrienSkiesIntegration.isMountedOnShip(player)) return;

        Camera camera = mc.gameRenderer.getMainCamera();
        if (camera == null) return;

        CameraOffsetHandler.applyOffsetForVsMountedShip(camera, mc.getFrameTime());
    }
}
