package com.lockonfix.mixin;

import com.lockonfix.compat.ControllableIntegration;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

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

        if (ControllableIntegration.isAnalogInput(input)) {
            return forwardAxis ? input.forwardImpulse : input.leftImpulse;
        }
        return forwardAxis ? input.forwardImpulse : input.leftImpulse;
    }
}
