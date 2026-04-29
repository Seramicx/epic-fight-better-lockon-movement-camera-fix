package com.lockonfix.mixin;

import com.lockonfix.handler.LockOnMovementHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Two interventions on the local player's mount, both inside
 * {@code LivingEntity.travelRidden}:
 *
 * <ol>
 *   <li><b>Per-frame interpolation:</b> capture {@code yRotO/yBodyRotO/yHeadRotO}
 *       at HEAD and restore at RETURN, undoing the
 *       {@code yRotO = yBodyRot = yHeadRot = getYRot()} snap that subclasses
 *       like {@code AbstractHorse}, {@code Pig}, {@code Strider} perform.
 *       Lets per-frame {@code lerp(yRotO, yRot, partial)} interpolate
 *       sub-tick rotation.</li>
 *   <li><b>Mount-rotate yaw substitution:</b> when
 *       {@link LockOnMovementHandler#isMountRotateActive()}, temporarily set
 *       {@code pPlayer.yRot = mountSmoothedYaw} at HEAD so
 *       {@code tickRidden}'s {@code setRot(player.yRot, ...)} steers the mount
 *       to bodyYaw. Restore at RETURN so the player's actual yRot stays
 *       camera/event-driven (camera and any other code that reads yRot stay
 *       unaffected).</li>
 * </ol>
 *
 * <p>The yaw substitution side-steps the priority fight against BLO and
 * Epic Fight: even if those mods write {@code player.yRot} after our LOW
 * priority handlers, the mount's {@code tickRidden} runs the next tick and
 * sees our substituted value. No grace period needed; mount-rotate
 * activates immediately on lock-off.</p>
 *
 * <p>All gates are client-side and limited to mounts whose controlling
 * passenger is the local player. Server logic and other-player mounts are
 * untouched.</p>
 */
@Mixin(LivingEntity.class)
public abstract class MixinLivingEntityRiddenInterp {

    @Unique private float lockonfix$savedYRotO;
    @Unique private float lockonfix$savedYBodyRotO;
    @Unique private float lockonfix$savedYHeadRotO;
    @Unique private boolean lockonfix$shouldRestore;

    @Unique private float lockonfix$savedPlayerYRot;
    @Unique private boolean lockonfix$shouldRestorePlayerYRot;

    @Inject(method = "travelRidden", at = @At("HEAD"))
    private void lockonfix$beforeTravelRidden(Player pPlayer, Vec3 vec, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        boolean clientLocalRider = self.level().isClientSide
                && pPlayer == Minecraft.getInstance().player;

        lockonfix$shouldRestore = clientLocalRider;
        if (clientLocalRider) {
            lockonfix$savedYRotO = self.yRotO;
            lockonfix$savedYBodyRotO = self.yBodyRotO;
            lockonfix$savedYHeadRotO = self.yHeadRotO;
        }

        // Mount-rotate substitution: only when actively decoupling. Capture
        // the current player.yRot so we can put it back the moment
        // travelRidden returns. The substitution forces the mount's
        // setRot(player.yRot, ...) to use mountSmoothedYaw, bypassing any
        // other mod (BLO, Epic Fight) that may have written player.yRot
        // during the tick.
        lockonfix$shouldRestorePlayerYRot = clientLocalRider
                && LockOnMovementHandler.isMountRotateActive();
        if (lockonfix$shouldRestorePlayerYRot) {
            lockonfix$savedPlayerYRot = pPlayer.getYRot();
            pPlayer.setYRot(LockOnMovementHandler.getMountSmoothedYaw());
        }
    }

    @Inject(method = "travelRidden", at = @At("RETURN"))
    private void lockonfix$afterTravelRidden(Player pPlayer, Vec3 vec, CallbackInfo ci) {
        if (lockonfix$shouldRestorePlayerYRot) {
            pPlayer.setYRot(lockonfix$savedPlayerYRot);
            lockonfix$shouldRestorePlayerYRot = false;
        }
        if (lockonfix$shouldRestore) {
            LivingEntity self = (LivingEntity) (Object) this;
            self.yRotO = lockonfix$savedYRotO;
            self.yBodyRotO = lockonfix$savedYBodyRotO;
            self.yHeadRotO = lockonfix$savedYHeadRotO;
        }
    }
}
