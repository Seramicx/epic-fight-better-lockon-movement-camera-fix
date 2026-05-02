package com.lockonfix.handler;

import com.lockonfix.LockOnMovementFix;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import yesman.epicfight.api.client.camera.EpicFightCameraAPI;

@Mod.EventBusSubscriber(modid = LockOnMovementFix.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class LockOnCrosshairHandler {

    private LockOnCrosshairHandler() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPreCrosshair(RenderGuiOverlayEvent.Pre event) {
        if (!VanillaGuiOverlay.CROSSHAIR.id().equals(event.getOverlay().id())) return;
        try {
            EpicFightCameraAPI api = EpicFightCameraAPI.getInstance();
            if (api != null && api.isLockingOnTarget()) {
                event.setCanceled(true);
            }
        } catch (Throwable ignored) {}
    }
}
