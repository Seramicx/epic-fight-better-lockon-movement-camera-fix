package com.lockonfix.mixin;

import com.lockonfix.compat.BossesRiseIntegration;
import com.lockonfix.compat.IntegrationRegistry;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import yesman.epicfight.client.world.capabilites.entitypatch.player.LocalPlayerPatch;

@Pseudo
@Mixin(value = LocalPlayerPatch.class, remap = false)
public abstract class MixinBossesRiseRollVanillaRender {

    @Inject(method = "overrideRender", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void lockonfix$skipEpicFightRenderDuringBossesRiseRoll(CallbackInfoReturnable<Boolean> cir) {
        if (!IntegrationRegistry.isBossesRise()) return;

        LocalPlayerPatch self = (LocalPlayerPatch) (Object) this;
        net.minecraft.world.entity.LivingEntity orig = self.getOriginal();
        if (!(orig instanceof LocalPlayer player)) return;

        if (BossesRiseIntegration.isRolling(player)) {
            cir.setReturnValue(false);
        }
    }
}
