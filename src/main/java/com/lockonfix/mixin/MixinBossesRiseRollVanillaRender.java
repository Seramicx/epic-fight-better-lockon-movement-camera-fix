package com.lockonfix.mixin;

import com.lockonfix.compat.BossesRiseIntegration;
import com.lockonfix.compat.IntegrationRegistry;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import yesman.epicfight.client.world.capabilites.entitypatch.player.LocalPlayerPatch;

/**
 * Lets Bosses'Rise's combat-roll animation render through the vanilla
 * {@code HumanoidModel} pipeline by making EpicFight skip its custom render
 * for the local player only while a Bosses'Rise roll is active.
 *
 * <p>EpicFight's {@code RenderEngine.Events.renderLivingEvent} cancels the
 * vanilla {@code RenderLivingEvent.Pre} when
 * {@code LocalPlayerPatch.overrideRender()} returns true, replacing it with
 * its own armature render. That cancellation prevents
 * {@code HumanoidModel.setup} / {@code HumanoidModel.animateBones} from
 * firing, so any mod whose player animation system hooks those (e.g.
 * Bosses'Rise's {@code PlayerAnimationMixin}) silently doesn't animate the
 * local player.
 *
 * <p>By forcing {@code overrideRender()} to return false during a BR roll,
 * EpicFight falls through to the vanilla render, BR's
 * {@code HumanoidModel} mixin fires, and the rolling animation plays. When
 * the roll ends, this mixin returns no-op and EpicFight's render resumes.
 *
 * <p>Compiles and loads cleanly without Bosses'Rise present -
 * {@link IntegrationRegistry#isBossesRise()} short-circuits, and
 * {@link BossesRiseIntegration#isRolling} is reflection-only.
 */
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
