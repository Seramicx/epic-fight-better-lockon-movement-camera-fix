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

@Mixin(Player.class)
public abstract class PlayerMixin extends Entity {

    protected PlayerMixin(EntityType<? extends LivingEntity> type, Level level) {
        super(type, level);
    }

    /**
     * This fully overrides the player's picking logic to trace from the camera
     * when over-the-shoulder is active and we are NOT locked on.
     * This fixes both the visual outline and the actual block interaction.
     */
    @Override
    public @NotNull HitResult pick(double interactionRange, float partialTick, boolean stopOnFluid) {
        Minecraft mc = Minecraft.getInstance();
        
        // Only override client-side player picking when in 3rd person and not locked on
        Player self = (Player) (Object) this;
        if (self.level().isClientSide && self == mc.player && mc.options.getCameraType() == CameraType.THIRD_PERSON_BACK && !EpicFightClientHooks.isLockOnTargeting()) {
            Camera camera = mc.gameRenderer.getMainCamera();
            Vec3 cameraPos = camera.getPosition();
            Vec3 lookVec = new Vec3(camera.getLookVector());

            // Increase the interaction range by the distance between the camera and the player's eyes
            double distance = interactionRange + cameraPos.distanceTo(self.getEyePosition(partialTick));

            Vec3 end = cameraPos.add(lookVec.scale(distance));
            ClipContext.Fluid fluidCtx = stopOnFluid ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE;

            BlockHitResult blockHit = this.level().clip(new ClipContext(
                cameraPos, end,
                ClipContext.Block.OUTLINE,
                fluidCtx,
                this
            ));

            double entityReach = distance;
            if (mc.gameMode != null && mc.gameMode.hasFarPickRange()) {
                entityReach = Math.max(entityReach, 6.0D + cameraPos.distanceTo(self.getEyePosition(partialTick)));
            }

            if (blockHit.getType() != HitResult.Type.MISS) {
                entityReach = blockHit.getLocation().distanceTo(cameraPos);
            }

            Vec3 entityEnd = cameraPos.add(lookVec.scale(entityReach));
            AABB searchBox = new AABB(cameraPos, entityEnd).inflate(1.0);

            EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                this, cameraPos, entityEnd, searchBox,
                e -> !e.isSpectator() && e.isPickable() && e != this,
                entityReach * entityReach
            );

            if (entityHit != null) {
                return entityHit;
            }
            return blockHit;
        }

        // Vanilla behavior fallback
        return super.pick(interactionRange, partialTick, stopOnFluid);
    }
}
