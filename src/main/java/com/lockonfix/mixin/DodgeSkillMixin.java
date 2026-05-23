package com.lockonfix.mixin;

import com.lockonfix.compat.ControllableIntegration;
import com.lockonfix.compat.IntegrationRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import yesman.epicfight.client.world.capabilites.entitypatch.player.LocalPlayerPatch;
import yesman.epicfight.network.client.CPSkillRequest;
import yesman.epicfight.skill.SkillContainer;
import yesman.epicfight.skill.dodge.DodgeSkill;

@Mixin(value = DodgeSkill.class, remap = false, priority = 1100)
public abstract class DodgeSkillMixin {

    @Inject(method = "getExecutionPacket", at = @At("HEAD"), cancellable = true, remap = false)
    private void lockonfix$cameraRelativeDodge(
        SkillContainer container, FriendlyByteBuf originalBuf,
        CallbackInfoReturnable<Object> cir
    ) {
        LocalPlayerPatch patch;
        try {
            patch = container.getClientExecutor();
        } catch (Exception e) {
            return;
        }
        if (patch == null) return;

        LocalPlayer player = patch.getOriginal();
        if (player == null) return;

        Minecraft mc = Minecraft.getInstance();
        Input input = player.input;

        if (IntegrationRegistry.isEpicFightExtra() && !ControllableIntegration.isAnalogInput(input)) {
            return;
        }

        float cameraYaw = mc.gameRenderer.getMainCamera().getYRot();

        float offsetDegrees;
        int vertical;

        if (ControllableIntegration.isAnalogInput(input)) {
            float[] analog = ControllableIntegration.readAnalogDirection(input);
            float forward = analog[0];
            float strafe  = analog[1];

            if (Math.abs(forward) < 0.01F && Math.abs(strafe) < 0.01F) {
                return;
            }

            offsetDegrees = -(float) Math.toDegrees(Math.atan2(strafe, forward));
            vertical = forward < -0.3F ? -1 : (forward > 0.3F ? 1 : 0);
        } else {
            int rawForward = 0;
            if (mc.options.keyUp.isDown()) rawForward += 1;
            if (mc.options.keyDown.isDown()) rawForward -= 1;

            int rawStrafe = 0;
            if (mc.options.keyLeft.isDown()) rawStrafe += 1;
            if (mc.options.keyRight.isDown()) rawStrafe -= 1;

            if (rawForward == 0 && rawStrafe == 0) {
                if (Math.abs(input.forwardImpulse) > 0.3F) {
                    rawForward = input.forwardImpulse > 0 ? 1 : -1;
                }
                if (Math.abs(input.leftImpulse) > 0.3F) {
                    rawStrafe = input.leftImpulse > 0 ? 1 : -1;
                }
            }

            vertical = rawForward;
            int horizontal = rawStrafe;

            offsetDegrees = -(90.0F * horizontal * (1 - Math.abs(vertical))
                            + 45.0F * vertical * horizontal);
        }

        float angle = Mth.wrapDegrees(offsetDegrees + cameraYaw);

        int dodgeType = vertical < 0 ? 1 : 0;

        CPSkillRequest packet = new CPSkillRequest(container.getSlot());
        packet.getBuffer().writeInt(dodgeType);
        packet.getBuffer().writeFloat(angle);

        cir.setReturnValue(packet);
    }
}
