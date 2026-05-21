package com.lockonfix.handler;

import com.lockonfix.LockOnMovementFix;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import yesman.epicfight.api.client.camera.EpicFightCameraAPI;

@EventBusSubscriber(modid = LockOnMovementFix.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class LockOnCrosshairHandler {

    private LockOnCrosshairHandler() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPreCrosshair(RenderGuiLayerEvent.Pre event) {
        if (!VanillaGuiLayers.CROSSHAIR.equals(event.getName())) return;
        try {
            EpicFightCameraAPI api = EpicFightCameraAPI.getInstance();
            if (api != null && api.isLockingOnTarget()) {
                event.setCanceled(true);
            }
        } catch (Throwable ignored) {}
    }
}
