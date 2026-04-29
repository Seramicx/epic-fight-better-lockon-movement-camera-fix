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

/**
 * Camera-relative 360° dodge at the skill-packet layer.
 *
 * <p>Base {@link DodgeSkill#getExecutionPacket} reads direction via
 * {@code MovementDirection.fromInputState()} (8-way only) and uses
 * {@code EpicFightCameraAPI.getForwardYRot()} as the base yaw. In non-TPS
 * lock-on mode {@code getForwardYRot()} returns player.yRot, which
 * {@code LockOnMovementHandler} has already rotated to the stick direction,
 * so the dodge would go wherever the body faces rather than where the
 * camera-relative input points.
 *
 * <p>We read raw key states (or precise analog stick values via Controllable)
 * and use the camera yaw as the base, so dodges go where the user expects
 * relative to the screen.
 *
 * <p>Interaction with epicfight_extra: it overrides dodge execution but
 * reads digital input only (input.up/down/left/right), collapsing controller
 * stick directions to 8 cardinals. So we defer to epicfight_extra only on
 * digital input; for analog we run our own mixin to preserve 360° precision.
 */
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

        // Only defer to epicfight_extra on digital input. On controller it
        // would quantize to 8-way; we want the true 360° angle so keep going.
        if (IntegrationRegistry.isEpicFightExtra() && !ControllableIntegration.isAnalogInput(input)) {
            return;
        }

        float cameraYaw = mc.gameRenderer.getMainCamera().getYRot();

        float offsetDegrees;
        int vertical;

        if (ControllableIntegration.isAnalogInput(input)) {
            // Analog path: use precise stick direction
            float[] analog = ControllableIntegration.readAnalogDirection(input);
            float forward = analog[0];
            float strafe  = analog[1];

            if (Math.abs(forward) < 0.01F && Math.abs(strafe) < 0.01F) {
                // No directional input; let vanilla handle it.
                return;
            }

            offsetDegrees = -(float) Math.toDegrees(Math.atan2(strafe, forward));
            vertical = forward < -0.3F ? -1 : (forward > 0.3F ? 1 : 0);
        } else {
            // Digital path: read RAW key states, not rewritten input booleans.
            // LockOnMovementHandler rewrites input.up/down/left/right for movement
            // (in non-TPS it forces up=true, rest=false), which would make every
            // dodge go forward. Raw key states reflect what the user actually pressed.
            int rawForward = 0;
            if (mc.options.keyUp.isDown()) rawForward += 1;
            if (mc.options.keyDown.isDown()) rawForward -= 1;

            int rawStrafe = 0;
            if (mc.options.keyLeft.isDown()) rawStrafe += 1;
            if (mc.options.keyRight.isDown()) rawStrafe -= 1;

            // Fallback to input impulse if no keyboard keys (e.g. controller
            // without Controllable, or some other input mod)
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

            // Same formula as vanilla DodgeSkill / epicfight-extra:
            // -(90 * horizontal * (1 - |vertical|) + 45 * vertical * horizontal)
            offsetDegrees = -(90.0F * horizontal * (1 - Math.abs(vertical))
                            + 45.0F * vertical * horizontal);
        }

        float angle = Mth.wrapDegrees(offsetDegrees + cameraYaw);

        // dodgeType matches the original: vertical >= 0 -> 0 (forward), < 0 -> 1 (backward).
        int dodgeType = vertical < 0 ? 1 : 0;

        CPSkillRequest packet = new CPSkillRequest(container.getSlot());
        packet.getBuffer().writeInt(dodgeType);
        packet.getBuffer().writeFloat(angle);

        cir.setReturnValue(packet);
    }
}
