package com.lockonfix.mixin;

import com.lockonfix.handler.AutoLockOnHandler;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MixinMouseHandler {

    @Shadow private double accumulatedDX;

    @Inject(method = "turnPlayer()V", at = @At("HEAD"))
    private void lockonfix$captureMouseDx(CallbackInfo ci) {
        AutoLockOnHandler.INSTANCE.recordMouseDx(this.accumulatedDX);
    }
}
