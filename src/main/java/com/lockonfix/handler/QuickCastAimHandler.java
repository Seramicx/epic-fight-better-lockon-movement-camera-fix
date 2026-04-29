package com.lockonfix.handler;

import com.lockonfix.LockOnMovementFix;
import com.lockonfix.compat.IntegrationRegistry;
import com.lockonfix.compat.IronSpellsIntegration;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import yesman.epicfight.api.client.camera.EpicFightCameraAPI;

/**
 * Iron's Spells quick-cast / spellbook-cast keybinds bypass the vanilla
 * use-item path; their {@code CastPacket}/{@code QuickCastPacket} carries no
 * rotation, so the server casts using its current yaw, which on the client
 * is whatever was last sent via {@code sendPosition} (typically the
 * movement-direction yaw, not the crosshair direction or lock-on target).
 *
 * <p>This handler runs on {@code ClientTickEvent.START} at {@code HIGHEST}
 * priority, before Iron's Spells' default-priority {@code handleKeybinds()}.
 * When a cast keybind is press-edged or a long cast is in progress:
 * <ul>
 *   <li><b>Not locked on</b>: snap yRot/xRot/yHeadRot to aim at
 *       {@code crosshairHit - playerEye} via
 *       {@code EpicFightCameraAPI.alignPlayerLookToCrosshair(false, false, true)}
 *       (which synchronously sends {@code ServerboundMovePlayerPacket.Rot}),
 *       then restore the snapshot. The server processes rotation before
 *       cast, so the spell fires from the corrected yaw. All client writes
 *       happen synchronously so the camera/body never visibly rotate.</li>
 *   <li><b>Locked on</b>: snap yRot/xRot to face the lock-on target, send
 *       {@code ServerboundMovePlayerPacket.Rot} ourselves, and update
 *       {@link LockOnMovementHandler}'s tracked yaw so the auto-face
 *       handler doesn't smooth in from a stale value. Restoration is
 *       skipped (lock-on already wants the body facing the target).</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = LockOnMovementFix.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class QuickCastAimHandler {

    private QuickCastAimHandler() {}

    private static boolean lastAnyCastDown = false;

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onClientTickStart(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!IntegrationRegistry.isIronsSpells()) {
            lastAnyCastDown = false;
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.options.getCameraType() != CameraType.THIRD_PERSON_BACK) {
            lastAnyCastDown = false;
            return;
        }

        LocalPlayer player = mc.player;
        if (player == null) {
            lastAnyCastDown = false;
            return;
        }

        EpicFightCameraAPI api;
        try {
            api = EpicFightCameraAPI.getInstance();
        } catch (Throwable t) {
            return;
        }
        if (api == null) return;

        boolean nowDown = IronSpellsIntegration.anyCastKeymapDown();
        boolean pressEdge = nowDown && !lastAnyCastDown;
        lastAnyCastDown = nowDown;

        boolean ongoing = IronSpellsIntegration.isCasting();

        if (!pressEdge && !ongoing) return;

        if (api.isLockingOnTarget()) {
            LivingEntity target = api.getFocusingEntity();
            if (target == null || !target.isAlive()) return;
            snapToTarget(mc, player, target);
            return;
        }

        float origYRot = player.getYRot();
        float origXRot = player.getXRot();
        float origYHeadRot = player.getYHeadRot();
        try {
            api.alignPlayerLookToCrosshair(false, false, true);
        } catch (Throwable ignored) {
        } finally {
            player.setYRot(origYRot);
            player.setXRot(origXRot);
            player.setYHeadRot(origYHeadRot);
        }
    }

    /**
     * Snap player rotation to face the lock-on target and synchronously send
     * the rotation packet to the server, so the cast packet that Iron's
     * Spells emits next will fire toward the target. Also pushes the new yaw
     * into {@link LockOnMovementHandler} so the auto-face handler doesn't
     * smooth back in from a stale value.
     */
    private static void snapToTarget(Minecraft mc, LocalPlayer player, LivingEntity target) {
        double dx = target.getX() - player.getX();
        double dy = target.getEyeY() - player.getEyeY();
        double dz = target.getZ() - player.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        float pitch = (float) (-(Mth.atan2(dy, horiz) * (180.0 / Math.PI)));

        player.setYRot(yaw);
        player.setXRot(pitch);
        player.setYHeadRot(yaw);
        LockOnMovementHandler.setSmoothedYRot(yaw);

        if (mc.getConnection() != null) {
            mc.getConnection().send(
                    new ServerboundMovePlayerPacket.Rot(yaw, pitch, player.onGround()));
        }
    }
}
