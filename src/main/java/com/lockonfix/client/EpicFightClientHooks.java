package com.lockonfix.client;

import com.lockonfix.LockOnMovementFix;
import com.lockonfix.compat.IntegrationRegistry;
import com.lockonfix.compat.IronSpellsIntegration;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
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
@Mod.EventBusSubscriber(modid = LockOnMovementFix.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class EpicFightClientHooks {

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

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
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
        // aiming immediately. The latch only refreshes in ClientTickEvent.END
        // and would otherwise lag one tick.
        return castLatchActive()
                || IronSpellsIntegration.isCasting()
                || IronSpellsIntegration.anyCastKeymapDown();
    }
}
