package com.lockonfix.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import com.lockonfix.client.EpicFightClientHooks;
import com.lockonfix.compat.IntegrationRegistry;
import com.lockonfix.compat.ValkyrienSkiesIntegration;

@Mixin(Player.class)
public abstract class PlayerMixin extends Entity {

    protected PlayerMixin(EntityType<? extends LivingEntity> type, Level level) {
        super(type, level);
    }

    /**
     * Eye-origin pick geometry, laterally shifted to the camera's offset.
     *
     * <pre>
     *   cameraOffset        = cameraPos - eyePos
     *   rayTraceStartOffset = lateral component of cameraOffset
     *                         (cameraOffset projected perpendicular to lookVec)
     *   start               = eyePos + rayTraceStartOffset
     *   end                 = cameraPos + lookVec * (effectiveRange + backDistance)
     * </pre>
     *
     * <p>Why: a camera-origin ray (~4 blocks behind the player) approaches the
     * target block at a long shallow angle, so the first face the ray crosses
     * is often a side/top, not the natural front. {@code BlockItem.useOn}
     * uses that face for {@code clickedPos.relative(face)}, which would place
     * the block on the wrong side of the highlight. Starting at
     * eye+lateralOffset gives the same depth as a vanilla eye pick, so the
     * front face is hit first and placement matches the highlight.
     *
     * <p>Block break, water bucket, and the highlight outline don't read
     * face direction, so they were unaffected even with the old camera-origin
     * geometry.
     *
     * <p>The Pythagorean adjustment to {@code effectiveRange} keeps reach
     * equal to {@code interactionRange} from the eye despite the lateral
     * shift.
     */
    @Override
    public @NotNull HitResult pick(double interactionRange, float partialTick, boolean stopOnFluid) {
        Minecraft mc = Minecraft.getInstance();
        Player self = (Player) (Object) this;
        if (self.level().isClientSide && self == mc.player && mc.options.getCameraType() == CameraType.THIRD_PERSON_BACK && !EpicFightClientHooks.isLockOnTargeting()) {
            Camera camera = mc.gameRenderer.getMainCamera();
            Vec3 cameraPos = camera.getPosition();
            Vec3 lookVec = new Vec3(camera.getLookVector());
            Vec3 eyePos = self.getEyePosition(partialTick);

            // Lateral camera offset = cameraOffset minus its projection on lookVec.
            Vec3 cameraOffset = cameraPos.subtract(eyePos);
            Vec3 rayTraceStartOffset = cameraOffset.subtract(
                lookVec.scale(cameraOffset.dot(lookVec))
            );

            double interactionRangeSq = interactionRange * interactionRange;
            double offsetLenSq = rayTraceStartOffset.lengthSqr();
            double effectiveRange = interactionRange;
            if (offsetLenSq < interactionRangeSq) {
                effectiveRange = Math.sqrt(interactionRangeSq - offsetLenSq);
            }
            double rayDistance = effectiveRange + cameraOffset.distanceTo(rayTraceStartOffset);

            Vec3 startPos = eyePos.add(rayTraceStartOffset);
            Vec3 endPos = cameraPos.add(lookVec.scale(rayDistance));

            ClipContext.Fluid fluidCtx = stopOnFluid ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE;
            ClipContext clipCtx = new ClipContext(
                startPos, endPos,
                ClipContext.Block.OUTLINE,
                fluidCtx,
                this
            );
            // VS2 compat: route through ship-aware raycast when mounted on a ship.
            BlockHitResult blockHit = IntegrationRegistry.isValkyrienSkies()
                ? ValkyrienSkiesIntegration.clipIncludeShips(this.level(), clipCtx)
                : this.level().clip(clipCtx);

            // Entity pick along the same ray segment.
            double entityReach;
            if (blockHit.getType() != HitResult.Type.MISS) {
                entityReach = blockHit.getLocation().distanceTo(startPos);
            } else {
                entityReach = rayDistance;
                if (mc.gameMode != null && mc.gameMode.hasFarPickRange()) {
                    entityReach = Math.max(entityReach, 6.0D + cameraOffset.distanceTo(rayTraceStartOffset));
                }
            }

            AABB searchBox = new AABB(startPos, endPos).inflate(1.0);
            EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                this, startPos, endPos, searchBox,
                e -> !e.isSpectator() && e.isPickable() && e != this,
                entityReach * entityReach
            );

            if (entityHit != null) {
                return entityHit;
            }
            return blockHit;
        }

        return super.pick(interactionRange, partialTick, stopOnFluid);
    }
}
