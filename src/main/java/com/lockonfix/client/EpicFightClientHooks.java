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
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import yesman.epicfight.api.client.camera.EpicFightCameraAPI;

/**
 * Client-side Epic Fight helpers and a cast-state latch for Iron's Spells.
 *
 * <p>The latch gives a brief grace window after any cast signal drops (held
 * item, {@code ClientMagicData.isCasting}, any cast keymap held). Used by
 * {@link #isAiming(LocalPlayer)} so {@code LockOnMovementHandler.onPlayerTick}
 * bails during aim and Epic Fight's per-tick yRot correction stays in
 * effect.
 */
public final class EpicFightClientHooks {

    public static final EpicFightClientHooks INSTANCE = new EpicFightClientHooks();

    private static final long CAST_LATCH_MS = 500L;

    private static long lastCastSignalMs = 0L;

    private EpicFightClientHooks() {}

    // =====================================================================
    // Lock-on target helper
    // =====================================================================

    public static boolean isLockOnTargeting() {
        try {
            EpicFightCameraAPI api = EpicFightCameraAPI.getInstance();
            return api != null && api.isLockingOnTarget();
        } catch (Throwable t) {
            return false;
        }
    }

    // =====================================================================
    // Cast-state latch, refreshed every tick while any signal is on
    // =====================================================================

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
    public void onClientTick(ClientTickEvent.Post event) {
        if (!IntegrationRegistry.isIronsSpells()) return;

        if (IronSpellsIntegration.anyCastKeymapDown() || IronSpellsIntegration.isCasting()) {
            lastCastSignalMs = System.currentTimeMillis();
        }
    }

    private static boolean castLatchActive() {
        return (System.currentTimeMillis() - lastCastSignalMs) < CAST_LATCH_MS;
    }

    // =====================================================================
    // Public aiming checks
    // =====================================================================

    /**
     * True when the player is drawing a ranged weapon (bow/crossbow/trident),
     * holding an Iron's Spells item, actively casting, or within the brief
     * cast-signal grace window. Used by {@code LockOnMovementHandler} to bail
     * out of its yRot override when Epic Fight should be driving aim.
     */
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

        // anyCastKeymapDown is checked directly so the press tick reports
        // aiming immediately. The latch only refreshes in ClientTickEvent.Post
        // and would otherwise lag one tick.
        return castLatchActive()
                || IronSpellsIntegration.isCasting()
                || IronSpellsIntegration.anyCastKeymapDown();
    }
}
