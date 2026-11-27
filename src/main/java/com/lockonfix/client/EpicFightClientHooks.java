package com.lockonfix.client;

import com.lockonfix.compat.IntegrationRegistry;
import com.lockonfix.compat.IronSpellsIntegration;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import yesman.epicfight.api.client.camera.EpicFightCameraAPI;

public final class EpicFightClientHooks {

    public static final EpicFightClientHooks INSTANCE = new EpicFightClientHooks();

    private static long lastCastSignalMs = 0L;

    private EpicFightClientHooks() {}

    public static boolean isLockOnTargeting() {
        try {
            EpicFightCameraAPI api = EpicFightCameraAPI.getInstance();
            return api != null && api.isLockingOnTarget();
        } catch (Throwable t) {
            return false;
        }
    }

    // EF picks with outline shapes from the camera, so grass or anything between the camera and the player can come back as the target
    public static void alignLookForShot(EpicFightCameraAPI api, Player player) {
        HitResult hit = api.getCrosshairHitResult();
        if (hit != null) {
            Vec3 toHit = hit.getLocation().subtract(player.getEyePosition());
            Vec3 look = new Vec3(Minecraft.getInstance().gameRenderer.getMainCamera().getLookVector());
            if (toHit.lengthSqr() < 2.25 || toHit.normalize().dot(look) < 0.1) {
                api.alignPlayerLookToCameraRotation(false, false, true);
                return;
            }
        }
        api.alignPlayerLookToCrosshair(false, false, true);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!IntegrationRegistry.isIronsSpells()) return;

        if (IronSpellsIntegration.anyCastKeymapDown() || IronSpellsIntegration.isCasting()) {
            lastCastSignalMs = System.currentTimeMillis();
        }
    }

    private static boolean castLatchActive() {
        return (System.currentTimeMillis() - lastCastSignalMs) < 500L;
    }

    public static boolean isAiming(LocalPlayer player) {
        if (player == null) return false;

        if (player.isUsingItem()) {
            ItemStack stack = player.getUseItem();
            if (stack.getItem() instanceof BowItem
                    || stack.getItem() instanceof CrossbowItem
                    || stack.getItem() instanceof TridentItem) {
                return true;
            }
            if (IronSpellsIntegration.isIronsItem(stack.getItem())) return true;
        }

        return castLatchActive()
                || IronSpellsIntegration.isCasting()
                || IronSpellsIntegration.anyCastKeymapDown();
    }
}
