package com.lockonfix.mixin;

import net.minecraft.world.entity.player.Player;
import org.joml.Vector2d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.unusual.block_factorys_bosses.capability.entity.RollCap$RollCapHandler", remap = false)
public class MixinBossesRiseRollPreserveCamera {

    @Unique private float lockonfix$savedYRot;
    @Unique private float lockonfix$savedYRotO;
    @Unique private float lockonfix$savedYHeadRotO;

    @Inject(method = "move", at = @At("HEAD"), require = 0, remap = false)
    private void lockonfix$saveCameraRot(Player player, Vector2d motion, CallbackInfo ci) {
        lockonfix$savedYRot = player.getYRot();
        lockonfix$savedYRotO = player.yRotO;
        lockonfix$savedYHeadRotO = player.yHeadRotO;
    }

    @Inject(method = "move", at = @At("TAIL"), require = 0, remap = false)
    private void lockonfix$restoreCameraRot(Player player, Vector2d motion, CallbackInfo ci) {
        player.setYRot(lockonfix$savedYRot);
        player.yRotO = lockonfix$savedYRotO;
        player.yHeadRotO = lockonfix$savedYHeadRotO;
    }
}
