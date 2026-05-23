package com.lockonfix.mixin;

import com.lockonfix.compat.ControllableIntegration;
import com.lockonfix.compat.IntegrationRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import yesman.epicfight.client.events.engine.ControlEngine;
import yesman.epicfight.client.world.capabilites.entitypatch.player.LocalPlayerPatch;
import yesman.epicfight.skill.SkillContainer;
import yesman.epicfight.skill.dodge.DodgeSkill;

/**
 * Camera-relative 360 dodge at the skill-argument layer.
 *
 * <p>Base {@link DodgeSkill#gatherArguments} reads input via
 * {@code MovementDirection.fromInputState(InputManager.getInputState(...))}
 * (8-way only) and uses {@code EpicFightCameraAPI.getForwardYRot()} as base
 * yaw. In non-TPS lock-on mode {@code getForwardYRot()} returns player.yRot,
 * which {@code LockOnMovementHandler} has already rotated to the stick
 * direction, so the dodge would go wherever the body faces rather than where
 * the camera-relative input points.
 *
 * <p>We read raw key states (or precise analog stick values via Controllable)
 * and use the camera yaw as the base, so dodges go where the user expects
 * relative to the screen.
 *
 * <p>Hook target on 1.21.1 is
 * {@code gatherArguments(SkillContainer, ControlEngine, CompoundTag)}: we
 * write {@code "direction"} (int) and {@code "yRot"} (float) into the
 * CompoundTag and cancel the original to skip its 8-way calculation.
 *
 * <p>Interaction with epicfight_extra: defers on digital input only; for
 * analog we run our own mixin to preserve 360 precision.
 */
@Mixin(value = DodgeSkill.class, remap = false, priority = 1100)
public abstract class DodgeSkillMixin {

    @Inject(method = "gatherArguments", at = @At("HEAD"), cancellable = true, remap = false)
    private void lockonfix$cameraRelativeDodge(
        SkillContainer container, ControlEngine controlEngine, CompoundTag arguments,
        CallbackInfo ci
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
        // would quantize to 8-way; we want the true 360 angle so keep going.
        if (IntegrationRegistry.isEpicFightExtra() && !ControllableIntegration.isAnalogInput(input)) {
            return;
        }

        // Read the actual rendered camera yaw - the value the user sees as
        // "forward". Reliable across all camera modes:
        //   - Lock-on: EpicFight's correctCamera listener (post-Camera.setup,
        //     via ViewportEvent.ComputeCameraAngles) overwrites Camera.yRot
        //     to point at the target.
        //   - Sprint/mount decouple: SSR's MixinCamera writes Camera.yRot
        //     from its decoupled internal yaw (mouse direction).
        //   - Vanilla 3rd-person: Camera.yRot follows player.yRot.
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
            // Read RAW key states, not rewritten input booleans.
            // LockOnMovementHandler rewrites input.up/down/left/right for
            // movement (in non-TPS it forces up=true, rest=false), which
            // would make every dodge go forward.
            int rawForward = 0;
            if (mc.options.keyUp.isDown()) rawForward += 1;
            if (mc.options.keyDown.isDown()) rawForward -= 1;

            int rawStrafe = 0;
            if (mc.options.keyLeft.isDown()) rawStrafe += 1;
            if (mc.options.keyRight.isDown()) rawStrafe -= 1;

            // Fallback to input impulse if no keyboard keys (controller
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

        arguments.putInt("direction", dodgeType);
        arguments.putFloat("yRot", angle);

        ci.cancel();
    }
}
