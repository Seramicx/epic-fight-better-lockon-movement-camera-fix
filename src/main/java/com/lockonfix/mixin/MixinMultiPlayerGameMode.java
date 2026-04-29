package com.lockonfix.mixin;

import com.lockonfix.compat.IronSpellsIntegration;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import yesman.epicfight.api.client.camera.EpicFightCameraAPI;

/**
 * Makes the arrow/spell leave the bow in the direction of the TPS crosshair
 * (accounting for shoulder-offset parallax) <em>without</em> visibly rotating
 * the player's camera, head, or body.
 *
 * <p>The trick: right before the {@code RELEASE_USE_ITEM} packet is sent, we
 *
 * <ol>
 *   <li>snapshot {@code yRot}/{@code xRot}/{@code yHeadRot},</li>
 *   <li>call {@code alignPlayerLookToCrosshair(false, false, true)}: sets
 *       those three fields to aim at {@code crosshairHit − playerEye} and
 *       synchronously sends {@code ServerboundMovePlayerPacket.Rot}, but
 *       leaves the "previous tick" Os and {@code yBodyRot} alone,</li>
 *   <li>restore the three snapshot values.</li>
 * </ol>
 *
 * <p>The server receives the rotation packet, then the release packet, and
 * spawns the arrow with crosshair-corrected yaw. On the client, all three
 * writes happen inside one synchronous call with no render frame between
 * them, so the camera never visibly moves.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MixinMultiPlayerGameMode {

    @Inject(method = "releaseUsingItem", at = @At("HEAD"))
    private void lockonfix$alignAimBeforeRelease(Player player, CallbackInfo ci) {
        if (player == null || !player.isUsingItem()) return;

        ItemStack use = player.getUseItem();
        if (use.isEmpty()) return;

        Item item = use.getItem();
        boolean isRanged = item instanceof BowItem
                || item instanceof CrossbowItem
                || item instanceof TridentItem
                || IronSpellsIntegration.isIronsItem(item);
        if (!isRanged) return;

        if (Minecraft.getInstance().options.getCameraType() != CameraType.THIRD_PERSON_BACK) return;

        EpicFightCameraAPI api;
        try {
            api = EpicFightCameraAPI.getInstance();
        } catch (Throwable t) {
            return;
        }
        if (api == null) return;

        // When locked on, lock-on auto-face has already aligned yRot to the target,
        // so the release packet will fire toward the target. Forcing crosshair
        // direction here would override that and shoot at the camera-hit point
        // (typically just past the target due to shoulder offset) instead.
        if (api.isLockingOnTarget()) return;

        float origYRot = player.getYRot();
        float origXRot = player.getXRot();
        float origYHeadRot = player.getYHeadRot();

        try {
            api.alignPlayerLookToCrosshair(false, false, true);
        } catch (Throwable ignored) {
            return;
        } finally {
            player.setYRot(origYRot);
            player.setXRot(origXRot);
            player.setYHeadRot(origYHeadRot);
        }
    }
}
