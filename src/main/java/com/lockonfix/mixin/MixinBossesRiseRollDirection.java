package com.lockonfix.mixin;

import com.lockonfix.compat.ControllableIntegration;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Makes Bosses'Rise's combat roll honor the player's actual key direction
 * (W/A/S/D) instead of the impulses produced by lock-on / sprint-rotate
 * input rewriting.
 *
 * <p>Bosses'Rise triggers a roll from {@code BossesRiseKeyMappings$1.m_7249_}
 * (its {@code DODGE_ROLL} keymapping override) by reading
 * {@code player.input.forwardImpulse} and {@code player.input.leftImpulse}
 * and forwarding them to {@code DodgeRollMessage} / {@code RollCap.startRoll}.
 * {@code RollCap.startRoll} then rotates the input vector by
 * {@code player.getYRot()} (the camera/look yaw) to derive a world-space
 * velocity.
 *
 * <p>Problem: in third-person, sprint-rotate's
 * {@link com.lockonfix.handler.LockOnMovementHandler} has already rewritten
 * those impulses to {@code (magnitude, 0)} so the player runs in the body's
 * facing direction with the camera decoupled. By the time BR reads them,
 * the actual key direction is lost — pressing S+D looks like "forward only"
 * to BR. Rotating that by {@code player.yRot} then produces a roll in the
 * camera-look direction every time, regardless of what the user pressed.
 *
 * <p>Fix: redirect the two {@code Input} field reads in {@code m_7249_} to
 * return raw key state instead. With the unrewritten input vector,
 * BR's existing {@code yRot} rotation produces the expected
 * camera-relative roll direction (W=forward, S+D=back-right relative to
 * camera, etc.) — matching the user's existing {@code DodgeSkillMixin}
 * behavior for EpicFight dodges.
 *
 * <p>Falls back to {@code Input.forwardImpulse}/{@code leftImpulse} when no
 * keyboard keys are held — preserves controller analog stick input via
 * Controllable, since that path leaves the impulses set without rewriting
 * keyboard booleans.
 *
 * <p>{@code require = 0} on the redirects so this mixin no-ops cleanly when
 * Bosses'Rise is not installed.
 */
@Pseudo
@Mixin(targets = "net.unusual.block_factorys_bosses.init.BossesRiseKeyMappings$1", remap = false)
public class MixinBossesRiseRollDirection {

    @Redirect(
        method = "m_7249_",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/player/Input;f_108566_:F",
            opcode = org.objectweb.asm.Opcodes.GETFIELD
        ),
        require = 0,
        remap = false
    )
    private float lockonfix$rawForward(Input input) {
        return rawDirection(input, true);
    }

    @Redirect(
        method = "m_7249_",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/player/Input;f_108567_:F",
            opcode = org.objectweb.asm.Opcodes.GETFIELD
        ),
        require = 0,
        remap = false
    )
    private float lockonfix$rawLeft(Input input) {
        return rawDirection(input, false);
    }

    private static float rawDirection(Input input, boolean forwardAxis) {
        Minecraft mc = Minecraft.getInstance();
        int raw = 0;
        if (forwardAxis) {
            if (mc.options.keyUp.isDown()) raw += 1;
            if (mc.options.keyDown.isDown()) raw -= 1;
        } else {
            if (mc.options.keyLeft.isDown()) raw += 1;
            if (mc.options.keyRight.isDown()) raw -= 1;
        }
        if (raw != 0) return raw;

        // No keyboard keys held — fall through to original impulse so a
        // controller analog stick (Controllable) still works. Controllable
        // writes input.forwardImpulse/leftImpulse without flipping the
        // keyUp/keyDown booleans.
        if (ControllableIntegration.isAnalogInput(input)) {
            return forwardAxis ? input.forwardImpulse : input.leftImpulse;
        }
        return forwardAxis ? input.forwardImpulse : input.leftImpulse;
    }
}
