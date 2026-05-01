package com.lockonfix.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import yesman.epicfight.api.animation.types.ActionAnimation;
import yesman.epicfight.api.client.camera.EpicFightCameraAPI;
import yesman.epicfight.client.world.capabilites.entitypatch.player.LocalPlayerPatch;

/**
 * Snaps EpicFight's {@code modelYRot} to {@code yRot} at the end of every
 * locked-on action begin, so the attack lunge moves toward the target.
 *
 * <p>EpicFight's {@code MoveCoordFunctions.MODEL_COORD} (used by attack
 * lunges) reads the player's transform via {@code getModelMatrix}, which
 * uses {@code modelYRot} - not vanilla {@code yRot}. EpicFight's own
 * {@code LocalPlayerPatch.beginAction} tries to snap them equal:
 * <pre>
 *   if (!useModelYRot || SYNC_CAMERA) modelYRot = original.getYRot();
 * </pre>
 * but that line is skipped when {@code useModelYRot} is true (which can
 * carry over from a previous turning-locked animation). With BLO loaded,
 * BLO's {@code LocalPlayerPatchMixin.redirectYRot} also adds a directional
 * offset (±45°/90°/135°/180°) to {@code yRot} every
 * tick during sprint+lockon based on input direction; that offset propagates
 * through {@code yBodyRot} and into {@code modelYRot} via the standard
 * 45°/tick lerp. Net effect: at attack-start, {@code modelYRot} can
 * sit ±90° away from {@code yRot} (= target direction). The lunge
 * fires in the {@code modelYRot} direction - e.g., NE for S+D,
 * NW for S+A.
 *
 * <p>EpicFight's beginAction does {@code setYRot(toTarget)} at the very end
 * when locked on. We inject at TAIL and force {@code setModelYRot(getYRot(),
 * false)} so {@code modelYRot} matches the corrected {@code yRot}. Subsequent
 * ticks have {@code yBodyRot = yRot} (forced by EpicFight's inaction logic),
 * so the lerp delta is zero and {@code modelYRot} stays on the target line.
 */
@Mixin(value = LocalPlayerPatch.class, remap = false)
public abstract class MixinFixAttackLungeDirection {

    @Inject(method = "beginAction", at = @At("TAIL"), require = 0, remap = false)
    private void lockonfix$snapModelYRotToYRotOnLockOnAttack(ActionAnimation animation, CallbackInfo ci) {
        EpicFightCameraAPI api;
        try {
            api = EpicFightCameraAPI.getInstance();
        } catch (Throwable t) {
            return;
        }
        if (api == null || !api.isLockingOnTarget()) return;

        LivingEntity focus = api.getFocusingEntity();
        if (focus == null || focus.isRemoved()) return;

        LocalPlayerPatch self = (LocalPlayerPatch) (Object) this;
        LivingEntity orig = self.getOriginal();
        if (orig == null) return;

        self.setModelYRot(orig.getYRot(), false);
    }
}
