package com.lockonfix.mixin;

import net.minecraft.world.entity.player.Player;
import org.joml.Vector2d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the third-person camera decoupled from the player's body during a
 * Bosses'Rise combat roll.
 *
 * <p>{@code RollCap$RollCapHandler.move(Player, Vector2d)} is called every
 * tick while {@code isRolling()} is true, and at its tail it does:
 * <pre>
 *   player.setYRot(player.getYHeadRot());
 *   player.yRotO     = player.yBodyRotO;
 *   player.yHeadRotO = player.yBodyRotO;
 * </pre>
 * forcing the camera ({@code player.yRot}) onto the head/body direction
 * every tick. {@link com.lockonfix.handler.LockOnMovementHandler}'s sprint-
 * rotate restores {@code yRot} once on the first tick of the roll (because
 * its {@code sprintRotateActive} is still set), but BR's
 * {@code RollCap.tick} forces {@code setSprinting(false)} that same tick,
 * so on subsequent ticks sprint-rotate doesn't re-arm. With nothing
 * countering BR, {@code yRot} drifts onto the body direction and the
 * camera visibly snaps onto the roll trajectory.
 *
 * <p>This mixin captures {@code yRot}/{@code yRotO}/{@code yHeadRotO} at
 * HEAD and restores them at TAIL, so BR's velocity application,
 * {@code hasImpulse} write, and pose stay intact, but the rotation
 * fields the camera reads are unchanged. Mouse movement between ticks
 * still updates {@code yRot} normally; we only undo BR's per-tick clobber.
 *
 * <p>{@code yBodyRot} is never touched by {@code move}, so vanilla
 * {@code tickHeadTurn} continues to swing the body model toward the roll
 * velocity — the rolling animation still visually rolls in the right
 * direction.
 *
 * <p>{@code @Pseudo} so the mod still loads when Bosses'Rise is absent.
 */
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
