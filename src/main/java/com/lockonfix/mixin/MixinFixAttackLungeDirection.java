package com.lockonfix.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import yesman.epicfight.api.animation.types.ActionAnimation;
import yesman.epicfight.api.client.camera.EpicFightCameraAPI;
import yesman.epicfight.client.world.capabilites.entitypatch.player.LocalPlayerPatch;

@Pseudo
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
